// Points every javadoc task at an expanded copy of its own sources, so links to
// members the annotation processor generates resolve.
//
// The javadoc tool runs no annotation processors and cannot be made to, so a
// {@link #getFoo()} against a generated accessor is a dangling reference in any
// run that reads source. The processor writes an expanded copy of each
// compilation unit during the ordinary compile, and the javadoc task reads that
// copy instead of the original tree.
//
// Apply with:  ./gradlew javadoc -I gradle/expand-javadoc.init.gradle.kts

import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

allprojects {
    val expanded = layout.buildDirectory.dir("expanded-sources")

    tasks.withType<JavaCompile>().configureEach {
        // One directory per source set, so main's expansion cannot overwrite
        // test's copy of a class with the same qualified name.
        val into = expanded.get().asFile.resolve(name.removePrefix("compile").removeSuffix("Java")
            .replaceFirstChar { it.lowercase() }.ifEmpty { "main" })
        options.compilerArgs.add("-Adev.simplified.expandTo=$into")
        outputs.dir(into)
    }

    tasks.withType<Javadoc>().configureEach {
        val compileTask = tasks.findByName(
            "compile" + name.removeSuffix("Javadoc").replaceFirstChar { it.uppercase() }
                .ifEmpty { "Main" } + "Java"
        ) as? JavaCompile ?: tasks.findByName("compileJava") as? JavaCompile

        if (compileTask != null) {
            dependsOn(compileTask)
            val into = compileTask.options.compilerArgs
                .firstOrNull { it.startsWith("-Adev.simplified.expandTo=") }
                ?.removePrefix("-Adev.simplified.expandTo=")
            if (into != null) setSource(fileTree(into) { include("**/*.java") })
        }

        val opts = options as StandardJavadocDocletOptions
        opts.encoding = "UTF-8"
        opts.addStringOption("Xmaxwarns", "10000")
        opts.addStringOption("Xmaxerrs", "10000")
    }
}
