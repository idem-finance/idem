package finance.idem.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumsTest {

    @Test
    fun `FiatCurrency contains expected values`() {
        val values = FiatCurrency.entries
        assertEquals(4, values.size)
        assertTrue(FiatCurrency.BRL in values)
        assertTrue(FiatCurrency.USD in values)
        assertTrue(FiatCurrency.MXN in values)
        assertTrue(FiatCurrency.EUR in values)
    }

    @Test
    fun `StablecoinToken contains expected values`() {
        val values = StablecoinToken.entries
        assertEquals(4, values.size)
        assertTrue(StablecoinToken.USDC in values)
        assertTrue(StablecoinToken.USDT in values)
        assertTrue(StablecoinToken.BRZ in values)
        assertTrue(StablecoinToken.PYUSD in values)
    }

    @Test
    fun `ChainId contains expected values`() {
        val values = ChainId.entries
        assertEquals(3, values.size)
        assertTrue(ChainId.EVM in values)
        assertTrue(ChainId.SOLANA in values)
        assertTrue(ChainId.TRON in values)
    }

    @Test
    fun `PaymentRail contains expected values`() {
        val values = PaymentRail.entries
        assertEquals(5, values.size)
        assertTrue(PaymentRail.ACH in values)
        assertTrue(PaymentRail.WIRE in values)
        assertTrue(PaymentRail.PIX in values)
        assertTrue(PaymentRail.SWIFT in values)
        assertTrue(PaymentRail.SEPA in values)
    }

    @Test
    fun `EntryType contains DEBIT and CREDIT`() {
        val values = EntryType.entries
        assertEquals(2, values.size)
        assertTrue(EntryType.DEBIT in values)
        assertTrue(EntryType.CREDIT in values)
    }
}
