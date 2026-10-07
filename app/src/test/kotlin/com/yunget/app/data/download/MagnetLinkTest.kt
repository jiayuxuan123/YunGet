/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 * 本文件取自上游 YunX (https://github.com/CYQawa/YunX)
 * Copyright (C) 2026 CYQawa
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

package com.yunget.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磁力链接解析的纯逻辑测试。
 *
 * 为什么单独测：磁力不是 URL（没有 authority，`?` 后是一串 `&` 参数，`dn` 里还可能有空格），
 * 用 `android.net.Uri` 解析会被拆坏；而 JVM 单测里 Uri 是空壳、根本测不了 ——
 * 所以实现刻意只用 JDK 的 URLDecoder，本测试也就能真跑。
 */
class MagnetLinkTest {

    private val realMagnet =
        "magnet:?xt=urn:btih:C12FE1C06BBA254A4B1E5FE3AC0AA5B5D4BD5FFB" +
            "&dn=Ubuntu%2024.04%20Desktop&tr=udp%3A%2F%2Ftracker.example%3A80"

    @Test
    fun recognizesMagnetScheme() {
        assertTrue(MagnetLink.isMagnet(realMagnet))
        assertTrue("大写 scheme 也应识别", MagnetLink.isMagnet("MAGNET:?xt=urn:btih:ABCD"))
        assertTrue("前后空白应容忍", MagnetLink.isMagnet("  magnet:?xt=urn:btih:ABCD  "))
        assertFalse("光杆 magnet: 不算", MagnetLink.isMagnet("magnet:"))
        assertFalse("普通 http 链接不是磁力", MagnetLink.isMagnet("https://example.com/a.bin"))
        assertFalse(MagnetLink.isMagnet(""))
    }

    @Test
    fun extractsInfoHashUppercased() {
        assertEquals("C12FE1C06BBA254A4B1E5FE3AC0AA5B5D4BD5FFB", MagnetLink.infoHash(realMagnet))
        // 32 位 base32 形式
        assertEquals(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567",
            MagnetLink.infoHash("magnet:?xt=urn:btih:abcdefghijklmnopqrstuvwxyz234567"),
        )
        assertEquals("无 info hash 时返回空串", "", MagnetLink.infoHash("magnet:?dn=abc"))
    }

    @Test
    fun displayNamePrefersDnAndDecodesPercentEncoding() {
        // %20 是空格；dn 是显示名，必须解出来给人看
        assertEquals("Ubuntu 24.04 Desktop", MagnetLink.displayName(realMagnet))
    }

    @Test
    fun displayNameFallsBackToHashPrefix() {
        val name = MagnetLink.displayName("magnet:?xt=urn:btih:C12FE1C06BBA254A4B1E5FE3AC0AA5B5D4BD5FFB")
        assertEquals("磁力_C12FE1C0", name)
        assertEquals("连 hash 都没有时的兜底", "磁力任务", MagnetLink.displayName("magnet:?dn="))
    }

    @Test
    fun plusSignIsKeptLiteralInDisplayName() {
        // 文件名里的 `+` 很常见（如 C++）；按字面保留，不能被当成空格吃掉
        assertEquals("C++ Primer.pdf", MagnetLink.displayName("magnet:?dn=C%2B%2B%20Primer.pdf"))
    }

    @Test
    fun displayNameCollapsesNewlinesAndTruncates() {
        val long = "x".repeat(200)
        val name = MagnetLink.displayName("magnet:?dn=$long")
        assertEquals("过长的 dn 必须截断（否则通知栏会被撑爆）", 80, name.length)

        val withNewline = MagnetLink.displayName("magnet:?dn=a%0Ab%09c")
        assertEquals("换行/制表符压成空格", "a b c", withNewline)
    }
}
