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
    val info = serviceInfo
    Log.d(TAG, "serviceInfo: $info")
}

    override fun onDestroy() {
        Log.d(TAG, "AccessibilityService destroyed")
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun clickAt(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "clickAt request: x=$x y=$y")

        if (x < 0 || y < 0) {
            Log.e(TAG, "clickAt: invalid coords")
            onResult?.invoke(false)
            return
        }

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()

        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    Log.d(TAG, "dispatchGesture COMPLETED at ($x,$y)")
                    onResult?.invoke(true)
                }
                override fun onCancelled(d: GestureDescription?) {
                    Log.d(TAG, "dispatchGesture CANCELLED at ($x,$y)")
                    onResult?.invoke(false)
                }
            },
            null
        )

        Log.d(TAG, "dispatchGesture returned: $ok")
        if (!ok) onResult?.invoke(false)
    }
}