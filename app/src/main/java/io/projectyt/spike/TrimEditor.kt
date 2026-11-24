package io.projectyt.spike

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.text.TextWatcher
import android.text.Editable
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
internal class TrimEditor(
    private val activity: Activity,
    private val source: PreparedVideo,
    options: DownloadOptions,
    private val worker: ExecutorService,
    private val onDismiss: () -> Unit,
    private val download: (ClipRange) -> Unit,
) {
    private val initialRange = options.rangeFor(source.durationUs)
    private val durationSeconds = ((source.durationUs + 999_999) / 1_000_000)
        .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    private val start = timestamp(R.string.trim_start)
    private val end = timestamp(R.string.trim_end)
    private val startSlider = slider(R.string.trim_start)
    private val endSlider = slider(R.string.trim_end)
    private val texture = TextureView(activity)
    private val info = TextView(activity).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val preview = Button(activity).apply {
        setText(R.string.trim_preview)
        isAllCaps = false
    }
    private var player: ExoPlayer? = null
    private var job: Future<*>? = null
    private var approved: ClipRange? = initialRange
    private var updating = false
    private var dialog: AlertDialog? = null

    fun show() {
        if (source.durationUs <= 0) {
            dialog = AlertDialog.Builder(activity).setTitle(R.string.trim)
                .setMessage(R.string.trim_duration_unavailable)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { onDismiss() }.show()
            return
        }
        showRange(initialRange)
        val previewPanel = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(texture, FrameLayout.LayoutParams(dp(previewHeight() * 16 / 9), -1, Gravity.CENTER))
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(previewPanel, LinearLayout.LayoutParams(-1, dp(previewHeight())))
            addView(markerRow(R.string.trim_start, start, startSlider))
            addView(markerRow(R.string.trim_end, end, endSlider))
            addView(info)
            addView(preview, LinearLayout.LayoutParams(-1, -2))
        }
        val modal = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.trim_title, source.title))
            .setView(ScrollView(activity).apply { addView(panel) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download) { _, _ -> approved?.let(download) }
            .create()
        modal.setOnDismissListener {
            job?.cancel(true)
            player?.release()
            player = null
            dialog = null
            onDismiss()
        }
        dialog = modal
        modal.show()
        modal.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF18222F.toInt())
                cornerRadius = dp(18).toFloat()
            })
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        preview.setOnClickListener { checkAndPreview() }
        preview.requestFocus()
        installEditingListeners()
        describe(initialRange, false)
    }

    fun dismiss() = dialog?.dismiss()

    private fun installEditingListeners() {
        watchTimestamp(start, startSlider)
        watchTimestamp(end, endSlider)
        bindSlider(startSlider, start)
        bindSlider(endSlider, end)
    }

    private fun watchTimestamp(field: EditText, slider: SeekBar) {
        val listener = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (updating) return
                runCatching { parseTrimTime(text.toString()) }.getOrNull()?.let {
                    slider.progress = (it / 1_000_000).coerceAtMost(durationSeconds.toLong()).toInt()
                }
                approved = null
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                player?.stop()
                info.setText(R.string.trim_check_range)
            }
            override fun afterTextChanged(text: Editable?) = Unit
        }
        field.addTextChangedListener(listener)
    }

    private fun bindSlider(slider: SeekBar, field: EditText) {
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            override fun onProgressChanged(bar: SeekBar?, seconds: Int, fromUser: Boolean) {
                if (fromUser) field.setText(trimTime(seconds * 1_000_000L))
            }
        })
    }

    private fun checkAndPreview() {
        val requested = runCatching {
            val startUs = parseTrimTime(start.text.toString())
            val enteredEndUs = parseTrimTime(end.text.toString())
            val endUs = if (enteredEndUs == durationSeconds * 1_000_000L) source.durationUs else enteredEndUs
            require(endUs <= source.durationUs) { activity.getString(R.string.trim_outside_source) }
            ClipRange(startUs, endUs)
        }.getOrElse {
            info.text = it.message
            return
        }
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(preview.windowToken, 0)
        player?.stop()
        setEditable(false)
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        approved = null
        info.setText(R.string.trim_checking)
        job = worker.submit {
            val result = runCatching { SourceClip.alignStart(source.video, requested) }
            activity.runOnUiThread {
                if (dialog?.isShowing != true) return@runOnUiThread
                setEditable(true)
                preview.requestFocus()
                result.onSuccess { range ->
                    approved = range
                    dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                    describe(range, range.startUs != requested.startUs)
                    play(range)
                }.onFailure {
                    info.text = if (it is IllegalArgumentException) it.message
                    else activity.getString(R.string.trim_preview_unavailable)
                }
            }
        }
    }

    private fun play(range: ClipRange) {
        val playback = player ?: ExoPlayer.Builder(activity).build().apply {
            setVideoTextureView(texture)
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    info.text = activity.getString(R.string.trim_preview_problem, info.text)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    texture.post { fitPreview(videoSize) }
                }
            })
            player = this
        }
        playback.setMediaSource(SourceClip.preview(activity, source, range))
        playback.prepare()
        playback.play()
    }

    private fun fitPreview(size: VideoSize) {
        if (texture.width == 0 || texture.height == 0 || size.width == 0 || size.height == 0) return
        val aspect = size.width * size.pixelWidthHeightRatio / size.height
        val viewAspect = texture.width.toFloat() / texture.height
        texture.setTransform(Matrix().apply {
            setScale(
                minOf(1f, aspect / viewAspect), minOf(1f, viewAspect / aspect),
                texture.width / 2f, texture.height / 2f,
            )
        })
    }

    private fun describe(range: ClipRange, adjusted: Boolean) {
        info.text = activity.getString(
            if (adjusted) R.string.trim_aligned_range else R.string.trim_range,
            trimTime(range.startUs, adjusted), trimTime(range.endUs), trimTime(range.durationUs, adjusted),
        )
    }

    private fun showRange(range: ClipRange) {
        updating = true
        start.setText(trimTime(range.startUs))
        val displayedEnd = if (range.endUs == source.durationUs) durationSeconds * 1_000_000L else range.endUs
        end.setText(trimTime(displayedEnd))
        startSlider.progress = (range.startUs / 1_000_000).toInt()
        endSlider.progress = (displayedEnd / 1_000_000).toInt()
        updating = false
    }

    private fun setEditable(enabled: Boolean) {
        listOf(start, end, startSlider, endSlider, preview).forEach { it.isEnabled = enabled }
    }

    private fun markerRow(title: Int, timestamp: EditText, slider: SeekBar) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            setText(title)
            labelFor = timestamp.id
        }, LinearLayout.LayoutParams(dp(44), -2))
        addView(timestamp, LinearLayout.LayoutParams(dp(82), -2))
        addView(slider, LinearLayout.LayoutParams(0, dp(44), 1f))
    }

    private fun timestamp(description: Int) = EditText(activity).apply {
        id = View.generateViewId()
        setSingleLine()
        keyListener = DigitsKeyListener.getInstance("0123456789:")
        contentDescription = activity.getString(description)
        textSize = 16f
    }

    private fun slider(description: Int) = SeekBar(activity).apply {
        max = durationSeconds
        keyProgressIncrement = maxOf(1, durationSeconds / 120)
        contentDescription = activity.getString(description)
        isFocusable = true
    }

    private fun previewHeight() = (activity.resources.configuration.screenHeightDp * 3 / 8).coerceIn(110, 260)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}

internal fun trimTime(timeUs: Long, fraction: Boolean = false): String {
    val seconds = timeUs / 1_000_000
    val time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    return if (fraction) time + String.format(Locale.ROOT, ".%03d", timeUs / 1000 % 1000) else time
}

internal fun parseTrimTime(value: String): Long {
    val parts = value.trim().split(':')
    val minutes = parts.firstOrNull()?.toIntOrNull()
    val seconds = parts.getOrNull(1)?.toIntOrNull()
    require(parts.size == 2 && minutes != null && minutes >= 0 && seconds != null && seconds in 0..59) {
        "Enter a time as minutes:seconds, for example 1:30."
    }
    return (minutes * 60L + seconds) * 1_000_000L
}
