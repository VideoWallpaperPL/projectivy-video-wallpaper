package io.projectyt.spike

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private var range = options.rangeFor(source.durationUs)
    private val handler = Handler(Looper.getMainLooper())
    private val start = timestamp(R.string.trim_start)
    private val end = timestamp(R.string.trim_end)
    private val texture = TextureView(activity)
    private val previewStatus = label(activity.getString(R.string.trim_preview_unavailable), 13f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(0xD818222F.toInt())
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val title = label(source.title, 15f).apply {
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
    }
    private val position = label("", 14f)
    private val length = label("", 19f, bold = true).apply {
        gravity = Gravity.END
        setTextColor(ACCENT)
    }
    private val info = label("", 13f).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setTextColor(MUTED)
    }
    private val play = transport("▶", R.string.trim_play, ::togglePlayback)
    private val jumpStart = transport("│◀", R.string.trim_jump_start) { jumpToBoundary(true) }
    private val jumpEnd = transport("▶│", R.string.trim_jump_end) { jumpToBoundary(false) }
    private val timeline by lazy {
        TrimTimeline(activity, source.durationUs, range, ::moveHandle) { startHandle, focused ->
            if (focused && !checking) {
                showingRemoteHint = true
                info.text = activity.getString(
                    R.string.trim_remote_hint, activity.getString(if (startHandle) R.string.trim_start else R.string.trim_end),
                )
            } else if (showingRemoteHint) {
                showingRemoteHint = false
                info.setText(R.string.trim_editor_hint)
            }
        }
    }
    private var player: ExoPlayer? = null
    private var job: Future<*>? = null
    private var dialog: AlertDialog? = null
    private var checking = false
    private var showingRemoteHint = false
    private var confirmedRange: ClipRange? = null
    private var displayedSecond = -1L
    private var nextSeekUs = 0L
    private val delayedSeek = Runnable { seekTo(nextSeekUs) }
    private val ticker = object : Runnable {
        override fun run() {
            val playback = player ?: return
            val currentUs = playback.currentPosition * 1_000L
            if (playback.playWhenReady && currentUs >= range.endUs) {
                playback.pause()
                seekTo(range.startUs)
            } else {
                displayPosition(currentUs)
            }
            handler.postDelayed(this, 100)
        }
    }

    fun show() {
        if (source.durationUs <= 0) {
            dialog = AlertDialog.Builder(activity).setTitle(R.string.choose_clip)
                .setMessage(R.string.trim_duration_unavailable)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { onDismiss() }.show()
            return
        }
        val close = action(R.string.cancel_clip, false) { dismiss() }
        val save = action(R.string.download, true, ::checkAndDownload)
        val modal = AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply {
                isFillViewport = true
                addView(panel(close, save))
            })
            .create()
        downloadButton = save
        modal.setOnDismissListener {
            job?.cancel(true)
            handler.removeCallbacksAndMessages(null)
            player?.release()
            player = null
            dialog = null
            onDismiss()
        }
        dialog = modal
        updateFields()
        info.setText(R.string.trim_editor_hint)
        displayPosition(range.startUs)
        modal.show()
        modal.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(740).coerceAtMost(activity.resources.displayMetrics.widthPixels - dp(28)), -2)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        bindTimestamp(start, true)
        bindTimestamp(end, false)
        play.requestFocus()
        startPlayer()
    }

    private lateinit var downloadButton: Button

    fun dismiss() = dialog?.dismiss()

    private fun panel(close: Button, save: Button) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL, 18)
        setPadding(dp(22), dp(17), dp(22), dp(14))
        addView(label(activity.getString(R.string.choose_clip), 23f, bold = true))
        addView(title, rowParams(top = 3))
        addView(FrameLayout(activity).apply {
            background = rounded(Color.BLACK, 10)
            clipToOutline = true
            addView(texture, FrameLayout.LayoutParams(-1, -1))
            addView(previewStatus, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }, LinearLayout.LayoutParams(-1, dp(previewHeight())).apply { topMargin = dp(12) })
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            addView(position, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
            for (control in listOf(jumpStart, play, jumpEnd)) {
                addView(control, LinearLayout.LayoutParams(dp(52), dp(40)).apply { marginStart = dp(5) })
            }
        }, rowParams(top = 7))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(WELL, 12)
            setPadding(dp(9), 0, dp(9), dp(6))
            addView(timeline, LinearLayout.LayoutParams(-1, dp(56)))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(activity.getString(R.string.trim_start), 13f))
                addView(start, LinearLayout.LayoutParams(dp(87), dp(42)).apply { marginStart = dp(5) })
                addView(label(activity.getString(R.string.trim_end), 13f).apply {
                    setPadding(dp(10), 0, 0, 0)
                })
                addView(end, LinearLayout.LayoutParams(dp(87), dp(42)).apply { marginStart = dp(5) })
                addView(length, LinearLayout.LayoutParams(0, -2, 1f))
            }, rowParams())
        }, rowParams(top = 9))
        addView(info, rowParams(top = 6))
        addView(LinearLayout(activity).apply {
            gravity = Gravity.END
            addView(close, LinearLayout.LayoutParams(-2, dp(43)))
            addView(save, LinearLayout.LayoutParams(-2, dp(43)).apply { marginStart = dp(8) })
        }, rowParams(top = 8))
    }

    private fun startPlayer() {
        player = ExoPlayer.Builder(activity).build().apply {
            setVideoTextureView(texture)
            addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = showPlaying(playWhenReady)
                override fun onPlayerError(error: PlaybackException) {
                    previewStatus.visibility = View.VISIBLE
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) previewStatus.visibility = View.GONE
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    texture.post { fitPreview(videoSize) }
                }
            })
            setMediaSource(SourceClip.preview(activity, source), range.startUs / 1000)
            prepare()
        }
        handler.post(ticker)
    }

    private fun showPlaying(isPlaying: Boolean) {
        play.text = if (isPlaying) "Ⅱ" else "▶"
        play.contentDescription = activity.getString(if (isPlaying) R.string.trim_pause else R.string.trim_play)
    }

    private fun togglePlayback() {
        val playback = player ?: return
        val previous = range
        if (!commitFields()) return
        handler.removeCallbacks(delayedSeek)
        if (playback.playWhenReady) {
            playback.pause()
        } else {
            if (range != previous || playback.currentPosition * 1000 !in range.startUs until lastFramePosition()) {
                seekTo(range.startUs)
            }
            playback.play()
        }
    }

    private fun jumpToBoundary(moveStart: Boolean) {
        if (!commitFields()) return
        handler.removeCallbacks(delayedSeek)
        seekTo(if (moveStart) range.startUs else lastFramePosition())
    }

    private fun displayPosition(timeUs: Long) {
        timeline.showPlayhead(timeUs)
        val second = timeUs / 1_000_000
        if (second == displayedSecond) return
        displayedSecond = second
        position.text = activity.getString(R.string.trim_position, trimTime(timeUs), trimTime(source.durationUs))
    }

    private fun seekTo(timeUs: Long) {
        player?.seekTo(timeUs / 1_000L)
        displayPosition(timeUs)
    }

    private fun lastFramePosition() = (range.endUs - 120_000).coerceAtLeast(range.startUs)

    private fun moveHandle(changeStart: Boolean, timeUs: Long) {
        if (checking) return
        range = range.moveBoundary(changeStart, timeUs, source.durationUs)
        if (confirmedRange != null && !showingRemoteHint) info.setText(R.string.trim_editor_hint)
        confirmedRange = null
        updateFields()
        player?.pause()
        nextSeekUs = if (changeStart) range.startUs else lastFramePosition()
        timeline.showPlayhead(nextSeekUs)
        handler.removeCallbacks(delayedSeek)
        handler.postDelayed(delayedSeek, 160)
    }

    private fun updateFields() {
        start.setText(trimTime(range.startUs, range.startUs % 1_000_000 != 0L))
        end.setText(trimTime(range.endUs, range.endUs % 1_000_000 != 0L))
        val showFraction = confirmedRange != null && range.durationUs % 1_000_000 != 0L
        length.text = activity.getString(R.string.trim_selected, trimTime(range.durationUs, showFraction))
        timeline.showSelection(range)
    }

    private fun bindTimestamp(field: EditText, isStart: Boolean) {
        field.setOnFocusChangeListener { _, focused -> if (!focused) commitField(field, isStart) }
        field.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (commitField(field, isStart)) {
                activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
                play.requestFocus()
            }
            true
        }
    }

    private fun commitField(field: EditText, isStart: Boolean): Boolean {
        if (checking) return true
        val currentUs = if (isStart) range.startUs else range.endUs
        if (field.text.toString() == trimTime(currentUs, currentUs % 1_000_000 != 0L)) return true
        val entered = runCatching { parseTrimTime(field.text.toString()) }.getOrElse {
            field.error = it.message
            return false
        }
        if (entered > source.durationUs) {
            field.error = activity.getString(R.string.trim_outside_source)
            return false
        }
        field.error = null
        moveHandle(isStart, entered)
        return true
    }

    private fun commitFields() = commitField(start, true) && commitField(end, false)

    private fun checkAndDownload() {
        if (!commitFields()) return
        val requested = range
        if (requested.startUs == 0L || confirmedRange == requested) {
            download(requested)
            dismiss()
            return
        }
        setChecking(true)
        player?.pause()
        info.setText(R.string.trim_checking)
        job = worker.submit {
            val result = runCatching { SourceClip.alignStart(source.video, requested) }
            activity.runOnUiThread {
                if (dialog?.isShowing != true) return@runOnUiThread
                setChecking(false)
                result.onSuccess { adjusted ->
                    if (adjusted == requested) {
                        download(adjusted)
                        dismiss()
                    } else {
                        range = adjusted
                        confirmedRange = adjusted
                        updateFields()
                        seekTo(range.startUs)
                        info.text = activity.getString(R.string.trim_aligned_start, trimTime(adjusted.startUs, true))
                        downloadButton.requestFocus()
                    }
                }.onFailure {
                    info.text = if (it is IllegalArgumentException) it.message
                    else activity.getString(R.string.trim_could_not_align)
                }
            }
        }
    }

    private fun setChecking(value: Boolean) {
        checking = value
        listOf(start, end, timeline, play, jumpStart, jumpEnd, downloadButton).forEach { it.isEnabled = !value }
    }

    private fun fitPreview(size: VideoSize) {
        if (texture.width == 0 || texture.height == 0 || size.width == 0 || size.height == 0) return
        val aspect = size.width * size.pixelWidthHeightRatio / size.height
        val viewAspect = texture.width.toFloat() / texture.height
        texture.setTransform(Matrix().apply {
            setScale(minOf(1f, aspect / viewAspect), minOf(1f, viewAspect / aspect),
                texture.width / 2f, texture.height / 2f)
        })
    }

    private fun rowParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(INK)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun action(text: Int, accent: Boolean, clicked: () -> Unit) = Button(activity).apply {
        setText(text)
        isAllCaps = false
        setTextColor(if (accent) 0xFF072C24.toInt() else INK)
        backgroundTintList = null
        background = controlBackground(if (accent) ACCENT else WELL)
        stateListAnimator = null
        setPadding(dp(17), 0, dp(17), 0)
        setOnClickListener { clicked() }
    }

    private fun transport(symbol: String, description: Int, clicked: () -> Unit) = Button(activity).apply {
        text = symbol
        contentDescription = activity.getString(description)
        setTextColor(INK)
        textSize = 16f
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        backgroundTintList = null
        background = controlBackground(WELL)
        setOnClickListener { clicked() }
    }

    private fun timestamp(description: Int) = EditText(activity).apply {
        id = View.generateViewId()
        setSingleLine()
        keyListener = DigitsKeyListener.getInstance("0123456789:.")
        imeOptions = EditorInfo.IME_ACTION_DONE
        contentDescription = activity.getString(description)
        setSelectAllOnFocus(true)
        setTextColor(INK)
        textSize = 15f
    }

    private fun controlBackground(color: Int) = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), rounded(0xFF5F706E.toInt(), 8))
        addState(intArrayOf(android.R.attr.state_focused), rounded(color, 8, if (color == ACCENT) Color.WHITE else ACCENT))
        addState(intArrayOf(), rounded(color, 8))
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun previewHeight() = (activity.resources.configuration.screenHeightDp * 39 / 100).coerceIn(105, 320)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()

    private companion object {
        val PANEL = 0xFF18222F.toInt()
        val WELL = 0xFF101A26.toInt()
        val INK = 0xFFF0F8FA.toInt()
        val MUTED = 0xFFBACBD5.toInt()
        val ACCENT = 0xFF86E7CC.toInt()
    }
}

internal fun trimTime(timeUs: Long, fraction: Boolean = false): String {
    val seconds = timeUs / 1_000_000
    val time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    return if (fraction) time + String.format(Locale.ROOT, ".%03d", timeUs / 1000 % 1000) else time
}

internal fun parseTrimTime(value: String): Long {
    val clock = value.trim().split(':', '.')
    val minutes = clock.firstOrNull()?.toIntOrNull()
    val seconds = clock.getOrNull(1)?.toIntOrNull()
    val millis = clock.getOrNull(2)?.takeIf { it.length in 1..3 }?.padEnd(3, '0')?.toIntOrNull()
    require(clock.size in 2..3 && minutes != null && minutes >= 0 && seconds != null && seconds in 0..59 &&
        (clock.size == 2 || (millis != null && millis in 0..999))
    ) {
        "Enter a time as minutes:seconds, for example 1:30."
    }
    return (minutes * 60L + seconds) * 1_000_000L + (millis ?: 0) * 1_000L
}
