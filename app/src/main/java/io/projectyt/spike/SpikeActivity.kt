package io.projectyt.spike

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private val pickerThumbnails = mutableMapOf<String, ImageView>()
    private val tiles = mutableMapOf<String, View>()
    private lateinit var selection: Selection
    private lateinit var downloadNotice: DownloadNotice
    private lateinit var input: EditText
    private lateinit var clearLink: ImageButton
    private lateinit var download: Button
    private lateinit var cancelWork: Button
    private lateinit var audioOption: Button
    private lateinit var resolutionOption: Button
    private lateinit var modeOption: Button
    private lateinit var durationOption: Button
    private lateinit var selectionButton: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var preview: VideoView
    private lateinit var previewControl: ImageButton
    private lateinit var placeholder: TextView
    private lateinit var historyLabel: TextView
    private lateinit var grid: LinearLayout
    private var started = false
    private var working = false
    private var advanced = false
    private var attachedEditorPending = false
    private var previewId: String? = null
    private var previewPaused = false
    private var pendingCopyId: String? = null
    private var options = DownloadOptions()
    private var operation: Operation? = null
    private var trimEditor: TrimEditor? = null
    private val openAttachedEditor = Runnable {
        if (attachedEditorPending && started && !working && trimEditor == null) openCurrentLink()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selection = Selection(this)
        downloadNotice = DownloadNotice(this)
        pendingCopyId = savedInstanceState?.getString("pendingCopyId")
        advanced = savedInstanceState?.getBoolean("advancedMode") ?: false
        options = DownloadOptions(
            minutes = savedInstanceState?.getInt("simpleMinutes", 2) ?: 2,
            includeAudio = savedInstanceState?.getBoolean("downloadAudio", true) ?: true,
            height = savedInstanceState?.getInt("downloadHeight", DEFAULT_HEIGHT) ?: DEFAULT_HEIGHT,
        )
        clearLink = iconButton(R.drawable.ui_close, R.string.clear_link) {
            input.text.clear()
            input.requestFocus()
        }.apply { visibility = View.GONE }
        input = EditText(this).apply {
            setHint(R.string.youtube_input)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(INK)
            setHintTextColor(MUTED)
            textSize = 15f
            background = stateDrawable(
                surface(FIELD, 15, OUTLINE, 2), surface(FIELD, 15, BORDER),
            )
            setPaddingRelative(dp(49), 0, dp(42), 0)
            setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_GO) false else {
                    openCurrentLink()
                    true
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    clearLink.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
                    scheduleAttachedEditor()
                }
            })
        }
        download = button(R.string.download, primary = true, action = ::openCurrentLink).apply {
            homeButton(R.drawable.ui_download, prominent = true)
        }
        cancelWork = button(android.R.string.cancel, action = ::cancelOperation).apply {
            homeButton(R.drawable.ui_close)
            visibility = View.GONE
        }
        audioOption = button(R.string.audio_on) {
            options = options.copy(includeAudio = !options.includeAudio)
            updateOptions()
        }
        audioOption.homeButton(R.drawable.ui_audio)
        resolutionOption = button(R.string.resolution, action = ::chooseResolution).apply {
            homeButton(R.drawable.ui_quality)
        }
        modeOption = button(R.string.simple_mode) {
            advanced = !advanced
            updateOptions()
            status.setText(readyHint())
            scheduleAttachedEditor()
        }
        modeOption.homeButton(R.drawable.ui_layers)
        durationOption = button(R.string.clip_length, action = ::chooseSimpleDuration).apply {
            homeButton(R.drawable.ui_clock)
        }
        selectionButton = button(R.string.select_wallpapers, action = ::chooseWallpapers).apply {
            homeButton(R.drawable.ui_layers)
        }
        updateOptions()
        status = label("", 12f, MUTED).apply {
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(BORDER)
            visibility = View.GONE
        }
        historyLabel = label("", if (wideLayout) 23f else 18f, INK, bold = true).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(screen())
        acceptSharedInput(intent)
        if (input.text.isEmpty()) download.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        started = true
        renderHistory()
        if (!working) showPreview(selection.playback())
        if (attachedEditorPending) scheduleAttachedEditor()
    }

    override fun onStop() {
        started = false
        input.removeCallbacks(openAttachedEditor)
        trimEditor?.dismiss()
        showPreview(null)
        super.onStop()
    }

    override fun onDestroy() {
        trimEditor?.dismiss()
        operation?.cancel()
        worker.shutdownNow()
        thumbnailWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingCopyId", pendingCopyId)
        outState.putBoolean("advancedMode", advanced)
        outState.putInt("simpleMinutes", options.minutes)
        outState.putBoolean("downloadAudio", options.includeAudio)
        outState.putInt("downloadHeight", options.height)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedInput(intent)
    }

    private val wideLayout get() = resources.configuration.screenWidthDp >= 680

    private val historyColumns get() = if (
        wideLayout || (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    ) 6 else 3

    private fun screen(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(if (wideLayout) 34 else 20)
            setPadding(inset, dp(19), inset, dp(24))
            addView(header())
            addView(composer(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(iconView(R.drawable.ui_clock).apply {
                    background = surface(ICON_WELL, 13)
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                }, LinearLayout.LayoutParams(dp(41), dp(35)))
                addView(historyLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                addView(selectionButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(22)
                bottomMargin = dp(13)
            })
            addView(grid)
        }
        return FrameLayout(this).apply {
            background = gradient(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(CANVAS, CANVAS, CANVAS_END))
            addView(headerArtwork(), FrameLayout.LayoutParams(MATCH_PARENT, dp(if (wideLayout) 218 else 190)))
            addView(ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(content)
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun headerArtwork() = FrameLayout(this).apply {
        addView(iconView(R.drawable.wallpaper_header).apply {
            scaleType = ImageView.ScaleType.MATRIX
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val image = drawable ?: return@addOnLayoutChangeListener
                val scale = maxOf(width.toFloat() / image.intrinsicWidth, height.toFloat() / image.intrinsicHeight)
                imageMatrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((width - image.intrinsicWidth * scale) / 2,
                        (height - image.intrinsicHeight * scale) * 0.34f)
                }
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(View(context).apply {
            background = gradient(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF00D131D.toInt(), 0x690D131D, 0x160D131D),
            )
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(View(context).apply {
            background = gradient(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x100D131D, 0x210D131D, 0xC00D131D.toInt(), CANVAS),
            )
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(iconView(R.drawable.ui_header_mark), LinearLayout.LayoutParams(dp(if (wideLayout) 51 else 38), dp(49)))
        addView(View(context).apply { setBackgroundColor(0x8053637C.toInt()) },
            LinearLayout.LayoutParams(dp(1), dp(43)).apply {
                marginStart = dp(12)
                marginEnd = dp(17)
            })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(getString(R.string.screen_title), if (wideLayout) 27f else 20f, INK, bold = true))
            addView(label(getString(R.string.screen_subtitle), if (wideLayout) 14f else 12f, MUTED))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun composer(): View {
        val editor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val field = FrameLayout(context).apply {
                addView(input, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(iconView(R.drawable.ui_link_video), FrameLayout.LayoutParams(dp(29), dp(29),
                    Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(13) })
                addView(clearLink, FrameLayout.LayoutParams(dp(30), dp(30),
                    Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(7) })
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(field, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(download, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { marginStart = dp(12) })
                addView(cancelWork, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { marginStart = dp(12) })
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                listOf(audioOption, resolutionOption, modeOption, durationOption).forEachIndexed { index, option ->
                    addView(option, LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply {
                        if (index > 0) marginStart = dp(if (wideLayout) 10 else 5)
                    })
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(7)).apply { topMargin = dp(8) })
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) })
        }
        return LinearLayout(this).apply {
            orientation = if (wideLayout) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xE628354B.toInt(), 0xEF161F30.toInt(), 0xF0202840.toInt()),
                17, 0xC3303E55.toInt(),
            )
            setPadding(dp(15), dp(13), dp(15), dp(13))
            if (wideLayout) {
                addView(editor, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(previewCard(), LinearLayout.LayoutParams(dp(200), dp(112)).apply { marginStart = dp(20) })
            } else {
                addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(previewCard(), LinearLayout.LayoutParams(MATCH_PARENT, dp(132)).apply { topMargin = dp(10) })
            }
        }
    }

    private fun previewCard() = FrameLayout(this).apply {
        background = surface(FIELD, 14, BORDER)
        clipToOutline = true
        placeholder = label(getString(R.string.preview_label), 12f, MUTED).apply { gravity = Gravity.CENTER }
        addView(placeholder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        preview = VideoView(context).apply {
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
            setOnPreparedListener { it.isLooping = true }
            setOnErrorListener { _, _, _ ->
                showPreview(null)
                status.setText(R.string.preview_unavailable)
                true
            }
        }
        addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        previewControl = iconButton(R.drawable.ui_pause, R.string.pause_background_preview) {
            if (previewPaused) preview.start() else preview.pause()
            previewPaused = !previewPaused
            updatePreviewControl()
        }.apply { visibility = View.GONE }
        addView(previewControl, FrameLayout.LayoutParams(dp(39), dp(39), Gravity.CENTER))
    }

    private fun renderHistory(preferredFocus: String? = null) {
        val history = selection.history()
        val selectedCount = history.selectedIds.size
        historyLabel.setText(if (history.entries.isEmpty()) R.string.history_empty else R.string.history)
        selectionButton.isEnabled = !working && history.entries.size >= 2
        selectionButton.text = if (selectedCount > 1) {
            resources.getQuantityString(R.plurals.wallpapers_selected, selectedCount, selectedCount)
        } else {
            getString(R.string.select_wallpapers)
        }
        grid.removeAllViews()
        thumbnailViews.clear()
        tiles.clear()
        val columns = historyColumns
        repeat(HISTORY_LIMIT / columns) { row ->
            val strip = LinearLayout(this)
            repeat(columns) { column ->
                val index = row * columns + column
                val entry = history.entries.getOrNull(index)
                val card = if (entry == null) emptyTile(index == history.entries.size)
                else tile(entry, entry.id in history.selectedIds, selectedCount > 1)
                strip.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (column > 0) marginStart = dp(if (columns == 6) 9 else 7)
                })
            }
            grid.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (row > 0) topMargin = dp(10)
            })
        }
        preferredFocus?.let { id -> (tiles[id] ?: download).requestFocus() }
        if (history.entries.isEmpty() && !working && status.text.isEmpty()) status.setText(readyHint())
    }

    private fun emptyTile(showAdd: Boolean) = WallpaperTile(this).apply {
        background = gradient(GradientDrawable.Orientation.TL_BR, intArrayOf(EMPTY_START, EMPTY_END), 13, BORDER)
        if (showAdd) {
            isFocusable = true
            contentDescription = getString(R.string.add_wallpaper)
            foreground = stateDrawable(surface(Color.TRANSPARENT, 13, OUTLINE, 3), surface(Color.TRANSPARENT, 13))
            addView(iconView(R.drawable.ui_plus).apply { alpha = 0.61f },
                FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
            setOnClickListener {
                if (!working) {
                    input.requestFocus()
                    getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        } else {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun tile(entry: SavedWallpaper, selected: Boolean, multiple: Boolean): View {
        val card = thumbnailCard(entry, thumbnailViews).apply {
            foreground = tileOutline(selected)
            contentDescription = when {
                selected && multiple -> getString(R.string.selected_title, entry.title)
                selected -> getString(R.string.active_title, entry.title)
                else -> entry.title
            }
            setOnClickListener { if (!working) openActions(entry) }
        }
        if (selected) {
            val badge = label(getString(if (multiple) R.string.selected else R.string.active), 10f, DARK_INK, bold = true).apply {
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = surface(MINT, 12)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(7)
                marginStart = dp(7)
            })
        }
        tiles[entry.id] = card
        return card
    }

    private fun thumbnailCard(
        entry: SavedWallpaper, images: MutableMap<String, ImageView>, showOptions: Boolean = false,
    ) = WallpaperTile(this).apply {
        isFocusable = true
        background = surface(SLOT, 13, BORDER)
        clipToOutline = true
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(9), 0, dp(9), dp(7))
            background = gradient(
                GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF109101A.toInt(), 0xB009101A.toInt(), Color.TRANSPARENT),
            )
            addView(label(entry.title, if (wideLayout) 13f else 11f, INK, bold = true).apply {
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
            if (showOptions) entry.options?.let {
                addView(label(optionSummary(it), 10f, INK).apply {
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(if (showOptions) 51 else 36), Gravity.BOTTOM))
        images[entry.id] = image
        loadThumbnail(entry)
    }

    private fun tileOutline(selected: Boolean, selectedColor: Int = ACCENT) = stateDrawable(
        surface(Color.TRANSPARENT, 13, OUTLINE, 3),
        surface(Color.TRANSPARENT, 13, if (selected) selectedColor else BORDER, if (selected) 2 else 1),
    )

    private fun displayThumbnail(id: String, bitmap: Bitmap) {
        thumbnailViews[id]?.setImageBitmap(bitmap)
        pickerThumbnails[id]?.setImageBitmap(bitmap)
    }

    private fun loadThumbnail(entry: SavedWallpaper) {
        val cached = thumbnails.get(entry.id)
        if (cached != null) {
            displayThumbnail(entry.id, cached)
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
                        val scale = minOf(320f / frame.width, 180f / frame.height, 1f)
                        val scaled = Bitmap.createScaledBitmap(
                            frame, (frame.width * scale).toInt().coerceAtLeast(1),
                            (frame.height * scale).toInt().coerceAtLeast(1), true,
                        )
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
                    displayThumbnail(entry.id, bitmap)
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
        val save = iconButton(R.drawable.ui_download, R.string.download_video) {
            dialog.dismiss()
            downloadSavedVideo(entry)
        }
        val delete = iconButton(R.drawable.ui_trash, R.string.delete) {
            dialog.dismiss()
            submit(R.string.deleting_background, entry.id, successMessage = R.string.deleted_from_library) {
                selection.delete(entry.id)
                thumbnails.remove(entry.id)
                selection.history().active
            }
        }.apply { imageTintList = ColorStateList.valueOf(DANGER) }
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
            entry.options?.let {
                addView(label(optionSummary(it), 12f, MUTED))
            }
            addView(LinearLayout(context).apply {
                addView(set, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(save, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
                addView(delete, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
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

    private fun audioLabel(value: DownloadOptions) = getString(if (value.includeAudio) R.string.audio_on else R.string.audio_off)

    private fun optionSummary(value: DownloadOptions): String {
        val length = value.range?.let { trimTime(it.durationUs) }
            ?: getString(R.string.clip_length_value, value.minutes)
        return getString(R.string.clip_option_summary, length, value.height, audioLabel(value))
    }

    private fun updateOptions() {
        audioOption.text = audioLabel(options)
        audioOption.setHomeIcon(if (options.includeAudio) R.drawable.ui_audio else R.drawable.ui_muted)
        audioOption.isSelected = options.includeAudio
        resolutionOption.text = getString(R.string.resolution_value, options.height)
        resolutionOption.contentDescription = getString(R.string.resolution_description, options.height)
        modeOption.setText(if (advanced) R.string.advanced_mode else R.string.simple_mode)
        modeOption.isSelected = advanced
        modeOption.contentDescription = getString(R.string.clip_mode_description, modeOption.text)
        durationOption.visibility = if (advanced) View.GONE else View.VISIBLE
        durationOption.text = getString(R.string.clip_length_value, options.minutes)
        durationOption.contentDescription = getString(R.string.clip_length_description, options.minutes)
        download.setText(if (advanced) R.string.trim_video else R.string.download)
        download.setHomeIcon(if (advanced) R.drawable.ui_trim else R.drawable.ui_download)
    }

    private fun readyHint() = if (advanced) R.string.advanced_ready else R.string.simple_ready

    private fun chooseSimpleDuration() {
        val minutes = (1..5).toList()
        val labels = minutes.map { resources.getQuantityString(R.plurals.clip_minutes, it, it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.clip_length)
            .setSingleChoiceItems(labels.toTypedArray(), minutes.indexOf(options.minutes)) { dialog, which ->
                options = options.copy(minutes = minutes[which])
                updateOptions()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseResolution() {
        val heights = listOf(DEFAULT_HEIGHT, HD_HEIGHT)
        AlertDialog.Builder(this)
            .setTitle(R.string.resolution)
            .setSingleChoiceItems(heights.map { getString(R.string.resolution_value, it) }.toTypedArray(),
                heights.indexOf(options.height)) { dialog, which ->
                options = options.copy(height = heights[which])
                updateOptions()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseWallpapers() {
        val history = selection.history()
        if (working || history.entries.size < 2) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val selected = history.selectedIds.toMutableSet()
        val columns = if (wideLayout) minOf(history.entries.size, 4) else 2
        val width = dp(42 + columns * 172).coerceAtMost(resources.displayMetrics.widthPixels - dp(32))
        val gap = dp(10)
        val rowCount = (history.entries.size + columns - 1) / columns
        val tileWidth = (width - dp(40) - gap * (columns - 1) + columns - 1) / columns
        val gridHeight = rowCount * ((tileWidth * 9 + 8) / 16) + (rowCount - 1) * gap
        val maxGridHeight = (resources.displayMetrics.heightPixels - dp(164)).coerceAtLeast(dp(60))
        val count = label("", 13f, MINT, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val cancel = button(android.R.string.cancel) { dialog.dismiss() }
        val apply = button(R.string.apply_wallpapers, primary = true) {
            val requested = selected.toSet()
            dialog.dismiss()
            if (requested != history.selectedIds) submit(R.string.setting_background) { selection.select(requested) }
        }
        fun updateCount() {
            count.text = resources.getQuantityString(R.plurals.wallpapers_selected, selected.size, selected.size)
            apply.isEnabled = selected.isNotEmpty()
        }
        updateCount()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        history.entries.chunked(columns).forEachIndexed { index, entries ->
            val row = LinearLayout(this)
            repeat(columns) { column ->
                val entry = entries.getOrNull(column)
                val card = entry?.let {
                    pickerCard(it, it.id in selected) {
                        val checked = if (selected.remove(it.id)) false else selected.add(it.id)
                        updateCount()
                        checked
                    }
                } ?: View(this)
                row.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (column > 0) marginStart = gap
                })
            }
            rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (index > 0) topMargin = gap
            })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
            background = surface(PANEL, 18, BORDER)
            addView(label(getString(R.string.select_wallpapers), 20f, INK, bold = true))
            addView(label(getString(R.string.selection_instructions), 13f, MUTED))
            addView(ScrollView(context).apply {
                isFocusable = false
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(rows)
            }, LinearLayout.LayoutParams(MATCH_PARENT, minOf(gridHeight, maxGridHeight)).apply { topMargin = dp(14) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(count, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(cancel, LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)))
                addView(apply, LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        dialog.setContentView(panel)
        dialog.setTitle(getString(R.string.select_wallpapers))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            pickerThumbnails.clear()
            if (!working) selectionButton.requestFocus()
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
        }
        dialog.show()
        dialog.window?.setLayout(width, WRAP_CONTENT)
        (rows.getChildAt(0) as LinearLayout).getChildAt(0).requestFocus()
    }

    private fun pickerCard(entry: SavedWallpaper, selected: Boolean, toggle: () -> Boolean): View {
        val card = thumbnailCard(entry, pickerThumbnails, showOptions = true)
        val check = iconView(R.drawable.ui_check).apply {
            imageTintList = ColorStateList.valueOf(DARK_INK)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        card.addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7)
            marginEnd = dp(7)
        })
        card.contentDescription = entry.options?.let {
            getString(R.string.selection_download_item, entry.title, optionSummary(it))
        } ?: entry.title
        fun update(checked: Boolean) {
            card.isSelected = checked
            card.foreground = tileOutline(checked, MINT)
            check.background = if (checked) surface(MINT, 12) else surface(0xB00D131D.toInt(), 12, INK)
            check.imageAlpha = if (checked) 255 else 0
        }
        update(selected)
        card.setOnClickListener { update(toggle()) }
        return card
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

    private fun scheduleAttachedEditor() {
        input.removeCallbacks(openAttachedEditor)
        attachedEditorPending = advanced && runCatching { YouTubeUrl.canonical(input.text.toString()) }.isSuccess
        if (attachedEditorPending && started && !working && trimEditor == null) {
            input.postDelayed(openAttachedEditor, 500)
        }
    }

    private fun openCurrentLink() {
        if (working || trimEditor != null) return
        input.removeCallbacks(openAttachedEditor)
        attachedEditorPending = false
        withLink { submitted, url, requested ->
            if (advanced) editClip(submitted, url, requested.copy(minutes = DownloadOptions().minutes))
            else startDownload(submitted, url, requested)
        }
    }

    private fun editClip(submitted: String, url: String, requested: DownloadOptions) {
        runOperation(R.string.opening_trim_editor, cancellable = true, action = {
            MediaResolver.prepare(applicationContext, url, requested)
        }) { result ->
            result.onSuccess { source ->
                if (input.text.toString() != submitted) return@onSuccess
                withResolution(source, requested) { resolvedOptions ->
                    if (!started || isDestroyed) return@withResolution
                    status.setText(readyHint())
                    showPreview(null)
                    trimEditor = TrimEditor(this, source, resolvedOptions, worker, onDismiss = {
                        trimEditor = null
                        if (started && !working) showPreview(selection.playback())
                        if (attachedEditorPending) scheduleAttachedEditor()
                    }) { range ->
                        startDownload(submitted, url, resolvedOptions.copy(range = range), source)
                    }.also { it.show() }
                }
            }.onFailure(::showError)
        }
    }

    private fun withLink(action: (String, String, DownloadOptions) -> Unit) {
        if (working) return
        val submitted = input.text.toString()
        val url = try {
            YouTubeUrl.canonical(submitted)
        } catch (error: IllegalArgumentException) {
            status.text = error.message
            return
        }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        val requested = options
        if (downloadNotice.acknowledged()) {
            action(submitted, url, requested)
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.content_notice_title)
                .setMessage(R.string.content_notice)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.acknowledge_notice) { _, _ ->
                    downloadNotice.acknowledge()
                    action(submitted, url, requested)
                }
                .show()
        }
    }

    private fun withResolution(source: PreparedVideo, requested: DownloadOptions, proceed: (DownloadOptions) -> Unit) {
        if (source.height == requested.height) {
            proceed(requested)
            return
        }
        status.setText(R.string.resolution_fallback)
        if (!started || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.resolution)
            .setMessage(R.string.resolution_fallback)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.use_720p) { _, _ -> proceed(requested.copy(height = source.height)) }
            .show()
    }

    private fun startDownload(
        submitted: String, url: String, requested: DownloadOptions, source: PreparedVideo? = null,
    ) {
        submit(
            if (source?.isFresh == true) R.string.preparing_clip else R.string.starting_download,
            clearInput = submitted, cancellable = true,
            onFailure = { error ->
                if (error is ResolutionFallback) {
                    withResolution(error.source, requested) { startDownload(submitted, url, it, error.source) }
                } else showError(error)
            },
        ) {
            MediaResolver.download(applicationContext, url, requested, source) { reportProgress(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun downloadSavedVideo(entry: SavedWallpaper) {
        if (working) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveVideoCopy(entry.id, R.string.saved_to_downloads) {
                WallpaperDownload.toDownloads(contentResolver, selection.file(entry), entry.title)
            }
            return
        }
        try {
            pendingCopyId = entry.id
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = WallpaperDownload.MIME_TYPE
                putExtra(Intent.EXTRA_TITLE, WallpaperDownload.fileName(entry.title))
            }, SAVE_VIDEO)
        } catch (_: ActivityNotFoundException) {
            pendingCopyId = null
            status.setText(R.string.no_save_picker)
        }
    }

    @Deprecated("Uses the platform document picker from a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SAVE_VIDEO) return
        val id = pendingCopyId
        pendingCopyId = null
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        saveVideoCopy(id, R.string.video_copy_saved) {
            val entry = selection.history().entries.firstOrNull { it.id == id }
            WallpaperDownload.toDocument(contentResolver, entry?.let(selection::file), uri)
        }
    }

    private fun saveVideoCopy(id: String?, success: Int, action: () -> Unit) {
        runOperation(R.string.saving_video_copy, cancellable = false, action = action) { result ->
            status.setText(if (result.isSuccess) success else R.string.video_copy_failed)
            tiles[id]?.requestFocus()
        }
    }

    private fun reportProgress(update: ClipProgress) {
        runOnUiThread {
            if (isDestroyed || !working || operation?.cancelled == true) return@runOnUiThread
            when (update) {
                is ClipProgress.Copying -> {
                    val percent = update.percent
                    progress.isIndeterminate = percent == null
                    if (percent != null) progress.progress = percent
                    status.text = if (percent == null) {
                        getString(R.string.preparing_clip)
                    } else {
                        getString(R.string.clip_progress, percent)
                    }
                }
                ClipProgress.Saving -> {
                    progress.isIndeterminate = true
                    status.setText(R.string.saving_clip)
                }
            }
        }
    }

    private fun submit(
        message: Int, focusId: String? = null, clearInput: String? = null, successMessage: Int? = null,
        cancellable: Boolean = false, onFailure: (Throwable) -> Unit = ::showError,
        action: () -> SavedWallpaper?,
    ) {
        val previousPlayback = selection.playback()?.id
        runOperation(message, cancellable, action) { result ->
            val playback = selection.playback()
            result.onSuccess { entry ->
                if (clearInput != null && input.text.toString() == clearInput) input.text.clear()
                val count = selection.history().selectedIds.size
                status.text = successMessage?.let(::getString)
                    ?: playback?.let {
                        getString(if (count > 1) R.string.preview_title else R.string.active_title, it.title)
                    }
                    ?: getString(readyHint())
                renderHistory(entry?.id ?: focusId)
                requestWallpaperUpdate()
            }.onFailure { error ->
                onFailure(error)
                renderHistory(focusId)
                if (previousPlayback != playback?.id) requestWallpaperUpdate()
            }
        }
    }

    private fun <T> runOperation(
        message: Int, cancellable: Boolean, action: () -> T, complete: (Result<T>) -> Unit,
    ) {
        if (working) return
        val work = Operation()
        operation = work
        working = true
        setControlsEnabled(false)
        if (cancellable) {
            download.visibility = View.GONE
            cancelWork.visibility = View.VISIBLE
            cancelWork.isEnabled = true
            cancelWork.requestFocus()
        }
        showPreview(null)
        status.setText(message)
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        if (message == R.string.starting_download || message == R.string.opening_trim_editor) status.postDelayed({
            if (operation === work && !work.cancelled && status.text == getString(message)) {
                status.setText(R.string.still_looking)
            }
        }, 12_000)
        worker.execute {
            val result = runCatching { work.run(action) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                operation = null
                working = false
                setControlsEnabled(true)
                val restoreFocus = cancelWork.hasFocus()
                cancelWork.visibility = View.GONE
                download.visibility = View.VISIBLE
                if (restoreFocus) download.requestFocus()
                progress.visibility = View.GONE
                complete(result)
                if (started && !working && trimEditor == null) showPreview(selection.playback())
                if (attachedEditorPending && !working && trimEditor == null) scheduleAttachedEditor()
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(download, audioOption, resolutionOption, modeOption, durationOption, clearLink)
            .forEach { it.isEnabled = enabled }
        selectionButton.isEnabled = enabled && selection.history().entries.size >= 2
    }

    private fun cancelOperation() {
        operation?.cancel()
        cancelWork.isEnabled = false
        status.setText(R.string.cancelling)
    }

    private fun showError(error: Throwable) {
        status.text = when (error) {
            is InterruptedException -> getString(R.string.cancelled)
            is IllegalArgumentException, is IllegalStateException -> error.message
            else -> getString(R.string.could_not_prepare)
        }
    }

    private class Operation {
        @Volatile var cancelled = false
            private set
        private var thread: Thread? = null

        fun <T> run(action: () -> T): T {
            synchronized(this) {
                if (cancelled) throw InterruptedException()
                thread = Thread.currentThread()
            }
            try {
                return action()
            } finally {
                synchronized(this) {
                    thread = null
                    Thread.interrupted()
                }
            }
        }

        @Synchronized
        fun cancel() {
            cancelled = true
            thread?.interrupt()
        }
    }

    private fun showPreview(entry: WallpaperPlayback?) {
        if (entry?.id == previewId) return
        previewId = entry?.id
        previewPaused = false
        preview.stopPlayback()
        placeholder.visibility = if (entry == null) View.VISIBLE else View.INVISIBLE
        updatePreviewControl()
        if (entry == null) {
            preview.visibility = View.INVISIBLE
        } else {
            // Showing the surface can open the last URI immediately.
            preview.setVideoURI(entry.uri)
            preview.visibility = View.VISIBLE
            preview.start()
        }
    }

    private fun updatePreviewControl() {
        previewControl.visibility = if (previewId == null) View.GONE else View.VISIBLE
        previewControl.setImageResource(if (previewPaused) R.drawable.ui_play else R.drawable.ui_pause)
        previewControl.contentDescription = getString(
            if (previewPaused) R.string.play_background_preview else R.string.pause_background_preview,
        )
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

    private fun button(title: Int, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
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
            intArrayOf(MUTED, if (primary) DARK_INK else INK),
        ))
        setOnClickListener { action() }
    }

    private fun iconView(icon: Int) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun iconButton(icon: Int, description: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = getString(description)
        backgroundTintList = null
        stateListAnimator = null
        background = stateDrawable(surface(0xE62A314A.toInt(), 50, OUTLINE, 2), surface(0xCA1B2537.toInt(), 50))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(9), dp(9), dp(9), dp(9))
        setOnClickListener { action() }
    }

    private fun Button.homeButton(icon: Int, prominent: Boolean = false) {
        textSize = if (wideLayout) 14f else 13f
        val padding = dp(if (prominent && wideLayout) 20 else if (wideLayout) 12 else 8)
        setPaddingRelative(padding, 0, padding, 0)
        setHomeIcon(icon)
        background = StateListDrawable().apply {
            val colors = if (prominent) intArrayOf(0xFF7A63F8.toInt(), 0xFF4A50DD.toInt(), 0xFF327CE5.toInt())
            else intArrayOf(0xFF222D41.toInt(), 0xFF182133.toInt())
            val direction = GradientDrawable.Orientation.TL_BR
            addState(intArrayOf(-android.R.attr.state_enabled), surface(SLOT, 14, BORDER))
            addState(intArrayOf(android.R.attr.state_focused), gradient(direction, colors, 14, OUTLINE, 2))
            addState(intArrayOf(android.R.attr.state_pressed), gradient(direction, colors, 14, OUTLINE, 2))
            addState(intArrayOf(android.R.attr.state_selected),
                gradient(direction, intArrayOf(0xFF352555.toInt(), 0xFF251D3B.toInt()), 14, ACCENT))
            addState(intArrayOf(), gradient(direction, colors, 14, if (prominent) null else BORDER))
        }
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(MUTED, INK),
        ))
    }

    private fun Button.setHomeIcon(icon: Int) {
        val size = dp(if (wideLayout) 19 else 15)
        val graphic = getDrawable(icon)?.apply { setBounds(0, 0, size, size) }
        setCompoundDrawablesRelative(graphic, null, null, null)
        compoundDrawablePadding = dp(if (wideLayout) 7 else 4)
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

    private fun gradient(
        orientation: GradientDrawable.Orientation, colors: IntArray, radius: Int = 0, stroke: Int? = null, width: Int = 1,
    ) = GradientDrawable(orientation, colors).apply {
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(width), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private class WallpaperTile(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val height = (MeasureSpec.getSize(widthMeasureSpec) * 9 + 8) / 16
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private companion object {
        const val SAVE_VIDEO = 1
        val CANVAS = Color.rgb(12, 17, 25)
        val CANVAS_END = Color.rgb(19, 28, 47)
        val PANEL = Color.rgb(24, 34, 47)
        val SLOT = Color.rgb(19, 27, 38)
        val FIELD = Color.rgb(15, 24, 34)
        val BORDER = Color.rgb(43, 58, 74)
        val EMPTY_START = Color.rgb(18, 27, 43)
        val EMPTY_END = Color.rgb(24, 34, 53)
        val ICON_WELL = Color.rgb(42, 31, 77)
        val INK = Color.rgb(239, 246, 249)
        val MUTED = Color.rgb(168, 187, 202)
        val MINT = Color.rgb(125, 229, 190)
        val ACCENT = Color.rgb(166, 127, 255)
        val OUTLINE = Color.rgb(220, 205, 255)
        val DARK_INK = Color.rgb(7, 34, 29)
        val DANGER = Color.rgb(255, 164, 164)
    }
}
