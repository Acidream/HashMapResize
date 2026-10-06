# OpenJDK HashMap tests

Verbatim copies of the OpenJDK jtreg tests for `HashMap` and `Map`, from tag `jdk-24-ga`:

- `java/util/HashMap/*` from https://github.com/openjdk/jdk/tree/jdk-24-ga/test/jdk/java/util/HashMap
- `java/util/Map/*` from https://github.com/openjdk/jdk/tree/jdk-24-ga/test/jdk/java/util/Map

They keep their original license headers (GPLv2, or GPLv2 with the Classpath Exception).

Do not edit them here. The `generateJdkTests` Gradle task generates two copies under
`build/generated/jdk-tests`: one where `HashMap` is renamed to `JdkHashMapCopy`
(package `jdktests.copy.*`), one where it is renamed to `RangeSplitHashMap`
(package `jdktests.rangesplit.*`). References written as `java.util.HashMap` are kept.
The few other changes needed are listed, with reasons, in `build.gradle.kts`.

TestNG tests run on the JUnit Platform through `testng-engine`; tests with a `main`
method run through `hmresize.JdkMainTests`.
