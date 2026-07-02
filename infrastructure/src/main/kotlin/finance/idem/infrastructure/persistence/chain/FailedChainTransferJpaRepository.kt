package finance.idem.infrastructure.persistence.chain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface FailedChainTransferJpaRepository : JpaRepository<FailedChainTransferDataModel, UUID> {
    /**
     * `ON CONFLICT (idempotency_key) DO NOTHING` — a given transfer is evaluated at most once
     * under normal checkpoint-advance semantics, but the upsert keeps [save] idempotent.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO failed_chain_transfers (
                id, chain_key, source, idempotency_key, tx_hash, block_number, tenant_id,
                wallet_address, token_contract, debit_account_id, credit_account_id,
                token, amount, error_message, created_at
            ) VALUES (
                CAST(:id AS uuid), :chainKey, :source, :idempotencyKey, :txHash, :blockNumber, CAST(:tenantId AS uuid),
                :walletAddress, :tokenContract, CAST(:debitAccountId AS uuid), CAST(:creditAccountId AS uuid),
                :token, :amount, :errorMessage, CAST(:createdAt AS timestamptz)
            )
            ON CONFLICT (idempotency_key) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("id") id: String,
        @Param("chainKey") chainKey: String,
        @Param("source") source: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("txHash") txHash: String,
        @Param("blockNumber") blockNumber: Long,
        @Param("tenantId") tenantId: String,
        @Param("walletAddress") walletAddress: String,
        @Param("tokenContract") tokenContract: String,
        @Param("debitAccountId") debitAccountId: String,
        @Param("creditAccountId") creditAccountId: String,
        @Param("token") token: String,
        @Param("amount") amount: BigDecimal,
        @Param("errorMessage") errorMessage: String,
        @Param("createdAt") createdAt: Instant,
    )
}
