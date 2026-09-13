package com.zyyme.workdayalarmclock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.TextViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zyyme.workdayalarmclock.camera.AmbientBrightnessController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * 时钟
 */
class ClockActivity : AppCompatActivity() {
    companion object {
        var me: ClockActivity? = null
    }

    var mediaSessionCompat: MediaSessionCompat? = null
    var mediaComponentName: ComponentName? = null
    var isActivityStarted = false

    private var timeHandler: Handler = Handler()
    private var runnable: Runnable? = null
    var sdfHmsmde = SimpleDateFormat("h:mm:ss.M月d日 E")

    var isKeepScreenOn = false
    private var showMsgUntil = 0L
    var clockMode = false
    private var returnPackage: String? = null
    var enableTop = false
    private var showLyrics = false
    private val lyricsOnTop get() = showLyrics && clockMode
    private var isVerticalLayout = false
    private var isUserSeeking = false

    // 防烧屏位移相关变量
    private var burnInOffsetX = 0f
    private var burnInOffsetY = 0f
    private var burnInVelocityX = 0f
    private var burnInVelocityY = 0f
    private var maxBurnInOffsetX = 0f
    private var maxBurnInOffsetY = 0f
    
    // 记录时间控件的基准高度，用于无极放大时动态突破限制
    private var baseTimeHeight = 0

    // 🌟 农历缓存变量，避免每秒重复计算
    private var cachedLunarString: String = ""
    private var cachedLunarDay: Int = -1

    private val prefs get() = getSharedPreferences("workday_alarm_settings", Context.MODE_PRIVATE)

    fun showMsg(msg: String) {
        runOnUiThread {
            showMsgUntil = SystemClock.elapsedRealtime() + 3000
            if (enableTop && !lyricsOnTop) {
                findViewById<TextView>(R.id.tv_top).text = msg
            } else {
                findViewById<TextView>(R.id.tv_date).text = msg
            }
        }
    }

    // --- 设置项读取与保存 ---
    private fun getFontScale(): Float = prefs.getFloat("key_font_scale", 1.0f)
    private fun setFontScale(scale: Float) = prefs.edit().putFloat("key_font_scale", scale).apply()

    private fun getFontWeight(): Int = prefs.getInt("key_font_weight", 1) // 0:正常, 1:加粗, 2:特粗
    private fun setFontWeight(weight: Int) = prefs.edit().putInt("key_font_weight", weight).apply()
    private fun getWeightText(w: Int) = when(w) { 0 -> "正常"; 1 -> "加粗"; 2 -> "特粗"; else -> "正常" }

    private fun getTimeDateMargin(): Int = prefs.getInt("key_time_date_margin", -20) // 默认 -20dp
    private fun setTimeDateMargin(margin: Int) = prefs.edit().putInt("key_time_date_margin", margin).apply()

    private fun getDateFontScale(): Float = prefs.getFloat("key_date_font_scale", 1.0f)
    private fun setDateFontScale(scale: Float) = prefs.edit().putFloat("key_date_font_scale", scale).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        me = this
        super.onCreate(savedInstanceState)
        returnPackage = intent.getStringExtra(HomeLauncherActivity.EXTRA_RETURN_PACKAGE)

        if (MeService.me == null) {
            startService(Intent(this, MeService::class.java))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE && MeSettings.isEnabled(this, MeSettings.KEY_LANDSCAPE)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        val flag24 = MeSettings.isEnabled(this, MeSettings.KEY_T24)
        val isNoSeconds = MeSettings.isEnabled(this, MeSettings.KEY_TSS)
        
        if (isNoSeconds) {
            if (flag24) sdfHmsmde = SimpleDateFormat("H:mm.M月d日 E")
            else sdfHmsmde = SimpleDateFormat("h:mm.M月d日 E")
        } else {
            if (flag24) sdfHmsmde = SimpleDateFormat("H:mmss.M月d日 E")
            else sdfHmsmde = SimpleDateFormat("h:mmss.M月d日 E")
        }

        setFullscreen()
        setContentView(R.layout.activity_clock)

        val tvTop = findViewById<TextView>(R.id.tv_top)
        val tvTime = findViewById<TextView>(R.id.tv_time)
        val tvDate = findViewById<TextView>(R.id.tv_date)
        val circularMusicProgress = findViewById<CircularMusicProgressView>(R.id.circular_music_progress)
        showLyrics = MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)

