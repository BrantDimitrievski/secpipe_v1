package ca.carleton.secpipe.adapter.pmd;

import ca.carleton.secpipe.contracts.finding.Finding.Level3;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rule name to CWE table for {@code pmd/secpipe-security.xml}. Rules without an entry here and not in
 * {@link #IGNORED} are dropped by the adapter, and a test fails if the ruleset gains such a rule.
 *
 * <p>Ported-from: capstone-mvp-1@2905635:src/main/java/ca/carleton/secpipe/tools/PmdAdapter.java
 */
final class PmdRules {

    record Info(String cwe, Score5 severity, Level3 confidence, String title) { }

    /** Rules deliberately not mapped. Empty today; add a name here instead of leaving it unmapped. */
    static final Set<String> IGNORED = Set.of();

    static final Map<String, Info> BY_RULE = Map.of(
            "HardCodedCryptoKey", new Info("CWE-321", Score5.HIGH, Level3.HIGH, "Hard-coded cryptographic key"),
            "InsecureCryptoIv", new Info("CWE-329", Score5.MEDIUM, Level3.HIGH, "Predictable initialization vector"),
            "AvoidUsingHardCodedIP", new Info("CWE-547", Score5.LOW, Level3.MEDIUM, "Hard-coded IP address"),
            "AvoidPrintStackTrace", new Info("CWE-209", Score5.LOW, Level3.LOW, "Stack trace printed to output"));

    private PmdRules() { }

    static Optional<Info> lookup(String ruleName) {
        return Optional.ofNullable(BY_RULE.get(ruleName));
    }

    static Set<String> supportedCwes() {
        Set<String> cwes = new TreeSet<>();
        BY_RULE.values().forEach(i -> cwes.add(i.cwe()));
        return Set.copyOf(cwes);
    }

    /** CWE to the rule names that can report it. */
    static Map<String, java.util.List<String>> rulesByCwe() {
        Map<String, java.util.List<String>> out = new java.util.TreeMap<>();
        new TreeSet<>(BY_RULE.keySet()).forEach(r -> out.computeIfAbsent(BY_RULE.get(r).cwe(), k -> new java.util.ArrayList<>()).add(r));
        return out;
    }
}
