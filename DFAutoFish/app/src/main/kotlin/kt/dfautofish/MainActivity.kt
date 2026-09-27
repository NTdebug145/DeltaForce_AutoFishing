package kt.dfautofish

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
    ) { requestCapture() }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, FloatingWindowService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "悬浮窗已启动，请切到游戏", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "需要授予屏幕录制权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论允许与否都继续，只是保存路径可能失败 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // API 28 及以下，保存到公共相册需要存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            } else requestCapture()
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            return
        }
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}