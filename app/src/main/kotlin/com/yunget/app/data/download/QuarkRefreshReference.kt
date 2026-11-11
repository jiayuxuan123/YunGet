package com.yunget.app.data.download

import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import org.json.JSONObject

/** Source identity only; account cookies and expiring share tokens are never stored here. */
internal data class QuarkRefreshReference(
    val shareId: String,
    val password: String,
    val fileId: String,
    val parentId: String,
    val expectedSize: Long,
    val savedFileId: String = "",
) {
    fun encode(): String = JSONObject().put("version", 1).put("shareId", shareId)
        .put("password", password).put("fileId", fileId).put("parentId", parentId)
        .put("expectedSize", expectedSize).put("savedFileId", savedFileId).toString()

    companion object {
        fun from(session: ShareSession, file: ShareFile, password: String?, savedFileId: String = "") =
            QuarkRefreshReference(session.shareId, password.orEmpty(), file.fid, file.pdirFid, file.fsize, savedFileId)

        fun decode(value: String): QuarkRefreshReference? = runCatching {
            val json = JSONObject(value)
            // Android's JSON getters coerce values that the JVM library may reject.
            fun integer(name: String): Long = when (val field = json.get(name)) {
                is Int -> field.toLong()
                is Long -> field
                else -> throw IllegalArgumentException("invalid reference integer")
            }
            fun text(name: String, optional: Boolean = false): String =
                if (optional && !json.has(name)) "" else
                    json.get(name) as? String ?: throw IllegalArgumentException("invalid reference text")
            require(integer("version") == 1L)
            val reference = QuarkRefreshReference(text("shareId"), text("password", optional = true),
                text("fileId"), text("parentId"), integer("expectedSize"),
                text("savedFileId", optional = true))
            require(reference.shareId.isNotBlank() && reference.fileId.isNotBlank())
            require(reference.expectedSize >= 0 && reference.password.length <= 128)
            reference
        }.getOrNull()
    }
}
