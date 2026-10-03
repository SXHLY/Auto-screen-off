package com.autoscreenoff

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * 息屏前确认悬浮窗：
 * - 主通道 TYPE_ACCESSIBILITY_OVERLAY：本应用无障碍服务运行期间系统始终允许显示，
 *   且不受「系统强制隐藏非系统悬浮窗」策略影响（TYPE_APPLICATION_OVERLAY 在部分
 *   锁屏/解锁状态下会被系统静默隐藏，导致用户看不到倒计时却被直接锁屏）；
 * - 降级通道 TYPE_APPLICATION_OVERLAY（需悬浮窗权限）；
 * - 倒计时结束无人取消 → 锁定屏幕
 * - 触摸屏幕任意位置或点击按钮 → 取消（重置无操作计时）
 */
object ConfirmOverlay {

    private const val TAG = "AutoScreenOff"

    /** 倒计时秒数 */
    const val COUNTDOWN_SECONDS = 10

    /** 倒计时期间：持续无规律运动达到该时长 → 判定用户仍清醒，自动取消（等同点击「我还醒着」） */
    private const val CANCEL_BY_MOTION_MS = 1_500L

    /** 运动检查周期（与 MotionMonitor 的采样节流一致） */
    private const val MOTION_CHECK_INTERVAL_MS = 400L

    @Volatile
    var isShowing: Boolean = false
        private set

    private var wm: WindowManager? = null
    private var view: View? = null
    private var context: Context? = null
    private var remaining = COUNTDOWN_SECONDS
    private var finished = false
    private var countdownText: TextView? = null

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (finished || view == null) return
            remaining--
            if (remaining <= 0) {
                finished = true
                Log.i(TAG, "countdown finished, locking")
                ScreenOffController.lockNow(context ?: return)
                dismiss()
            } else {
                countdownText?.text = "$remaining 秒后自动息屏"
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    /**
     * 倒计时期间持续监听运动：无规律运动（上下甩动/来回偏转）连续 ≥1.5 秒
     * 说明用户仍清醒握持手机 → 自动取消锁定，与手动点击取消等价。
     * （呼吸级规律微动无法累积连续时长，不会误触发，见 MotionMonitor.isSustainedMotion）
     */
    private val motionTick = object : Runnable {
        override fun run() {
            if (finished || view == null) return
            if (MotionMonitor.isSustainedMotion(CANCEL_BY_MOTION_MS)) {
                Log.i(TAG, "countdown cancelled by sustained motion (user awake)")
                cancel()
                return
            }
            handler.postDelayed(this, MOTION_CHECK_INTERVAL_MS)
        }
    }

    /** 显示确认悬浮窗；两条通道都失败（无权限等）时返回 false（调用方直接执行关闭链路） */
    fun show(ctx: Context, seconds: Int = COUNTDOWN_SECONDS): Boolean {
        if (isShowing) return true
        // 无障碍服务运行中（show 仅由 evaluate 调用，此时必然已连接）→ 无障碍通道无需任何权限
        if (showWithType(ctx, seconds, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)) {
            return true
        }
        // 降级：普通悬浮窗（需「显示在其他应用上层」权限）。不做前置检查，系统是最终裁决
        return try {
            showWithType(ctx, seconds, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } catch (e: Exception) {
            Log.w(TAG, "show overlay failed (no permission?)", e)
            false
        }
    }

    private fun showWithType(ctx: Context, seconds: Int, windowType: Int): Boolean = try {
        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Service 上下文的主题不是 Material 主题（MaterialButton 会拒绝构建），
        // 用 ContextThemeWrapper 强制套用 App 的 Material 3 主题
        val themedCtx = ContextThemeWrapper(ctx, R.style.Theme_AutoScreenOff)
        val overlayView = buildView(themedCtx)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(overlayView, lp)
        wm = windowManager
        view = overlayView
        context = ctx
        isShowing = true
        finished = false
        remaining = seconds.coerceAtLeast(1)
            countdownText?.text = "$remaining 秒后自动息屏"
            handler.post(tick)
            handler.post(motionTick)
            Log.i(TAG, "confirm overlay shown (type=$windowType, ${remaining}s)")
            true
    } catch (e: Exception) {
        Log.w(TAG, "showWithType $windowType failed", e)
        false
    }

    /** 关闭悬浮窗（锁屏后 / 用户取消 / 屏幕熄灭时调用） */
    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        finished = true
        handler.removeCallbacks(tick)
        handler.removeCallbacks(motionTick)
        try {
            view?.let { wm?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "dismiss overlay failed", e)
        }
        view = null
        wm = null
        context = null
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun buildView(ctx: Context): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xE6101418.toInt())
            setPadding(dp(ctx, 36), 0, dp(ctx, 36), 0)
        }

        root.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_moon)
            setColorFilter(0xFF8AB4F8.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56))
        })

        root.addView(TextView(ctx).apply {
            text = "检测到您长时间无操作"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }.apply {
            setPadding(0, dp(ctx, 24), 0, 0)
        })

        countdownText = TextView(ctx).apply {
            text = "$COUNTDOWN_SECONDS 秒后自动息屏"
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(ctx, 16), 0, dp(ctx, 8))
        }
        root.addView(countdownText)

        root.addView(TextView(ctx).apply {
            text = "如果您还在观看，请点击屏幕任意位置或下方按钮取消"
            textSize = 14f
            setTextColor(0xFFB8C2CC.toInt())
            gravity = Gravity.CENTER
        })

        root.addView(MaterialButton(ctx).apply {
            text = "我还醒着，取消息屏"
            backgroundTintList = ColorStateList.valueOf(0xFF2E7D32.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 28) }
        }.also { btn ->
            btn.setOnClickListener { cancel() }
        })

        // 触摸任意位置 = 用户还醒着 → 取消
        root.setOnTouchListener { _, _ ->
            cancel()
            true
        }
        return root
    }

    private fun cancel() {
        if (finished) return
        finished = true
        ScreenOffController.onConfirmCancelled()
        dismiss()
    }
}
