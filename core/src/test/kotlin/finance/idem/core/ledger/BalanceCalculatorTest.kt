package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.MonetaryEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BalanceCalculatorTest {

    private val now = Instant.now()
    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val otherAccountId = AccountId.generate()

    private fun assetAccount() = Account.create(
        id = accountId, tenantId = tenantId, name = "Nostro BRL",
        currency = FiatCurrency.BRL, type = AccountType.ASSET,
        createdAt = now, createdBy = "system",
    )

    private fun liabilityAccount() = Account.create(
        id = accountId, tenantId = tenantId, name = "Customer BRL Payable",
        currency = FiatCurrency.BRL, type = AccountType.LIABILITY,
        createdAt = now, createdBy = "system",
    )

    private fun brlFiat(amount: String) = FiatEntry(
        amount = MonetaryAmount.of(amount), currency = FiatCurrency.BRL, rail = PaymentRail.PIX,
    )

    private fun usdFiat(amount: String) = FiatEntry(
        amount = MonetaryAmount.of(amount), currency = FiatCurrency.USD, rail = PaymentRail.WIRE,
    )

    private fun usdcOnChain(amount: String) = OnChainEntry(
        amount = MonetaryAmount.of(amount), token = StablecoinToken.USDC,
        chainId = ChainId.EVM, txHash = "0xabc", blockNumber = 19_000_000L,
        walletAddress = "0xWallet", tokenContract = "0xContract",
    )

    private fun line(entryType: EntryType, entry: MonetaryEntry, accId: AccountId = accountId) =
        JournalLine(UUID.randomUUID(), TransactionId.generate(), accId, tenantId, entryType, entry, null, now, "system")

    private fun tx(lines: List<JournalLine>) = Transaction.create(
        id = TransactionId.generate(), tenantId = tenantId, idempotencyKey = UUID.randomUUID().toString(),
        lines = lines, occurredAt = now, createdAt = now, createdBy = "system",
    )

    @Test
    fun `empty transaction list yields zero balance`() {
        assertTrue(BalanceCalculator.compute(assetAccount(), emptyList()).isZero())
    }

    @Test
    fun `debit-normal account nets debits minus credits`() {
        val transactions = listOf(
            tx(listOf(line(EntryType.DEBIT, brlFiat("1000")), line(EntryType.CREDIT, brlFiat("1000"), otherAccountId))),
            tx(listOf(line(EntryType.CREDIT, brlFiat("400")), line(EntryType.DEBIT, brlFiat("400"), otherAccountId))),
        )

        assertEquals(MonetaryAmount.of("600"), BalanceCalculator.compute(assetAccount(), transactions))
    }

    @Test
    fun `credit-normal account nets credits minus debits`() {
        val transactions = listOf(
            tx(listOf(line(EntryType.CREDIT, brlFiat("500")), line(EntryType.DEBIT, brlFiat("500"), otherAccountId))),
        )

        assertEquals(MonetaryAmount.of("500"), BalanceCalculator.compute(liabilityAccount(), transactions))
    }

    @Test
    fun `lines for other accounts are excluded`() {
        val transactions = listOf(
            tx(listOf(line(EntryType.DEBIT, brlFiat("1000")), line(EntryType.CREDIT, brlFiat("1000"), otherAccountId))),
        )

        assertEquals(MonetaryAmount.of("1000"), BalanceCalculator.compute(assetAccount(), transactions))
    }

    @Test
    fun `on-chain entries on the account are excluded`() {
        val onChainEntry = usdcOnChain("180.00")
        val transactions = listOf(
            tx(listOf(line(EntryType.DEBIT, onChainEntry), line(EntryType.CREDIT, onChainEntry, otherAccountId))),
        )

        assertTrue(BalanceCalculator.compute(assetAccount(), transactions).isZero())
    }

    @Test
    fun `fiat entries in a different currency than the account are excluded`() {
        val transactions = listOf(
            tx(listOf(line(EntryType.DEBIT, usdFiat("100")), line(EntryType.CREDIT, usdFiat("100"), otherAccountId))),
        )

        assertTrue(BalanceCalculator.compute(assetAccount(), transactions).isZero())
    }
}
