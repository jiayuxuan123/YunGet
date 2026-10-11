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

package com.yunget.app.data.download

/**
 * 线程数这个旋钮的取值建议（P15）。
 *
 * ## 为什么需要它
 *
 * App 允许把线程数调到 256（引擎上限），但**界面上没有任何提示**说明调高会发生什么。
 * 实测数据（来自引擎侧的分片扫描与并发收敛测量）：
 *
 *  - 128 连接相对较低档位**差了 4.6 倍** —— 也就是说"拉满"在某些服务端上不是更快，
 *    而是明显更慢；
 *  - 但这条结论**不能反过来说**：另有一类服务端是**每连接独立限速**，
 *    对它们而言连接数越多总吞吐越高（引擎侧实测：每连接 96KB/s 时，
 *    总吞吐随连接数线性增长）。
 *
 * 所以这里的措辞必须**同时说清两件事**：调高有风险，但对某些服务端确实有效。
 * 写成"不建议超过 16"会把第二类用户坑了；写成"越高越快"会把第一类用户坑了。
 * 这不是代码问题，是信息问题 —— 本文件的职责就是把这条信息给准确。
 *
 * ## 为什么做成独立对象而不是写在 UI 里
 *
 * 阈值与措辞是这个应用的**产品判断**，会随实测更新；放在 Composable 里就只能靠肉眼回归。
 * 抽出来之后它可以被单测覆盖（`ThreadCountAdviceTest`），改措辞时能立刻看到影响范围。
 */
internal object ThreadCountAdvice {

    /**
     * 超过这个值就给提示。
     *
     * 选 32 的依据：默认值是 16（保守），而实测出现问题的档位是 128。
     * 32 是"明显高于默认、但仍在一个合理区间"的分界 —— 从这里开始提示，
     * 用户有足够的余地继续调高（这是他的选择），但已经被告知了风险。
     */
    const val CAUTION_THRESHOLD = 32

    /** 严重区段：实测在这个量级上出现过明显劣化。 */
    const val SEVERE_THRESHOLD = 128

    /**
     * 当前档位的风险级别。
     */
    enum class Level {
        /** 保守区（≤ 阈值）：照常使用。 */
        NORMAL,

        /** 提醒区：调高有风险，但对每连接限速的服务端有效。 */
        CAUTION,

        /** 严重区：实测出现过数倍劣化。 */
        SEVERE,
    }

    fun levelFor(threads: Int): Level = when {
        threads >= SEVERE_THRESHOLD -> Level.SEVERE
        threads >= CAUTION_THRESHOLD -> Level.CAUTION
        else -> Level.NORMAL
    }

    /**
     * 一句话说明当前档位（设置项的描述文字）。
     *
     * 措辞纪律：
     *  - **不给结论**：不说"不建议"，也不说"越高越快"，而是说清两种服务端的差别；
     *  - **给可验证的观察点**：用户怎么知道自己属于哪一类 —— 看下载速度，
     *    调低一档对比一下就知道；
     *  - **不吓唬**：不用"警告""危险"这类词，这不是安全问题。
     */
    fun describe(threads: Int): String = when (levelFor(threads)) {
        Level.NORMAL ->
            "并发上限：当前 $threads 线程。引擎慢启动从少逐步爬升到该值（需服务器支持 Range）。"
        Level.CAUTION ->
            "并发上限：当前 $threads 线程 —— 明显高于默认值。连接多了不一定更快：" +
                "有些服务端会惩罚高并发（实测某些档位可差数倍），而另一些是每连接独立限速、" +
                "连接越多总速度越高。拿不准就下载同一个文件、在相邻两档之间比一次速度。"
        Level.SEVERE ->
            "并发上限：当前 $threads 线程 —— 这已是引擎上限附近。实测在这个量级上，" +
                "部分服务端会出现数倍劣化（表现为速度不升反降、或频繁重试）。" +
                "若速度异常，先降回 16～32 再对比。"
    }

    /**
     * 对话框里的长说明（标题下方那段）。
     *
     * 与 [describe] 分开是因为两处读者的处境不同：设置项描述是"扫一眼"，
     * 对话框是"我正在决定"，后者可以也更需要把机制讲清。
     */
    fun dialogNote(threads: Int): String = when (levelFor(threads)) {
        Level.NORMAL ->
            "设置并发上限（最高 ${com.yunget.app.data.prefs.SettingsRepository.MAX_DOWNLOAD_THREADS}，" +
                "即引擎上限）；引擎会慢启动逐步爬升到该值（需服务器支持 Range）。"
        Level.CAUTION, Level.SEVERE ->
            "设置并发上限（最高 ${com.yunget.app.data.prefs.SettingsRepository.MAX_DOWNLOAD_THREADS}）。" +
                "引擎会慢启动爬升到该值，但**爬升不等于用满** —— " +
                "引擎在测到加连接不再带来吞吐提升时会自行收敛，所以调高档位并不等于会拉满那么多连接。\n\n" +
                "当前选择 $threads 线程：" + when (levelFor(threads)) {
                    Level.SEVERE -> "已在实测明显出问题的量级；除非你确定自己的服务端是每连接限速，否则建议降档。"
                    else -> "高于默认值；对每连接限速的服务端有效，对惩罚高并发的服务端可能反而更慢。"
                }
    }
}
