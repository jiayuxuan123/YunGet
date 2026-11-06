package com.yunget.app.data.network

/**
 * 百度网盘登录与 API 相关常量（依据抓包 + 文档）。
 */
object BaiduConstants {

    /** WebView 登录页（提取 BDUSS/STOKEN） */
    const val LOGIN_URL = "https://pan.baidu.com/"

    /** 提取 Cookie 的域名 */
    const val COOKIE_DOMAIN = "https://pan.baidu.com"

    /** PC 网页 UA（share/verify、xpan/share、gettemplatevariable 使用） */
    const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"



    /** 百度客户端 UA（filemanager、locatedownload 等老接口使用） */

    const val UA_NETDISK =
        "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0"



    /**

     * 个人盘列表「App 通道」的 UA：版本号必须与 [APP_VERSION] 一致。

     *

     * 百度会交叉校验 UA 里的版本与 query 的 version——两边对不上就按「不是同一个客户端」拒绝，

     * 所以这两个值只能成对修改。

     */

    const val UA_APP =

        "netdisk;13.15.4;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0;"



    /** 个人盘列表「App 通道」的协议版本（api/list 的 version 参数） */

    const val APP_VERSION = "13.15.4"



    /** 百度网盘统一 app_id */
    const val APP_ID = "250528"

    /** 临时转存目录名（对齐抓包） */
    const val TEMP_DIR_NAME = "YunGet临时转存"

    /** 关键 Cookie 字段，缺失 BDUSS 则视为未登录 */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("BDUSS=")
}