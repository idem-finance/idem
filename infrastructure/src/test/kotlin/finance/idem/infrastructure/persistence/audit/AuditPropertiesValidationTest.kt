package finance.idem.infrastructure.persistence.audit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class AuditPropertiesValidationTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(AuditConfig::class.java)

    @Test
    fun `context fails to start when hmac-secret is blank`() {
        runner
            .withPropertyValues("idem.audit.hmac-secret=")
            .run { context ->
                assertThat(context).hasFailed()
                // BindException wraps BindValidationException when @NotBlank is violated
                assertThat(context.startupFailure?.cause)
                    .isInstanceOf(BindException::class.java)
            }
    }

    @Test
    fun `context starts when hmac-secret is non-blank`() {
        runner
            .withPropertyValues("idem.audit.hmac-secret=some-strong-test-secret")
            .run { context ->
                assertThat(context).hasNotFailed()
                val props = context.getBean(AuditProperties::class.java)
                assertThat(props.hmacSecret).isEqualTo("some-strong-test-secret")
            }
    }
}
