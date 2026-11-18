package ca.carleton.secpipe.core.detect;

import java.util.List;

/** Test adapter: path traversal rule (CWE-23, family CWE-22) over C.java, one hit in D.java (outside the claim). */
public final class FakeBetaAdapter extends FakeAdapter {

    public FakeBetaAdapter() { }

    @Override
    public String id() {
        return "fake-beta";
    }

    @Override
    String cwe() {
        return "CWE-23";
    }

    @Override
    String candidateFile() {
        return "src/main/java/D.java";
    }

    @Override
    List<String> claimedFiles() {
        return List.of("src/main/java/C.java");
    }
}
