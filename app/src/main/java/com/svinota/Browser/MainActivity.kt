package com.svinota.Browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
// Явный импорт ресурсов вашего проекта
import com.svinota.Browser.R

// Шрифты
val LogoFont = FontFamily(Font(R.font.comic_neue_bold, FontWeight.Bold))

// Модель данных вкладки
data class Tab(
    val id: Long = System.currentTimeMillis(),
    var url: MutableState<String> = mutableStateOf(""),
    var webView: WebView? = null
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

    var tabs by remember { mutableStateOf(listOf(Tab(url = mutableStateOf(initialUrl)))) }
    var activeTabId by remember { mutableLongStateOf(tabs.first().id) }

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
                }
            }
            activeTab.url.value.isNotEmpty() -> {
                activeTab.url.value = ""
                inputUrl = ""
            }
            tabs.size > 1 -> {
                val currentSize = tabs.size
                tabs = tabs.filter { it.id != activeTabId }
                activeTabId = tabs.last().id
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
                                }) {
                                    Image(painter = painterResource(id = R.drawable.icon), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                                    Text("SSNDash", fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                                }
                            }
                            items(bookmarks) { url ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { activeTab.url.value = url }) {
                                    Surface(Modifier.size(56.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Text(url.removePrefix("https://").removePrefix("http://").split("/")[0], fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                                }
                            }
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        if (url != null && !url.startsWith("data:") && url != "about:blank") {
                                            activeTab.url.value = url
                                        }
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        if (url != null && !url.startsWith("data:") && url != "about:blank") {
                                            activeTab.url.value = url
                                        }
                                    }
                                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                        val names = listOf("tvrshk", "KS51", "guganator3000", "Letexe", "michael dodo pizza", "Timofya453")
                                        val bgHex = String.format("#%06X", (0xFFFFFF and bgColor.toArgb()))
                                        val textHex = String.format("#%06X", (0xFFFFFF and textColor.toArgb()))
                                        val errorTitle = if (currentLang == "ru") "Ошибка!" else "Error!"
                                        val errorMsg = if (currentLang == "ru") "не смог найти этот сайт." else "couldn't find this site."
                                        val retryBtn = if (currentLang == "ru") "Попробовать снова" else "Try again"

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
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isDark)
                                }
                                activeTab.webView = this
                                loadUrl(activeTab.url.value)
                            }
                        },
                        update = { webView ->
                            if (webView.url != activeTab.url.value) {
                                webView.loadUrl(activeTab.url.value)
                            }
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDark)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(28.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 8.dp
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                    history = (history + formatted).takeLast(50).distinct()
                                    dataPrefs.edit().putStringSet("history", history.toSet()).apply()
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
                        IconButton(onClick = { showMenuSheet = true }) { Icon(Icons.Default.Menu, null) }
                    }
                }
            }
        }
    }

    if (showMenuSheet) {
        ModalBottomSheet(onDismissRequest = { showMenuSheet = false; currentMenuPage = "main" }) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp, top = 8.dp)) {
                when(currentMenuPage) {
                    "main" -> {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            MenuIconBtn(Icons.Default.List, "History") { currentMenuPage = "history" }
                            MenuIconBtn(Icons.Default.Star, "Bookmarks") { currentMenuPage = "bookmarks" }
                            MenuIconBtn(Icons.Default.KeyboardArrowDown, "Downloads") {
                                context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Surface(onClick = { showMenuSheet = false; showFullSettings = true }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, null)
                                Spacer(Modifier.width(12.dp))
                                Text("Settings", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    "history" -> {
                        Text("History", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(history.reversed()) { url ->
                                ListItem(headlineContent = { Text(url, maxLines = 1) }, modifier = Modifier.clickable {
                                    activeTab.url.value = url
                                    showMenuSheet = false
                                })
                            }
                        }
                    }
                    "bookmarks" -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Bookmarks", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                            IconButton(onClick = { showAddBookmarkDialog = true }) { Icon(Icons.Default.Add, null) }
                        }
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            item {
                                ListItem(headlineContent = { Text("SSNDash") }, supportingContent = { Text("https://ssndash.ru") }, modifier = Modifier.clickable {
                                    activeTab.url.value = "https://ssndash.ru"
                                    showMenuSheet = false
                                })
                            }
                            items(bookmarks) { url ->
                                ListItem(
                                    headlineContent = { Text(url.removePrefix("https://").removePrefix("http://").split("/")[0], maxLines = 1) },
                                    supportingContent = { Text(url, maxLines = 1) },
                                    trailingContent = {
                                        IconButton(onClick = {
                                            bookmarks = bookmarks - url
                                            dataPrefs.edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
                                        }) { Icon(Icons.Default.Delete, null) }
                                    },
                                    modifier = Modifier.clickable {
                                        activeTab.url.value = url
                                        showMenuSheet = false
                                    }
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
            Column(Modifier.padding(16.dp)) {
                Button(onClick = {
                    val n = Tab(); tabs = tabs + n; activeTabId = n.id; showTabsSheet = false
                }, modifier = Modifier.fillMaxWidth()) { Text(t("+ New Tab", "+ Новая вкладка", currentLang)) }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(tabs) { t ->
                        ListItem(
                            modifier = Modifier.clickable { activeTabId = t.id; showTabsSheet = false },
                            headlineContent = { Text(if(t.url.value.isEmpty()) t("New Tab", "Новая вкладка", currentLang) else t.url.value, maxLines = 1) },
                            trailingContent = { IconButton(onClick = { if(tabs.size > 1) { tabs = tabs.filter { it.id != t.id }; if(activeTabId == t.id) activeTabId = tabs.last().id } }) { Icon(Icons.Default.Close, null) } }
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
                        val formatted = if (newBookmarkUrl.startsWith("http")) newBookmarkUrl else "https://$newBookmarkUrl"
                        bookmarks = (bookmarks + formatted).distinct()
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(8.dp)) {
        Icon(icon, null)
        Text(label, fontSize = 12.sp)
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
    val themeLabels = if (currentLang == "ru") listOf("Светлая", "Светлая (Material)", "Тёмная", "Тёмная (Material)") else listOf("Light", "Light (Material)", "Dark", "Dark (Material)")
    val langLabels = mapOf("en" to "English", "ru" to "Русский")

    var themeExpanded by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }
    var showAboutPage by remember { mutableStateOf(false) }

    if (showAboutPage) {
        Scaffold(topBar = { TopAppBar(title = { Text("About") }, navigationIcon = { IconButton(onClick = { showAboutPage = false }) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(80.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.icon),
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("svinotaBrowser", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("Version: alpha-4", fontSize = 14.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Text("By SSNDash Team", textAlign = TextAlign.Center, fontSize = 14.sp)

                Spacer(Modifier.height(32.dp))
                Text("License", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                Spacer(Modifier.height(8.dp))
                Text(
                    "This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License version 3.0 or any later version, published by the Free Software Foundation.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Start
                )
            }
        }
    } else {
        Scaffold(topBar = { TopAppBar(title = { Text(t("Settings", "Настройки", currentLang)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding ->
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                Text(t("Search Engine", "Поисковая система", currentLang), Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                listOf(
                    "DuckDuckGo" to "https://duckduckgo.com/?q=",
                    "Google" to "https://google.com/search?q=",
                    "Yandex" to "https://yandex.ru/search/?text=",
                    "Perplexity" to "https://www.perplexity.ai/?q="
                ).forEach { (name, url) ->
                    ListItem(headlineContent = { Text(name) }, leadingContent = { RadioButton(currentSearchEngine == url, onClick = { onSearchEngineChange(url) }) }, modifier = Modifier.clickable { onSearchEngineChange(url) })
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
                    headlineContent = { Text("About") },
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    modifier = Modifier.clickable { showAboutPage = true }
                )
            }
        }
    }
}