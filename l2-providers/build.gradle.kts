/*
 * L2 网盘 Provider 插件打包模块（纯 JVM，不进 APK）。
 *
 * 编译期指向 :app 的 Kotlin 产物（CloudProvider 契约、各网盘 API/仓库、
 * ShareLinkParser、YunHostBridge 都是宿主类）；运行期这些类由插件的
 * 父类加载器（宿主）提供，插件 jar 里只装自己的 dex。
 *
 * 产物：build/l2/<providerId>.jar —— 每个 jar 内含同一个 classes.dex
 * （模块里全部 Provider 类），由各插件清单的 entryClass 决定实例化谁；
 * 多出的类不可见、不加载，打包保持一个 dex 是刻意的简化。
 */

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    // 版本在根 build.gradle.kts 声明（与 app 的 Kotlin 一致）
    kotlin("jvm")
}

val appCompileKotlin = project(":app").tasks.named("compileDebugKotlin")

// 宿主类目录：CloudProvider 契约、各网盘 API/仓库、ShareLinkParser、YunHostBridge。
// 运行期由插件父类加载器（宿主）提供同名的真类；这里只是编译期引用。
// 用固定路径而不是任务输出的 Provider 映射 —— 后者在 Kotlin 编译类路径上解析不可靠。
val appKotlinClassesDir = rootProject.layout.projectDirectory
    .dir("app/build/tmp/kotlin-classes/debug")

dependencies {
    compileOnly("dev.turbodl:turbo-plugin-runtime:0.2.2.0")
    compileOnly(files(appKotlinClassesDir))
    // 宿主类签名里出现的类型：Android 基础类（含 org.json）、Room、OkHttp
    compileOnly(files("D:/Android/sdk/platforms/android-36/android.jar"))
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(appCompileKotlin)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val providersToPackage = mapOf(
    "provider.quark" to "com.yunget.plugin.provider.QuarkCloudProvider",
    "provider.uc" to "com.yunget.plugin.provider.UcCloudProvider",
    "provider.lanzou" to "com.yunget.plugin.provider.LanzouCloudProvider",
    "provider.pan123" to "com.yunget.plugin.provider.Pan123CloudProvider",
)

val d8Tool = "D:/Android/sdk/build-tools/36.0.0/d8.bat"
val androidJar = "D:/Android/sdk/platforms/android-36/android.jar"

/** d8：插件 jar 字节码 → classes.dex（默认接口方法按 min-api 23 自动脱糖）。 */
val dexTask = tasks.register("dexL2Classes", Exec::class) {
    dependsOn(tasks.named("compileKotlin"), tasks.named("jar"))
    val pluginJar = tasks.named("jar", Jar::class).get().archiveFile
    val outDir = layout.buildDirectory.dir("l2/dex")
    outputs.dir(outDir)
    commandLine(d8Tool, "--release", "--min-api", "23", "--lib", androidJar,
        "--output", outDir.get().asFile.absolutePath, pluginJar.get().asFile.absolutePath)
}

/** 每个插件 id 一个 jar（内容 = classes.dex + 清单）。 */
tasks.register("packageL2Jars") {
    group = "build"
    description = "把 L2 网盘 Provider 打成市场可分的 jar（build/l2/<id>.jar）"
    dependsOn(dexTask)
    doLast {
        val dexFile = dexTask.get().outputs.files.singleFile.resolve("classes.dex")
        require(dexFile.isFile) { "d8 没有产出 classes.dex" }
        val outDir = layout.buildDirectory.dir("l2/jars").get().asFile.apply { mkdirs() }
        for ((id, entryClass) in providersToPackage) {
            val out = File(outDir, "$id.jar")
            ZipOutputStream(FileOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\r\nL2-Entry-Class: $entryClass\r\n\r\n".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                dexFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            println("L2 jar: ${out.absolutePath}（entryClass=$entryClass）")
        }
    }
}
