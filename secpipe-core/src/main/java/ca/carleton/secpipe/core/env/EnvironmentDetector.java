package ca.carleton.secpipe.core.env;

import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import ca.carleton.secpipe.core.env.DoctorReport.Check;
import ca.carleton.secpipe.core.env.DoctorReport.Status;
import ca.carleton.secpipe.core.util.Proc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Detects host facts (JDK, Maven, Docker, analyzers on PATH). Never executes the target project's wrapper scripts;
 * the wrapper's Maven version is read from {@code .mvn/wrapper/maven-wrapper.properties} as text.
 */
public final class EnvironmentDetector {

    public static final int MIN_JAVA = 21;
    public static final List<String> TOOLS = List.of("semgrep", "joern", "grype", "trivy", "spotbugs", "codeql");
    static final Duration DOCKER_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(20);

    private final boolean windows;

    public EnvironmentDetector() {
        this(Proc.isWindows());
    }

    EnvironmentDetector(boolean windows) {
        this.windows = windows;
    }

    public EnvironmentInfo detect(Map<String, String> env) {
        return detect(env, null);
    }

    /** As {@link #detect(Map)}, additionally looking for a Maven wrapper in {@code projectRoot} (may be null). */
    public EnvironmentInfo detect(Map<String, String> env, Path projectRoot) {
        String os = System.getProperty("os.name", "unknown");
        String arch = System.getProperty("os.arch", "unknown");
        Optional<Path> javaHome = javaHome(env);

        boolean wrapper = projectRoot != null && hasMavenWrapper(projectRoot);
        Optional<String> mavenVersion = Optional.empty();
        if (wrapper) {
            mavenVersion = wrapperMavenVersion(projectRoot);
        }
        if (mavenVersion.isEmpty()) {
            mavenVersion = Proc.which("mvn", env, windows)
                    .flatMap(p -> Proc.firstLine(p, TOOL_TIMEOUT, "-v"));
        }

        Optional<String> docker = dockerVersion(env);

        Map<String, Path> tools = new TreeMap<>();
        for (String t : TOOLS) {
            Proc.which(t, env, windows).ifPresent(p -> tools.put(t, p));
        }
        boolean gha = "true".equalsIgnoreCase(env.getOrDefault("GITHUB_ACTIONS", ""));
        return new EnvironmentInfo(os, arch, Runtime.version().toString(), javaHome, mavenVersion, wrapper,
                docker.isPresent(), docker, gha, Map.copyOf(tools));
    }

    /** Runs all probes and returns structured results; see {@link DoctorReport}. */
    public DoctorReport doctor(Map<String, String> env, Path projectRoot) {
        List<Check> out = new ArrayList<>();
        Runtime.Version v = Runtime.version();
        if (v.feature() >= MIN_JAVA) {
            out.add(new Check("java.host", Status.OK, "Java " + v + " at " + System.getProperty("java.home"), ""));
        } else {
            out.add(new Check("java.host", Status.FAIL,
                    "Java " + v + " is too old; SecPipe needs Java " + MIN_JAVA + "+",
                    "install JDK " + MIN_JAVA + "+ (https://adoptium.net) and set JAVA_HOME."));
        }
        String envHome = env.get("JAVA_HOME");
        if (envHome == null || envHome.isBlank()) {
            out.add(new Check("java.home", Status.WARN, "JAVA_HOME is not set",
                    "some build tools need it. Point it at your JDK " + MIN_JAVA + "+ folder."));
        } else if (!Files.isDirectory(Path.of(envHome))) {
            out.add(new Check("java.home", Status.FAIL, "JAVA_HOME points to a missing folder: " + envHome,
                    "fix JAVA_HOME so that $JAVA_HOME/bin/java exists."));
        } else {
            out.add(javaHomeCheck(envHome));
        }
        Optional<Path> javac = Proc.which("javac", env, windows);
        out.add(javac.isPresent()
                ? new Check("java.javac", Status.OK, "javac found at " + javac.get(), "")
                : new Check("java.javac", Status.WARN, "javac not found on PATH (a JRE, not a full JDK)",
                        "install a JDK to compile generated regression tests."));

        Optional<Path> mvn = Proc.which("mvn", env, windows);
        out.add(mvn.isPresent()
                ? new Check("maven.path", Status.OK, "Maven: "
                        + Proc.firstLine(mvn.get(), TOOL_TIMEOUT, "-v").orElse(mvn.get().toString()), "")
                : new Check("maven.path", Status.INFO, "Maven not on PATH",
                        "optional: the project's mvnw wrapper is used inside the sandbox."));

        if (projectRoot != null) {
            out.addAll(projectChecks(projectRoot));
        }

        Optional<String> docker = dockerVersion(env);
        if (docker.isPresent()) {
            out.add(new Check("docker", Status.OK, "Docker server " + docker.get(), ""));
        } else {
            out.add(new Check("docker", Status.WARN, "Docker is not available",
                    "install Docker and start the daemon; sandboxed verification needs it "
                            + "(findings stay needs_validation without it)."));
        }

        for (String t : TOOLS) {
            Optional<Path> p = Proc.which(t, env, windows);
            out.add(p.isPresent()
                    ? new Check("tool." + t, Status.OK, t + " found at " + p.get(), "")
                    : new Check("tool." + t, Status.INFO, t + " not on PATH",
                            "optional analyzer; it will be skipped."));
        }
        return new DoctorReport(out);
    }

