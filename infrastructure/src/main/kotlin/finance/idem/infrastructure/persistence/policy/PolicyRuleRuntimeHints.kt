package finance.idem.infrastructure.persistence.policy

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * [PolicyRepositoryAdapter] maps native SQL query results onto [PolicyRuleDataModel] via
 * `entityManager.createNativeQuery(sql, PolicyRuleDataModel::class.java)`. This bypasses
 * Spring Data's AOT-generated repository implementations (it's not a derived/`@Query`
 * repository method), so Hibernate's reflective row-to-entity mapping needs an explicit hint.
 */
class PolicyRuleRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        hints.reflection().registerType(
            PolicyRuleDataModel::class.java,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.DECLARED_FIELDS,
        )
    }
}
