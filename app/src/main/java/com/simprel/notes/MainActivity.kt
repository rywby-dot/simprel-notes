package com.simprel.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.view.animation.PathInterpolator
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var toolbar: LinearLayout
    private val markdown by lazy { Markwon.builder(this).usePlugin(StrikethroughPlugin.create()).build() }
    private var ready = false
    private var current: Note? = null
    private var activeEditor: EditText? = null
    private lateinit var host: FrameLayout
    private var listPage: ListPage? = null
    private var overlay: View? = null
    private var localized: Context? = null
    private var swipe: SwipeProgress? = null
    private var velocity: VelocityTracker? = null
    private var gestureActive = false
    private var settling = false
    private var keyboardWasVisible = false
    private var savedListScroll: Int? = null
    private val easing = PathInterpolator(.2f, 0f, 0f, 1f)
    private fun str(id: Int, vararg args: Any): String = (localized ?: this).getString(id, *args)
    private fun alwaysDelete() = store.prefs.getBoolean("always_delete", false)
    private fun deletionVisible() = quickDelete || alwaysDelete()

    override fun attachBaseContext(base: Context) {
        val selected = base.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "system") ?: "system"
        val system = android.content.res.Resources.getSystem().configuration.locales[0].language
        val locale = Locale.forLanguageTag(resolveLanguage(selected, system))
        val config = Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    private var screen = "list"
    private var quickDelete = false
    private var backgrounded = true
    private var confirmation: AlertDialog? = null
    private var bg = 0
    private var fg = 0
    private var surface = 0
    private var outline = 0
    private var secondary = 0
    private var accent = 0
    private var darkTheme = false
    private var restored: ScreenState? = null
    private data class ScreenState(val screen: String, val note: Note?, val quick: Boolean)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun pref(key: String, fallback: String) = store.prefs.getString(key, fallback) ?: fallback
    private fun flag(key: String) = store.prefs.getBoolean(key, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Remove the system starting view as soon as content is ready, without an exit fade.
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        try { store = Store(this) } catch (_: Exception) {
            AlertDialog.Builder(this).setMessage(str(R.string.read_error))
                .setPositiveButton(str(R.string.close)) { _, _ -> finish() }.setCancelable(false).show()
            return
        }
        ready = true
        restored = lastNonConfigurationInstance as? ScreenState
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { back() }
    }
    override fun onResume() {
        super.onResume()
        if (!ready) return
        if (backgrounded) {
            confirmation?.dismiss()
            backgrounded = false
            quickDelete = false
            val previous = restored
            restored = null
            if (previous != null) {
                quickDelete = previous.quick
                when (previous.screen) {
                    "settings" -> settings()
                    "editor" -> open(store.notes.find { it.id == previous.note?.id } ?: previous.note ?: Note())
                    else -> showList()
                }
                return
            }
            when (pref("start", "new")) {
                "list" -> showList()
                "last" -> open(store.notes.find { it.id == pref("last", "") } ?: Note())
                else -> open(Note())
            }
        }
    }
    override fun onRetainNonConfigurationInstance(): Any = ScreenState(screen, current, quickDelete)
    override fun onStop() { super.onStop(); backgrounded = true; if (ready) persist() }
    @Deprecated("Used on Android 12 and older")
    override fun onBackPressed() { back() }
    private fun persist(): Boolean = try { store.save(); true } catch (_: Exception) {
        Toast.makeText(this, str(R.string.save_error), Toast.LENGTH_LONG).show(); false
    }
    private fun colors() {
        val dark = when (pref("theme", "system")) {
            "light" -> false; "dark", "amoled" -> true
            else -> resources.configuration.uiMode and 0x30 == 0x20
        }
        darkTheme = dark
        bg = Color.parseColor(if (pref("theme", "system") == "amoled") "#000000" else if (dark) "#141218" else "#FFFBFE")
        surface = Color.parseColor(if (dark) "#211F26" else "#F7F2FA")
        fg = Color.parseColor(if (dark) "#E6E0E9" else "#1D1B20")
        secondary = Color.parseColor(if (dark) "#CAC4D0" else "#49454F")
        outline = Color.parseColor(if (dark) "#49454F" else "#CAC4D0")
        accent = when (pref("accent", "system")) {
            "blue" -> Color.parseColor(if (dark) "#ADC6FF" else "#275EA7")
            "green" -> Color.parseColor(if (dark) "#9DD49E" else "#326B36")
            "rose" -> Color.parseColor(if (dark) "#FFB1C8" else "#984061")
            "violet" -> Color.parseColor(if (dark) "#D0BCFF" else "#6750A4")
            else -> if (android.os.Build.VERSION.SDK_INT >= 31) getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600) else Color.rgb(103, 80, 164)
        }
        if (::host.isInitialized) host.setBackgroundColor(bg)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.decorView.windowInsetsController?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
    private fun shape(color: Int, radius: Int = 28, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (border) setStroke(dp(1), outline)
    }
    private fun ensureHost() {
        if (::host.isInitialized) return
        host = FrameLayout(this).apply { setBackgroundColor(bg) }
        host.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(host)
        host.requestApplyInsets()
    }
    private fun discardList(preserveScroll: Boolean = true) {
        if (!preserveScroll) savedListScroll = null else listPage?.let { savedListScroll = it.scrollPosition() }
        listPage?.let { host.removeView(it) }
        listPage = null
    }
    private fun ensureList(): ListPage {
        ensureHost()
        val page = listPage ?: ListPage().also { listPage = it; host.addView(it, 0, FrameLayout.LayoutParams(-1, -1)) }
        page.submit()
        page.importantForAccessibility = if (screen == "list") View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return page
    }
    private fun frame() {
        activeEditor = null
        colors()
        ensureHost()
        overlay?.let { it.animate().cancel(); host.removeView(it) }
        settling = false
        resetGesture()
        listPage?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay = root
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
    }
    private fun spacer() { toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    private fun icon(kind: String, description: String, action: () -> Unit): View {
        val view = Glyph(this, kind, accent).apply {
            contentDescription = description; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(outline), shape(Color.TRANSPARENT), shape(Color.WHITE))
            setOnClickListener { action() }
        }
        return view
    }
    private fun tool(kind: String, description: String, action: () -> Unit): View = icon(kind, description, action).also {
        toolbar.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(host.windowToken, 0) }
    private fun open(note: Note) {
        current = note; screen = "editor"; quickDelete = false
        store.prefs.edit().putString("last", note.id).apply()
        frame()
        tool("back", str(R.string.all_notes)) { leaveEditor() }; spacer()
        tool("delete", str(R.string.delete_current)) { delete(note, "confirm_editor") {
            if (pref("delete_action", "list") == "exit") exitApp() else leaveEditor()
        } }
        tool("check", str(R.string.done)) {
            if (persist()) { if (pref("done_action", "exit") == "exit") exitApp() else leaveEditor() }
        }
        val editor = EditText(this).apply {
            setText(note.text); setTextColor(fg); setHintTextColor(secondary); hint = str(R.string.note_hint)
            textSize = 18f; gravity = Gravity.TOP; background = null
            setPadding(dp(24), dp(8), dp(24), dp(24))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            setLineSpacing(dp(4).toFloat(), 1f)
            highlightColor = (accent and 0x00FFFFFF) or 0x40000000
            if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(accent) }
        }
        activeEditor = editor
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        body.addView(editor, FrameLayout.LayoutParams(-1, -1))
        val styler = if (pref("markdown", "raw") == "render") MarkdownStyler(accent) else null
        styler?.apply(editor.text)
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (current !== note || screen != "editor") return
                note.text = s.toString()
                if (note.text.isNotBlank() && store.notes.none { it.id == note.id }) store.notes.add(0, note)
                persist()
            }
            override fun afterTextChanged(s: Editable?) { if (s != null) styler?.apply(s) }
        })
        editor.setSelection(editor.length())
        editor.requestFocus()
        editor.post { if (screen == "editor" && current?.id == note.id) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && screen == "editor" && !settling && !gestureActive) activeEditor?.let { editor ->
            editor.requestFocus()
            editor.post {
                if (activeEditor === editor && screen == "editor" && !settling && !gestureActive) {
                    if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.ime())
                    else @Suppress("DEPRECATION") (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }
    private fun exitApp() { hideKeyboard(); finishAndRemoveTask() }
    private fun leaveEditor() {
        if (settling || !persist()) return
        ensureList()
        hideKeyboard()
        settleEditor(true)
    }
    private fun settleEditor(dismiss: Boolean) {
        val page = overlay ?: return
        settling = true
        if (dismiss) { activeEditor?.clearFocus(); hideKeyboard() }
        page.animate().translationX(if (dismiss) host.width.toFloat() else 0f).setInterpolator(easing).setDuration(240).withEndAction {
            if (overlay !== page) return@withEndAction
            settling = false
            if (dismiss) {
                host.removeView(page)
                overlay = null
                activeEditor = null
                screen = "list"
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                listPage?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                host.isFocusableInTouchMode = true
                host.requestFocus()
            } else {
                page.elevation = 0f
                if (keyboardWasVisible) activeEditor?.let { editor ->
                    editor.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }.start()
    }
    private fun resetGesture() { velocity?.recycle(); velocity = null; swipe = null; gestureActive = false }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (settling) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetGesture()
            if (screen == "editor" && confirmation == null && activeEditor?.hasSelection() == false) {
                val bounds = Rect()
                if (activeEditor?.getGlobalVisibleRect(bounds) == true && bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    swipe = SwipeProgress(ViewConfiguration.get(this).scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong())
                    swipe?.start(event.x, event.y, event.eventTime)
                    velocity = VelocityTracker.obtain()
                }
            }
        }
        velocity?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (gestureActive) { resetGesture(); settleEditor(false); return true }
            resetGesture()
        }
        if (screen == "editor" && (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)) {
            val progress = swipe?.move(event.x, event.y, event.eventTime)
            if (progress != null) {
                if (!gestureActive) {
                    gestureActive = true
                    val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    ensureList()
                    overlay?.elevation = dp(12).toFloat()
                    keyboardWasVisible = if (android.os.Build.VERSION.SDK_INT >= 30) host.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true else true
                    hideKeyboard()
                }
                overlay?.translationX = progress.coerceIn(0f, host.width.toFloat())
            }
        }
        if (gestureActive) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                velocity?.computeCurrentVelocity(1000)
                val distance = overlay?.translationX ?: 0f
                val commit = event.actionMasked == MotionEvent.ACTION_UP && shouldDismissEditor(distance, host.width.toFloat(), velocity?.xVelocity ?: 0f, resources.displayMetrics.density)
                resetGesture()
                settleEditor(commit && persist())
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) resetGesture()
        return super.dispatchTouchEvent(event)
    }
    private fun back() {
        if (settling) return
        when (screen) {
            "editor" -> leaveEditor()
            "settings" -> showList()
            else -> if (quickDelete && !alwaysDelete()) { quickDelete = false; listPage?.updateDeleteControls() } else finish()
        }
    }
    private fun delete(note: Note, type: String, after: () -> Unit) {
        fun perform() {
            val index = store.notes.indexOfFirst { it.id == note.id }
            if (index >= 0) store.notes.removeAt(index)
            if (persist()) { if (current?.id == note.id) current = null; after() }
            else if (index >= 0) store.notes.add(index, note)
        }
        if (note.text.isBlank() || !flag(type)) { perform(); return }
        confirmation = AlertDialog.Builder(ContextThemeWrapper(this, if (darkTheme) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle(str(R.string.delete_question)).setMessage(note.text.take(180))
            .setNegativeButton(str(R.string.cancel), null).setPositiveButton(str(R.string.delete)) { _, _ -> perform() }
            .setOnDismissListener { confirmation = null }.show().also {
                it.window?.setBackgroundDrawable(shape(surface))
                it.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
                it.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent)
            }
    }
    private fun showList() {
        colors()
        ensureHost()
        hideKeyboard()
        resetGesture()
        overlay?.let { it.animate().cancel(); host.removeView(it) }
        overlay = null; activeEditor = null; settling = false
        screen = "list"
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        ensureList().updateDeleteControls()
        host.isFocusableInTouchMode = true; host.requestFocus()
    }
    private fun reorder(ids: List<String>): Boolean {
        val previous = store.notes.toList()
        val visible = previous.filter { it.text.isNotBlank() }
        if (ids.size != visible.size || ids.toSet() != visible.map { it.id }.toSet()) return false
        val byId = visible.associateBy { it.id }
        store.notes.clear()
        store.notes.addAll(ids.map { byId.getValue(it) })
        store.notes.addAll(previous.filter { it.text.isBlank() })
        if (!persist()) { store.notes.clear(); store.notes.addAll(previous); return false }
        listPage?.submit()
        return true
    }
    private inner class NoteCard(note: Note) : FrameLayout(this@MainActivity) {
        val content = TextView(this@MainActivity).apply {
            setTextColor(fg); textSize = 16f; setPadding(dp(18), dp(18), dp(18), dp(18))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val id = note.id
        private var rendered: String? = null
        val cross = icon("close", str(R.string.delete_note, note.text.take(40))) {
            store.notes.find { it.id == id }?.let { delete(it, "confirm_quick") { listPage?.submit() } }
        }
        init {
            background = shape(surface, 28, true); clipToOutline = true
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(cross, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
            setOnClickListener { if (screen == "list") store.notes.find { it.id == id }?.let { open(it) } }
            update(note)
        }
        fun update(note: Note) {
            if (rendered != note.text) {
                if (pref("markdown", "raw") == "render") markdown.setMarkdown(content, note.text) else content.text = note.text
                content.movementMethod = null
                content.isClickable = false; content.isLongClickable = false; content.isFocusable = false
                contentDescription = note.text
                cross.contentDescription = str(R.string.delete_note, note.text.take(40))
                rendered = note.text
            }
            cross.visibility = if (deletionVisible()) View.VISIBLE else View.GONE
        }
    }
    private inner class ListPage : LinearLayout(this@MainActivity) {
        private val reversed = pref("list_direction", "normal") == "reverse"
        private val pageBody = FrameLayout(this@MainActivity)
        private val bin = icon("delete", str(R.string.quick_delete)) { quickDelete = !quickDelete; updateDeleteControls() }
        private val empty = TextView(this@MainActivity).apply { text = str(R.string.empty_notes); setTextColor(secondary); textSize = 18f; gravity = Gravity.CENTER }
        private val grid = AnimatedNotesGrid(this@MainActivity, { NoteCard(it) }, { view, note -> (view as NoteCard).update(note) }, { reorder(it) }).apply { reverse = reversed }
        private val scroll = object : ScrollView(this@MainActivity) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (reversed) grid.minimumHeight = (h - paddingTop - paddingBottom).coerceAtLeast(0)
            }
        }
        private var submitted = false
        init {
            orientation = VERTICAL; setBackgroundColor(bg)
            val bar = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
            bar.addView(icon("settings", str(R.string.settings)) { settings() }, LayoutParams(dp(48), dp(48)))
            bar.addView(View(this@MainActivity), LayoutParams(0, 1, 1f))
            bar.addView(bin, LayoutParams(dp(48), dp(48)))
            addView(bar, LayoutParams(-1, dp(64)))
            addView(pageBody, LayoutParams(-1, 0, 1f))
            grid.dragLocation = { y ->
                val viewportY = y - scroll.scrollY + scroll.paddingTop
                if (viewportY < dp(72)) scroll.smoothScrollBy(0, -dp(24))
                else if (viewportY > scroll.height - dp(72)) scroll.smoothScrollBy(0, dp(24))
            }
            scroll.clipToPadding = false
            scroll.setPadding(dp(22), dp(14), dp(22), dp(96))
            scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))
            pageBody.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            pageBody.addView(empty, FrameLayout.LayoutParams(-1, -1))
            val add = icon("add", str(R.string.new_note)) { open(Note()) }.apply { background = shape(accent, 16) }
            (add as Glyph).ink = bg
            pageBody.addView(add, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
            bin.setOnDragListener { v, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                    DragEvent.ACTION_DRAG_ENTERED -> { v.background = shape(accent); (v as Glyph).ink = bg; v.invalidate(); true }
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { (v as Glyph).ink = accent; updateDeleteControls(); v.invalidate(); true }
                    DragEvent.ACTION_DROP -> {
                        store.notes.find { it.id == event.localState as? String }?.let { delete(it, "confirm_drag") { submit() } }
                        true
                    }
                    else -> true
                }
            }
            scroll.setOnDragListener { _, event ->
                if (event.action == DragEvent.ACTION_DRAG_LOCATION) {
                    if (event.y < dp(72)) scroll.smoothScrollBy(0, -dp(24))
                    else if (event.y > scroll.height - dp(72)) scroll.smoothScrollBy(0, dp(24))
                }
                event.localState is String
            }
        }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (screen == "list") super.dispatchTouchEvent(event) else true
        private fun positionAfterLayout(position: Int?) {
            scroll.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val bottom = (grid.height + scroll.paddingTop + scroll.paddingBottom - scroll.height).coerceAtLeast(0)
                    scroll.scrollTo(0, position ?: bottom)
                }
            })
            scroll.requestLayout()
        }
        fun scrollPosition() = scroll.scrollY
        fun submit() {
            val atBottom = scroll.scrollY >= grid.height - scroll.height + scroll.paddingTop + scroll.paddingBottom - dp(32)
            val notes = store.notes.filter { it.text.isNotBlank() }
            grid.submit(notes, submitted)
            empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
            val restore = if (!submitted) savedListScroll else null
            if (restore != null) { savedListScroll = null; positionAfterLayout(restore) }
            else if (reversed && (!submitted || atBottom)) positionAfterLayout(null)
            submitted = true
            updateDeleteControls()
        }
        fun updateDeleteControls() {
            val visible = deletionVisible()
            grid.cards().forEach { (it as NoteCard).cross.visibility = if (visible) View.VISIBLE else View.GONE }
            bin.isSelected = visible
            bin.isClickable = !alwaysDelete()
            bin.contentDescription = str(if (alwaysDelete()) R.string.always_delete else if (quickDelete) R.string.quick_delete_done else R.string.quick_delete)
            bin.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(outline), shape(if (visible) surface else Color.TRANSPARENT), shape(Color.WHITE))
        }
    }
    private fun settings() {
        screen = "settings"; frame()
        val bindings = mutableListOf<() -> Unit>()
        val back = tool("back", str(R.string.all_notes)) { showList() }
        bindings.add { (back as Glyph).ink = accent; back.contentDescription = str(R.string.all_notes); back.invalidate() }
        val title = TextView(this).apply { textSize = 22f; setPadding(dp(12), 0, 0, 0) }
        toolbar.addView(title)
        bindings.add { title.text = str(R.string.settings); title.setTextColor(fg) }
        val scroll = ScrollView(this); body.addView(scroll)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(32)) }; scroll.addView(list)
        fun refresh() { root.setBackgroundColor(bg); bindings.forEach { it() } }
        fun label(id: Int) {
            val view = TextView(this).apply { textSize = 14f; setPadding(0, dp(20), 0, dp(8)) }
            list.addView(view)
            bindings.add { view.text = str(id); view.setTextColor(accent) }
        }
        fun description(id: Int) {
            val view = TextView(this).apply { textSize = 14f }; list.addView(view)
            bindings.add { view.text = str(id); view.setTextColor(secondary) }
        }
        fun choice(titleId: Int, key: String, values: Array<String>, labels: IntArray, fallback: String) {
            label(titleId)
            val group = RadioGroup(this)
            values.forEachIndexed { index, value ->
                val button = RadioButton(this).apply { id = View.generateViewId(); textSize = 16f; minHeight = dp(48); isChecked = pref(key, fallback) == value }
                group.addView(button)
                bindings.add { button.text = str(labels[index]); button.setTextColor(fg); button.buttonTintList = ColorStateList.valueOf(accent) }
                button.setOnClickListener {
                    store.prefs.edit().putString(key, value).apply()
                    if (key == "language") {
                        val system = android.content.res.Resources.getSystem().configuration.locales[0].language
                        val locale = Locale.forLanguageTag(resolveLanguage(value, system))
                        localized = createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) })
                    }
                    if (key in setOf("language", "theme", "accent", "markdown", "list_direction")) discardList(key != "list_direction")
                    if (key in setOf("language", "theme", "accent")) { colors(); refresh() }
                }
            }
            list.addView(group)
        }
        fun toggle(id: Int, key: String, initial: Boolean, changed: (Boolean) -> Unit = {}) {
            val view = SettingToggle(this, str(id), initial, accent, surface, fg, secondary) { checked ->
                store.prefs.edit().putBoolean(key, checked).apply(); changed(checked)
            }
            list.addView(view)
            bindings.add { view.updateAppearance(str(id), accent, surface, fg, secondary) }
        }
        choice(R.string.language, "language", arrayOf("system", "ru", "en"), intArrayOf(R.string.system, R.string.russian, R.string.english), "system")
        choice(R.string.list_direction, "list_direction", arrayOf("normal", "reverse"), intArrayOf(R.string.direction_normal, R.string.direction_reverse), "normal")
        label(R.string.editor_buttons)
        choice(R.string.delete_button_action, "delete_action", arrayOf("exit", "list"), intArrayOf(R.string.exit_app, R.string.return_to_list), "list")
        choice(R.string.done_button_action, "done_action", arrayOf("exit", "list"), intArrayOf(R.string.exit_app, R.string.return_to_list), "exit")
        label(R.string.always_delete)
        toggle(R.string.always_delete, "always_delete", alwaysDelete()) { quickDelete = false }
        description(R.string.always_delete_help)
        choice(R.string.theme, "theme", arrayOf("system", "light", "dark", "amoled"), intArrayOf(R.string.system, R.string.light, R.string.dark, R.string.amoled), "system")
        choice(R.string.accent, "accent", arrayOf("system", "violet", "blue", "green", "rose"), intArrayOf(R.string.system, R.string.violet, R.string.blue, R.string.green, R.string.rose), "system")
        label(R.string.confirm_delete)
        toggle(R.string.in_editor, "confirm_editor", flag("confirm_editor"))
        toggle(R.string.by_drag, "confirm_drag", flag("confirm_drag"))
        toggle(R.string.by_cross, "confirm_quick", flag("confirm_quick"))
        choice(R.string.markdown, "markdown", arrayOf("raw", "render"), intArrayOf(R.string.markdown_raw, R.string.markdown_render), "raw")
        description(R.string.markdown_help)
        choice(R.string.on_open, "start", arrayOf("new", "list", "last"), intArrayOf(R.string.new_note, R.string.notes_list, R.string.previous_note), "new")
        refresh()
    }
}

/** Consistent 24dp outline icons inside 48dp touch targets. */
private class Glyph(context: android.content.Context, private val kind: String, var ink: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); paint.color = ink
        canvas.save(); val scale = resources.displayMetrics.density; canvas.translate(width / 2f - 12 * scale, height / 2f - 12 * scale); canvas.scale(scale, scale)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        when (kind) {
            "back" -> { line(19f,12f,5f,12f); line(5f,12f,12f,5f); line(5f,12f,12f,19f) }
            "check" -> { line(4f,12f,9f,17f); line(9f,17f,20f,6f) }
            "delete" -> { line(4f,6f,20f,6f); line(9f,3f,15f,3f); canvas.drawRoundRect(6f,6f,18f,21f,2f,2f,paint); line(10f,10f,10f,17f); line(14f,10f,14f,17f) }
            "close" -> { line(6f,6f,18f,18f); line(6f,18f,18f,6f) }
            "add" -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
            "settings" -> { canvas.drawCircle(12f,12f,7f,paint); canvas.drawCircle(12f,12f,3f,paint); repeat(8) { canvas.save(); canvas.rotate(it*45f,12f,12f); line(12f,2f,12f,5f); canvas.restore() } }
        }; canvas.restore()
    }
}
