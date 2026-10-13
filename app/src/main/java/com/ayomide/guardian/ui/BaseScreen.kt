package com.ayomide.guardian.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ayomide.guardian.R

/**
 * Base for every screen: a top bar with back arrow and title, and a scrolling
 * column that is rebuilt by [render] whenever the screen is shown or data
 * changes (call [refresh]).
 */
abstract class BaseScreen : Activity() {

    protected lateinit var ui: Ui
    protected lateinit var content: LinearLayout
    protected lateinit var actions: LinearLayout
    private lateinit var scroll: ScrollView

    abstract val screenTitle: String
    protected open val showBack: Boolean = true
    protected lateinit var titleView: TextView

    /** Optional fixed bar under the scrolling content (e.g. bottom navigation). */
    protected open fun bottomBar(): View? = null

    /** Scrolls back to the top on the next render (e.g. after switching tabs). */
    protected var resetScroll = false

    /** Round icon button for the top bar. */
    protected fun iconButton(icon: Int, label: String, onClick: () -> Unit) = android.widget.ImageView(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary))
        contentDescription = label
        val p = dp(12)
        setPadding(p, p, p, p)
        background = ripple(rounded(R.color.card, 100), 100)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }

        val bar = ui.row().apply {
            setPadding(dp(4), dp(12), dp(8), dp(8))
            minimumHeight = dp(64)
        }
        if (showBack) {
            bar.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary))
                contentDescription = "Back"
                val p = dp(12)
                setPadding(p, p, p, p)
                background = ripple(rounded(R.color.card, 100), 100)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8); marginEnd = dp(4) }
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
            })
        }
        titleView = TextView(this).apply {
            text = screenTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (showBack) 22f else 28f)
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(if (showBack) dp(8) else dp(12), 0, 0, 0)
        }.weight(1f)
        bar.addView(titleView)
        actions = ui.row().apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        bar.addView(actions)

        content = ui.column().apply { setPadding(dp(16), 0, dp(16), dp(32)) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }

        root.addView(bar)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomBar()?.let { root.addView(it) }
        setContentView(root)
        applyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        val y = if (resetScroll) 0 else scroll.scrollY
        resetScroll = false
        content.removeAllViews()
        render()
        scroll.post { scroll.scrollTo(0, y) }
    }

    abstract fun render()

    protected fun add(v: View) = content.addView(v)

    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }
}
