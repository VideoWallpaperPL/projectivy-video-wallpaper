package io.projectyt.spike

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import java.util.concurrent.Executors

class SpikeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbnailWorker = Executors.newSingleThreadExecutor()
    private val thumbnails = LruCache<String, Bitmap>(HISTORY_LIMIT)
    private val thumbnailRequests = mutableSetOf<String>()
    private val thumbnailViews = mutableMapOf<String, ImageView>()
    private val tiles = mutableMapOf<String, View>()
    private lateinit var selection: Selection
    private lateinit var downloadNotice: DownloadNotice
    private lateinit var input: EditText
    private lateinit var download: Button
    private lateinit var addFile: Button
    private lateinit var status: TextView
    private lateinit var preview: VideoView
    private lateinit var placeholder: TextView
    private lateinit var historyLabel: TextView
    private lateinit var grid: LinearLayout
    private var started = false
    private var working = false
    private var previewId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selection = Selection(this)
        downloadNotice = DownloadNotice(this)
        input = EditText(this).apply {
            setHint(R.string.youtube_input)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(INK)
            setHintTextColor(MUTED)
            textSize = 14f
            background = focusBackground(FIELD, 10)
            setPadding(dp(12), 0, dp(12), 0)
            setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_GO) false else {
                    downloadLink()
                    true
                }
            }
        }
        download = button(R.string.download, primary = true, action = ::downloadLink)
        addFile = button(R.string.add_file, action = ::pickFile)
        status = label("", 12f, MUTED).apply {
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        historyLabel = label("", 20f, INK, bold = true).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(screen())
        acceptSharedInput(intent)
        if (input.text.isEmpty()) addFile.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        started = true
        renderHistory()
        if (!working) showPreview(selection.history().active)
    }

    override fun onStop() {
        started = false
        showPreview(null)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        thumbnailWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedInput(intent)
    }

    private fun screen(): View {
        val editor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(PANEL, 16, BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(input, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(download, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) })
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(addFile, LinearLayout.LayoutParams(WRAP_CONTENT, dp(38)))
                addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val previewCard = FrameLayout(this).apply {
            background = surface(FIELD, 14, BORDER)
            clipToOutline = true
            placeholder = label(getString(R.string.preview_label), 12f, MUTED).apply { gravity = Gravity.CENTER }
            addView(placeholder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            preview = VideoView(context).apply {
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.INVISIBLE
                setOnPreparedListener { player ->
                    player.isLooping = true
                }
                setOnErrorListener { _, _, _ ->
                    showPreview(null)
                    status.setText(R.string.preview_unavailable)
                    true
                }
            }
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        }
        val top = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp >= 640) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (orientation == LinearLayout.HORIZONTAL) {
                addView(editor, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(previewCard, LinearLayout.LayoutParams(dp(190), dp(112)).apply { marginStart = dp(14) })
            } else {
                addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(previewCard, LinearLayout.LayoutParams(MATCH_PARENT, dp(120)).apply { topMargin = dp(10) })
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            addView(label(getString(R.string.screen_title), 29f, INK, bold = true))
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(historyLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(16)
                bottomMargin = dp(9)
            })
            addView(grid)
        }
        return ScrollView(this).apply {
            setBackgroundColor(CANVAS)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }
    }

    private fun renderHistory(preferredFocus: String? = null) {
        val history = selection.history()
        historyLabel.setText(if (history.entries.isEmpty()) R.string.history_empty else R.string.history)
        grid.removeAllViews()
        thumbnailViews.clear()
        tiles.clear()
        repeat(3) { row ->
            val strip = LinearLayout(this)
            repeat(4) { column ->
                val entry = history.entries.getOrNull(row * 4 + column)
                val card = if (entry == null) {
                    View(this).apply {
                        background = surface(SLOT, 12, BORDER)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                } else {
                    tile(entry, entry.id == history.activeId)
                }
                strip.addView(card, LinearLayout.LayoutParams(0, dp(86), 1f).apply {
                    if (column > 0) marginStart = dp(10)
                })
            }
            grid.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (row > 0) topMargin = dp(10)
            })
        }
        preferredFocus?.let { id -> (tiles[id] ?: addFile).requestFocus() }
        if (history.entries.isEmpty() && !working && status.text.isEmpty()) status.setText(R.string.ready_to_add)
    }

    private fun tile(entry: SavedWallpaper, active: Boolean): View {
        val card = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            background = surface(SLOT, 12, BORDER)
            clipToOutline = true
            foreground = stateDrawable(surface(Color.TRANSPARENT, 12, MINT, 3), surface(Color.TRANSPARENT, 12))
            contentDescription = if (active) getString(R.string.active_title, entry.title) else entry.title
            setOnClickListener { if (!working) openActions(entry) }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val caption = label(entry.title, 13f, INK, bold = true).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(9), 0, dp(9), dp(7))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF0000910.toInt(), Color.TRANSPARENT))
        }
        card.addView(caption, FrameLayout.LayoutParams(MATCH_PARENT, dp(36), Gravity.BOTTOM))
        if (active) {
            val badge = label(getString(R.string.active), 10f, DARK_INK, bold = true).apply {
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = surface(MINT, 6)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(7)
                marginStart = dp(7)
            })
        }
        tiles[entry.id] = card
        thumbnailViews[entry.id] = image
        loadThumbnail(entry)
        return card
    }

    private fun loadThumbnail(entry: SavedWallpaper) {
        val cached = thumbnails.get(entry.id)
        if (cached != null) {
            thumbnailViews[entry.id]?.setImageBitmap(cached)
            return
        }
        if (!thumbnailRequests.add(entry.id)) return
        val file = selection.file(entry)
        thumbnailWorker.execute {
            val reader = MediaMetadataRetriever()
            val bitmap = try {
                reader.setDataSource(file.absolutePath)
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val time = (duration / 3).coerceIn(0, 12_000) * 1_000
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    reader.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                } else {
                    reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                        val scaled = Bitmap.createScaledBitmap(frame, 320, 180, true)
                        if (scaled !== frame) frame.recycle()
                        scaled
                    }
                }
            } catch (_: Exception) {
                null
            } finally {
                reader.release()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                thumbnailRequests.remove(entry.id)
                if (bitmap != null && file.isFile) {
                    thumbnails.put(entry.id, bitmap)
                    thumbnailViews[entry.id]?.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun openActions(entry: SavedWallpaper) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val heading = label(entry.title, 17f, INK, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val set = button(R.string.set_background, primary = true) {
            dialog.dismiss()
            submit(R.string.setting_background, entry.id) { selection.activate(entry.id) }
        }
        val delete = button(R.string.delete, destructive = true) {
            dialog.dismiss()
            submit(R.string.deleting_background, entry.id) {
                selection.delete(entry.id)
                selection.history().active
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = surface(PANEL, 18, BORDER)
            val bitmap = thumbnails.get(entry.id)
            if (bitmap != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = surface(FIELD, 10)
                    clipToOutline = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(MATCH_PARENT, dp(82)).apply { bottomMargin = dp(10) })
            }
            addView(heading)
            addView(LinearLayout(context).apply {
                addView(set, LinearLayout.LayoutParams(0, dp(42), 2f))
                addView(delete, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        dialog.setContentView(panel)
        dialog.setTitle(getString(R.string.wallpaper_actions))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
        }
        dialog.show()
        dialog.window?.setLayout(dp(310).coerceAtMost(resources.displayMetrics.widthPixels - dp(32)), WRAP_CONTENT)
        set.requestFocus()
    }

    private fun acceptSharedInput(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return
        val url = runCatching { YouTubeUrl.canonical(shared) }.getOrNull()
        if (url == null) {
            status.setText(R.string.invalid_share)
            return
        }
        input.setText(url)
        if (!working) download.requestFocus()
    }

    private fun downloadLink() {
        if (working) return
        val submitted = input.text.toString()
        val url = try {
            YouTubeUrl.canonical(submitted)
        } catch (error: IllegalArgumentException) {
            status.text = error.message
            return
        }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        if (downloadNotice.acknowledged()) {
            startDownload(submitted, url)
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.content_notice_title)
                .setMessage(R.string.content_notice)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.acknowledge_notice) { _, _ ->
                    downloadNotice.acknowledge()
                    startDownload(submitted, url)
                }
                .show()
        }
    }

    private fun startDownload(submitted: String, url: String) {
        submit(R.string.starting_download, clearInput = submitted) {
            MediaResolver.download(applicationContext, url, ::reportProgress)
        }
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        if (working) return
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/mp4"
            }, PICK_VIDEO)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/mp4"
                }, PICK_VIDEO)
            } catch (_: ActivityNotFoundException) {
                status.setText(R.string.no_file_picker)
            }
        }
    }

    @Deprecated("Uses the platform document picker from a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK || working) return
        val uri = data?.data ?: return
        submit(R.string.opening_file) { MediaResolver.import(applicationContext, uri, ::reportProgress) }
    }

    private fun reportProgress(percent: Int?) {
        runOnUiThread {
            if (isDestroyed || !working) return@runOnUiThread
            status.text = if (percent == null) getString(R.string.preparing_clip)
            else getString(R.string.clip_progress, percent)
        }
    }

    private fun submit(
        message: Int, focusId: String? = null, clearInput: String? = null, action: () -> SavedWallpaper?,
    ) {
        if (working) return
        working = true
        download.isEnabled = false
        addFile.isEnabled = false
        showPreview(null)
        status.setText(message)
        worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                working = false
                download.isEnabled = true
                addFile.isEnabled = true
                result.onSuccess { entry ->
                    if (clearInput != null && input.text.toString() == clearInput) input.text.clear()
                    status.text = entry?.let { getString(R.string.active_title, it.title) } ?: getString(R.string.ready_to_add)
                    renderHistory(entry?.id ?: focusId)
                    requestWallpaperUpdate()
                }.onFailure { error ->
                    status.text = if (error is IllegalArgumentException || error is IllegalStateException) error.message
                    else getString(R.string.could_not_prepare)
                }
                if (started) showPreview(selection.history().active)
            }
        }
    }

    private fun showPreview(entry: SavedWallpaper?) {
        if (entry?.id == previewId) return
        previewId = entry?.id
        preview.stopPlayback()
        placeholder.visibility = if (entry == null) View.VISIBLE else View.INVISIBLE
        preview.visibility = if (entry == null) View.INVISIBLE else View.VISIBLE
        if (entry != null) {
            preview.setVideoURI(selection.uri(entry))
            preview.start()
        }
    }

    private fun requestWallpaperUpdate() {
        sendBroadcast(Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
            setPackage(PROJECTIVY_PACKAGE)
            putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, getString(R.string.provider_uuid))
            putExtra(
                WallpaperProviderContract.EXTRA_UPDATE_REASON,
                WallpaperProviderContract.UpdateReason.DATA_CHANGED,
            )
        })
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(
        title: Int, primary: Boolean = false, destructive: Boolean = false, action: () -> Unit,
    ) = Button(this).apply {
        setText(title)
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
        stateListAnimator = null
        backgroundTintList = null
        background = focusBackground(if (primary) MINT else FIELD, 10)
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(MUTED, if (primary) DARK_INK else if (destructive) DANGER else INK),
        ))
        setOnClickListener { action() }
    }

    private fun focusBackground(color: Int, radius: Int) =
        stateDrawable(surface(color, radius, OUTLINE, 3), surface(color, radius, BORDER), surface(SLOT, radius, BORDER))

    private fun stateDrawable(focused: GradientDrawable, normal: GradientDrawable, disabled: GradientDrawable? = null) =
        StateListDrawable().apply {
            if (disabled != null) addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused.constantState?.newDrawable() ?: focused)
            addState(intArrayOf(), normal)
        }

    private fun surface(color: Int, radius: Int, stroke: Int? = null, width: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(width), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val PICK_VIDEO = 1
        val CANVAS = Color.rgb(12, 17, 25)
        val PANEL = Color.rgb(24, 34, 47)
        val SLOT = Color.rgb(19, 27, 38)
        val FIELD = Color.rgb(15, 24, 34)
        val BORDER = Color.rgb(43, 58, 74)
        val INK = Color.rgb(239, 246, 249)
        val MUTED = Color.rgb(168, 187, 202)
        val MINT = Color.rgb(125, 229, 190)
        val OUTLINE = Color.rgb(208, 255, 235)
        val DARK_INK = Color.rgb(7, 34, 29)
        val DANGER = Color.rgb(255, 164, 164)
    }
}
