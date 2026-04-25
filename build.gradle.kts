// Root project - intentionally minimal. Declares plugin versions once so
// subprojects can apply them by id without redeclaring; shared coordinates
// (group, version) flow to subprojects via allprojects { } below.
//
// All actual build logic lives in library/build.gradle.kts and
// plugin/build.gradle.kts. This file should stay small - any logic that
// fits in only one module should live there; logic that fits in both
// should still be duplicated for locality, unless it grows past ~30 lines.

plugins {
    id("org.jetbrains.intellij.platform") version "2.14.0" apply false
    id("org.jetbrains.changelog") version "2.2.1" apply false
}

allprojects {
    group = "io.github.simplified-dev"
    version = "2.0.0"
}
