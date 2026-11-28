package com.example.mytelegramapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
    val sender: String,
    val text: String,
    val isMine: Boolean
)

data class UiConfig(
    val appTitle: String = "Advanced Telegram",
    val topBarColor: Color = Color(0xCC0F172A),
    val cardColor: Color = Color(0xB3111827),
    val mineBubbleColor: Color = Color(0xE61D4ED8),
    val otherBubbleColor: Color = Color(0xE61F2937),
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
    if (code !in 200..299) {
        throw RuntimeException("HTTP $code: $body")
    }
    return body
}

private fun httpPostJson(path: String, jsonBody: String): String {
    val url = URL("$BRIDGE_BASE$path")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 5000
        readTimeout = 15000
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(jsonBody)
        writer.flush()
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
    if (code !in 200..299) {
        throw RuntimeException("HTTP $code: $body")
    }
    return body
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
        if (txt.isNotEmpty()) {
            list.add(
                ChatMessage(
                    sender = obj.optString("sender", "User"),
                    text = txt,
                    isMine = obj.optBoolean("isMine", false)
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
    val scope = rememberCoroutineScope()
    var uiConfig by remember { mutableStateOf(UiConfig()) }
    var selectedChat by remember { mutableStateOf<ChatItem?>(null) }
    var chats by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var isInitialChatsLoading by remember { mutableStateOf(true) }
    var isInitialMessagesLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val freshUi = fetchUiConfig()
                if (freshUi != uiConfig) {
                    uiConfig = freshUi
                }
            } catch (_: Exception) {
            }
            delay(1000L)
        }
    }

    LaunchedEffect(selectedChat, uiConfig.refreshMs) {
        if (selectedChat == null) {
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

    LaunchedEffect(selectedChat?.id, uiConfig.refreshMs) {
        val active = selectedChat
        if (active != null) {
            while (true) {
                try {
                    val freshMsgs = fetchRealMessages(active.id)
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

    val current = selectedChat
    if (current == null) {
        ChatListScreen(
            chats = chats,
            uiConfig = uiConfig,
            isLoading = isInitialChatsLoading,
            onChatClick = { chat ->
                selectedChat = chat
                messages = emptyList()
                isInitialMessagesLoading = true
            }
        )
    } else {
        ChatDetailScreen(
            chat = current,
            messages = messages,
            uiConfig = uiConfig,
            isLoading = isInitialMessagesLoading,
            onBack = { selectedChat = null },
            onSend = { text ->
                scope.launch {
                    try {
                        sendRealMessage(current.id, text)
                        messages = fetchRealMessages(current.id)
                    } catch (_: Exception) {
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chats: List<ChatItem>,
    uiConfig: UiConfig,
    isLoading: Boolean,
    onChatClick: (ChatItem) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "App Logo",
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(uiConfig.appTitle, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = uiConfig.topBarColor,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Image(
                painter = painterResource(id = R.drawable.home_theme),
                contentDescription = "Home Theme",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            if (isLoading && chats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chats, key = { it.id }) { chat ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chat: ChatItem,
    messages: List<ChatMessage>,
    uiConfig: UiConfig,
    isLoading: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chat.title, color = Color.White, fontSize = 17.sp) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = Color(0xFFBAE6FD))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = uiConfig.topBarColor)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xE60F172A))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message likhein...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty()) {
                            onSend(trimmed)
                            inputText = ""
                        }
                    }
                ) {
                    Text("Send")
                }
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Image(
                painter = painterResource(id = R.drawable.chat_theme),
                contentDescription = "Chat Theme",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            if (isLoading && messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF0F172A))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
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
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                if (!msg.isMine) {
                                    Text(
                                        msg.sender,
                                        color = Color(0xFF7DD3FC),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Text(msg.text, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
