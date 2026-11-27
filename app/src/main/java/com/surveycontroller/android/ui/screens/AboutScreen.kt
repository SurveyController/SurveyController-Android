package com.surveycontroller.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.surveycontroller.android.R
import com.surveycontroller.android.app.AppVersion
import com.surveycontroller.android.ui.MainViewModel
import com.surveycontroller.android.ui.components.ZoomableImageDialog
import com.surveycontroller.android.ui.components.StudioCard
import com.surveycontroller.android.ui.components.studioFeature

private data class Contributor(val name: String, val url: String)

private val CONTRIBUTORS = listOf(
    Contributor("@HUNGRY_M0", "https://github.com/hungryM0"),
    Contributor("@shiaho777", "https://github.com/shiaho777"),
    Contributor("@BingBuLiang", "https://github.com/BingBuLiang"),
    Contributor("@dAwn-Rebirth", "https://github.com/dAwn-Rebirth"),
    Contributor("@Moyuin-aka", "https://github.com/Moyuin-aka"),
    Contributor("@qintaiyang", "https://github.com/qintaiyang"),
)

@Composable
fun AboutScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val update by viewModel.update.collectAsState()
    val checking by viewModel.checkingUpdate.collectAsState()
    var showTerms by remember { mutableStateOf(false) }
    // 当前放大查看的图片：null=不显示，Pair(drawableRes, fileName)
    var zoomImage by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    val repoUrl = "https://github.com/${AppVersion.GITHUB_OWNER}/${AppVersion.GITHUB_REPO}"

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().studioFeature().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painter = painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(54.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("SurveyController", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
                Text("Android · v${AppVersion.VERSION}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }

        // 免责声明警示条
        StudioCard(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(22.dp))
                Text(
                    "本项目仅供学习交流使用，开源以供研究软件原理，禁止用于任何恶意滥用行为。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        // 检查更新
        StudioCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("当前版本", style = MaterialTheme.typography.titleMedium)
                        Text("v${AppVersion.VERSION} · Android", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (checking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else OutlinedButton(onClick = viewModel::checkUpdate) { Text("检查更新") }
                }
                update?.let { u ->
                    Column(Modifier.padding(top = 8.dp)) {
                        when {
                            u.error != null -> Text("检查失败：${u.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            u.hasUpdate -> {
                                Text("发现新版本 ${u.latestVersion}（当前 ${u.currentVersion}）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                if (u.releaseNotes.isNotBlank()) Text(u.releaseNotes.take(300), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    u.apkDownloadUrl?.let { apk -> Button(onClick = { open(apk) }) { Text("下载 APK") } }
                                    OutlinedButton(onClick = { open(u.releaseUrl) }) { Text("前往发行页") }
                                }
                            }
                            else -> Text("已是最新版本（${u.currentVersion}）", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(AppVersion.RELEASES_PAGE) }) { Text("更新日志") }
                    OutlinedButton(onClick = { open(AppVersion.DOC_SITE) }) { Text("使用文档") }
                    OutlinedButton(onClick = { open(repoUrl) }) { Text("GitHub") }
                }
            }
        }

        // 社区 / 联系 / 贡献
        StudioCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("社区", style = MaterialTheme.typography.titleMedium)
                Text("加入交流群获取最新版本、反馈问题和交流经验。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Image(
                    painter = painterResource(R.drawable.community_qr),
                    contentDescription = "社区二维码",
                    modifier = Modifier
                        .size(180.dp)
                        .padding(top = 10.dp)
                        .align(Alignment.CenterHorizontally)
                        .clickable { zoomImage = R.drawable.community_qr to "community_qr" },
                    contentScale = ContentScale.Fit,
                )
                Text("扫码加入 QQ 交流群（点击放大）", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(repoUrl) }) { Text("参与贡献") }
                }
            }
        }

        // 赞赏支持
        StudioCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("支持作者", style = MaterialTheme.typography.titleMedium)
                Text("如果这个项目对你有帮助，欢迎请作者喝杯奶茶~", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.donate_wechat),
                            contentDescription = "微信赞赏",
                            modifier = Modifier
                                .size(140.dp)
                                .clickable { zoomImage = R.drawable.donate_wechat to "donate_wechat" },
                        )
                        Text("微信赞赏（点击放大）", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.donate_alipay),
                            contentDescription = "支付宝赞赏",
                            modifier = Modifier
                                .size(140.dp)
                                .clickable { zoomImage = R.drawable.donate_alipay to "donate_alipay" },
                        )
                        Text("支付宝（点击放大）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 致谢 / 许可 / 条款
        StudioCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("开源许可", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("GPL-3.0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Text("分发或修改时须按 GPL-3.0 提供源代码，确保接收者享有使用、研究、修改与再分发的自由。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { open("$repoUrl/blob/main/LICENSE") }) { Text("查看协议") }

                Text("贡献者", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                CONTRIBUTORS.chunked(2).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { c ->
                            OutlinedButton(onClick = { open(c.url) }, modifier = Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                        if (rowItems.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }

                OutlinedButton(onClick = { showTerms = true }, modifier = Modifier.padding(top = 4.dp)) { Text("服务条款与隐私声明") }
            }
        }

        Text(
            "Copyright © 2026 HUNGRY_M0. All rights reserved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
    }

    if (showTerms) TermsDialog(onDismiss = { showTerms = false })
    zoomImage?.let { (res, name) ->
        ZoomableImageDialog(drawableRes = res, fileName = name, onDismiss = { zoomImage = null })
    }
}
