package com.autoscreenoff

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 运动监测（行为模式分析）
 *
 * 将运动分为三档，避免"静止/运动"二元判断带来的体验灾难：
 *
 * 1. 大幅运动 BURST —— 拿起、走动、翻身、调整姿势等（抖动大/偏离重力明显）
 *    → 明确的清醒信号，重置"无运动计时"
 * 2. 随机微动 MICRO —— 清醒时无意识的小动作（摸脸、调整、伸懒腰等），
 *    时间间隔不均匀、没有周期
 *    → 清醒信号，阻止息屏（间隔规律性分析不通过）
 * 3. 规律呼吸微动 —— 约 2~8 秒一次、间隔均匀的低幅起伏（人体呼吸特征）
 *    → 睡着握着手机/手机放在胸口都会出现，**不作为清醒信号**，
 *      不阻止息屏（ScreenOffController 只要求更长的无操作时间）
 *
 * 结论：
 * - 醒着时手机被拿起/走动/频繁小动作 → 阻止息屏（不误锁）
 * - 睡着时手机完全静止 或 只有规律呼吸起伏 → 正常息屏（不遗漏）
 */
object MotionMonitor {

    private const val TAG = "AutoScreenOff"

    /** 采样节流（毫秒） */
    private const val SAMPLE_INTERVAL_MS = 400L

    /** 低通滤波系数 */
    private const val FILTER_ALPHA = 0.15

    /** 大幅运动：实时值与低通基准的抖动超过该值（m/s²） */
    private const val BURST_JITTER_THRESHOLD = 0.9

    /** 大幅运动：合加速度明显偏离重力（拿起/走动等） */
    private const val BURST_GRAVITY_DEVIATION = 1.2

    /** 微动判定区间（m/s²），低于下限视为传感器噪声/静止 */
    private const val MICRO_JITTER_MIN = 0.10
    private const val MICRO_JITTER_MAX = 0.90

    /** 规律性（呼吸）分析窗口与周期范围 */
    private const val REGULAR_WINDOW_MS = 90_000L
    private const val BREATH_INTERVAL_MIN_MS = 2_000L   // 2s ~ 8s（约 7.5~30 次/分）
    private const val BREATH_INTERVAL_MAX_MS = 8_000L
    private const val MIN_VALID_INTERVALS = 4           // 至少 4 个均匀间隔才判定为规律
    private const val MAX_INTERVAL_CV = 0.35            // 间隔变异系数上限（标准差/均值）

    /** 「持续运动」判定：运动样本之间允许的最大空档（短暂停顿不中断连续性） */
    private const val SUSTAINED_GAP_MS = 1_200L

    /** 最近一次大幅运动的时间戳 */
    @Volatile
    var lastBurstAt: Long = System.currentTimeMillis()
        private set

    /** 微动事件时间戳队列（升序，只保留窗口内；ConcurrentLinkedDeque 线程安全） */
    private val microEvents = ConcurrentLinkedDeque<Long>()

    /** 持续运动窗口（确认弹窗倒计时期间判定「用户仍清醒」用）：窗口起点与最近一次运动样本 */
    @Volatile
    private var sustainedStart = 0L

    @Volatile
    private var lastMovingAt = 0L

    private var sensorManager: SensorManager? = null
    private var lastSampleAt = 0L
    private var filteredMagnitude: Double = SensorManager.GRAVITY_EARTH.toDouble()

    fun start(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        sensorManager = sm
        lastBurstAt = System.currentTimeMillis()
        microEvents.clear()
        sustainedStart = 0L
        lastMovingAt = 0L
        filteredMagnitude = SensorManager.GRAVITY_EARTH.toDouble()
        lastSampleAt = 0L
        Log.i(TAG, "motion monitor started")
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        sensorManager = null
        Log.i(TAG, "motion monitor stopped")
    }

    /** 距最近一次大幅运动（拿起/走动/翻身等）的时间（毫秒） */
    fun timeSinceBurst(): Long = System.currentTimeMillis() - lastBurstAt

