/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.plugin

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import dev.turbodl.core.ApiVersion
import dev.turbodl.plugin.native.ATTR_DISPLAY_NAME
import dev.turbodl.plugin.native.ATTR_ENTRY_CLASS
import dev.turbodl.plugin.native.ATTR_PLUGIN_ID
import dev.turbodl.plugin.native.ATTR_API_VERSION
import dev.turbodl.plugin.native.KIND
import dev.turbodl.plugin.native.NativePluginLoadException
import dev.turbodl.plugin.native.NativePluginLoaderProvider
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginLoaderProvider
import dev.turbodl.plugin.runtime.PluginSource
import java.io.File

/**
 * L2（原生）插件的 Android 加载器（P16）。
 *
 * ## 为什么能热装却**不能**热生效
 *
 * 这里有个反直觉的地方：类**可以**在运行时加载（`DexClassLoader` 就是干这个的），
 * 但加载后**不能替换** —— 类加载器不卸载类，已加载的类定义无法改写。
 * 而插件还可能持有原生库句柄、解码器、文件描述符。所以：
 *
 * ```
 * 安装 jar  →  立刻可以「加载」（DexClassLoader 读得到）
 *          →  但要等**进程重启**才真正参与业务
 * ```
 *
 * 这就是为什么 [PluginLevel.NATIVE] 标了 `requiresRestart`，界面必须在每一次
 * 安装/更新/卸载/启停之后明确提示 —— 不能做成"看起来生效了"。
 *
 * ## 类加载器的生命周期
 *
 * 每个 jar 一个 `DexClassLoader`，**缓存起来**而不是每次加载都新建：
 *
 *  - 反复新建会让同一个类被不同的加载器各加载一份，`instanceof` 与类型转换全乱；
 *  - 插件内部若持有静态状态（连接池、缓存），换加载器等于丢状态。
 *
 * 缓存的键是 jar 的绝对路径 + 修改时间 —— 换内容（更新）时自然得到新的加载器，
 * 而旧的那个留在内存里直到进程重启（这正是"不能热替换"的技术原因）。
 */
internal class AndroidNativePluginLoader(private val context: Context) : NativePluginLoaderProvider {

    private companion object {
        const val TAG = "插件-L2"
        /** dex 的优化产物目录：AGP 对 jar 里没有的类会在这里落缓存。 */
        const val DEX_DIR = "plugin-l2"

        /** 可加载 dex 的暂存目录（codeCacheDir 下，Android 10+ 唯一放行的可加载位置）。 */
        const val STAGE_DIR = "l2-jars"
    }

    /** 已加载的 jar → 其类加载器。键含 mtime，更新后自然是新条目。 */
    private val loaders = java.util.concurrent.ConcurrentHashMap<String, DexClassLoader>()

    override fun load(source: PluginSource): List<Plugin> {
        val jarFile = File(source.uri)
        if (!jarFile.isFile) {
            throw NativePluginLoadException(
                "找不到插件文件：${jarFile.name}", NativePluginLoadException.Kind.JAR_UNREADABLE,
            )
        }

        val entryClass = source.attributes[ATTR_ENTRY_CLASS]
            ?: throw NativePluginLoadException(
                "插件未声明入口类（entryClass）", NativePluginLoadException.Kind.ENTRY_CLASS_MISSING,
            )

        // 宿主 API 版本必须满足插件要求：不满足就**明确拒绝**，
        // 而不是加载后在运行中报一个没有上下文的 NoSuchMethodError。
        source.attributes[ATTR_API_VERSION]?.let { required ->
            val want = parseApiVersion(required)
            if (want != null && ApiVersion.CURRENT < want) {
                throw NativePluginLoadException(
                    "「${source.attributes[ATTR_DISPLAY_NAME] ?: entryClass}」需要 API " +
                        "${want.major}.${want.minor}.${want.patch} 或更高，" +
                        "当前应用是 ${ApiVersion.CURRENT.major}.${ApiVersion.CURRENT.minor}.${ApiVersion.CURRENT.patch}",
                    NativePluginLoadException.Kind.API_VERSION_TOO_LOW,
                )
            }
        }

        val plugin = instantiate(source, entryClass, jarFile)
        return listOf(plugin)
    }

    private fun instantiate(source: PluginSource, entryClass: String, jarFile: File): Plugin {
        val loader = loaderFor(jarFile)
        return try {
            val cls = loader.loadClass(entryClass)
            // 显式检查类型再实例化：直接 newInstance 失败时的报错是
            // ClassCastException / NoSuchMethodError，看不出"为什么"——
            // 而这两种失败对插件作者是完全不同的问题。
            if (!Plugin::class.java.isAssignableFrom(cls)) {
                throw NativePluginLoadException(
                    "入口类 $entryClass 没有实现 dev.turbodl.plugin.runtime.Plugin",
                    NativePluginLoadException.Kind.ENTRY_CLASS_INVALID,
                )
            }
            val ctor = cls.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance() as Plugin
        } catch (e: NativePluginLoadException) {
            throw e
        } catch (e: ClassNotFoundException) {
            throw NativePluginLoadException(
                "插件里找不到入口类 $entryClass", NativePluginLoadException.Kind.ENTRY_CLASS_MISSING, e,
            )
        } catch (e: ReflectiveOperationException) {
            throw NativePluginLoadException(
                "入口类 $entryClass 实例化失败（需要公开的无参构造函数）",
                NativePluginLoadException.Kind.ENTRY_CLASS_INVALID, e,
            )
        } catch (e: Throwable) {
            throw NativePluginLoadException(
                "加载插件失败：${e.javaClass.simpleName}: ${e.message}",
                NativePluginLoadException.Kind.LOAD_FAILED, e,
            )
        }
    }

