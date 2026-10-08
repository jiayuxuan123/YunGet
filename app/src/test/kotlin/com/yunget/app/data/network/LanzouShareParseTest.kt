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

package com.yunget.app.data.network

import com.yunget.app.data.network.model.ShareFile
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝奏分享页的参数解析（纯 JVM，不发网络请求）。
 *
 * 这些用例对应 2.6.20 修的那个故障：文件夹分享里点文件报「蓝奏分享页缺少下载参数」。
 * 根因两条，各自都有用例钉住：
 *  1. 文件夹分享的**分享页是目录页**，参数在文件自己的页面上 —— 以前根本没去取；
 *  2. 参数名不固定，新版写成对象且值是**变量名** —— 以前硬编码 `wp_sign`/`sign`/`ajaxdata`。
 *
 * 页面的形状取自 alist `drivers/lanzou` 的实现与它自己的用例（项目一直致谢 alist 的协议研究）。
 */
class LanzouShareParseTest {

    private val api = LanzouApi { OkHttpClient() }

    private fun page(isFolder: Boolean, base: String = "https://wwa.lanzouj.com") = LanzouSharePage(
        shareUrl = "$base/b0shared",
        baseUrl = base,
        title = "分享",
        isFolder = isFolder,
        needsPwd = false,
        iframeUrl = null,
        fileId = null,
        folderParams = null,
        html = "",
        singleFile = null
    )

    private fun file(fid: String) = ShareFile(
        fid = fid, fname = "文件.bin", fsize = 0L, isdir = false, pdirFid = "", fidToken = ""
    )

    // ---------------------------------------------------------------- 关键回归

    @Test
    fun folderChildMustBeReadFromItsOwnPage() {
        // 文件夹分享里的文件：必须去打 {host}/{文件key}
        assertEquals(
            "https://wwa.lanzouj.com/b0childkey",
            api.downloadPageUrlFor(page(isFolder = true), file("b0childkey"))
        )
    }

    @Test
    fun singleFileShareUsesTheSharePageItself() {
        // 单文件分享的分享页本身就是那个文件的页面，不该多打一次请求
        assertNull(api.downloadPageUrlFor(page(isFolder = false), file("b0shared")))
    }

    @Test
    fun folderChildKeyWithPrefixIsNormalized() {
        // 有些路径下 fid 带 `f:` 前缀，拼 URL 前要去掉
        assertEquals(
            "https://wwa.lanzouj.com/iabc123",
            api.downloadPageUrlFor(page(isFolder = true), file("f:iabc123"))
        )
    }

    @Test
    fun blankKeyStillFallsBackToNull() {
        assertNull(api.downloadPageUrlFor(page(isFolder = true), file("  ")))
    }

    // ---------------------------------------------------------------- 参数对象

    @Test
    fun newStylePageResolvesVariablesInsideTheObject() {
        // 新版：值写的是变量名，真正的值在 var 里（alist 用例里的形状）
        val html = """<script>var sign = 'test-sign'; data: {'action':'downprocess','sign':sign,'ves':1}</script>"""
        val map = api.dataObject(api.stripNotes(html))!!
        assertEquals("downprocess", map["action"])
        assertEquals("test-sign", map["sign"])
        assertEquals("1", map["ves"])
    }

    @Test
    fun passwordStylePageResolvesIndirectKeys() {
        val html = """
            <script>
            var ajaxdata = 'key-from-js';
            var websignkey = 'ws-key';
            data : {'action':'downprocess','sign':'abc','signs':ajaxdata,'websignkey':websignkey,'websign':'','kd':'1','ves':1}
            </script>
        """.trimIndent()
        val map = api.dataObject(api.stripNotes(html))!!
        assertEquals("abc", map["sign"])
        assertEquals("key-from-js", map["signs"])
        assertEquals("ws-key", map["websignkey"])
        assertEquals("", map["websign"])
    }

    @Test
    fun commentDecoyIsStrippedBeforeParsing() {
        // 蓝奏会在注释里放假参数；先去注释就不会被诱饵骗到
        val html = """
            <!-- data: {'action':'downprocess','sign':'DECOY','fake':1} -->
            <script>var real = 'ok'; data: {'action':'downprocess','sign':real,'ves':1}</script>
        """.trimIndent()
        val map = api.dataObject(api.stripNotes(html))!!
        assertEquals("ok", map["sign"])
        assertNull(map["fake"])
    }

    @Test
    fun longestObjectWinsWhenSeveralArePresent() {
        // 页面里有多个 data {...} 时取参数最全的那个
        val html = """
            <script>data: {'a':1}</script>
            <script>data: {'action':'downprocess','sign':'s','signs':'k','websignkey':'k','kd':'1','ves':1}</script>
        """.trimIndent()
        val map = api.dataObject(api.stripNotes(html))!!
        assertEquals("downprocess", map["action"])
        assertEquals("k", map["websignkey"])
    }

    @Test
    fun noObjectMeansNoGuess() {
        assertNull(api.dataObject("<script>var x = 1;</script>"))
    }

    // ---------------------------------------------------------------- 接口地址

    @Test
    fun ajaxPathAcceptsBothLegacyAndNewForms() {
        assertEquals("/ajaxm.php?file=12345", api.findAjaxPath("url: '/ajaxm.php?file=12345'"))
        assertEquals("/ajaxfile.php?file=215138709", api.findAjaxPath("url : '/ajaxfile.php?file=215138709'"))
        // 新版页面不给 file 参数，路径就是裸的 —— 以前只认带 file 的写法会直接漏掉
        assertEquals("/ajaxm.php", api.findAjaxPath("data: {'action':'downprocess'} , url:'/ajaxm.php'"))
    }

