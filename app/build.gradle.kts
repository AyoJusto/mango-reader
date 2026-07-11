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
        }
    }
}

// JCEF needs these AWT internals opened on JDK 16+ (jcefmaven README).
val jcefJvmArgs = listOf(
    "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
)

compose.desktop {
    application {
        mainClass = "dev.mango.app.MainKt"
        // stock JDK can't load TruffleAttach (optimized Truffle unavailable — GraalJS runs
        // interpreted; fine, extension calls are network-bound). Silence the boot warning.
        jvmArgs("-Dpolyglotimpl.AttachLibraryFailureAction=ignore")
        jvmArgs(*jcefJvmArgs.toTypedArray())
    }
}

