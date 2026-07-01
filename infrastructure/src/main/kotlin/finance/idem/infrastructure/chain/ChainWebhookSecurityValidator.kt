package finance.idem.infrastructure.chain

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Fails fast at startup in non-dev environments when webhook signing keys are absent.
 * Dev mode (profile "dev") intentionally allows blank keys so local testing works
 * without Alchemy/QuickNode credentials — both services skip HMAC validation when
 * their respective key is blank.
 */
@Component
@Profile("!dev")
class ChainWebhookSecurityValidator(
    private val config: ChainConfig,
) {
    @PostConstruct
    fun validate() {
        check(config.alchemyWebhookSigningKey.isNotBlank()) {
            "idem.chain.alchemy-webhook-signing-key must not be blank — set ALCHEMY_WEBHOOK_SIGNING_KEY in production"
        }
        check(config.quicknodeWebhookSecret.isNotBlank()) {
            "idem.chain.quicknode-webhook-secret must not be blank — set QUICKNODE_WEBHOOK_SECRET in production"
        }
    }
}
