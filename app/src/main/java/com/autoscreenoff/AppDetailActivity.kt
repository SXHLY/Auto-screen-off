package com.autoscreenoff

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.appbar.MaterialToolbar

/**
 * 第二页（Material 3）：单个应用的详细息屏配置。
 * - 专属无操作时长（覆盖全局默认，如 B 站长视频设 60 分钟）
 * - 当前生效时长展示
 * - 从息屏列表移除
 */
class AppDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_NAME = "name"
    }

    private lateinit var store: SettingsStore
    private lateinit var pkg: String
    private lateinit var name: String

    private lateinit var picker: NumberPicker
    private lateinit var effectValue: TextView
    private lateinit var effectHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: run {
            finish()
            return
        }
        name = intent.getStringExtra(EXTRA_NAME) ?: pkg
        store = SettingsStore(this)

        // ---- 顶部工具栏 ----
        val toolbar = MaterialToolbar(this).apply {
            title = name
            navigationIcon = ContextCompat.getDrawable(this@AppDetailActivity, R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
            setPadding(0, dp(8), 0, 0)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(28))
        }
        root.addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buildContent(root)

        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(scroll)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        elevation = 0f
        setCardBackgroundColor(0xFFFFFFFF.toInt())
        setContentPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun icon(res: Int, size: Int = 22): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(0xFF1E88E5.toInt())
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun textView(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF9AA0A6.toInt())
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun buildContent(root: LinearLayout) {
        // ---- 应用信息 ----
        val infoCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val infoContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoContent.addView(textView(name, 20f, 0xFF1A1C1E.toInt(), bold = true))
        infoContent.addView(textView(pkg, 12f, 0xFF9AA0A6.toInt()).apply {
            setPadding(0, dp(2), 0, 0)
        })
        infoCard.addView(infoContent)
        root.addView(infoCard)

        // ---- 当前生效规则 ----
        val ruleCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val ruleContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@AppDetailActivity, R.drawable.bg_icon_circle)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        iconBox.addView(icon(R.drawable.ic_schedule, 20))
        ruleContent.addView(iconBox)
        val ruleTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ruleTexts.addView(textView("当前生效规则", 13f, 0xFF9AA0A6.toInt()))
        effectValue = textView("", 18f, 0xFF1A1C1E.toInt(), bold = true)
        ruleTexts.addView(effectValue)
        ruleContent.addView(ruleTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
        })
        ruleCard.addView(ruleContent)
        root.addView(ruleCard)

        // ---- 专属时长设置 ----
        val setCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val setContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContent.addView(textView("专属无操作时长", 16f, 0xFF1A1C1E.toInt(), bold = true))
        setContent.addView(hintText("超过该时长无任何滑动/触摸才触发息屏 · 长视频/影视可放宽（如 60 分钟），短视频可收紧"))

        val pickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        pickerRow.addView(textView("无操作", 15f, 0xFF1A1C1E.toInt()))
        picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 120
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        pickerRow.addView(picker, LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT))
        pickerRow.addView(textView("分钟后息屏", 15f, 0xFF1A1C1E.toInt()))
        setContent.addView(pickerRow)

        val btnApply = MaterialButton(this).apply {
            text = "应用此专属时长"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        btnApply.setOnClickListener {
            val timeouts = HashMap(store.getAppTimeoutMinutes())
            timeouts[pkg] = picker.value
            store.setAppTimeoutMinutes(timeouts)
            refreshEffect()
            Toast.makeText(this, "已设置：无操作 ${picker.value} 分钟后息屏", Toast.LENGTH_SHORT).show()
        }
        setContent.addView(btnApply)

        val btnDefault = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "恢复为全局默认"
            setTextColor(0xFF1E88E5.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnDefault.setOnClickListener {
            val timeouts = HashMap(store.getAppTimeoutMinutes())
            timeouts.remove(pkg)
            store.setAppTimeoutMinutes(timeouts)
            refreshEffect()
            Toast.makeText(this, "已恢复为全局默认（${store.idleMinutes} 分钟）", Toast.LENGTH_SHORT).show()
        }
        setContent.addView(btnDefault)

        setCard.addView(setContent)
        root.addView(setCard)

        // ---- 移除 ----
        effectHint = textView("", 12f, 0xFF9AA0A6.toInt())
        root.addView(effectHint)

        val btnRemove = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "从息屏列表移除"
            setTextColor(0xFFD32F2F.toInt())
            setIconResource(R.drawable.ic_delete)
            iconTint = ColorStateList.valueOf(0xFFD32F2F.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        btnRemove.setOnClickListener {
            store.targetApps = store.targetApps - pkg
            val timeouts = HashMap(store.getAppTimeoutMinutes())
            timeouts.remove(pkg)
            store.setAppTimeoutMinutes(timeouts)
            Toast.makeText(this, "已从息屏列表移除", Toast.LENGTH_SHORT).show()
            finish()
        }
        root.addView(btnRemove)

        refreshEffect()
    }

    /** 刷新"当前生效规则"展示 */
    private fun refreshEffect() {
        val custom = store.getAppTimeoutMinutes()[pkg]
        val global = store.idleMinutes
        if (custom != null) {
            effectValue.text = "无操作 $custom 分钟"
            effectValue.setTextColor(0xFF1E88E5.toInt())
            effectHint.text = "该应用使用专属设置，不随全局默认变化"
        } else {
            effectValue.text = "无操作 $global 分钟（全局默认）"
            effectValue.setTextColor(0xFF1A1C1E.toInt())
            effectHint.text = "该应用跟随全局默认时长"
        }
        picker.value = custom ?: global
    }
}
