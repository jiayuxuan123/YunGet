package com.yunget.app.data.network

import com.yunget.app.data.upload.UploadIo
import com.yunget.app.data.upload.UploadSource
import com.yunget.app.data.upload.UploadPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

    suspend fun LanzouApi.upload(

        io: UploadIo,

        file: UploadSource,

        parentFolderId: String,

        cookie: String,

        uid: String,

        vei: String

    ): String? = withContext(Dispatchers.IO) {

        io.progress(UploadPhase.PREPARING)

        val response = io.send(

            url = "${LanzouConstants.HTML5UP_URL}?uid=$uid&vei=$vei",

            method = "POST",

            headers = mapOf(

                "Cookie" to cookie,

                "User-Agent" to LanzouConstants.WEB_UA,

                "Referer" to LanzouConstants.MYDISK_URL,

                "Origin" to LanzouConstants.ORIGIN

            ),

            fields = listOf(

                "task" to "1",

                "vie" to "2",

                "ve" to "2",

                "id" to "WU_FILE_0",

                "name" to file.name,

                "folder_id_bb_n" to parentFolderId.ifBlank { LanzouConstants.ROOT_FOLDER_ID }

            ),

            fieldName = "upload_file",

            retries = 0

        )

        if (response.code == 401 || response.code == 403) {

            throw IllegalStateException("蓝奏登录已过期，请重新网页登录")

        }

        if (!response.isSuccessful) {

            throw IllegalStateException("文件上传失败（HTTP ${response.code}）")

        }

        io.progress(UploadPhase.FINISHING, file.size)

        val json = runCatching { JSONObject(response.body) }.getOrElse {

            if (response.body.trimStart().startsWith("<")) {

                throw IllegalStateException("蓝奏返回了验证页面，请先在官网完成验证后再上传")

            }

            throw IllegalStateException("蓝奏上传响应异常，请重试")

        }

        val zt = json.optInt("zt", -1)

        if (zt != 1) {

            val info = json.optString("info").replace(Regex("<[^>]*>"), "").trim()

            when (zt) {

                4 -> throw IllegalStateException("蓝奏请求频繁，请稍后重试")

                9 -> throw IllegalStateException("蓝奏登录已过期，请重新网页登录")

                else -> throw IllegalStateException(

                    "蓝奏上传失败（zt=$zt）" +

                        if (info.isNotBlank()) "：$info" else "，请在官网检查文件类型与大小"

                )

            }

        }

        val id = json.optJSONArray("text")?.optJSONObject(0)?.optString("id").orEmpty()

        if (id.isBlank()) throw IllegalStateException("蓝奏未返回上传文件信息")

        LanzouConstants.FILE_PREFIX + id

    }



