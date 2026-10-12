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
import android.text.TextUtils
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
    private var editorDraft: EditorDraft? = null
    private var activeEditor: EditText? = null
    private lateinit var host: FrameLayout
    private var listPage: ListPage? = null
    private var overlay: View? = null
    private var localized: Context? = null
    private var swipe: SwipeProgress? = null
    private var velocity: VelocityTracker? = null
    private var gestureActive = false
    private var settling = false
    private var pendingList = false
    private var listGesture: SwipeProgress? = null
    private var listGestureMode = 0
    private var settingsSwipe = false
    private var gestureLastNote = ""
    private var gestureQuickDelete = false
    private fun compact() = pref("layout", "grid") == "compact"
    private var keyboardWasVisible = false
    private var savedListScroll: Int? = null
    private val easing = PathInterpolator(.2f, 0f, 0f, 1f)
    private fun str(id: Int, vararg args: Any): String = (localized ?: this).getString(id, *args)
    private fun alwaysDelete() = store.prefs.getBoolean("always_delete", true)
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
    private var preparingBackground = false
    private val createdNoteIds = mutableSetOf<String>()
    private var confirmation: AlertDialog? = null
    private var undoPanel: View? = null
    private var undoAppearance: (() -> Unit)? = null
    private val undoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var undoTimeout: Runnable? = null
    private var bg = 0
    private var fg = 0
    private var surface = 0
    private var outline = 0
    private var secondary = 0
    private var accent = 0
    private var darkTheme = false
    private var restored: ScreenState? = null
    private data class ScreenState(val screen: String, val note: Note?, val quick: Boolean, val returning: Boolean, val createdIds: Set<String>, val draft: EditorDraft?)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun pref(key: String, fallback: String) = store.prefs.getString(key, fallback) ?: fallback
    private fun textScale() = store.prefs.getInt("text_scale", 100).coerceIn(50, 400) / 100f
    private val baseTextSizes = java.util.WeakHashMap<TextView, Float>()
    private fun scaleText(view: View) {
        if (view is TextView && view !is NotePreview) {
            val base = baseTextSizes.getOrPut(view) { view.textSize }
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * textScale())
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) scaleText(view.getChildAt(i))
    }
    private fun flag(key: String) = store.prefs.getBoolean(key, false)

    private fun runtimeTheme(selected: String?, dark: Boolean): Int = when {
        selected == "amoled" -> R.style.AppThemeAmoled
        dark -> R.style.AppThemeDark
        else -> R.style.AppThemeLight
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val launchPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val launchTheme = launchPrefs.getString("theme", "system")
        val systemDark = android.content.res.Resources.getSystem().configuration.uiMode and 0x30 == 0x20
        val launchDark = launchTheme in setOf("dark", "amoled") || (launchTheme == "system" && systemDark)
        setTheme(runtimeTheme(launchTheme, launchDark))
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(if (launchTheme == "amoled") "#000000" else if (launchDark) "#141218" else "#FFFBFE")))
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
        // Restore the platform default for tasks whose preview an older version disabled.
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(true)
        colors()
        restored = lastNonConfigurationInstance as? ScreenState
        restored?.let { createdNoteIds.addAll(it.createdIds) }
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { back() }
    }
    override fun onStart() {
        super.onStart()
        restoreForeground()
    }
    override fun onResume() {
        super.onResume()
        restoreForeground()
        preparingBackground = false
        if (ready) { focusEditor(); showPendingUndo() }
    }
    private fun restoreForeground() {
        if (!ready) return
        if (backgrounded) {
            confirmation?.dismiss()
            backgrounded = false
            val wasInitialized = ::host.isInitialized
            val previous = restored
            restored = null
            if (previous != null && !previous.returning) {
                quickDelete = previous.quick
                when (previous.screen) {
                    "settings" -> settings()
                    "editor" -> {
                        open(store.notes.find { it.id == previous.note?.id } ?: previous.note ?: Note())
                        previous.draft?.takeIf { it.id == current?.id }?.let { editorDraft = it }
                    }
                    else -> showList()
                }
                host.visibility = View.VISIBLE
                return
            }
            if (pref("start", "new") != "previous") quickDelete = false
            when (pref("start", "new")) {
                "previous" -> {
                    if (!wasInitialized) when (pref("previous_screen", "list")) {
                        "editor" -> open(store.notes.find { it.id == pref("last", "") } ?: Note())
                        "settings" -> settings()
                        else -> showList()
                    }
                }
                "list" -> showList()
                "last" -> open(store.notes.find { it.id == pref("last", "") } ?: Note())
                else -> open(Note())
            }
            host.visibility = View.VISIBLE
        }
    }
    override fun onDestroy() {
        undoTimeout?.let { undoHandler.removeCallbacks(it) }
        super.onDestroy()
    }
    override fun onRetainNonConfigurationInstance(): Any = ScreenState(screen, current, quickDelete, backgrounded, createdNoteIds.toSet(), editorDraft)
    override fun onPause() {
        preparingBackground = true
        if (ready) {
            confirmation?.dismiss()
            if (listGestureMode != 0) cancelListGesture()
            overlay?.animate()?.cancel()
            if (pendingList) showList()
            if (settling) { settling = false; overlay?.translationX = 0f }
            overlay?.translationX = 0f
            overlay?.elevation = 0f
            resetGesture()
            persist()
            store.prefs.edit().putString("previous_screen", screen).apply()
        }
        if (ready && android.os.Build.VERSION.SDK_INT >= 31) {
            val systemDark = android.content.res.Resources.getSystem().configuration.uiMode and 0x30 == 0x20
            val dark = when (pref("theme", "system")) { "dark", "amoled" -> true; "light" -> false; else -> systemDark }
            val mode = if (dark) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_NO
            if (store.prefs.getInt("launch_night_mode", -1) != mode) {
                store.prefs.edit().putInt("launch_night_mode", mode).apply()
                getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(mode)
            }
        }
        backgrounded = !isChangingConfigurations
        super.onPause()
    }
    override fun onStop() { if (ready) persist(); super.onStop() }
    @Deprecated("Used on Android 12 and older")
    override fun onBackPressed() { back() }
    private fun persist(): Boolean = try { store.save(); true } catch (_: Exception) {
        Toast.makeText(this, str(R.string.save_error), Toast.LENGTH_LONG).show(); false
    }
    private fun colors() {
        val dark = when (pref("theme", "system")) {
            "light" -> false; "dark", "amoled" -> true
            else -> android.content.res.Resources.getSystem().configuration.uiMode and 0x30 == 0x20
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
        setTheme(runtimeTheme(pref("theme", "system"), dark))
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        if (::host.isInitialized) host.setBackgroundColor(bg)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.decorView.windowInsetsController?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        undoAppearance?.invoke()
    }
    private fun noteBorderWidth(): Float {
        val pixels = store.prefs.getFloat("border_width", 2f).coerceIn(0f, 25f) * resources.displayMetrics.density
        return pixels
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
    private fun frame(preserveGesture: Boolean = false) {
        activeEditor = null
        colors()
        ensureHost()
        overlay?.let { it.animate().cancel(); host.removeView(it) }
        settling = false
        pendingList = false
        if (!preserveGesture) resetGesture()
        listPage?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay = root
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
        undoPanel?.bringToFront()
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
    private fun open(note: Note, interactive: Boolean = false, animated: Boolean = false) {
        editorDraft = EditorDraft(note.id, note.text, store.notes.indexOfFirst { it.id == note.id && it.text.isNotBlank() }, pref("last", ""))
        current = note; screen = "editor"; quickDelete = false
        store.prefs.edit().putString("last", note.id).apply()
        frame(interactive)
        tool("back", str(R.string.all_notes)) { leaveEditor() }; spacer()
        tool("delete", str(R.string.delete_current)) { delete(note, "confirm_editor") {
            if (pref("delete_action", "exit") == "exit") exitApp() else leaveEditor()
        } }
        tool("copy", str(R.string.copy_note)) { copyEditor(pref("copy_action", "copy_delete_exit")) }
        tool("check", str(R.string.done)) {
            if (persist()) { if (pref("done_action", "exit") == "exit") exitApp() else leaveEditor() }
        }
        val editor = EditText(this).apply {
            setText(note.text); setTextColor(fg); setHintTextColor(secondary); hint = str(R.string.note_hint)
            textSize = 18f * textScale(); gravity = Gravity.TOP; background = null
            setPadding(dp(24), dp(8), dp(24), dp(24))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            setLineSpacing(dp(4).toFloat(), 1f)
            highlightColor = (accent and 0x00FFFFFF) or 0x40000000
            if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(accent) }
        }
        activeEditor = editor
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or if (interactive || animated || preparingBackground) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        editor.isVerticalScrollBarEnabled = false
        editor.isHorizontalScrollBarEnabled = false
        editor.isVerticalFadingEdgeEnabled = false
        editor.overScrollMode = View.OVER_SCROLL_NEVER
        // The full-height text lives in a native scrolling container: dragging,
        // fling deceleration, caret visibility and edge stretch share Android's behavior.
        val editorScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            if (android.os.Build.VERSION.SDK_INT >= 29) setEdgeEffectColor(outline)
            addView(editor, FrameLayout.LayoutParams(-1, -2))
        }
        body.addView(editorScroll, FrameLayout.LayoutParams(-1, -1).apply { rightMargin = dp(12) })
        val rail = ScrollRail(this, editorScroll, outline).apply { setBackgroundColor(bg) }
        body.addView(rail, FrameLayout.LayoutParams(dp(12), -1, Gravity.END))
        editorScroll.setOnScrollChangeListener { _, _, _, _, _ -> rail.invalidate() }
        editor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rail.invalidate() }
        editorScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rail.invalidate() }
        val styler = if (pref("markdown", "render") == "render") MarkdownStyler(markdown) else null
        styler?.apply(editor.text)
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (current !== note || screen != "editor") return
                note.text = s.toString()
                if (note.text.isNotBlank() && store.notes.none { it.id == note.id }) {
                    store.notes.add(0, note)
                    createdNoteIds.add(note.id)
                }
                persist()
            }
            override fun afterTextChanged(s: Editable?) { if (s != null) styler?.apply(s) }
        })
        editor.setSelection(editor.length())
        if (interactive || preparingBackground) { editor.clearFocus(); host.isFocusableInTouchMode = true; host.requestFocus() }
        else editor.requestFocus()
        if (animated) presentOverlay(1)
        editor.post { if (!interactive && !animated && !preparingBackground && screen == "editor" && current?.id == note.id) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !preparingBackground && screen == "editor" && !settling && !gestureActive && listGestureMode == 0) activeEditor?.let { editor ->
            editor.requestFocus()
            editor.post {
                if (!preparingBackground && activeEditor === editor && screen == "editor" && !settling && !gestureActive && listGestureMode == 0) {
                    if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.ime())
                    else @Suppress("DEPRECATION") (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }
    private fun copyText(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(str(R.string.note_hint), text))
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, str(R.string.note_copied), Toast.LENGTH_SHORT).show()
    }
    private fun copyEditor(behavior: String) {
        val note = current ?: return
        val action = copyAction(behavior)
        fun afterCopy() {
            when (action.destination) {
                "exit" -> exitApp()
                "list" -> leaveEditor()
            }
        }
        if (action.delete) delete(note, "confirm_editor", copy = true) { afterCopy() }
        else if (persist()) { copyText(note.text); afterCopy() }
    }
    private fun exitApp() { hideKeyboard(); finishAndRemoveTask() }
    private fun leaveEditor() {
        if (settling || !persist()) return
        ensureList()
        hideKeyboard()
        settleEditor(true)
    }
    private fun discardEditorChanges(): Boolean {
        val note = current ?: return false
        val draft = editorDraft?.takeIf { it.id == note.id } ?: return false
        val before = store.notes.toList()
        store.notes.clear(); store.notes.addAll(draft.discardFrom(before))
        if (!persist()) { store.notes.clear(); store.notes.addAll(before); return false }
        createdNoteIds.remove(note.id)
        // Keep the EditText binding usable if Android interrupts the exit animation.
        note.text = if (draft.index >= 0) draft.text else ""
        val restoredIndex = store.notes.indexOfFirst { it.id == note.id }
        if (restoredIndex >= 0) store.notes[restoredIndex] = note
        current = null
        activeEditor?.setText(note.text)
        activeEditor?.setSelection(note.text.length)
        current = note
        if (draft.index < 0) store.prefs.edit().putString("last", draft.previousLast).apply()
        listPage?.submit()
        return true
    }
    private fun settleEditor(dismiss: Boolean, exit: Boolean = false, left: Boolean = exit, restored: (() -> Unit)? = null) {
        val page = overlay ?: return
        settling = true
        pendingList = dismiss && !exit
        if (dismiss) { activeEditor?.clearFocus(); hideKeyboard() }
        page.animate().translationX(if (dismiss) host.width.toFloat() * (if (left) -1 else 1) else 0f).setInterpolator(easing).setDuration(240).withEndAction {
            if (overlay !== page) return@withEndAction
            settling = false
            pendingList = false
            if (dismiss && exit) { exitApp(); return@withEndAction }
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
                restored?.invoke()
            }
        }.start()
    }
    private fun presentOverlay(direction: Int) {
        val page = overlay ?: return
        ensureList()
        settling = true
        page.translationX = direction * (host.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        page.elevation = dp(12).toFloat()
        page.animate().translationX(0f).setDuration(240).setInterpolator(easing).withEndAction {
            if (overlay === page) { settling = false; page.elevation = 0f; focusEditor() }
        }.start()
    }
    private fun focusEditor() {
        if (screen != "editor" || preparingBackground) return
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        activeEditor?.let { editor -> editor.requestFocus(); editor.post {
            if (!preparingBackground && activeEditor === editor && !settling && listGestureMode == 0) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        } }
    }
    private fun leaveSettings() { if (!settling) settleSettings(true) }
    private fun settleSettings(dismiss: Boolean) {
        ensureList()
        val page = overlay ?: return
        settling = true
        pendingList = dismiss
        hideKeyboard()
        page.animate().translationX(if (dismiss) -host.width.toFloat() else 0f).setDuration(240).setInterpolator(easing).withEndAction {
            if (overlay !== page) return@withEndAction
            if (dismiss) showList() else { settling = false; pendingList = false; page.elevation = 0f }
        }.start()
    }
    private fun cancelListGesture() {
        store.prefs.edit().putString("last", gestureLastNote).apply()
        listGestureMode = 0
        showList()
        quickDelete = gestureQuickDelete
        listPage?.updateDeleteControls()
    }
    private fun settleListGesture(commit: Boolean) {
        val page = overlay ?: return
        val direction = listGestureMode
        settling = true
        page.animate().translationX(if (commit) 0f else -direction * host.width.toFloat()).setDuration(240).setInterpolator(easing).withEndAction {
            if (overlay !== page) return@withEndAction
            settling = false
            if (commit) { listGestureMode = 0; page.elevation = 0f; focusEditor() } else cancelListGesture()
        }.start()
    }
    private fun resetGesture() { velocity?.recycle(); velocity = null; swipe = null; listGesture = null; gestureActive = false; settingsSwipe = false }
    private fun touchesSettingsInput(view: View, x: Float, y: Float): Boolean {
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(x.toInt(), y.toInt())) return false
        if (view is SeekBar || view is EditText) return true
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (touchesSettingsInput(view.getChildAt(index), x, y)) return true
        }
        return false
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (settling) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetGesture()
            if (screen == "list" && confirmation == null && event.y > host.paddingTop + dp(64)) {
                listGesture = SwipeProgress(ViewConfiguration.get(this).scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong(), true)
                listGesture?.start(event.x, event.y, event.eventTime)
                velocity = VelocityTracker.obtain()
            }
            if (screen == "settings" && confirmation == null && event.y > host.paddingTop + dp(64) && !touchesSettingsInput(root, event.rawX, event.rawY)) {
                settingsSwipe = true
                swipe = SwipeProgress(ViewConfiguration.get(this).scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong())
                swipe?.start(-event.x, event.y, event.eventTime)
                velocity = VelocityTracker.obtain()
            }
            if (screen == "editor" && confirmation == null && activeEditor?.hasSelection() == false) {
                val bounds = Rect()
                val undoBounds = Rect()
                val touchesUndo = undoPanel?.getGlobalVisibleRect(undoBounds) == true && undoBounds.contains(event.rawX.toInt(), event.rawY.toInt())
                if (!touchesUndo && activeEditor?.getGlobalVisibleRect(bounds) == true && bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    swipe = SwipeProgress(ViewConfiguration.get(this).scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong(), true)
                    swipe?.start(event.x, event.y, event.eventTime)
                    velocity = VelocityTracker.obtain()
                }
            }
        }
        velocity?.addMovement(event)
        if (listGesture != null || listGestureMode != 0) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                val progress = listGesture?.move(event.x, event.y, event.eventTime)
                if (progress != null) {
                    if (listGestureMode == 0 && progress != 0f) {
                        val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                        gestureLastNote = pref("last", "")
                        gestureQuickDelete = quickDelete
                        listGestureMode = if (progress > 0) 1 else -1
                        if (listGestureMode == 1) settings(interactive = true) else open(Note(), interactive = true)
                        overlay?.elevation = dp(12).toFloat()
                    }
                    if (listGestureMode != 0) overlay?.translationX = (-listGestureMode * host.width + progress).coerceIn(if (listGestureMode == 1) -host.width.toFloat() else 0f, if (listGestureMode == 1) 0f else host.width.toFloat())
                }
            }
            if (listGestureMode != 0) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    velocity?.computeCurrentVelocity(1000)
                    val distance = host.width - kotlin.math.abs(overlay?.translationX ?: 0f)
                    val commit = event.actionMasked == MotionEvent.ACTION_UP && shouldDismissEditor(distance, host.width.toFloat(), (velocity?.xVelocity ?: 0f) * listGestureMode, resources.displayMetrics.density)
                    resetGesture()
                    settleListGesture(commit)
                }
                return true
            }
        }
        if (settingsSwipe) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                val progress = swipe?.move(-event.x, event.y, event.eventTime)
                if (progress != null) {
                    if (!gestureActive) {
                        gestureActive = true
                        val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                        ensureList(); hideKeyboard()
                        overlay?.elevation = dp(12).toFloat()
                    }
                    overlay?.translationX = -progress.coerceIn(0f, host.width.toFloat())
                }
            }
            if (gestureActive) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    velocity?.computeCurrentVelocity(1000)
                    val commit = event.actionMasked == MotionEvent.ACTION_UP && shouldDismissEditor(-(overlay?.translationX ?: 0f), host.width.toFloat(), -(velocity?.xVelocity ?: 0f), resources.displayMetrics.density)
                    resetGesture()
                    settleSettings(commit)
                }
                return true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (gestureActive) { resetGesture(); settleEditor(false); return true }
            resetGesture()
        }
        if (screen == "editor" && !gestureActive && activeEditor?.hasSelection() == true) {
            resetGesture()
            return super.dispatchTouchEvent(event)
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
                overlay?.translationX = progress.coerceIn(-host.width.toFloat(), host.width.toFloat())
            }
        }
        if (gestureActive) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                velocity?.computeCurrentVelocity(1000)
                val distance = overlay?.translationX ?: 0f
                val action = if (event.actionMasked == MotionEvent.ACTION_UP) editorSwipeAction(distance, host.width.toFloat(), velocity?.xVelocity ?: 0f, resources.displayMetrics.density) else 0
                val behavior = if (action < 0) pref("left_swipe_action", "save_exit") else "save_list"
                if (action < 0 && behavior.startsWith("copy_")) {
                    resetGesture()
                    settleEditor(false, restored = { copyEditor(behavior) })
                    return true
                }
                val completed = action != 0 && (if (behavior == "discard_exit" || behavior == "discard_list") discardEditorChanges() else persist())
                resetGesture()
                settleEditor(completed, exit = completed && action < 0 && behavior != "discard_list", left = action < 0)
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
            "settings" -> leaveSettings()
            else -> if (quickDelete && !alwaysDelete()) { quickDelete = false; listPage?.updateDeleteControls() } else finish()
        }
    }
    private fun clearUndo() {
        undoTimeout?.let { undoHandler.removeCallbacks(it) }; undoTimeout = null
        undoPanel?.let { host.removeView(it) }; undoPanel = null; undoAppearance = null
        store.prefs.edit().remove("pending_undo").apply()
    }
    private fun showPendingUndo() {
        val encoded = store.prefs.getString("pending_undo", null) ?: return
        val entry = try { org.json.JSONObject(encoded) } catch (_: Exception) { clearUndo(); return }
        val original = UndoWindow(entry.optLong("expires"), entry.optBoolean("awaiting"))
        val window = original.onEntry(System.currentTimeMillis(), store.prefs.getInt("undo_seconds", 4))
        if (window != original) {
            entry.put("awaiting", false).put("expires", window.expiresAt)
            store.prefs.edit().putString("pending_undo", entry.toString()).commit()
        }
        val remaining = window.remaining(System.currentTimeMillis())
        if (remaining <= 0) { clearUndo(); return }
        undoTimeout?.let { undoHandler.removeCallbacks(it) }
        undoPanel?.let { host.removeView(it) }
        val panel = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(4), dp(8), dp(4))
            elevation = dp(6).toFloat()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val message = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), dp(12), dp(12)) }
        panel.addView(message, LinearLayout.LayoutParams(0, -2, 1f))
        val undo = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            minHeight = dp(48); minWidth = dp(64); setPadding(dp(12), dp(8), dp(12), dp(8)); isFocusable = true
            setOnClickListener {
                val note = Note(entry.getString("id"), entry.getString("text"))
                val index = entry.optInt("index").coerceIn(0, store.notes.size)
                val inserted = store.notes.none { it.id == note.id }
                if (inserted) store.notes.add(index, note)
                if (persist()) {
                    clearUndo(); listPage?.submit()
                    if (entry.optBoolean("editor")) open(note, animated = true)
                } else if (inserted) store.notes.remove(note)
            }
        }
        panel.addView(undo, LinearLayout.LayoutParams(-2, -2))
        undoAppearance = {
            panel.background = shape(if (pref("theme", "system") == "amoled") bg else surface, 12, border = true)
            message.text = str(R.string.note_deleted); message.setTextColor(fg)
            undo.text = str(R.string.undo); undo.setTextColor(accent)
            undo.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or 0x22000000), null, shape(Color.WHITE, 12))
        }
        undoAppearance?.invoke()
        scaleText(panel)
        undoPanel = panel
        host.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(16) })
        panel.alpha = 0f; panel.translationY = dp(16).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(180).start()
        undoTimeout = Runnable { clearUndo() }.also { undoHandler.postDelayed(it, remaining) }
    }
    private fun delete(note: Note, type: String, copy: Boolean = false, after: () -> Unit) {
        fun perform() {
            if (copy) copyText(note.text)
            val index = store.notes.indexOfFirst { it.id == note.id }
            if (index >= 0) store.notes.removeAt(index)
            if (persist()) {
                if (current?.id == note.id) current = null
                val undoEnabled = index >= 0 && note.text.isNotBlank() && store.prefs.getBoolean(type.replace("confirm_", "undo_"), true)
                if (index >= 0) clearUndo()
                if (undoEnabled) {
                    val entry = org.json.JSONObject().put("id", note.id).put("text", note.text).put("index", index)
                        .put("editor", type == "confirm_editor").put("expires", System.currentTimeMillis() + store.prefs.getInt("undo_seconds", 4).coerceIn(1, 10) * 1000L)
                    store.prefs.edit().putString("pending_undo", entry.toString()).commit()
                    after()
                    if (isFinishing) {
                        entry.put("awaiting", true)
                        store.prefs.edit().putString("pending_undo", entry.toString()).commit()
                    } else showPendingUndo()
                } else after()
            } else if (index >= 0) store.notes.add(index, note)
        }
        if (note.text.isBlank() || !flag(type)) { perform(); return }
        confirmation = AlertDialog.Builder(ContextThemeWrapper(this, if (darkTheme) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle(str(R.string.delete_question)).setMessage(note.text.take(180))
            .setNegativeButton(str(R.string.cancel), null).setPositiveButton(str(R.string.delete)) { _, _ -> perform() }
            .setOnDismissListener { confirmation = null }.show().also {
                it.window?.setBackgroundDrawable(shape(surface))
                it.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
                it.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent)
                it.window?.decorView?.let { view -> scaleText(view) }
            }
    }
    private fun showList() {
        colors()
        ensureHost()
        hideKeyboard()
        resetGesture()
        overlay?.let { it.animate().cancel(); host.removeView(it) }
        overlay = null; activeEditor = null; settling = false; pendingList = false
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
    private inner class NoteCard(note: Note, private val preview: Boolean = false) : FrameLayout(this@MainActivity) {
        private fun cardCompact() = !preview && compact()
        private fun badgeEnabled() = preview || deletionVisible()
        val content = NotePreview(this@MainActivity).apply {
            setTextColor(fg); textSize = 16f * textScale(); setPadding(dp(18), dp(18), dp(18), dp(18))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val id = note.id
        private var cardRadius = 0f
        private val textClip = Path()
        private fun updateGeometry() {
            cardRadius = if (cardCompact()) 0f else minOf(width, height) * store.prefs.getInt("rounding", 20).coerceIn(0, 100) / 200f
            val cardShape = NoteSurfaceDrawable(surface, cardRadius, noteBorderWidth(), outline)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or 0x22000000), cardShape,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = cardRadius })
            content.setTextColor(fg)
            content.textSize = 16f * textScale()
            content.gravity = if (cardCompact()) Gravity.CENTER_VERTICAL else Gravity.TOP
            content.isSingleLine = cardCompact()
            val padding = (store.prefs.getFloat("text_padding", 12f).coerceIn(0f, 50f) * resources.displayMetrics.density).toInt()
            content.setPadding(padding, if (cardCompact()) 0 else padding, if (cardCompact() && badgeEnabled()) maxOf(padding, dp(42)) else padding, if (cardCompact()) 0 else padding)
            content.maxLines = if (cardCompact()) 1 else ((height - content.paddingTop - content.paddingBottom) / content.lineHeight.coerceAtLeast(1)).coerceAtLeast(1)
            content.cornerRadius = cardRadius
            content.compact = cardCompact()
            content.badgeVisible = badgeEnabled() && !cardCompact()
            content.geometryChanged()
            (cross as Glyph).ink = accent; cross.invalidate()
            val badgeShape = NoteSurfaceDrawable(surface, dp(100).toFloat(), noteBorderWidth(), outline)
            cross.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(outline), badgeShape, shape(Color.WHITE, 100))
            requestLayout()
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (cardCompact()) {
                cross.layout(width - dp(36), (height - dp(28)) / 2, width - dp(8), (height + dp(28)) / 2)
            } else {
                val center = noteBadgeCenter(width.toFloat(), cardRadius, dp(14).toFloat())
                val x = (center.first - dp(14)).toInt()
                val y = (center.second - dp(14)).toInt()
                cross.layout(x, y, x + dp(28), y + dp(28))
            }
            val badgeLeft = cross.left - dp(3).toFloat()
            val badgeBottom = cross.bottom + dp(3).toFloat()
            if (content.badgeLeft != badgeLeft || content.badgeBottom != badgeBottom) {
                content.badgeLeft = badgeLeft; content.badgeBottom = badgeBottom
                content.geometryChanged()
            }
        }
        override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
            if (child !== content || cardCompact()) return super.drawChild(canvas, child, drawingTime)
            textClip.reset()
            textClip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cardRadius, cardRadius, Path.Direction.CW)
            val save = canvas.save(); canvas.clipPath(textClip)
            val result = super.drawChild(canvas, child, drawingTime)
            canvas.restoreToCount(save)
            return result
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateGeometry()
        }
        private var rendered: String? = null
        private var renderedMode: String? = null
        val cross = icon("close", str(R.string.delete_note, note.text.take(40))) {
            store.notes.find { it.id == id }?.let { delete(it, "confirm_quick") { listPage?.submit() } }
        }
        init {
            tag = id
            background = shape(surface, 28, true); clipToOutline = false; clipChildren = false; clipToPadding = false
            content.gravity = if (cardCompact()) Gravity.CENTER_VERTICAL else Gravity.TOP
            content.setPadding(dp(18), if (cardCompact()) 0 else dp(18), if (badgeEnabled()) dp(48) else dp(18), if (cardCompact()) 0 else dp(18))
            if (cardCompact()) content.isSingleLine = true
            addView(content, FrameLayout.LayoutParams(-1, -1))
            cross.tag = "note-delete-badge"
            if (!preview) cross.setOnLongClickListener {
                store.notes.find { it.id == id }?.let { delete(it, "confirm_quick", copy = true) { listPage?.submit() } }
                true
            }
            addView(cross, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
            setOnClickListener { if (!preview && screen == "list") store.notes.find { it.id == id }?.let { open(it, animated = true) } }
            update(note)
            if (preview) {
                isClickable = false; isFocusable = false; cross.isClickable = false; cross.isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        fun update(note: Note) {
            val mode = "${cardCompact()}:${pref("markdown", "render")}:$accent"
            if (rendered != note.text || renderedMode != mode) {
                renderedMode = mode
                if (pref("markdown", "render") == "render") {
                    val rendered = android.text.SpannableStringBuilder(markdown.toMarkdown(note.text))
                    if (cardCompact()) for (i in rendered.indices) if (rendered[i] == '\n' || rendered[i] == '\r') rendered.replace(i, i + 1, " ")
                    content.text = rendered
                } else content.text = if (cardCompact()) note.text.replace(Regex("[\\r\\n]+"), " ") else note.text
                content.movementMethod = null
                content.isClickable = false; content.isLongClickable = false; content.isFocusable = false
                contentDescription = note.text
                cross.contentDescription = str(R.string.delete_note, note.text.take(40))
                rendered = note.text
            }
            cross.visibility = if (badgeEnabled()) View.VISIBLE else View.GONE
            content.setPadding(dp(18), if (cardCompact()) 0 else dp(18), if (badgeEnabled()) dp(48) else dp(18), if (cardCompact()) 0 else dp(18))
            updateGeometry()
        }
    }
    private inner class ListPage : LinearLayout(this@MainActivity) {
        private val reversed = pref("list_direction", "reverse") == "reverse"
        private val pageBody = FrameLayout(this@MainActivity)
        private val bin = icon("delete", str(R.string.quick_delete)) { quickDelete = !quickDelete; updateDeleteControls() }
        private val empty = TextView(this@MainActivity).apply { text = str(R.string.empty_notes); setTextColor(secondary); textSize = 18f * textScale(); gravity = Gravity.CENTER }
        private val grid = AnimatedNotesGrid(this@MainActivity, { NoteCard(it) }, { view, note -> (view as NoteCard).update(note) }, { reorder(it) }).apply { reverse = reversed; compact = this@MainActivity.compact() }
        private val scrollAnchor = ListScrollAnchor()
        private val scroll = object : ScrollView(this@MainActivity) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { scrollAnchor.release() }
                return super.onInterceptTouchEvent(event)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { scrollAnchor.release() }
                return super.onTouchEvent(event)
            }
            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                scrollAnchor.release()
                return super.onGenericMotionEvent(event)
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (reversed) grid.minimumHeight = (h - paddingTop - paddingBottom).coerceAtLeast(0)
            }
        }
        private var submitted = false
        init {
            orientation = VERTICAL; setBackgroundColor(bg)
            val bar = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
            bar.addView(icon("settings", str(R.string.settings)) { settings(animated = true) }, LayoutParams(dp(48), dp(48)))
            bar.addView(View(this@MainActivity), LayoutParams(0, 1, 1f))
            bar.addView(bin, LayoutParams(dp(48), dp(48)))
            lateinit var mode: View
            mode = icon(if (compact()) "grid" else "list", str(if (compact()) R.string.grid_view else R.string.compact_view)) {
                val anchor = grid.cards().filter { it.bottom >= scroll.scrollY }.minByOrNull { kotlin.math.abs(it.top - scroll.scrollY) }
                val offset = anchor?.let { it.top - scroll.scrollY } ?: 0
                store.prefs.edit().putString("layout", if (compact()) "grid" else "compact").apply()
                scrollAnchor.release()
                grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        grid.viewTreeObserver.removeOnPreDrawListener(this)
                        if (anchor != null) scroll.scrollTo(0, (anchor.top - offset).coerceAtLeast(0))
                        return true
                    }
                })
                grid.changePresentation(compact())
                submit()
                scrollAnchor.release()
                (mode as Glyph).kind = if (compact()) "grid" else "list"
                mode.contentDescription = str(if (compact()) R.string.grid_view else R.string.compact_view); mode.invalidate()
            }
            bar.addView(mode, LayoutParams(dp(48), dp(48)))
            addView(bar, LayoutParams(-1, dp(64)))
            addView(pageBody, LayoutParams(-1, 0, 1f))
            grid.dragLocation = { y ->
                scrollAnchor.release()
                val viewportY = y - scroll.scrollY + scroll.paddingTop
                if (viewportY < dp(72)) scroll.smoothScrollBy(0, -dp(24))
                else if (viewportY > scroll.height - dp(72)) scroll.smoothScrollBy(0, dp(24))
            }
            scroll.clipToPadding = false
            scroll.isVerticalScrollBarEnabled = false
            scroll.isHorizontalScrollBarEnabled = false
            scroll.isVerticalFadingEdgeEnabled = false
            scroll.isHorizontalFadingEdgeEnabled = false
            scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            if (android.os.Build.VERSION.SDK_INT >= 29) scroll.setEdgeEffectColor(outline)
            scroll.isFillViewport = true
            scroll.setPadding(0, 0, 0, dp(96))
            // These are part of the child's measured bounds, not outside them.
            // ScrollView can clip its child, so an external margin cannot protect overflowing badges.
            grid.setPadding(if (compact()) 0 else dp(22), if (compact()) 0 else dp(28), if (compact()) 0 else dp(22), if (compact()) 0 else dp(14))
            scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))
            scroll.viewTreeObserver.addOnPreDrawListener {
                val target = scrollAnchor.target(grid.height + scroll.paddingTop + scroll.paddingBottom, scroll.height)
                if (target != null && scroll.scrollY != target) scroll.scrollTo(0, target)
                true
            }
            pageBody.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { rightMargin = dp(12) })
            val rail = ScrollRail(this@MainActivity, scroll, outline).apply { setBackgroundColor(bg) }
            pageBody.addView(rail, FrameLayout.LayoutParams(dp(12), -1, Gravity.END))
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> rail.invalidate() }
            grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rail.invalidate() }
            scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rail.invalidate() }
            pageBody.addView(empty, FrameLayout.LayoutParams(-1, -1))
            val add = icon("add", str(R.string.new_note)) { open(Note(), animated = true) }.apply { background = shape(accent, 16) }
            (add as Glyph).ink = bg
            pageBody.addView(add, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
            bin.setOnDragListener { v, event ->
                if (alwaysDelete()) return@setOnDragListener false
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
            if (position == null) scrollAnchor.followBottom() else scrollAnchor.restore(position)
            scroll.requestLayout()
            scroll.invalidate()
        }
        private fun followInsertionEdge() {
            scrollAnchor.followInsertion(reversed)
            scroll.requestLayout()
            scroll.invalidate()
        }
        fun scrollPosition() = scroll.scrollY
        fun submit() {
            val atBottom = scroll.scrollY >= grid.height - scroll.height + scroll.paddingTop + scroll.paddingBottom - dp(32)
            val notes = store.notes.filter { it.text.isNotBlank() }
            val newlyCreated = notes.map { it.id }.filter { it in createdNoteIds }
            val metrics = android.text.TextPaint().apply { textSize = 16f * resources.displayMetrics.scaledDensity * textScale() }.fontMetricsInt
            grid.compactRowHeight = maxOf(dp(56), 2 * (metrics.bottom - metrics.top) + dp(16))
            grid.rounding = store.prefs.getInt("rounding", 20).coerceIn(0, 100) / 100f
            grid.borderWidth = noteBorderWidth().toFloat()
            grid.borderColor = outline
            grid.submit(notes, submitted)
            grid.invalidate()
            empty.textSize = 18f * textScale()
            empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
            val restore = if (!submitted) savedListScroll else null
            if (newlyCreated.isNotEmpty()) {
                savedListScroll = null
                createdNoteIds.removeAll(newlyCreated.toSet())
                followInsertionEdge()
            } else if (restore != null) { savedListScroll = null; positionAfterLayout(restore) }
            else if (reversed && (!submitted || atBottom)) positionAfterLayout(null)
            submitted = true
            updateDeleteControls()
        }
        fun updateDeleteControls() {
            val visible = deletionVisible()
            grid.cards().forEach { card -> store.notes.find { it.id == (card as NoteCard).tag }?.let { (card as NoteCard).update(it) } }
            bin.visibility = if (alwaysDelete()) View.GONE else View.VISIBLE
            bin.isSelected = visible
            bin.isClickable = !alwaysDelete()
            bin.contentDescription = str(if (alwaysDelete()) R.string.always_delete else if (quickDelete) R.string.quick_delete_done else R.string.quick_delete)
            bin.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(outline), shape(if (visible) surface else Color.TRANSPARENT), shape(Color.WHITE))
        }
    }
    private fun settings(interactive: Boolean = false, animated: Boolean = false) {
        screen = "settings"; frame(interactive)
        hideKeyboard()
        val bindings = mutableListOf<() -> Unit>()
        val back = tool("back", str(R.string.all_notes)) { leaveSettings() }
        bindings.add { (back as Glyph).ink = accent; back.contentDescription = str(R.string.all_notes); back.invalidate() }
        val title = TextView(this).apply { textSize = 22f; setPadding(dp(12), dp(8), 0, dp(8)) }
        toolbar.minimumHeight = dp(64)
        toolbar.layoutParams = toolbar.layoutParams.apply { height = -2 }
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bindings.add { title.text = str(R.string.settings); title.setTextColor(fg) }
        val scroll = ScrollView(this); body.addView(scroll)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(32)) }; scroll.addView(list)
        fun refresh() { root.setBackgroundColor(bg); bindings.forEach { it() }; scaleText(root) }
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
            fun selected() = values.indexOf(pref(key, fallback)).coerceAtLeast(0)
            fun select(index: Int) {
                store.prefs.edit().putString(key, values[index]).apply()
                if (key == "language") {
                    val system = android.content.res.Resources.getSystem().configuration.locales[0].language
                    val locale = Locale.forLanguageTag(resolveLanguage(values[index], system))
                    localized = createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) })
                }
                if (key in setOf("language", "theme", "accent", "markdown", "list_direction")) discardList(key != "list_direction")
                if (key in setOf("language", "theme", "accent")) colors()
                refresh()
            }
            if (values.size == 2) {
                val group = LinearLayout(this)
                values.indices.forEach { index ->
                    val button = TextView(this).apply {
                        textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(48)
                        setPadding(dp(8), dp(12), dp(8), dp(12)); isFocusable = true
                        setOnClickListener { select(index) }
                    }
                    group.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
                    bindings.add {
                        val checked = selected() == index
                        button.text = (if (checked) "✓  " else "") + str(labels[index])
                        button.isSelected = checked; button.setTextColor(fg)
                        val radii = if (index == 0) floatArrayOf(100f,100f,0f,0f,0f,0f,100f,100f) else floatArrayOf(0f,0f,100f,100f,100f,100f,0f,0f)
                        val shape = GradientDrawable().apply { setColor(if (checked) surface else bg); cornerRadii = radii; setStroke(dp(1), outline) }
                        button.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or 0x22000000), shape, null)
                        button.contentDescription = str(labels[index])
                        if (android.os.Build.VERSION.SDK_INT >= 30) button.stateDescription = str(if (checked) R.string.selected else R.string.not_selected)
                    }
                }
                list.addView(group, LinearLayout.LayoutParams(-1, -2))
            } else {
                val row = TextView(this).apply {
                    textSize = 16f; minHeight = dp(56); gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12)); isFocusable = true
                }
                bindings.add {
                    row.text = str(labels[selected()]) + "   ▾"; row.setTextColor(fg)
                    row.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or 0x22000000), shape(surface, 12), shape(Color.WHITE, 12))
                }
                row.setOnClickListener {
                    val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
                    val heading = TextView(this).apply { text = str(titleId); textSize = 24f; setTextColor(fg); setPadding(0, 0, 0, dp(16)) }
                    options.addView(heading)
                    val dialog = android.app.Dialog(this)
                    values.indices.forEach { index ->
                        val item = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); isFocusable = true }
                        item.addView(MaterialRadio(this, index == selected(), accent, secondary), LinearLayout.LayoutParams(dp(40), dp(48)))
                        item.addView(TextView(this).apply { text = str(labels[index]); textSize = 16f; setTextColor(fg); setPadding(dp(8), dp(12), 0, dp(12)) }, LinearLayout.LayoutParams(0, -2, 1f))
                        item.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or 0x22000000), null, shape(Color.WHITE, 12))
                        item.contentDescription = str(labels[index]); item.isSelected = index == selected()
                        item.accessibilityDelegate = object : View.AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.className = "android.widget.RadioButton"; info.isCheckable = true; info.isChecked = index == selected()
                            }
                        }
                        item.setOnClickListener { dialog.dismiss(); select(index) }
                        options.addView(item)
                    }
                    val scroller = ScrollView(this).apply { addView(options) }
                    scaleText(options)
                    dialog.setContentView(scroller); dialog.window?.setBackgroundDrawable(shape(surface, 28))
                    dialog.show(); dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(240)), -2)
                }
                list.addView(row)
            }
        }
        fun toggle(id: Int, key: String, initial: Boolean, changed: (Boolean) -> Unit = {}) {
            val view = SettingToggle(this, str(id), initial, accent, surface, fg, secondary) { checked ->
                store.prefs.edit().putBoolean(key, checked).apply(); changed(checked)
            }
            list.addView(view)
            bindings.add { view.updateAppearance(str(id), accent, surface, fg, secondary) }
        }
        label(R.string.preview)
        val sample = NoteCard(Note("settings-preview", str(R.string.preview_text)), true)
        val sampleBox = FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(16), dp(12), dp(12)) }
        sampleBox.addView(sample, FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER))
        list.addView(sampleBox, LinearLayout.LayoutParams(-1, dp(208)))
        fun updateSample() { sample.update(Note("settings-preview", str(R.string.preview_text))); sample.requestLayout(); sample.invalidate() }
        bindings.add { updateSample() }
        fun slider(title: Int, max: Int, initial: Int, value: (Int) -> String, save: (Int) -> Unit) {
            label(title)
            val readout = TextView(this).apply { textSize = 16f }
            list.addView(readout)
            val slider = RoundingSlider(this).apply { this.max = max; keyProgressIncrement = 1; progress = initial.coerceIn(0, max) }
            list.addView(slider, LinearLayout.LayoutParams(-1, dp(56)))
            bindings.add {
                slider.activeColor = accent; slider.trackColor = outline; slider.invalidate()
                slider.contentDescription = str(title)
                readout.text = value(slider.progress); readout.setTextColor(fg)
                if (android.os.Build.VERSION.SDK_INT >= 30) slider.stateDescription = readout.text
            }
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar?, progress: Int, fromUser: Boolean) {
                    readout.text = value(progress)
                    if (android.os.Build.VERSION.SDK_INT >= 30) slider.stateDescription = readout.text
                    if (fromUser) { save(progress); updateSample(); listPage?.submit(); scaleText(root) }
                }
                override fun onStartTrackingTouch(view: SeekBar?) {}
                override fun onStopTrackingTouch(view: SeekBar?) {}
            })
        }
        slider(R.string.card_rounding, 100, store.prefs.getInt("rounding", 20), { "$it%" }) { store.prefs.edit().putInt("rounding", it).apply() }
        slider(R.string.border_width, 50, (store.prefs.getFloat("border_width", 2f) * 2).toInt(), { String.format(Locale.getDefault(), "%.1f dp", it / 2f) }) { store.prefs.edit().putFloat("border_width", it / 2f).apply() }
        slider(R.string.text_padding, 50, store.prefs.getFloat("text_padding", 12f).toInt(), { "$it dp" }) { store.prefs.edit().putFloat("text_padding", it.toFloat()).apply() }
        slider(R.string.text_scale, 70, (store.prefs.getInt("text_scale", 100) - 50) / 5, { "${50 + it * 5}%" }) { store.prefs.edit().putInt("text_scale", 50 + it * 5).apply() }
        choice(R.string.language, "language", arrayOf("system", "ru", "en"), intArrayOf(R.string.system, R.string.russian, R.string.english), "system")
        choice(R.string.list_direction, "list_direction", arrayOf("normal", "reverse"), intArrayOf(R.string.direction_normal, R.string.direction_reverse), "reverse")
        label(R.string.editor_buttons)
        choice(R.string.delete_button_action, "delete_action", arrayOf("exit", "list"), intArrayOf(R.string.exit_app, R.string.return_to_list), "exit")
        choice(R.string.done_button_action, "done_action", arrayOf("exit", "list"), intArrayOf(R.string.exit_app, R.string.return_to_list), "exit")
        val copyValues = arrayOf("copy_delete_exit", "copy_stay", "copy_list", "copy_delete_list", "copy_exit")
        val copyLabels = intArrayOf(R.string.copy_delete_exit, R.string.copy_stay, R.string.copy_list, R.string.copy_delete_list, R.string.copy_exit)
        choice(R.string.copy_button_action, "copy_action", copyValues, copyLabels, "copy_delete_exit")
        choice(R.string.left_swipe_action, "left_swipe_action", arrayOf("save_exit", "discard_exit", "discard_list") + copyValues,
            intArrayOf(R.string.swipe_save_exit, R.string.swipe_discard_exit, R.string.swipe_discard_list) + copyLabels, "save_exit")
        description(R.string.swipe_discard_help)
        label(R.string.always_delete)
        toggle(R.string.always_delete, "always_delete", alwaysDelete()) { quickDelete = false }
        description(R.string.always_delete_help)
        choice(R.string.theme, "theme", arrayOf("system", "light", "dark", "amoled"), intArrayOf(R.string.system, R.string.light, R.string.dark, R.string.amoled), "system")
        choice(R.string.accent, "accent", arrayOf("system", "violet", "blue", "green", "rose"), intArrayOf(R.string.system, R.string.violet, R.string.blue, R.string.green, R.string.rose), "system")
        label(R.string.confirm_delete)
        toggle(R.string.in_editor, "confirm_editor", flag("confirm_editor"))
        toggle(R.string.by_drag, "confirm_drag", flag("confirm_drag"))
        toggle(R.string.by_cross, "confirm_quick", flag("confirm_quick"))
        label(R.string.undo_settings)
        toggle(R.string.in_editor, "undo_editor", store.prefs.getBoolean("undo_editor", true))
        toggle(R.string.by_drag, "undo_drag", store.prefs.getBoolean("undo_drag", true))
        toggle(R.string.by_cross, "undo_quick", store.prefs.getBoolean("undo_quick", true))
        slider(R.string.undo_duration, 9, store.prefs.getInt("undo_seconds", 4) - 1, { str(R.string.seconds_value, it + 1) }) { store.prefs.edit().putInt("undo_seconds", it + 1).apply() }
        description(R.string.undo_exit_help)
        choice(R.string.markdown, "markdown", arrayOf("raw", "render"), intArrayOf(R.string.markdown_raw, R.string.markdown_render), "render")
        description(R.string.markdown_help)
        choice(R.string.on_open, "start", arrayOf("new", "list", "last", "previous"), intArrayOf(R.string.new_note, R.string.notes_list, R.string.previous_note, R.string.previous_screen), "new")
        fun resetDefaults() {
                val edit = store.prefs.edit()
                listOf("rounding", "border_width", "text_padding", "language", "list_direction",
                    "delete_action", "done_action", "copy_action", "always_delete", "theme", "accent",
                    "confirm_editor", "confirm_drag", "confirm_quick", "markdown", "start", "layout",
                    "text_scale", "undo_editor", "undo_drag", "undo_quick", "undo_seconds", "left_swipe_action")
                    .forEach { edit.remove(it) }
                edit.apply()
                quickDelete = false
                val system = android.content.res.Resources.getSystem().configuration.locales[0].language
                val locale = Locale.forLanguageTag(resolveLanguage("system", system))
                localized = createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) })
                discardList()
                colors()
                settings()
        }
        val divider = View(this)
        list.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(32); bottomMargin = dp(24) })
        val reset = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56); isFocusable = true
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        val resetIcon = Glyph(this, "reset", accent).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        reset.addView(resetIcon, LinearLayout.LayoutParams(dp(24), dp(48)).apply { rightMargin = dp(16) })
        val resetLabel = TextView(this).apply {
            textSize = 16f; setPadding(0, dp(12), 0, dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        reset.addView(resetLabel, LinearLayout.LayoutParams(0, -2, 1f))
        reset.setOnClickListener {
            confirmation = AlertDialog.Builder(ContextThemeWrapper(this, if (darkTheme) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert))
                .setTitle(str(R.string.reset_question)).setMessage(str(R.string.reset_message))
                .setNegativeButton(str(R.string.cancel), null)
                .setPositiveButton(str(R.string.reset_action)) { _, _ -> resetDefaults() }
                .setOnDismissListener { confirmation = null }.show().also { dialog ->
                    dialog.window?.setBackgroundDrawable(shape(surface, 28))
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent)
                    dialog.window?.decorView?.let { scaleText(it) }
                }
        }
        list.addView(reset, LinearLayout.LayoutParams(-1, -2))
        bindings.add {
            divider.setBackgroundColor(outline)
            resetLabel.text = str(R.string.reset_settings); resetLabel.setTextColor(fg)
            reset.contentDescription = str(R.string.reset_settings)
            resetIcon.ink = accent; resetIcon.invalidate()
            reset.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or 0x22000000), shape(surface, 12), shape(Color.WHITE, 12))
        }
        refresh()
        if (animated) presentOverlay(-1)
    }
}

