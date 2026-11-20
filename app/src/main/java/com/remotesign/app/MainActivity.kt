package com.remotesign.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.remotesign.app.data.PollResult
import com.remotesign.app.data.SignApi
import com.remotesign.app.data.SignInfo
import com.remotesign.app.data.TokenStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface UiState {
    data object Input : UiState
    data object Loading : UiState
    data class Qr(val info: SignInfo) : UiState
    data class Signed(val info: SignInfo) : UiState
    data class Notice(val text: String) : UiState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RemoteSignApp()
                }
            }
        }
    }
}

@Composable
private fun RemoteSignApp() {
    val context = LocalContext.current
    val store = remember { TokenStore(context) }
    val api = remember { SignApi(store) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<UiState>(UiState.Input) }
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    fun fetchAndShow() {
        state = UiState.Loading
        scope.launch {
            state = try {
                val session = store.session()
                when {
                    session == null -> {
                        inputError = "凭证格式无法识别"
                        UiState.Input
                    }
                    !session.isValid() -> UiState.Notice("凭证已过期，请让对方重新发送")
                    else -> {
                        val info = api.signInfo()
                        if (info == null) UiState.Notice("当前没有待签到的预约")
                        else UiState.Qr(info)
                    }
                }
            } catch (e: Exception) {
                UiState.Notice(e.message ?: "请求失败，请重试")
            }
        }
    }

    // 启动时若有未过期的凭证，直接拉签到码
    LaunchedEffect(Unit) {
        val session = store.session()
        if (session != null && session.isValid()) fetchAndShow()
    }

    when (val s = state) {
        UiState.Input -> InputScreen(
            input = input,
            error = inputError,
            onInputChange = { input = it; inputError = null },
            onSubmit = {
                if (store.save(input) == null) {
                    inputError = "没识别出凭证，请粘贴 Authorization=... 或整行 Cookie"
                } else {
                    input = ""
                    fetchAndShow()
                }
            },
        )

        UiState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { Text("正在获取签到码…") }

        is UiState.Qr -> QrScreen(
            info = s.info,
            api = api,
            store = store,
            onSigned = { state = UiState.Signed(it) },
            onExpired = { state = UiState.Notice("凭证已过期，请让对方重新发送") },
            onChangeCredential = {
                store.clear()
                state = UiState.Input
            },
        )

        is UiState.Signed -> SignedScreen(
            info = s.info,
            onDone = {
                store.clear()
                state = UiState.Input
            },
        )

        is UiState.Notice -> NoticeScreen(
            text = s.text,
            onBack = { state = UiState.Input },
        )
    }
}

@Composable
private fun InputScreen(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("远程签到", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "把对方发来的登录凭证粘贴到下面，获取他的图书馆签到码，代为出示签到。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            label = { Text("登录凭证") },
            placeholder = { Text("Authorization=eyJ... 或整行 Cookie") },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = input.isNotBlank(),
        ) { Text("获取签到码") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { clipboard.getText()?.text?.let(onInputChange) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("从剪贴板粘贴") }
        Spacer(Modifier.height(20.dp))
        Text(
            "凭证等同于对方账号的临时钥匙（约 2 小时有效），只能看到并出示签到码；用完可在签到页清除。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QrScreen(
    info: SignInfo,
    api: SignApi,
    store: TokenStore,
    onSigned: (SignInfo) -> Unit,
    onExpired: () -> Unit,
    onChangeCredential: () -> Unit,
) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(info) }
    var pollHint by remember { mutableStateOf("把二维码对准签到设备出示") }
    var remainMs by remember { mutableLongStateOf(store.session()?.remainMs() ?: 0) }

    // 出示二维码时保持亮屏并拉满亮度，方便扫码设备识别
    DisposableEffect(Unit) {
        val window = (context as? ComponentActivity)?.window
        val oldBrightness = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window?.attributes?.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = oldBrightness
                    ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // 凭证倒计时
    LaunchedEffect(Unit) {
        while (true) {
            remainMs = store.session()?.remainMs() ?: 0
            if (remainMs <= 0) {
                onExpired()
                break
            }
            delay(1000)
        }
    }

    // 轮询换码/签到状态（官方前端是 9 秒，这里 5 秒更跟手）
    LaunchedEffect(current.qrContent) {
        while (true) {
            delay(5000)
            when (val r = api.pollQr(current.qrContent)) {
                PollResult.StillValid -> pollHint = "把二维码对准签到设备出示"
                is PollResult.Rotated -> {
                    current = current.copy(qrContent = r.newToken)
                    pollHint = "二维码已刷新，请出示新码"
                }
                PollResult.Signed -> {
                    onSigned(current)
                    break
                }
                PollResult.Expired -> {
                    onExpired()
                    break
                }
                is PollResult.Failed -> pollHint = "状态刷新失败（${r.message}），码仍可出示"
            }
        }
    }

    val qrBitmap = remember(current.qrContent) { makeQrBitmap(current.qrContent) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            listOf(current.libName, current.floor).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (current.seatName.isNotBlank()) {
            Text("座位 ${current.seatName}", fontSize = 18.sp)
        }
        if (current.expText.isNotBlank()) {
            Text(
                current.expText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .background(Color.White)
                .padding(12.dp),
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "签到二维码",
                    modifier = Modifier.size(280.dp),
                )
            } else {
                Text("二维码生成失败", modifier = Modifier.size(280.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(pollHint, textAlign = TextAlign.Center)
        Text(
            "凭证剩余 ${formatRemain(remainMs)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onChangeCredential) { Text("清除凭证 / 换一个") }
    }
}

@Composable
private fun SignedScreen(info: SignInfo, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✅", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("签到成功", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            listOf(info.libName, info.floor, info.seatName)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone) { Text("完成并清除凭证") }
    }
}

@Composable
private fun NoticeScreen(text: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}

private fun formatRemain(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun makeQrBitmap(content: String): Bitmap? = try {
    val size = 880
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val black = android.graphics.Color.BLACK
    val white = android.graphics.Color.WHITE
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) black else white)
        }
    }
    bitmap
} catch (e: Exception) {
    null
}
