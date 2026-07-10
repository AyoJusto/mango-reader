plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        jvmMain.dependencies {
            api(libs.graalvm.polyglot)
            implementation(libs.graalvm.js)
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
    // Gradle's Test task enables JVM assertions by default, which also switches on
    // Truffle's InteropLibrary$Asserts wrapper. That wrapper derives member readability
    // from a guest Proxy's `ownKeys` trap only; the official @paperback/types 0.8 compat
    // wrapper (vendored, not ours to edit) returns a `new Proxy(target, {get, has})` with
    // no `ownKeys` trap, so the assertion wrapper flags a false-positive "contract
    // violation" even though the real `get` trap resolves correctly (proven: the trap
    // runs and returns the right value before the assertion fires). Production runs
    // without `-ea`, so this only makes the test JVM match real runtime behavior.
    enableAssertions = false
}
