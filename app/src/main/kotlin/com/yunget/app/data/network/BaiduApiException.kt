package com.yunget.app.data.network

/**
 * 百度网盘 API 业务异常：携带服务端 errno/err_msg，
 * 用于把具体错误原因（如「提取码错误」「分享已失效」）透传给 UI。
 */
class BaiduApiException(message: String) : Exception(message)