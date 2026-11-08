import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
            // frosted sidebar needs real backdrop blur; Compose has no built-in region blur
            implementation(libs.haze)
            implementation(libs.nucleus.application)
            implementation(libs.nucleus.decorated.window.tao)
            implementation(libs.kotlinx.coroutines.core)
            // supplies Dispatchers.Main (Swing EDT); Coil prefetch needs it and nothing else provides it
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.cio)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            // :app opens its own DB file (composition root); :core keeps its driver private
            implementation(libs.sqldelight.sqlite.driver)
            // ktor logs through slf4j; without a provider every launch prints NOP warnings
            runtimeOnly(libs.slf4j.simple)
            // embedded Chromium for the Cloudflare solve; CEF natives download at runtime
            implementation(libs.jcefmaven)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.ui.test.junit4)
            implementation(libs.ktor.client.mock)
            // without the vintage engine JUnit5 silently discovers zero tests in the JUnit4 UI tests
            runtimeOnly(libs.junit.vintage.engine)
        }
    }
}

// sun.lwawt* exist only in macOS java.desktop; opening them elsewhere makes the JVM warn, so gate on Mac.
val jcefJvmArgs = buildList {
    add("--add-opens"); add("java.desktop/sun.awt=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        add("--add-opens"); add("java.desktop/sun.lwawt=ALL-UNNAMED")
        add("--add-opens"); add("java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    }
}

// Gated by JDK major: older JVMs neither warn nor accept these flags.
fun quietNativeWarningArgs(major: Int) = buildList {
    if (major >= 22) add("--enable-native-access=ALL-UNNAMED")
    if (major >= 23) add("--sun-misc-unsafe-memory-access=allow")
}

compose.resources {
    packageOfResClass = "dev.mango.app.resources"
    // JVM-only module declares the resources dependency on jvmMain, so auto-detection needs this.
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
        // stock JDK can't load TruffleAttach and GraalJS runs interpreted; silence the boot warning
        jvmArgs("-Dpolyglotimpl.AttachLibraryFailureAction=ignore")
        jvmArgs(*jcefJvmArgs.toTypedArray())
        jvmArgs(*quietNativeWarningArgs(25).toTypedArray())
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "mango"
            packageVersion = "1.0.0"
            description = "Manhwa reader for Paperback extensions"
            vendor = "Justin Goldstock"
            // jdeps finds the first four; the rest are needed at runtime (Truffle, charsets, locales, accessibility)
            modules(
                "java.instrument",
                "java.management",
                "java.sql",
                "jdk.unsupported",
                "jdk.management",
                "jdk.charsets",
                "jdk.localedata",
                "jdk.accessibility"
            )
            windows {
                iconFile.set(project.file("icons/mango.ico"))
                perUserInstall = true
                menuGroup = "mango"
                shortcut = true
                upgradeUuid = "8d3f2c41-6b7a-4e59-9c0d-2a7e5f1b3c64"
                dirChooser = false
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform {
        // live tests open a real browser and hit the network; run with -Plive
        if (!project.hasProperty("live")) excludeTags("live")
    }
    jvmArgs(jcefJvmArgs)
    jvmArgs(quietNativeWarningArgs(25))
}
