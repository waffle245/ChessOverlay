package com.lilbig.chessoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var btn: Button
    private lateinit var mpm: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, OverlayService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            status.text = "✅ Running! Switch to chess.com"
            btn.isEnabled = false
        } else {
            status.text = "❌ Screen capture denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(60, 100, 60, 60)
            setBackgroundColor(0xFF1a1a2e.toInt())
        }
        val title = TextView(this).apply {
            text = "♞ ChessOverlay"
            textSize = 30f
            setTextColor(0xFF00C8FF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val desc = TextView(this).apply {
            text = "\nStockfish depth-15 best move arrow\nover chess.com app\n\n!! Brilliant  ! Great  ★ Best\n○ Good  ?! Inaccuracy\n? Mistake  ?? Blunder\n"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Tap Start to begin"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        btn = Button(this).apply {
            text = "Start Overlay"
            setOnClickListener { handleStart() }
        }
        root.addView(title)
        root.addView(desc)
        root.addView(status)
        root.addView(btn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.canDrawOverlays(this))
            status.text = "⚠ Grant Display over other apps first"
        else
            status.text = "✅ Ready — tap Start"
    }

    private fun handleStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
