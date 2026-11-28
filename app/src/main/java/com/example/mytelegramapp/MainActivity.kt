package com.example.mytelegramapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ChatItem(
    val id: Long,
    val title: String,
    val type: String,
    val lastMessage: String
)

data class ChatMessage(
    val id: Long,
    val sender: String,
    val text: String,
    val isMine: Boolean,
    val hasMedia: Boolean = false
)

data class UiConfig(
    val appTitle: String = "Advanced Telegram",
    val topBarColor: Color = Color(0xCC0F172A),
    val cardColor: Color = Color(0xB3111827),
    val mineBubbleColor: Color = Color(0xE61D4ED8),
    val otherBubbleColor: Color = Color(0xE61F2937),
    val homeWallpaperUrl: String = "",
    val chatWallpaperUrl: String = "",
    val refreshMs: Long = 1000L
)

private const val BRIDGE_BASE = "http://127.0.0.1:8765"

private fun parseColorSafe(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

private fun httpGet(path: String): String {
    val url = URL("$BRIDGE_BASE$path")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 5000
        readTimeout = 15000
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
    if (code !in 200..299) throw RuntimeException("HTTP $code: $body")
    return body
}

private fun httpPostJson(path: String, jsonBody: String): String {
    val url = URL("$BRIDGE_BASE$path")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 5000
        readTimeout = 90000
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(jsonBody)
        writer.flush()
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
    if (code !in 200..299) throw RuntimeException("HTTP $code: $body")
    return body
}

private suspend fun copyUriToCache(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val outDir = File(context.cacheDir, "media_out").apply { mkdirs() }
        val outFile = File(outDir, "upload_${System.currentTimeMillis()}.jpg")
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val orig = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
        if (orig != null) {
            val maxDim = 1280
            val scale = minOf(1f, maxDim.toFloat() / maxOf(orig.width, orig.height).coerceAtLeast(1))
            val targetW = (orig.width * scale).toInt().coerceAtLeast(1)
            val targetH = (orig.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(orig, targetW, targetH, true) else orig
            outFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            if (scaled !== orig) scaled.recycle()
            orig.recycle()
        } else {
            outFile.writeBytes(rawBytes)
        }
        if (outFile.exists() && outFile.length() > 0L) outFile.absolutePath else null
    } catch (_: Exception) {
        null
    }
}

private suspend fun loadRemoteBitmap(context: Context, imageUrl: String, cacheName: String): Bitmap? =
    withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        val cacheFile = File(context.cacheDir, cacheName)
        try {
            val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 8000
            }
            if (conn.responseCode in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    cacheFile.writeBytes(bytes)
                    return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (_: Exception) {
        }
        if (cacheFile.exists()) BitmapFactory.decodeFile(cacheFile.absolutePath) else null
    }

private suspend fun fetchUiConfig(): UiConfig = withContext(Dispatchers.IO) {
    val raw = httpGet("/ui")
    val obj = JSONObject(raw)
    val def = UiConfig()
    UiConfig(
        appTitle = obj.optString("appTitle", def.appTitle),
        topBarColor = parseColorSafe(obj.optString("topBarColor", ""), def.topBarColor),
        cardColor = parseColorSafe(obj.optString("cardColor", ""), def.cardColor),
        mineBubbleColor = parseColorSafe(obj.optString("mineBubbleColor", ""), def.mineBubbleColor),
        otherBubbleColor = parseColorSafe(obj.optString("otherBubbleColor", ""), def.otherBubbleColor),
        homeWallpaperUrl = obj.optString("homeWallpaperUrl", ""),
        chatWallpaperUrl = obj.optString("chatWallpaperUrl", ""),
        refreshMs = obj.optLong("refreshMs", def.refreshMs).coerceAtLeast(500L)
    )
}

private suspend fun fetchRealChats(): List<ChatItem> = withContext(Dispatchers.IO) {
    val raw = httpGet("/chats")
    val arr = JSONArray(raw)
    List(arr.length()) { i ->
        val obj = arr.getJSONObject(i)
        ChatItem(
            id = obj.getLong("id"),
            title = obj.optString("title", "Chat"),
            type = obj.optString("type", "Chat"),
            lastMessage = obj.optString("lastMessage", "")
        )
    }
}

private suspend fun fetchRealMessages(chatId: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
    val raw = httpGet("/messages/$chatId")
    val arr = JSONArray(raw)
    val list = mutableListOf<ChatMessage>()
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val txt = obj.optString("text", "").trim()
        val hasMedia = obj.optBoolean("hasMedia", false)
        if (txt.isNotEmpty() || hasMedia) {
            list.add(
                ChatMessage(
                    id = obj.optLong("id", i.toLong() + 1L),
                    sender = obj.optString("sender", "User"),
                    text = if (txt.isNotEmpty()) txt else "[Photo]",
                    isMine = obj.optBoolean("isMine", false),
                    hasMedia = hasMedia
                )
            )
        }
    }
    list
}

