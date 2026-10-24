package com.streamtv.app.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import com.streamtv.app.data.BookmarkManager
import com.streamtv.app.data.HistoryManager
import com.streamtv.app.data.SiteManager

/**
 * Native overlay menu, built for D-pad navigation.
 *
 * Every row is focusable, so the remote can reach quick actions without a
 * mouse: play, next server, back/forward, search, cursor mode and the
 * bookmark/history lists.
 */
class OverlayMenu(
    private val context: Context,
    private val bookmarkManager: BookmarkManager,
    private val historyManager: HistoryManager,
    private val onNavigate: (String) -> Unit,
    private val onBookmarkCurrent: () -> Unit,
    private val isCursorMode: () -> Boolean = { false },
    private val onPlay: () -> Unit = {},
    private val onNextServer: () -> Unit = {},
    private val onGoBack: () -> Unit = {},
    private val onGoForward: () -> Unit = {},
    private val onSearch: () -> Unit = {},
    private val onToggleCursor: () -> Unit = {},
    private val onRefresh: () -> Unit = {}
) {

    companion object {
        const val APP_VERSION = "2.3.0"
        const val CREDIT_URL = "https://www.tiktok.com/@buildbox.studio"
    }

    private fun createMenuBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            colors = intArrayOf(
                Color.parseColor("#F21A1A1A"),
                Color.parseColor("#F20A0A0A")
            )
            gradientType = GradientDrawable.LINEAR_GRADIENT
            setStroke(2, Color.parseColor("#E50914"))
        }
    }

    private fun createMenuItemBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            setColor(Color.parseColor("#1F1F1F"))
        }
    }

    private fun createSectionLabel(text: String, density: Float): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.parseColor("#888888"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
            setPadding((4 * density).toInt(), (8 * density).toInt(), 0, (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createMenuItem(icon: String, label: String, onClick: () -> Unit): LinearLayout {
        val density = context.resources.displayMetrics.density

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (20 * density).toInt(),
                (14 * density).toInt(),
                (20 * density).toInt(),
                (14 * density).toInt()
            )
            background = createMenuItemBackground()

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = (8 * density).toInt()
            layoutParams = params

            // Icon
            addView(TextView(context).apply {
                text = icon
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })

            // Label
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            // Arrow
            addView(TextView(context).apply {
                text = "›"
                textSize = 20f
                setTextColor(Color.parseColor("#888888"))
            })

            setOnClickListener { onClick() }

            // Focus highlight for TV remote
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                val bg = v.background as? GradientDrawable
                if (hasFocus) {
                    bg?.setColor(Color.parseColor("#2A1416"))
                    bg?.setStroke((2 * density).toInt(), Color.parseColor("#E50914"))
                    v.scaleX = 1.02f
                    v.scaleY = 1.02f
                } else {
                    bg?.setColor(Color.parseColor("#1F1F1F"))
                    bg?.setStroke(0, Color.TRANSPARENT)
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            }
        }
    }

    /**
     * Focus the first reachable view so the D-pad works as soon as the dialog
     * appears (a Dialog does not focus anything by itself).
     */
    private fun focusFirst(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view.isFocusable) {
            if (view.requestFocus()) return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (focusFirst(view.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun focusWhenShown(dialog: Dialog, root: View) {
        dialog.setOnShowListener {
            root.post { focusFirst(root) }
        }
    }

    fun showMainMenu() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.7f)
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }

        val density = context.resources.displayMetrics.density
        val maxWidth = (360 * density).toInt()
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.85).toInt()

        // ScrollView as root so entire menu is scrollable
        val scrollView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            isVerticalScrollBarEnabled = true
            isFillViewport = true
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createMenuBackground()
            setPadding(
                (24 * density).toInt(),
                (28 * density).toInt(),
                (24 * density).toInt(),
                (20 * density).toInt()
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Title
        rootLayout.addView(TextView(context).apply {
            text = "PIXELTV"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        // Subtitle
        rootLayout.addView(TextView(context).apply {
            text = "Remote Menu"
            textSize = 12f
            setTextColor(Color.parseColor("#B3B3B3"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        rootLayout.addView(createSectionLabel("PEMUTARAN", density))

        rootLayout.addView(createMenuItem("▶", "Putar / Lanjutkan") {
            dialog.dismiss()
            onPlay()
        })
        rootLayout.addView(createMenuItem("🔄", "Ganti Server") {
            dialog.dismiss()
            onNextServer()
        })

        rootLayout.addView(createSectionLabel("NAVIGASI", density))

        rootLayout.addView(createMenuItem("⬅️", "Kembali") {
            dialog.dismiss()
            onGoBack()
        })
        rootLayout.addView(createMenuItem("➡️", "Maju") {
            dialog.dismiss()
            onGoForward()
        })
        rootLayout.addView(createMenuItem("🔍", "Cari Film") {
            dialog.dismiss()
            onSearch()
        })
        rootLayout.addView(createMenuItem(
            "🎯",
            if (isCursorMode()) "Mode Kursor: AKTIF" else "Mode Kursor: MATI"
        ) {
            dialog.dismiss()
            onToggleCursor()
        })
        rootLayout.addView(createMenuItem("🔄", "Muat Ulang") {
            dialog.dismiss()
            onRefresh()
        })

        rootLayout.addView(createDivider(density))

        // Existing actions
        rootLayout.addView(createMenuItem("🏠", "Beranda") {
            dialog.dismiss()
            onNavigate(SiteManager.sites[0].url)
        })
        rootLayout.addView(createMenuItem("🌐", "Pilih Situs") {
            dialog.dismiss()
            showSiteSelector()
        })
        rootLayout.addView(createMenuItem("⭐", "Bookmark") {
            dialog.dismiss()
            showBookmarks()
        })
        rootLayout.addView(createMenuItem("📋", "Riwayat") {
            dialog.dismiss()
            showHistory()
        })
        rootLayout.addView(createMenuItem("➕", "Bookmark Halaman Ini") {
            dialog.dismiss()
            onBookmarkCurrent()
        })
        rootLayout.addView(createMenuItem("🗑️", "Hapus Riwayat") {
            dialog.dismiss()
            confirmClearHistory()
        })

        rootLayout.addView(createDivider(density))

        // Credit
        rootLayout.addView(TextView(context).apply {
            text = "Build by Buildbox Studio"
            textSize = 12f
            setTextColor(Color.parseColor("#E50914"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CREDIT_URL))
                context.startActivity(intent)
            }
        })

        // Version
        rootLayout.addView(TextView(context).apply {
            text = "v$APP_VERSION"
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, (2 * density).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        scrollView.addView(rootLayout)

        dialog.setContentView(scrollView)
        dialog.window?.setLayout(maxWidth, maxHeight)
        focusWhenShown(dialog, rootLayout)
        dialog.show()

        // Animate in
        rootLayout.alpha = 0f
        rootLayout.scaleX = 0.9f
        rootLayout.scaleY = 0.9f
        rootLayout.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun showSiteSelector() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.7f)
            setGravity(Gravity.CENTER)
        }

        val density = context.resources.displayMetrics.density
        val maxWidth = (320 * density).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createMenuBackground()
            setPadding(
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt()
            )
            layoutParams = ViewGroup.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        rootLayout.addView(TextView(context).apply {
            text = "🌐  Pilih Situs"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, (16 * density).toInt())
        })

        SiteManager.sites.forEach { site ->
            rootLayout.addView(createMenuItem(site.icon, site.name) {
                dialog.dismiss()
                onNavigate(site.url)
            })
        }

        dialog.setContentView(rootLayout)
        focusWhenShown(dialog, rootLayout)
        dialog.show()

        rootLayout.alpha = 0f
        rootLayout.animate().alpha(1f).setDuration(150).start()
    }

    fun showBookmarks() {
        val bookmarks = bookmarkManager.getBookmarks()

        if (bookmarks.isEmpty()) {
            Toast.makeText(context, "Belum ada bookmark", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.7f)
            setGravity(Gravity.CENTER)
        }

        val density = context.resources.displayMetrics.density
        val maxWidth = (360 * density).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createMenuBackground()
            setPadding(
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt()
            )
            layoutParams = ViewGroup.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        rootLayout.addView(TextView(context).apply {
            text = "⭐  Bookmark (${bookmarks.size})"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, (16 * density).toInt())
        })

        // Scrollable list
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (300 * density).toInt().coerceAtMost(bookmarks.size * (60 * density).toInt())
            )
        }

        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        bookmarks.forEach { bookmark ->
            val title = if (bookmark.title.length > 35) bookmark.title.take(35) + "…" else bookmark.title
            listLayout.addView(createMenuItem("⭐", title) {
                dialog.dismiss()
                onNavigate(bookmark.url)
            })
        }

        scrollView.addView(listLayout)
        rootLayout.addView(scrollView)

        // Clear all button
        rootLayout.addView(createMenuItem("🗑️", "Hapus Semua Bookmark") {
            dialog.dismiss()
            bookmarkManager.clearAll()
            Toast.makeText(context, "Semua bookmark dihapus", Toast.LENGTH_SHORT).show()
        })

        dialog.setContentView(rootLayout)
        focusWhenShown(dialog, rootLayout)
        dialog.show()

        rootLayout.alpha = 0f
        rootLayout.animate().alpha(1f).setDuration(150).start()
    }

    fun showHistory() {
        val history = historyManager.getHistory()

        if (history.isEmpty()) {
            Toast.makeText(context, "Belum ada riwayat", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.7f)
            setGravity(Gravity.CENTER)
        }

        val density = context.resources.displayMetrics.density
        val maxWidth = (360 * density).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createMenuBackground()
            setPadding(
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt()
            )
            layoutParams = ViewGroup.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        rootLayout.addView(TextView(context).apply {
            text = "📋  Riwayat (${history.size})"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, (16 * density).toInt())
        })

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (300 * density).toInt().coerceAtMost(history.size * (60 * density).toInt())
            )
        }

        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        history.take(20).forEach { item ->
            val title = if (item.title.length > 35) item.title.take(35) + "…" else item.title
            listLayout.addView(createMenuItem("📄", title) {
                dialog.dismiss()
                onNavigate(item.url)
            })
        }

        scrollView.addView(listLayout)
        rootLayout.addView(scrollView)

        rootLayout.addView(createMenuItem("🗑️", "Hapus Semua Riwayat") {
            dialog.dismiss()
            historyManager.clearAll()
            Toast.makeText(context, "Riwayat dihapus", Toast.LENGTH_SHORT).show()
        })

        dialog.setContentView(rootLayout)
        focusWhenShown(dialog, rootLayout)
        dialog.show()

        rootLayout.alpha = 0f
        rootLayout.animate().alpha(1f).setDuration(150).start()
    }

    private fun confirmClearHistory() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.7f)
            setGravity(Gravity.CENTER)
        }

        val density = context.resources.displayMetrics.density
        val maxWidth = (300 * density).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createMenuBackground()
            setPadding(
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt()
            )
            layoutParams = ViewGroup.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }

        rootLayout.addView(TextView(context).apply {
            text = "🗑️"
            textSize = 36f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
        })

        rootLayout.addView(TextView(context).apply {
            text = "Hapus Riwayat?"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (8 * density).toInt())
        })

        rootLayout.addView(TextView(context).apply {
            text = "Semua riwayat tontonan akan dihapus."
            textSize = 13f
            setTextColor(Color.parseColor("#B3B3B3"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        })

        // Buttons row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun styleFocus(button: TextView, hasFocus: Boolean, fill: String) {
            val bg = button.background as? GradientDrawable
            bg?.setColor(Color.parseColor(if (hasFocus) "#E50914" else fill))
            bg?.setStroke(if (hasFocus) (2 * density).toInt() else 0, Color.WHITE)
            button.scaleX = if (hasFocus) 1.04f else 1f
            button.scaleY = if (hasFocus) 1.04f else 1f
        }

        // Cancel button
        val cancelButton = TextView(context).apply {
            text = "Batal"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(
                (24 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt(),
                (12 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#333333"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener { dialog.dismiss() }
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { v, hasFocus ->
                styleFocus(v as TextView, hasFocus, "#333333")
            }
        }

        // Delete button
        val deleteButton = TextView(context).apply {
            text = "Hapus"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(
                (24 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt(),
                (12 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#E50914"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
            setOnClickListener {
                dialog.dismiss()
                historyManager.clearAll()
                Toast.makeText(context, "Riwayat dihapus", Toast.LENGTH_SHORT).show()
            }
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { v, hasFocus ->
                styleFocus(v as TextView, hasFocus, "#E50914")
            }
        }

        buttonRow.addView(cancelButton)
        buttonRow.addView(deleteButton)
        rootLayout.addView(buttonRow)

        dialog.setContentView(rootLayout)
        dialog.setOnShowListener {
            cancelButton.post { cancelButton.requestFocus() }
        }
        dialog.show()
    }

    private fun createDivider(density: Float): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
            setBackgroundColor(Color.parseColor("#222222"))
        }
    }
}
