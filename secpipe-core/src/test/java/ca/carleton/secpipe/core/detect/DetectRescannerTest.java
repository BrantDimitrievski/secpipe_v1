package ca.carleton.secpipe.core.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import ca.carleton.secpipe.core.engine.DefaultRunLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DetectRescannerTest {

    static final Path FIXTURE = Path.of("..", "fixtures", "e2e", "sqli-min").toAbsolutePath().normalize();
    static final String LOGIN = "src/main/java/com/shop/app/LoginServlet.java";
    static final String TAG = "src/main/java/com/shop/app/TagFilter.java";

    @TempDir
    Path tmp;

    /** Parses source text only: one CWE-89 candidate per {@code executeQuery("..." + x)} line. */
    static final class ConcatQueryAdapter implements AnalyzerAdapter {
        final AtomicReference<Request> last = new AtomicReference<>();

        @Override
        public String id() {
            return "concat-query";
        }

        @Override
        public String displayName() {
            return id();
        }

        @Override
        public Kind kind() {
            return Kind.SAST;
        }

        @Override
        public Set<String> supportedCwes() {
            return Set.of("CWE-89");
        }

        @Override
        public Availability availability(EnvironmentInfo env) {
            return Availability.yes("1.0");
        }

        @Override
        public boolean defaultEnabled(RunProfile profile) {
            return true;
        }

        @Override
        public String licenseNote() {
            return "test only";
        }

        @Override
        public boolean requiresSandbox() {
            return false;
        }

        @Override
        public Result analyze(Request request) {
            last.set(request);
            List<Candidate> out = new ArrayList<>();
            for (String rel : request.profile().sourceFiles()) {
                List<String> lines = read(request.projectRoot().resolve(rel));
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.contains("executeQuery(\"") && line.contains("+")) {
                        out.add(candidate(rel, i + 1, line));
                    }
                }
            }
            return new Result(out, List.of(), new RunMetadata.ToolRun(id(), Optional.of("1.0"),
                    RunMetadata.ToolRun.Status.OK, Optional.empty(), 0, out.size()));
        }

        private Candidate candidate(String file, int line, String text) {
            return new Candidate(Optional.empty(), Finding.Category.CODE, Finding.Origin.TOOL,
                    List.of(new Finding.ToolSource(id(), "concat", Optional.empty(), Optional.of(file),
                            Optional.of(line))),
                    List.of("CWE-89"), List.of(), "SQL built by concatenation", "message", "root cause",
                    List.of(new Finding.TraceStep(Finding.TraceKind.SINK, file, line, "X#doPost()", "sink")),
                    List.of(), Optional.of(text), Optional.of("X#doPost()"), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        }

        private static List<String> read(Path p) {
            try {
                return Files.readAllLines(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private DetectRescanner rescanner(ConcatQueryAdapter adapter) {
        return new DetectRescanner(DetectFixtures.config(), List.of(adapter), Map.of(), tmp.resolve("work"),
                Clock.systemUTC(), DefaultRunLog.discarding());
    }

    private Path copyFixture(String name) throws IOException {
        Path dest = tmp.resolve(name);
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path p : walk.toList()) {
                Path to = dest.resolve(FIXTURE.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to);
                }
            }
        }
        return dest;
    }

    private static Map<String, String> fingerprintsByFile(List<Candidate> candidates) {
        Map<String, String> out = new TreeMap<>();
        candidates.forEach(c -> out.put(c.trace().get(c.trace().size() - 1).file(), c.fingerprint().orElseThrow()));
        return out;
    }

    private static Map<String, Long> snapshot(Path root) throws IOException {
        Map<String, Long> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString(), Files.size(p) * 31 + Files.getLastModifiedTime(p).toMillis());
            }
        }
        return out;
    }

    @Test
    void rescanOfACopyFindsTheOriginalFingerprints() throws Exception {
        ConcatQueryAdapter adapter = new ConcatQueryAdapter();
        DetectRescanner r = rescanner(adapter);
        Map<String, String> original = fingerprintsByFile(r.rescan(FIXTURE, Set.of()));
        Path copy = copyFixture("copy");

        List<Candidate> rescanned = r.rescan(copy, Set.of());

        assertThat(original).containsOnlyKeys(LOGIN, TAG);
        assertThat(original.values()).allMatch(fp -> fp.startsWith("code:CWE-89:"));
        assertThat(fingerprintsByFile(rescanned)).isEqualTo(original);
        assertThat(rescanned).extracting(c -> c.fingerprint().orElseThrow()).isSorted();
        assertThat(adapter.last.get().projectRoot()).isEqualTo(copy);
        assertThat(adapter.last.get().sandbox()).isEmpty();
    }

    @Test
    void afterAFakeFixTheFingerprintIsGone() throws Exception {
        DetectRescanner r = rescanner(new ConcatQueryAdapter());
        Path copy = copyFixture("fixed");
        Map<String, String> before = fingerprintsByFile(r.rescan(copy, Set.of()));
        Path login = copy.resolve(LOGIN);
        String fixed = Files.readString(login).replace(
                "st.executeQuery(\"select id from users where username = '\" + username + \"'\")",
                "st.executeQuery(\"select id from users where username = ?\")");
        assertThat(fixed).isNotEqualTo(Files.readString(login));
        Files.writeString(login, fixed);

        Map<String, String> after = fingerprintsByFile(r.rescan(copy, Set.of()));

        assertThat(after).doesNotContainValue(before.get(LOGIN));
        assertThat(after).containsExactly(Map.entry(TAG, before.get(TAG)));
    }

    @Test
    void scopeFilesKeepOnlyCandidatesInThoseFiles() throws Exception {
        DetectRescanner r = rescanner(new ConcatQueryAdapter());
        Path copy = copyFixture("scoped");
        Map<String, String> all = fingerprintsByFile(r.rescan(copy, Set.of()));

        assertThat(fingerprintsByFile(r.rescan(copy, Set.of(LOGIN))))
                .containsExactly(Map.entry(LOGIN, all.get(LOGIN)));
        assertThat(r.rescan(copy, Set.of("src/main/java/com/shop/app/Db.java"))).isEmpty();
    }

    @Test
    void rescanNeverWritesTheWorkingCopyAndUsesItsOwnScratch() throws Exception {
        DetectRescanner r = rescanner(new ConcatQueryAdapter());
        Path copy = copyFixture("readonly");
        Map<String, Long> before = snapshot(copy);

        r.rescan(copy, Set.of());
        r.rescan(copy, Set.of());

        assertThat(snapshot(copy)).isEqualTo(before);
        assertThat(tmp.resolve("work/rescan/1/detect/concat-query")).isDirectory();
        assertThat(tmp.resolve("work/rescan/2/detect/concat-query")).isDirectory();
    }

    @Test
    void sandboxAdaptersAreSkippedSoNoTargetCodeRuns() throws Exception {
        FakeAdapter.reset();
        FakeAdapter.SANDBOX.put("fake-alpha", true);
        try {
            List<AnalyzerAdapter> adapters = new ArrayList<>(DetectFixtures.fakes());
            ConcatQueryAdapter concat = new ConcatQueryAdapter();
            adapters.add(concat);
            DetectRescanner r = new DetectRescanner(DetectFixtures.config(), adapters, Map.of(),
                    tmp.resolve("work"), Clock.systemUTC(), DefaultRunLog.discarding());

            r.rescan(copyFixture("sandboxed"), Set.of());

            assertThat(FakeAdapter.LAST_REQUEST).doesNotContainKey("fake-alpha");
            assertThat(concat.last.get()).isNotNull();
        } finally {
            FakeAdapter.reset();
        }
    }

    @Test
    void aMissingWorkingCopyIsRejected() {
        DetectRescanner r = rescanner(new ConcatQueryAdapter());
        assertThatThrownBy(() -> r.rescan(tmp.resolve("missing"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a directory");
    }
}
