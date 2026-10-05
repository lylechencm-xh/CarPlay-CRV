plugins {
    alias(libs.plugins.android.application)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

val crvTestKeystorePath = providers.environmentVariable("CRV_TEST_KEYSTORE_PATH").orNull
val crvTestStorePassword = providers.environmentVariable("CRV_TEST_STORE_PASSWORD").orNull
val crvTestKeyAlias = providers.environmentVariable("CRV_TEST_KEY_ALIAS").orNull
val crvTestKeyPassword = providers.environmentVariable("CRV_TEST_KEY_PASSWORD").orNull

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 17
        targetSdk = 28
        versionCode = 31
        versionName = "0.2.12"
    }

    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    // The upstream debug HUD demos depend on BYD/HUD code intentionally excluded from the
    // Android 4.2.2 CR-V runtime. Keep those debug-only sources out of this APK.
    sourceSets.getByName("debug").kotlin.exclude("com/shilapi/xcertplay/hud/**")

    signingConfigs {
        create("crvTest") {
            storeFile = crvTestKeystorePath?.let(::file) ?: file("missing-crv-test.keystore")
            storePassword = crvTestStorePassword.orEmpty()
            keyAlias = crvTestKeyAlias.orEmpty()
            keyPassword = crvTestKeyPassword.orEmpty()
        }
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            // Honda head-unit installer recognition depends on the legacy package identity.
            // Do not append an applicationIdSuffix here: the final APK must remain
            // exactly com.shihab.diplay.
            versionNameSuffix = "-crv"
            if (crvTestKeystorePath != null) {
                signingConfig = signingConfigs.getByName("crvTest")
            }
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint {
        abortOnError = true
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":crvlegacy"))
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
