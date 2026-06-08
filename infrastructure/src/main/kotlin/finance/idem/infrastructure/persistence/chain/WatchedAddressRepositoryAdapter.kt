package finance.idem.infrastructure.persistence.chain

import finance.idem.core.StablecoinToken
import finance.idem.infrastructure.chain.WatchedAddress
import finance.idem.infrastructure.chain.WatchedAddressRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class WatchedAddressRepositoryAdapter(
    private val jpaRepository: WatchedAddressJpaRepository,
) : WatchedAddressRepository {

    @Transactional(readOnly = true)
    override fun findByChainKey(chainKey: String): List<WatchedAddress> =
        jpaRepository.findByChainKey(chainKey).map { it.toDomain() }
}

private fun WatchedAddressDataModel.toDomain() = WatchedAddress(
    chainKey = chainKey,
    walletAddress = walletAddress,
    tokenContract = tokenContract,
    token = StablecoinToken.valueOf(token),
    tenantId = tenantId.toString(),
    debitAccountId = debitAccountId.toString(),
    creditAccountId = creditAccountId.toString(),
)
