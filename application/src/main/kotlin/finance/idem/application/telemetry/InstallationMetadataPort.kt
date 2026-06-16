package finance.idem.application.telemetry

import java.util.UUID

interface InstallationMetadataPort {
    fun getOrCreateId(): UUID
}
