import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

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
    // The annotation + processor + mutator live in the sibling library
    // module. implementation scope puts the library jar on the plugin's
    // runtime classpath and gets it bundled under lib/ in the IntelliJ
    // plugin distribution zip so the IDE can load it at runtime.
    implementation(project(":library"))

    intellijPlatform {
        create("IC", "2023.3")
        testFramework(TestFrameworkType.Platform)
        // Since IC 2023.3, UsefulTestCase's static initializer references
        // com.intellij.testFramework.common.TestEnvironmentKt, which ships in
        // the IDE's bundled lib/testFramework.jar rather than the Maven
        // test-framework artifact. Pull the bundled jar explicitly; without
        // it every test fails to initialize with NoClassDefFoundError.
        testFramework(TestFrameworkType.Bundled)
        // Adds LightJavaCodeInsightFixtureTestCase + JAVA_NN project descriptors
        // (mock JDK), needed by AnnotationTest / ClassBuilder*Test for proper
        // String/Object resolution during inspection highlighting.
        testFramework(TestFrameworkType.Plugin.Java)
        bundledPlugin("com.intellij.java")
    }

    testImplementation("junit:junit:4.13.2")
}

// ----------------------------------------------------------------------------
// Java configuration
// ----------------------------------------------------------------------------

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ----------------------------------------------------------------------------
// IntelliJ platform: plugin metadata + verifier
// ----------------------------------------------------------------------------

// The default buildPlugin zip derives its name from the subproject
// ("plugin"), producing plugin-<version>.zip. Override to "Simplified-
// Annotations" so the final artifact reads "Simplified-Annotations-
// <version>.zip" and the internal folder matches. Cannot use
// rootProject.name here since the repo is named "annotations" but the
// plugin's display name on the marketplace is "Simplified Annotations".
tasks.named<Zip>("buildPlugin") {
    archiveBaseName = "Simplified-Annotations"
}

intellijPlatform {
    pluginConfiguration {
        name = "Simplified Annotations"

        ideaVersion {
            sinceBuild = "232"
        }

        // Pulled from CHANGELOG.md (at the repo root) by the changelog plugin.
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
    // Task requires a running IDE (233+) since IntelliJ Platform Gradle Plugin
    // 2.14.0, but we still compile against 232. Disabling skips the marketplace-
    // settings pre-index step; plugin still loads fine in 2023.2+ consumers.
    buildSearchableOptions = false

    // Plugin Verifier: catches API breakage across IDE versions before users
    // hit it. Pinned to explicit released builds rather than recommended()
    // because the latter pulls in unreleased EAP IDEs that fail to download.
    pluginVerification {
        ides {
            // Community distribution was retired after 2024.3; 2025.3+ ships
            // only the unified IntellijIdea type (Community + Ultimate merged).
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
        }
    }
}

// ----------------------------------------------------------------------------
// Changelog plugin: drives changeNotes above by parsing CHANGELOG.md.
// Lives at the repo root so both modules can reference the same source.
// ----------------------------------------------------------------------------

changelog {
    version.set(project.version.toString())
    path.set(rootProject.file("CHANGELOG.md").canonicalPath)
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"))
    keepUnreleasedSection.set(true)
}

