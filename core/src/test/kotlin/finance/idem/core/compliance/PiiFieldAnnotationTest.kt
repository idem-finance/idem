package finance.idem.core.compliance

import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PiiFieldAnnotationTest {
    private fun piiField(
        clazz: Class<*>,
        fieldName: String,
    ): PiiField {
        val field =
            clazz.declaredFields.firstOrNull { it.name == fieldName }
                ?: error("Field '$fieldName' not found on ${clazz.simpleName}")
        return field.getAnnotation(PiiField::class.java)
            ?: error("@PiiField missing on ${clazz.simpleName}.$fieldName")
    }

    @Test
    fun `NaturalPerson firstName is tagged FULL_NAME with default 7-year retention`() {
        val ann = piiField(NaturalPerson::class.java, "firstName")
        assertEquals(PiiCategory.FULL_NAME, ann.category)
        assertEquals(7, ann.retentionYears)
    }

    @Test
    fun `NaturalPerson lastName is tagged FULL_NAME`() {
        val ann = piiField(NaturalPerson::class.java, "lastName")
        assertEquals(PiiCategory.FULL_NAME, ann.category)
    }

    @Test
    fun `NaturalPerson dateOfBirth is tagged DATE_OF_BIRTH`() {
        val ann = piiField(NaturalPerson::class.java, "dateOfBirth")
        assertEquals(PiiCategory.DATE_OF_BIRTH, ann.category)
    }

    @Test
    fun `NaturalPerson nationalId is tagged DOCUMENT_NUMBER`() {
        val ann = piiField(NaturalPerson::class.java, "nationalId")
        assertEquals(PiiCategory.DOCUMENT_NUMBER, ann.category)
    }

    @Test
    fun `NaturalPerson country has no PiiField annotation`() {
        val field = NaturalPerson::class.java.declaredFields.first { it.name == "country" }
        val ann = field.getAnnotation(PiiField::class.java)
        assertEquals(null, ann, "country is not PII — ISO 3166-1 code is public data")
    }

    @Test
    fun `LegalPerson name is tagged FULL_NAME`() {
        val ann = piiField(LegalPerson::class.java, "name")
        assertEquals(PiiCategory.FULL_NAME, ann.category)
    }

    @Test
    fun `LegalPerson registrationNumber is tagged DOCUMENT_NUMBER`() {
        val ann = piiField(LegalPerson::class.java, "registrationNumber")
        assertEquals(PiiCategory.DOCUMENT_NUMBER, ann.category)
    }

    @Test
    fun `VaspTransferParty accountNumber is tagged FINANCIAL_DATA`() {
        val ann = piiField(VaspTransferParty::class.java, "accountNumber")
        assertEquals(PiiCategory.FINANCIAL_DATA, ann.category)
    }

    @Test
    fun `VaspTransferParty vaspDid has no PiiField annotation`() {
        val field = VaspTransferParty::class.java.declaredFields.first { it.name == "vaspDid" }
        val ann = field.getAnnotation(PiiField::class.java)
        assertEquals(null, ann, "vaspDid is pseudonymous — not directly PII")
    }

    @Test
    fun `PiiField default retentionYears is 7`() {
        val default = PiiField::class.java.getMethod("retentionYears").defaultValue
        assertEquals(7, default)
    }
}
