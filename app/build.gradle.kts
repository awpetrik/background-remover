import java.util.Properties

import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Build the Rust core (.so, arm64-v8a) before Android packaging needs it.
val buildRustSo = tasks.register<Exec>("buildRustSo") {
    group = "build"
    description = "Build eraser-core for arm64-v8a via rust/build-android.sh"
    workingDir = rootProject.projectDir
    commandLine("bash", "rust/build-android.sh")
    inputs.dir(rootProject.file("rust/eraser-core/src"))
    inputs.file(rootProject.file("rust/eraser-core/Cargo.toml"))
    outputs.file(layout.buildDirectory.file("rustJniLibs/arm64-v8a/liberaser_core.so"))
}

tasks.named("preBuild") { dependsOn(buildRustSo) }

// Host build of the same crate, used by JVM unit tests (see below).
val buildRustHost = tasks.register<Exec>("buildRustHost") {
    group = "build"
    description = "Build eraser-core for the host (unit tests)"
    workingDir = rootProject.file("rust/eraser-core")
    commandLine("cargo", "build", "--lib")
    environment("PATH", "${System.getenv("PATH")}:${System.getProperty("user.home")}/.cargo/bin")
    inputs.dir(rootProject.file("rust/eraser-core/src"))
    outputs.file(rootProject.file("rust/eraser-core/target/debug/liberaser_core.so"))
}

android {
    namespace = "id.eraserremake.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.eraserremake.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signing dibaca dari app/key.properties bila ada (lihat
            // scripts/build_release.sh yang menolak build "release" tanpa
            // keystore sungguhan). Tanpa file itu APK keluar tanpa tanda
            // tangan dan tidak bisa diinstall sebagai rilis.
            val keyPropsFile = file("key.properties")
            if (keyPropsFile.exists()) {
                val keyProps = Properties().apply {
                    keyPropsFile.inputStream().use { load(it) }
                }
                signingConfig = signingConfigs.create("releaseKey") {
                    storeFile = file(keyProps.getProperty("storeFile"))
                    storePassword = keyProps.getProperty("storePassword")
                    keyAlias = keyProps.getProperty("keyAlias")
                    keyPassword = keyProps.getProperty("keyPassword")
                }
            }
        }
    }
    lint {
        // Baseline hanya memuat 3 temuan NewApi pada kode hasil generate
        // UniFFI (java.lang.ref.Cleaner dipilih lewat Class.forName dengan
        // fallback JNA, aman di API 29-32).
        baseline = file("lint-baseline.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            // Static path (AGP 9 rejects Provider instances here). The directory
            // is produced by the buildRustSo task, wired into preBuild above.
            jniLibs.srcDir("build/rustJniLibs")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    // UniFFI Kotlin bindings load the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.14.0")
    // Unduhan model di latar (Fase 2).
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// JVM unit tests exercise the real UniFFI bindings against the host build of
// eraser-core, so the Kotlin <-> Rust bridge is verified without a device.
tasks.withType<Test>().configureEach {
    // Test workers talk to the Gradle daemon over loopback TCP. Without this
    // flag the JVM prefers IPv6 and the connection can be intercepted in
    // sandboxed environments; the worker then dies at startup with no test
    // output. Same fix as PiggyBank's build.
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    systemProperty(
        "jna.library.path",
        rootProject.file("rust/eraser-core/target/debug").absolutePath
    )
    dependsOn(buildRustHost)
}
