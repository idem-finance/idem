package finance.idem

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {

    private val modules = ApplicationModules.of(IdemApplication::class.java)

    @Test
    fun documentsModuleStructure() {
        modules.forEach { println(it) }
    }

    @Test
    fun coreHasNoFrameworkDependencies() {
        val classes = ClassFileImporter().importPackages("finance.idem.core")

        noClasses()
            .that().resideInAPackage("finance.idem.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "org.apache.kafka..",
            )
            .check(classes)
    }

    @Test
    fun applicationLayerHasNoPersistenceOrWebDependencies() {
        val classes = ClassFileImporter().importPackages("finance.idem.application")

        noClasses()
            .that().resideInAPackage("finance.idem.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.data..",
                "jakarta.persistence..",
                "org.springframework.web..",
                "org.springframework.http..",
            )
            .check(classes)
    }

    @Test
    fun coreDoesNotDependOnUpperLayers() {
        val classes = ClassFileImporter().importPackages("finance.idem.core")

        noClasses()
            .that().resideInAPackage("finance.idem.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "finance.idem.application..",
                "finance.idem.infrastructure..",
                "finance.idem.api..",
                "finance.idem.mcp..",
            )
            .check(classes)
    }

    @Test
    fun applicationDoesNotDependOnInfrastructureOrApi() {
        val classes = ClassFileImporter().importPackages("finance.idem.application")

        noClasses()
            .that().resideInAPackage("finance.idem.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "finance.idem.infrastructure..",
                "finance.idem.api..",
            )
            .check(classes)
    }
}
