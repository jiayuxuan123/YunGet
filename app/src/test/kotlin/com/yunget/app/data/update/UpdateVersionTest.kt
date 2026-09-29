package com.yunget.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较与"检查更新"选版逻辑的回归测试。
 *
 * ## 为什么要有这些测试
 *
 * 用户实测踩到的真实故障：装了 **2.6.9** 却提示"已是最新"。
 * 根因是用了 GitHub 的 `/releases/latest` 接口 —— 它**按设计跳过预发布版**，
 * 而本项目的测试版正是预发布。于是线上"最新"返回的是旧的 2.6.7。
 *
 * 修复改成"拉列表 + 自己按版本号取最大"。这套逻辑里，
 * **版本比较**是最容易出错的一环（后缀、位数不齐、前缀 v），故重点覆盖。
 *
 * 注意：本模块用 JUnit 4，`assertTrue(message, condition)` 的**消息在前**，
 * 与 kotlin.test 相反。
 */
class UpdateVersionTest {

    private fun cmp(a: String, b: String) = UpdateChecker.compareVersions(a, b)

    // ---------------------------------------------------------------- 基础

    @Test
    fun majorAndMinorOrdering() {
        assertTrue("2.6.9 应大于 2.6.7", cmp("2.6.9", "2.6.7") > 0)
        assertTrue("2.6.7 应小于 2.6.9", cmp("2.6.7", "2.6.9") < 0)
        assertTrue("2.7.0 应大于 2.6.9", cmp("2.7.0", "2.6.9") > 0)
        assertTrue("3.0.0 应大于 2.99.99", cmp("3.0.0", "2.99.99") > 0)
        assertEquals("相同版本应相等", 0, cmp("2.6.9", "2.6.9"))
    }

    @Test
    fun leadingVIsOptional() {
        assertEquals("tag 带 v 前缀不应影响比较", 0, cmp("v2.6.9", "2.6.9"))
        assertTrue("v2.6.9 应大于 v2.6.7", cmp("v2.6.9", "v2.6.7") > 0)
        assertEquals("大小写 V 都应支持", 0, cmp("V2.6.9", "v2.6.9"))
    }

    @Test
    fun unevenSegmentCountsAreZeroPadded() {
        assertEquals("2.6 与 2.6.0 应相等", 0, cmp("2.6", "2.6.0"))
        assertTrue("2.6.1 应大于 2.6", cmp("2.6.1", "2.6") > 0)
        assertEquals("2 与 2.0.0 应相等", 0, cmp("2", "2.0.0"))
    }

    // ---------------------------------------------------------------- 预发布后缀
    // 本项目的高频场景：测试版叫 2.6.9-dev12 / 2.6.9-rc1

    @Test
    fun prereleaseSuffixRanksBelowPlainRelease() {
        assertTrue(
            "正式版应大于同号测试版（否则'从 dev 升正式'检测不到）",
            cmp("2.6.9", "2.6.9-dev12") > 0,
        )
        assertTrue("rc 版应小于同号正式版", cmp("2.6.9-rc1", "2.6.9") < 0)
        assertTrue("dev 与 rc 不同后缀时按字典序：dev < rc", cmp("2.6.9-dev12", "2.6.9-rc1") < 0)
    }

    @Test
    fun prereleaseCountersCompareNumerically() {
        // 字典序会把 dev9 排在 dev12 之后（"9" > "1"），必须按数值比
        assertTrue("dev12 应大于 dev9（按数值，而非字典序）", cmp("2.6.9-dev12", "2.6.9-dev9") > 0)
        assertTrue("rc10 应大于 rc2", cmp("2.6.9-rc10", "2.6.9-rc2") > 0)
        assertEquals("同号同后缀应相等", 0, cmp("2.6.9-dev12", "2.6.9-dev12"))
    }

    @Test
    fun baseNumberDominatesSuffix() {
        assertTrue("主版本更高的测试版，仍高于低版本的正式版", cmp("2.7.0-dev1", "2.6.9") > 0)
    }

    // ---------------------------------------------------------------- 选版逻辑
    // 直接验证"从一批 release 里挑出该提示用户的那一个"

    @Test
    fun picksHighestVersionRegardlessOfOrder() {
        // 模拟 GitHub 列表接口的真实返回（按发布时间倒序）
        val tags = listOf("v2.6.9", "v2.6.7", "v2.6.6", "v2.6.5")
        val best = tags.maxWithOrNull { a, b -> cmp(a, b) }
        assertEquals("应挑出版本号最大的", "v2.6.9", best)
    }

    @Test
    fun prereleaseIsPickedWhenHighest() {
        // 这正是修复前看不到 2.6.9 的场景
        val tags = listOf("v2.6.9", "v2.6.7", "v2.6.6")
        val best = tags.maxWithOrNull { a, b -> cmp(a, b) }
        assertEquals("预发布版是最高版本时应被选中（修复点）", "v2.6.9", best)
    }

    @Test
    fun outOfOrderPublicationDoesNotConfuseSelection() {
        // 补发旧版本的情形：时间顺序与版本顺序不一致，必须按版本号选
        val tags = listOf("v2.6.5", "v2.6.9", "v2.6.7")
        val best = tags.maxWithOrNull { a, b -> cmp(a, b) }
        assertEquals("应按版本号而非发布时间取最大", "v2.6.9", best)
    }

    @Test
    fun alreadyNewestYieldsNoPrompt() {
        val current = "2.6.9"
        val tags = listOf("v2.6.9", "v2.6.7")
        val best = tags.maxWithOrNull { a, b -> cmp(a, b) }!!
        assertTrue("已是最新时不应提示更新", cmp(best, current) <= 0)
    }

    @Test
    fun newerReleaseYieldsPrompt() {
        val current = "2.6.7"
        val tags = listOf("v2.6.9", "v2.6.7")
        val best = tags.maxWithOrNull { a, b -> cmp(a, b) }!!
        assertTrue("存在更高版本时应提示更新", cmp(best, current) > 0)
    }

    @Test
    fun upgradingFromDevBuildToPlainReleaseIsDetected() {
        // 从 2.6.9-dev12 升到 2.6.9 正式版
        val current = "2.6.9-dev12"
        assertTrue(
            "dev 版应能检测到同号正式版（修复前此处会判为相等而漏更新）",
            cmp("2.6.9", current) > 0,
        )
    }
}
