package ehms.test;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * JUnit 5 base class preserving the original MiniTest suite shape so no test
 * body had to change: define() records test("name", body) pairs and each suite
 * exposes them through a @TestFactory as dynamic tests.
 */
public abstract class EhmsTest {

    /** A test body that may throw anything (same shape as before). */
    public interface Body { void run() throws Exception; }

    private final List<DynamicTest> recorded = new ArrayList<>();

    /** Records one test - call this from define(), exactly as before. */
    protected final void test(String name, Body body) {
        recorded.add(DynamicTest.dynamicTest(name, body::run));
    }

    /** Each suite adds exactly one method:
     *   &#64;TestFactory Stream&lt;DynamicTest&gt; all() { return junitTests(); } */
    protected final Stream<DynamicTest> junitTests() {
        define();
        return recorded.stream();
    }

    protected abstract void define();

    // ---------------- assertions ----------------

    protected static void assertTrue(boolean condition) { Assertions.assertTrue(condition); }
    protected static void assertTrue(boolean condition, String message) { Assertions.assertTrue(condition, message); }
    protected static void assertFalse(boolean condition) { Assertions.assertFalse(condition); }
    protected static void assertEquals(Object expected, Object actual) { Assertions.assertEquals(expected, actual); }

    protected static void assertContains(String haystack, String needle) {
        Assertions.assertTrue(haystack != null && haystack.contains(needle),
                "expected <" + haystack + "> to contain <" + needle + ">");
    }

    protected static void assertThrows(Class<? extends Throwable> type, Body body) {
        Assertions.assertThrows(type, body::run);
    }

    protected static void assertThrowsWithMessage(Class<? extends Throwable> type, String needle, Body body) {
        Throwable t = Assertions.assertThrows(type, body::run);
        Assertions.assertTrue(String.valueOf(t.getMessage()).contains(needle),
                "expected message <" + t.getMessage() + "> to contain <" + needle + ">");
    }
}