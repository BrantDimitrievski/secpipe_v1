package ca.carleton.secpipe.contracts.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.carleton.secpipe.contracts.spi.SourceIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** WO-CC.2: Location validation and ordering, and the SourceIndex path rule it reuses. */
class LocationTest {

    @Test
    void acceptsRelativePathPositiveLineAndWellFormedScope() {
        Location l = Location.of("src/main/java/A.java", 1, "a.A#run(String, int)");
        assertEquals("src/main/java/A.java", l.file());
        assertEquals(1, l.line());
        assertEquals(Optional.of("a.A#run(String, int)"), l.scope());
        assertEquals(Optional.empty(), Location.of("A.java", 3).scope());
        assertEquals(Optional.of("A#<init>()"), Location.of("A.java", 3, "A#<init>()").scope());
    }

    @Test
    void rejectsLineBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> Location.of("A.java", 0));
        assertThrows(IllegalArgumentException.class, () -> Location.of("A.java", -5));
    }

    @Test
    void rejectsAbsoluteOrEscapingOrNonCanonicalPaths() {
        for (String bad : List.of("", "/etc/passwd", "../A.java", "src/../../A.java", "src/..", "C:/A.java",
                "src\\A.java", "./A.java", "src//A.java", "src/", "a\0b")) {
            assertThrows(IllegalArgumentException.class, () -> Location.of(bad, 1), bad);
            assertThrows(IllegalArgumentException.class, () -> SourceIndex.requireRelativePath(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Location.of(null, 1));
        assertEquals("a/..b/c.java", SourceIndex.requireRelativePath("a/..b/c.java"));
    }

    @Test
    void rejectsNullOrMalformedScope() {
        assertThrows(NullPointerException.class, () -> new Location("A.java", 1, null));
        for (String bad : List.of("", " ", "A", "A#run", "#run()", "A#()", "A#run(String", "A #run()")) {
            assertThrows(IllegalArgumentException.class, () -> Location.of("A.java", 1, bad), bad);
        }
    }

    @Test
    void ordersByFileThenLineThenScopeWithAbsentScopeFirst() {
        Location a = Location.of("a/B.java", 2);
        Location b = Location.of("a/B.java", 2, "B#x()");
        Location c = Location.of("a/B.java", 10);
        Location d = Location.of("b/A.java", 1);
        List<Location> list = new ArrayList<>(List.of(d, c, b, a));
        list.sort(null);
        assertEquals(List.of(a, b, c, d), list);
    }
}
