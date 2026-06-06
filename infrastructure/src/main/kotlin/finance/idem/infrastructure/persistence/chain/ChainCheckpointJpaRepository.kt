package finance.idem.infrastructure.persistence.chain

import org.springframework.data.jpa.repository.JpaRepository

interface ChainCheckpointJpaRepository : JpaRepository<ChainCheckpointDataModel, String>
