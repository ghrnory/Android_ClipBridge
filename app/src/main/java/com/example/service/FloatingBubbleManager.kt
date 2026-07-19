package com.example.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.TextView
import android.util.Log

class FloatingBubbleManager(private val context: Context, private val syncService: ClipboardSyncService) {

    companion object {
        private const val TAG = "FloatingBubbleManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    fun showBubble() {
        if (bubbleView != null) return

        val dpToPx = { dp: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        // Create the root container
        bubbleView = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        // Create the circular button
        val size = dpToPx(56)
        val button = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
            
            // Background: Circle with primary color (purple/blue) and white border
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF6650A4.toInt()) // Theme Primary Purple40
                setStroke(dpToPx(2), 0xFFFFFFFF.toInt()) // White border
            }
            background = shape
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpToPx(6).toFloat()
            }
        }

        // Icon inside button
        val iconSize = dpToPx(28)
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            // Use standard Android sync icon
            setImageResource(android.R.drawable.ic_popup_sync)
            setColorFilter(Color.WHITE)
        }
        button.addView(icon)

        // Success Indicator Badge (hidden by default)
        val badgeSize = dpToPx(20)
        val badge = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            val badgeShape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4CAF50.toInt()) // Green color
                setStroke(dpToPx(1), Color.WHITE)
            }
            background = badgeShape
            text = "✓"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        button.addView(badge)

        bubbleView?.addView(button)

        // Window Manager Layout Parameters
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // Handle Touch and Dragging
        button.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val layoutParams = params ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        button.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        button.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        if (!isDragging) {
                            performClipboardSync(icon, badge)
                        } else {
                            snapToEdge()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                        }
                        
                        if (isDragging) {
                            layoutParams.x = initialX + dx.toInt()
                            layoutParams.y = initialY + dy.toInt()
                            try {
                                windowManager.updateViewLayout(bubbleView, layoutParams)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating view layout", e)
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay bubble view", e)
        }
    }

    private fun snapToEdge() {
        val layoutParams = params ?: return
        val root = bubbleView ?: return
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        
        val targetX = if (layoutParams.x + root.width / 2 < screenWidth / 2) {
            0
        } else {
            screenWidth - root.width
        }
        
        val startX = layoutParams.x
        val handler = Handler(Looper.getMainLooper())
        var step = 0
        val duration = 10
        val runnable = object : Runnable {
            override fun run() {
                if (step < duration) {
                    val progress = step.toFloat() / duration
                    layoutParams.x = (startX + (targetX - startX) * progress).toInt()
                    try {
                        windowManager.updateViewLayout(root, layoutParams)
                    } catch (e: Exception) {
                        return
                    }
                    step++
                    handler.postDelayed(this, 16)
                } else {
                    layoutParams.x = targetX
                    try {
                        windowManager.updateViewLayout(root, layoutParams)
                    } catch (e: Exception) {}
                }
            }
        }
        handler.post(runnable)
    }

    private fun performClipboardSync(icon: ImageView, badge: TextView) {
        try {
            // Trigger QuickSyncActivity with ACTION_SYNC_TO_PC to read the clipboard in foreground context
            val intent = Intent(context, com.example.ui.QuickSyncActivity::class.java).apply {
                action = "com.example.ACTION_SYNC_TO_PC"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)

            // Show success badge and animation to give immediate feedback
            badge.visibility = View.VISIBLE
            badge.scaleX = 0f
            badge.scaleY = 0f
            badge.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            
            icon.animate().rotationBy(360f).setDuration(500).withEndAction {
                Handler(Looper.getMainLooper()).postDelayed({
                    badge.animate().scaleX(0f).scaleY(0f).setDuration(200).withEndAction {
                        badge.visibility = View.GONE
                    }.start()
                }, 1500)
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QuickSyncActivity from bubble", e)
            Toast.makeText(context, "فشل تشغيل مزامنة الحافظة", Toast.LENGTH_SHORT).show()
        }
    }

    fun dismissBubble() {
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView)
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing bubble", e)
            }
            bubbleView = null
            params = null
        }
    }
}
