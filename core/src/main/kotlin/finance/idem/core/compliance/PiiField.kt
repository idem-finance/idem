package finance.idem.core.compliance

enum class PiiCategory {
    DOCUMENT_NUMBER,
    FULL_NAME,
    DATE_OF_BIRTH,
    EMAIL,
    PHONE,
    ADDRESS,
    FINANCIAL_DATA,
}

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class PiiField(
    val category: PiiCategory,
    val retentionYears: Int = 7,
)
