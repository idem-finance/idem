package finance.idem.infrastructure.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.compliance.LegalPerson
import finance.idem.core.compliance.NaturalPerson
import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.compliance.TravelRuleRepository
import finance.idem.core.compliance.VaspTransferParty
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Component
class TravelRuleRepositoryAdapter(
    private val jpaRepository: TravelRuleDataJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) : TravelRuleRepository {

    private fun setTenantId(tenantId: TenantId) {
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
            .executeUpdate()
    }

    @Transactional
    override fun save(data: TravelRuleData, tenantId: TenantId): TravelRuleData {
        setTenantId(tenantId)
        jpaRepository.save(
            TravelRuleDataDataModel(
                id = UUID.randomUUID(),
                tenantId = tenantId.value,
                transferId = data.transferId,
                originator = objectMapper.writeValueAsString(data.originator.toJson()),
                beneficiary = objectMapper.writeValueAsString(data.beneficiary.toJson()),
                transferAmount = data.transferAmount.value,
                transferAsset = data.transferAsset.name,
                threshold = data.threshold.value,
                createdAt = Instant.now(),
            )
        )
        return data
    }

    @Transactional(readOnly = true)
    override fun findByTransferId(transferId: String, tenantId: TenantId): TravelRuleData? {
        setTenantId(tenantId)
        return jpaRepository.findByTransferIdAndTenantId(transferId, tenantId.value)?.toDomain()
    }

    private fun TravelRuleDataDataModel.toDomain(): TravelRuleData =
        TravelRuleData(
            transferId = transferId,
            originator = objectMapper.readValue(originator, PartyJson::class.java).toDomain(),
            beneficiary = objectMapper.readValue(beneficiary, PartyJson::class.java).toDomain(),
            transferAmount = MonetaryAmount.of(transferAmount),
            transferAsset = StablecoinToken.valueOf(transferAsset),
            threshold = MonetaryAmount.of(threshold),
        )

    private fun VaspTransferParty.toJson() = PartyJson(
        naturalPerson = naturalPerson?.let { p ->
            NaturalPersonJson(
                firstName = p.firstName,
                lastName = p.lastName,
                dateOfBirth = p.dateOfBirth.toString(),
                nationalId = p.nationalId,
                country = p.country,
            )
        },
        legalPerson = legalPerson?.let { p ->
            LegalPersonJson(
                name = p.name,
                registrationNumber = p.registrationNumber,
                country = p.country,
            )
        },
        accountNumber = accountNumber,
        vaspDid = vaspDid,
    )

    private fun PartyJson.toDomain() = VaspTransferParty(
        naturalPerson = naturalPerson?.let { p ->
            NaturalPerson(
                firstName = p.firstName,
                lastName = p.lastName,
                dateOfBirth = LocalDate.parse(p.dateOfBirth),
                nationalId = p.nationalId,
                country = p.country,
            )
        },
        legalPerson = legalPerson?.let { p ->
            LegalPerson(
                name = p.name,
                registrationNumber = p.registrationNumber,
                country = p.country,
            )
        },
        accountNumber = accountNumber,
        vaspDid = vaspDid,
    )

    private data class PartyJson(
        val naturalPerson: NaturalPersonJson? = null,
        val legalPerson: LegalPersonJson? = null,
        val accountNumber: String,
        val vaspDid: String,
    )

    private data class NaturalPersonJson(
        val firstName: String,
        val lastName: String,
        val dateOfBirth: String,
        val nationalId: String? = null,
        val country: String,
    )

    private data class LegalPersonJson(
        val name: String,
        val registrationNumber: String,
        val country: String,
    )
}
