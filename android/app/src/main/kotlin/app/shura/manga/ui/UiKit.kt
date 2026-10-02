package app.shura.manga.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import app.shura.manga.R

/**
 * Shura's design tokens and view builders.
 *
 * Every screen is built in code rather than XML, so these helpers are the single place the visual
 * language lives: the dark palette, the crimson accent, corner radii, spacing and the few widget
 * shapes (card, primary/secondary button, icon button, cover, section header, empty state). A
 * screen that uses them cannot drift from the identity by accident.
 */
object ShuraColors {
    const val background = 0xFF0B0B0D.toInt()
    const val surface = 0xFF141417.toInt()
    const val surfaceVariant = 0xFF1C1C21.toInt()
    const val card = 0xFF17171B.toInt()
    const val accent = 0xFFE23744.toInt()
    const val accentPressed = 0xFFB01521.toInt()
    const val onAccent = 0xFFFFFFFF.toInt()
    const val onBackground = 0xFFFFFFFF.toInt()
    const val textSecondary = 0xFF9AA0A6.toInt()
    const val textTertiary = 0xFF6B7075.toInt()
    const val outline = 0xFF2A2A30.toInt()
    const val success = 0xFF5CC98B.toInt()
    const val error = 0xFFFF5A5F.toInt()
    const val scrim = 0xCC000000.toInt()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.dpF(value: Float): Float = value * resources.displayMetrics.density

fun Context.rounded(
    color: Int,
    radiusDp: Int = 14,
    strokeColor: Int? = null,
    strokeDp: Int = 0,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = dpF(radiusDp.toFloat())
    if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
}

fun View.roundOutline(radiusDp: Int) {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, view.resources.displayMetrics.density * radiusDp)
        }
    }
}

fun Context.text(
    value: CharSequence,
    sizeSp: Float = 15f,
    color: Int = ShuraColors.onBackground,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
): TextView = TextView(this).apply {
    text = value
    textSize = sizeSp
    setTextColor(color)
    includeFontPadding = false
    if (bold) setTypeface(typeface, Typeface.BOLD)
    this.maxLines = maxLines
    if (maxLines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
}

/** A section title, optionally with a trailing action on the opposite side. */
fun Context.sectionHeader(title: CharSequence, action: Pair<CharSequence, () -> Unit>? = null): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(
        text(title, 17f, ShuraColors.onBackground, bold = true),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    if (action != null) {
        row.addView(text(action.first, 13f, ShuraColors.accent).apply {
            setPaddingRelative(dp(8), dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { action.second() }
        })
    }
    return row
}

fun Context.primaryButton(label: CharSequence, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 15f
    setTextColor(ShuraColors.onAccent)
    background = rounded(ShuraColors.accent, 12)
    stateListAnimator = null
    minHeight = 0
    minimumHeight = 0
    setPaddingRelative(dp(20), dp(12), dp(20), dp(12))
    setOnClickListener { onClick() }
}

fun Context.secondaryButton(label: CharSequence, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 15f
    setTextColor(ShuraColors.onBackground)
    background = rounded(ShuraColors.surfaceVariant, 12, ShuraColors.outline, 1)
    stateListAnimator = null
    minHeight = 0
    minimumHeight = 0
    setPaddingRelative(dp(20), dp(12), dp(20), dp(12))
    setOnClickListener { onClick() }
}

fun Context.iconButton(
    iconRes: Int,
    tint: Int = ShuraColors.onBackground,
    sizeDp: Int = 40,
    onClick: (() -> Unit)? = null,
): ImageButton = ImageButton(this).apply {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(tint)
    background = ColorDrawable(Color.TRANSPARENT)
    scaleType = ImageView.ScaleType.CENTER
    setPadding(dp(8), dp(8), dp(8), dp(8))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    onClick?.let { click -> setOnClickListener { click() } }
}

/** A cover-sized, rounded, outlined image placeholder. Callers hand the URL to [CoverLoader]. */
fun Context.cover(widthDp: Int, heightDp: Int, radiusDp: Int = 10): ImageView = ImageView(this).apply {
    scaleType = ImageView.ScaleType.CENTER_CROP
    background = rounded(ShuraColors.surfaceVariant, radiusDp)
    roundOutline(radiusDp)
    layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))
}