        // 按钮控制逻辑
        findViewById<Button>(R.id.btn_back).setOnClickListener {
            if (!returnPackage.isNullOrBlank()) {
                if (!HomeLauncherActivity.revealPreviousTask(this, returnPackage) &&
                    !HomeLauncherActivity.returnToPreviousApp(this, returnPackage)) moveTaskToBack(true)
                return@setOnClickListener
            }
            val intent: Intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
        findViewById<Button>(R.id.btn_app).setOnClickListener {
            val intent = Intent(this, AppListActivity::class.java).apply {
                putExtra(HomeLauncherActivity.EXTRA_RETURN_PACKAGE, returnPackage)
            }
            startActivity(intent)
        }
        tvTop.setOnClickListener {
            val intent = Intent(this, AppListActivity::class.java).apply {
                putExtra(HomeLauncherActivity.EXTRA_RETURN_PACKAGE, returnPackage)
            }
            startActivity(intent)
        }
        findViewById<Button>(R.id.btn_prev).setOnClickListener { MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        findViewById<Button>(R.id.btn_play).setOnClickListener { MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        findViewById<Button>(R.id.btn_next).setOnClickListener { MeService.me?.keyHandleAction(2147483645) }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_STOP) }
        findViewById<Button>(R.id.btn_volm).setOnClickListener { MeService.me?.keyHandleAction(2147483646) }
        findViewById<Button>(R.id.btn_volp).setOnClickListener { MeService.me?.keyHandleAction(2147483647) }
        findViewById<Button>(R.id.btn_forward).setOnClickListener { MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }

        val musicSeekBar = findViewById<SeekBar>(R.id.sb_music_progress)
        val tvMusicPosition = findViewById<TextView>(R.id.tv_music_position)
        val tvMusicDuration = findViewById<TextView>(R.id.tv_music_duration)
        musicSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvMusicPosition.text = formatMusicTime(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekMusicTo(seekBar?.progress ?: 0)
                isUserSeeking = false
            }
        })
        musicSeekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                isUserSeeking = true
                tvMusicPosition.text = formatMusicTime(musicSeekBar.progress)
                false
            } else if (event.action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                seekMusicTo(musicSeekBar.progress)
                isUserSeeking = false
                true
            } else false
        }

        // 🌟 时间区域手势：单击播放/暂停，长按弹出综合设置
        val timeGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (clockMode) {
                    MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    return true
                }
                return false
            }
            override fun onLongPress(e: MotionEvent?) {
                if (clockMode) {
                    showTimeSettingsDialog(tvTime, tvDate)
                } else {
                    showMsg("一键")
                    MeService.me?.toGo("1key")
                }
            }
            override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                        if (diffX > 0) MeService.me?.keyHandleAction(2147483645)
                        else MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        return true
                    }
                } else {
                    if (Math.abs(diffY) > 100 && Math.abs(velocityY) > 100) {
                        if (diffY > 0) MeService.me?.keyHandleAction(2147483646)
                        else MeService.me?.keyHandleAction(2147483647)
                        return true
                    }
                }
                return false
            }
        })
        tvTime.setOnTouchListener { v, event -> timeGestureDetector.onTouchEvent(event) }

        // 🌟 日期区域点击与长按逻辑
        tvDate.setOnClickListener {
            if (clockMode) recreate()
            else {
                if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
            }
        }
        
        tvDate.setOnLongClickListener {
            if (clockMode) {
                // 时钟模式下，长按日期弹出日期字体大小设置
                showDateSettingsDialog(tvDate)
                true
            } else {
                // 非时钟模式下，保留原有的锁屏功能
                val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponentName = ComponentName(this, MeDeviceAdminReceiver::class.java)
                if (devicePolicyManager.isAdminActive(adminComponentName)) {
                    try { devicePolicyManager.lockNow() } catch (e: Exception) { showMsg("锁屏失败: ${e.message}") }
                }
                true
            }
        }
        
        findViewById<Button>(R.id.btn_minsize).setOnClickListener { setFullScreenClock() }
        
        var screenReverseFlag = false
        findViewById<Button>(R.id.btn_rotation).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && !screenReverseFlag) {
                screenReverseFlag = true; ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !screenReverseFlag) {
                screenReverseFlag = true; ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && screenReverseFlag) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        
        val rootLayout = findViewById<LinearLayout>(R.id.root_layout)
        rootLayout.post {
            if (intent.getBooleanExtra("clockMode", false) || intent.action == "android.media.action.STILL_IMAGE_CAMERA") {
                setFullScreenClock()
            }
            if (intent.getBooleanExtra("keepOn", false)) {
                isKeepScreenOn = true
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            intent.replaceExtras(Intent())
        }

        // 核心定时器
        runnable = object : Runnable {
            override fun run() {
                val now = Date()
                val hmsmde = sdfHmsmde.format(now).split(".")
                val timeText = hmsmde[0]
                val isNoSeconds = MeSettings.isEnabled(this@ClockActivity, MeSettings.KEY_TSS)
                
                // 🌟 计算农历并拼接到日期字符串中 (带缓存机制)
                val calendar = Calendar.getInstance()
                val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
                if (currentDay != cachedLunarDay) {
                    cachedLunarDay = currentDay
                    cachedLunarString = getLunarString(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, currentDay)
                }
                val dateParts = hmsmde[1].split(" ")
                val dateStr = if (dateParts.size >= 2) {
                    "${dateParts[0]} $cachedLunarString ${dateParts[1]}" // 例如: 9月12日 正月初一 周六
                } else {
                    "${hmsmde[1]} $cachedLunarString"
                }
                
                val spannable = SpannableString(timeText)
                val normalColor = tvTime.currentTextColor
                val blinkColor = if (Calendar.getInstance().get(Calendar.SECOND) % 2 == 0) normalColor else Color.TRANSPARENT

                val colonIndex = timeText.indexOf(':')
                if (colonIndex != -1) {
                    spannable.setSpan(ForegroundColorSpan(blinkColor), colonIndex, colonIndex + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    
                    if (!isNoSeconds) {
                        val hasNewline = timeText.contains('\n')
                        val secStartIndex = if (hasNewline) colonIndex + 4 else colonIndex + 3
                        if (secStartIndex < timeText.length) {
                            spannable.setSpan(RelativeSizeSpan(0.33f), secStartIndex, timeText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                
                tvTime.setText(spannable, TextView.BufferType.SPANNABLE)

                // 防烧屏位移逻辑
                if (clockMode) {
                    burnInOffsetX += burnInVelocityX
                    burnInOffsetY += burnInVelocityY

                    if (Math.abs(burnInOffsetX) > maxBurnInOffsetX) {
                        burnInVelocityX = -burnInVelocityX
                        burnInOffsetX = burnInOffsetX.coerceIn(-maxBurnInOffsetX, maxBurnInOffsetX)
                    }
                    if (Math.abs(burnInOffsetY) > maxBurnInOffsetY) {
                        burnInVelocityY = -burnInVelocityY
                        burnInOffsetY = burnInOffsetY.coerceIn(-maxBurnInOffsetY, maxBurnInOffsetY)
                    }

                    tvTime.translationX = burnInOffsetX
                    tvTime.translationY = burnInOffsetY
                    tvDate.translationX = burnInOffsetX
                    tvDate.translationY = burnInOffsetY
                    if (enableTop) {
                        tvTop.translationX = burnInOffsetX
                        tvTop.translationY = burnInOffsetY
                    }
                }

                // 音乐进度与歌词更新
                val service = MeService.me
                val millis = service?.getPlaybackPosition()
                val duration = service?.getPlaybackDuration() ?: 0
                updateMusicProgress(musicSeekBar, tvMusicPosition, tvMusicDuration, millis, duration)
                circularMusicProgress.setProgress(millis, duration)
                val lyric = if (showLyrics) service?.getCurrentLyric(millis) else null
                if (lyricsOnTop) {
                    if (tvTop.text.toString() != lyric.orEmpty()) tvTop.text = lyric.orEmpty()
                }
                if (SystemClock.elapsedRealtime() >= showMsgUntil) {
                    val batInfo = service?.batInfo.orEmpty()
                    val playTime = millis?.let { String.format("%2d:%02d", it / 60000, (it % 60000) / 1000) }
                    if (showLyrics && !clockMode) {
                        setTextIfChanged(tvDate, if (millis != null && lyric != null) lyric else batInfo + dateStr)
                    } else if (lyricsOnTop) {
                        if (isVerticalLayout) {
                            val secondLine = (batInfo + (playTime?.let { "▷$it" } ?: "")).trimEnd()
                            setTextIfChanged(tvDate, dateStr + (secondLine.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""))
                        } else {
                            setTextIfChanged(tvDate, batInfo + dateStr + (playTime?.let { " ▷$it" } ?: ""))
                        }
                    } else if (enableTop) {
                        setTextIfChanged(tvTop, batInfo + (playTime?.let { "▷$it" } ?: ""))
                        setTextIfChanged(tvDate, dateStr)
                    } else {
                        setTextIfChanged(tvDate, batInfo + dateStr + (playTime?.let { " ▷$it" } ?: ""))
                    }
                }
                
                if (showLyrics && millis != null) {
                    timeHandler.postDelayed(this, 250)
                } else {
                    timeHandler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
                }
            }
        }
        timeHandler.postDelayed(runnable as Runnable, 1000 - System.currentTimeMillis() % 1000)
    }

    // 🌟 综合设置对话框：时钟大小、粗细、间距
    private fun showTimeSettingsDialog(tvTime: TextView, tvDate: TextView) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }

        // 1. 字体大小
        val tvSizeTitle = TextView(context).apply { text = "时钟字体大小: ${(getFontScale() * 100).toInt()}%"; textSize = 16f; setPadding(0, 0, 0, 10) }
        val sbSize = SeekBar(context).apply { max = 300; progress = ((getFontScale() - 1.0f) * 100).toInt() }
        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val scale = 1.0f + progress / 100f
                    tvSizeTitle.text = "时钟字体大小: ${(scale * 100).toInt()}%"
                    applyTimeSettings(tvTime, tvDate, scale, getFontWeight(), getTimeDateMargin())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(tvSizeTitle)
        layout.addView(sbSize)

        // 2. 字体粗细
        val tvWeightTitle = TextView(context).apply { text = "时钟字体粗细: ${getWeightText(getFontWeight())}"; textSize = 16f; setPadding(0, 30, 0, 10) }
        val sbWeight = SeekBar(context).apply { max = 2; progress = getFontWeight() }
        sbWeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvWeightTitle.text = "时钟字体粗细: ${getWeightText(progress)}"
                    applyTimeSettings(tvTime, tvDate, getFontScale(), progress, getTimeDateMargin())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(tvWeightTitle)
        layout.addView(sbWeight)

        // 🌟 3. 上下间距 (范围优化: -200dp ~ 100dp, 跨度 300)
        val tvMarginTitle = TextView(context).apply { text = "时钟与日期间距: ${getTimeDateMargin()}dp"; textSize = 16f; setPadding(0, 30, 0, 10) }
        val sbMargin = SeekBar(context).apply { 
            max = 300
            progress = getTimeDateMargin() + 200 // 偏移量，使 -200 对应 progress 0
        }
        sbMargin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val margin = progress - 200 // 还原为真实 dp 值
                    tvMarginTitle.text = "时钟与日期间距: ${margin}dp"
                    applyTimeSettings(tvTime, tvDate, getFontScale(), getFontWeight(), margin)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(tvMarginTitle)
        layout.addView(sbMargin)

        AlertDialog.Builder(this)
            .setTitle("时钟显示设置")
            .setView(layout)
            .setPositiveButton("确定") { dialog, _ ->
                setFontScale(1.0f + sbSize.progress / 100f)
                setFontWeight(sbWeight.progress)
                setTimeDateMargin(sbMargin.progress - 200) // 保存真实 dp 值
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                applyTimeSettings(tvTime, tvDate, getFontScale(), getFontWeight(), getTimeDateMargin())
                dialog.dismiss()
            }
            .show()
    }

    // 🌟 日期设置对话框：仅调节日期字体大小 (范围优化: 10% ~ 200%)
    private fun showDateSettingsDialog(tvDate: TextView) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }

        val currentPercent = (getDateFontScale() * 100).toInt()
        val tvTitle = TextView(context).apply { text = "日期字体大小: ${currentPercent}%"; textSize = 16f; setPadding(0, 0, 0, 10) }
        
        // 范围 10% 到 200%，跨度为 190
        val sb = SeekBar(context).apply { 
            max = 190
            progress = (currentPercent - 10).coerceIn(0, 190)
        }
        
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val scale = (10f + progress) / 100f // 10 到 200 映射回 0.1f 到 2.0f
                    tvTitle.text = "日期字体大小: ${(scale * 100).toInt()}%"
                    applyDateSettings(tvDate, scale)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(tvTitle)
        layout.addView(sb)

        AlertDialog.Builder(this)
            .setTitle("日期显示设置")
            .setView(layout)
            .setPositiveButton("确定") { dialog, _ ->
                val finalScale = (10f + sb.progress) / 100f
                setDateFontScale(finalScale)
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                applyDateSettings(tvDate, getDateFontScale())
                dialog.dismiss()
            }
            .show()
    }

    // 应用时钟相关设置 (大小、粗细、间距)
    private fun applyTimeSettings(tvTime: TextView, tvDate: TextView, scale: Float, weight: Int, marginDp: Int) {
        // 1. 应用粗细
        tvTime.paint.isFakeBoldText = (weight >= 2)
        tvTime.setTypeface(tvTime.typeface, if (weight >= 1) Typeface.BOLD else Typeface.NORMAL)
        
        // 2. 应用间距 (修改 tvDate 的 topMargin)
        val params = tvDate.layoutParams as LinearLayout.LayoutParams
        params.topMargin = (marginDp * resources.displayMetrics.density).toInt()
        tvDate.layoutParams = params

        // 3. 应用大小 (动态高度突破 autoSize 限制)
        val newHeight = (baseTimeHeight * scale).toInt()
        tvTime.layoutParams.height = newHeight
        tvTime.requestLayout()
        
        val maxTimeSize = (1000f * scale).toInt()
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(tvTime, 18, maxTimeSize, 1, TypedValue.COMPLEX_UNIT_SP)
        
        // 强制刷新触发 autoSize 重新计算
        val currentText = tvTime.text
        tvTime.text = ""
        tvTime.text = currentText
    }

    // 应用日期相关设置 (大小)
    private fun applyDateSettings(tvDate: TextView, scale: Float) {
        val maxDateSize = (80f * scale).toInt()
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(tvDate, 8, maxDateSize, 1, TypedValue.COMPLEX_UNIT_SP)
        val currentText = tvDate.text
        tvDate.text = ""
        tvDate.text = currentText
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            returnPackage = intent.getStringExtra(HomeLauncherActivity.EXTRA_RETURN_PACKAGE)
        }
    }

    override fun onStart() { super.onStart(); isActivityStarted = true }
    override fun onStop() { isActivityStarted = false; super.onStop() }

    override fun onResume() {
        super.onResume()
        showLyrics = MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)
        MeService.me?.syncLyricsSetting()
        setFullscreen()
        AmbientBrightnessController.applyLatestTo(window)
    }

    override fun onBackPressed() {
        if (!returnPackage.isNullOrBlank()) {
            if (!HomeLauncherActivity.revealPreviousTask(this, returnPackage) &&
                !HomeLauncherActivity.returnToPreviousApp(this, returnPackage)) moveTaskToBack(true)
            return
        }
        if (MeService.clockModeModel.contains(Build.MANUFACTURER + Build.MODEL) || MeSettings.isEnabled(this, MeSettings.KEY_CLOCK)) {
            val intent: Intent = Intent(this, MainActivity::class.java)
            startActivity(intent); finish()
        } else {
            super.onBackPressed()
        }
    }

    private fun setFullScreenClock() {
        clockMode = true
        showLyrics = MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)
        findViewById<LinearLayout>(R.id.btm_layout1).visibility = View.GONE
        findViewById<LinearLayout>(R.id.btm_layout2).visibility = View.GONE
        findViewById<LinearLayout>(R.id.btm_layout3).visibility = View.GONE
        findViewById<LinearLayout>(R.id.btm_layout4).visibility = View.GONE
        findViewById<LinearLayout>(R.id.music_progress_layout).visibility = View.GONE

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val tvTop = findViewById<TextView>(R.id.tv_top)
        val tvTime = findViewById<TextView>(R.id.tv_time)
        val tvDate = findViewById<TextView>(R.id.tv_date)
        val circularMusicProgress = findViewById<CircularMusicProgressView>(R.id.circular_music_progress)
        val rootLayout = findViewById<LinearLayout>(R.id.root_layout)
        var realHeightPixels = rootLayout.height
        var realWidthPixels = rootLayout.width
        
        if (Build.MODEL == "HPN_XH") {
            val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (statusBarId != 0) realHeightPixels += resources.getDimensionPixelSize(statusBarId)
        }
        
        isVerticalLayout = displayMetrics.heightPixels / displayMetrics.widthPixels.toFloat() > 1.15 || MeSettings.isEnabled(this, MeSettings.KEY_VERTICAL)
        val isRound = rootLayout.height == rootLayout.width || MeSettings.isEnabled(this, MeSettings.KEY_ROUND)
        circularMusicProgress.visibility = if (isRound) View.VISIBLE else View.GONE
        
        if (isRound) {
            val topPadding = realHeightPixels / 8
            val bottomPadding = (topPadding - (realWidthPixels - realHeightPixels).coerceAtLeast(0)).coerceAtLeast(0)
            rootLayout.setPadding(0, topPadding, 0, bottomPadding)
            val textHorizontalPadding = 10
            val edgeHorizontalPadding = (realWidthPixels / 7) + textHorizontalPadding
            tvTop.setPadding(edgeHorizontalPadding, tvTop.paddingTop, edgeHorizontalPadding, tvTop.paddingBottom)
            tvTime.setPadding(textHorizontalPadding, tvTime.paddingTop, textHorizontalPadding, tvTime.paddingBottom)
            tvDate.setPadding(edgeHorizontalPadding, tvDate.paddingTop, edgeHorizontalPadding, tvDate.paddingBottom)
            realHeightPixels -= topPadding + bottomPadding
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isNoSeconds = MeSettings.isEnabled(this, MeSettings.KEY_TSS)

        // 计算基准高度
        var baseTimeHeightRatio = 0.75f
        if (isRound || isVerticalLayout || showLyrics) {
            baseTimeHeightRatio = 0.5f
        } else if (isLandscape && isNoSeconds) {
            baseTimeHeightRatio = 0.85f
        }
        baseTimeHeight = (realHeightPixels * baseTimeHeightRatio).toInt()
        
        tvTop.layoutParams.height = if (isRound || isVerticalLayout || showLyrics) (realHeightPixels * 0.25).toInt() else 0
        enableTop = isRound || isVerticalLayout || showLyrics
        tvDate.layoutParams.height = (realHeightPixels * 0.25).toInt()

        // 初始化防烧屏参数
        val density = displayMetrics.density
        burnInVelocityX = (if (Math.random() > 0.5) 1 else -1) * 15f * density
        burnInVelocityY = (if (Math.random() > 0.5) 1 else -1) * 15f * density
        maxBurnInOffsetX = realWidthPixels * 0.12f
        maxBurnInOffsetY = realHeightPixels * 0.12f
        
        burnInOffsetX = (Math.random() * maxBurnInOffsetX * 2 - maxBurnInOffsetX).toFloat()
        burnInOffsetY = (Math.random() * maxBurnInOffsetY * 2 - maxBurnInOffsetY).toFloat()

        // 🌟 启动时应用所有保存的自定义设置
        applyTimeSettings(tvTime, tvDate, getFontScale(), getFontWeight(), getTimeDateMargin())
        applyDateSettings(tvDate, getDateFontScale())

        if (isVerticalLayout) {
            if (isNoSeconds) {
                if (MeSettings.isEnabled(this, MeSettings.KEY_T24)) sdfHmsmde = SimpleDateFormat("H:mm\nss.M月d日 E")
                else sdfHmsmde = SimpleDateFormat("h:mm\nss.M月d日 E")
            }
            tvTime.maxLines = 2
        }
        if (isRound) isVerticalLayout = true
    }

    private fun updateMusicProgress(seekBar: SeekBar, positionView: TextView, durationView: TextView, position: Int?, duration: Int) {
        if (position == null || duration <= 0) {
            if (seekBar.max != 0) seekBar.max = 0
            if (seekBar.progress != 0) seekBar.progress = 0
            if (seekBar.isEnabled) seekBar.isEnabled = false
            val emptyTime = formatMusicTime(0)
            setTextIfChanged(positionView, emptyTime)
            setTextIfChanged(durationView, emptyTime)
            return
        }
        if (!seekBar.isEnabled) seekBar.isEnabled = true
        if (seekBar.max != duration) seekBar.max = duration
        if (!isUserSeeking) {
            val progress = position.coerceIn(0, duration)
            if (seekBar.progress != progress) seekBar.progress = progress
            setTextIfChanged(positionView, formatMusicTime(position))
        }
        setTextIfChanged(durationView, formatMusicTime(duration))
    }

    private fun setTextIfChanged(view: TextView, text: CharSequence) {
        if (view.text.toString() != text.toString()) view.text = text
    }

    private fun seekMusicTo(target: Int) { MeService.me?.seekPlaybackTo(target) }

    private fun formatMusicTime(millis: Int): String {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%d:%02d", minutes, seconds)
    }

    private fun setFullscreen() {
        if (Build.MODEL == "HPN_XH") {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onDestroy() {
        if (runnable != null) timeHandler.removeCallbacks(runnable!!)
        me = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(keyEvent: KeyEvent?): Boolean {
        if (clockMode) {
            when (keyEvent?.action) {
                KeyEvent.ACTION_DOWN -> { if (MeService.me?.keyHandle(keyEvent.keyCode, true) == true) return true }
                KeyEvent.ACTION_UP -> { if (MeService.me?.keyHandle(keyEvent.keyCode, false) == true) return true }
            }
        }
        return super.dispatchKeyEvent(keyEvent)
    }

    private fun mediaButtonReceiverInit() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        mediaComponentName = ComponentName(packageName, MeMediaButtonReceiver::class.java.name).apply {
            packageManager.setComponentEnabledSetting(this, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            if (Build.VERSION.SDK_INT >= 21) {
                mediaSessionCompat = MediaSessionCompat(this@ClockActivity, "WorkdayAlarmClock", this, null).apply {
                    setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
                    setCallback(object : MediaSessionCompat.Callback() {
                        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                            MeMediaButtonReceiver().onReceive(this@ClockActivity, mediaButtonEvent); return true
                        }
                    }, Handler(Looper.getMainLooper()))
                    isActive = true
                }
            } else {
                audioManager.registerMediaButtonEventReceiver(this)
            }
        }
    }

    private fun mediaButtonReceiverDestroy() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= 21) {
            mediaSessionCompat?.let { it.setCallback(null); it.release() }
        } else {
            mediaComponentName?.let { audioManager.unregisterMediaButtonEventReceiver(it) }
        }
    }

    // ================= 🌟 农历算法开始 =================
    private val lunarInfo = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04bd7, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, 0x14b63
    )

    private fun lYearDays(y: Int): Int {
        var sum = 348
        var i = 0x8000
        while (i > 0x8) {
            if ((lunarInfo[y - 1900] and i) != 0) sum += 1
            i = i shr 1
        }
        return sum + leapDays(y)
    }

    private fun leapDays(y: Int): Int {
        return if (leapMonth(y) != 0) {
            if ((lunarInfo[y - 1900] and 0x10000) != 0) 30 else 29
        } else 0
    }

    private fun leapMonth(y: Int): Int {
        return lunarInfo[y - 1900] and 0xf
    }

    private fun monthDays(y: Int, m: Int): Int {
        return if ((lunarInfo[y - 1900] and (0x10000 shr m)) != 0) 30 else 29
    }

    private data class LunarDate(val year: Int, val month: Int, val day: Int, val isLeap: Boolean)

    private fun getLunarDate(year: Int, month: Int, day: Int): LunarDate {
        val utcZone = TimeZone.getTimeZone("UTC")
        val cal1 = Calendar.getInstance(utcZone)
        cal1.set(year, month - 1, day, 0, 0, 0)
        cal1.set(Calendar.MILLISECOND, 0)
        
        val cal2 = Calendar.getInstance(utcZone)
        cal2.set(1900, 0, 31, 0, 0, 0)
        cal2.set(Calendar.MILLISECOND, 0)
        
        var offset = ((cal1.timeInMillis - cal2.timeInMillis) / 86400000).toInt()
        
        var i = 1900
        var temp = 0
        while (i < 2050 && offset > 0) {
            temp = lYearDays(i)
            offset -= temp
            i++
        }
        if (offset < 0) {
            offset += temp
            i--
        }
        val lunarYear = i
        val leap = leapMonth(lunarYear)
        var isLeap = false

        i = 1
        while (i < 13 && offset > 0) {
            if (leap > 0 && i == (leap + 1) && !isLeap) {
                --i
                isLeap = true
                temp = leapDays(lunarYear)
            } else {
                temp = monthDays(lunarYear, i)
            }
            if (isLeap && i == (leap + 1)) {
                isLeap = false
            }
            offset -= temp
            i++
        }

        if (offset == 0 && leap > 0 && i == leap + 1) {
            if (isLeap) {
                isLeap = false
            } else {
                isLeap = true
                --i
            }
        }
        if (offset < 0) {
            offset += temp
            --i
        }

        return LunarDate(lunarYear, i, offset + 1, isLeap)
    }

    private val nStr1 = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
    private val nStr2 = arrayOf("初", "十", "廿", "卅", "□")

    private fun getCDay(d: Int): String {
        return when (d) {
            10 -> "初十"
            20 -> "二十"
            30 -> "三十"
            else -> nStr2[d / 10] + nStr1[d % 10]
        }
    }

    // 🌟 修复：月份加上“月”字，例如“正月”、“二月”
    private fun getCMon(m: Int): String {
        return if (m == 1) "正月" else "${nStr1[m]}月"
    }

    private fun getLunarString(year: Int, month: Int, day: Int): String {
        val lunar = getLunarDate(year, month, day)
        var monStr = getCMon(lunar.month)
        val dayStr = getCDay(lunar.day)
        if (lunar.isLeap) {
            monStr = "闰$monStr" // 例如：闰正月、闰二月
        }
        return "$monStr$dayStr" // 拼接后即为：正月初一、二月十五、闰四月初八
    }
    // ================= 🌟 农历算法结束 =================
}
