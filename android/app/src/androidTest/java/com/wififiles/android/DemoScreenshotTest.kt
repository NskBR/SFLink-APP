package com.wififiles.android

import android.content.Intent
import android.graphics.Bitmap
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.net.ssl.SSLContext

/** Documentation only: no real connection, stored authorization or server is created. */
@RunWith(AndroidJUnit4::class)
class DemoScreenshotTest {
    @Test fun captureFictionalConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            Session.state = SessionState(active = true, peer = "PC de demonstração", trusted = listOf(TrustedPeer("f".repeat(64), "PC de demonstração", "DEMO-NOT-A-CREDENTIAL")),
                pairing = Pairing(listOf("192.0.2.20"), "DEMO-NOT-A-CREDENTIAL", "", "", SSLContext.getInstance("TLS"), "demo"))
            val badge = TextView(activity).apply { text = "DEMONSTRAÇÃO · DADOS FICTÍCIOS"; setTextColor(0xFF20E3AD.toInt()); setBackgroundColor(0xFF09251D.toInt()); textSize = 11f; gravity = Gravity.CENTER; setPadding(12, 12, 12, 12) }
            activity.addContentView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = (104 * activity.resources.displayMetrics.density).toInt() })
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(1200)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val output = File(context.cacheDir, "documentation/android-demo.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        instrumentation.runOnMainSync { Session.state = SessionState(); activity.finish() }
    }
}
