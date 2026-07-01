package finance.idem.core

enum class PaymentRail {
    /** US domestic batch clearing (1–3 days) */
    ACH,

    /** US domestic real-time gross settlement via Fedwire/CHIPS (same-day) */
    WIRE,

    /** Brazilian instant payment system (< 10 s) */
    PIX,

    /** International bank messaging through correspondent banks (1–2 days) */
    SWIFT,

    /** European payment system — instant or next-day */
    SEPA,
}
