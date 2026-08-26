import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipFile

// Root project - intentionally minimal. Declares plugin versions once so
// subprojects can apply them by id without redeclaring; shared coordinates
// (group, version) flow to subprojects via allprojects { } below.
//
// Almost all build logic lives in library/build.gradle.kts and
// plugin/build.gradle.kts. This file should stay small - any logic that
// fits in only one module should live there; logic that fits in both
// should still be duplicated for locality, unless it grows past ~30 lines.
//
// The release block at the bottom is the exception the rule allows for: a
// release is one plugin zip and one Maven Central bundle checked and shipped
// together, and neither module can see the other's half of it.

plugins {
    id("org.jetbrains.intellij.platform") version "2.14.0" apply false
    id("org.jetbrains.changelog") version "2.2.1" apply false
}

allprojects {
    group = "io.github.simplified-dev"
    version = "2.6.3"
}

// ----------------------------------------------------------------------------
// Publishing
//
//   ./gradlew publishBuild        build both, the bundle signed
//   ./gradlew publishValidate     the above, then check what was built
//   ./gradlew publishLocal        install the library into ~/.m2
//   ./gradlew publishCentral      validate, then upload the bundle to Central
//   ./gradlew publishMarketplace  validate, then upload the plugin
//
// Signing is not asked for on the command line. These tasks exist to produce
// artifacts fit to upload and an unsigned one is not, so requesting one turns
// signing on - see the note in library/build.gradle.kts for why that is read
// off the invocation rather than set from here. publishLocal is deliberately
// outside that set: it exists to try a build against a real consumer, which is
// a thing to be able to do without holding a GPG key.
//
// The two upload tasks are named for where they send rather than for what they
// send, because `publishPlugin` is already a task on :plugin and a second one
// of that name at the root would make `gradlew publishPlugin` mean both.
// ----------------------------------------------------------------------------

// The root is evaluated before its subprojects, so the two archive tasks do not
// exist yet when this script runs. Asking for them first is what makes the
// lookups below resolve - and the archive names stay owned by the module that
// builds each one, rather than being restated here where they would drift.
evaluationDependsOn(":plugin")
evaluationDependsOn(":library")

// Read outside the task blocks below: inside one, `group` is the task's own.
val releaseGroup = group.toString()
val releaseVersion = version.toString()
val pluginArchive = project(":plugin").tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
val libraryArchive = project(":library").tasks.named<Zip>("centralBundle").flatMap { it.archiveFile }

val publishBuild by tasks.registering {
    group = "publish"
    description = "Builds the plugin distribution and the signed Maven Central bundle."
    dependsOn(":plugin:buildPlugin", ":library:centralBundle")

    val plugin = pluginArchive
    val library = libraryArchive
    doLast {
        logger.lifecycle("plugin   ${plugin.get().asFile}")
        logger.lifecycle("library  ${library.get().asFile}")
    }
}

