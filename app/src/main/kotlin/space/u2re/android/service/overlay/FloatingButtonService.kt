package space.u2re.service.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import androidx.core.content.ContextCompat
import space.u2re.service.QuickActionActivity
import space.u2re.service.R

class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingButton: Button? = null
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private var buttonWidthPx = 0
    private var buttonHeightPx = 0
    private var isEdgeHidden = false
    private var edgePeekPx = 0
    private val hideThresholdPx by lazy { (28 * resources.displayMetrics.density).toInt() }
    private val buttonSidePx by lazy { dpToPx(50) }
    private var touchSlopPx = 0

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
            buttonSidePx,
            buttonSidePx,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 16
            y = 240
        }
        edgePeekPx = (14 * resources.displayMetrics.density).toInt()
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop
        refreshScreenBounds()

        val button = Button(this).apply {
            text = resolveButtonLabel()
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.white))
            setBackgroundColor(0xE62D3142.toInt())
            minimumHeight = buttonSidePx
            minimumWidth = buttonSidePx
            setPadding(4, 4, 4, 4)
            setOnTouchListener(buttonDragListener)
        }
        val layoutListener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                buttonWidthPx = button.width
                buttonHeightPx = button.height
                if (buttonWidthPx > 0 && buttonHeightPx > 0) {
                    button.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        }
        try {
            windowManager?.addView(button, params)
            floatingButton = button
            button.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun refreshScreenBounds() {
        val manager = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            screenWidthPx = bounds.width()
            screenHeightPx = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = manager.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            screenWidthPx = metrics.widthPixels
            screenHeightPx = metrics.heightPixels
        }
    }

    private val buttonDragListener = View.OnTouchListener { view, event ->
        val layoutParams = params ?: return@OnTouchListener false
        val button = floatingButton ?: return@OnTouchListener false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isEdgeHidden) {
                    revealFromEdge(layoutParams)
                }
                button.text = resolveButtonLabel()
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()
                layoutParams.x = initialX + deltaX
                layoutParams.y = initialY + deltaY
                windowManager?.updateViewLayout(view, layoutParams)
                true
            }

            MotionEvent.ACTION_UP -> {
                refreshScreenBounds()
                ensureButtonInBounds(layoutParams)
                val moved = kotlin.math.abs(event.rawX - initialTouchX) > touchSlopPx || kotlin.math.abs(event.rawY - initialTouchY) > touchSlopPx
                if (!moved) {
                    launchQuickAction()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                false
            }

            else -> false
        }
    }

    private fun ensureButtonInBounds(layoutParams: WindowManager.LayoutParams) {
        val width = if (buttonWidthPx > 0) buttonWidthPx else fallbackButtonWidthPx()
        val height = if (buttonHeightPx > 0) buttonHeightPx else 0
        refreshScreenBounds()

        val safeX = layoutParams.x.coerceIn(0, maxOf(0, screenWidthPx - width))
        val safeY = if (screenHeightPx > 0 && height > 0) {
            layoutParams.y.coerceIn(0, screenHeightPx - height)
        } else {
            layoutParams.y
        }

        val nearLeft = safeX <= hideThresholdPx
        val nearRight = (safeX + width) >= (screenWidthPx - hideThresholdPx)

        when {
            nearLeft -> hideToLeft(layoutParams, width)
            nearRight -> hideToRight(layoutParams, width)
            else -> {
                layoutParams.x = safeX
                layoutParams.y = safeY
                isEdgeHidden = false
                windowManager?.updateViewLayout(floatingButton, layoutParams)
            }
        }
    }

    private fun hideToLeft(layoutParams: WindowManager.LayoutParams, width: Int) {
        layoutParams.x = -maxOf(0, width - edgePeekPx)
        isEdgeHidden = true
        windowManager?.updateViewLayout(floatingButton, layoutParams)
    }

    private fun hideToRight(layoutParams: WindowManager.LayoutParams, width: Int) {
        layoutParams.x = screenWidthPx - edgePeekPx
        isEdgeHidden = true
        windowManager?.updateViewLayout(floatingButton, layoutParams)
    }

    private fun revealFromEdge(layoutParams: WindowManager.LayoutParams) {
        refreshScreenBounds()
        val width = if (buttonWidthPx > 0) buttonWidthPx else fallbackButtonWidthPx()
        val nearLeft = layoutParams.x < edgePeekPx
        if (nearLeft) {
            layoutParams.x = 0
        } else {
            layoutParams.x = maxOf(0, screenWidthPx - width)
        }
        isEdgeHidden = false
        windowManager?.updateViewLayout(floatingButton, layoutParams)
    }

    private fun fallbackButtonWidthPx(): Int = buttonSidePx

    private fun launchQuickAction() {
        val currentText = getClipboardTextOrEmpty()
        val intent = Intent(this, QuickActionActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(QuickActionActivity.EXTRA_MODE, QuickActionActivity.MODE_FLOATING)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            type = "text/plain"
            if (currentText.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, currentText)
            }
        }
        startActivity(intent)
    }

    private fun resolveButtonLabel(): String {
        return getString(R.string.floating_button_label_share)
    }

    private fun getClipboardTextOrEmpty(): String {
        return try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
            val clip = manager.primaryClip ?: return ""
            if (clip.itemCount <= 0) return ""
            clip.getItemAt(0).coerceToText(this).toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

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

