package com.yunget.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.rememberGlobalSnackbarHostState
import com.yunget.app.ui.resolve.BackToParentItem
import com.yunget.app.ui.resolve.CrumbBar
import com.yunget.app.ui.resolve.ShareFileRow
import com.yunget.app.ui.viewmodel.Pan123CloudUiState
import com.yunget.app.ui.viewmodel.Pan123CloudViewModel
import com.yunget.app.ui.viewmodel.ResolveViewModel

/**
 * 转存到 123 云盘弹窗：浏览 123 个人网盘目录（只进文件夹），确认后转存到当前目录。
 * 复用 Pan123CloudViewModel 做目录浏览（与网盘页同一实例）；转存走 mshare copy/save（无需签名）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pan123SaveSheet(
    resolveViewModel: ResolveViewModel,
    cloudViewModel: Pan123CloudViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cloudState by cloudViewModel.uiState.collectAsState()
    val saving = resolveViewModel.isSaving
    val message = resolveViewModel.saveMessage

    LaunchedEffect(Unit) {
        cloudViewModel.loadRoot()
    }
    // 转存结果提示
    LaunchedEffect(message) {
        if (message != null) {
            SnackbarController.show(message)
            resolveViewModel.consumeSaveMessage()
        }
    }

    // ModalBottomSheet 为独立窗口，需自带 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.SaveAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "转存到123云盘",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = resolveViewModel.saveTarget?.fname ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            CrumbBar(
                rootTitle = "根目录",
                pathNames = (cloudState as? Pan123CloudUiState.Loaded)?.pathNames ?: emptyList(),
                onNavigate = { cloudViewModel.navigateToLevel(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 返回上一级：固定在目录区上方（与网盘移动弹窗一致）
            if ((cloudState as? Pan123CloudUiState.Loaded)?.pathNames?.isNotEmpty() == true) {
                BackToParentItem(onClick = { cloudViewModel.back() })
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 目录切换：淡入过渡（与网盘移动弹窗一致）
            AnimatedContent(
                targetState = cloudState,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "pan123SaveState"
            ) { s ->
                when (s) {
                    is Pan123CloudUiState.Loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }

                    is Pan123CloudUiState.Error -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { cloudViewModel.loadRoot() }) {
                                Text("重试")
                            }
                        }
                    }

                    is Pan123CloudUiState.Loaded -> {
                        val dirs = s.files.filter { it.isdir }
                        if (dirs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "当前目录没有子文件夹，可直接转存到此目录",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(dirs, key = { it.fid }) { dir ->
                                    ShareFileRow(
                                        file = dir,
                                        onClick = { cloudViewModel.openFolder(dir) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            val currentDirName =
                (cloudState as? Pan123CloudUiState.Loaded)?.pathNames?.lastOrNull() ?: "根目录"
            Button(
                onClick = {
                    val dirId = (cloudState as? Pan123CloudUiState.Loaded)?.dirId ?: "0"
                    resolveViewModel.saveToCloud(dirId)
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("转存到此目录（$currentDirName）")
                }
            }

            // 转存结果提示（ModalBottomSheet 为独立窗口，需自带 Snackbar 宿主）
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}