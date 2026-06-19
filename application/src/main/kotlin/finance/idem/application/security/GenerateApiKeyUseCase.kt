package finance.idem.application.security

interface GenerateApiKeyUseCase {
    fun execute(cmd: GenerateApiKeyCommand): Result<GeneratedApiKey>
}
