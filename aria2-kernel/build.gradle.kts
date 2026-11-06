import java.util.Properties

plugins { alias(libs.plugins.android.application) }

val signingProperties = Properties().apply {
    val source = rootProject.file("keystore.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}

android {
    namespace = "com.yunget.kernel.aria2"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yunget.kernel.aria2"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.37.0-1"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        if (signingProperties.isNotEmpty()) create("release") {
            storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            if (signingProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging { jniLibs.useLegacyPackaging = true }
}
