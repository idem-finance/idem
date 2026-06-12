package finance.idem.api.ledger

import finance.idem.core.ChainId
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.OnChainEntry
import java.math.BigDecimal

data class OnChainEntryResponseDto(
    val amount: BigDecimal,
    val token: StablecoinToken,
    val chainId: ChainId,
    val txHash: String,
    val blockNumber: Long,
    val walletAddress: String,
    val tokenContract: String,
    val fromAddress: String? = null,
) : MonetaryEntryResponseDto() {
    companion object {
        fun from(entry: OnChainEntry) = OnChainEntryResponseDto(
            amount = entry.amount.value,
            token = entry.token,
            chainId = entry.chainId,
            txHash = entry.txHash,
            blockNumber = entry.blockNumber,
            walletAddress = entry.walletAddress,
            tokenContract = entry.tokenContract,
            fromAddress = entry.fromAddress,
        )
    }
}
