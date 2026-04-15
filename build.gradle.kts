import org.gradle.external.javadoc.CoreJavadocOptions
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("signing")
    id("maven-publish")
    id("org.jetbrains.intellij.platform") version "2.6.0"
    id("org.jetbrains.changelog") version "2.2.1"
}

// ----------------------------------------------------------------------------
// Coordinates
// ----------------------------------------------------------------------------

group = "dev.sbs"
version = "1.2.0"
description = "Java annotations with companion IDE tooling: @ResourcePath (static resource-path validation), @XContract (superset of @Contract), and @ClassBuilder (annotation-processor builder generation with richer setter shapes than Lombok)."

val githubOrg = "SkyBlock-Simplified"
val pluginArtifactId = project.name.lowercase()
val githubUrl = "https://github.com/$githubOrg/$pluginArtifactId"

// ----------------------------------------------------------------------------
// Repositories + dependencies
// ----------------------------------------------------------------------------

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2023.2")
        testFramework(TestFrameworkType.Platform)
        // Adds LightJavaCodeInsightFixtureTestCase + JAVA_NN project descriptors
        // (mock JDK), needed by AnnotationTest / ClassBuilder*Test for proper
        // String/Object resolution during inspection highlighting.
        testFramework(TestFrameworkType.Plugin.Java)
        bundledPlugin("com.intellij.java")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.testing.compile:compile-testing:0.21.0")
}

// ----------------------------------------------------------------------------
// Java configuration
// ----------------------------------------------------------------------------

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Enables sourcesJar / javadocJar tasks, registered as classifier
    // artifacts on the "java" software component so the publication picks
    // them up automatically via from(components["java"]) below.
    withSourcesJar()
    withJavadocJar()
}

// ----------------------------------------------------------------------------
// IntelliJ platform: plugin metadata + verifier
// ----------------------------------------------------------------------------

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "232"
        }

        // Pulled from CHANGELOG.md by the org.jetbrains.changelog plugin.
        // Renders only the current release's section; older versions stay
        // discoverable on the marketplace's release-history view.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML
                )
            }
        }
    }
    buildSearchableOptions = true

    // Plugin Verifier: catches API breakage across IDE versions before users
    // hit it. Pinned to explicit released builds rather than recommended()
    // because the latter pulls in unreleased EAP IDEs that fail to download.
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2023.2")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
        }
    }
}

// ----------------------------------------------------------------------------
// Changelog plugin: drives changeNotes above by parsing CHANGELOG.md.
// ----------------------------------------------------------------------------

changelog {
    version.set(project.version.toString())
    path.set(file("CHANGELOG.md").canonicalPath)
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"))
    keepUnreleasedSection.set(true)
}

// ----------------------------------------------------------------------------
// javac internal exports
//
// Two related-but-distinct sets:
//   javacCompileExports - the six packages the mutator itself imports.
//   javacRuntimeExports - a superset with four additional packages that
//     compile-testing reaches into when it drives javac inside the test JVM.
// Compile uses the small set; aptTest's runtime uses the large set.
// ----------------------------------------------------------------------------

val javacCompileExports = listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
)

val javacRuntimeExports = javacCompileExports + listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED"
)

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(javacCompileExports)
}

// Javadoc resolves source against module boundaries the same way the
// compiler does, so it needs the same exports to see com.sun.tools.javac.*.
// addMultilineStringsOption emits one --add-exports flag per value;
// addStringOption would silently overwrite on duplicate keys.
// Doclint disabled because internal javac classes referenced in our @link /
// @see tags have lint-noisy javadoc that we can't control.
tasks.withType<Javadoc>().configureEach {
    val opts = options as CoreJavadocOptions
    opts.addMultilineStringsOption("-add-exports").value =
        javacCompileExports.map { it.removePrefix("--add-exports=") }
    opts.addStringOption("Xdoclint:none", "-quiet")
}

// ----------------------------------------------------------------------------
// aptTest source set + task
// ----------------------------------------------------------------------------

