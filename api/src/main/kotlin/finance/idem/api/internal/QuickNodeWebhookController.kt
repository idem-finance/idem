package finance.idem.api.internal

import finance.idem.application.chain.QuickNodeWebhookUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/webhooks")
class QuickNodeWebhookController(private val quickNodeWebhookUseCase: QuickNodeWebhookUseCase) {

    @PostMapping("/quicknode")
    fun receive(
        @RequestHeader(value = "X-QN-Signature", required = false) signature: String?,
        @RequestHeader(value = "X-QN-Nonce", required = false) nonce: String?,
        @RequestHeader(value = "X-QN-Timestamp", required = false) timestamp: String?,
        @RequestBody rawBody: String,
    ): ResponseEntity<Void> = quickNodeWebhookUseCase.handle(signature, nonce, timestamp, rawBody).fold(
        onSuccess = { ResponseEntity.ok().build() },
        onFailure = { ResponseEntity.status(HttpStatus.UNAUTHORIZED).build() },
    )
}
