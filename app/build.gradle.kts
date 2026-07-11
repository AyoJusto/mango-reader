plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":core"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
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
            implementation(compose.desktop.uiTestJUnit4)
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

compose.desktop {
    application {
        mainClass = "dev.mango.app.MainKt"
        // stock JDK can't load TruffleAttach (optimized Truffle unavailable — GraalJS runs
        // interpreted; fine, extension calls are network-bound). Silence the boot warning.
        jvmArgs("-Dpolyglotimpl.AttachLibraryFailureAction=ignore")
        jvmArgs(*jcefJvmArgs.toTypedArray())
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
}

