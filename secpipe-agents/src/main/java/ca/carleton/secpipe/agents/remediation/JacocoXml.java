package ca.carleton.secpipe.agents.remediation;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reader for a JaCoCo XML report (WO-M.2): line coverage per source file. A line is <i>executable</i> when the report
 * lists it ({@code <line nr mi ci .../>} under a {@code <sourcefile>}), and <i>covered</i> when at least one of its
 * instructions ran ({@code ci > 0}). Lines without bytecode (imports, braces, comments) are not in the report.
 *
 * <p>Source files are keyed by their package-relative path, e.g. {@code com/shop/app/LoginServlet.java}. The parser
 * never loads the report DTD or any external entity; JaCoCo's {@code <!DOCTYPE report ...>} declaration is accepted
 * but ignored.
 */
public final class JacocoXml {

    private JacocoXml() {
    }

    /** Line coverage of one report: source path to (line number to covered), all sorted. */
    public record Coverage(Map<String, Map<Integer, Boolean>> lines) {
        public Coverage {
            Map<String, Map<Integer, Boolean>> copy = new TreeMap<>();
            lines.forEach((k, v) -> copy.put(k, Map.copyOf(new TreeMap<>(v))));
            lines = Map.copyOf(copy);
        }

        /** Whether the report has any data for {@code sourcePath} (package-relative). */
        public boolean hasFile(String sourcePath) {
            return lines.containsKey(sourcePath);
        }

        /** Empty when the line is not executable (or the file is unknown); otherwise whether it was covered. */
        public Optional<Boolean> covered(String sourcePath, int line) {
            Map<Integer, Boolean> file = lines.get(sourcePath);
            return file == null ? Optional.empty() : Optional.ofNullable(file.get(line));
        }
    }

    /** Parses {@code xml}; empty when it is missing, unreadable or not a JaCoCo {@code <report>}. */
    public static Optional<Coverage> parse(Path xml) {
        if (!Files.isRegularFile(xml, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(xml, LinkOption.NOFOLLOW_LINKS)) {
            return parse(in);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Parses a report from a stream; empty when it is not a readable JaCoCo {@code <report>}. */
    public static Optional<Coverage> parse(InputStream in) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setValidating(false);
            f.setNamespaceAware(false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = f.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // quiet: errors throw, nothing is printed
            Document doc = builder.parse(in);
            Element root = doc.getDocumentElement();
            if (!root.getTagName().equals("report")) {
                return Optional.empty();
            }
            Map<String, Map<Integer, Boolean>> out = new TreeMap<>();
            NodeList packages = root.getElementsByTagName("package");
            for (int i = 0; i < packages.getLength(); i++) {
                Element pkg = (Element) packages.item(i);
                String pkgName = pkg.getAttribute("name");
                for (Element sf : children(pkg, "sourcefile")) {
                    String path = pkgName.isEmpty() ? sf.getAttribute("name") : pkgName + "/" + sf.getAttribute("name");
                    Map<Integer, Boolean> lines = out.computeIfAbsent(path, k -> new TreeMap<>());
                    for (Element line : children(sf, "line")) {
                        int nr = Integer.parseInt(line.getAttribute("nr"));
                        int ci = Integer.parseInt(line.getAttribute("ci"));
                        lines.merge(nr, ci > 0, Boolean::logicalOr);
                    }
                }
            }
            return Optional.of(new Coverage(out));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && ((Element) n).getTagName().equals(tag)) {
                out.add((Element) n);
            }
        }
        return out;
    }
}
