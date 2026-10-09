package com.wififiles.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.Executors

data class SessionState(val starting: Boolean = false, val active: Boolean = false, val pairing: Pairing? = null, val peer: String = "", val operation: String = "", val transferred: Long = 0, val total: Long = 0, val history: List<String> = emptyList(), val error: String = "", val code: String = "", val pending: PairRequest? = null, val trusted: List<TrustedPeer> = emptyList())
object Session {
    var gate: PairingGate? = null
    var trust: TrustedPeers? = null
    var revoke: (() -> Unit)? = null
    var state by mutableStateOf(SessionState())
    private val main = Handler(Looper.getMainLooper())
    fun update(block: (SessionState) -> SessionState) { main.post { state = block(state) } }
}

class SharingService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var server: FileServer? = null
    private var bootstrap: BootstrapServer? = null
    private var discovery: DiscoveryServer? = null
    @Volatile private var alive = false
    @Volatile private var lastPeer = 0L
    private val monitor = Handler(Looper.getMainLooper())
    private val networkWatch = object : Runnable {
        override fun run() {
            if (!alive) return
            val current = server
            if (current != null && Session.state.active && PairingFactory.addresses(this@SharingService).toSet() != current.pairing.addresses.toSet()) {
                Session.update { it.copy(active = false, starting = true, peer = "") }
                runCatching { current.stop() }
            }
            if (Session.state.peer.isNotBlank() && Session.state.operation.isBlank() && android.os.SystemClock.elapsedRealtime() - lastPeer > 20_000) Session.update { it.copy(peer = "") }
            monitor.postDelayed(this, 5000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (alive) return START_NOT_STICKY
        if (!Environment.isExternalStorageManager()) { Session.update { it.copy(error = "Autorize o acesso aos arquivos antes de iniciar.") }; stopSelf(); return START_NOT_STICKY }
        alive = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sharing", "Conexão com o PC", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SharingService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "sharing").setSmallIcon(R.drawable.ic_notification).setContentTitle("SFLink")
            .setContentText("Compartilhamento ativo. Toque para abrir.").setContentIntent(launch).setOngoing(true).addAction(0, "Encerrar", stop).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Session.update { SessionState(starting = true) }
        monitor.postDelayed(networkWatch, 5000)
        executor.execute {
            try {
              while (alive) {
                val pairing = PairingFactory.create(this)
                val trusted = TrustedPeers(this)
                Session.trust = trusted
                if (!alive) return@execute
                val gate = PairingGate(pairing.certificate) { request -> Session.update { it.copy(pending = request) } }
                Session.gate = gate
                val next = FileServer(this, pairing, gate, trusted,
                    onPeer = { peer -> lastPeer = android.os.SystemClock.elapsedRealtime(); Session.update { it.copy(peer = peer) } },
                    onProgress = { operation, done, total -> Session.update { it.copy(operation = operation, transferred = done, total = total) } },
                    onComplete = { description -> Session.update { it.copy(operation = "", transferred = 0, total = 0, history = (listOf(description) + it.history).take(12)) } })
                server = next
                Session.revoke = { next.revokeConnections(); Session.update { it.copy(peer = "", trusted = trusted.list()) } }
                bootstrap = BootstrapServer(pairing.certificate)
                discovery = DiscoveryServer(pairing.id)
                if (!alive) { next.stop(); bootstrap?.stop(); discovery?.stop(); gate.close(); return@execute }
                val folder = File(cacheDir, "pairing").apply { mkdirs() }
                File(folder, "conexao-wifi.json").writeText(pairing.document())
                File(folder, "certificado.pem").writeText(pairing.certificate)
                Session.update { it.copy(starting = false, active = true, pairing = pairing, code = gate.code, trusted = trusted.list()) }
                try { next.run() } finally {
                    runCatching { next.stop() }; runCatching { bootstrap?.stop() }; runCatching { discovery?.stop() }
                    gate.close(); server = null; bootstrap = null; discovery = null
                }
              }
            } catch (error: Exception) {
                if (alive) {
                    val message = if (error is java.net.BindException) "A porta de conexão já está em uso. Encerre a outra sessão e tente novamente." else "Não foi possível iniciar a conexão. ${error.message.orEmpty()}"
                    Session.update { it.copy(starting = false, active = false, error = message) }
                    Handler(Looper.getMainLooper()).post { stopSelf() }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        alive = false
        monitor.removeCallbacks(networkWatch)
        Session.gate?.close(); Session.gate = null
        runCatching { bootstrap?.stop() }; bootstrap = null
        runCatching { discovery?.stop() }; discovery = null
        Session.revoke = null
        runCatching { server?.stop() }
        server = null
        executor.shutdownNow()
        File(cacheDir, "pairing/conexao-wifi.json").delete()
        File(cacheDir, "pairing/certificado.pem").delete()
        Session.update { it.copy(active = false, starting = false, pairing = null, peer = "", operation = "", transferred = 0, total = 0, code = "", pending = null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