val publishValidate by tasks.registering {
    group = "publish"
    description = "Checks the built artifacts, reading both archives in place rather than unpacking them."
    dependsOn(publishBuild)

    val plugin = pluginArchive
    val library = libraryArchive
    val expected = releaseVersion
    doLast {
        // Declared inside the action rather than beside the task. A function at
        // the top level of a Kotlin build script is a member of the script, so
        // calling one from a task action captures the script - and with it the
        // Project - which the configuration cache rejects.
        fun entriesOf(archive: File): List<String> =
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            }

        fun armorOf(archive: File, entry: String): String =
            ZipFile(archive).use { zip ->
                zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }.decodeToString().trim()
            }

        val problems = mutableListOf<String>()

        // The bundle. Every jar, pom and module Central will accept has to
        // carry a signature beside it, and the bundle has to hold one version -
        // the staging directory is an ordinary folder, so a release published
        // into one that still held the last would ship both.
        val bundle = library.get().asFile
        val bundled = entriesOf(bundle)
        val versions = bundled
            .map { it.substringBeforeLast('/').substringAfterLast('/') }
            .filter { it.isNotEmpty() && it.first().isDigit() }
            .distinct()
            .sorted()
        if (versions != listOf(expected)) {
            problems += "bundle carries version(s) $versions where the build is $expected"
        }

        val signatures = bundled.filter { it.endsWith(".asc") }.toSet()
        val signable = bundled.filter {
            it.endsWith(".jar") || it.endsWith(".pom") || it.endsWith(".module")
        }
        if (signable.isEmpty()) problems += "bundle carries nothing that can be signed"
        signable.filterNot { "$it.asc" in signatures }.forEach {
            problems += "no signature beside ${it.substringAfterLast('/')}"
        }
        signatures.forEach {
            val armor = armorOf(bundle, it)
            if (!armor.startsWith("-----BEGIN PGP SIGNATURE-----")
                || !armor.endsWith("-----END PGP SIGNATURE-----")) {
                problems += "${it.substringAfterLast('/')} is not an armored PGP signature"
            }
        }

        // The plugin zip. It carries no signature of its own - that is a
        // JetBrains certificate this project does not hold - so what is checked
        // is that the zip is the version being released. Both jars are named
        // from the version, which is what catches a zip left behind by an
        // earlier build and reported up to date.
        val distribution = plugin.get().asFile
        val packaged = entriesOf(distribution)
        if (packaged.none { it.endsWith("/plugin-$expected.jar") }) {
            problems += "no plugin-$expected.jar in ${distribution.name}"
        }
        if (packaged.none { it.endsWith("/library-$expected.jar") }) {
            problems += "no library-$expected.jar bundled in ${distribution.name}"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString(
                    prefix = "release artifacts are not fit to upload:\n  - ",
                    separator = "\n  - "))
        }

        logger.lifecycle("bundle   ${bundle.name}: ${signable.size} artifacts, all signed")
        logger.lifecycle("plugin   ${distribution.name}: $expected, unsigned (no JetBrains certificate configured)")
    }
}

val publishLocal by tasks.registering {
    group = "publish"
    description = "Installs the library into the local Maven repository, for trying it from a real consumer."
    dependsOn(":library:publishToMavenLocal")

    val coordinates = "$releaseGroup:annotations:$releaseVersion"
    doLast {
        logger.lifecycle("installed $coordinates into ~/.m2")
    }
}

