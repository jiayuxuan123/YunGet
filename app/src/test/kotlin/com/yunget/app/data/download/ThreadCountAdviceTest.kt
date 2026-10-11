package com.yunget.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线程数建议（P15）。
 *
 * 这一项**不是代码问题，是信息问题**：App 允许把线程数调到 256 而界面上毫无说明，
 * 用户只能靠猜。所以这里验的重点是**措辞的准确性** ——
 *
 *  - 调高有风险（实测 128 连接差 4.6 倍）；
 *  - 但对每连接限速的服务端，高连接数确实有效。
 *
 * 两件事必须同时说清。少说前者会坑一类用户，少说后者会坑另一类。
 * 措辞的回归靠断言钉住：改文案时若把其中一层意思说丢了，测试会失败。
 */
class ThreadCountAdviceTest {

    // ---------------------------------------------- 阈值分档

    @Test
    fun `默认值与低档位在保守区`() {
        assertEquals(
            "默认 16 应当是保守区",
            ThreadCountAdvice.Level.NORMAL,
            ThreadCountAdvice.levelFor(16),
        )
        assertEquals(ThreadCountAdvice.Level.NORMAL, ThreadCountAdvice.levelFor(1))
        assertEquals(ThreadCountAdvice.Level.NORMAL, ThreadCountAdvice.levelFor(31))
    }

    @Test
    fun `阈值边界`() {
        assertEquals(
            "刚好到阈值就该进提醒区",
            ThreadCountAdvice.Level.CAUTION,
            ThreadCountAdvice.levelFor(ThreadCountAdvice.CAUTION_THRESHOLD),
        )
        assertEquals(
            "差一档仍在保守区",
            ThreadCountAdvice.Level.NORMAL,
            ThreadCountAdvice.levelFor(ThreadCountAdvice.CAUTION_THRESHOLD - 1),
        )
        assertEquals(
            "刚好到严重阈值",
            ThreadCountAdvice.Level.SEVERE,
            ThreadCountAdvice.levelFor(ThreadCountAdvice.SEVERE_THRESHOLD),
        )
        assertEquals(
            "严重阈值前一档只是提醒区",
            ThreadCountAdvice.Level.CAUTION,
            ThreadCountAdvice.levelFor(ThreadCountAdvice.SEVERE_THRESHOLD - 1),
        )
    }

    @Test
    fun `引擎上限落在严重区`() {
        // 256 是引擎上限，也应当是提示最重的一档
        assertEquals(
            "引擎上限必须落在严重区（那里是最需要提示的地方）",
            ThreadCountAdvice.Level.SEVERE,
            ThreadCountAdvice.levelFor(256),
        )
    }

    @Test
    fun `严重阈值不高于提醒阈值`() {
        assertTrue(
            "严重阈值应当不低于提醒阈值，否则分档逻辑会自相矛盾",
            ThreadCountAdvice.SEVERE_THRESHOLD >= ThreadCountAdvice.CAUTION_THRESHOLD,
        )
    }

    // ---------------------------------------------- 措辞：两件事都要说到

    @Test
    fun `提醒区必须同时说清两种服务端的差别`() {
        val text = ThreadCountAdvice.describe(ThreadCountAdvice.CAUTION_THRESHOLD)

        assertTrue(
            "必须说明高并发可能被惩罚 —— 否则用户以为越高越快：$text",
            text.contains("惩罚") || text.contains("不一定更快") || text.contains("差数倍"),
        )
        assertTrue(
            "也必须说明每连接限速的服务端确实受益 —— 否则把另一类用户坑了：$text",
            text.contains("每连接") || text.contains("连接越多"),
        )
        assertTrue(
            "要给出可验证的观察点（怎么自己判断属于哪一类）：$text",
            text.contains("比") || text.contains("对比") || text.contains("测"),
        )
    }

    @Test
    fun `严重区的说明要给可操作的下一步`() {
        val text = ThreadCountAdvice.describe(ThreadCountAdvice.SEVERE_THRESHOLD)
        assertTrue(
            "只说有风险不够，要告诉用户怎么办：$text",
            text.contains("降") || text.contains("调低"),
        )
        assertTrue(
            "严重区要指出具体症状，用户才知道自己是不是撞上了：$text",
            text.contains("不升反降") || text.contains("重试") || text.contains("速度"),
        )
    }

    @Test
    fun `措辞不给死结论`() {
        // 这是总纲点名的风险：措辞不当会误导"每连接限速"那类服务端的用户。
        for (t in listOf(1, 16, 32, 64, 128, 256)) {
            val text = ThreadCountAdvice.describe(t) + ThreadCountAdvice.dialogNote(t)
            for (banned in listOf("不建议超过", "不要超过", "越高越快", "越高越慢", "必然")) {
                assertFalse(
                    "档位 $t 的文案里出现了结论式措辞「$banned」—— 两类服务端都存在，" +
                        "给死结论一定会误导其中一类：$text",
                    text.contains(banned),
                )
            }
        }
    }

    @Test
    fun `措辞不吓唬用户`() {
        // 这不是安全问题，用「警告/危险」这类词会让用户不敢调
        for (t in listOf(32, 128, 256)) {
            val text = ThreadCountAdvice.describe(t)
            for (banned in listOf("警告", "危险", "风险自负", "后果")) {
                assertFalse(
                    "档位 $t 的文案用了吓唬性的词「$banned」：$text",
                    text.contains(banned),
                )
            }
        }
    }

    // ---------------------------------------------- 措辞：基本要求

    @Test
    fun `每一档的说明都非空且带上了具体数值`() {
        for (t in listOf(1, 8, 16, 32, 64, 128, 256)) {
            val short = ThreadCountAdvice.describe(t)
            val long = ThreadCountAdvice.dialogNote(t)
            assertTrue("档位 $t 的短说明不能为空", short.isNotBlank())
            assertTrue("档位 $t 的长说明不能为空", long.isNotBlank())
            assertTrue(
                "短说明要带上当前数值，用户才知道自己看的是哪一档：$short",
                short.contains(t.toString()),
            )
        }
    }

    @Test
    fun `对话框说明会解释爬升不等于用满`() {
        // 这是 P10 带来的新事实：引擎会自适应收敛。不解释的话，
        // 用户会以为"设了 256 就会开 256 个连接"，从而不敢设高。
        val text = ThreadCountAdvice.dialogNote(256)
        assertTrue(
            "高档位的说明要讲清「爬升不等于用满」，否则用户会误以为设了就会拉满：$text",
            text.contains("收敛") || text.contains("不等于"),
        )
    }
}
