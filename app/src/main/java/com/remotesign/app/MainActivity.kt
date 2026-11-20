package com.remotesign.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.remotesign.app.data.PollResult
import com.remotesign.app.data.Session
import com.remotesign.app.data.SignApi
import com.remotesign.app.data.SignInfo
import com.remotesign.app.data.SignSpec
import com.remotesign.app.data.TokenStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- 主题（图书馆绿 + 奶油纸面） ----------

private val LibraryGreen = Color(0xFF004D4E)
private val LibraryAmber = Color(0xFFF2B25C)

private val LightColors = lightColorScheme(
    primary = LibraryGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBEB),
    onPrimaryContainer = Color(0xFF003233),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA8),
    onSecondaryContainer = Color(0xFF4A3000),
    background = Color(0xFFFAF6EC),
    onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFFFDF6),
    onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFEDE7D6),
    onSurfaceVariant = Color(0xFF5A564A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7ED3D6),
    onPrimary = Color(0xFF003233),
    primaryContainer = Color(0xFF00585A),
    onPrimaryContainer = Color(0xFFB2EDEE),
    secondary = LibraryAmber,
    onSecondary = Color(0xFF4A3000),
    secondaryContainer = Color(0xFF6B4A00),
    onSecondaryContainer = Color(0xFFFFDEA8),
    background = Color(0xFF101613),
    onBackground = Color(0xFFE4E2D8),
    surface = Color(0xFF182019),
    onSurface = Color(0xFFE4E2D8),
    surfaceVariant = Color(0xFF2A332A),
    onSurfaceVariant = Color(0xFFB9BCAE),
)

@Composable
private fun LibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

// ---------- 状态 ----------

private sealed interface UiState {
    data object Home : UiState
    data object FriendInput : UiState
    data class Loading(val text: String) : UiState
    data class Qr(val info: SignInfo) : UiState
    data class Signed(val info: SignInfo) : UiState
    data class Notice(val text: String) : UiState
    data object OwnerScan : UiState
    data class OwnerCredential(val session: Session) : UiState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LibraryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LibrarySignApp()
                }
            }
        }
    }
}

