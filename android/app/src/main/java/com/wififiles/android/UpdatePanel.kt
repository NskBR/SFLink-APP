package com.wififiles.android

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun UpdatePanel(activity: Activity, updater: AppUpdater, transferring: Boolean) {
    val muted = Color(0xFF91A1B5)
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1116)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF202830)), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Atualizações do SFLink", fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text("Versão ${BuildConfig.VERSION_NAME} · GitHub Releases", color = muted, fontSize = 12.sp)
            updater.offer?.let { offer -> Text("Versão ${offer.version} disponível", color = Color(0xFF20E3AD), fontSize = 14.sp); if (offer.notes.isNotBlank()) Text(offer.notes.take(1500), color = muted, fontSize = 12.sp) }
            if (updater.downloading) { LinearProgressIndicator(progress = { updater.progress }, modifier = Modifier.fillMaxWidth()); Text("Baixando APK · ${(updater.progress * 100).toInt()}%", color = muted, fontSize = 12.sp) }
            if (updater.message.isNotBlank()) Text(updater.message, color = muted, fontSize = 12.sp)
            if (updater.error.isNotBlank()) Text(updater.error, color = Color(0xFFF3ADB3), fontSize = 12.sp)
            if (updater.downloading) TextButton(onClick = { updater.cancel() }) { Text("Cancelar download") }
            else if (updater.ready) {
                Button(onClick = { updater.install(activity, transferring) }, enabled = !transferring && !updater.busy, modifier = Modifier.fillMaxWidth()) { Text("Instalar atualização") }
                if (transferring) Text("Aguarde as transferências terminarem.", color = muted, fontSize = 12.sp)
            } else {
                OutlinedButton(onClick = { updater.check() }, enabled = !updater.busy, modifier = Modifier.fillMaxWidth()) { Text(if (updater.busy) "Verificando…" else "Verificar atualizações") }
                if (updater.offer != null) Button(onClick = { updater.download() }, enabled = !updater.busy, modifier = Modifier.fillMaxWidth()) { Text("Baixar atualização") }
            }
        }
    }
}
