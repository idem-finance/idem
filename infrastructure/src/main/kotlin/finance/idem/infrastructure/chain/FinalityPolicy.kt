package finance.idem.infrastructure.chain

object FinalityPolicy {
    /** [finance.idem.core.ledger.Transaction.createdBy] value used by the real-time Alchemy
     * webhook path — the only source gated behind [finance.idem.core.ledger.EntryStatus
     * .WATCHING] pending finality confirmation. All other sources (chain-recovery, tron-poller)
     * only ever post transfers already past their chain's finality bound, so they settle
     * immediately. */
    const val WEBHOOK_SOURCE = "alchemy-webhook"
}
