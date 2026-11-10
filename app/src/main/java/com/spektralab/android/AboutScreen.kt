package com.spektralab.android

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal const val PROJECT_REPOSITORY_URL = "https://github.com/deepspyder/theia"
internal const val UPSTREAM_PROJECT_URL = "https://github.com/andreavolpato/spektrafilm"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("关于", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回编辑器")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                expandedHeight = 56.dp,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Text("Theia", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(6.dp))
                Text("胶片与暗房模拟" + if (version.isNotBlank()) " · $version" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.author_avatar),
                        contentDescription = "米瑞斯的猫的头像",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(80.dp).clip(TheiaShapes.panel),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("作者", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("米瑞斯的猫", style = MaterialTheme.typography.titleLarge)
                    }
                }

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(28.dp))
                Text("免费使用，开放源码", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text("Theia 是一个免费开源的胶片与暗房模拟项目。官方不收取下载、安装或使用费用。",
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Text("基于 spektrafilm 开发，感谢原项目作者与开源贡献者。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(UPSTREAM_PROJECT_URL, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(20.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = TheiaShapes.panel) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("遇到收费？请申请退款", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("如果你为获取本应用支付了费用，请向收费方申请退款。下载与更新请以 GitHub 仓库公开的信息为准。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                Spacer(Modifier.height(28.dp))
                Text("项目仓库", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(PROJECT_REPOSITORY_URL, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        try {
                            uriHandler.openUri(PROJECT_REPOSITORY_URL)
                        } catch (_: ActivityNotFoundException) {
                            scope.launch { snackbar.showSnackbar("未找到浏览器，请复制仓库地址后打开") }
                        } catch (_: IllegalArgumentException) {
                            scope.launch { snackbar.showSnackbar("暂时无法打开链接，请复制仓库地址") }
                        }
                    },
                    shape = TheiaShapes.control,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开 GitHub 仓库")
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(PROJECT_REPOSITORY_URL))
                    scope.launch { snackbar.showSnackbar("仓库地址已复制") }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制仓库地址")
                }
                Spacer(Modifier.height(20.dp))
                Text("感谢每一位使用者与开源贡献者。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
