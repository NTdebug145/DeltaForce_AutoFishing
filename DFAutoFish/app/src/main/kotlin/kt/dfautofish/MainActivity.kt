package kt.dfautofish

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var mpm: MediaProjectionManager

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { requestCaptureOrStart() }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, FloatingWindowService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "需要授予屏幕录制权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            } else requestCaptureOrStart()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestCaptureOrStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            return
        }
        // Android 11+ 使用无障碍 takeScreenshot，无需录屏权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = Intent(this, FloatingWindowService::class.java)
            ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            // Android 10 及以下走 MediaProjection
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }
}