    /**
     * 最近是否出现持续 ≥ [durationMs] 的连续无规律运动（上下甩动、来回偏转等）。
     * 呼吸级规律微动是 2~8 秒一次的孤立小幅起伏，样本间隔远大于 [SUSTAINED_GAP_MS]，
     * 无法累积出连续时长，天然被排除 —— 用于确认弹窗倒计时期间识别「用户其实还醒着」。
     */
    fun isSustainedMotion(durationMs: Long): Boolean {
        if (sustainedStart == 0L) return false
        return lastMovingAt - sustainedStart >= durationMs
    }

    /**
     * 最近是否有"规律呼吸级微动"（约 2~8 秒一次、间隔均匀）。
     * 用于区分：睡着握着手机（有规律呼吸） vs 醒着随机小动作。
     */
    fun hasRegularBreathingRecently(): Boolean {
        val now = System.currentTimeMillis()
        // 取窗口内时间戳快照
        val times = ArrayList<Long>()
        for (t in microEvents) {
            if (t >= now - REGULAR_WINDOW_MS) times.add(t)
        }
        if (times.size < MIN_VALID_INTERVALS + 1) return false

        // 只统计落在呼吸周期范围内的间隔
        val intervals = ArrayList<Long>(times.size - 1)
        for (i in 1 until times.size) {
            val d = times[i] - times[i - 1]
            if (d in BREATH_INTERVAL_MIN_MS..BREATH_INTERVAL_MAX_MS) intervals.add(d)
        }
        if (intervals.size < MIN_VALID_INTERVALS) return false

        // 间隔均匀性：变异系数（标准差/均值）小 = 规律
        var sum = 0.0
        for (v in intervals) sum += v
        val mean = sum / intervals.size
        var varSum = 0.0
        for (v in intervals) {
            val d = v - mean
            varSum += d * d
        }
        val cv = sqrt(varSum / intervals.size) / mean
        return cv <= MAX_INTERVAL_CV
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = System.currentTimeMillis()
            if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
            lastSampleAt = now

            val x = event.values[0].toDouble()
            val y = event.values[1].toDouble()
            val z = event.values[2].toDouble()
            val magnitude = sqrt(x * x + y * y + z * z)

            // 低通滤波：平稳基准
            filteredMagnitude = filteredMagnitude * (1 - FILTER_ALPHA) + magnitude * FILTER_ALPHA

            val jitter = abs(magnitude - filteredMagnitude)
            val deviationFromGravity = abs(magnitude - SensorManager.GRAVITY_EARTH)

            when {
                // 大幅运动：明确的清醒信号
                jitter > BURST_JITTER_THRESHOLD || deviationFromGravity > BURST_GRAVITY_DEVIATION -> {
                    lastBurstAt = now
                    microEvents.clear() // 打断规律分析，重新积累
                    Log.d(TAG, "burst motion jitter=${"%.2f".format(jitter)}")
                }
                // 微动：记录时间戳用于规律性分析
                jitter in MICRO_JITTER_MIN..MICRO_JITTER_MAX -> {
                    microEvents.addLast(now)
                    while (!microEvents.isEmpty() &&
                        microEvents.first() < now - REGULAR_WINDOW_MS
                    ) {
                        microEvents.removeFirst()
                    }
                }
            }

            // 持续运动窗口：任何超出噪声底的运动样本（大幅或微动）都延续窗口；
            // 静止超过 SUSTAINED_GAP_MS 则窗口作废（短暂停顿不打断甩动/偏转的连续性）
            val moving = jitter > MICRO_JITTER_MIN || deviationFromGravity > MICRO_JITTER_MIN
            if (moving) {
                if (sustainedStart == 0L || now - lastMovingAt > SUSTAINED_GAP_MS) {
                    sustainedStart = now
                }
                lastMovingAt = now
            } else if (now - lastMovingAt > SUSTAINED_GAP_MS) {
                sustainedStart = 0L
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // 无需处理
        }
    }
}
