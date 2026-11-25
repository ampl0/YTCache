package com.ytcache.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ytcache.app.data.CaptureStore
import com.ytcache.app.data.MetadataFetcher
import com.ytcache.app.data.VideoEntry
import com.ytcache.app.databinding.ActivityLibraryBinding
import com.ytcache.app.ui.ThumbLoader
import com.ytcache.app.ui.VideoAdapter
import com.ytcache.app.util.Format
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LibraryActivity : AppCompatActivity() {

    private enum class Sort { NEWEST, OLDEST, LARGEST, AZ }

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var store: CaptureStore
    private lateinit var adapter: VideoAdapter
    private val thumbs by lazy { ThumbLoader() }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var all: List<VideoEntry> = emptyList()
    private var query = ""
    private var sort = Sort.NEWEST
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private val repairAttempted = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CaptureStore(File(getExternalFilesDir(null) ?: filesDir, "YTCache"))

        adapter = VideoAdapter(
            thumbs = thumbs,
            onClick = { entry ->
                if (selecting) toggleSelection(entry) else openPlayer(entry)
            },
            onLongPress = { entry ->
                if (!selecting) {
                    selecting = true
                    toggleSelection(entry)
                }
            },
            onMenu = { anchor, entry -> if (selecting) toggleSelection(entry) else showMenu(anchor, entry) },
            isSelected = { entry -> entry.videoId in selected },
            selectionMode = { selecting },
        )

        binding.grid.layoutManager = GridLayoutManager(this, 2)
        binding.grid.adapter = adapter
        binding.grid.setHasFixedSize(false)

        binding.backButton.setOnClickListener {
            if (selecting) exitSelection() else finish()
        }
        binding.browseButton.setOnClickListener { finish() }
        binding.emptyAction.setOnClickListener {
            if (query.isNotBlank()) {
                binding.searchInput.setText("")
            } else {
                finish()
            }
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                applyFilter()
            }
        })

        binding.sortGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            sort = when (checkedIds.firstOrNull()) {
                binding.chipOldest.id -> Sort.OLDEST
                binding.chipLargest.id -> Sort.LARGEST
                binding.chipAz.id -> Sort.AZ
                else -> Sort.NEWEST
            }
            applyFilter()
        }

        binding.selectionClose.setOnClickListener { exitSelection() }
        binding.selectionAll.setOnClickListener { selectAll() }
        binding.selectionExport.setOnClickListener { batchExport() }
        binding.selectionDelete.setOnClickListener { batchDelete() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) {
                    exitSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        io.execute {
            val videos = store.listVideos()
            main.post {
                all = videos
                applyFilter()
            }
            // Captures recorded before metadata repair existed may still say
            // "Untitled video"; fix a few of them in the background after the
            // grid is already on screen.
            var repaired = 0
            videos.take(8).forEach { entry ->
                if (repairMetadata(entry)) repaired++
            }
            if (repaired > 0) {
                val fresh = store.listVideos()
                main.post {
                    all = fresh
                    applyFilter()
                }
            }
        }
    }

    private fun repairMetadata(entry: VideoEntry): Boolean {
        val missingTitle = entry.title.isBlank() || entry.title == "Untitled video"
        if (!missingTitle) return false
        if (!repairAttempted.add(entry.videoId)) return false
        val info = MetadataFetcher.fetch(entry.videoId, wantDescription = true) ?: return false
        return store.saveVideoInfo(info.toJson()) != null
    }

    private fun applyFilter() {
        val filtered = all.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                it.author.contains(query, ignoreCase = true)
        }
        val sorted = when (sort) {
            Sort.NEWEST -> filtered.sortedByDescending { it.capturedAt }
            Sort.OLDEST -> filtered.sortedBy { it.capturedAt }
            Sort.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
            Sort.AZ -> filtered.sortedBy { it.title.lowercase(Locale.getDefault()) }
        }

        adapter.submitList(sorted)
        val totalBytes = all.sumOf { it.sizeBytes }
        binding.summaryText.text = getString(R.string.storage_usage, all.size, Format.size(totalBytes))

        updateSelectionUi(sorted)

        if (sorted.isEmpty()) {
            binding.grid.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            val searching = query.isNotBlank() && all.isNotEmpty()
            if (searching) {
                binding.emptyTitle.setText(R.string.no_results_title)
                binding.emptyBody.setText(R.string.no_results_body)
                binding.emptyAction.setText(R.string.cancel)
            } else {
                binding.emptyTitle.setText(R.string.empty_title)
                binding.emptyBody.setText(R.string.empty_body)
                binding.emptyAction.setText(R.string.empty_action)
            }
        } else {
            binding.grid.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        }
    }

    // -------------------------------------------------------------- selection

    private fun toggleSelection(entry: VideoEntry) {
        if (!selected.add(entry.videoId)) selected.remove(entry.videoId)
        if (selected.isEmpty()) {
            exitSelection()
            return
        }
        binding.grid.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updateSelectionUi(all)
        refreshSelectionMarks()
    }

    private fun updateSelectionUi(visible: List<VideoEntry>) {
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.summaryText.visibility = if (selecting) View.GONE else View.VISIBLE
        binding.browseButton.visibility = if (selecting) View.INVISIBLE else View.VISIBLE
        if (selecting) {
            binding.selectionCount.text = getString(R.string.selected_count, selected.size)
            val bytes = visible.filter { it.videoId in selected }.sumOf { it.sizeBytes }
            binding.selectionSize.text = Format.size(bytes)
            binding.selectionAll.setText(
                if (visible.isNotEmpty() && visible.all { it.videoId in selected }) {
                    R.string.clear_selection
                } else {
                    R.string.select_all
                },
            )
        }
    }

    private fun refreshSelectionMarks() {
        if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private fun selectAll() {
        val visible = all.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                it.author.contains(query, ignoreCase = true)
        }
        if (visible.isNotEmpty() && visible.all { it.videoId in selected }) {
            selected.clear()
            exitSelection()
            return
        }
        selected.clear()
        visible.forEach { selected.add(it.videoId) }
        updateSelectionUi(all)
        refreshSelectionMarks()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        binding.selectionBar.visibility = View.GONE
        binding.summaryText.visibility = View.VISIBLE
        binding.browseButton.visibility = View.VISIBLE
        refreshSelectionMarks()
        applyFilterSummary()
    }

    private fun applyFilterSummary() {
        val totalBytes = all.sumOf { it.sizeBytes }
        binding.summaryText.text = getString(R.string.storage_usage, all.size, Format.size(totalBytes))
    }

    private fun selectedEntries(): List<VideoEntry> = all.filter { it.videoId in selected }

    // ------------------------------------------------------------------ menus

    private fun openPlayer(entry: VideoEntry) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, entry.videoId),
        )
    }

    private fun showMenu(anchor: View, entry: VideoEntry) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_EXPORT_VIDEO, 0, R.string.export_video)
        popup.menu.add(0, MENU_EXPORT_AUDIO, 1, R.string.export_audio)
        popup.menu.add(0, MENU_SELECT, 2, R.string.select)
        popup.menu.add(0, MENU_COPY, 3, R.string.copy_link)
        popup.menu.add(0, MENU_DETAILS, 4, R.string.details)
        popup.menu.add(0, MENU_DELETE, 5, R.string.delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT_VIDEO -> export(entry, "video")
                MENU_EXPORT_AUDIO -> export(entry, "audio")
                MENU_SELECT -> {
                    selecting = true
                    selected.clear()
                    selected.add(entry.videoId)
                    updateSelectionUi(all)
                    refreshSelectionMarks()
                }
                MENU_COPY -> copyLink(entry)
                MENU_DETAILS -> showDetails(entry)
                MENU_DELETE -> confirmDelete(entry)
            }
            true
        }
        popup.show()
    }

    private fun export(entry: VideoEntry, kind: String) {
        Toast.makeText(this, R.string.exporting, Toast.LENGTH_SHORT).show()
        io.execute {
            val outcome = Exporter.export(this, store, entry.videoId, kind)
            main.post { Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun batchExport() {
        val items = selectedEntries()
        if (items.isEmpty()) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exporting)
            .setMessage(getString(R.string.exporting_progress, 0, items.size))
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        io.execute {
            var ok = 0
            var failed = 0
            var lastMessage = ""
            items.forEachIndexed { index, entry ->
                main.post {
                    if (dialog.isShowing) {
                        dialog.setMessage(getString(R.string.exporting_progress, index + 1, items.size))
                    }
                }
                val outcome = Exporter.export(this, store, entry.videoId, "video")
                if (outcome.ok) ok++ else {
                    failed++
                    lastMessage = outcome.message
                }
            }
            main.post {
                if (dialog.isShowing) dialog.dismiss()
                val message = if (failed == 0) {
                    getString(R.string.exported_count, ok)
                } else {
                    getString(R.string.exported_partial, ok, failed, lastMessage)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                exitSelection()
            }
        }
    }

    private fun copyLink(entry: VideoEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube", entry.sourceUrl))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun showDetails(entry: VideoEntry) {
        val stamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(entry.capturedAt))
        val body = buildString {
            appendLine(entry.title)
            if (entry.author.isNotBlank()) appendLine(entry.author)
            appendLine()
            appendLine("Captured: $stamp")
            appendLine("Size: ${Format.size(entry.sizeBytes)}")
            appendLine("Streams: ${entry.streamCount}")
            if (entry.lengthSeconds > 0) appendLine("Duration: ${Format.duration(entry.lengthSeconds)}")
            if (entry.partial) appendLine("Status: partial capture")
            appendLine("Video ID: ${entry.videoId}")
            if (entry.description.isNotBlank()) {
                appendLine()
                appendLine(entry.description.take(600))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.details)
            .setMessage(body.trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(entry: VideoEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_body, entry.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.execute {
                    store.delete(entry.videoId)
                    main.post {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun batchDelete() {
        val items = selectedEntries()
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_many_title, items.size))
            .setMessage(R.string.delete_many_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.execute {
                    var removed = 0
                    items.forEach { if (store.delete(it.videoId)) removed++ }
                    main.post {
                        Toast.makeText(
                            this,
                            getString(R.string.deleted_count, removed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        exitSelection()
                        refresh()
                    }
                }
            }
            .show()
    }

    companion object {
        private const val MENU_EXPORT_VIDEO = 1
        private const val MENU_EXPORT_AUDIO = 2
        private const val MENU_SELECT = 3
        private const val MENU_COPY = 4
        private const val MENU_DETAILS = 5
        private const val MENU_DELETE = 6
    }
}
