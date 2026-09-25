/**
 * Where the Krystallos kernel source lives.
 *
 * A git submodule, so that a recursive clone of this repository is enough to
 * build it — the same arrangement Krystallos itself uses for libsmb2.
 *
 * `-Pkrystallos.dir=<path>` overrides it, for working on the kernel in its own
 * checkout. Note that pointing at a submodule's *working tree*, as the default
 * does, means uncommitted kernel edits are picked up by the next build; the
 * pinned commit only decides what a fresh clone gets.
 */
val krystallosDir: String = (findProperty("krystallos.dir") as String?)
    ?: rootProject.projectDir.resolve("vendor/krystallos").absolutePath

plugins {
    alias(libs.plugins.android.application)
    // No Kotlin Android plugin: AGP 9 compiles Kotlin itself and the standalone
    // plugin is incompatible with its new DSL.
    alias(libs.plugins.kotlin.compose)
    // Navigation 3 routes and the stored server list are @Serializable.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hyalos.player"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hyalos.player"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Only 64-bit and 32-bit ARM plus x86_64 for the emulator. `x86` is
        // omitted: no device that matters runs 32-bit x86, and every ABI costs
        // a full Rust cross-compile.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For BuildConfig.VERSION_NAME, which the About page shows. Nothing here
        // reads a build flag at runtime otherwise; Compose needs no flag of its
        // own beyond the line above.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.inspector.frame)

    implementation(libs.tink.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // `@aar`, not the default jar. The jar has no native libraries, and the
    // generated bindings load the Rust library through JNA's direct mapping —
    // without libjnidispatch.so inside the APK this fails at run time with an
    // UnsatisfiedLinkError that names neither JNA nor the missing variant.
    implementation(variantOf(libs.jna) { artifactType("aar") })

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // The BOM again, because androidTest does not inherit `implementation`'s
    // platform constraint — without it the artifact below has no version.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Heavy (it pulls in transformer and mockito), but it carries Media3's own
    // DataSourceContractTest, which checks the DataSource contract far more
    // thoroughly than hand-written tests would. androidTest only.
    androidTestImplementation(libs.media3.test.utils)
    // The contract test mocks a TransferListener; on a device that needs
    // Mockito's Android mock maker.
    androidTestImplementation(libs.mockito.android)
}

// ---------------------------------------------------------------------------
// Rust kernel
// ---------------------------------------------------------------------------

/**
 * Where the kernel's cdylib is built to, and where its generated bindings go.
 *
 * Both live under the build directory so `./gradlew clean` removes them and
 * nothing generated is ever committed.
 */
val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs")
val uniffiOutDir = layout.buildDirectory.dir("generated/uniffi")

/**
 * Cross-compiles the Rust kernel for every ABI in `abiFilters`.
 *
 * Invoked directly rather than through one of the Gradle plugins for
 * cargo-ndk: both of the available ones are low-activity forks, and one of them
 * is known to break on Gradle 9. This is about twenty lines and has no
 * lifecycle to keep up with.
 *
 * `-P 29` is not optional — cargo-ndk defaults to API 21, below this app's
 * minSdk, and the mismatch produces a library linked against an older libc.
 *
 * Declared as a custom type rather than a plain `Exec` so that `outputDir` can
 * be referenced as a task output, which is what lets AGP wire the dependency
 * from `merge*JniLibFolders` automatically.
 */
abstract class BuildRust : Exec() {
    /** Where the cross-compiled libraries land. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val buildRust = tasks.register<BuildRust>("buildRust") {
    group = "rust"
    description = "Cross-compiles the Krystallos kernel for the configured ABIs"

    workingDir = file(krystallosDir)
    outputDir.set(rustJniLibsDir)

    // `--release` always: a debug Rust library is large and slow, and the
    // 16 KB page alignment this project depends on is a release-profile
    // linker behaviour.
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-P", "29",
        "-o", rustJniLibsDir.get().asFile.absolutePath,
        "build", "--release",
        "-p", "krystallos-ffi",
    )

    // What the Rust build actually reads, declared so Gradle can skip the task
    // when none of it changed.
    //
    // `crates/` and `Cargo.toml` alone were not enough. Two things the build
    // depends on live outside them and would have gone unnoticed:
    //
    // - `Cargo.lock`, which decides dependency versions.
    // - `vendor/libsmb2`, a *nested* submodule. Bumping its pointer changes the
    //   C sources the kernel compiles, while leaving `crates/` byte-identical —
    //   so the task would have been skipped and a stale library shipped.
    //
    // `RELATIVE` path sensitivity means the checkout's location does not affect
    // the fingerprint. That is not a guess: the release build was measured to be
    // byte-identical whether the kernel was built from a sibling checkout or
    // from `vendor/`, because `strip = "debuginfo"` removes the path-dependent
    // debug info. Moving the checkout should not force a rebuild.
    inputs.files(
        fileTree(krystallosDir) {
            include("Cargo.toml", "Cargo.lock")
            include("crates/**")
            include("vendor/libsmb2/lib/**", "vendor/libsmb2/include/**")
            include("vendor/libsmb2/CMakeLists.txt")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE)

    doFirst {
        if (!file(krystallosDir).isDirectory) {
            throw GradleException(
                "The Krystallos kernel was not found at $krystallosDir.\n" +
                    "Clone it next to this repository, or point at it with " +
                    "-Pkrystallos.dir=<path>.",
            )
        }
    }
}

/**
 * Generates the Kotlin bindings from the compiled kernel.
 *
 * Runs against the arm64-v8a artifact, but the output is platform-independent —
 * verified by generating from the host build and from the Android one and
 * comparing hashes. So the ABI chosen here does not affect the result.
 *
 * Declared as a custom task type rather than a plain `Exec` because
 * `addGeneratedSourceDirectory` needs a typed `DirectoryProperty` to reference
 * as the task's output, which is what lets AGP wire the dependency up on its
 * own.
 */
abstract class GenerateUniffiBindings : Exec() {
    /** Where the generated Kotlin lands. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val generateUniffiBindings = tasks.register<GenerateUniffiBindings>("generateUniffiBindings") {
    group = "rust"
    description = "Generates the Kotlin bindings for the Krystallos kernel"

    dependsOn(buildRust)
    workingDir = file(krystallosDir)

    val library = rustJniLibsDir.map { it.file("arm64-v8a/libkrystallos_ffi.so") }
    val outDir = uniffiOutDir.get().asFile
    outputDir.set(uniffiOutDir)

    commandLine(
        "cargo", "run", "--quiet",
        "-p", "krystallos-ffi", "--bin", "uniffi-bindgen",
        "--",
        "generate",
        "--library", library.get().asFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
    )

    inputs.file(library)
}

// The generated bindings are Kotlin source, so they have to reach the Kotlin
// compile — and the compile has to wait for the task that produces them.
//
// Both source directories are contributed through the Variant API rather than
// the older `android.sourceSets` DSL. AGP 9 rejects a `Provider` in the old API
// outright: it cannot tell whether such a directory holds generated
// (read-only) or static (read-write) files, and refuses to guess. The Variant
// API makes that distinction explicit — and, for the generated case, carries
// the task dependency automatically, which the old API did not.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildRust,
            BuildRust::outputDir,
        )
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateUniffiBindings,
            GenerateUniffiBindings::outputDir,
        )
    }
}
