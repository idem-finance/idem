package finance.idem.api.ledger

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * [MonetaryEntryRequestDto] and [MonetaryEntryResponse] use `@JsonSubTypes` polymorphism.
 * Their subtypes never appear directly as a controller parameter/return type — only the
 * sealed parent does — so Spring's AOT endpoint-signature inference never discovers them.
 * Jackson still needs reflective constructor access to deserialize/serialize each subtype.
 */
class MonetaryEntryDtoRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        listOf(
            MonetaryEntryRequestDto::class.java,
            FiatEntryDto::class.java,
            OnChainEntryDto::class.java,
            MonetaryEntryResponse::class.java,
            FiatEntryResponse::class.java,
            OnChainEntryResponse::class.java,
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
