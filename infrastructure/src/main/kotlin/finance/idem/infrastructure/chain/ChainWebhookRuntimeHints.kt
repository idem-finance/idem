package finance.idem.infrastructure.chain

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * [AlchemyWebhookService] and [QuickNodeWebhookService] receive the webhook body as a raw
 * `String` (so the signature can be verified before parsing) and only then call
 * `objectMapper.readValue<T>(rawBody)`. Because the payload type never appears as a
 * controller parameter type, Spring MVC's AOT endpoint-signature inference never sees it —
 * these reified-generic deserialization targets need explicit hints.
 */
class ChainWebhookRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        listOf(
            AlchemyWebhookPayload::class.java,
            AlchemyWebhookEvent::class.java,
            AlchemyActivity::class.java,
            AlchemyRawContract::class.java,
            AlchemyLog::class.java,
            QuickNodeWebhookPayload::class.java,
            QuickNodeStreamPayload::class.java,
            QuickNodeStreamMetadata::class.java,
        ).forEach { type ->
            hints.reflection().registerType(
                type,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.DECLARED_FIELDS,
            )
        }
    }
}
