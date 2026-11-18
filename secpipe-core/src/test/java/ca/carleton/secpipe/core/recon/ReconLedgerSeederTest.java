package ca.carleton.secpipe.core.recon;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.ids.CoverageIds;
import ca.carleton.secpipe.contracts.json.LedgerCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.core.ledger.LedgerInvariants;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReconLedgerSeederTest {

    private static final List<LedgerUnit> UNITS = ReconNode.run(ReconFixtures.shopSamples(), false).units();

    @Test
    void seedsOneUnitPerFamilyAndEntrySurfacePlusSinkOnlyFiles() {
        // 14 servlet entry surfaces x 5 families, plus library sink files with no entry surface:
        // FileChecksum (CWE-22, CWE-328), PasswordHasher (CWE-328), ConfigEncryptor and BackupEncryptor (CWE-327).
        assertThat(UNITS).hasSize(14 * 5 + 5);
        assertThat(UNITS).filteredOn(u -> u.canonicalRefs().surface().endsWith("#library-code"))
                .extracting(u -> u.startingPaths().get(0).replaceAll(".*/", "") + " " + u.cwe().orElseThrow())
                .containsExactlyInAnyOrder("FileChecksum.java CWE-22", "FileChecksum.java CWE-328",
                        "PasswordHasher.java CWE-328", "ConfigEncryptor.java CWE-327", "BackupEncryptor.java CWE-327");
    }

    @Test
    void coverageIdsAreUniqueSortedAndDerivedFromCanonicalRefs() {
        List<String> ids = UNITS.stream().map(LedgerUnit::coverageId).toList();

        assertThat(ids).doesNotHaveDuplicates().isSorted();
        assertThat(UNITS).allSatisfy(u -> assertThat(u.coverageId()).isEqualTo(CoverageIds.of(u.canonicalRefs())));
        assertThat(LedgerInvariants.checkL1(UNITS)).isEmpty();
    }

    @Test
    void unitsArePlannedAndSchemaValid() {
        assertThat(UNITS).allSatisfy(u -> {
            assertThat(u.status()).isEqualTo(LedgerUnit.Status.PLANNED);
            assertThat(u.agentId()).isEmpty();
            assertThat(u.priorStatus()).isEqualTo(LedgerUnit.PriorStatus.NEW);
            assertThat(u.startingPaths()).hasSize(1);
            assertThat(LedgerInvariants.checkL2(u, "/" + u.coverageId())).isEmpty();
        });
        assertThat(LedgerInvariants.check(List.of(), UNITS).ok()).isTrue();
        // Round-tripping through the strict codec validates every unit against ledger.schema.json.
        assertThat(LedgerCodec.read(LedgerCodec.write(UNITS))).isEqualTo(UNITS);
    }

    @Test
    void everyFamilyGetsAUnitForEveryEntrySurface() {
        Set<String> families = UNITS.stream().map(u -> u.cwe().orElseThrow())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(families).containsExactlyElementsOf(SinkCatalogue.FAMILIES);
        assertThat(UNITS).filteredOn(u -> u.startingPaths().get(0).endsWith("/OrderSearch.java"))
                .extracting(u -> u.cwe().orElseThrow())
                .containsExactlyInAnyOrderElementsOf(SinkCatalogue.FAMILIES);
        assertThat(UNITS).filteredOn(u -> u.startingPaths().get(0).endsWith("/OrderSearch.java")
                        && u.cwe().orElseThrow().equals("CWE-89"))
                .singleElement()
                .satisfies(u -> {
                    assertThat(u.surface()).isEqualTo("GET /orders/search");
                    assertThat(u.boundary()).isEqualTo("OrderSearch.doGet");
                    assertThat(u.ordinaryAttackClassBlock()).isEqualTo("ATTACK-CLASSES.md#Injection");
                    assertThat(u.canonicalRefs().attackClass()).isEqualTo("ATTACK-CLASSES.md#Injection/CWE-89");
                });
    }

    @Test
    void sinkCatalogueCoversTheFiveFamilies() {
        SinkCatalogue catalogue = SinkCatalogue.load();
        assertThat(catalogue.families()).containsExactlyInAnyOrderElementsOf(SinkCatalogue.FAMILIES);
        assertThat(catalogue.forMethod("executeQuery")).extracting(SinkCatalogue.Sink::className)
                .containsExactly("java.sql.Statement");
        assertThat(catalogue.sinks()).allSatisfy(s -> assertThat(s.arg()).isNotNegative());
    }
}
