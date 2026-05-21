package com.svinota.Browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.svinota.Browser.R

val LogoFont = FontFamily(Font(R.font.comic_neue_bold, FontWeight.Bold))

data class Tab(
    val id: Long = System.nanoTime(),
    var url: MutableState<String> = mutableStateOf(""),
    var webView: WebView? = null,
    var favicon: MutableState<Bitmap?> = mutableStateOf(null),
    var isDesktopMode: MutableState<Boolean> = mutableStateOf(false)
)

@Composable
fun t(en: String, ru: String, forcedLang: String? = null): String {
    val locale = forcedLang ?: Locale.getDefault().language
    return if (locale == "ru") ru else en
}

@Composable
fun MenuIconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clickable { onClick() }
            .padding(12.dp)
            .fillMaxWidth()
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
            var barPosition by remember { mutableIntStateOf(prefs.getInt("bar_position", 0)) } // 0 = Bottom, 1 = Top

            val useDynamicColors = (themeMode == 1 || themeMode == 3) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val isDark = themeMode >= 2

            val colorScheme = when {
                themeMode == 4 -> darkColorScheme(
                    primary = Color(0xFFB7B7B7),
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF121212),
                    onBackground = Color.White,
                    onSurface = Color.White,
                    onSurfaceVariant = Color(0xFFE0E0E0)
                )
                useDynamicColors && isDark -> dynamicDarkColorScheme(context)
                useDynamicColors && !isDark -> dynamicLightColorScheme(context)
                isDark -> darkColorScheme()
                else -> lightColorScheme()
            }

            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (context as Activity).window
                    window.statusBarColor = colorScheme.surface.toArgb()
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
                }
            }

            MaterialTheme(colorScheme = colorScheme) {
                SvinotaBrowser(
                    initialUrl = sentUrl,
                    themeMode = themeMode,
                    currentSearchEngine = searchEngine,
                    currentLang = appLang,
                    barPosition = barPosition,
                    onThemeChange = { mode -> themeMode = mode; prefs.edit().putInt("theme_mode", mode).apply() },
                    onSearchEngineChange = { searchEngine = it; prefs.edit().putString("search_engine", it).apply() },
                    onLangChange = { lang -> appLang = lang; prefs.edit().putString("app_lang", lang).apply() },
                    onBarPositionChange = { pos -> barPosition = pos; prefs.edit().putInt("bar_position", pos).apply() }
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
    barPosition: Int,
    onThemeChange: (Int) -> Unit,
    onSearchEngineChange: (String) -> Unit,
    onLangChange: (String) -> Unit,
    onBarPositionChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val dataPrefs = remember { context.getSharedPreferences("browser_data", Context.MODE_PRIVATE) }

    var wallpaperType by remember { mutableStateOf(dataPrefs.getString("wp_type", "default") ?: "default") }
    var wallpaperColor by remember { mutableIntStateOf(dataPrefs.getInt("wp_color", Color.Gray.toArgb())) }
    var wallpaperBase64 by remember { mutableStateOf(dataPrefs.getString("wp_base64", null)) }
    var showBookmarksHome by remember { mutableStateOf(dataPrefs.getBoolean("show_bookmarks_home", true)) }

    val wallpaperBitmap = remember(wallpaperBase64) {
        wallpaperBase64?.let {
            try {
                val decodedBytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } catch (e: Exception) { null }
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showColorPickerDialog by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                        val base64String = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)

                        wallpaperBase64 = base64String
                        wallpaperType = "image"
                        dataPrefs.edit()
                            .putString("wp_type", "image")
                            .putString("wp_base64", base64String)
                            .apply()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error loading image", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    // Логика скрытия навбара при скролле (только если он сверху)
    var isBarVisible by remember { mutableStateOf(true) }
    val barTranslationY by animateFloatAsState(
        targetValue = if (barPosition == 1 && !isBarVisible && activeTab.url.value.isNotEmpty()) -100f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "BarTranslation"
    )

    // Сбрасываем видимость бара при переключении табов или переходе на главную
    LaunchedEffect(activeTabId, activeTab.url.value) {
        isBarVisible = true
    }

    val isDark = themeMode >= 2
    val bgColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface
    val errorTitle = t("Error!", "Ошибка!", currentLang)
    val errorMsg = t("couldn't find this site.", "не смог найти этот сайт.", currentLang)
    val retryBtn = t("Try again", "Попробовать снова", currentLang)

    BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> { scope.launch { drawerState.close() } }
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

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerShape = RoundedCornerShape(topStart = 0.dp, bottomStart = 16.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                    drawerContainerColor = MaterialTheme.colorScheme.surface
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                            Text(t("Wallpaper Settings", "Настройки обоев", currentLang), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(24.dp))

                            val isDefaultSelected = wallpaperType == "default"
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Surface(
                                    onClick = {
                                        wallpaperType = "default"
                                        dataPrefs.edit().putString("wp_type", "default").apply()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(80.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (isDefaultSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(t("System Default", "По умолчанию", currentLang), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                            }

                            val isColorSelected = wallpaperType == "color"
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Surface(
                                    onClick = { showColorPickerDialog = true },
                                    modifier = Modifier.fillMaxWidth().height(80.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (isColorSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .then(
                                                    if (isColorSelected) {
                                                        Modifier.background(Color(wallpaperColor)).border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                                    } else {
                                                        Modifier.background(Brush.linearGradient(listOf(Color.Red, Color.Green, Color.Blue)))
                                                    }
                                                )
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(t("Color Palette", "Цветные обои", currentLang), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                            }

                            val isImageSelected = wallpaperType == "image"
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Surface(
                                    onClick = { imagePickerLauncher.launch("image/*") },
                                    modifier = Modifier.fillMaxWidth().height(100.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (isImageSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (isImageSelected && wallpaperBitmap != null) {
                                            Image(
                                                bitmap = wallpaperBitmap.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .blur(8.dp),
                                                contentScale = ContentScale.Crop
                                            )
                                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
                                            Icon(Icons.Default.Check, null, tint = Color.White)
                                        } else {
                                            Icon(Icons.Default.Add, null)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(t("Choose from Gallery", "Выбрать из галереи", currentLang), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                            }

                            HorizontalDivider(Modifier.padding(vertical = 12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(t("Show Bookmarks", "Показывать закладки", currentLang), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Switch(
                                    checked = showBookmarksHome,
                                    onCheckedChange = { checked ->
                                        showBookmarksHome = checked
                                        dataPrefs.edit().putBoolean("show_bookmarks_home", checked).apply()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (drawerState.isOpen) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                scope.launch { drawerState.close() }
                            }
                        } else Modifier
                    )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Scaffold { padding ->
                        Box(
                            Modifier
                                .padding(padding)
                                .fillMaxSize()
                                .background(
                                    if (activeTab.url.value.isEmpty()) {
                                        when (wallpaperType) {
                                            "color" -> Color(wallpaperColor)
                                            "image" -> Color.Transparent
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                )
                        ) {
                            if (activeTab.url.value.isEmpty() && wallpaperType == "image" && wallpaperBitmap != null) {
                                Image(
                                    bitmap = wallpaperBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }

                            // КОНТЕНТ СТРАНИЦЫ (Сайт или Главный экран)
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (activeTab.url.value.isEmpty()) {
                                    // Главная страница: если бар сверху, делаем отступ, чтобы логотип и закладки съехали ниже
                                    Column(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(24.dp)
                                            .padding(top = if (barPosition == 1) 80.dp else 0.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Image(painter = painterResource(id = R.drawable.icon), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                                                Spacer(Modifier.width(12.dp))
                                                Text(
                                                    "svinotaBrowser",
                                                    fontSize = 28.sp,
                                                    fontFamily = LogoFont,
                                                    color = if (wallpaperType != "default") Color.White else MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            IconButton(
                                                onClick = { scope.launch { drawerState.open() } },
                                                colors = IconButtonDefaults.iconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.0f),
                                                    contentColor = if (wallpaperType != "default") Color.White else MaterialTheme.colorScheme.primary
                                                )
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Wallpaper settings")
                                            }
                                        }
                                        Spacer(Modifier.height(40.dp))

                                        if (showBookmarksHome) {
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
                                                        Image(painter = painterResource(id = R.drawable.ssndash), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                                                        Text(
                                                            "SSNDash",
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(top = 4.dp),
                                                            maxLines = 1,
                                                            color = if (wallpaperType != "default") Color.White else MaterialTheme.colorScheme.onSurface
                                                        )
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
                                                        Text(
                                                            bName.ifBlank { bUrl.removePrefix("https://").removePrefix("http://").split("/")[0] },
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(top = 4.dp),
                                                            maxLines = 1,
                                                            color = if (wallpaperType != "default") Color.White else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
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

                                                        // Слушатель скролла для скрытия/показа верхнего навбара
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                                                                if (barPosition == 1) {
                                                                    if (scrollY > oldScrollY && scrollY > 20) {
                                                                        isBarVisible = false // Скролл вниз -> прячем
                                                                    } else if (scrollY < oldScrollY) {
                                                                        isBarVisible = true  // Скролл вверх -> показываем
                                                                    }
                                                                }
                                                            }
                                                        }

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
                            }

                            // НАВЕРХНУТЫЙ НАВБАР (Перекрывает всё поверх, без подложки самого ряда)
                            Row(
                                modifier = Modifier
                                    .align(if (barPosition == 1) Alignment.TopCenter else Alignment.BottomCenter)
                                    .graphicsLayer {
                                        // Применяем смещение по оси Y для анимации скрытия (умножаем на плотность пикселей)
                                        translationY = barTranslationY * density
                                    }
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
            }
        }
    }

    AnimatedVisibility(
        visible = showFullSettings,
        enter = slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it }),
        exit = slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { it })
    ) {
        SettingsScreen(
            themeMode = themeMode,
            currentSearchEngine = currentSearchEngine,
            currentLang = currentLang,
            barPosition = barPosition,
            onThemeChange = onThemeChange,
            onSearchEngineChange = onSearchEngineChange,
            onLangChange = onLangChange,
            onBarPositionChange = onBarPositionChange
        ) { showFullSettings = false }
    }

    if (showColorPickerDialog) {
        val colorsPreset = listOf(
            Color(0xFFF44336), Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
            Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF00BCD4), Color(0xFF009688),
            Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFCDDC39), Color(0xFFFFEB3B),
            Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFF607D8B)
        )

        AlertDialog(
            onDismissRequest = { showColorPickerDialog = false },
            title = { Text(t("Choose Background Color", "Выберите цвет фона", currentLang), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(colorsPreset) { color ->
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(color)
                                    .clickable {
                                        wallpaperType = "color"
                                        wallpaperColor = color.toArgb()
                                        dataPrefs.edit().putString("wp_type", "color").putInt("wp_color", color.toArgb()).apply()
                                        showColorPickerDialog = false
                                    }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showColorPickerDialog = false }) {
                    Text(t("Cancel", "Отмена", currentLang))
                }
            }
        )
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

                        if (activeTab.url.value.isNotEmpty()) {
                            Surface(
                                onClick = {
                                    activeTab.webView?.let { webView ->
                                        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                        if (!activeTab.isDesktopMode.value) {
                                            webView.settings.userAgentString = desktopUA
                                            webView.settings.useWideViewPort = true
                                            webView.settings.loadWithOverviewMode = true
                                            activeTab.isDesktopMode.value = true
                                        } else {
                                            webView.settings.userAgentString = null
                                            webView.settings.useWideViewPort = false
                                            webView.settings.loadWithOverviewMode = false
                                            activeTab.isDesktopMode.value = false
                                        }
                                        webView.reload()
                                    }
                                    showMenuSheet = false
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painter = painterResource(id = R.drawable.computer), null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = t("Desktop site", "Версия для ПК", currentLang),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        Surface(onClick = { showMenuSheet = false; showFullSettings = true }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: Int,
    currentSearchEngine: String,
    currentLang: String,
    barPosition: Int,
    onThemeChange: (Int) -> Unit,
    onSearchEngineChange: (String) -> Unit,
    onLangChange: (String) -> Unit,
    onBarPositionChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("svinota_prefs", Context.MODE_PRIVATE) }
    var customSearchUrl by remember { mutableStateOf(prefs.getString("custom_search", "https://") ?: "https://") }
    var showCustomDialog by remember { mutableStateOf(false) }

    val themeLabels = if (currentLang == "ru") {
        listOf("Светлая", "Светлая (Material)", "Тёмная", "Тёмная (Material)", "AMOLED")
    } else {
        listOf("Light", "Light (Material)", "Dark", "Dark (Material)", "AMOLED")
    }

    val barLabels = if (currentLang == "ru") {
        listOf("Снизу", "Сверху")
    } else {
        listOf("Bottom", "Top")
    }

    val langLabels = mapOf("en" to "English", "ru" to "Русский")

    var themeExpanded by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }
    var barExpanded by remember { mutableStateOf(false) }
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
                Text("Version: alpha-10", fontSize = 14.sp, color = Color.Gray)
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
                            value = themeLabels.getOrElse(themeMode) { themeLabels.last() }, onValueChange = {}, readOnly = true,
                            label = { Text(t("Theme", "Тема", currentLang)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) },
                            shape = RoundedCornerShape(12.dp),
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

                // ВЫБОР ПОЛОЖЕНИЯ НАВБАРA
                Box(Modifier.padding(horizontal = 16.dp)) {
                    ExposedDropdownMenuBox(expanded = barExpanded, onExpandedChange = { barExpanded = !barExpanded }) {
                        OutlinedTextField(
                            value = barLabels.getOrElse(barPosition) { barLabels.first() }, onValueChange = {}, readOnly = true,
                            label = { Text(t("Navigation bar position", "Положение нав. бара", currentLang)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = barExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = barExpanded, onDismissRequest = { barExpanded = false }) {
                            barLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { onBarPositionChange(index); barExpanded = false })
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
                            shape = RoundedCornerShape(12.dp),
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