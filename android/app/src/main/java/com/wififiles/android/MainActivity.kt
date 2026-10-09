package com.wififiles.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.util.Locale

private val Base = Color(0xFF090B0E)
private val Panel = Color(0xFF0D1116)
private val Raised = Color(0xFF14191F)
private val Accent = Color(0xFF20E3AD)
private val Muted = Color(0xFF91A1B5)
private val Line = Color(0xFF202830)

class MainActivity : ComponentActivity() {
    private var storageAllowed by mutableStateOf(false)
    private var feedback by mutableStateOf("")
    private var discoveryLock: android.net.wifi.WifiManager.MulticastLock? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startSharing() }
    private val saveConnection = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pairing = Session.state.pairing
        if (uri != null && pairing != null) runCatching { contentResolver.openOutputStream(uri)?.use { it.write(pairing.document().toByteArray()) } }
            .onSuccess { feedback = "Arquivo de conexão salvo." }.onFailure { feedback = "Não foi possível salvar o arquivo." }
    }
    override fun onResume() {
        super.onResume(); storageAllowed = Environment.isExternalStorageManager()
        discoveryLock = (applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)).createMulticastLock("wifi-files-visible").apply { setReferenceCounted(false); acquire() }
        runCatching {
            val trusted = Session.trust ?: TrustedPeers(this).also { Session.trust = it }
            Session.update { it.copy(trusted = trusted.list()) }
            if (trusted.list().isNotEmpty() && storageAllowed && !Session.state.active && !Session.state.starting) startSharing()
        }.onFailure { feedback = "Não foi possível ler os dispositivos lembrados. ${it.message.orEmpty()}" }
    }
    override fun onPause() {
        discoveryLock?.let { if (it.isHeld) it.release() }; discoveryLock = null
        super.onPause()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storageAllowed = Environment.isExternalStorageManager()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF05251B), background = Base, surface = Panel,
                surfaceVariant = Raised, onSurface = Color(0xFFE4EAF1), onSurfaceVariant = Muted, outline = Line)) {
                Surface(Modifier.fillMaxSize(), color = Base) { Screen() }
            }
        }
    }
    private fun requestStorage() { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
    private fun startSharing() {
        if (!Environment.isExternalStorageManager()) { requestStorage(); return }
        ContextCompat.startForegroundService(this, Intent(this, SharingService::class.java))
    }
    private fun startWithNotification() { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else startSharing() }
    private fun stopSharing() { stopService(Intent(this, SharingService::class.java)) }
    private fun shareConnection() {
        val file = File(cacheDir, "pairing/conexao-wifi.json")
        if (!file.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Enviar conexão ao PC"))
    }
    @Composable private fun Screen() {
        val updater = remember { AppUpdater(this) }
        DisposableEffect(updater) { updater.check(); onDispose { updater.close() } }
        val state = Session.state
        var tab by remember { mutableStateOf("Conexão") }
        var showQr by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(false) }
        var showNewPc by remember { mutableStateOf(false) }
        var forget by remember { mutableStateOf<TrustedPeer?>(null) }
        val stats = remember(state.history.size, state.active, storageAllowed) { StatFs(Environment.getExternalStorageDirectory().path) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp, 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.ic_brand), "Logo SFLink", modifier = Modifier.width(40.dp).height(34.dp))
                Column { Text("SFLink", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text("via Wi-Fi", color = Accent, fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                Surface(color = if (state.active) Color(0xFF09251D) else Raised, shape = RoundedCornerShape(50)) {
                    Text(if (state.active) "Disponível" else "Desconectado", color = if (state.active) Accent else Muted, fontSize = 11.sp, modifier = Modifier.padding(12.dp, 7.dp))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.padding(4.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tab, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                    Text(when (tab) { "Conexão" -> "Seu computador e celular, sem cabo."; "Transferências" -> "Acompanhe seus arquivos nesta sessão."; else -> "Computadores com acesso rápido ao celular." }, color = Muted, fontSize = 13.sp)
                }
                if (tab == "Conexão") {
                    PanelCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(color = Color(0xFF09251D), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Outlined.Computer, null, tint = Accent, modifier = Modifier.padding(13.dp).size(25.dp)) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (state.starting) "Preparando conexão…" else if (state.peer.isNotBlank()) state.peer else if (state.active) "Aguardando o PC" else "Conectar ao computador", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(if (state.peer.isNotBlank()) "Conectado via Wi-Fi" else "Na mesma rede local", color = if (state.peer.isNotBlank()) Accent else Muted, fontSize = 12.sp)
                            }
                        }
                        if (state.starting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                        if (!state.active && !state.starting) {
                            Text(if (state.trusted.isNotEmpty()) "Abra a conexão para o PC reconhecer este celular automaticamente." else "Ative a conexão e faça o primeiro pareamento pelo aplicativo do PC.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                            Button(onClick = { if (storageAllowed) startWithNotification() else requestStorage() }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(50)) {
                                Icon(if (storageAllowed) Icons.Outlined.Wifi else Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (storageAllowed) "Ativar conexão" else "Autorizar acesso aos arquivos")
                            }
                        }
                        if (state.active) {
                            if (state.trusted.isNotEmpty()) {
                                Surface(color = Color(0xFF09251D), shape = RoundedCornerShape(16.dp)) {
                                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Outlined.VerifiedUser, null, tint = Accent, modifier = Modifier.size(19.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Conexão rápida ativada", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Medium); Text("Abra o app no PC. Seus dispositivos lembrados conectam sem código.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp) }
                                    }
                                }
                            }
                            if (state.trusted.isNotEmpty()) TextButton(onClick = { showNewPc = !showNewPc }) { Text(if (showNewPc) "Ocultar novo pareamento" else "Parear outro computador") }
                            if (state.trusted.isEmpty() || showNewPc) {
                            HorizontalDivider(color = Line)
                            Text(if (state.trusted.isNotEmpty()) "Parear outro computador" else "Primeiro pareamento", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("Endereço do celular", color = Muted, fontSize = 12.sp)
                            state.pairing?.addresses.orEmpty().forEach { Text("https://$it:8443", color = Accent, fontSize = 15.sp) }
                            if (state.pairing?.addresses.isNullOrEmpty()) Text("Conecte o celular ao Wi-Fi para encontrar o PC.", color = Color(0xFFE8BA73), fontSize = 12.sp)
                            Surface(color = Raised, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Código temporário", color = Muted, fontSize = 12.sp)
                                    Text(if (state.code.isBlank()) "Código utilizado" else state.code.chunked(3).joinToString(" "), color = Accent, fontSize = if (state.code.isBlank()) 20.sp else 34.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Válido por 5 minutos. Digite no PC e autorize aqui.", color = Muted, fontSize = 11.sp)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { val code = Session.gate?.renew().orEmpty(); Session.update { it.copy(code = code) } }) { Text("Novo código") }
                                TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Ocultar opções" else "Outras opções") }
                            }
                            if (showAdvanced) {
                                OutlinedButton(onClick = { saveConnection.launch("conexao-wifi.json") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Salvar arquivo de conexão") }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { shareConnection() }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Compartilhar", fontSize = 12.sp) }
                                    OutlinedButton(onClick = { showQr = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.QrCode2, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("QR code", fontSize = 12.sp) }
                                }
                                Text("Para lembrar um PC, conecte usando IP e código e marque a opção na autorização.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            }
                            OutlinedButton(onClick = { stopSharing() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF3ADB3))) { Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Encerrar compartilhamento") }
                        }
                        if (state.error.isNotBlank()) Text(state.error, color = Color(0xFFFFB4AC), fontSize = 13.sp)
                    }
                    PanelCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.FolderOpen, null, tint = Accent); Text("Armazenamento interno", fontWeight = FontWeight.Medium, fontSize = 14.sp) }
                        Text("${formatBytes(stats.availableBytes)} livres de ${formatBytes(stats.totalBytes)}", color = Muted, fontSize = 12.sp)
                        LinearProgressIndicator(progress = { (1f - stats.availableBytes.toFloat() / stats.totalBytes.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = Accent, trackColor = Line)
                        Text("Músicas, downloads, fotos e documentos", color = Muted, fontSize = 11.sp)
                    }
                } else if (tab == "Transferências") {
                    PanelCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Outlined.SwapVert, null, tint = Accent); Text("Transferências", fontWeight = FontWeight.Medium); Spacer(Modifier.weight(1f)); if (state.history.isNotEmpty()) TextButton(onClick = { Session.update { it.copy(history = emptyList()) } }) { Text("Limpar", fontSize = 12.sp) } }
                        if (state.operation.isNotBlank()) {
                            Text(state.operation, fontSize = 14.sp)
                            LinearProgressIndicator(progress = { if (state.total > 0) (state.transferred.toFloat() / state.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                            Text("${formatBytes(state.transferred)} de ${formatBytes(state.total)}", color = Muted, fontSize = 12.sp)
                        }
                        if (state.history.isEmpty() && state.operation.isBlank()) { Icon(Icons.Outlined.SwapVert, null, tint = Muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp).size(36.dp)); Text("Tudo pronto para transferir", modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 15.sp); Text("Arraste arquivos no PC para enviar ao celular.", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 18.dp)) }
                        state.history.forEach { item -> Row(Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(14.dp)).padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Check, null, tint = Accent, modifier = Modifier.size(19.dp)); Column { Text(item, fontSize = 13.sp); Text("Concluído", color = Accent, fontSize = 11.sp) } } }
                    }
                } else {
                    PanelCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Devices, null, tint = Accent); Text("Dispositivos lembrados", fontWeight = FontWeight.Medium, fontSize = 15.sp) }
                        if (state.trusted.isEmpty()) { Text("Nenhum computador lembrado", fontSize = 15.sp); Text("Na primeira conexão, marque “Lembrar dispositivo” na tela de autorização. Depois, basta abrir os dois aplicativos na mesma rede.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp) }
                        state.trusted.forEach { peer ->
                            Row(Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.Computer, null, tint = Accent)
                                Column(Modifier.weight(1f)) { Text(peer.name, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text("Conexão rápida autorizada", color = Muted, fontSize = 11.sp) }
                                TextButton(onClick = { forget = peer }) { Text("Esquecer", fontSize = 12.sp, color = Color(0xFFF3ADB3)) }
                            }
                        }
                        if (state.trusted.isNotEmpty()) Text("Esquecer revoga o acesso salvo deste PC. Para reconectar, ele precisará de um novo pareamento.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
                if (tab == "Dispositivos") UpdatePanel(this@MainActivity, updater, state.operation.isNotBlank())
                if (feedback.isNotBlank()) Text(feedback, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Row(Modifier.padding(4.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { Icon(Icons.Outlined.Shield, null, tint = Accent, modifier = Modifier.size(17.dp)); Text("Conexão local e criptografada. Encerre o compartilhamento para interromper o acesso.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp) }
                Spacer(Modifier.height(8.dp))
            }
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                listOf("Conexão" to Icons.Outlined.Wifi, "Transferências" to Icons.Outlined.SwapVert, "Dispositivos" to Icons.Outlined.Devices).forEach { (name, icon) ->
                    NavigationBarItem(selected = tab == name, onClick = { tab = name }, icon = { Icon(icon, null) }, label = { Text(name, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Color(0xFF09251D), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }
        state.pending?.let { request ->
            var rememberDevice by remember(request) { mutableStateOf(false) }
            AlertDialog(onDismissRequest = { Session.gate?.answer(request, false) }, containerColor = Panel, title = { Text("Autorizar este computador?") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(request.clientName.ifBlank { request.peer }, fontWeight = FontWeight.Medium)
                    Surface(color = Raised, shape = RoundedCornerShape(14.dp)) { Text(request.verification.chunked(4).joinToString(" "), modifier = Modifier.padding(14.dp), fontSize = 20.sp, color = Accent, fontWeight = FontWeight.SemiBold) }
                    Text("Confira se esta verificação aparece no PC. A autorização permite acessar e modificar seus arquivos.", fontSize = 13.sp)
                    if (request.clientId.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = rememberDevice, onCheckedChange = { rememberDevice = it }); Text("Lembrar dispositivo", fontWeight = FontWeight.Medium) }
                        Text("Nas próximas vezes, este PC conecta sem código nem confirmação. Você pode revogar o acesso em Dispositivos.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }, confirmButton = { TextButton(onClick = { Session.gate?.answer(request, true, rememberDevice); Session.update { it.copy(code = "") } }) { Text("Autorizar") } }, dismissButton = { TextButton(onClick = { Session.gate?.answer(request, false) }) { Text("Recusar") } })
        }
        forget?.let { peer -> AlertDialog(onDismissRequest = { forget = null }, containerColor = Panel, title = { Text("Esquecer ${peer.name}?") }, text = { Text("O acesso salvo será revogado e as conexões atuais serão encerradas. Esse PC precisará parear novamente.") }, confirmButton = { TextButton(onClick = {
            runCatching { Session.trust?.forget(peer.id); Session.revoke?.invoke(); Session.update { it.copy(trusted = Session.trust?.list().orEmpty(), peer = "") } }.onFailure { feedback = "Não foi possível esquecer o dispositivo." }
            forget = null
        }) { Text("Esquecer") } }, dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancelar") } }) }
        if (showQr && state.pairing != null) {
            val bitmap = remember(state.pairing) { qrBitmap(state.pairing.document()) }
            AlertDialog(onDismissRequest = { showQr = false }, containerColor = Panel, title = { Text("Conexão com o PC") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { Image(bitmap.asImageBitmap(), "QR code de conexão", Modifier.size(248.dp).background(Color.White).padding(8.dp)); Text("O QR contém o acesso desta sessão. Para lembrar o PC, use o pareamento por código.", fontSize = 12.sp) } }, confirmButton = { TextButton(onClick = { showQr = false }) { Text("Fechar") } })
        }
    }
}

@Composable private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), border = androidx.compose.foundation.BorderStroke(1.dp, Line), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.forLanguageTag("pt-BR"), "%.1f %s", value, units[index])
}
private fun qrBitmap(text: String): android.graphics.Bitmap {
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
    return android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888).apply { setPixels(IntArray(640 * 640) { if (matrix[it % 640, it / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 640, 0, 0, 640, 640) }
}