/** Consistent 24dp outline icons inside 48dp touch targets. */
private class Glyph(context: android.content.Context, var kind: String, var ink: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); paint.color = ink
        paint.strokeWidth = if (kind == "close") 1.6f else 1.9f
        canvas.save(); val scale = resources.displayMetrics.density; canvas.translate(width / 2f - 12 * scale, height / 2f - 12 * scale); canvas.scale(scale, scale)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        when (kind) {
            "back" -> { line(19f,12f,5f,12f); line(5f,12f,12f,5f); line(5f,12f,12f,19f) }
            "check" -> { line(4f,12f,9f,17f); line(9f,17f,20f,6f) }
            "delete" -> { line(4f,6f,20f,6f); line(9f,3f,15f,3f); canvas.drawRoundRect(6f,6f,18f,21f,2f,2f,paint); line(10f,10f,10f,17f); line(14f,10f,14f,17f) }
            "copy" -> { canvas.drawRoundRect(8f,8f,20f,21f,2f,2f,paint); line(16f,4f,4f,4f); line(4f,4f,4f,17f) }
            "close" -> { line(8f,8f,16f,16f); line(8f,16f,16f,8f) }
            "add" -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
            "grid" -> { for (x in listOf(4f,14f)) for (y in listOf(4f,14f)) canvas.drawRoundRect(x,y,x+6f,y+6f,1f,1f,paint) }
            "list" -> { for (y in listOf(5f,12f,19f)) line(4f,y,20f,y) }
            "reset" -> {
                // Counterclockwise restore arrow around a small gear.
                canvas.drawArc(3f,3f,21f,21f,205f,285f,false,paint)
                line(3.8f,4.8f,3.8f,9f); line(3.8f,9f,8f,9f)
                canvas.drawCircle(12f,12f,4f,paint); canvas.drawCircle(12f,12f,1.3f,paint)
                repeat(8) { canvas.save(); canvas.rotate(it*45f,12f,12f); line(12f,6.5f,12f,8f); canvas.restore() }
            }
            "settings" -> { canvas.drawCircle(12f,12f,7f,paint); canvas.drawCircle(12f,12f,3f,paint); repeat(8) { canvas.save(); canvas.rotate(it*45f,12f,12f); line(12f,2f,12f,5f); canvas.restore() } }
        }; canvas.restore()
    }
}
