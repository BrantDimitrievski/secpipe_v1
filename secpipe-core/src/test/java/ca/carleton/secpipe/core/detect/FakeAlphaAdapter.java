package ca.carleton.secpipe.core.detect;

import java.util.List;

/** Test adapter: SQL injection rule over A.java and B.java, one hit in A.java. */
public final class FakeAlphaAdapter extends FakeAdapter {

    public FakeAlphaAdapter() { }

    @Override
    public String id() {
        return "fake-alpha";
    }

    @Override
    String cwe() {
        return "CWE-89";
    }

    @Override
    String candidateFile() {
        return "src/main/java/A.java";
    }

    @Override
    List<String> claimedFiles() {
        return List.of("src/main/java/A.java", "src/main/java/B.java");
    }
}
