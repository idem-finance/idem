package finance.idem.infrastructure.persistence.chain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WatchedAddressJpaRepository : JpaRepository<WatchedAddressDataModel, UUID> {
    fun findByChainKey(chainKey: String): List<WatchedAddressDataModel>
}
