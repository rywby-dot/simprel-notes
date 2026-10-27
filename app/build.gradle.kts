plugins { id("com.android.application") }
android {
    namespace = "com.simprel.notes"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.simprel.notes"
        minSdk = 26
        targetSdk = 37
        versionCode = 23
        versionName = "2.1"
        testInstrumentationRunner = "com.simprel.notes.SmokeTests"
    }
    signingConfigs {
        create("localRelease") {
            val customKeystore = providers.environmentVariable("SIMPREL_KEYSTORE").orNull
            storeFile = rootProject.file(customKeystore ?: ".signing/simprel-notes.keystore")
            storePassword = providers.environmentVariable("SIMPREL_STORE_PASSWORD").orNull
                ?: if (customKeystore == null) "android" else null
            keyAlias = providers.environmentVariable("SIMPREL_KEY_ALIAS").orNull
                ?: if (customKeystore == null) "androiddebugkey" else "simprel-release"
            keyPassword = providers.environmentVariable("SIMPREL_KEY_PASSWORD").orNull ?: storePassword
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("androidx.annotation:annotation:1.9.1")
}
tasks.register<Copy>("exportReleaseApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "Simprel-Notes.apk" }
}
