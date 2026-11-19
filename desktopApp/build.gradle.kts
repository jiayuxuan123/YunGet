import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose") version "1.9.3"
}

val sharedUi = rootProject.file("shared-ui/src/main/kotlin")
val turboDl = rootProject.file("../TurboDL/turbodl-native/src/main")
val nativeBin = providers.gradleProperty("yungetDesktopNativeBin").map(::file)
val nativeResources = layout.buildDirectory.dir("native-resources")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
sourceSets.main {
    java.srcDir(turboDl.resolve("java"))
}
kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
    sourceSets.main { kotlin.srcDirs(sharedUi, turboDl.resolve("kotlin")) }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3-desktop:1.9.0-beta03")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna:5.6.0")
    implementation("net.java.dev.jna:jna-platform:5.6.0")
    testImplementation("junit:junit:4.13.2")
}

// Compose packages these files beside its runtime under the app resource directory.
val stageNative by tasks.registering(Sync::class) {
    from(nativeBin) {
        include("turbodl_engine.dll", "libcurl-x64.dll", "libtorrent-rasterbar.dll",
            "avutil-*.dll", "avcodec-*.dll", "avformat-*.dll")
    }
    into(nativeResources.map { it.dir("windows-x64") })
    doFirst {
        require(nativeBin.orNull?.resolve("turbodl_engine.dll")?.isFile == true) {
            "Pass -PyungetDesktopNativeBin to the installed TurboDL Windows x64 bin directory."
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.yunget.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "YunGet"
            packageVersion = providers.gradleProperty("yungetVersion").get()
            appResourcesRootDir.set(nativeResources)
            modules(
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.prefs",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )
            windows {
                iconFile.set(rootProject.file("desktop/windows/runner/resources/app_icon.ico"))
                dirChooser = true
                perUserInstall = true
                shortcut = true
                menuGroup = "YunGet"
                upgradeUuid = "9a3f8c21-6c9a-4c3f-a8c7-0f61d7b11a2e"
            }
        }
    }
}

tasks.matching { it.name in setOf("prepareAppResources", "createDistributable", "packageMsi", "packageDistributionForCurrentOS") }
    .configureEach { dependsOn(stageNative) }

tasks.test {
    nativeBin.orNull?.let { systemProperty("yunget.native.bin", it.absolutePath) }
}
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    nativeBin.orNull?.let { systemProperty("yunget.native.bin", it.absolutePath) }
}
