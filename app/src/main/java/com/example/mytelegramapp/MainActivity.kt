package com.example.mytelegramapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                TelegramCloneApp()
            }
        }
    }
}

@Composable
fun TelegramCloneApp() {
    var selectedChat by remember { mutableStateOf<ChatItem?>(null) }

    val chats = remember {
        listOf(
            ChatItem(1L, "Saved Messages", "Personal Chat", "Notes & links ready"),
            ChatItem(-100111L, "Tech & Growth Group", "Group", "Aaj ka update check karo"),
            ChatItem(-100222L, "Updates Channel", "Channel", "New build version 0.1 live")
        )
    }

    val messagesByChat = remember {
        mutableStateMapOf(
            1L to mutableStateListOf(
                ChatMessage("Me", "Starter Android app ready hai!", true),
                ChatMessage("System", "Baad mein yahan Telegram messages connect honge.", false)
            ),
            -100111L to mutableStateListOf(
                ChatMessage("Rahul", "Group messages yahan line se dikhenge.", false),
                ChatMessage("Me", "Theek hai, UI test kar rahe hain.", true)
            ),
            -100222L to mutableStateListOf(
                ChatMessage("Updates Channel", "Channel posts yahan show hongi.", false)
            )
        )
    }

    val current = selectedChat
    if (current == null) {
        ChatListScreen(
            chats = chats,
            onChatClick = { selectedChat = it }
        )
    } else {
        val msgs = messagesByChat.getOrPut(current.id) { mutableStateListOf() }
        ChatDetailScreen(
            chat = current,
            messages = msgs,
            onBack = { selectedChat = null },
            onSend = { text ->
                msgs.add(ChatMessage("Me", text, true))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chats: List<ChatItem>,
    onChatClick: (ChatItem) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Telegram App", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1F2937),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chats) { chat ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chat: ChatItem,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
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
