package com.yunget.app.data.network

/**
 * 夸克 API 业务异常：携带服务端返回的 message 与 code 字段，
 * 用于把具体错误原因（如「提取码错误」「分享已失效」「file not found」）透传给 UI；
 * code 供上层识别特定错误（如 21001 触发兜底重转）。
 */
class QuarkApiException(message: String, val code: Int = 0) : Exception(message)