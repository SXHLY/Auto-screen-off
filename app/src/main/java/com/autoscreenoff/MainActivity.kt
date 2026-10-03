package com.autoscreenoff

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 第一页（Material 3）：权限状态 + 全局配置 + 已配置息屏的应用列表。
 * - 权限齐全时隐藏"获取权限"入口，仅显示一行绿色就绪状态
 * - 列表中的应用点击进入 AppDetailActivity（第二页）
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_NOTIFICATION_PERMISSION = 100
        private const val REQ_LOCATION_PERMISSION = 101
    }

    private lateinit var store: SettingsStore

    /** 权限卡容器（内容动态重建） */
    private lateinit var permCard: MaterialCardView
    private lateinit var permCardContent: LinearLayout

    private lateinit var masterSwitch: SwitchMaterial
    private lateinit var idlePicker: NumberPicker
    private lateinit var motionSwitch: SwitchMaterial
    private lateinit var drivingSwitch: SwitchMaterial
    private lateinit var confirmSwitch: SwitchMaterial
    private lateinit var confirmSecondsText: TextView
    private lateinit var cancelDelayText: TextView
    private lateinit var closeAppSwitch: SwitchMaterial
    private lateinit var suspendSwitch: SwitchMaterial
    private lateinit var suspendSupportHint: TextView
    private lateinit var windowSwitch: SwitchMaterial
    private lateinit var startPicker: NumberPicker
    private lateinit var endPicker: NumberPicker
    private lateinit var windowRow: LinearLayout

    /** 手动暂停卡片（动态） */
    private lateinit var pauseStatusText: TextView
    private lateinit var btnResume: MaterialButton
    private lateinit var btnSnooze: MaterialButton

    /** 息屏应用列表容器（内容动态重建） */
    private lateinit var appsCardContent: LinearLayout
    private lateinit var appCountText: TextView

    /** 版本与更新卡片 */
    private lateinit var repoStatusText: TextView
    private lateinit var btnCheckUpdate: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        buildUi()
        loadValues()
        refreshPermCard()
        refreshAppList()
        requestNotificationPermission()
        // 总开关此前已开启（如升级安装、开机后）→ 确保前台服务在运行
        MonitorForegroundService.ensureRunning(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 通知以 SINGLE_TOP 打开已运行的 Activity 时走这里；同步 intent 供 onResume 消费
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 从「新版本已下载」通知点入 → 直接调起系统安装器（一次性消费，避免旋转/返回重复弹）
        intent?.getStringExtra(UpdateManager.EXTRA_INSTALL_APK)?.let { path ->
            intent.removeExtra(UpdateManager.EXTRA_INSTALL_APK)
            UpdateManager.installApk(this, path)
        }
        registerAccessibilityStateListener()
        refreshPermCard()
        refreshAppList()
        refreshPauseCard()
        refreshSuspendSupport()
        refreshRepoStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterAccessibilityStateListener()
    }

    /** Android 13+ 请求通知权限（无障碍被关闭时的提醒依赖它） */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION_PERMISSION
            )
        }
    }

    /** 无障碍状态变化时实时刷新界面（无需退出重进） */
    private var accStateListener: AccessibilityStateChangeListener? = null

    private fun registerAccessibilityStateListener() {
        if (accStateListener != null) return
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityStateChangeListener { _ ->
            runOnUiThread {
                refreshPermCard()
                refreshAppList()
            }
        }
        accStateListener = listener
        am.addAccessibilityStateChangeListener(listener)
    }

    private fun unregisterAccessibilityStateListener() {
        accStateListener?.let {
            (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                .removeAccessibilityStateChangeListener(it)
        }
        accStateListener = null
    }

    // ------------------------------------------------------------------
    // UI 构建（Material 3）
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        elevation = 0f
        setCardBackgroundColor(0xFFFFFFFF.toInt())
        strokeWidth = 0
        setContentPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun icon(res: Int, size: Int = 24): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(0xFF5F6368.toInt())
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

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(scroll)

        // ---- 大标题 ----
        root.addView(textView("自动息屏", 26f, 0xFF1A1C1E.toInt(), bold = true))
        root.addView(textView("检测到您睡着时自动锁屏 · 省电省流量", 13f, 0xFF6B7280.toInt()))

        // ---- 权限卡（动态） ----
        permCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
        permCardContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permCard.addView(permCardContent)
        root.addView(permCard)

        // ---- 全局配置卡 ----
        val configCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val configContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 总开关
        masterSwitch = SwitchMaterial(this)
        configContent.addView(switchRow(R.drawable.ic_lock, "启用自动息屏", null, masterSwitch) {
            store.enabled = it
            // 联动常驻前台服务：开启即保活，关闭即释放
            if (it) MonitorForegroundService.ensureRunning(this)
            else MonitorForegroundService.stopIfDisabled(this)
        })

        // 无操作时长（全局默认）
        val idleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        idleRow.addView(icon(R.drawable.ic_schedule))
        idleRow.addView(
            textView("无操作时长", 15f, 0xFF1A1C1E.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14)
            }
        )
        idlePicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 120
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        idlePicker.setOnValueChangedListener { _, _, newVal ->
            store.idleMinutes = newVal
            // 未设专属时长的应用副标题展示的是全局默认值，需同步刷新
            refreshAppList()
        }
        idleRow.addView(idlePicker, LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT))
        idleRow.addView(textView("分钟", 13f, 0xFF6B7280.toInt()))
        configContent.addView(idleRow)
        configContent.addView(hintText("无操作超过该时长才触发 · 长视频应用可在详情页单独放宽"))

        configContent.addView(divider())

        // 运动检测
        motionSwitch = SwitchMaterial(this)
        configContent.addView(switchRow(
            R.drawable.ic_motion, "运动检测", "拿起/走动等明显运动时阻止息屏",
            motionSwitch
        ) { store.motionCheckEnabled = it })

        // 驾驶模式检测（GPS，可选，默认关）
        drivingSwitch = SwitchMaterial(this)
        configContent.addView(switchRow(
            R.drawable.ic_motion, "驾驶模式检测（GPS）",
            "速度≥25km/h 时提醒选择驾驶/乘客模式；驾驶则暂停 10 分钟并自动复查",
            drivingSwitch
        ) { setDrivingCheck(it) })

        // 息屏前确认
        confirmSwitch = SwitchMaterial(this)
        configContent.addView(switchRow(
            R.drawable.ic_check_circle, "息屏前弹窗确认", "倒计时期间碰一下屏幕即可取消",
            confirmSwitch
        ) { store.confirmBeforeLock = it })

        // 确认倒计时秒数（点击弹选择）
        val confirmSecondsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { showConfirmSecondsDialog() }
        }
        confirmSecondsRow.addView(icon(R.drawable.ic_schedule))
        confirmSecondsRow.addView(
            textView("确认倒计时", 15f, 0xFF1A1C1E.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14)
            }
        )
        confirmSecondsText = textView("", 14f, 0xFF1E88E5.toInt())
        confirmSecondsRow.addView(confirmSecondsText)
        configContent.addView(confirmSecondsRow)
        configContent.addView(hintText("倒计时结束无人响应才执行「关闭应用 + 锁屏」"))

        // 取消后重新监测延迟（点击弹选择）
        val cancelDelayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { showCancelDelayDialog() }
        }
        cancelDelayRow.addView(icon(R.drawable.ic_play))
        cancelDelayRow.addView(
            textView("取消后重新监测", 15f, 0xFF1A1C1E.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14)
            }
        )
        cancelDelayText = textView("", 14f, 0xFF1E88E5.toInt())
        cancelDelayRow.addView(cancelDelayText)
        configContent.addView(cancelDelayRow)
        configContent.addView(hintText("「我还醒着」或倒计时期间检测到运动取消后，隔多久重新开始监测（取消时清醒、稍后睡着的场景）"))

        configContent.addView(divider())

        // 锁屏后关闭视频应用
        closeAppSwitch = SwitchMaterial(this)
        configContent.addView(switchRow(
            R.drawable.ic_close, "锁屏后关闭视频应用", "退回桌面并结束后台进程，彻底停止播放",
            closeAppSwitch
        ) { store.closeAppEnabled = it })

        // 强力暂停模式（默认关；仅设备所有者可用，普通手机由 refreshSuspendSupport 禁用）
        suspendSwitch = SwitchMaterial(this)
        configContent.addView(switchRow(
            R.drawable.ic_stop, "强力暂停模式", "冻结应用全部组件（解锁后自动恢复，慎用）",
            suspendSwitch
        ) { store.suspendAppEnabled = it })
        suspendSupportHint = hintText("")
        configContent.addView(suspendSupportHint)

        configContent.addView(divider())

        // 时间段
        windowSwitch = SwitchMaterial(this)
        configContent.addView(switchRow(
            R.drawable.ic_schedule, "仅在时间段内生效", "支持跨午夜，如 23:00 – 07:00",
            windowSwitch
        ) { checked ->
            store.windowEnabled = checked
            updateWindowVisibility()
        })

        windowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        startPicker = hourPicker(store.startHour) { store.startHour = it }
        endPicker = hourPicker(store.endHour) { store.endHour = it }
        windowRow.addView(textView("开始", 13f, 0xFF6B7280.toInt()))
        windowRow.addView(startPicker, LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT))
        windowRow.addView(textView("时 · 结束", 13f, 0xFF6B7280.toInt()))
        windowRow.addView(endPicker, LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT))
        windowRow.addView(textView("时", 13f, 0xFF6B7280.toInt()))
        configContent.addView(windowRow)

        configCard.addView(configContent)
        root.addView(configCard)

        // ---- 息屏应用卡 ----
        val appsCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val appsContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val appsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        appsHeader.addView(icon(R.drawable.ic_apps))
        appsHeader.addView(
            textView("息屏应用", 16f, 0xFF1A1C1E.toInt(), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14)
            }
        )
        appCountText = textView("", 12f, 0xFF9AA0A6.toInt())
        appsHeader.addView(appCountText)
        appsContent.addView(appsHeader)

        appsCardContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appsContent.addView(appsCardContent)

        val btnAdd = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "添加应用"
            setIconResource(R.drawable.ic_apps)
            iconTint = ColorStateList.valueOf(0xFF1E88E5.toInt())
            setTextColor(0xFF1E88E5.toInt())
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        btnAdd.setOnClickListener { showAppDialog() }
        appsContent.addView(btnAdd)

        val btnClear = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "清除全部专属时长"
            setTextColor(0xFF6B7280.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnClear.setOnClickListener {
            store.setAppTimeoutMinutes(emptyMap())
            refreshAppList()
            Toast.makeText(this, "已清除全部应用专属时长", Toast.LENGTH_SHORT).show()
        }
        appsContent.addView(btnClear)

        appsCard.addView(appsContent)
        root.addView(appsCard)

        // ---- 手动暂停卡 ----
        val pauseCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val pauseContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val pauseHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pauseHeader.addView(icon(R.drawable.ic_pause))
        pauseHeader.addView(
            textView("手动暂停", 16f, 0xFF1A1C1E.toInt(), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14)
            }
        )
        pauseContent.addView(pauseHeader)

        pauseStatusText = textView("", 13f, 0xFF6B7280.toInt())
        pauseContent.addView(pauseStatusText.apply { setPadding(0, dp(6), 0, dp(6)) })

        val pauseBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnSnooze = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "暂停 30 分钟"
            setTextColor(0xFF1E88E5.toInt())
            minHeight = dp(44)
            setMinimumHeight(dp(44))
            insetTop = 0
            insetBottom = 0
        }
        btnSnooze.setOnClickListener { showSnoozeDialog() }
        pauseBtnRow.addView(btnSnooze, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        btnResume = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "恢复监测"
            setTextColor(0xFF2E7D32.toInt())
            minHeight = dp(44)
            setMinimumHeight(dp(44))
            insetTop = 0
            insetBottom = 0
        }
        btnResume.setOnClickListener {
            store.snoozeUntilMs = 0L
            refreshPauseCard()
            Toast.makeText(this, "已恢复监测", Toast.LENGTH_SHORT).show()
        }
        pauseBtnRow.addView(btnResume, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8)
        })
        pauseContent.addView(pauseBtnRow)
        pauseContent.addView(hintText("暂停期间不会触发关闭与锁屏 · 通知栏/快捷磁贴也可操作"))

        pauseCard.addView(pauseContent)
        root.addView(pauseCard)

        // ---- 测试 ----
        val btnTest = MaterialButton(this).apply {
            text = "立即测试锁定"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        btnTest.setOnClickListener {
            val locked = ScreenOffController.lockNow(this)
            Toast.makeText(
                this,
                if (locked) "已执行锁屏" else "锁屏失败：请先激活设备管理员",
                Toast.LENGTH_SHORT
            ).show()
        }
        root.addView(btnTest)

        // ---- 版本与更新（仓库监听） ----
        val updateCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val updateContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updateContent.addView(textView("版本与更新", 16f, 0xFF1A1C1E.toInt(), bold = true))
        val verName = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        updateContent.addView(textView("当前版本 v$verName", 13f, 0xFF6B7280.toInt()).apply {
            setPadding(0, dp(6), 0, dp(2))
        })
        repoStatusText = textView("", 13f, 0xFF9AA0A6.toInt())
        updateContent.addView(repoStatusText)

        btnCheckUpdate = MaterialButton(this).apply {
            text = "检查更新"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        btnCheckUpdate.setOnClickListener { checkUpdateManually() }
        updateContent.addView(btnCheckUpdate)

        val btnRepo = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "设置更新仓库（GitHub / Gitee）"
            setTextColor(0xFF1E88E5.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnRepo.setOnClickListener { showRepoDialog() }
        updateContent.addView(btnRepo)
        updateContent.addView(hintText("更新来源 GitHub 仓库 Release · 仅在点「检查更新」时联网，平时不主动触发 · 不上传任何数据"))
        updateCard.addView(updateContent)
        root.addView(updateCard)

        root.addView(textView(
            "工作原理：目标应用在前台 + 超过无操作时长 + 无明显运动 + 在时间段内 → 弹窗倒计时（可取消）→ 退回桌面、结束视频应用后台进程并锁屏。视频自动刷新的进度、弹幕不算操作。",
            12f, 0xFF9AA0A6.toInt()
        ).apply { setPadding(0, dp(12), 0, 0) })
    }

    /** 开关行：图标 + 标题（+副标题） + 开关 */
    private fun switchRow(
        iconRes: Int,
        title: String,
        subtitle: String?,
        switch: SwitchMaterial,
        onChanged: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(icon(iconRes))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(textView(title, 15f, 0xFF1A1C1E.toInt()))
        if (subtitle != null) {
            texts.addView(textView(subtitle, 12f, 0xFF9AA0A6.toInt()))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(14)
        })
        switch.setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        row.addView(switch)
        return row
    }

    private fun hourPicker(value: Int, onChange: (Int) -> Unit): NumberPicker =
        NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            this.value = value
            setOnValueChangedListener { _, _, newVal -> onChange(newVal) }
        }

    private fun updateWindowVisibility() {
        windowRow.visibility = if (windowSwitch.isChecked) View.VISIBLE else View.GONE
    }

    /** 手动暂停卡片状态刷新之外的运行条件刷新 */
    private fun refreshSuspendSupport() {
        // setPackagesSuspended 仅设备/配置文件所有者可调用（系统限制，普通手机无法
        // 获取该身份）→ 如实禁用开关并说明原因，避免用户误以为功能已生效
        val supported = AppTerminator.canSuspend(this)
        suspendSwitch.isEnabled = supported
        suspendSupportHint.visibility = if (supported) View.GONE else View.VISIBLE
        if (!supported) {
            suspendSupportHint.text =
                "此模式需要设备所有者权限（仅企业部署或 ADB 环境可获取），普通手机无法使用"
            if (suspendSwitch.isChecked) suspendSwitch.isChecked = false // 监听器会同步写回设置
        }
    }

    /** 刷新手动暂停卡片状态 */
    private fun refreshPauseCard() {
        val now = System.currentTimeMillis()
        val until = store.snoozeUntilMs
        if (until > now) {
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            pauseStatusText.text = "已暂停至 ${sdf.format(java.util.Date(until))}（期间不会关闭应用与锁屏）"
            pauseStatusText.setTextColor(0xFFF9A825.toInt())
            btnResume.visibility = View.VISIBLE
            btnSnooze.isEnabled = false
            btnSnooze.alpha = 0.5f
        } else {
            pauseStatusText.text = "当前未暂停"
            pauseStatusText.setTextColor(0xFF6B7280.toInt())
            btnResume.visibility = View.GONE
            btnSnooze.isEnabled = true
            btnSnooze.alpha = 1f
        }
    }

    /** 手动暂停时长选择 */
    private fun showSnoozeDialog() {
        val options = arrayOf("15 分钟", "30 分钟", "1 小时", "2 小时", "今晚停用（8 小时）")
        val minutes = longArrayOf(15, 30, 60, 120, 480)
        MaterialAlertDialogBuilder(this)
            .setTitle("暂停监测")
            .setItems(options) { _, which ->
                store.snoozeUntilMs = System.currentTimeMillis() + minutes[which] * 60_000L
                refreshPauseCard()
                Toast.makeText(
                    this, "已暂停 ${options[which]}", Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 息屏前确认倒计时选择 */
    private fun showConfirmSecondsDialog() {
        val options = arrayOf(5, 10, 15, 20, 30)
        val labels = options.map { "$it 秒" }.toTypedArray()
        val checked = options.indexOf(store.confirmSeconds).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("确认倒计时（无人响应后执行关闭+锁屏）")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                store.confirmSeconds = options[which]
                confirmSecondsText.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「取消后重新监测」延迟选择 */
    private fun showCancelDelayDialog() {
        val options = arrayOf(5, 10, 15, 30, 60)
        val labels = options.map { "$it 分钟" }.toTypedArray()
        val checked = options.indexOf(store.cancelCooldownMinutes).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("取消后重新监测（取消时清醒、稍后睡着的场景）")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                store.cancelCooldownMinutes = options[which]
                cancelDelayText.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 驾驶模式检测开关：开启需要定位权限（读取 GPS 速度） */
    private fun setDrivingCheck(enabled: Boolean) {
        val granted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (enabled && !granted) {
            requestPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_LOCATION_PERMISSION
            )
            return // 授权结果回调里决定最终状态
        }
        val changed = store.drivingCheckEnabled != enabled
        store.drivingCheckEnabled = enabled
        // 重启前台服务以应用/移除 location 前台服务类型
        if (changed && store.enabled) {
            MonitorForegroundService.ensureRunning(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                store.drivingCheckEnabled = true
                if (store.enabled) MonitorForegroundService.ensureRunning(this)
                Toast.makeText(this, "驾驶模式检测已开启", Toast.LENGTH_SHORT).show()
            } else {
                drivingSwitch.isChecked = false // 触发监听器写回关闭状态
                Toast.makeText(this, "未授予定位权限，驾驶模式检测未开启", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadValues() {
        masterSwitch.isChecked = store.enabled
        idlePicker.value = store.idleMinutes
        motionSwitch.isChecked = store.motionCheckEnabled
        // 载入时同步开关态但不触发权限请求（避免每次打开应用都弹权限框）
        drivingSwitch.setOnCheckedChangeListener(null)
        drivingSwitch.isChecked = store.drivingCheckEnabled &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        drivingSwitch.setOnCheckedChangeListener { _, checked -> setDrivingCheck(checked) }
        confirmSwitch.isChecked = store.confirmBeforeLock
        confirmSecondsText.text = "${store.confirmSeconds} 秒"
        cancelDelayText.text = "${store.cancelCooldownMinutes} 分钟"
        closeAppSwitch.isChecked = store.closeAppEnabled
        suspendSwitch.isChecked = store.suspendAppEnabled
        windowSwitch.isChecked = store.windowEnabled
        startPicker.value = store.startHour
        endPicker.value = store.endHour
        updateWindowVisibility()
        refreshPauseCard()
    }

    // ------------------------------------------------------------------
    // 权限卡（动态）
    // ------------------------------------------------------------------

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val ourComponent = ComponentName(this, MonitorAccessibilityService::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val si = info.resolveInfo.serviceInfo
                si?.packageName == ourComponent.packageName &&
                    si?.name?.endsWith("MonitorAccessibilityService") == true
            }
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, DeviceAdminReceiver::class.java))
    }

    private fun isNotificationGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 权限卡：
     * - 权限齐全 → 绿色卡片 + 就绪行（不再展示获取入口）
     * - 有缺失 → 红色描边 + 每项"去开启"入口
     */
    private fun refreshPermCard() {
        permCardContent.removeAllViews()
        val accOk = isAccessibilityEnabled()
        val adminOk = isAdminActive()
        val notifOk = isNotificationGranted()

        if (accOk && adminOk && notifOk) {
            permCard.setStrokeColor(ColorStateList.valueOf(0xFF2E7D32.toInt()))
            permCard.setStrokeWidth(dp(1))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(icon(R.drawable.ic_check_circle, 22).apply {
                setColorFilter(0xFF2E7D32.toInt())
            })
            row.addView(textView(" 权限已就绪，自动息屏正常运行", 14f, 0xFF2E7D32.toInt()))
            permCardContent.addView(row)
            addBatteryRowIfNeeded()
            return
        }

        permCard.setStrokeColor(ColorStateList.valueOf(0xFFD32F2F.toInt()))
        permCard.setStrokeWidth(dp(1))

        if (store.enabled) {
            val warnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            warnRow.addView(icon(R.drawable.ic_warning, 22).apply {
                setColorFilter(0xFFD32F2F.toInt())
            })
            warnRow.addView(textView(" 权限缺失，自动息屏不会触发", 14f, 0xFFD32F2F.toInt(), bold = true))
            permCardContent.addView(warnRow)
        } else {
            permCardContent.addView(textView("完成以下权限设置后自动息屏才会生效", 13f, 0xFF6B7280.toInt()))
        }

        // 无障碍
        permCardContent.addView(permRow(R.drawable.ic_accessibility, "无障碍服务", accOk) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        // 设备管理员
        permCardContent.addView(permRow(R.drawable.ic_lock, "设备管理员", adminOk) { requestAdmin() })
        // 通知权限（Android 13+ 才需要）
        if (Build.VERSION.SDK_INT >= 33) {
            permCardContent.addView(permRow(R.drawable.ic_notifications, "通知权限", notifOk) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION_PERMISSION
                )
            })
        }
        addBatteryRowIfNeeded()
    }

    /**
     * 电池优化白名单（建议项，不参与"权限缺失"判定）：
     * 未加白名单时显示一行入口；国产 ROM 省电策略是后台服务被杀的头号原因。
     */
    private fun addBatteryRowIfNeeded() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        permCardContent.addView(permRow(
            R.drawable.ic_moon, "电池优化白名单（建议，防后台被杀）", false
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
    }

    /** 权限行：图标 + 名称 + 状态/按钮 */
    private fun permRow(iconRes: Int, title: String, ok: Boolean, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(icon(iconRes, 20))
        row.addView(
            textView(title, 14f, 0xFF1A1C1E.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            }
        )
        if (ok) {
            row.addView(textView("✓ 已开启", 13f, 0xFF2E7D32.toInt()))
        } else {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "去开启"
                setTextColor(0xFF1E88E5.toInt())
                minHeight = dp(36)
                setMinimumHeight(dp(36))
                insetTop = 0
                insetBottom = 0
            }
            btn.setOnClickListener { action() }
            row.addView(btn)
        }
        return row
    }

    // ------------------------------------------------------------------
    // 息屏应用列表（动态）
    // ------------------------------------------------------------------

    private fun appNameOf(pkg: String): String {
        SettingsStore.DEFAULT_APPS[pkg]?.let { return it }
        return pkg
    }

    private fun refreshAppList() {
        appsCardContent.removeAllViews()
        val selected = store.targetApps
        val timeouts = store.getAppTimeoutMinutes()

        appCountText.text = if (selected.isEmpty()) "未配置" else "${selected.size} 个"

        if (selected.isEmpty()) {
            appsCardContent.addView(textView("尚未配置应用，自动息屏不会触发", 13f, 0xFF9AA0A6.toInt()).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        val sorted = selected.sortedBy { appNameOf(it).lowercase() }
        for (pkg in sorted) {
            val name = appNameOf(pkg)
            val custom = timeouts[pkg]

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setOnClickListener { openAppDetail(pkg, name) }
            }
            // 图标（主色容器圆角底）
            val iconBox = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@MainActivity, R.drawable.bg_icon_circle
                )
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            iconBox.addView(icon(R.drawable.ic_apps, 18).apply {
                setColorFilter(0xFF1E88E5.toInt())
            })
            row.addView(iconBox)

            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(textView(name, 15f, 0xFF1A1C1E.toInt()))
            texts.addView(textView(
                if (custom != null) "专属 $custom 分钟无操作后息屏" else "跟随全局默认（${store.idleMinutes} 分钟）",
                12f,
                if (custom != null) 0xFF1E88E5.toInt() else 0xFF9AA0A6.toInt()
            ))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            })
            row.addView(icon(R.drawable.ic_chevron, 22).apply {
                setColorFilter(0xFFC4C9D0.toInt())
            })
            appsCardContent.addView(row)
        }
    }

    private fun openAppDetail(pkg: String, name: String) {
        startActivity(Intent(this, AppDetailActivity::class.java)
            .putExtra(AppDetailActivity.EXTRA_PKG, pkg)
            .putExtra(AppDetailActivity.EXTRA_NAME, name))
    }

    // ------------------------------------------------------------------
    // 添加应用（两种形式：内置常用默认配置 / 手机已安装应用）
    // ------------------------------------------------------------------

    private data class AppEntry(val pkg: String, val name: String, val icon: Drawable?)

    private var selectedPkgs: LinkedHashSet<String> = linkedSetOf()
    private var installedEntries: List<AppEntry>? = null

    private fun showAppDialog() {
        selectedPkgs = LinkedHashSet(store.targetApps)

        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnDefault = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "内置常用"
            setTextColor(0xFF1E88E5.toInt())
            minHeight = dp(44)
            setMinimumHeight(dp(44))
            insetTop = 0
            insetBottom = 0
        }
        val btnInstalled = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "手机已安装"
            setTextColor(0xFF1E88E5.toInt())
            minHeight = dp(44)
            setMinimumHeight(dp(44))
            insetTop = 0
            insetBottom = 0
        }
        tabRow.addView(btnDefault, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(btnInstalled, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(tabRow)

        val listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }
        view.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))

        listView.setOnItemClickListener { _, row, pos, _ ->
            val entry = (listView.adapter as AppEntryAdapter).getItem(pos)
            if (entry.pkg in selectedPkgs) {
                selectedPkgs.remove(entry.pkg)
            } else {
                selectedPkgs.add(entry.pkg)
            }
            (row as? ViewGroup)?.getChildAt(2)?.let {
                (it as CheckBox).isChecked = entry.pkg in selectedPkgs
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("添加目标应用")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                store.targetApps = selectedPkgs.toSet()
                refreshAppList()
            }
            .setNeutralButton("自定义包名") { _, _ -> showCustomAppDialog() }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        fun showTab(entries: List<AppEntry>) {
            listView.adapter = AppEntryAdapter(entries)
        }

        showTab(buildDefaultEntries())

        btnInstalled.setOnClickListener {
            val cached = installedEntries
            if (cached != null) {
                showTab(cached)
            } else {
                btnInstalled.isEnabled = false
                btnInstalled.text = "正在加载…"
                Thread {
                    val entries = queryInstalledApps()
                    runOnUiThread {
                        installedEntries = entries
                        btnInstalled.isEnabled = true
                        btnInstalled.text = "手机已安装"
                        showTab(entries)
                    }
                }.start()
            }
        }
    }

    private fun buildDefaultEntries(): List<AppEntry> {
        val entries = ArrayList<AppEntry>()
        SettingsStore.DEFAULT_APPS.forEach { (pkg, name) ->
            entries.add(AppEntry(pkg, name, null))
        }
        store.customApps.forEach { pkg ->
            entries.add(AppEntry(pkg, "$pkg（自定义）", null))
        }
        return entries
    }

    private fun queryInstalledApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo != null }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    AppEntry(
                        pkg = it.activityInfo.packageName,
                        name = it.loadLabel(pm).toString(),
                        icon = it.loadIcon(pm)
                    )
                }
                .sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private inner class AppEntryAdapter(private val entries: List<AppEntry>) : BaseAdapter() {

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): AppEntry = entries[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = entries[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))

                val icon = ImageView(this@MainActivity)
                addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))

                val texts = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                texts.addView(TextView(this@MainActivity).apply { textSize = 15f })
                texts.addView(TextView(this@MainActivity).apply {
                    textSize = 11f
                    setTextColor(0xFF9AA0A6.toInt())
                })
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(10)
                })

                addView(CheckBox(this@MainActivity).apply {
                    isClickable = false
                    isFocusable = false
                    buttonTintList = ColorStateList.valueOf(0xFF1E88E5.toInt())
                })
            }

            (row.getChildAt(0) as ImageView).setImageDrawable(
                entry.icon ?: packageManager.defaultActivityIcon
            )
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = entry.name
            (texts.getChildAt(1) as TextView).text = entry.pkg
            (row.getChildAt(2) as CheckBox).isChecked = entry.pkg in selectedPkgs
            return row
        }
    }

    private fun showCustomAppDialog() {
        val input = EditText(this).apply {
            hint = "输入包名，例如 com.ss.android.ugc.aweme"
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("添加自定义应用")
            .setView(container)
            .setPositiveButton("添加") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isEmpty()) return@setPositiveButton
                store.customApps = store.customApps + pkg
                store.targetApps = store.targetApps + pkg
                refreshAppList()
                showAppDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 版本与更新（仓库监听）
    // ------------------------------------------------------------------

    private fun refreshRepoStatus() {
        val url = store.updateRepoUrl
        repoStatusText.text = if (url.isBlank()) "更新仓库：未配置" else "更新仓库：$url"
        repoStatusText.setTextColor(if (url.isBlank()) 0xFF9AA0A6.toInt() else 0xFF1E88E5.toInt())
    }

    /** 配置更新仓库地址（github.com/owner/repo / gitee.com/owner/repo / 完整 API 地址） */
    private fun showRepoDialog() {
        val input = EditText(this).apply {
            hint = "例如 github.com/SXHLY/Auto-screen-off"
            setText(store.updateRepoUrl)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("设置更新仓库")
            .setMessage("支持 GitHub / Gitee 仓库地址或完整 API 地址；更新只在点「检查更新」时联网获取")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                val valid = url.isBlank() || UpdateChecker.normalizeApiUrl(url) != null
                if (!valid) {
                    Toast.makeText(this, "地址格式无法识别，未保存", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.updateRepoUrl = url
                refreshRepoStatus()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 手动检查更新：结果以 Toast + 通知反馈 */
    private fun checkUpdateManually() {
        if (store.updateRepoUrl.isBlank()) {
            showRepoDialog()
            return
        }
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "检查中…"
        Thread {
            val info = UpdateChecker.fetchLatest(this)
            runOnUiThread {
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.text = "检查更新"
                val msg = when {
                    info == null -> "检查失败：请确认仓库地址与网络"
                    info.build > UpdateChecker.currentBuild(this) -> {
                        store.pendingUpdateVersion = info.versionName
                        store.pendingUpdateUrl = info.apkUrl
                        UpdateManager.showUpdateNotification(this, info)
                        "发现新版本 v${info.versionName}，通知栏可下载"
                    }
                    else -> "已是最新版本（v${info.versionName}）"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 权限引导
    // ------------------------------------------------------------------

    private fun requestAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, DeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "用于在检测到您睡着时立即锁定屏幕，停止视频播放"
                )
            })
        } else {
            Toast.makeText(this, "设备管理员已激活", Toast.LENGTH_SHORT).show()
        }
    }
}
