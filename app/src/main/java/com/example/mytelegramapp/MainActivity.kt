package com.example.mytelegramapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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

private const val BRIDGE_BASE = "http://127.0.0.1:8765"

private fun httpGet(path: String): String {
    val url = URL("$BRIDGE_BASE$path")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 30000
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
        connectTimeout = 10000
        readTimeout = 30000
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
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    primary = Color(0xFF2563EB)
                )
            ) {
                TelegramRealApp()
            }
        }
    }
}

@Composable
fun TelegramRealApp() {
    val scope = rememberCoroutineScope()
    var selectedChat by remember { mutableStateOf<ChatItem?>(null) }
    var chats by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var isLoadingMessages by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Chats load ho rahi hain...") }
    var isLoading by remember { mutableStateOf(true) }

    fun loadChats() {
        isLoading = true
        statusText = "Chats load ho rahi hain..."
        scope.launch {
            try {
                chats = fetchRealChats()
                statusText = "Connected (${chats.size} chats)"
            } catch (e: Exception) {
                statusText = "Error: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadChats()
    }

    val current = selectedChat
    if (current == null) {
        ChatListScreen(
            chats = chats,
            statusText = statusText,
            isLoading = isLoading,
            onRefresh = { loadChats() },
            onChatClick = { chat ->
                selectedChat = chat
                messages = emptyList()
                isLoadingMessages = true
                scope.launch {
                    try {
                        messages = fetchRealMessages(chat.id)
                    } catch (e: Exception) {
                        messages = listOf(
                            ChatMessage(
                                "Error",
                                "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}",
                                false
                            )
                        )
                    } finally {
                        isLoadingMessages = false
                    }
                }
            }
        )
    } else {
        ChatDetailScreen(
            chat = current,
            messages = messages,
            isLoading = isLoadingMessages,
            onBack = { selectedChat = null },
            onSend = { text ->
                scope.launch {
                    try {
                        sendRealMessage(current.id, text)
                        messages = fetchRealMessages(current.id)
                    } catch (e: Exception) {
                        messages = messages + ChatMessage(
                            "Error",
                            "Send fail (${e.javaClass.simpleName}): ${e.message}",
                            false
                        )
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
    statusText: String,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onChatClick: (ChatItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Personal Chat", "Group", "Channel")

    val filteredChats = remember(chats, searchQuery, selectedFilter) {
        chats.filter { item ->
            val matchesType = selectedFilter == "All" || item.type.equals(selectedFilter, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.lastMessage.contains(searchQuery, ignoreCase = true)
            matchesType && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Telegram App", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onRefresh) {
                        Text("Refresh", color = Color(0xFF93C5FD))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1F2937),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .padding(padding)
                .padding(12.dp)
        ) {
            Text(statusText, color = Color(0xFF9CA3AF), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Chat, group ya channel search karein...") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { tab ->
                    FilterChip(
                        selected = selectedFilter == tab,
                        onClick = { selectedFilter = tab },
                        label = { Text(tab) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredChats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Koi chat nahi mili. Refresh dabayein ya Termux bridge check karein.",
                        color = Color(0xFF9CA3AF),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredChats, key = { it.id }) { chat ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
                                .clickable { onChatClick(chat) }
                                .padding(12.dp)
                        ) {
                            Text(chat.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("${chat.type} • ${chat.lastMessage}", color = Color(0xFF9CA3AF), fontSize = 13.sp)
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
    isLoading: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val reversedMessages = remember(messages) { messages.asReversed() }

    LaunchedEffect(reversedMessages.size) {
        if (reversedMessages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chat.title, color = Color.White, fontSize = 17.sp) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = Color(0xFF93C5FD))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1F2937))
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
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
        containerColor = Color(0xFF121212)
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (reversedMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Is chat mein koi text message nahi mila.", color = Color(0xFF9CA3AF))
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(reversedMessages) { msg ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (msg.isMine) Color(0xFF1D4ED8) else Color(0xFF262626),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(msg.sender, color = Color(0xFFBFDBFE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(msg.text, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