val publishCentral by tasks.registering {
    group = "publish"
    description = "Validates the artifacts, then uploads the bundle to Maven Central."
    dependsOn(publishValidate)

    val library = libraryArchive
    val expected = releaseVersion
    // Read here rather than in the action: a gradle property is configuration
    // input, and asking for it at execution time is what the configuration
    // cache cannot replay.
    val answer = providers.gradleProperty("upload")
    val token = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
        .orElse(providers.gradleProperty("mavenCentralToken"))
    // Copied to a local like everything else the action reads. A script-level
    // val is a field of the script, so naming one inside a task action captures
    // the script itself, which the configuration cache refuses to store.
    val coordinates = "$releaseGroup:annotations:$releaseVersion"
    doLast {
        // Local, for the same reason the checks above are: a function beside
        // the task is a member of the script, and a task action that calls one
        // captures the Project with it.
        fun confirmed(written: String?): Boolean = when (written?.trim()?.lowercase()) {
            "yes", "y", "true" -> true
            "no", "n", "false" -> false
            null -> {
                val prompt = "Upload $expected to Maven Central? [y/N] "
                val console = System.console()
                val reply = if (console != null) console.readLine(prompt) else {
                    print(prompt)
                    System.out.flush()
                    readlnOrNull()
                } ?: throw GradleException("no console to ask on - pass -Pupload=yes or -Pupload=no")
                reply.trim().lowercase() in setOf("y", "yes")
            }
            else -> throw GradleException("-Pupload takes yes or no, not '$written'")
        }

        if (!confirmed(answer.orNull)) {
            logger.lifecycle("Declined - nothing was sent. The bundle is where it was built:")
            logger.lifecycle("  ${library.get().asFile}")
            return@doLast
        }

        val written = token.orNull?.trim()
            ?: throw GradleException(
                "no Maven Central token - set MAVEN_CENTRAL_TOKEN, or mavenCentralToken "
                    + "in ~/.gradle/gradle.properties")
        // The Portal wants base64 of "user:pass". A token pasted as the pair
        // still has its colon and base64 never does, so which one was given is
        // readable from the value - and encoding it here is one less way to
        // earn a 401 that explains nothing.
        val bearer = if (written.contains(':')) {
            Base64.getEncoder().encodeToString(written.toByteArray())
        } else {
            written
        }

        val bundle = library.get().asFile
        val boundary = "simplified-${bundle.length()}-$expected"
        val opening = ("--$boundary\r\n"
            + "Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val closing = "\r\n--$boundary--\r\n".toByteArray()

        // Names the deployment as the tail of the purl the page prints for the
        // component it holds - pkg:maven/<namespace>/annotations@<version> - so
        // the two read as the same thing on the only page either appears on.
        // Encoded because the shape carries an '@'.
        val deploymentName = URLEncoder.encode("annotations@$expected", StandardCharsets.UTF_8)

        val http = HttpClient.newHttpClient()
        val upload = HttpRequest.newBuilder()
            .uri(URI.create("https://central.sonatype.com/api/v1/publisher/upload"
                + "?publishingType=AUTOMATIC&name=$deploymentName"))
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(opening + bundle.readBytes() + closing))
            .build()

        val accepted = http.send(upload, HttpResponse.BodyHandlers.ofString())
        if (accepted.statusCode() !in 200..299) {
            throw GradleException(
                "Central rejected the bundle (${accepted.statusCode()}): ${accepted.body()}")
        }
        val deployment = accepted.body().trim()
        logger.lifecycle("uploaded as $deployment, publishing automatically")

        // AUTOMATIC publishes on its own once validation passes, so the states
        // worth waiting for are the one that says it failed and the one that
        // says it is past validating. Read with a regex rather than a JSON
        // parser: one field is wanted, and the whole body is printed on the
        // failure that is the only time the rest of it matters.
        val reported = Regex(""""deploymentState"\s*:\s*"([A-Z_]+)"""")
        var last = "PENDING"
        for (attempt in 1..60) {
            Thread.sleep(5_000)
            val status = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(
                        "https://central.sonatype.com/api/v1/publisher/status?id=$deployment"))
                    .header("Authorization", "Bearer $bearer")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            val state = reported.find(status.body())?.groupValues?.get(1) ?: "UNKNOWN"
            if (state != last) {
                logger.lifecycle("  $state")
                last = state
            }
            when (state) {
                "FAILED" -> throw GradleException(
                    "Central failed the deployment $deployment:\n${status.body()}")
                "PUBLISHING", "PUBLISHED" -> {
                    logger.lifecycle("$coordinates is $state")
                    return@doLast
                }
            }
        }
        throw GradleException(
            "deployment $deployment was still $last after five minutes - it may yet finish, "
                + "so check https://central.sonatype.com/publishing/deployments rather than "
                + "uploading it again")
    }
}

// The Marketplace upload is :plugin:publishPlugin, which already knows how to
// talk to the service - so the confirmation gates that task rather than a copy
// of it. It has to be onlyIf and not a doLast check: a dependency runs before
// the task that declares it, so a confirmation asked there would be asked after
// the upload it is meant to authorise.
val marketplaceUpload = project(":plugin").tasks.named("publishPlugin")
marketplaceUpload.configure {
    mustRunAfter(publishValidate)
    val expected = releaseVersion
    val answer = providers.gradleProperty("upload")
    onlyIf { task ->
        val decided = when (val written = answer.orNull?.trim()?.lowercase()) {
            "yes", "y", "true" -> true
            "no", "n", "false" -> false
            null -> {
                val prompt = "Upload $expected to the JetBrains Marketplace? [y/N] "
                val console = System.console()
                val reply = if (console != null) console.readLine(prompt) else {
                    print(prompt)
                    System.out.flush()
                    readlnOrNull()
                } ?: throw GradleException("no console to ask on - pass -Pupload=yes or -Pupload=no")
                reply.trim().lowercase() in setOf("y", "yes")
            }
            else -> throw GradleException("-Pupload takes yes or no, not '$written'")
        }
        if (!decided) task.logger.lifecycle("Declined - nothing was sent to the Marketplace.")
        decided
    }
}

val publishMarketplace by tasks.registering {
    group = "publish"
    description = "Validates the artifacts, then uploads the plugin to the JetBrains Marketplace."
    dependsOn(publishValidate, marketplaceUpload)

    val plugin = pluginArchive
    val expected = releaseVersion
    doLast {
        logger.lifecycle("plugin $expected left at ${plugin.get().asFile}")
    }
}
