package finance.idem.infrastructure.security

import finance.idem.infrastructure.observability.TraceIdFilter
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WebSecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        apiKeyAuthFilter: ApiKeyAuthFilter,
        mcpSseAuthBridgeFilter: McpSseAuthBridgeFilter,
        traceIdFilter: TraceIdFilter,
    ): SecurityFilterChain {
        return http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                // Internal webhook endpoints are authenticated by HMAC signature, not API keys.
                auth.requestMatchers("/internal/**").permitAll()
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                auth.requestMatchers("/actuator/**").hasAuthority("ADMIN")
                auth.requestMatchers("/error").permitAll()
                auth.requestMatchers("/swagger-ui/**").permitAll()
                auth.requestMatchers("/v3/api-docs/**").permitAll()
                auth.anyRequest().authenticated()
            }
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            // MCP bridge runs after API key filter: picks up session auth for POST /mcp/messages
            // that arrive without an X-API-Key header (mcp-remote only sends it on GET /sse).
            .addFilterAfter(mcpSseAuthBridgeFilter, ApiKeyAuthFilter::class.java)
            .addFilterBefore(traceIdFilter, ApiKeyAuthFilter::class.java)
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint { _, response, _ ->
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.status = 401
                    response.writer.write("""{"code":"unauthorized","message":"Missing or invalid API key"}""")
                }
            }
            .build()
    }
}
