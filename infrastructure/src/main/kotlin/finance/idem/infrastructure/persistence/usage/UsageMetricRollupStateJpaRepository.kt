package finance.idem.infrastructure.persistence.usage

import org.springframework.data.jpa.repository.JpaRepository

interface UsageMetricRollupStateJpaRepository : JpaRepository<UsageMetricRollupStateDataModel, Short>
