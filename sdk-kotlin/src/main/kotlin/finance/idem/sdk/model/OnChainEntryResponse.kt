package finance.idem.sdk.model

import java.math.BigDecimal

data class OnChainEntryResponse(
    val amount: BigDecimal,
    val token: StablecoinToken,
    val chainId: ChainId,
    val txHash: String,
    val blockNumber: Long,
    val walletAddress: String,
    val tokenContract: String,
    val fromAddress: String? = null,
) : MonetaryEntryResponse()
