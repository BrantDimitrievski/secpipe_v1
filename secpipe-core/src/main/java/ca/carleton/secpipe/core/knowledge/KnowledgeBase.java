package ca.carleton.secpipe.core.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline CWE knowledge: CWE to family root, impact class and remediation text per family. Loaded once from
 * {@code /knowledge/cwe-families.json} and immutable afterwards. Family roots match
 * {@code Fingerprints.CWE_FAMILIES}.
 *
 * <p>Ported-from: capstone-mvp-1@2905635:src/main/java/ca/carleton/secpipe/knowledge/KnowledgeBase.java
 */
public final class KnowledgeBase {

    /** One CWE family. */
    public record Family(String id, String name, ImpactClass impactClass, List<String> members, String description,
                         String remediation) {
        public Family {
            members = List.copyOf(members);
        }
    }

    private static final Pattern CWE_ID = Pattern.compile("(?i)(?:cwe[-_ ]?)?0*(\\d+)");
    private static final String RESOURCE = "/knowledge/cwe-families.json";
    private static final KnowledgeBase INSTANCE = new KnowledgeBase();

    private final Map<String, Family> families;
    private final Map<String, String> familyByMember;

    /** The shared instance. */
    public static KnowledgeBase load() {
        return INSTANCE;
    }

    private KnowledgeBase() {
        Map<String, Family> byId = new LinkedHashMap<>();
        Map<String, String> byMember = new HashMap<>();
        JsonNode root = read();
        for (Map.Entry<String, JsonNode> e : root.path("families").properties()) {
            JsonNode n = e.getValue();
            List<String> members = new ArrayList<>();
            n.path("members").forEach(m -> members.add(m.asString()));
            Family f = new Family(e.getKey(), n.path("name").asString(), ImpactClass.valueOf(n.path("impactClass").asString()),
                    members, n.path("description").asString(), n.path("remediation").asString());
            byId.put(f.id(), f);
            members.forEach(m -> byMember.put(m, f.id()));
        }
        this.families = Map.copyOf(byId);
        this.familyByMember = Map.copyOf(byMember);
    }

    private static JsonNode read() {
        try (InputStream in = KnowledgeBase.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled resource " + RESOURCE);
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Canonical {@code CWE-<n>} for "cwe-023", "CWE_22", "22" or "CWE-22: Path Traversal"; empty if there is no number. */
    public static Optional<String> normalizeCwe(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = CWE_ID.matcher(raw.strip());
        if (m.lookingAt()) {
            return Optional.of("CWE-" + m.group(1));
        }
        return Optional.empty();
    }

    /** Family root for a CWE id (e.g. CWE-23 gives CWE-22). Unknown ids are their own family; unparsable input is returned as is. */
    public String family(String cwe) {
        Optional<String> canonical = normalizeCwe(cwe);
        if (canonical.isEmpty()) {
            return cwe;
        }
        return familyByMember.getOrDefault(canonical.get(), canonical.get());
    }

    /** The family record for a family root or any member CWE; empty for CWEs outside the knowledge base. */
    public Optional<Family> familyInfo(String cweOrFamily) {
        return Optional.ofNullable(families.get(family(cweOrFamily)));
    }

    public Optional<ImpactClass> impactClass(String family) {
        return familyInfo(family).map(Family::impactClass);
    }

    public Optional<String> remediation(String family) {
        return familyInfo(family).map(Family::remediation);
    }

    /** All family roots, sorted by id. */
    public List<String> familyIds() {
        return families.keySet().stream().sorted().toList();
    }
}
