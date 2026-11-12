package com.blackout.focus.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.blackout.focus.data.reel.ReelPlatform
import com.blackout.focus.data.reel.ReelRepository
import com.blackout.focus.ui.MainActivity
import java.util.Locale
import kotlin.math.abs

/**
 * Manages the compact floating Reel Counter pill without starting any ForegroundService
 * (zero notifications, zero Dynamic Island popups, and zero blinking).
 */
class FloatingReelHudManager(
    private val context: Context,
    private val reelRepository: ReelRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var pillContainer: LinearLayout? = null
    private var statusDotView: View? = null
    private var counterTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isAttached = false
    private var latestCount = 0
    private var latestPlatform: ReelPlatform? = null

    fun show(platform: ReelPlatform, count: Int) {
        latestPlatform = platform
        latestCount = count
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showInternal() }
        } else {
            showInternal()
        }
    }

    fun updateCount(count: Int) {
        latestCount = count
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updatePillText() }
        } else {
            updatePillText()
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideInternal() }
        } else {
            hideInternal()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInternal() {
        if (isAttached && pillContainer != null) {
            if (pillContainer?.visibility != View.VISIBLE) {
                pillContainer?.visibility = View.VISIBLE
            }
            updatePillText()
            return
        }

        val density = context.resources.displayMetrics.density
        val savedPos = reelRepository.getHudPosition()

        val overlayType = if (Settings.canDrawOverlays(context)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = savedPos?.first ?: (14 * density).toInt()
            y = savedPos?.second ?: (60 * density).toInt()
        }
        layoutParams = params

        val pillBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(Color.parseColor("#E6050505"))
            setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
        }

        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D71921"))
        }

        val dotView = View(context).apply {
            background = dotDrawable
            val size = (6 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (6 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        statusDotView = dotView

        val textView = TextView(context).apply {
            text = String.format(Locale.US, "%03d", latestCount)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
            includeFontPadding = false
        }
        counterTextView = textView

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground
            val padH = (11 * density).toInt()
            val padV = (5 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 6f * density
            addView(dotView)
            addView(textView)
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            val currentParams = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = currentParams.x
                    initialY = currentParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragging = true
                    }
                    currentParams.x = (initialX + dx).coerceAtLeast(0)
                    currentParams.y = (initialY + dy).coerceAtLeast(0)
                    try {
                        windowManager.updateViewLayout(container, currentParams)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val launchIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        try {
                            context.startActivity(launchIntent)
                        } catch (_: Exception) {
                        }
                    } else {
                        reelRepository.saveHudPosition(currentParams.x, currentParams.y)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            pillContainer = container
            isAttached = true
            updatePillText()
        } catch (_: Exception) {
            isAttached = false
        }
    }

    private fun updatePillText() {
        counterTextView?.text = String.format(Locale.US, "%03d", latestCount)
        val limit = reelRepository.dailyLimit.value
        val dotColor = if (latestCount >= limit) {
            Color.parseColor("#D71921")
        } else {
            Color.parseColor("#FFFFFF")
        }
        (statusDotView?.background as? GradientDrawable)?.setColor(dotColor)
    }

    private fun hideInternal() {
        val container = pillContainer
        pillContainer = null
        counterTextView = null
        statusDotView = null
        isAttached = false

        if (container != null) {
            try {
                windowManager.removeViewImmediate(container)
            } catch (_: Exception) {
                try {
                    windowManager.removeView(container)
                } catch (_: Exception) {
                }
            }
        }
    }
}
