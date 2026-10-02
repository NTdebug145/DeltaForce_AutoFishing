package kt.dfautofish

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AccessibilityClicker : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AccessibilityClicker? = null
            private set
        private const val TAG = "DFAutoFish"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onDestroy() {
        Log.d(TAG, "AccessibilityService destroyed")
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * 在屏幕绝对坐标 (x, y) 处注入一次点击。
     * @param onResult 手势结束后回调，true 表示 onCompleted
     */
    fun clickAt(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        if (x < 0 || y < 0) {
            onResult?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)   // 保证路径合法
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()

        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    Log.d(TAG, "gesture COMPLETED ($x,$y)")
                    onResult?.invoke(true)
                }
                override fun onCancelled(d: GestureDescription?) {
                    Log.d(TAG, "gesture CANCELLED ($x,$y)")
                    onResult?.invoke(false)
                }
            },
            null
        )

        if (!ok) {
            Log.e(TAG, "dispatchGesture returned false")
            onResult?.invoke(false)
        }
    }
}