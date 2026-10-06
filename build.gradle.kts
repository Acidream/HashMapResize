plugins {
    java
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val jmh by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val jmhVersion = "1.37"

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // For the OpenJDK TestNG tests in jdk-tests/, run on the JUnit Platform.
    testImplementation("org.testng:testng:7.11.0")
    testRuntimeOnly("org.junit.support:testng-engine:1.0.5")

    "jmhImplementation"("org.openjdk.jmh:jmh-core:$jmhVersion")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

application {
    mainClass = "hmresize.ResizeBenchmark"
    applicationDefaultJvmArgs = listOf("-Xms2g", "-Xmx2g", "--add-opens", "java.base/java.util=ALL-UNNAMED")
}

// Generates the OpenJDK HashMap/Map tests in jdk-tests/ (kept verbatim) for each implementation under
// test: `HashMap` is renamed to the implementation's class, references written as `java.util.HashMap`
// are kept, and each file gets a package. jdk-tests/README.md has the details.
val jdkTestImplementations = mapOf("copy" to "JdkHashMapCopy", "rangesplit" to "RangeSplitHashMap")

// The only edits besides the rename. They apply before it, so `HashMap` here means the class under test.
val jdkTestPatches = mapOf(
    "WhiteBoxResizeTest.java" to listOf(
        // `table` is declared in JdkHashMapCopy, not in RangeSplitHashMap, so look it up there. The
        // LinkedHashMap and HashSet cases still need java.util.HashMap's own `table`.
        "    final VarHandle HM_TABLE;\n" to
            "    final VarHandle HM_TABLE;\n" +
            "    final VarHandle JDK_HM_TABLE; // hm_resize patch\n",
        "        HM_TABLE = hmlookup.unreflectVarHandle(HashMap.class.getDeclaredField(\"table\"));\n" to
            "        HM_TABLE = hmlookup.unreflectVarHandle(JdkHashMapCopy.class.getDeclaredField(\"table\")); // hm_resize patch\n" +
            "        JDK_HM_TABLE = MethodHandles.privateLookupIn(java.util.HashMap.class, MethodHandles.lookup())\n" +
            "                .unreflectVarHandle(java.util.HashMap.class.getDeclaredField(\"table\")); // hm_resize patch\n",
        "            VarHandle vh = map instanceof WeakHashMap ? WHM_TABLE : HM_TABLE;\n" to
            "            VarHandle vh = map instanceof WeakHashMap ? WHM_TABLE\n" +
            "                    : map instanceof java.util.HashMap ? JDK_HM_TABLE : HM_TABLE; // hm_resize patch\n",
        "            HashMap<?, ?> hashMap = (HashMap<?, ?>) HS_MAP.get(content);\n" to
            "            java.util.HashMap<?, ?> hashMap = (java.util.HashMap<?, ?>) HS_MAP.get(content); // hm_resize patch\n",
    ),
)

val generateJdkTests by tasks.registering {
    val originals = layout.projectDirectory.dir("jdk-tests/java/util")
    val output = layout.buildDirectory.dir("generated/jdk-tests")
    inputs.dir(originals)
    inputs.property("implementations", jdkTestImplementations)
    inputs.property("patches", jdkTestPatches.toString())
    outputs.dir(output)
    doLast {
        val out = output.get().asFile
        out.deleteRecursively()
        val keep = "\u0000KEEP_JAVA_UTIL_HM\u0000" // must not contain the word being renamed
        for ((implPackage, implClass) in jdkTestImplementations) {
            for (dir in listOf("HashMap", "Map")) {
                val pkg = "jdktests.$implPackage.${dir.lowercase()}"
                originals.dir(dir).asFile.listFiles { f -> f.name.endsWith(".java") }!!.sorted().forEach { file ->
                    var text = file.readText().replace("\r\n", "\n")
                    for ((old, new) in jdkTestPatches[file.name].orEmpty()) {
                        check(old in text) { "patch for ${file.name} no longer applies: $old" }
                        text = text.replace(old, new)
                    }
                    text = text.lines().filterNot { it.trim() == "import java.util.HashMap;" }.joinToString("\n")
                        .replace("java.util.HashMap", keep)
                        .replace(Regex("\\bHashMap\\b"), implClass)
                        .replace(keep, "java.util.HashMap")
                    val header = "package $pkg; // generated from jdk-tests/java/util/$dir/${file.name}\n" +
                        "import hmresize.JdkHashMapCopy;\nimport hmresize.RangeSplitHashMap;\n"
                    val target = out.resolve(pkg.replace('.', '/')).resolve(file.name)
                    target.parentFile.mkdirs()
                    target.writeText(header + text)
                }
            }
        }
    }
}

sourceSets.test {
    java.srcDir(generateJdkTests)
}

tasks.test {
    useJUnitPlatform()
    // As the jtreg tags of the OpenJDK tests ask: whitebox access to java.util, a 2g heap for
    // WhiteBoxResizeTest, and the short run of the collision tests.
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    maxHeapSize = "2g"
    systemProperty("test.map.collisions.shortrun", "true")
}

// Runs JMH. Pass JMH options with -Pjmh="...", e.g. ./gradlew jmh -Pjmh="ResizeJmh -p perBin=1024".
// Without options it runs every benchmark and writes JSON results to build/reports/jmh/results.json.
tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs the JMH benchmarks."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    val resultFile = layout.buildDirectory.file("reports/jmh/results.json")
    val options = providers.gradleProperty("jmh")
    args(options.map { it.trim().split(Regex("\\s+")) }
        .orElse(resultFile.map { listOf("-rf", "json", "-rff", it.asFile.path) }).get())
    doFirst { resultFile.get().asFile.parentFile.mkdirs() }
}
