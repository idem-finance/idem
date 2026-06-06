package finance.idem.infrastructure.chain

import finance.idem.core.StablecoinToken

data class WatchedAddress(
    val chainKey: String,
    val walletAddress: String,
    val tokenContract: String,
    val token: StablecoinToken,
    val tenantId: String,
    val debitAccountId: String,
    val creditAccountId: String,
)
