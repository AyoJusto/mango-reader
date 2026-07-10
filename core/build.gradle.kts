plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("MangoDatabase") {
            packageName.set("dev.mango.core.db")
        }
    }
}

kotlin {
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
        // live tests hit real source sites; run explicitly with -Plive
        if (!project.hasProperty("live")) excludeTags("live")
    }
    testLogging { showStandardStreams = true }
}
