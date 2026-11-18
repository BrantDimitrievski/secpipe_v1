package ca.carleton.secpipe.eval.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parser for the OWASP Benchmark {@code expectedresults-1.2.csv} answer key. */
public final class ExpectedResults {

    /** One answer-key row: {@code BenchmarkTest00001,cmdi,true,78}. {@code cwe} is the bare number. */
    public record Row(String test, String category, boolean vulnerable, String cwe) { }

    private ExpectedResults() {
    }

    public static List<Row> read(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** Skips blank lines and the {@code #} header; a malformed row is an error naming its line number. */
    public static List<Row> parse(String csv) {
        List<Row> rows = new ArrayList<>();
        String[] lines = csv.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split(",");
            if (cols.length < 4 || !cols[0].strip().matches("BenchmarkTest\\d{5}")
                    || !(cols[2].strip().equals("true") || cols[2].strip().equals("false"))
                    || !cols[3].strip().matches("\\d+")) {
                throw new IllegalArgumentException("expected-results line " + (i + 1) + " is malformed: " + line);
            }
            rows.add(new Row(cols[0].strip(), cols[1].strip(), Boolean.parseBoolean(cols[2].strip()),
                    cols[3].strip().replaceFirst("^0+(?=\\d)", "")));
        }
        return List.copyOf(rows);
    }
}
