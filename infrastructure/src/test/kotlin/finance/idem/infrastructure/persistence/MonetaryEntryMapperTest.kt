package finance.idem.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonetaryEntryMapperTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `OnChainEntry toColumns-toDomain round-trip preserves fromAddress`() {
        val entry =
            OnChainEntry(
                amount = MonetaryAmount.of("100.000000"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                txHash = "0xabc123",
                blockNumber = 19_000_000L,
                walletAddress = "0xWallet",
                tokenContract = "0xContract",
                fromAddress = "0xfromsender",
            )

        val domain = entry.toColumns(mapper).toDomain(mapper) as OnChainEntry

        assertEquals("0xfromsender", domain.fromAddress)
    }

    @Test
    fun `toDomain deserializes monetary_entry_data missing the fromAddress key as null`() {
        val columns =
            MonetaryEntryColumns(
                amount = BigDecimal("100.000000"),
                currency = StablecoinToken.USDC.name,
                monetaryEntryType = "ONCHAIN",
                monetaryEntryData =
                    """
                    {"chainId":"EVM","txHash":"0xabc123","blockNumber":19000000,"walletAddress":"0xWallet","tokenContract":"0xContract"}
                    """.trimIndent(),
            )

        val domain = columns.toDomain(mapper) as OnChainEntry

        assertNull(domain.fromAddress)
    }
}
