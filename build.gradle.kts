import org.gradle.kotlin.dsl.support.serviceOf
import java.security.MessageDigest

plugins {
    id("java")
    id("signing")
    id("maven-publish")
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "dev.sbs"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Gradle IntelliJ Plugin (https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
    intellijPlatform {
        create("IC", "2023.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // Adds LightJavaCodeInsightFixtureTestCase + JAVA_NN project descriptors
        // (mock JDK), needed by AnnotationTest / ClassBuilder*Test for proper
        // String/Object resolution during inspection highlighting.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
        bundledPlugin("com.intellij.java")
    }

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.testing.compile:compile-testing:0.21.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "232"
        }

        changeNotes = """
            <h3>1.2.0</h3>
            <ul>
              <li><b>AST-mutation pivot</b> - <code>@ClassBuilder</code> now injects a <code>public static class Builder</code> directly into the annotated class via javac AST mutation rather than emitting a sibling <code>&lt;TypeName&gt;Builder.java</code>. Interface targets still emit sibling <code>&lt;Name&gt;Impl.java</code> + <code>&lt;Name&gt;Builder.java</code> since there is no in-source mutation surface on an interface body.</li>
              <li><b>Auto-generated bootstrap methods</b> - <code>builder()</code>, <code>from(T)</code>, and <code>mutate()</code> are now injected on the annotated type automatically. Hand-written methods with the same name and arity win (skip-on-collision + <code>Kind.NOTE</code>). The bootstrap-methods inspection is retired.</li>
              <li><b>SuperBuilder for abstract classes</b> - <code>@ClassBuilder</code> on an abstract class produces a self-typed <code>Builder&lt;T extends Target, B extends Builder&lt;T, B&gt;&gt;</code> with abstract <code>build()</code> and <code>self()</code>. Concrete subclasses carrying <code>@ClassBuilder</code> automatically inherit the parent's builder (<code>class Builder extends Super.Builder&lt;ThisType, ThisType.Builder&gt;</code>), override <code>self()</code> and <code>build()</code>, and get a protected copy constructor. Opt out with <code>generateCopyConstructor = false</code>.</li>
              <li><b>IDE augmentation</b> - a new <code>PsiAugmentProvider</code> surfaces the injected bootstrap methods to the PSI layer, so autocompletion, goto-symbol, and type resolution all work before the first javac round. A gutter icon (replaceable SVG at <code>/icons/classbuilder_generated.svg</code>) marks every <code>@ClassBuilder</code> annotation.</li>
              <li><b>Multi-JDK support</b> - versioned compat layer under <code>dev.sbs.classbuilder.mutate.compat</code> with <code>v17</code>/<code>v21</code>/<code>v23</code>/<code>v25</code> shims dispatched by <code>Runtime.version().feature()</code>. aptTest matrix covers JDK 17, 21, and 25.</li>
              <li><b>Consumer requirement</b> - javac-only (ecj not supported). Consumers' builds need <code>--add-exports=jdk.compiler/com.sun.tools.javac.*=ALL-UNNAMED</code> on compile; see <code>build.gradle.kts</code> for the full list.</li>
              <li><b>Gradle 9.4.1</b> - wrapper bumped so JDK 25 is natively supported without toolchain workarounds; unused Kotlin JVM plugin dropped.</li>
            </ul>

            <h3>1.1.0</h3>
            <ul>
              <li><b>New @ClassBuilder annotation</b> - generates a sibling <code>&lt;TypeName&gt;Builder.java</code> via a JSR 269 annotation processor. Supports classes, records, and interfaces (interfaces also get a matching <code>&lt;Name&gt;Impl</code>). Full Lombok <code>@Builder</code> parity plus opinionated extras: configurable method prefix, <code>builderName</code>/<code>builderMethodName</code>/<code>buildMethodName</code>/<code>fromMethodName</code>/<code>toBuilderMethodName</code>, generated <code>from(T)</code> + <code>mutate()</code>, and emitted <code>@XContract</code> on every setter so IDE data-flow understands fresh-object and this-return shapes.</li>
              <li><b>New field-level companions</b> - <code>@Singular</code> (collection/map add/put/varargs/clear), <code>@Negate</code> (paired inverse boolean setters), <code>@Formattable</code> (<code>@PrintFormat</code> overloads with null-tolerant variants for Optional&lt;String&gt; fields), <code>@BuilderDefault</code>, <code>@BuilderIgnore</code>, <code>@ObtainVia</code>.</li>
              <li><b>@BuilderDefault source-initializer copying</b> - the generated builder now reproduces the field's declared initializer verbatim, with type references (e.g. <code>UUID.randomUUID()</code>, <code>List.of(...)</code>) auto-imported. Reports a compile error if <code>@BuilderDefault</code> is applied to a field with no initializer.</li>
              <li><b>@ObtainVia accessor redirection</b> - the generated <code>from(T)</code> honours <code>method</code>, <code>field</code>, and <code>isStatic</code>, so builders can reconstruct from types that don't expose a standard getter.</li>
              <li><b>New @BuildFlag runtime validator</b> - enforces <code>nonNull</code>, <code>notEmpty</code>, <code>group</code> mutual-requirement, regex <code>pattern</code>, and length/size <code>limit</code> constraints in the generated <code>build()</code>. Zero external dependencies.</li>
              <li><b>New ClassBuilder bootstrap inspection</b> - ERROR-severity check that the three bootstrap methods (<code>builder()</code> / <code>from(T)</code> / <code>mutate()</code>) are materialised on the annotated class, with a quick-fix that inserts them all at once with matching <code>@XContract</code> annotations.</li>
              <li><b>New ClassBuilder field inspection</b> - flags misuse of the companion annotations (e.g. <code>@Formattable</code> on a non-String field) at source-edit time.</li>
            </ul>

            <h3>1.0.5</h3>
            <ul>
              <li><b>@XContract annotation</b> - superset of JetBrains @Contract with relational comparisons, &amp;&amp;/|| grouping, named-parameter references, instanceof checks, typed throws returns, chained comparisons, and full pure/mutates support. A synthetic @Contract is inferred so IntelliJ data-flow works from a single annotation.</li>
              <li><b>XContract Call-Site inspection</b> - flags calls whose literal arguments deterministically trigger a fail or throws clause.</li>
              <li><b>ResourcePath Base-Prefix Usage inspection</b> - warns when a @ResourcePath(base="X") parameter is passed raw into a resource-loading call, with a quick-fix that prepends X/. Also flags base mismatches across call boundaries.</li>
              <li><b>ResourcePath freeze fix</b> - removed the project-wide ReferencesSearch that locked up the IDE on large utility files.</li>
              <li><b>Modernised PSI listener</b> - narrowed to annotation events only; replaced deprecated DaemonCodeAnalyzer.restart(PsiFile).</li>
              <li><b>Settings</b> - additional resource-root paths, glob-based file exclusions, split severity dropdowns, inheritance and mutates checks.</li>
            </ul>
        """.trimIndent()
    }
    buildSearchableOptions = false
}