@Composable
private fun LibrarySignApp() {
    val context = LocalContext.current
    val store = remember { TokenStore(context) }
    val api = remember { SignApi(store) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<UiState>(UiState.Home) }
    var homeSession by remember { mutableStateOf(store.session()) }

    fun goHome() {
        homeSession = store.session()
        state = UiState.Home
    }

    fun fetchAndShowQr() {
        state = UiState.Loading("正在获取签到码…")
        scope.launch {
            state = try {
                val session = store.session()
                when {
                    session == null -> UiState.Notice("凭证格式无法识别，请重新导入")
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

    // 子页面统一返回首页
    BackHandler(enabled = state != UiState.Home && state !is UiState.Loading) { goHome() }

    when (val s = state) {
        UiState.Home -> HomeScreen(
            session = homeSession,
            onFriend = { state = UiState.FriendInput },
            onOwner = { state = UiState.OwnerScan },
            onShowQr = { fetchAndShowQr() },
            onClear = {
                store.clear()
                homeSession = null
            },
        )

        UiState.FriendInput -> FriendInputScreen(
            onSubmit = { text ->
                if (store.save(text) == null) {
                    null
                } else {
                    fetchAndShowQr()
                    Unit
                }
            },
            onBack = { goHome() },
        )

        is UiState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(s.text)
            }
        }

        is UiState.Qr -> QrScreen(
            info = s.info,
            api = api,
            store = store,
            onSigned = { state = UiState.Signed(it) },
            onExpired = { state = UiState.Notice("凭证已过期，请让对方重新发送") },
            onBack = { goHome() },
        )

        is UiState.Signed -> SignedScreen(
            info = s.info,
            onDone = { goHome() },
        )

        is UiState.Notice -> NoticeScreen(
            text = s.text,
            onBack = { goHome() },
        )

        UiState.OwnerScan -> OwnerScanScreen(
            api = api,
            store = store,
            onGot = { session -> state = UiState.OwnerCredential(session) },
            onBack = { goHome() },
        )

        is UiState.OwnerCredential -> OwnerCredentialScreen(
            session = s.session,
            store = store,
            onShowQr = { fetchAndShowQr() },
            onDone = { goHome() },
        )
    }
}

// ---------- 首页 ----------

@Composable
private fun HomeScreen(
    session: Session?,
    onFriend: () -> Unit,
    onOwner: () -> Unit,
    onShowQr: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Text("图书馆随签", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "我去图书馆 · 远程代签到",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(24.dp))

        EntryCard(
            badge = "签",
            badgeColor = MaterialTheme.colorScheme.primary,
            title = "代朋友签到",
            desc = "粘贴朋友发来的登录凭证，出示他的签到码",
            onClick = onFriend,
        )
        Spacer(Modifier.height(12.dp))
        EntryCard(
            badge = "钥",
            badgeColor = MaterialTheme.colorScheme.secondary,
            title = "获取我的凭证",
            desc = "微信扫码授权，一键拿到自己的凭证发给朋友",
            onClick = onOwner,
        )

        if (session != null && session.isValid()) {
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "本机已存有有效凭证",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    RemainText(expireAtMs = session.expireAtMs, prefix = "剩余 ")
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(onClick = onShowQr) { Text("出示签到码") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = onClear) { Text("清除") }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "凭证约 2 小时有效，等同这段时间内的账号钥匙，只发给信得过的人。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryCard(
    badge: String,
    badgeColor: Color,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    desc,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 凭证剩余时间（每秒刷新） */
@Composable
private fun RemainText(expireAtMs: Long, prefix: String = "") {
    var remain by remember { mutableLongStateOf(expireAtMs - System.currentTimeMillis()) }
    LaunchedEffect(expireAtMs) {
        while (true) {
            remain = expireAtMs - System.currentTimeMillis()
            if (remain <= 0) break
            delay(1000)
        }
    }
    Text(
        prefix + if (remain > 0) formatRemain(remain) else "已过期",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------- 代朋友签到：粘贴凭证 ----------

@Composable
private fun FriendInputScreen(
    onSubmit: (String) -> Any?,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("代朋友签到", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "把朋友发来的登录凭证粘贴到下面，获取他的签到码。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            label = { Text("登录凭证") },
            placeholder = { Text("Authorization=eyJ... 或整行 Cookie") },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (onSubmit(input) == null) {
                    error = "没识别出凭证，请粘贴 Authorization=... 或整行 Cookie"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = input.isNotBlank(),
        ) { Text("获取签到码") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { clipboard.getText()?.text?.let { input = it; error = null } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("从剪贴板粘贴") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("返回") }
    }
}

// ---------- 获取我的凭证：微信扫码授权 ----------

@Composable
private fun OwnerScanScreen(
    api: SignApi,
    store: TokenStore,
    onGot: (Session) -> Unit,
    onBack: () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastTriedCode by remember { mutableStateOf<String?>(null) }

    fun tryFromClipboard(manual: Boolean) {
        if (busy) return
        val text = clipboard.getText()?.text
        if (text.isNullOrBlank()) {
            if (manual) error = "剪贴板是空的，请先在微信里复制授权后的回调链接"
            return
        }
        // 剪贴板里直接就是 JWT 的情况
        val direct = store.save(text)
        if (direct != null) {
            onGot(direct)
            return
        }
        val code = SignApi.extractCode(text)
        if (code == null) {
            if (manual) error = "剪贴板里没找到授权链接，请在微信里打开授权页后复制链接再回来"
            return
        }
        if (code == lastTriedCode) return
        lastTriedCode = code
        busy = true
        scope.launch {
            try {
                val session = api.exchangeCode(code)
                onGot(session)
            } catch (e: Exception) {
                error = e.message ?: "换取凭证失败"
                busy = false
            }
        }
    }

    // 从微信切回来时自动检测剪贴板
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tryFromClipboard(manual = false)
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val qrBitmap = remember { makeQrBitmap(SignSpec.authUrl(), highDensity = true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "获取我的凭证",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "用微信扫一扫下面的二维码完成授权，然后在微信里复制回调页的链接，切回本 App 即可自动换取凭证。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Box(Modifier.padding(14.dp)) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "微信授权二维码",
                        modifier = Modifier.size(260.dp),
                    )
                } else {
                    Text("二维码生成失败", modifier = Modifier.size(260.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("正在换取凭证…")
        } else {
            Button(
                onClick = { tryFromClipboard(manual = true) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("我已复制链接，立即获取") }
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("返回") }
        Spacer(Modifier.height(16.dp))
        Text(
            "授权码约 5 分钟有效，过期重新扫码即可。二维码是官方公众号的授权链接，扫码后在微信内完成，本 App 不接触你的微信密码。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------- 我的凭证 ----------

@Composable
private fun OwnerCredentialScreen(
    session: Session,
    store: TokenStore,
    onShowQr: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val token = session.token
    val masked = if (token.length > 22) {
        token.take(12) + " … " + token.takeLast(8)
    } else token

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("我的凭证", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "把凭证发给在图书馆的朋友，他粘贴进本 App 就能替你出示签到码。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    masked,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                RemainText(expireAtMs = session.expireAtMs, prefix = "有效期剩余 ")
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                clipboard.setText(AnnotatedString(token))
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (copied) "已复制，去粘贴给朋友" else "复制完整凭证") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, token)
                }
                context.startActivity(Intent.createChooser(intent, "发送凭证"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("分享给朋友") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onShowQr,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("出示我自己的签到码") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("完成") }
        Spacer(Modifier.height(16.dp))
        Text(
            "凭证在有效期内等同于你的账号钥匙（可查预约、可退座），只发给信得过的人；过期后自动作废。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- 签到码出示 ----------

@Composable
private fun QrScreen(
    info: SignInfo,
    api: SignApi,
    store: TokenStore,
    onSigned: (SignInfo) -> Unit,
    onExpired: () -> Unit,
    onBack: () -> Unit,
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
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    listOf(current.libName, current.floor)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (current.seatName.isNotBlank()) {
                    Text("座位 ${current.seatName}", fontSize = 17.sp)
                }
                if (current.expText.isNotBlank()) {
                    Text(
                        current.expText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "签到二维码",
                            modifier = Modifier.size(272.dp),
                        )
                    } else {
                        Text("二维码生成失败", modifier = Modifier.size(272.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "凭证剩余 ${formatRemain(remainMs)}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(pollHint, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onBack) { Text("返回首页") }
    }
}

// ---------- 结果页 ----------

@Composable
private fun SignedScreen(info: SignInfo, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text("签到成功", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            listOf(info.libName, info.floor, info.seatName)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onDone) { Text("返回首页") }
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
        Button(onClick = onBack) { Text("返回首页") }
    }
}

// ---------- 工具 ----------

private fun formatRemain(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun makeQrBitmap(content: String, highDensity: Boolean = false): Bitmap? = try {
    val size = 880
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to if (highDensity) ErrorCorrectionLevel.M else ErrorCorrectionLevel.L,
    )
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