    private Check javaHomeCheck(String envHome) {
        Path java = Path.of(envHome, "bin", windows ? "java.exe" : "java");
        if (!Files.isRegularFile(java)) {
            return new Check("java.home", Status.FAIL, "JAVA_HOME has no bin/java: " + envHome,
                    "point JAVA_HOME at a JDK " + MIN_JAVA + "+ folder.");
        }
        Optional<String> line = Proc.firstLine(java, TOOL_TIMEOUT, "-version");
        int feature = line.map(EnvironmentDetector::parseJavaFeature).orElse(-1);
        if (feature >= 0 && feature < MIN_JAVA) {
            return new Check("java.home", Status.FAIL, "JAVA_HOME=" + envHome + " is Java " + feature + ", too old",
                    "install JDK " + MIN_JAVA + "+ and point JAVA_HOME at it.");
        }
        return new Check("java.home", Status.OK, "JAVA_HOME=" + envHome + line.map(l -> " (" + l + ")").orElse(""), "");
    }

    /** Parses the feature number from a {@code java -version} first line such as {@code openjdk version "21.0.2"}. */
    static int parseJavaFeature(String line) {
        int a = line.indexOf('"');
        int b = line.indexOf('"', a + 1);
        if (a < 0 || b < 0) {
            return -1;
        }
        String v = line.substring(a + 1, b);
        if (v.startsWith("1.")) {
            v = v.substring(2);
        }
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) {
            end++;
        }
        return end == 0 ? -1 : Integer.parseInt(v.substring(0, end));
    }

    private List<Check> projectChecks(Path root) {
        List<Check> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            out.add(new Check("project.root", Status.FAIL, "not a directory: " + root,
                    "pass the root folder of the project you want to scan."));
            return out;
        }
        out.add(new Check("project.root", Status.OK, "project " + root, ""));
        if (hasMavenWrapper(root)) {
            Optional<String> ver = wrapperMavenVersion(root);
            out.add(new Check("maven.wrapper", Status.OK, "Maven wrapper present"
                    + ver.map(x -> " (Maven " + x + ")").orElse(" (version unknown)"), ""));
        } else if (Files.isRegularFile(root.resolve("pom.xml"))) {
            out.add(new Check("maven.wrapper", Status.INFO, "no Maven wrapper (mvnw) in the project",
                    "a Maven from the sandbox image is used instead."));
        }
        return out;
    }

    static boolean hasMavenWrapper(Path root) {
        return Files.isRegularFile(root.resolve("mvnw")) || Files.isRegularFile(root.resolve("mvnw.cmd"));
    }

    /** Reads the Maven version from the distribution URL in the wrapper properties. Never runs the wrapper. */
    static Optional<String> wrapperMavenVersion(Path root) {
        Path props = root.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.properties");
        if (!Files.isRegularFile(props)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(props)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        String url = p.getProperty("distributionUrl", "");
        int i = url.lastIndexOf("apache-maven-");
        if (i < 0) {
            return Optional.empty();
        }
        String rest = url.substring(i + "apache-maven-".length());
        int dash = rest.indexOf("-bin");
        if (dash < 0) {
            dash = rest.indexOf("-src");
        }
        return dash > 0 ? Optional.of(rest.substring(0, dash)) : Optional.empty();
    }

    private Optional<Path> javaHome(Map<String, String> env) {
        String h = env.get("JAVA_HOME");
        if (h != null && !h.isBlank() && Files.isDirectory(Path.of(h))) {
            return Optional.of(Path.of(h));
        }
        String own = System.getProperty("java.home");
        return own == null ? Optional.empty() : Optional.of(Path.of(own));
    }

    /** {@code docker version --format '{{.Server.Version}}'} with a 5 s timeout; empty if unavailable. */
    private Optional<String> dockerVersion(Map<String, String> env) {
        Optional<Path> docker = Proc.which("docker", env, windows);
        if (docker.isEmpty()) {
            return Optional.empty();
        }
        try {
            Proc.Result r = Proc.run(List.of(docker.get().toString(), "version", "--format", "{{.Server.Version}}"),
                    null, DOCKER_TIMEOUT);
            String v = r.stdout().strip();
            return r.ok() && !v.isEmpty() && !v.equals("<no value>") ? Optional.of(v) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
