package finance.idem.api.internal

import finance.idem.application.chain.QuickNodeWebhookPort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/webhooks")
class QuickNodeWebhookController(private val port: QuickNodeWebhookPort) {

    @PostMapping("/quicknode")
    fun receive(
        @RequestHeader(value = "X-QN-Signature", required = false) signature: String?,
        @RequestBody rawBody: String,
    ): ResponseEntity<Void> = port.handle(signature, rawBody).fold(
        onSuccess = { ResponseEntity.ok().build() },
        onFailure = { ResponseEntity.status(HttpStatus.UNAUTHORIZED).build() },
    )
}
