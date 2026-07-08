package finance.idem.sdk.model

data class CreateAccountRequest(
    val name: String,
    val description: String? = null,
    val currency: FiatCurrency,
    val type: AccountType,
)
