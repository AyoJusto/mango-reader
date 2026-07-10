plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.quickjs.kt)
            implementation(libs.ktor.client.core)
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
