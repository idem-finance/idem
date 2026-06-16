package finance.idem.application.telemetry

interface TelemetryStatsPort {
    fun tenantCount(): Long
    fun journalLineCount(): Long
}
