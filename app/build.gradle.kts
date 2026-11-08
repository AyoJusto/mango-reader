plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":core"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.components.resources)
            // real backdrop blur (frosted sidebar panel) — Compose has no built-in
            // way to blur only the region behind an overlay
            implementation(libs.haze)
            implementation(libs.nucleus.application)
            implementation(libs.nucleus.decorated.window.tao)
            implementation(libs.kotlinx.coroutines.core)
            // Provides Dispatchers.Main (Swing EDT) on the JVM — nothing supplies it
            // transitively, and Coil's enqueue() dispatches to Main for prefetch
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.cio)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            // :app opens its own DB file (composition root); :core keeps its driver private
            implementation(libs.sqldelight.sqlite.driver)
            // ktor logs through slf4j; without a provider every launch prints NOP warnings
            runtimeOnly(libs.slf4j.simple)
            // M4.3b: embedded Chromium (JCEF) for the Cloudflare solve — CEF natives
            // download at runtime into a dir we choose, not bundled in the jar
            implementation(libs.jcefmaven)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.ui.test.junit4)
            implementation(libs.ktor.client.mock)
            // uiTestJUnit4 tests are JUnit4; without the vintage engine the JUnit5 platform
            // silently discovers zero tests in them and the suite "passes" without running
            runtimeOnly(libs.junit.vintage.engine)
        }
    }
}

// JCEF needs these AWT internals opened on JDK 16+ (jcefmaven README). sun.lwawt* exist only
// in the macOS java.desktop module; opening them on Windows/Linux is a no-op the JVM warns
// about ("package sun.lwawt not in java.desktop"), so add them only where they exist.
val jcefJvmArgs = buildList {
    add("--add-opens"); add("java.desktop/sun.awt=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        add("--add-opens"); add("java.desktop/sun.lwawt=ALL-UNNAMED")
        add("--add-opens"); add("java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    }
}

/**
 * Silences boot warnings on modern JDKs: sqlite-jdbc loads its native library via the
 * restricted System::load (warned since JDK 24, needs --enable-native-access), and Truffle
 * still uses sun.misc.Unsafe field offsets (warned since JDK 24, allowed via a flag that
 * exists only on JDK 23+). Older JVMs neither warn nor accept the flags, hence the gates.
 */
fun quietNativeWarningArgs(major: Int) = buildList {
    if (major >= 22) add("--enable-native-access=ALL-UNNAMED")
    if (major >= 23) add("--sun-misc-unsafe-memory-access=allow")
}

compose.resources {
    packageOfResClass = "dev.mango.app.resources"
    // Auto-detection looks for the resources dependency on commonMain; this module is
    // JVM-only and declares it on jvmMain instead, so ask explicitly.
    generateResClass = always
}

compose.desktop {
    application {
        mainClass = "dev.mango.app.MainKt"
        // Plain Temurin, never a JetBrains Runtime: a JBR bundling JCEF breaks jcefmaven.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }.get().metadata.installationPath.asFile.absolutePath
        // stock JDK can't load TruffleAttach (optimized Truffle unavailable — GraalJS runs
        // interpreted; fine, extension calls are network-bound). Silence the boot warning.
        jvmArgs("-Dpolyglotimpl.AttachLibraryFailureAction=ignore")
        jvmArgs(*jcefJvmArgs.toTypedArray())
        jvmArgs(*quietNativeWarningArgs(25).toTypedArray())
        nativeDistributions {
            packageName = "mango"
            windows {
                // Regenerated from design/icon/mango.svg by IconRenderTest; see its KDoc.
                iconFile.set(project.file("icons/mango.ico"))
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform {
        // live tests open a real browser / hit the network (and download CEF once); run them
        // on demand with -Plive, never on a normal test run
        if (!project.hasProperty("live")) excludeTags("live")
    }
    // JCEF needs the same AWT opens in the test JVM when a live test drives it
    jvmArgs(jcefJvmArgs)
    jvmArgs(quietNativeWarningArgs(25))
}

