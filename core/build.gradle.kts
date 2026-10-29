// 共享内核：纯 JVM，不依赖 Android。
//
// 这里放的是"协议与数据"，也就是最容易变、也最该只写一次的部分：分享链接解析、
// 各网盘的 Api/Repository、模型。Android 应用与桌面端的引擎宿主都依赖它，
// 于是蓝奏这类协议的改动只发生一次，不会在两个客户端里各改一遍。
plugins {
    `java-library`
    kotlin("jvm")
}

// 不用 java.toolchain：本机只有 JDK 21 与 JRE 17，工具链解析会失败。
// 这里用当前 JDK 编译、产出 **17 字节码** —— Android 的 D8 接受 class file 61，
// 桌面端在 21 上也能直接跑。
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
    // OkHttp 的 OkHttpClient 出现在公开构造参数里（LanzouApi 允许注入客户端），
    // 所以必须是 api 而不是 implementation。
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // org.json 在 Android 上由系统提供，桌面端由引擎宿主提供；这里只编译期需要，
    // 绝不能被拖进 Android 的 APK（会和框架里的同名类冲突）。
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
