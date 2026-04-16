import org.gradle.external.javadoc.CoreJavadocOptions

plugins {
    `java-library`
    signing
    `maven-publish`
    idea  // so IntelliJ marks src/aptTest as test sources (see idea { } block below)
}

// ----------------------------------------------------------------------------
// Coordinates (inherited group/version live on the root project)
// ----------------------------------------------------------------------------

description = "Java annotations with companion IDE tooling: @ResourcePath (static resource-path validation), @XContract (superset of @Contract), and @ClassBuilder (annotation-processor builder generation with richer setter shapes than Lombok)."

val githubOrg = "SkyBlock-Simplified"
val pluginArtifactId = "simplified-annotations"
val githubUrl = "https://github.com/$githubOrg/$pluginArtifactId"

// ----------------------------------------------------------------------------
// Repositories + dependencies
// ----------------------------------------------------------------------------

repositories {
    mavenCentral()
}

dependencies {
    // @NotNull / @Nullable / @PrintFormat appear both on the annotation
    // surface (ClassBuilder.java, ResourcePath.java) and in BuilderEmitter's
    // generated source text. "api" scope propagates to downstream consumers
    // via Maven so their compile paths resolve the annotations without an
    // explicit dep. IntelliJ-plugin runtime already ships its own copy of
    // org.jetbrains.annotations; the plugin-packaging pipeline de-dupes.
    api("org.jetbrains:annotations:24.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.testing.compile:compile-testing:0.21.0")
}

// ----------------------------------------------------------------------------
// Java configuration
// ----------------------------------------------------------------------------

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
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
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
)

val javacRuntimeExports = javacCompileExports + listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"
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

// aptTest-only deps: declared here because the source set's configurations
// only exist after sourceSets { create("aptTest") { ... } } above.
// ASM inspects class-file-retention @XContract annotations on generated
// builder methods - reflection can't see them, so we parse the class-file
// bytes directly. 9.8+ handles class-file version 69 (JDK 25).
dependencies {
    "aptTestImplementation"("org.ow2.asm:asm:9.8")
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

// Tell IntelliJ that src/aptTest is a test-scoped source root. Without this
// the IDE imports the directory as "production" sources, which paints test
// classes with the wrong icon, puts them in the wrong tool-window scope,
// and offers the wrong "Create Test" / "Run Test" actions. The Gradle
// Java plugin's source-set classification stays as-is - this only affects
// IDE metadata.
idea {
    module {
        testSources.from(sourceSets["aptTest"].java.srcDirs)
        testResources.from(sourceSets["aptTest"].resources.srcDirs)
    }
}

// ----------------------------------------------------------------------------
// showcase source set + runnable jar + integration test wiring
//
// The showcase source set is a consumer-style fixture: @ClassBuilder types
// annotated with every runtime-observable configuration, plus a main() that
// exercises the generated builders. Packaged as a standalone runnable jar
// and exercised by BuildRuleShowcaseIntegrationTest in src/test.
//
// CRITICAL: the showcase jar is INTERNAL verification only. It is never
// added to the "release" maven publication, and its output directory is
// build/showcase/ rather than build/libs/ so the central-staging bundle
// and any "take everything in build/libs" wiring physically cannot see it.
// ----------------------------------------------------------------------------

sourceSets {
    create("showcase") {
        java.srcDir("src/showcase/java")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += output + compileClasspath
    }
}

configurations {
    named("showcaseImplementation") { extendsFrom(configurations.implementation.get()) }
    named("showcaseRuntimeOnly")    { extendsFrom(configurations.runtimeOnly.get()) }
}

tasks.named<JavaCompile>("compileShowcaseJava") {
    // The APT's static initializer opens jdk.compiler/com.sun.tools.javac.*
    // automatically via the JavacAccess bootstrap, so no --add-exports fork
    // args are needed for consumer builds. Just point the processor path at
    // the library output so ClassBuilderProcessor's service file is found.
    options.annotationProcessorPath = files(sourceSets.main.get().output) +
        configurations.compileClasspath.get()
}

val showcaseJar by tasks.registering(Jar::class) {
    group = "verification"
    description = "Packages the @ClassBuilder runtime showcase as a standalone runnable jar."
    archiveClassifier.set("showcase")
    destinationDirectory.set(layout.buildDirectory.dir("showcase"))
    dependsOn(tasks.named("compileShowcaseJava"))
    from(sourceSets["showcase"].output)
    from(sourceSets.main.get().output)
    manifest {
        attributes("Main-Class" to "dev.sbs.classbuilder.showcase.BuildRuleShowcase")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    dependsOn(showcaseJar)
    systemProperty(
        "showcase.jar",
        showcaseJar.flatMap { it.archiveFile }.get().asFile.absolutePath
    )
    systemProperty(
        "showcase.output.dir",
        layout.buildDirectory.dir("showcase-output").get().asFile.absolutePath
    )
}

// ----------------------------------------------------------------------------
// Maven publication
//   https://central.sonatype.com/publishing
//
// The library jar here is exactly what Maven Central consumers download;
// the IntelliJ-plugin integration ships from the sibling :plugin module.
// Standard java component carries jar + sourcesJar + javadocJar from the
// java { withSourcesJar/withJavadocJar } block above, so from(components["java"])
// produces a clean artifact set without extra classifier variants.
// ----------------------------------------------------------------------------

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
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
