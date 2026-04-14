import java.security.MessageDigest

plugins {
    id("java")
    id("signing")
    id("maven-publish")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "dev.sbs"
version = "1.0.5"

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
            <ul>
              <li><b>New @XContract annotation</b> - superset of JetBrains @Contract with relational comparisons, &amp;&amp;/|| grouping, named-parameter references, instanceof checks, typed throws returns, chained comparisons, and full pure/mutates support. A synthetic @Contract is inferred so IntelliJ data-flow works from a single annotation.</li>
              <li><b>New XContract Call-Site inspection</b> - flags calls whose literal arguments deterministically trigger a fail or throws clause.</li>
              <li><b>New ResourcePath Base-Prefix Usage inspection</b> - warns when a @ResourcePath(base="X") parameter is passed raw into a resource-loading call, with a quick-fix that prepends X/. Also flags base mismatches across call boundaries.</li>
              <li><b>ResourcePath freeze fix</b> - removed the project-wide ReferencesSearch that locked up the IDE on large utility files.</li>
              <li><b>Modernised PSI listener</b> - narrowed to annotation events only; replaced deprecated DaemonCodeAnalyzer.restart(PsiFile).</li>
              <li><b>New settings</b> - additional resource-root paths, glob-based file exclusions, split severity dropdowns, inheritance and mutates checks.</li>
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

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
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

        doLast {
            val outputDir = mavenPublishDir.get().asFile
            outputDir.walkTopDown()
                .filter { it.isFile && !it.extension.matches(Regex("(sha1|md5|asc)")) }
                .forEach { file ->
                    file.writeChecksumFile("SHA-1")
                    file.writeChecksumFile("MD5")

                    if (file.shouldSign()) {
                        exec { commandLine("gpg", "-ab", file.absolutePath) }
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
                description.set("This plugin evaluates string expressions marked with the @ResourcePath annotation to check if resource files exist.")
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