    /**
     * 取（或建立）这个 jar 的类加载器。
     *
     * ## 为什么要先把 jar 复制进 codeCacheDir
     *
     * Android 10+（targetSdk 29+）**拒绝从应用可写目录加载 dex**
     * （"Writable dex file is not allowed"——真机 2.7.6 上实测复现），
     * 而市场安装的 jar 就落在 filesDir。唯一的例外路径是
     * [Context.getCodeCacheDir]：把 jar **原样复制**一份到那里再交给
     * [DexClassLoader]。复制按「源 mtime」做缓存键——jar 更新后自然得到新副本；
     * 旧副本与旧加载器一起留在进程里直到重启（这正是"类不能热替换"的同一件事，无害）。
     */
    private fun loaderFor(jarFile: File): ClassLoader {
        val key = "${jarFile.absolutePath}@${jarFile.lastModified()}"
        return loaders.getOrPut(key) {
            val staged = stageForDexLoad(jarFile)
            val dexOut = File(context.codeCacheDir, DEX_DIR).apply { mkdirs() }
            DexClassLoader(
                staged.absolutePath,
                dexOut.absolutePath,
                /* librarySearchPath */ null,
                Plugin::class.java.classLoader,   // 父加载器：让它看见引擎的接口
            )
        }
    }

    /** dex 只能从 codeCacheDir 加载：把安装目录里的 jar 复制一份过去（幂等）。 */
    private fun stageForDexLoad(jarFile: File): File {
        val stageDir = File(context.codeCacheDir, STAGE_DIR).apply { mkdirs() }
        val staged = File(stageDir, "${jarFile.nameWithoutExtension}.${jarFile.lastModified()}.jar")
        if (!staged.isFile || staged.length() != jarFile.length()) {
            jarFile.copyTo(staged, overwrite = true)
            staged.setLastModified(jarFile.lastModified())
        }
        return staged
    }

    /** 已建立的加载器数量（诊断：理论上等于已加载且未重启的 L2 插件数）。 */
    fun loadedJarCount(): Int = loaders.size

    private fun parseApiVersion(raw: String): ApiVersion? = runCatching {
        val parts = raw.split('.')
        if (parts.size != 3) return null
        ApiVersion(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }.getOrNull()
}

/**
 * 把 [AndroidNativePluginLoader] 包成引擎认识的 [PluginLoaderProvider]。
 *
 * 内核只认 `PluginLoaderProvider`，不认识 L2。这个包装是**唯一**让两者相遇的地方 ——
 * 之后内核对 JS 与 L2 一视同仁（同一个注册表、同一套生命周期）。
 */
internal class NativeLoaderProvider(
    private val loader: AndroidNativePluginLoader,
    /** 提供者 id；同一个 host 里只能有一个 L2 加载器。 */
    override val loaderId: String = "native",
) : PluginLoaderProvider {

    override fun canLoad(source: PluginSource): Boolean = source.kind == KIND

    override fun load(source: PluginSource): List<Plugin> = loader.load(source)
}

/**
 * 把 [AndroidNativePluginLoader] 装进 [PluginHost] 的插件外壳。
 *
 * 内核只认 [Plugin]：loader 本身不是一个 Plugin，而是"注册进 `PluginLoaderProvider`
 * 扩展点"的一段逻辑 —— 与 JS 加载器同一模式（引擎 `JsPluginLoaderPlugin.onLoad` 里
 * `context.registerExtension(PluginLoaderProvider.KEY, …)`）。注册之后，内核收到
 * `kind = "native"` 的 [PluginSource] 时就会派发给 [NativeLoaderProvider]，
 * JS 与 L2 从此走同一个注册表、同一套生命周期。
 *
 * ## 为什么此前一直是死代码
 *
 * 这个外壳与 [AndroidNativePluginLoader] 都写完了，但 [PluginRuntime.createHost]
 * 从来没有 install 过它 —— `scanNativePlugins` 无调用方、`plugins/native` 目录不存在、
 * 市场也没有 jar 落盘路径。机制完整而没接线，等于没有（P16 的教训，记在这里防止复发）。
 */
internal class NativeLoaderPlugin(context: Context) : Plugin {

    override val id: String = "loader.native"

    override val name: String = "L2 原生插件加载器"

    private val provider = NativeLoaderProvider(AndroidNativePluginLoader(context))

    override fun onLoad(context: PluginContext) {
        context.registerExtension(PluginLoaderProvider.KEY, provider)
    }
}

/**
 * 扫描已安装的 L2 插件目录，返回可加载的来源。
 *
 * @param root L2 插件根目录（应用私有区下的 `plugins/native`）
 * @param nameOf 从 jar 名推导插件 id（`com.example.adapter.jar` → `com.example.adapter`）
 */
internal fun scanNativePlugins(root: File, nameOf: (File) -> String): List<File> =
    root.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") && !f.name.startsWith(".") }
        ?.sortedBy { it.name }
        ?: emptyList()