sourceSets {
    main {
        java.srcDirs("src/main/java")
        resources.srcDirs("src/main/resources")
    }
    // Separate source set for pure annotation-processor tests that must run on a full JDK
    // outside the IntelliJ test framework (whose module layer hides javac).
    create("aptTest") {
        java.srcDir("src/aptTest/java")
        compileClasspath += sourceSets.main.get().output + configurations["testCompileClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

configurations {
    named("aptTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
    named("aptTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
}

val aptTest by tasks.registering(Test::class) {
    description = "Runs annotation-processor tests outside the IntelliJ test framework."
    group = "verification"
    testClassesDirs = sourceSets["aptTest"].output.classesDirs
    classpath = sourceSets["aptTest"].runtimeClasspath
    useJUnit()
    // Cross-JDK sweep support: pass -PaptTestJdk=21 (or 17/25/etc.) to exercise
    // the processor under a specific JDK without changing the main build JVM.
    // Requires that JDK to be discoverable as a Gradle toolchain.
    (project.findProperty("aptTestJdk") as String?)?.let { v ->
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(v.toInt()))
        })
    }
    doFirst {
        logger.lifecycle("aptTest JVM: ${javaLauncher.get().metadata.javaRuntimeVersion}")
    }
    // compile-testing + JDK internals on newer JDKs need these opens
    jvmArgs(
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}

tasks.named("check") { dependsOn(aptTest) }

// Checksum Helpers
fun File.generateChecksum(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    digest.update(this.readBytes())
    return digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
}
fun File.writeChecksumFile(algorithm: String) {
    val checksum = generateChecksum(algorithm)
    val extension = when (algorithm) {
        "SHA-1" -> "sha1"
        "MD5" -> "md5"
        else -> error("Unsupported algorithm: $algorithm")
    }
    val checksumFile = File("${this.absolutePath}.$extension")
    checksumFile.writeText(checksum)
}
fun File.shouldSign(): Boolean {
    val ascFile = File("${this.absolutePath}.asc")
    return !ascFile.exists() || ascFile.lastModified() < this.lastModified()
}

// Publishing Directory
val mavenPublishDir = layout.buildDirectory.dir("publications/release")
val cleanMavenPublishDir by tasks.registering(Delete::class) {
    delete(mavenPublishDir)
}
val cleanDistributions by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("distributions"))
}

// Create Pom, Sources and Javadocs
val javadocJar by tasks.registering(Jar::class) {
    archiveBaseName.set(project.name.lowercase())
    archiveClassifier.set("javadoc")
    dependsOn(tasks.javadoc)
    from(tasks.javadoc)
    destinationDirectory.set(mavenPublishDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
val sourcesJar by tasks.registering(Jar::class) {
    archiveBaseName.set(project.name.lowercase())
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
    destinationDirectory.set(mavenPublishDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Exports required by the AST-mutation pipeline (dev.sbs.classbuilder.mutate) which
// reaches into com.sun.tools.javac.* internals. Used by both compileJava on the plugin
// jar and by downstream consumers at javac-time; the consumer-side exports must be
// configured in their own build (documented in MIGRATION.md).
val javacInternalExports = listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
)

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.compilerArgs.addAll(javacInternalExports)
    }

    named<Jar>("jar") {
        archiveBaseName.set(project.name.lowercase())
        destinationDirectory.set(mavenPublishDir)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // Generate checksum and signed files for all files in generated-resources
    val checksumAndSigning by registering {
        dependsOn(buildPlugin, sourcesJar, javadocJar, "generatePomFileForReleasePublication")
        notCompatibleWithConfigurationCache("Accesses project files dynamically.")

        // Gradle 9 removed Project.exec; ExecOperations must be injected instead.
        val execOps = project.serviceOf<org.gradle.process.ExecOperations>()
        doLast {
            val outputDir = mavenPublishDir.get().asFile
            outputDir.walkTopDown()
                .filter { it.isFile && !it.extension.matches(Regex("(sha1|md5|asc)")) }
                .forEach { file ->
                    file.writeChecksumFile("SHA-1")
                    file.writeChecksumFile("MD5")

                    if (file.shouldSign()) {
                        execOps.exec { commandLine("gpg", "-ab", file.absolutePath) }
                    }
                }
        }
    }

    // Zip the files in generated-resources into a single archive
    val mavenZip by registering(Zip::class) {
        dependsOn(checksumAndSigning, cleanDistributions)
        archiveBaseName.set("${project.name}-${project.version}-maven")
        archiveVersion.set("")
        archiveClassifier.set("")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))

        //val mavenPublicationDir = mavenPublishDir.get().asFile
        val groupPath = project.group.toString().replace('.', '/')
        val artifactId = project.name.lowercase()
        val version = project.version.toString()
        val artifactName = "${artifactId}-${version}"

        from(mavenPublishDir) {
            include("**/*") // Includes JARs, pom.xml, and all checksum files
            exclude("metadata")
            rename("pom-default.xml", "${artifactName}.pom")
            rename("${artifactName}-base.jar", "${artifactName}.jar")
            into("$groupPath/$artifactId/$version")
        }
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    named("jar") { dependsOn(cleanMavenPublishDir) }
    sourcesJar { dependsOn(cleanMavenPublishDir) }
    javadocJar { dependsOn(cleanMavenPublishDir) }
    // generatePomFileForReleasePublication has no dependency on cleanMavenPublishDir, so Gradle
    // can schedule it before the clean runs and have its output deleted. Force it to run after.
    // Use matching/configureEach because maven-publish registers this task lazily.
    matching { it.name == "generatePomFileForReleasePublication" }.configureEach { mustRunAfter(cleanMavenPublishDir) }
    // buildPlugin also outputs to distributions/; ensure it runs after the directory is cleaned
    named("buildPlugin") { mustRunAfter(cleanDistributions) }
    register("publishAndPackage") {
        dependsOn("publishToMavenLocal", mavenZip)
        // checksumAndSigning writes to publications/release/ while publishToMavenLocal reads from it;
        // serialise the two paths to prevent a race condition on that directory
        mavenZip.get().mustRunAfter("publishToMavenLocal")
    }
}

// https://central.sonatype.com/publishing
// https://plugins.jetbrains.com/plugin/27678-simplified-annotations
publishing {
    publications {
        create<MavenPublication>("release") {
            artifact(tasks.named("jar"))
            artifact(javadocJar)
            artifact(sourcesJar)

            pom {
                name.set("Simplified Annotations")
                description.set("Java annotations with companion IDE tooling: @ResourcePath (static resource-path validation), @XContract (superset of @Contract), and @ClassBuilder (annotation-processor builder generation with richer setter shapes than Lombok).")
                url.set("https://github.com/SkyBlock-Simplified/" + project.name.lowercase())
                artifactId = project.name.lowercase()
                version = project.version.toString()

                developers {
                    developer {
                        name.set("CraftedFury")
                        organization.set("SkyBlock Simplified")
                        organizationUrl.set("https://sbs.dev/")
                    }
                }

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/SkyBlock-Simplified/" + project.name.lowercase() + ".git")
                    developerConnection.set("scm:git:ssh://github.com:SkyBlock-Simplified/" + project.name.lowercase() + ".git")
                    url.set("https://github.com/SkyBlock-Simplified/" + project.name.lowercase())
                }
            }
        }
    }
}