private suspend fun sendRealMessage(chatId: Long, text: String) = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("chatId", chatId)
        put("text", text)
    }.toString()
    httpPostJson("/send", body)
}

private suspend fun forwardRealMessage(fromChatId: Long, toChatId: Long, messageId: Long) = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("fromChatId", fromChatId)
        put("toChatId", toChatId)
        put("messageId", messageId)
    }.toString()
    httpPostJson("/forward", body)
}

private suspend fun sendRealMedia(chatId: Long, filePath: String, caption: String) = withContext(Dispatchers.IO) {
    val bytes = File(filePath).readBytes()
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val body = JSONObject().apply {
        put("chatId", chatId)
        put("filePath", filePath)
        put("fileBase64", b64)
        put("caption", caption)
    }.toString()
    httpPostJson("/send_file", body)
}

enum class MainTab(val label: String) {
    CHATS("Chats"),
    CONTACTS("Contacts"),
    SETTINGS("Settings"),
    PROFILE("Profile")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    primary = Color(0xFF0284C7)
                )
            ) {
                AdvancedTelegramApp()
            }
        }
    }
}

@Composable
fun AdvancedTelegramApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiConfig by remember { mutableStateOf(UiConfig()) }
    var homeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var chatBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.CHATS) }
    var selectedChatId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedChatTitle by rememberSaveable { mutableStateOf("") }
    var chats by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var isInitialChatsLoading by remember { mutableStateOf(true) }
    var isInitialMessagesLoading by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf("Bridge: 127.0.0.1:8765") }
    var forwardingMsg by remember { mutableStateOf<ChatMessage?>(null) }

    val activeChat = remember(selectedChatId, selectedChatTitle, chats) {
        val cid = selectedChatId
        if (cid == null) null
        else chats.find { it.id == cid } ?: ChatItem(cid, selectedChatTitle.ifBlank { "Chat" }, "Chat", "")
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val freshUi = fetchUiConfig()
                if (freshUi != uiConfig) {
                    uiConfig = freshUi
                    homeBitmap = loadRemoteBitmap(context, freshUi.homeWallpaperUrl, "live_home_bg.png")
                    chatBitmap = loadRemoteBitmap(context, freshUi.chatWallpaperUrl, "live_chat_bg.png")
                }
                statusNotice = "Connected (127.0.0.1:8765)"
            } catch (_: Exception) {
                statusNotice = "Bridge Offline - Start bridge_server.py"
            }
            delay(2000L)
        }
    }

    LaunchedEffect(selectedChatId, selectedTab, uiConfig.refreshMs) {
        if (selectedChatId == null && (selectedTab == MainTab.CHATS || selectedTab == MainTab.CONTACTS)) {
            while (true) {
                try {
                    val fresh = fetchRealChats()
                    if (fresh != chats) {
                        chats = fresh
                    }
                } catch (_: Exception) {
                } finally {
                    isInitialChatsLoading = false
                }
                delay(uiConfig.refreshMs)
            }
        }
    }

    LaunchedEffect(selectedChatId, uiConfig.refreshMs) {
        val cid = selectedChatId
        if (cid != null) {
            while (true) {
                try {
                    val freshMsgs = fetchRealMessages(cid)
                    if (freshMsgs != messages) {
                        messages = freshMsgs
                    }
                } catch (_: Exception) {
                } finally {
                    isInitialMessagesLoading = false
                }
                delay(uiConfig.refreshMs)
            }
        }
    }

    if (activeChat != null) {
        ChatDetailScreen(
            chat = activeChat,
            messages = messages,
            uiConfig = uiConfig,
            chatBitmap = chatBitmap,
            isLoading = isInitialMessagesLoading,
            onBack = {
                selectedChatId = null
                selectedChatTitle = ""
            },
            onSendText = { text ->
                scope.launch {
                    try {
                        sendRealMessage(activeChat.id, text)
                        messages = fetchRealMessages(activeChat.id)
                    } catch (_: Exception) {
                    }
                }
            },
            onSendUri = { uri, caption, onDone ->
                scope.launch {
                    val path = copyUriToCache(context, uri)
                    if (path != null) {
                        try {
                            sendRealMedia(activeChat.id, path, caption)
                            val localMsg = ChatMessage(
                                id = -System.currentTimeMillis(),
                                sender = "Me",
                                text = if (caption.isNotBlank()) caption else "[Photo]",
                                isMine = true,
                                hasMedia = true,
                                localPhotoPath = path
                            )
                            val fetched = fetchRealMessages(activeChat.id)
                            messages = if (fetched.any { it.hasMedia }) fetched else (fetched + localMsg)
                            onDone("Sent")
                        } catch (e: Exception) {
                            onDone("Err: ${e.message ?: "send_file"}")
                        }
                    } else {
                        onDone("File copy failed")
                    }
                }
            },
            onRequestForward = { msg -> forwardingMsg = msg }
        )

        val targetMsg = forwardingMsg
        if (targetMsg != null) {
            AlertDialog(
                onDismissRequest = { forwardingMsg = null },
                title = { Text("Forward To...") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(chats.filter { it.id != activeChat.id }, key = { it.id }) { dest ->
                            TextButton(
                                onClick = {
                                    val msgToSend = targetMsg
                                    forwardingMsg = null
                                    scope.launch {
                                        try {
                                            forwardRealMessage(activeChat.id, dest.id, msgToSend.id)
                                        } catch (_: Exception) {
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(dest.title, color = Color.White)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { forwardingMsg = null }) { Text("Cancel") }
                }
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color(0xF20F172A)) {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            label = { Text(tab.label, fontSize = 11.sp) },
                            icon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (selectedTab == tab) Color(0xFF38BDF8) else Color(0xFF64748B))
                                )
                            }
                        )
                    }
                }
            },
            containerColor = Color.Transparent
        ) { navPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(navPadding)) {
                when (selectedTab) {
                    MainTab.CHATS -> {
                        ChatListScreen(
                            chats = chats,
                            uiConfig = uiConfig,
                            homeBitmap = homeBitmap,
                            isLoading = isInitialChatsLoading,
                            onChatClick = { chat ->
                                selectedChatId = chat.id
                                selectedChatTitle = chat.title
                                messages = emptyList()
                                isInitialMessagesLoading = true
                            }
                        )
                    }
                    MainTab.CONTACTS -> {
                        ContactsScreen(
                            chats = chats,
                            uiConfig = uiConfig,
                            homeBitmap = homeBitmap,
                            onOpenChat = { chat ->
                                selectedChatId = chat.id
                                selectedChatTitle = chat.title
                                messages = emptyList()
                                isInitialMessagesLoading = true
                            }
                        )
                    }
                    MainTab.SETTINGS -> {
                        TelegramSettingsScreen(
                            uiConfig = uiConfig,
                            homeBitmap = homeBitmap,
                            statusNotice = statusNotice,
                            chatCount = chats.size,
                            onUpdateLocalConfig = { updated -> uiConfig = updated },
                            onClearWallpaperCache = {
                                scope.launch(Dispatchers.IO) {
                                    File(context.cacheDir, "live_home_bg.png").delete()
                                    File(context.cacheDir, "live_chat_bg.png").delete()
                                    val h = loadRemoteBitmap(context, uiConfig.homeWallpaperUrl, "live_home_bg.png")
                                    val c = loadRemoteBitmap(context, uiConfig.chatWallpaperUrl, "live_chat_bg.png")
                                    withContext(Dispatchers.Main) {
                                        homeBitmap = h
                                        chatBitmap = c
                                        statusNotice = "Wallpaper cache refreshed"
                                    }
                                }
                            }
                        )
                    }
                    MainTab.PROFILE -> {
                        ProfileScreen(
                            uiConfig = uiConfig,
                            homeBitmap = homeBitmap,
                            statusNotice = statusNotice,
                            chatCount = chats.size
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chats: List<ChatItem>,
    uiConfig: UiConfig,
    homeBitmap: Bitmap?,
    isLoading: Boolean,
    onChatClick: (ChatItem) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(chats, query) {
        if (query.isBlank()) chats
        else chats.filter {
            it.title.contains(query.trim(), ignoreCase = true) ||
                it.lastMessage.contains(query.trim(), ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "App Logo",
                            modifier = Modifier.size(30.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(uiConfig.appTitle, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = uiConfig.topBarColor)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (homeBitmap != null) {
                Image(bitmap = homeBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(painter = painterResource(id = R.drawable.home_theme), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }

            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(uiConfig.cardColor, RoundedCornerShape(12.dp)),
                    placeholder = { Text("Search chats...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (isLoading && chats.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { chat ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(uiConfig.cardColor, RoundedCornerShape(12.dp))
                                    .clickable { onChatClick(chat) }
                                    .padding(12.dp)
                            ) {
                                Text(chat.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                if (chat.lastMessage.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(chat.lastMessage, color = Color(0xFFE2E8F0), fontSize = 13.sp, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    chats: List<ChatItem>,
    uiConfig: UiConfig,
    homeBitmap: Bitmap?,
    onOpenChat: (ChatItem) -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    val list = remember(chats, search) {
        if (search.isBlank()) chats
        else chats.filter { it.title.contains(search.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts & Chats", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = uiConfig.topBarColor)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (homeBitmap != null) {
                Image(bitmap = homeBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(painter = painterResource(id = R.drawable.home_theme), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().background(uiConfig.cardColor, RoundedCornerShape(12.dp)),
                    placeholder = { Text("Search contacts...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(uiConfig.cardColor, RoundedCornerShape(12.dp))
                                .clickable { onOpenChat(item) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF0284C7)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(item.title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(item.type, color = Color(0xFF93C5FD), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramSettingsScreen(
    uiConfig: UiConfig,
    homeBitmap: Bitmap?,
    statusNotice: String,
    chatCount: Int,
    onUpdateLocalConfig: (UiConfig) -> Unit,
    onClearWallpaperCache: () -> Unit
) {
    var titleDraft by remember(uiConfig.appTitle) { mutableStateOf(uiConfig.appTitle) }
    var fastSyncEnabled by remember(uiConfig.refreshMs) { mutableStateOf(uiConfig.refreshMs <= 1000L) }
    var blueBubblesEnabled by remember(uiConfig.mineBubbleColor) {
        mutableStateOf(uiConfig.mineBubbleColor == Color(0xE61D4ED8))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = uiConfig.topBarColor)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (homeBitmap != null) {
                Image(bitmap = homeBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(painter = painterResource(id = R.drawable.home_theme), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(uiConfig.cardColor, RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Appearance & Sync", color = Color(0xFF7DD3FC), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("$statusNotice | Chats: $chatCount", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        OutlinedTextField(
                            value = titleDraft,
                            onValueChange = { titleDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("App Header Title") },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val clean = titleDraft.trim()
                                if (clean.isNotEmpty()) onUpdateLocalConfig(uiConfig.copy(appTitle = clean))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Apply Title") }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Telegram Blue Bubbles", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = blueBubblesEnabled,
                                onCheckedChange = { checked ->
                                    blueBubblesEnabled = checked
                                    onUpdateLocalConfig(uiConfig.copy(mineBubbleColor = if (checked) Color(0xE61D4ED8) else Color(0xE6047857)))
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Fast Live Sync (1s)", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = fastSyncEnabled,
                                onCheckedChange = { checked ->
                                    fastSyncEnabled = checked
                                    onUpdateLocalConfig(uiConfig.copy(refreshMs = if (checked) 1000L else 3000L))
                                }
                            )
                        }
                        OutlinedButton(onClick = onClearWallpaperCache, modifier = Modifier.fillMaxWidth()) {
                            Text("Reload Live Wallpapers", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    uiConfig: UiConfig,
    homeBitmap: Bitmap?,
    statusNotice: String,
    chatCount: Int
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = uiConfig.topBarColor)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (homeBitmap != null) {
                Image(bitmap = homeBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(painter = painterResource(id = R.drawable.home_theme), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(uiConfig.cardColor, RoundedCornerShape(14.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(uiConfig.appTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(statusNotice, color = Color(0xFF93C5FD), fontSize = 12.sp)
                        Text("Active Chats: $chatCount", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chat: ChatItem,
    messages: List<ChatMessage>,
    uiConfig: UiConfig,
    chatBitmap: Bitmap?,
    isLoading: Boolean,
    onBack: () -> Unit,
    onSendText: (String) -> Unit,
    onSendUri: (Uri, String, (String) -> Unit) -> Unit,
    onRequestForward: (ChatMessage) -> Unit
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var isSendingMedia by remember { mutableStateOf(false) }
    var mediaStatus by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingUri = uri
            mediaStatus = "Photo ready to send"
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat.title, color = Color.White, fontSize = 17.sp)
                        if (mediaStatus.isNotBlank()) {
                            Text(mediaStatus, color = Color(0xFF7DD3FC), fontSize = 11.sp)
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("<- Back", color = Color(0xFFBAE6FD)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = uiConfig.topBarColor)
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xF20F172A)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val picked = pendingUri
                if (picked != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSendingMedia) "Sending photo..." else "Photo selected",
                            color = Color(0xFF7DD3FC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row {
                            TextButton(
                                enabled = !isSendingMedia,
                                onClick = {
                                    pendingUri = null
                                    mediaStatus = ""
                                }
                            ) { Text("Cancel", color = Color(0xFFFCA5A5), fontSize = 12.sp) }
                            Button(
                                enabled = !isSendingMedia,
                                onClick = {
                                    val cap = inputText.trim()
                                    isSendingMedia = true
                                    mediaStatus = "Uploading..."
                                    onSendUri(picked, cap) { res ->
                                        isSendingMedia = false
                                        pendingUri = null
                                        inputText = ""
                                        mediaStatus = res
                                    }
                                }
                            ) { Text(if (isSendingMedia) "..." else "Send Photo", fontSize = 12.sp) }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { mediaPicker.launch("image/*") },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text("Media", color = Color(0xFF7DD3FC), fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (picked != null) "Caption likhein..." else "Message likhein...") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val trimmed = inputText.trim()
                            if (trimmed.isNotEmpty()) {
                                onSendText(trimmed)
                                inputText = ""
                            }
                        }
                    ) { Text("Send") }
                }
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (chatBitmap != null) {
                Image(bitmap = chatBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(painter = painterResource(id = R.drawable.chat_theme), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (isLoading && messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF0F172A))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 290.dp)
                                    .background(
                                        if (msg.isMine) uiConfig.mineBubbleColor else uiConfig.otherBubbleColor,
                                        RoundedCornerShape(
                                            topStart = 12.dp,
                                            topEnd = 12.dp,
                                            bottomStart = if (msg.isMine) 12.dp else 2.dp,
                                            bottomEnd = if (msg.isMine) 2.dp else 12.dp
                                        )
                                    )
                                    .clickable { onRequestForward(msg) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                if (!msg.isMine) {
                                    Text(msg.sender, color = Color(0xFF7DD3FC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                val pPath = msg.localPhotoPath
                                if (pPath != null && File(pPath).exists()) {
                                    val bmp = remember(pPath) { BitmapFactory.decodeFile(pPath) }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "Sent photo",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 120.dp, max = 210.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }
                                }
                                Text(msg.text, color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Tap to forward", color = Color(0xFFCBD5E1), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
