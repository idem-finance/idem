package finance.idem.infrastructure.persistence.telemetry

import org.springframework.data.jpa.repository.JpaRepository

interface InstallationMetadataJpaRepository : JpaRepository<InstallationMetadataDataModel, Int>