sourceSets {
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
    jvmArgs(javacRuntimeExports)

    // Cross-JDK sweep support: pass -PaptTestJdk=21 (or 17/25/etc.) to exercise
    // the processor under a specific JDK without changing the main build JVM.
    // Requires that JDK to be discoverable as a Gradle toolchain.
    (project.findProperty("aptTestJdk") as String?)?.toIntOrNull()?.let { feature ->
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(feature))
        })
    }
    doFirst {
        logger.lifecycle("aptTest JVM: ${javaLauncher.get().metadata.javaRuntimeVersion}")
    }
}

tasks.named("check") { dependsOn(aptTest) }

// ----------------------------------------------------------------------------
// Maven publication
//   https://central.sonatype.com/publishing
//   https://plugins.jetbrains.com/plugin/27678-simplified-annotations
//
// Publishes the regular jar + sources + javadoc to Maven Local for testing
// and to a local "centralStaging" repository whose contents are zipped by
// the centralBundle task into a Central Publisher Portal upload bundle.
// Checksums (.md5, .sha1, .sha256, .sha512) and signatures (.asc) are
// produced automatically by the maven-publish + signing plugin pair - no
// custom file walking required.
// ----------------------------------------------------------------------------

// Gradle Module Metadata attaches the java component's apiElements and
// runtimeElements variants, which the IntelliJ Platform Plugin backs with
// the un-instrumented -base jar. Disabling the .module file strips that
// reference so only the explicit artifacts below reach Maven Central.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

publishing {
    publications {
        create<MavenPublication>("release") {
            // Explicit artifacts rather than from(components["java"]) - the
            // IntelliJ Platform Plugin registers a "base" classifier variant
            // (pre-instrumentation jar) on the java component which leaks
            // into the Central bundle as an unwanted -base.jar. The project
            // has no runtime dependencies so skipping components["java"]
            // doesn't lose dependency metadata from the POM.
            //
            // composedJar (not jar) is the main artifact - the plugin's jar
            // task output gets redirected to an empty stub when buildSearch-
            // ableOptions is enabled, whereas composedJar always contains
            // the full instrumented-classes + plugin.xml archive.
            artifact(tasks.named("composedJar"))
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
            artifactId = pluginArtifactId

            pom {
                name.set("Simplified Annotations")
                description.set(project.description)
                url.set(githubUrl)

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        name.set("CraftedFury")
                        organization.set("SkyBlock Simplified")
                        organizationUrl.set("https://sbs.dev/")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/$githubOrg/$pluginArtifactId.git")
                    developerConnection.set("scm:git:ssh://github.com:$githubOrg/$pluginArtifactId.git")
                    url.set(githubUrl)
                }
            }
        }
    }

    repositories {
        // The bundle for upload to https://central.sonatype.com is just a zip
        // of this directory, produced by the centralBundle task below.
        maven {
            name = "centralStaging"
            url = uri(layout.buildDirectory.dir("central-staging"))
        }
    }
}

// Signing is opt-in via -PsignArtifacts=true (or in ~/.gradle/gradle.properties).
// Defaults OFF so local developers without GPG can run the publish flow against
// the staging repo to verify mechanics; releases to Central must opt in. Uses
// the gpg CLI's default secret key when enabled (gpg --list-secret-keys).
val signArtifacts = providers.gradleProperty("signArtifacts").orNull?.toBoolean() == true
if (signArtifacts) {
    signing {
        useGpgCmd()
        sign(publishing.publications["release"])
    }
}

// ----------------------------------------------------------------------------
// Central Publisher Portal upload bundle
// ----------------------------------------------------------------------------

val centralBundle by tasks.registering(Zip::class) {
    description = "Builds the Maven Central Publisher Portal upload bundle from the staged publication."
    group = "publishing"
    dependsOn("publishReleasePublicationToCentralStagingRepository")

    archiveBaseName.set("$pluginArtifactId-${project.version}-bundle")
    archiveClassifier.set("")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("central-staging"))
}

tasks.register("publishAndPackage") {
    description = "Publishes the plugin to Maven Local and builds the Central upload bundle."
    group = "publishing"
    dependsOn("publishToMavenLocal", centralBundle)
}
