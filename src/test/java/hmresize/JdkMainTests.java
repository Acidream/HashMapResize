package hmresize;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the OpenJDK jtreg tests that are plain {@code main} programs (generated from {@code jdk-tests/}
 * by the generateJdkTests Gradle task), once per implementation. Such a test fails by throwing.
 * The TestNG tests among them run directly through testng-engine.
 */
class JdkMainTests {

    static final List<String> IMPLEMENTATIONS = List.of("copy", "rangesplit");

    static final List<String> MAIN_TESTS = List.of(
            "hashmap.HashMapCloneLeak",
            "hashmap.KeySetRemove",
            "hashmap.NullKeyAtResize",
            "hashmap.OverrideIsEmpty",
            "hashmap.PutNullKey",
            "hashmap.ReplaceExisting",
            "hashmap.SetValue",
            "hashmap.ToArray",
            "hashmap.ToString",
            "map.EntryHashCode",
            "map.Get",
            "map.LockStep",
            "map.ToArray");

    static Stream<Arguments> tests() {
        List<Arguments> cases = new ArrayList<>();
        for (String impl : IMPLEMENTATIONS) {
            for (String test : MAIN_TESTS) {
                cases.add(Arguments.of("jdktests." + impl + "." + test));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tests")
    void runMain(String className) throws Throwable {
        try {
            Class.forName(className).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
