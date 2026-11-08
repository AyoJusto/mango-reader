plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("MangoDatabase") {
            packageName.set("dev.mango.core.db")
            // default sqlite dialect (3.18) predates upsert; the bundled xerial driver is 3.4x
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:${libs.versions.sqldelight.get()}")
        }
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        jvmMain.dependencies {
            api(libs.graalvm.polyglot)
            implementation(libs.graalvm.js)
            implementation(libs.sqldelight.sqlite.driver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.cio)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform {
        if (!project.hasProperty("live")) excludeTags("live")
    }
    testLogging { showStandardStreams = true }
    // JDK 25 warns on sqlite-jdbc's native load and Truffle's sun.misc.Unsafe use; both are expected here
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}
