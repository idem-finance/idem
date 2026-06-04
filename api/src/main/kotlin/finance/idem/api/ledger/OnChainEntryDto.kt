package finance.idem.api.ledger

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.OnChainEntry
import java.math.BigDecimal

data class OnChainEntryDto(
    val amount: BigDecimal,
    val token: StablecoinToken,
    val chainId: ChainId,
    val txHash: String,
    val blockNumber: Long,
    val walletAddress: String,
    val tokenContract: String,
) : MonetaryEntryRequestDto() {
    override fun toDomain() = OnChainEntry(
        amount = MonetaryAmount.of(amount),
        token = token,
        chainId = chainId,
        txHash = txHash,
        blockNumber = blockNumber,
        walletAddress = walletAddress,
        tokenContract = tokenContract,
    )
}
