package com.ayomide.guardian.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ClipDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.ayomide.guardian.R
import java.util.Locale
import kotlin.math.roundToInt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun <T : View> T.weight(w: Float): T = apply {
    layoutParams = LinearLayout.LayoutParams(0, WRAP, w)
}

fun <T : View> T.marginTop(px: Int): T = apply {
    val lp = (layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = px
    layoutParams = lp
}

/** Shape + colour helpers for the Material 3-style look. */
fun Context.rounded(colorRes: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radiusDp).toFloat()
    setColor(getColor(colorRes))
}

/** Wraps [content] in a ripple whose mask matches its rounded shape. */
fun Context.ripple(content: Drawable?, radiusDp: Int): RippleDrawable {
    val mask = GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(0xFFFFFFFF.toInt()) }
    return RippleDrawable(ColorStateList.valueOf(0x22000000), content, mask)
}

/** Small builder for the programmatic, framework-only UI. */
class Ui(val ctx: Context) {

    companion object {
        const val CARD_RADIUS = 24
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun color(id: Int) = ctx.getColor(id)

    fun column(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    /** Filled tonal card (no outline), 24dp corners. */
    fun card(onClick: (() -> Unit)? = null): LinearLayout = tonalCard(R.color.card, onClick)

    fun tonalCard(bgRes: Int, onClick: (() -> Unit)? = null, radiusDp: Int = CARD_RADIUS): LinearLayout = column().apply {
        val bg = ctx.rounded(bgRes, radiusDp)
        background = if (onClick != null) ctx.ripple(bg, radiusDp) else bg
        val p = ctx.dp(20)
        setPadding(p, ctx.dp(18), p, ctx.dp(18))
        marginTop(ctx.dp(12))
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun text(
        s: CharSequence,
        sizeSp: Float = 14f,
        colorRes: Int = R.color.text_secondary,
        bold: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color(colorRes))
        typeface = if (bold) (if (sizeSp >= 20f) Typeface.DEFAULT_BOLD else MEDIUM) else Typeface.DEFAULT
        setLineSpacing(0f, 1.15f)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    fun title(s: CharSequence) = text(s, 17f, R.color.text_primary, true)
    fun primary(s: CharSequence) = text(s, 15f, R.color.text_primary)
    fun small(s: CharSequence) = text(s, 13.5f)
    fun big(s: CharSequence, colorRes: Int = R.color.text_primary) = text(s, 32f, colorRes, true)

    /** Section header: sentence case, primary colour (Material 3 style). */
    fun section(s: String) = text(s, 15f, R.color.accent, true).apply {
        setPadding(ctx.dp(4), 0, 0, 0)
    }.marginTop(ctx.dp(28))

    /** Pill-shaped button. Primary = filled, otherwise tonal. */
    fun button(label: String, filled: Boolean = true, onClick: () -> Unit): Button = Button(ctx, null, 0).apply {
        text = label
        isAllCaps = false
        typeface = MEDIUM
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        stateListAnimator = null
        gravity = Gravity.CENTER
        minHeight = ctx.dp(52)
        minimumHeight = ctx.dp(52)
        setPadding(ctx.dp(24), 0, ctx.dp(24), 0)
        val bg = ctx.rounded(if (filled) R.color.accent else R.color.accent_container, 100)
        background = ctx.ripple(bg, 100)
        setTextColor(color(if (filled) R.color.on_accent else R.color.on_accent_container))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(12) }
        setOnClickListener { onClick() }
    }

    fun tonalButton(label: String, onClick: () -> Unit) = button(label, filled = false, onClick = onClick)

    /** Text button: no container, primary colour. */
    fun flatButton(label: String, onClick: () -> Unit): Button = Button(ctx, null, 0).apply {
        text = label
        isAllCaps = false
        typeface = MEDIUM
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        setTextColor(color(R.color.accent))
        minHeight = ctx.dp(48)
        minimumHeight = ctx.dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        background = ctx.ripple(null, 100)
        setOnClickListener { onClick() }
    }

    /** Small rounded label, e.g. a status chip. */
    fun chip(s: String, bgRes: Int = R.color.accent, fgRes: Int = R.color.on_accent): TextView = TextView(ctx).apply {
        text = s
        typeface = MEDIUM
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(color(fgRes))
        background = ctx.rounded(bgRes, 100)
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(10) }
    }

    /** Vector icon inside a tinted rounded square ("squircle"). */
    fun iconBadge(iconRes: Int, bgRes: Int, fgRes: Int, sizeDp: Int = 48): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(color(fgRes))
        background = ctx.rounded(bgRes, sizeDp * 3 / 10)
        val p = ctx.dp(sizeDp / 4)
        setPadding(p, p, p, p)
        contentDescription = null
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    fun progress(percent: Int, colorRes: Int = R.color.accent): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val track = ctx.rounded(R.color.card_high, 100)
            val fill = ClipDrawable(ctx.rounded(colorRes, 100), Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
            }
            progress = percent.coerceIn(0, 100)
            layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(10)).apply { topMargin = ctx.dp(10) }
        }

    fun input(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = ctx.rounded(R.color.card_high, 16)
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(10) }
        }

    fun numberInput(hint: String, value: String = "") =
        input(hint, value, InputType.TYPE_CLASS_NUMBER)

    fun decimalInput(hint: String, value: String = "") =
        input(hint, value, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

    fun multiline(hint: String, value: String = "", lines: Int = 3): EditText =
        input(hint, value, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        }

    fun label(s: String) = text(s, 13.5f, R.color.text_secondary, true).marginTop(ctx.dp(16))

    fun spinner(items: List<String>, selected: Int): Spinner = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        background = ctx.rounded(R.color.card_high, 16)
        setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(8) }
    }

    /**
     * Dialog with a form. [onSave] returns an error message to show, or null
     * to close. [onDelete] adds a Delete button (with confirmation).
     */
    fun form(
        title: String,
        body: LinearLayout,
        saveLabel: String = "Save",
        onDelete: (() -> Unit)? = null,
        onSave: () -> String?,
    ) {
        val error = text("", 13f, R.color.danger).apply { visibility = View.GONE }
        val wrapper = column().apply {
            val p = ctx.dp(20)
            setPadding(p, ctx.dp(8), p, 0)
            addView(body)
            addView(error.marginTop(ctx.dp(8)))
        }
        val scroll = ScrollView(ctx).apply { addView(wrapper) }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(saveLabel, null)
            .setNegativeButton("Cancel", null)
        if (onDelete != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                confirm("Delete?", "This can't be undone.", "Delete") { onDelete() }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val err = try {
                    onSave()
                } catch (e: Exception) {
                    "Something went wrong: ${e.message}"
                }
                if (err == null) {
                    dialog.dismiss()
                } else {
                    error.text = err
                    error.visibility = View.VISIBLE
                }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    fun confirm(title: String, message: String, yes: String, action: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun empty(message: String) = card().apply {
        setPadding(ctx.dp(24), ctx.dp(28), ctx.dp(24), ctx.dp(28))
        addView(text(message, 14.5f).apply { gravity = Gravity.CENTER })
    }
}
