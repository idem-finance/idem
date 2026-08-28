package finance.idem.api.internal

import finance.idem.application.billing.BillingWebhookUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/webhooks")
class BillingWebhookController(
    private val billingWebhookUseCase: BillingWebhookUseCase,
) {
    @PostMapping("/billing")
    fun receive(
        @RequestHeader(value = "X-Idem-Signature", required = false) signature: String?,
        @RequestBody rawBody: String,
    ): ResponseEntity<Void> =
        billingWebhookUseCase.handle(signature, rawBody).fold(
            onSuccess = { ResponseEntity.ok().build() },
            onFailure = { ResponseEntity.status(HttpStatus.UNAUTHORIZED).build() },
        )
}
