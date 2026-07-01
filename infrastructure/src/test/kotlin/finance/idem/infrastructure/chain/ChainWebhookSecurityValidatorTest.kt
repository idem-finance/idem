package finance.idem.infrastructure.chain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class ChainWebhookSecurityValidatorTest {
    /**
     * Minimal config that exposes a [ChainConfig] bean so [ChainWebhookSecurityValidator]
     * can be wired — mirrors how [EvmChainReaderFactory] registers it in production.
     */
    @Configuration
    class TestChainConfig(
        private val config: ChainConfig,
    ) {
        @Bean
        fun chainConfig() = config
    }

    private fun runnerWith(
        alchemyKey: String,
        quicknodeSecret: String,
    ) = ApplicationContextRunner()
        .withBean(ChainConfig::class.java, {
            ChainConfig(alchemyWebhookSigningKey = alchemyKey, quicknodeWebhookSecret = quicknodeSecret)
        })
        .withUserConfiguration(ChainWebhookSecurityValidator::class.java)

    @Test
    fun `context fails when alchemy signing key is blank`() {
        runnerWith(alchemyKey = "", quicknodeSecret = "some-secret")
            .run { context ->
                assertThat(context).hasFailed()
                // BeanCreationException wraps the IllegalStateException from @PostConstruct
                assertThat(context.startupFailure)
                    .rootCause()
                    .hasMessageContaining("ALCHEMY_WEBHOOK_SIGNING_KEY")
            }
    }

    @Test
    fun `context fails when quicknode secret is blank`() {
        runnerWith(alchemyKey = "some-key", quicknodeSecret = "")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .rootCause()
                    .hasMessageContaining("QUICKNODE_WEBHOOK_SECRET")
            }
    }

    @Test
    fun `context starts when both keys are non-blank`() {
        runnerWith(alchemyKey = "alchemy-key", quicknodeSecret = "quicknode-secret")
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }
}
