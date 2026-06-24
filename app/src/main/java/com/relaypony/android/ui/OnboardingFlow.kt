package com.relaypony.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relaypony.android.R
import com.relaypony.android.transfer.QrImage
import com.relaypony.android.transfer.TransferController
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 5

@Composable
fun OnboardingFlow(controller: TransferController, onFinish: () -> Unit) {
    val pager = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (page) {
                        0 -> WelcomePage()
                        1 -> LanguagePage(controller)
                        2 -> HowItWorksPage()
                        3 -> DevicePage(controller)
                        else -> PermissionsPage()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(PAGE_COUNT) { i ->
                    val active = i == pager.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (active) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pager.currentPage > 0) {
                    TextButton(onClick = {
                        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                    }) { Text(stringResource(R.string.ob_back)) }
                } else {
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.ob_skip)) }
                }
                if (pager.currentPage < PAGE_COUNT - 1) {
                    Button(onClick = {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }) { Text(stringResource(R.string.ob_next)) }
                } else {
                    Button(onClick = onFinish) { Text(stringResource(R.string.ob_get_started)) }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Icon(
        Icons.Filled.Lock,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(72.dp),
    )
    Text(
        "RelayPony",
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(
        stringResource(R.string.ob_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun LanguagePage(controller: TransferController) {
    val languages = listOf(
        "en" to "English",
        "es" to "Espa\u00f1ol",
        "de" to "Deutsch",
        "fr" to "Fran\u00e7ais",
        "ja" to "\u65e5\u672c\u8a9e",
        "pt-BR" to "Portugu\u00eas (BR)",
        "hi" to "\u0939\u093f\u0928\u094d\u0926\u0940",
    )
    Text(stringResource(R.string.ob_language_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.ob_language_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
    languages.forEach { (code, label) ->
        val live = code in Locales.LIVE
        val selected = controller.languageCode.value == code
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .then(if (live) Modifier.clickable { controller.setLanguage(code) } else Modifier),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (live) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    selected -> Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.lang_selected), tint = MaterialTheme.colorScheme.primary)
                    !live -> Text(stringResource(R.string.lang_soon), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HowItWorksPage() {
    val travel = remember { Animatable(0f) }
    var runId by remember { mutableIntStateOf(0) }
    var delivered by remember { mutableStateOf(false) }

    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        delivered = false
        travel.snapTo(0f)
        travel.animateTo(1f, animationSpec = tween(1200))
        delivered = true
    }

    Text(stringResource(R.string.ob_how_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.ob_how_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(96.dp),
    ) {
        val track = maxWidth - 48.dp
        PhoneGlyph(modifier = Modifier.align(Alignment.CenterStart))
        PhoneGlyph(modifier = Modifier.align(Alignment.CenterEnd))
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 4.dp + track * travel.value)
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (delivered) Icons.Filled.Check else Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    OutlinedButton(
        onClick = { runId++ },
        modifier = Modifier.padding(top = 20.dp),
    ) { Text(if (delivered) stringResource(R.string.ob_send_again) else stringResource(R.string.ob_send_demo)) }
    if (delivered) {
        Text(
            stringResource(R.string.ob_delivered),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PhoneGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 40.dp, height = 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun DevicePage(controller: TransferController) {
    val qr = remember { QrImage.generate(controller.myQrText()) }
    Text(stringResource(R.string.ob_device_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.ob_device_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
    Image(
        bitmap = qr.asImageBitmap(),
        contentDescription = stringResource(R.string.ob_device_qr_desc),
        modifier = Modifier.size(220.dp),
    )
    Text(
        stringResource(R.string.ob_this_device, controller.deviceName),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun PermissionsPage() {
    Text(stringResource(R.string.ob_perms_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.ob_perms_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
    PermissionRow(stringResource(R.string.ob_perm_camera_title), stringResource(R.string.ob_perm_camera_detail))
    PermissionRow(stringResource(R.string.ob_perm_nearby_title), stringResource(R.string.ob_perm_nearby_detail))
    PermissionRow(stringResource(R.string.ob_perm_storage_title), stringResource(R.string.ob_perm_storage_detail))
}

@Composable
private fun PermissionRow(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
