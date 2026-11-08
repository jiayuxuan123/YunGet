package com.yunget.app.ui.resolve

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.ui.preview.PreviewTypes
import com.yunget.app.ui.preview.PreviewKind
import com.yunget.app.ui.preview.previewNetworkHeaders
import com.yunget.app.ui.theme.ThemeController

@Composable
internal fun FileLeadingIcon(file: ShareFile) {
    val thumbnail = file.thumbnail?.takeIf {
        !file.isdir &&
            ThemeController.cloudThumbnails &&
            // 只给图片：视频/文档的缩略图各家给得并不一致，先不放开
            PreviewTypes.kindOf(file.fname) == PreviewKind.IMAGE
    }
    if (thumbnail == null) {
        FileIconBadge(file = file)
        return
    }
    val context = LocalContext.current
    val request = remember(thumbnail.url, thumbnail.headers) {
        ImageRequest.Builder(context)
            .data(thumbnail.url)
            .httpHeaders(previewNetworkHeaders(thumbnail.headers))
            .build()
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

/** 文件夹及无缩略图文件的 tonal 图标，与解析列表的 16dp 形状保持一致。 */
@Composable
private fun FileIconBadge(file: ShareFile) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (file.isdir) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (file.isdir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (file.isdir) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

