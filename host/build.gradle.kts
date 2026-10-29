// 引擎宿主：桌面端（Flutter）与 TurboDL/共享内核之间的桥。
//
// 为什么需要它：Flutter 用不了 JVM 库，而下载引擎与网盘解析都在 Kotlin 上。
// 这个进程只做一件事 —— 从 stdin 读 JSON 行、把结果/事件写到 stdout，
// 由 Flutter 侧作为子进程拉起。协议与 Android 侧的平台通道共用同一套消息。
plugins {
    `java-library`
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("dev.turbodl:turbodl-core:0.2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.yunget.host.MainKt")
}

// 单文件分发包：Flutter 侧只需要一个 jar。
tasks.jar {
    manifest { attributes["Main-Class"] = "com.yunget.host.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
    archiveFileName.set("yunget-host.jar")
}
