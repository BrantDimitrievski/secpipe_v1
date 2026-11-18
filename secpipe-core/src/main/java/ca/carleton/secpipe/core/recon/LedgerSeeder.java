package ca.carleton.secpipe.core.recon;

import ca.carleton.secpipe.contracts.ids.CoverageIds;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.recon.Location;
import ca.carleton.secpipe.contracts.recon.ReconFacts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Seeds the coverage ledger from recon (WO-K.4): one {@code planned} unit per (family, entry surface), plus one per
 * (family, file) for sink files that contain no entry surface (library code such as a weak-hash helper would
 * otherwise have no unit). {@code coverage_id = CoverageIds.of(canonical_refs)}; output is unique and sorted by
 * coverage id.
 */
final class LedgerSeeder {

    private static final Map<String, String> ATTACK_CLASS = Map.of(
            "CWE-22", "Path traversal",
            "CWE-78", "OS command injection",
            "CWE-89", "SQL injection",
            "CWE-327", "Broken or risky cryptographic algorithm",
            "CWE-328", "Weak hash");
    private static final Map<String, String> BLOCK = Map.of(
            "CWE-22", "ATTACK-CLASSES.md#Resource and file handling",
            "CWE-78", "ATTACK-CLASSES.md#Injection",
            "CWE-89", "ATTACK-CLASSES.md#Injection",
            "CWE-327", "ATTACK-CLASSES.md#Cryptography and secrets",
            "CWE-328", "ATTACK-CLASSES.md#Cryptography and secrets");

    private LedgerSeeder() { }

    static List<LedgerUnit> seed(List<JavaRecon.EntrySurface> surfaces, ReconFacts facts, List<String> families) {
        Map<String, LedgerUnit> byId = new TreeMap<>();
        Set<String> surfaceFiles = new TreeSet<>();
        surfaces.forEach(s -> surfaceFiles.add(s.location().file()));
        for (String family : families) {
            for (JavaRecon.EntrySurface s : surfaces) {
                String file = s.location().file();
                String scope = s.location().scope().orElseThrow();
                LedgerUnit.CanonicalRefs refs = new LedgerUnit.CanonicalRefs(file + "#" + scope,
                        file + "#" + s.methodName(), subsystem(file), attackRef(family), Optional.empty());
                put(byId, unit(refs, s.label(), s.simpleClassName() + "." + s.methodName(), family, file));
            }
            Set<String> sinkOnly = new TreeSet<>();
            for (Location l : facts.sinks(family)) {
                if (!surfaceFiles.contains(l.file())) {
                    sinkOnly.add(l.file());
                }
            }
            for (String file : sinkOnly) {
                LedgerUnit.CanonicalRefs refs = new LedgerUnit.CanonicalRefs(file + "#library-code",
                        file + "#sink-sites", subsystem(file), attackRef(family), Optional.empty());
                put(byId, unit(refs, "library code in " + file, "sink sites in " + fileName(file), family, file));
            }
        }
        List<LedgerUnit> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(LedgerUnit::coverageId));
        return List.copyOf(out);
    }

    private static void put(Map<String, LedgerUnit> byId, LedgerUnit u) {
        byId.putIfAbsent(u.coverageId(), u);
    }

    private static LedgerUnit unit(LedgerUnit.CanonicalRefs refs, String surface, String boundary, String family,
                                   String file) {
        return new LedgerUnit(CoverageIds.of(refs), refs, surface, boundary, subsystemLabel(file),
                ATTACK_CLASS.getOrDefault(family, family) + " (" + family + ")", Optional.of(family), List.of(file),
                BLOCK.getOrDefault(family, "ATTACK-CLASSES.md#Wildcard"), List.of(), List.of(),
                LedgerUnit.PriorStatus.NEW, List.of(), 0, LedgerUnit.Status.PLANNED, Optional.empty(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    private static String attackRef(String family) {
        String block = BLOCK.getOrDefault(family, "ATTACK-CLASSES.md#Wildcard");
        return block + "/" + family;
    }

    /** The file's directory (canonical subsystem ref), or {@code .} for a file at the root. */
    private static String subsystem(String file) {
        int slash = file.lastIndexOf('/');
        return slash < 0 ? "." : file.substring(0, slash);
    }

    private static String subsystemLabel(String file) {
        String dir = subsystem(file);
        int java = dir.indexOf("src/main/java/");
        return java < 0 ? dir : dir.substring(java + "src/main/java/".length()).replace('/', '.');
    }

    private static String fileName(String file) {
        return file.substring(file.lastIndexOf('/') + 1);
    }
}
