package finance.idem.api.internal

import finance.idem.application.chain.AlchemyWebhookUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/webhooks")
class AlchemyWebhookController(
    private val alchemyWebhookUseCase: AlchemyWebhookUseCase,
) {
    @PostMapping("/alchemy")
    fun receive(
        @RequestHeader(value = "X-Alchemy-Signature", required = false) signature: String?,
        @RequestBody rawBody: String,
    ): ResponseEntity<Void> =
        alchemyWebhookUseCase.handle(signature, rawBody).fold(
            onSuccess = { ResponseEntity.ok().build() },
            onFailure = { ResponseEntity.status(HttpStatus.UNAUTHORIZED).build() },
        )
}
