package com.svinota.Browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.svinota.Browser.R

// Шрифты
val LogoFont = FontFamily(Font(R.font.comic_neue_bold, FontWeight.Bold))

// Модель данных вкладки
data class Tab(
    val id: Long = System.nanoTime(), // Используем наносекунды, чтобы ID гарантированно не дублировались при быстром создании
    var url: MutableState<String> = mutableStateOf(""),
    var webView: WebView? = null,
    var favicon: MutableState<Bitmap?> = mutableStateOf(null)
)

@Composable
fun t(en: String, ru: String, forcedLang: String? = null): String {
    val locale = forcedLang ?: Locale.getDefault().language
    return if (locale == "ru") ru else en
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sentUrl = if (intent?.action == Intent.ACTION_VIEW) intent?.dataString ?: "" else ""

        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("svinota_prefs", Context.MODE_PRIVATE) }

            var themeMode by remember { mutableIntStateOf(prefs.getInt("theme_mode", 0)) }
            var searchEngine by remember { mutableStateOf(prefs.getString("search_engine", "https://duckduckgo.com/?q=") ?: "https://duckduckgo.com/?q=") }
            var appLang by remember { mutableStateOf(prefs.getString("app_lang", Locale.getDefault().language) ?: "en") }

            val useDynamicColors = (themeMode == 1 || themeMode == 3) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val isDark = themeMode >= 2

            val colorScheme = when {
                useDynamicColors && isDark -> dynamicDarkColorScheme(context)
                useDynamicColors && !isDark -> dynamicLightColorScheme(context)
                isDark -> darkColorScheme()
                else -> lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                SvinotaBrowser(
                    initialUrl = sentUrl,
                    themeMode = themeMode,
                    currentSearchEngine = searchEngine,
                    currentLang = appLang,
                    onThemeChange = { mode -> themeMode = mode; prefs.edit().putInt("theme_mode", mode).apply() },
                    onSearchEngineChange = { searchEngine = it; prefs.edit().putString("search_engine", it).apply() },
                    onLangChange = { lang -> appLang = lang; prefs.edit().putString("app_lang", lang).apply() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SvinotaBrowser(
    initialUrl: String,
    themeMode: Int,
    currentSearchEngine: String,
    currentLang: String,
    onThemeChange: (Int) -> Unit,
    onSearchEngineChange: (String) -> Unit,
    onLangChange: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val dataPrefs = remember { context.getSharedPreferences("browser_data", Context.MODE_PRIVATE) }

    // Надежное восстановление вкладок через JSON (исключает проблему дубликатов Set и ограничения в 2 вкладки)
    var tabs by remember {
        mutableStateOf(
            run {
                val savedJson = dataPrefs.getString("opened_tabs_json", null)
                val restoredList = mutableListOf<Tab>()
                if (!savedJson.isNullOrEmpty()) {
                    try {
                        val jsonArray = JSONArray(savedJson)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val id = obj.optLong("id", System.nanoTime() + i)
                            val url = obj.optString("url", "")
                            restoredList.add(Tab(id = id, url = mutableStateOf(url)))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (restoredList.isEmpty()) {
                    listOf(Tab(url = mutableStateOf(initialUrl)))
                } else {
                    restoredList
                }
            }
        )
    }

    var activeTabId by remember {
        mutableLongStateOf(
            dataPrefs.getLong("active_tab_id", tabs.first().id).let { savedId ->
                if (tabs.any { it.id == savedId }) savedId else tabs.first().id
            }
        )
    }

    // Лямбда сохранения состояния через JSON (сохраняет точное количество, даже если URL одинаковые)
    val saveTabsState = {
        try {
            val jsonArray = JSONArray()
            tabs.forEach { tab ->
                val obj = JSONObject()
                obj.put("id", tab.id)
                obj.put("url", tab.url.value)
                jsonArray.put(obj)
            }
            dataPrefs.edit()
                .putString("opened_tabs_json", jsonArray.toString())
                .putLong("active_tab_id", activeTabId)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var history by remember { mutableStateOf(dataPrefs.getStringSet("history", emptySet())?.toList() ?: emptyList()) }
    var bookmarks by remember { mutableStateOf(dataPrefs.getStringSet("bookmarks", emptySet())?.toList() ?: emptyList()) }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.first()
    var inputUrl by remember(activeTabId, activeTab.url.value) { mutableStateOf(activeTab.url.value) }

    var showTabsSheet by remember { mutableStateOf(false) }
    var showMenuSheet by remember { mutableStateOf(false) }
    var showFullSettings by remember { mutableStateOf(false) }
    var currentMenuPage by remember { mutableStateOf("main") }

    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var newBookmarkName by remember { mutableStateOf("") }
    var newBookmarkUrl by remember { mutableStateOf("") }

    val isDark = themeMode >= 2
    val bgColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface
    val errorTitle = t("Error!", "Ошибка!", currentLang)
    val errorMsg = t("couldn't find this site.", "не смог найти этот сайт.", currentLang)
    val retryBtn = t("Try again", "Попробовать снова", currentLang)

    BackHandler(enabled = true) {
        when {
            currentMenuPage != "main" -> currentMenuPage = "main"
            showFullSettings -> showFullSettings = false
            showMenuSheet -> showMenuSheet = false
            showTabsSheet -> showTabsSheet = false
            activeTab.webView?.canGoBack() == true -> {
                activeTab.webView?.goBack()
                val backUrl = activeTab.webView?.copyBackForwardList()?.currentItem?.url
                if (backUrl != null) {
                    activeTab.url.value = backUrl
                    inputUrl = backUrl
                    saveTabsState()
                }
            }
            activeTab.url.value.isNotEmpty() -> {
                activeTab.url.value = ""
                inputUrl = ""
                saveTabsState()
            }
            tabs.size > 1 -> {
                val tabToRemove = activeTab
                tabs = tabs.filter { it.id != activeTabId }
                activeTabId = tabs.last().id
                tabToRemove.webView?.let {
                    it.loadUrl("about:blank")
                    it.stopLoading()
                    it.destroy()
                }
                saveTabsState()
            }
            else -> { (context as? ComponentActivity)?.finish() }
        }
    }

    if (showFullSettings) {
        SettingsScreen(themeMode, currentSearchEngine, currentLang, onThemeChange, onSearchEngineChange, onLangChange) { showFullSettings = false }
    } else {
        Scaffold { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (activeTab.url.value.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painter = painterResource(id = R.drawable.icon), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                            Spacer(Modifier.width(12.dp))
                            Text("svinotaBrowser", fontSize = 28.sp, fontFamily = LogoFont)
                        }
                        Spacer(Modifier.height(40.dp))
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                    activeTab.url.value = "https://ssndash.ru"
                                    saveTabsState()
                                }) {
                                    // Обновленный ресурс иконки ssndash
                                    Image(painter = painterResource(id = R.drawable.ssndash), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                                    Text("SSNDash", fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                                }
                            }
                            items(bookmarks) { bookmarkData ->
                                val parts = bookmarkData.split("|||")
                                val bName = parts.getOrNull(0) ?: ""
                                val bUrl = parts.getOrNull(1) ?: bookmarkData
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                    activeTab.url.value = bUrl
                                    saveTabsState()
                                }) {
                                    Surface(Modifier.size(56.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Text(bName.ifBlank { bUrl.removePrefix("https://").removePrefix("http://").split("/")[0] }, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                                }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        tabs.forEach { tab ->
                            val isCurrent = tab.id == activeTabId

                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                if (url != null && !url.startsWith("data:") && url != "about:blank") {
                                                    tab.url.value = url
                                                    saveTabsState()
                                                }
                                            }
                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                if (url != null && !url.startsWith("data:") && url != "about:blank") {
                                                    tab.url.value = url
                                                    saveTabsState()
                                                    val currentHistory = dataPrefs.getStringSet("history", emptySet())?.toList() ?: emptyList()
                                                    if (!currentHistory.contains(url)) {
                                                        val updatedHistory = (currentHistory + url).takeLast(50).distinct()
                                                        history = updatedHistory
                                                        dataPrefs.edit().putStringSet("history", updatedHistory.toSet()).apply()
                                                    }
                                                }
                                            }
                                            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                                val names = listOf("tvrshk", "KS51", "guganator3000", "Letexe", "michael dodo pizza", "Timofya453")
                                                val bgHex = String.format("#%06X", (0xFFFFFF and bgColor.toArgb()))
                                                val textHex = String.format("#%06X", (0xFFFFFF and textColor.toArgb()))

                                                val errorHtml = """
                                                    <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0"><style>
                                                        body { background-color: $bgHex; color: $textHex; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; font-family: sans-serif; text-align: center; margin: 0; }
                                                        h1 { color: #0077ff; }
                                                        button { padding: 12px 24px; border-radius: 20px; border: none; background: #0077ff; color: white; font-weight: bold; margin-top: 20px; }
                                                    </style></head><body>
                                                        <h1>$errorTitle</h1>
                                                        <p>${names.random()} $errorMsg</p>
                                                        <button onclick="location.reload()">$retryBtn</button>
                                                    </body></html>
                                                """.trimIndent()
                                                view?.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                                            }
                                        }

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                                                super.onReceivedIcon(view, icon)
                                                if (icon != null) {
                                                    tab.favicon.value = icon
                                                }
                                            }
                                        }

                                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                                            try {
                                                val request = DownloadManager.Request(Uri.parse(url))
                                                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                                request.setMimeType(mimetype)
                                                request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                                                request.addRequestHeader("User-Agent", userAgent)
                                                request.setTitle(fileName)
                                                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                                                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                                                Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show()
                                            }
                                        }

                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                                        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isDark)
                                        }
                                        tab.webView = this
                                        if (tab.url.value.isNotEmpty()) {
                                            loadUrl(tab.url.value)
                                        }
                                    }
                                },
                                update = { webView ->
                                    if (tab.url.value.isNotEmpty() && webView.url != tab.url.value) {
                                        webView.loadUrl(tab.url.value)
                                    }
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDark)
                                    }
                                    if (isCurrent) {
                                        webView.requestFocus()
                                    } else {
                                        webView.clearFocus()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(if (isCurrent) 1f else 0.01f)
                            )
                        }
                    }
                }

                // НИЖНЯЯ ПАНЕЛЬ И ОТДЕЛЬНАЯ КНОПКА МЕНЮ
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(45.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 8.dp
                    ) {
                        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = inputUrl, onValueChange = { inputUrl = it }, modifier = Modifier.weight(1f),
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                trailingIcon = {
                                    if (activeTab.url.value.isNotEmpty()) {
                                        IconButton(onClick = { activeTab.webView?.reload() }) {
                                            Icon(Icons.Default.Refresh, null)
                                        }
                                    }
                                },
                                placeholder = { Text(t("Search...", "Поиск...", currentLang)) },
                                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (inputUrl.isNotBlank()) {
                                        val trimmed = inputUrl.trim()
                                        val formatted = if (trimmed.matches("^(https?://)?([\\w-]+\\.)+[\\w-]{2,}(/.*)?$".toRegex()) && !trimmed.contains(" ")) {
                                            if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
                                        } else { "$currentSearchEngine$trimmed" }
                                        activeTab.url.value = formatted
                                        saveTabsState()
                                        val currentHistory = dataPrefs.getStringSet("history", emptySet())?.toList() ?: emptyList()
                                        val updatedHistory = (currentHistory + formatted).takeLast(50).distinct()
                                        history = updatedHistory
                                        dataPrefs.edit().putStringSet("history", updatedHistory.toSet()).apply()
                                        focusManager.clearFocus()
                                    }
                                }),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                            )
                            IconButton(onClick = { showTabsSheet = true }) {
                                Box(Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                    Text(tabs.size.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Surface(
                        onClick = { showMenuSheet = true },
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Menu, null)
                        }
                    }
                }
            }
        }
    }

    if (showMenuSheet) {
        ModalBottomSheet(onDismissRequest = { showMenuSheet = false; currentMenuPage = "main" }) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp, top = 0.dp)) {
                when(currentMenuPage) {
                    "main" -> {
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.weight(1.0f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                MenuIconBtn(Icons.Default.List, t("History", "История", currentLang)) { currentMenuPage = "history" }
                            }
                            Box(modifier = Modifier.weight(1.0f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                MenuIconBtn(Icons.Default.Star, t("Bookmarks", "Закладки", currentLang)) { currentMenuPage = "bookmarks" }
                            }
                            Box(modifier = Modifier.weight(1.0f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                MenuIconBtn(Icons.Default.KeyboardArrowDown, t("Downloads", "Загрузки", currentLang)) {
                                    context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(onClick = { showMenuSheet = false; showFullSettings = true }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, null)
                                Spacer(Modifier.width(12.dp))
                                Text(t("Settings", "Настройки", currentLang), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    "history" -> {
                        Text(t("History", "История", currentLang), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(history.reversed()) { url ->
                                ListItem(headlineContent = { Text(url, maxLines = 1) }, modifier = Modifier.clickable {
                                    activeTab.url.value = url
                                    saveTabsState()
                                    showMenuSheet = false
                                })
                            }
                        }
                    }
                    "bookmarks" -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t("Bookmarks", "Закладки", currentLang), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                            IconButton(onClick = { showAddBookmarkDialog = true }) { Icon(Icons.Default.Add, null) }
                        }
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            item {
                                ListItem(
                                    headlineContent = { Text("SSNDash") },
                                    supportingContent = { Text("https://ssndash.ru") },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            activeTab.url.value = "https://ssndash.ru"
                                            saveTabsState()
                                            showMenuSheet = false
                                        },
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    )
                                )
                            }
                            items(bookmarks) { bookmarkData ->
                                val parts = bookmarkData.split("|||")
                                val bName = parts.getOrNull(0) ?: ""
                                val bUrl = parts.getOrNull(1) ?: bookmarkData
                                ListItem(
                                    headlineContent = { Text(bName.ifBlank { bUrl.removePrefix("https://").removePrefix("http://").split("/")[0] }, maxLines = 1) },
                                    supportingContent = { Text(bUrl, maxLines = 1) },
                                    trailingContent = {
                                        IconButton(onClick = {
                                            bookmarks = bookmarks - bookmarkData
                                            dataPrefs.edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
                                        }) { Icon(Icons.Default.Delete, null) }
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            activeTab.url.value = bUrl
                                            saveTabsState()
                                            showMenuSheet = false
                                        },
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTabsSheet) {
        ModalBottomSheet(onDismissRequest = { showTabsSheet = false }) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 0.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val n = Tab()
                            tabs = tabs + n
                            activeTabId = n.id
                            saveTabsState()
                            showTabsSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(t("+ New Tab", "+ Новая вкладка", currentLang))
                    }

                    IconButton(
                        onClick = {
                            // Безопасное закрытие всех WebView перед очисткой списка
                            tabs.forEach { it.webView?.destroy() }
                            val n = Tab()
                            tabs = listOf(n)
                            activeTabId = n.id
                            saveTabsState()
                            showTabsSheet = false
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Close all tabs")
                    }
                }

                Spacer(Modifier.height(16.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(tabs, key = { it.id }) { t ->
                        val isCurrentTab = t.id == activeTabId
                        val siteFavicon = t.favicon.value

                        ListItem(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    width = if (isCurrentTab) 2.dp else 0.dp,
                                    color = if (isCurrentTab) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    activeTabId = t.id
                                    saveTabsState()
                                    showTabsSheet = false
                                },
                            leadingContent = {
                                if (siteFavicon != null) {
                                    Image(
                                        bitmap = siteFavicon.asImageBitmap(),
                                        contentDescription = "Favicon",
                                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
                                    )
                                } else {
                                    Icon(Icons.Default.Menu, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            headlineContent = {
                                Text(
                                    text = if(t.url.value.isEmpty()) t("New Tab", "Новая вкладка", currentLang) else t.url.value.removePrefix("https://").removePrefix("http://").split("/")[0],
                                    maxLines = 1,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            supportingContent = {
                                if(t.url.value.isNotEmpty()) {
                                    Text(t.url.value, maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    val currentTabs = tabs
                                    if(currentTabs.size > 1) {
                                        // Безопасный перевод фокуса ДО удаления элемента
                                        if(activeTabId == t.id) {
                                            val remaining = currentTabs.filter { it.id != t.id }
                                            activeTabId = remaining.last().id
                                        }
                                        tabs = currentTabs.filter { it.id != t.id }
                                        t.webView?.let {
                                            it.loadUrl("about:blank")
                                            it.stopLoading()
                                            it.destroy()
                                        }
                                    } else {
                                        t.url.value = ""
                                        t.favicon.value = null
                                        t.webView?.loadUrl("about:blank")
                                    }
                                    saveTabsState()
                                }) {
                                    Icon(Icons.Default.Close, null)
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isCurrentTab) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        }
    }

    if (showAddBookmarkDialog) {
        AlertDialog(
            onDismissRequest = { showAddBookmarkDialog = false },
            title = { Text(t("Add Bookmark", "Новая закладка", currentLang)) },
            text = {
                Column {
                    OutlinedTextField(value = newBookmarkName, onValueChange = { newBookmarkName = it }, label = { Text(t("Name", "Название", currentLang)) }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newBookmarkUrl, onValueChange = { newBookmarkUrl = it }, label = { Text("URL") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newBookmarkUrl.isNotBlank()) {
                        val formattedUrl = if (newBookmarkUrl.startsWith("http")) newBookmarkUrl else "https://$newBookmarkUrl"
                        val compositeValue = "${newBookmarkName.trim()}|||$formattedUrl"
                        bookmarks = (bookmarks + compositeValue).distinct()
                        dataPrefs.edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
                        newBookmarkName = ""; newBookmarkUrl = ""; showAddBookmarkDialog = false
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showAddBookmarkDialog = false }) { Text(t("Cancel", "Отмена", currentLang)) } }
        )
    }
}

@Composable
fun MenuIconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.clickable { onClick() }.padding(12.dp).fillMaxWidth()
    ) {
        Icon(icon, null)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: Int,
    currentSearchEngine: String,
    currentLang: String,
    onThemeChange: (Int) -> Unit,
    onSearchEngineChange: (String) -> Unit,
    onLangChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("svinota_prefs", Context.MODE_PRIVATE) }
    var customSearchUrl by remember { mutableStateOf(prefs.getString("custom_search", "https://") ?: "https://") }
    var showCustomDialog by remember { mutableStateOf(false) }

    val themeLabels = if (currentLang == "ru") listOf("Светлая", "Светлая (Material)", "Тёмная", "Тёмная (Material)") else listOf("Light", "Light (Material)", "Dark", "Dark (Material)")
    val langLabels = mapOf("en" to "English", "ru" to "Русский")

    var themeExpanded by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }
    var showAboutPage by remember { mutableStateOf(false) }

    if (showAboutPage) {
        Scaffold(topBar = { TopAppBar(title = { Text(t("About", "О программе", currentLang)) }, navigationIcon = { IconButton(onClick = { showAboutPage = false }) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(22.dp))
                )
                Spacer(Modifier.height(16.dp))
                Text("svinotaBrowser", fontSize = 24.sp, fontFamily = LogoFont)
                // Обновлено до версии alpha-8
                Text("Version: alpha-8", fontSize = 14.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Text(t("By SSNDash Team", "Разработчик: SSNDash Team", currentLang), textAlign = TextAlign.Center, fontSize = 14.sp)

                Spacer(Modifier.height(32.dp))
                Text(t("License", "Лицензия", currentLang), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                Spacer(Modifier.height(8.dp))
                Text(
                    t(
                        "This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License version 3.0 or any later version, published by the Free Software Foundation.",
                        "Это свободное программное обеспечение: вы можете распространять и/или изменять его в соответствии с условиями Стандартной общественной лицензии GNU Affero версии 3.0 или более поздней версии, опубликованной Фондом свободного программного обеспечения.",
                        currentLang
                    ),
                    fontSize = 12.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Start
                )
            }
        }
    } else {
        Scaffold(topBar = { TopAppBar(title = { Text(t("Settings", "Настройки", currentLang)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding ->
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t("Search Engine", "Поисковая система", currentLang), Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showCustomDialog = true }) { Icon(Icons.Default.Add, null) }
                }

                val engines = mutableListOf(
                    "DuckDuckGo" to "https://duckduckgo.com/?q=",
                    "Google" to "https://google.com/search?q=",
                    "Yandex" to "https://yandex.ru/search/?text=",
                    "Perplexity" to "https://www.perplexity.ai/?q="
                )

                if (customSearchUrl != "https://" && customSearchUrl.isNotBlank()) {
                    engines.add("Custom" to customSearchUrl)
                }

                engines.forEach { (name, url) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = if(name == "Custom") { { Text(url, maxLines = 1) } } else null,
                        leadingContent = { RadioButton(currentSearchEngine == url, onClick = { onSearchEngineChange(url) }) },
                        modifier = Modifier.clickable { onSearchEngineChange(url) }
                    )
                }
                HorizontalDivider()

                Text(t("Appearance", "Внешний вид", currentLang), Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                Box(Modifier.padding(horizontal = 16.dp)) {
                    ExposedDropdownMenuBox(expanded = themeExpanded, onExpandedChange = { themeExpanded = !themeExpanded }) {
                        OutlinedTextField(
                            value = themeLabels[themeMode], onValueChange = {}, readOnly = true,
                            label = { Text(t("Theme", "Тема", currentLang)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = themeExpanded, onDismissRequest = { themeExpanded = false }) {
                            themeLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { onThemeChange(index); themeExpanded = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.padding(horizontal = 16.dp)) {
                    ExposedDropdownMenuBox(expanded = langExpanded, onExpandedChange = { langExpanded = !langExpanded }) {
                        OutlinedTextField(
                            value = langLabels[currentLang] ?: "English", onValueChange = {}, readOnly = true,
                            label = { Text(t("Language", "Язык", currentLang)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                            langLabels.forEach { (code, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { onLangChange(code); langExpanded = false })
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 16.dp))

                ListItem(
                    headlineContent = { Text(t("About", "О программе", currentLang)) },
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    modifier = Modifier.clickable { showAboutPage = true }
                )
            }
        }
    }

    if (showCustomDialog) {
        var tempUrl by remember { mutableStateOf(customSearchUrl) }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text(t("Custom Search Engine", "Свой поисковик", currentLang)) },
            text = {
                Column {
                    Text(t("Enter URL with query parameter:", "Введите URL с параметром запроса:", currentLang), fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        placeholder = { Text("https://example.com/?q=") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    customSearchUrl = tempUrl
                    prefs.edit().putString("custom_search", tempUrl).apply()
                    onSearchEngineChange(tempUrl)
                    showCustomDialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showCustomDialog = false }) { Text(t("Cancel", "Отмена", currentLang)) } }
        )
    }
}