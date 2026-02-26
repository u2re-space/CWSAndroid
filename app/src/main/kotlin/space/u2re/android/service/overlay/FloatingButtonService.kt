package space.u2re.service.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.content.ContextCompat
import space.u2re.service.MainActivity
import space.u2re.service.R

class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingButton: Button? = null
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            stopSelf()
            return
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 240
        }

        val button = Button(this).apply {
            text = "CWS"
            setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.white))
            setBackgroundColor(0xE62D3142.toInt())
            setOnClickListener {
                launchApp()
            }
            setPadding(24, 12, 24, 12)
            setOnTouchListener(buttonDragListener)
        }
        try {
            windowManager?.addView(button, params)
            floatingButton = button
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private val buttonDragListener = View.OnTouchListener { view, event ->
        val layoutParams = params ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()
                layoutParams.x = initialX - deltaX
                layoutParams.y = initialY + deltaY
                windowManager?.updateViewLayout(view, layoutParams)
                true
            }

            MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.abs(event.rawX - initialTouchX) > 12f || kotlin.math.abs(event.rawY - initialTouchY) > 12f
                if (!moved) {
                    view.performClick()
                }
                true
            }

            else -> false
        }
    }

    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun windowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        floatingButton?.let {
            windowManager?.removeViewImmediate(it)
        }
        floatingButton = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingButtonService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }
    }
}

