package com.yunget.app.data.network

/**
 * 夸克 CDN 节点处理（修复 AlistGo/alist#830 下载 412 的一部分）：
 * 关闭节点优选——AList 等成熟实现均**原样使用夸克下发的直链**。
 * 改写 host / 预先 GET 探测会消耗直链额度，且改写 host 可能命中节点绑定的签名 → 412。
 * 如需提速，应改用「不消耗直链」的方式（如仅测原 host 延迟、按本地出口地理选择），且绝不改写服务端签发的 host。
 */
object QuarkCdn {

    /** 保持原样返回（与 AList quark_uc 行为一致），彻底排除节点改写/探测导致的 412 变量 */
    suspend fun fastest(original: String, cookie: String): String = original
}