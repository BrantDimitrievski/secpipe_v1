package ca.carleton.secpipe.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.ids.Fingerprints;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeBaseTest {

    private final KnowledgeBase kb = KnowledgeBase.load();

    @Test
    void cwe23MapsToFamily22() {
        assertThat(kb.family("CWE-23")).isEqualTo("CWE-22");
        assertThat(kb.family("CWE-22")).isEqualTo("CWE-22");
    }

    @Test
    void everyFamilyHasRemediationDescriptionAndImpactClass() {
        assertThat(kb.familyIds()).containsAll(Set.of("CWE-22", "CWE-78", "CWE-89", "CWE-327", "CWE-328"));
        for (String id : kb.familyIds()) {
            assertThat(kb.remediation(id)).as("remediation of %s", id).get().asString().isNotBlank();
            assertThat(kb.impactClass(id)).as("impact class of %s", id).isPresent();
            assertThat(kb.familyInfo(id).orElseThrow().description()).as("description of %s", id).isNotBlank();
        }
    }

    @Test
    void familyMappingAgreesWithFingerprintFamilies() {
        for (Map.Entry<String, Set<String>> entry : Fingerprints.CWE_FAMILIES.entrySet()) {
            for (String member : entry.getValue()) {
                assertThat(kb.family(member)).as("family of %s", member).isEqualTo(entry.getKey());
                assertThat(kb.family(member)).isEqualTo(Fingerprints.cweFamily(member));
            }
        }
    }

    @Test
    void impactClassesOfTheScannedFamilies() {
        assertThat(kb.impactClass("CWE-89")).contains(ImpactClass.INJECTION);
        assertThat(kb.impactClass("CWE-78")).contains(ImpactClass.INJECTION);
        assertThat(kb.impactClass("CWE-22")).contains(ImpactClass.FILE_ACCESS);
        assertThat(kb.impactClass("CWE-327")).contains(ImpactClass.CRYPTOGRAPHIC_WEAKNESS);
        assertThat(kb.impactClass("CWE-328")).contains(ImpactClass.CRYPTOGRAPHIC_WEAKNESS);
    }

    @Test
    void memberCweResolvesToItsFamilyRemediation() {
        assertThat(kb.remediation("CWE-916")).isEqualTo(kb.remediation("CWE-328"));
        assertThat(kb.remediation("CWE-89")).get().asString().contains("PreparedStatement");
    }

    @Test
    void unknownCweIsItsOwnFamilyWithNoKnowledge() {
        assertThat(kb.family("CWE-9999")).isEqualTo("CWE-9999");
        assertThat(kb.remediation("CWE-9999")).isEmpty();
        assertThat(kb.impactClass("CWE-9999")).isEmpty();
    }

    @Test
    void normalizesCweSpellings() {
        assertThat(KnowledgeBase.normalizeCwe("cwe-023")).contains("CWE-23");
        assertThat(KnowledgeBase.normalizeCwe("CWE_89")).contains("CWE-89");
        assertThat(KnowledgeBase.normalizeCwe("CWE-22: Path Traversal")).contains("CWE-22");
        assertThat(KnowledgeBase.normalizeCwe("78")).contains("CWE-78");
        assertThat(KnowledgeBase.normalizeCwe("not a cwe")).isEmpty();
        assertThat(KnowledgeBase.normalizeCwe(null)).isEmpty();
        assertThat(kb.family("cwe-23")).isEqualTo("CWE-22");
        assertThat(kb.family("not a cwe")).isEqualTo("not a cwe");
    }

    @Test
    void loadIsSharedAndImmutable() {
        assertThat(KnowledgeBase.load()).isSameAs(kb);
        assertThat(kb.familyInfo("CWE-22").orElseThrow().members()).contains("CWE-23", "CWE-36", "CWE-73");
    }
}
