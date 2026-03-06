package org.monogram.presentation.features.webview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.settings.sessions.SectionHeader
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternalWebView(
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("Loading...") }
    var currentUrl by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isDesktopMode by remember { mutableStateOf(false) }
    var isAdBlockEnabled by remember { mutableStateOf(true) }
    var textZoom by remember { mutableIntStateOf(100) }

    var showFindInPage by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var activeMatchIndex by remember { mutableIntStateOf(0) }
    var matchCount by remember { mutableIntStateOf(0) }

    var isSecure by remember { mutableStateOf(url.startsWith("https")) }
    var sslCertificate by remember { mutableStateOf<SslCertificate?>(null) }
    var showCertificateSheet by remember { mutableStateOf(false) }
    val certificateSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val displayUrl = remember(currentUrl) {
        currentUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
    }

    val adBlockKeywords = remember {
        listOf(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adsystem.com", "adservice.com", "analytics", "/ads/", "/banners/", "tracker",
            "metrika", "sentry", "crashlytics", "app-measurement.com",
            "amplitude.com", "mixpanel.com", "facebook.com/tr", "adfox.ru",
            "ad.mail.ru", "track.mail.ru", "tns-counter.ru", "hotjar.com", "inspectlet.com"
        )
    }

    val defaultUserAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    LaunchedEffect(isDesktopMode) {
        webView?.settings?.userAgentString = if (isDesktopMode) desktopUserAgent else defaultUserAgent
        webView?.reload()
    }

    LaunchedEffect(textZoom) {
        webView?.settings?.textZoom = textZoom
    }

    DisposableEffect(webView) {
        val listener = WebView.FindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                matchCount = numberOfMatches
                activeMatchIndex = activeMatchOrdinal
            }
        }
        webView?.setFindListener(listener)
        onDispose {
            webView?.setFindListener(null)
        }
    }

    LaunchedEffect(findQuery) {
        if (findQuery.isNotEmpty()) {
            webView?.findAllAsync(findQuery)
        } else {
            webView?.clearMatches()
            matchCount = 0
            activeMatchIndex = 0
        }
    }

    val dismissBottomSheet: () -> Unit = {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                showBottomSheet = false
            }
        }
    }

    val dismissCertificateSheet: () -> Unit = {
        scope.launch {
            certificateSheetState.hide()
        }.invokeOnCompletion {
            if (!certificateSheetState.isVisible) {
                showCertificateSheet = false
            }
        }
    }

    BackHandler {
        if (showBottomSheet) {
            dismissBottomSheet()
        } else if (showCertificateSheet) {
            dismissCertificateSheet()
        } else if (showFindInPage) {
            showFindInPage = false
            webView?.clearMatches()
        } else if (canGoBack) {
            webView?.goBack()
        } else {
            onDismiss()
        }
    }

    if (showCertificateSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCertificateSheet = false },
            sheetState = certificateSheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Icon(
                        imageVector = if (isSecure) Icons.Rounded.Lock else Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = if (isSecure) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (isSecure) "Security Information" else "Insecure Connection",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (isSecure && sslCertificate != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "The connection to this site is encrypted and secure.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        CertificateInfoItem("Issued to", sslCertificate?.issuedTo?.dName ?: "Unknown")
                        CertificateInfoItem("Issued by", sslCertificate?.issuedBy?.dName ?: "Unknown")
                        CertificateInfoItem("Valid until", sslCertificate?.validNotAfterDate?.toString() ?: "Unknown")
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(16.dp)) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "The connection to this site is not secure. You should not enter any sensitive information (such as passwords or credit cards) because it could be stolen by attackers.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = dismissCertificateSheet,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Close")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = showFindInPage,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith
                            fadeOut(animationSpec = tween(300))
                },
                label = "TopBarAnimation"
            ) { isFinding ->
                if (isFinding) {
                    TopAppBar(
                        title = {
                            SettingsTextField(
                                value = findQuery,
                                onValueChange = { findQuery = it },
                                placeholder = "Find in page...",
                                icon = Icons.Rounded.Search,
                                position = ItemPosition.STANDALONE,
                                singleLine = true,
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (matchCount > 0) {
                                            Text(
                                                "${activeMatchIndex + 1}/$matchCount",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                        }
                                        IconButton(onClick = { webView?.findNext(false) }) {
                                            Icon(Icons.Rounded.KeyboardArrowUp, "Previous")
                                        }
                                        IconButton(onClick = { webView?.findNext(true) }) {
                                            Icon(Icons.Rounded.KeyboardArrowDown, "Next")
                                        }
                                    }
                                }
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                showFindInPage = false
                                webView?.clearMatches()
                            }) {
                                Icon(Icons.Rounded.Close, "Close Find")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column(
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.clickable { showCertificateSheet = true }
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isSecure) Icons.Rounded.Lock else Icons.Rounded.Public,
                                        contentDescription = if (isSecure) "Secure" else "Insecure",
                                        modifier = Modifier.size(12.dp),
                                        tint = if (isSecure) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = displayUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showBottomSheet = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                safeBrowsingEnabled = true
                            }
                            setSupportZoom(true)
                        }
                        webViewClient = object : WebViewClient() {
                            private var hasSslError = false

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                hasSslError = false
                                url?.let { currentUrl = it }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                title = view?.title ?: "Web View"
                                url?.let {
                                    currentUrl = it
                                    isSecure = it.startsWith("https") && !hasSslError
                                    sslCertificate = view?.certificate
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                hasSslError = true
                                isSecure = false
                                super.onReceivedSslError(view, handler, error)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val url = uri.toString()
                                val context = view?.context ?: return false

                                if (url.startsWith("intent://")) {
                                    try {
                                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                        if (intent != null) {
                                            val packageManager = context.packageManager
                                            val info = packageManager.resolveActivity(
                                                intent,
                                                PackageManager.MATCH_DEFAULT_ONLY
                                            )

                                            if (info != null) {
                                                context.startActivity(intent)
                                                return true
                                            }

                                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                            if (!fallbackUrl.isNullOrEmpty()) {
                                                view.loadUrl(fallbackUrl)
                                                return true
                                            }

                                            val packagename = intent.`package`
                                            if (!packagename.isNullOrEmpty()) {
                                                val marketIntent = Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse("market://details?id=$packagename")
                                                )
                                                marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(marketIntent)
                                                return true
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Log error
                                    }
                                    return true
                                }

                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    return try {
                                        val intent = Intent(Intent.ACTION_VIEW, uri)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                        true
                                    } catch (e: Exception) {
                                        true
                                    }
                                }

                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, uri)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                                        val packageManager = context.packageManager
                                        val resolveInfo =
                                            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

                                        if (resolveInfo != null) {
                                            val packageName = resolveInfo.activityInfo.packageName

                                            val isBrowser =
                                                packageName == "com.android.chrome" || packageName == "com.google.android.browser"
                                            val isMyPackage = packageName == context.packageName

                                            if (!isBrowser && !isMyPackage) {
                                                context.startActivity(intent)
                                                return true
                                            }
                                        }
                                    } catch (e: Exception) {
                                    }
                                }

                                return false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (isAdBlockEnabled) {
                                    val requestUrl = request?.url?.toString()?.lowercase()
                                    if (requestUrl != null && adBlockKeywords.any { requestUrl.contains(it) }) {
                                        return WebResourceResponse(
                                            "text/plain",
                                            "UTF-8",
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }

                            override fun onReceivedTitle(view: WebView?, webTitle: String?) {
                                title = webTitle ?: "Web View"
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                onRelease = { view -> view.destroy() },
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(animationSpec = tween(300)) + expandVertically(),
                exit = fadeOut(animationSpec = tween(500)) + shrinkVertically(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
                dragHandle = { BottomSheetDefaults.DragHandle() },
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 48.dp)
                ) {
                    Text(
                        text = "Options",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            NavigationItem(
                                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                label = "Back",
                                enabled = canGoBack,
                                onClick = { webView?.goBack() }
                            )
                            NavigationItem(
                                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                                label = "Forward",
                                enabled = canGoForward,
                                onClick = { webView?.goForward() }
                            )
                            NavigationItem(
                                icon = Icons.Rounded.Refresh,
                                label = "Refresh",
                                onClick = {
                                    webView?.reload()
                                    dismissBottomSheet()
                                }
                            )
                        }
                    }

                    SectionHeader("Actions")

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ActionItem(Icons.Rounded.ContentCopy, "Copy") {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("URL", currentUrl))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                dismissBottomSheet()
                            }
                            ActionItem(Icons.Rounded.Share, "Share") {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share link via"))
                                dismissBottomSheet()
                            }
                            ActionItem(Icons.AutoMirrored.Rounded.OpenInNew, "Browser") {
                                uriHandler.openUri(currentUrl)
                                dismissBottomSheet()
                            }
                            ActionItem(Icons.Rounded.Search, "Find") {
                                dismissBottomSheet()
                                showFindInPage = true
                            }
                        }
                    }

                    SectionHeader("Settings")

                    SettingsSwitchTile(
                        icon = Icons.Rounded.DesktopWindows,
                        title = "Desktop Site",
                        checked = isDesktopMode,
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = ItemPosition.TOP,
                        onCheckedChange = { isDesktopMode = it }
                    )

                    SettingsSwitchTile(
                        icon = Icons.Rounded.Block,
                        title = "Block Ads",
                        checked = isAdBlockEnabled,
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = ItemPosition.MIDDLE,
                        onCheckedChange = {
                            isAdBlockEnabled = it
                            webView?.reload()
                        }
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(
                            bottomStart = 24.dp,
                            bottomEnd = 24.dp,
                            topStart = 4.dp,
                            topEnd = 4.dp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary.copy(0.15f),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.TextFormat, null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    "Text Size: $textZoom%",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 18.sp
                                )
                            }
                            Slider(
                                value = textZoom.toFloat(),
                                onValueChange = { textZoom = it.toInt() },
                                valueRange = 50f..200f,
                                steps = 14,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CertificateInfoItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun NavigationItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "NavAlpha")
    val scale by animateFloatAsState(if (enabled) 1f else 0.9f, label = "NavScale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .alpha(alpha)
            .scale(scale)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}