fun Context.progressBar(sizeDp: Int = 24): ProgressBar = ProgressBar(this).apply {
    indeterminateTintList = ColorStateList.valueOf(ShuraColors.accent)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.emptyState(
    title: CharSequence,
    hint: CharSequence,
    actionLabel: CharSequence? = null,
    onAction: (() -> Unit)? = null,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setPaddingRelative(dp(24), dp(56), dp(24), dp(56))
    addView(text(title, 18f, ShuraColors.onBackground, bold = true).apply { gravity = Gravity.CENTER })
    addView(text(hint, 13f, ShuraColors.textSecondary).apply {
        gravity = Gravity.CENTER
        setPaddingRelative(0, dp(8), 0, dp(16))
    })
    if (actionLabel != null && onAction != null) {
        addView(primaryButton(actionLabel, onAction))
    }
}

fun Context.chip(label: CharSequence): TextView = text(label, 11f, ShuraColors.textSecondary).apply {
    background = rounded(ShuraColors.surfaceVariant, 999)
    setPaddingRelative(dp(10), dp(4), dp(10), dp(4))
}

fun Context.spacer(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(ShuraColors.outline)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
}

/** A tappable navigation/choices row: a title, an optional subtitle and a trailing chevron. */
fun Context.settingRow(
    title: CharSequence,
    subtitle: CharSequence? = null,
    onClick: () -> Unit,
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPaddingRelative(dp(4), dp(14), dp(4), dp(14))
        setOnClickListener { onClick() }
    }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    column.addView(text(title, 15f, ShuraColors.onBackground))
    if (subtitle != null) {
        column.addView(
            text(subtitle, 12f, ShuraColors.textSecondary).apply { setPaddingRelative(0, dp(2), 0, 0) },
        )
    }
    row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(
        ImageView(this).apply {
            setImageResource(R.drawable.ic_forward)
            imageTintList = ColorStateList.valueOf(ShuraColors.textTertiary)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        },
    )
    return row
}

/** A cover with a caption underneath, sized for a horizontal shelf or a grid cell. */
fun Context.coverCell(
    widthDp: Int,
    title: CharSequence,
    subtitle: CharSequence? = null,
    onClick: () -> Unit,
): LinearLayout {
    val cell = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }
    val image = cover(widthDp, (widthDp * 1.4f).toInt())
    cell.addView(image)
    cell.addView(
        text(title, 13f, ShuraColors.onBackground, maxLines = 2).apply { setPaddingRelative(0, dp(6), 0, 0) },
    )
    if (subtitle != null) {
        cell.addView(
            text(subtitle, 11f, ShuraColors.textSecondary, maxLines = 1).apply {
                setPaddingRelative(0, dp(2), 0, 0)
            },
        )
    }
    return cell
}

/** A read-only settings row: a title and a trailing value, deliberately without a chevron. */
fun Context.valueRow(title: CharSequence, value: CharSequence): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPaddingRelative(dp(4), dp(14), dp(4), dp(14))
    addView(
        text(title, 15f, ShuraColors.onBackground),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    addView(text(value, 13f, ShuraColors.textSecondary, maxLines = 1))
}

/** A settings row with a switch. Tapping anywhere on the row flips it. */
fun Context.switchRow(
    title: CharSequence,
    subtitle: CharSequence? = null,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
): LinearLayout {
    val toggle = Switch(this).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChanged(value) }
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPaddingRelative(dp(4), dp(10), dp(4), dp(10))
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    column.addView(text(title, 15f, ShuraColors.onBackground))
    if (subtitle != null) {
        column.addView(
            text(subtitle, 12f, ShuraColors.textSecondary).apply { setPaddingRelative(0, dp(2), 0, 0) },
        )
    }
    row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(toggle)
    return row
}
