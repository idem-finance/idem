package finance.idem.application.settlement

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId

data class RegisterSettlementCommand(
    val tenantId: TenantId,
    val accountId: AccountId,
    val amount: MonetaryAmount,
    val token: StablecoinToken,
    val chainId: ChainId,
    val walletAddress: String,
    val expectedFromAddress: String?,
    val createdBy: String,
    val idempotencyKey: String,
)
