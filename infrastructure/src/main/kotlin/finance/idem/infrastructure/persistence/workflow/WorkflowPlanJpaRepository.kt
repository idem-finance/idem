package finance.idem.infrastructure.persistence.workflow

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface WorkflowPlanJpaRepository : JpaRepository<WorkflowPlanDataModel, UUID> {
    fun findByIdAndTenantId(
        id: UUID,
        tenantId: UUID,
    ): WorkflowPlanDataModel?

    // Traces a settlement's matchedTransactionId back to the workflow step that posted it, if
    // any — used by ReorgReversalService to link a reorg reversal back to its originating agent
    // workflow. A transaction belongs to at most one step, so this is unambiguous.
    @Query(
        """
        SELECT DISTINCT p FROM WorkflowPlanDataModel p JOIN p.steps s
        WHERE s.transactionId = :transactionId AND p.tenantId = :tenantId
        """,
    )
    fun findByStepTransactionId(
        @Param("transactionId") transactionId: UUID,
        @Param("tenantId") tenantId: UUID,
    ): WorkflowPlanDataModel?
}