    @Test
    fun ajaxPathResolvesAgainstPageOrigin() {
        val html = """<script>url:'/ajaxm.php'</script>"""
        assertEquals(
            "https://wwa.lanzouj.com/ajaxm.php",
            api.resolveAjaxUrl(html, "https://wwa.lanzouj.com", "999", withPwd = false)
        )
    }

    @Test
    fun declaredDomainWinsOverSameOrigin() {
        val html = """<script>var domain1 = 'https://apifile.woozooo.com/ajaxm.php?file=1';</script>"""
        assertEquals(
            "https://apifile.woozooo.com/ajaxm.php?file=1",
            api.resolveAjaxUrl(html, "https://wwa.lanzouj.com", "1", withPwd = false)
        )
    }

    @Test
    fun iframeIsAbsolutized() {
        assertEquals(
            "https://wwa.lanzouj.com/fn?abc",
            api.iframeSrc("""<iframe src="//wwa.lanzouj.com/fn?abc"></iframe>""", "https://wwa.lanzouj.com")
        )
        assertEquals(
            "https://wwa.lanzouj.com/fn",
            api.iframeSrc("""<iframe src="/fn"></iframe>""", "https://wwa.lanzouj.com")
        )
    }

    // ---------------------------------------------------------------- 目录参数

    @Test
    fun folderParamsFromObjectForm() {
        // 新版分享页把分页参数写成对象：zhi 前只认 var 写法，于是永远解析不出分页参数
        val html = """<script>{ 'lx':2, 'fid':2455975, 't':1666146943, 'k':'5e30653d4f54227c4856e473e49c3d5b' }</script>"""
        val params = api.folderParamsOf(html, "fallback")!!
        assertEquals("2", params.lx)
        assertEquals("2455975", params.fid)
        assertEquals("1666146943", params.t)
        assertEquals("5e30653d4f54227c4856e473e49c3d5b", params.k)
    }

    @Test
    fun folderParamsFromVarForm() {
        val html = """<script>var lx = '2'; var fid = '2455975'; var t = '1666146943'; var k = 'deadbeef';</script>"""
        val params = api.folderParamsOf(html, "fallback")!!
        assertEquals("2455975", params.fid)
        assertEquals("deadbeef", params.k)
    }

    @Test
    fun folderParamsFallsBackToShareKeyWhenFidMissing() {
        val html = """<script>var t = '1'; var k = '2';</script>"""
        assertEquals("b0shared", api.folderParamsOf(html, "b0shared")!!.fid)
    }

    @Test
    fun folderParamsWithoutTKIsRejectedInsteadOfGuessed() {
        // 缺 t/k 就没法分页，宁可返回 null 让上层报"分页异常"，也不要拿错参数去请求
        assertNull(api.folderParamsOf("<script>var lx = '2';</script>", "b0shared"))
    }

    // ---------------------------------------------------------------- 子目录

    @Test
    fun subFoldersAreMarkedAsFolders() {
        // 子目录只在分享页 HTML 上；以前它们被当成文件，点进去就走下载流程 → 报缺参数
        val html = """
            <div class="mbxfolder"><a href="/b0folder1" title="x">子目录一</a></div>
            <div class="mbxfolder"><a href="/b0folder2">子目录二</a></div>
        """.trimIndent()
        val folders = api.parseSubFolders(html)
        assertEquals(listOf("b0folder1", "b0folder2"), folders.map { it.fid })
        assertEquals(listOf("子目录一", "子目录二"), folders.map { it.fname })
        assertTrue(folders.all { it.isdir })
    }

    @Test
    fun subFolderListIsDeduplicatedAndIgnoresEmptyHref() {
        val html = """
            <div class="folderlink"><a href="/same">重复</a></div>
            <div class="folderlink"><a href="/same">重复</a></div>
            <div class="folderlink"><a href="/">空</a></div>
        """.trimIndent()
        val folders = api.parseSubFolders(html)
        assertEquals(listOf("same"), folders.map { it.fid })
    }

    @Test
    fun plainFilePageHasNoSubFolders() {
        assertTrue(api.parseSubFolders("<div class='fileinfo'>单个文件</div>").isEmpty())
    }

    // ---------------------------------------------------------------- 循环跳转判据

    /**
     * 2.6.21 修的第二个故障：命中 acw_sc__v2 人机校验页后，代码会算出 Cookie 再**重放同一个地址**
     * —— 那是设计好的第二次请求。而循环检测只按 URL 去重，于是那次重放必然被判成「循环跳转」。
     * 现在连着 Cookie 一起记：Cookie 变了 = 新尝试，没变 = 真循环。
     */
    @Test
    fun replayWithFreshCookieIsNotALoop() {
        val guard = LanzouApi.HopGuard()
        val url = "https://down.lanzouj.com/file/abc"
        assertTrue("第一次应当放行", guard.enter(url, "down_ip=1"))
        assertTrue("算出校验 Cookie 后重放同一地址，应当放行", guard.enter(url, "down_ip=1; acw_sc__v2=deadbeef"))
    }

    @Test
    fun sameUrlWithSameCookieIsStillALoop() {
        val guard = LanzouApi.HopGuard()
        val url = "https://down.lanzouj.com/file/abc"
        assertTrue(guard.enter(url, "down_ip=1"))
        assertFalse("同一地址 + 同一份 Cookie 又回来 = 真循环", guard.enter(url, "down_ip=1"))
    }

    @Test
    fun differentUrlsAreIndependent() {
        val guard = LanzouApi.HopGuard()
        assertTrue(guard.enter("https://a.lanzouj.com/file/x", ""))
        assertTrue(guard.enter("https://b.lanzouj.com/file/x", ""))
        assertTrue(guard.enter("https://a.lanzouj.com/file/y", ""))
    }
}
