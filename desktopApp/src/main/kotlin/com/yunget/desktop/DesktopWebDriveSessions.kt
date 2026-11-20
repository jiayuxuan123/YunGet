package com.yunget.desktop

import com.yunget.app.data.network.OfficialWebDrive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** 每个官网使用独立浏览器资料目录。登录数据由浏览器维护。 */
internal class DesktopWebDriveSessions(
    dataDirectory: File,
    private val findBrowser: () -> DesktopBrowserExecutable? = DesktopBrowserLocator::findBrowser,
) {
    private val root = dataDirectory.toPath().toAbsolutePath().normalize().resolve("web-drive-profiles")
    private val browsers = mutableMapOf<OfficialWebDrive, DesktopBrowserExecutable>()
    private val processes = mutableMapOf<OfficialWebDrive, Process>()
    private val _savedProfiles = MutableStateFlow(
        OfficialWebDrive.entries.filterTo(mutableSetOf()) {
            Files.isDirectory(profile(it), LinkOption.NOFOLLOW_LINKS)
        }.toSet()
    )
    val savedProfiles = _savedProfiles.asStateFlow()

    private fun profile(drive: OfficialWebDrive): Path = root.resolve(drive.id)

    suspend fun open(drive: OfficialWebDrive, url: URI = URI(drive.entryUrl)) =
        withContext(Dispatchers.IO) {
            require(drive.accepts(url)) { "请使用${drive.displayName}的 HTTPS 链接" }
            synchronized(this@DesktopWebDriveSessions) {
                val browser = browsers.getOrPut(drive) {
                    findBrowser() ?: error("未检测到 Microsoft Edge 或 Google Chrome")
                }
                val profile = profile(drive)
                Files.createDirectories(root)
                check(!Files.isSymbolicLink(root) && !Files.isSymbolicLink(profile)) { "网页会话目录无法使用" }
                Files.createDirectories(profile)
                check(profile.toRealPath().parent == root.toRealPath()) { "网页会话目录无法使用" }
                val process = ProcessBuilder(webDriveBrowserCommand(browser, profile, url))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (processes[drive]?.isAlive != true) processes[drive] = process
                _savedProfiles.value += drive
            }
        }

    suspend fun clear(drive: OfficialWebDrive) = withContext(Dispatchers.IO) {
        synchronized(this@DesktopWebDriveSessions) {
            val closeBrowserMessage = "请关闭${drive.displayName}的浏览器窗口后重试"
            check(processes[drive]?.isAlive != true) { closeBrowserMessage }
            val profile = profile(drive)
            if (!Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) {
                _savedProfiles.value -= drive
                return@synchronized
            }
            val resolvedRoot = root.toRealPath()
            val resolvedProfile = profile.toRealPath()
            check(!Files.isSymbolicLink(root) && !Files.isSymbolicLink(profile) &&
                resolvedProfile.parent == resolvedRoot && resolvedProfile.fileName.toString() == drive.id
            ) { "网页会话目录无法使用" }
            val retired = resolvedRoot.resolve(".cleared-${drive.id}-${UUID.randomUUID()}")
            try {
                Files.move(resolvedProfile, retired)
            } catch (err: IOException) {
                throw IOException(closeBrowserMessage, err)
            }
            processes.remove(drive)
            browsers.remove(drive)
            _savedProfiles.value -= drive
            try {
                Files.walk(retired).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            } catch (err: IOException) {
                throw IOException("已退出网页会话，部分旧浏览器文件未能清理", err)
            }
        }
    }
}

internal fun webDriveBrowserCommand(browser: DesktopBrowserExecutable, profile: Path, url: URI): List<String> =
    listOf(
        browser.file.absolutePath,
        "--user-data-dir=$profile",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-background-mode",
        "--new-window",
        url.toASCIIString(),
    )
