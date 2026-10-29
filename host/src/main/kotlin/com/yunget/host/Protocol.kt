package com.yunget.host

import org.json.JSONArray
import org.json.JSONObject

/**
 * 输出通道。
 *
 * 保证所有写 stdout 的地方都过这里：协议要求"一行一个 JSON"，
 * 任何一处直接 println 普通文本都会把 Flutter 侧的解析器打乱。
 * 日志一律走 `{"event":"log"}`。
 */
class Output(private val write: (String) -> Unit) {

    fun ok(id: String?, result: Any?) {
        val obj = JSONObject().put("id", id ?: "")
        obj.put("ok", true)
        obj.put("result", toJson(result))
        emit(obj)
    }

    fun error(id: String?, message: String) {
        val obj = JSONObject().put("id", id ?: "")
        obj.put("ok", false)
        obj.put("error", JSONObject().put("message", message))
        emit(obj)
    }

    fun event(name: String, build: JSONObject.() -> Unit) {
        val obj = JSONObject().put("event", name)
        obj.build()
        emit(obj)
    }

    fun log(message: String) = event("log") { put("message", message) }

    private fun emit(obj: JSONObject) {
        // 同步写：多个协程可能同时拼一行，交给调用方保证原子性（这里是单进程单输出）。
        synchronized(this) { write(obj.toString()) }
    }

    private fun toJson(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (k, v) -> put(k.toString(), toJson(v)) }
        }
        is List<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        else -> value.toString()
    }
}
