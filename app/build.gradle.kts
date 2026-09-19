import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// ── Rust NDK cross-compilation ──────────────────────────────────────────────

val rustTargets = mapOf(
    "aarch64-linux-android" to "arm64-v8a",
    "x86_64-linux-android"  to "x86_64",
)
val ndkDir: String by lazy { android.ndkDirectory.absolutePath }
val rustCoreDir = file("${rootDir}/rust/core")
val cargoTargetDir = "${rootDir}/rust/target"

// Detect NDK host tag (platform-dependent)
fun ndkHostTag(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("linux")   -> "linux-x86_64"
        os.contains("mac")     -> "darwin-x86_64"
        os.contains("windows") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
}

fun ndkToolchainBin(): String = "$ndkDir/toolchains/llvm/prebuilt/${ndkHostTag()}/bin"

fun ndkLinker(target: String): String {
    val bin = ndkToolchainBin()
    // NDK r27+ requires API level suffix (e.g. aarch64-linux-android26-clang)
    val apiLevel = "26"  // matches minSdk
    val clang = if (System.getProperty("os.name").lowercase().contains("windows")) {
        "$bin/${target}${apiLevel}-clang.cmd"
    } else {
        "$bin/${target}${apiLevel}-clang"
    }
    return clang
}

tasks.register("buildRust") {
    group = "rust"
    description = "Build Rust guardian-core for all Android targets"
    val jniLibsDir = file("${projectDir}/src/main/jniLibs")

    doLast {
        val bin = ndkToolchainBin()
        for ((target, abi) in rustTargets) {
            val outDir = file("${jniLibsDir}/${abi}")
            outDir.mkdirs()

            // Write .cargo/config.toml for this target
            val cargoDir = file("${rootDir}/rust/.cargo")
            cargoDir.mkdirs()
            file("${cargoDir}/config.toml").writeText(
                """
                [target.$target]
                linker = "${ndkLinker(target).replace("\\", "\\\\")}"
                ar = "${bin.replace("\\", "\\\\")}/llvm-ar"

                [build]
                target-dir = "${cargoTargetDir.replace("\\", "\\\\")}"
                """.trimIndent()
            )

            // Execute cargo build
            exec {
                workingDir = rustCoreDir
                val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
                val cargoBin = if (System.getProperty("os.name").lowercase().contains("win"))
                    "${cargoHome}/bin/cargo.exe" else "${cargoHome}/bin/cargo"
                commandLine(cargoBin, "build", "--release", "--target", target)
                environment("CC", "${bin}/clang")
                environment("AR", "${bin}/llvm-ar")
            }

            // Copy .so into jniLibs
            val soFile = file("${cargoTargetDir}/${target}/release/libguardian_core_android.so")
            if (soFile.exists()) {
                copy {
                    from(soFile)
                    into(outDir)
                    rename { "libguardian_core_android.so" }
                }
            } else {
                logger.warn("Rust .so not found: ${soFile.absolutePath}")
            }
        }

        // Generate Kotlin bindings from UDL using uniffi-bindgen
        // build.rs generates Rust scaffolding from the same UDL — both sides stay in sync
        val udlFile = file("${rustCoreDir}/src/guardian.udl")
        val configFile = file("${rustCoreDir}/uniffi.toml")
        val kotlinOut = file("${projectDir}/src/main/java")
        if (udlFile.exists()) {
            val cargoHome2 = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
            val uniffiBin = if (System.getProperty("os.name").lowercase().contains("win"))
                "${cargoHome2}/bin/uniffi-bindgen.exe" else "${cargoHome2}/bin/uniffi-bindgen"
            val cmd = mutableListOf(
                uniffiBin, "generate", udlFile.absolutePath,
                "--language", "kotlin", "--no-format", "--out-dir", kotlinOut.absolutePath
            )
            if (configFile.exists()) {
                cmd.addAll(listOf("--config", configFile.absolutePath))
            }
            exec {
                workingDir = rustCoreDir
                commandLine(cmd)
            }
            // Post-process: remove unused ffi_*_rust_future_* declarations (no async in this crate)
            val ktFile = file("${kotlinOut}/uniffi/guardian_core_android/guardian_core_android.kt")
            if (ktFile.exists()) {
                var kt = ktFile.readText()
                kt = kt.replace(Regex("""\s*external fun ffi_guardian_core_android_rust_future_\w+\([^)]*\)[^}]*\n"""), "")
                // Fix library name: uniffi.toml cdylib_name may not be picked up,
                // so force it to match the actual .so filename (libguardian_core_android.so)
                kt = kt.replace("\"uniffi_guardian_core_android\"", "\"guardian_core_android\"")
                ktFile.writeText(kt)
                logger.lifecycle("Post-processed Kotlin binding (removed rust_future decls, fixed lib name)")
            }
            logger.lifecycle("UniFFI Kotlin bindings generated to ${kotlinOut.absolutePath}")
        } else {
            logger.warn("UDL file not found at ${udlFile.absolutePath} — Kotlin bindings not regenerated")
        }
    }
}

// Wire Rust build into preBuild
tasks.named("preBuild") {
    dependsOn("buildRust")
}

// ── Git version info (graceful fallback if git unavailable) ───────────────
fun gitCmd(vararg args: String): String = try {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
} catch (_: Exception) { "unknown" }

val gitHash   by lazy { gitCmd("rev-parse", "--short", "HEAD") }
val gitDate   by lazy { gitCmd("log", "-1", "--format=%ci") }
val gitBranch by lazy { gitCmd("rev-parse", "--abbrev-ref", "HEAD") }
val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

// Write version.properties into generated assets for AboutScreen to read at runtime
val versionAssetsDir = layout.buildDirectory.dir("generated/version-assets").get().asFile

tasks.register("writeVersionProperties") {
    val outFile = versionAssetsDir.resolve("version.properties")
    outputs.file(outFile)
    doLast {
        versionAssetsDir.mkdirs()
        outFile.writeText(buildString {
            appendLine("git_hash=$gitHash")
            appendLine("git_date=$gitDate")
            appendLine("git_branch=$gitBranch")
            appendLine("build_time=$buildTime")
        })
    }
}

android {
    namespace = "com.campusauth"
    compileSdk = 35
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.campusauth.guardian"
        minSdk = 26       // Android 8.0 — foreground service support
        targetSdk = 35    // Android 15
        versionCode = (project.findProperty("VERSION_CODE") as? String)?.toIntOrNull() ?: 1
        versionName = project.findProperty("VERSION_NAME") as? String ?: "dev"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Git info exposed to Kotlin via BuildConfig
        buildConfigField("String", "GIT_HASH",   "\"$gitHash\"")
        buildConfigField("String", "GIT_DATE",   "\"$gitDate\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("yusakisakura.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "114514"
                keyAlias = System.getenv("KEY_ALIAS") ?: "key0"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "114514"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksFile = rootProject.file("yusakisakura.jks")
            if (ksFile.exists() || System.getenv("KEYSTORE_BASE64") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Register generated version.properties as an additional assets source
    sourceSets {
        getByName("main") {
            assets.srcDir(versionAssetsDir)
        }
    }
}

// Wire writeVersionProperties before any task that reads assets
tasks.whenTaskAdded {
    if (name.startsWith("merge") && name.endsWith("Assets")
        || name.startsWith("lint") || name.startsWith("generate") && name.contains("Lint")) {
        dependsOn("writeVersionProperties")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // UniFFI 0.28 generates JNA bindings — required at runtime
    implementation("net.java.dev.jna:jna:5.16.0@aar")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
