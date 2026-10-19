package com.thomas.bibisaya

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.MetricAffectingSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Les couleurs du style « Jeepney ». */
object C {
    const val YELLOW = 0xFFFFC928.toInt()
    const val RED = 0xFFD0261D.toInt()
    const val NAVY = 0xFF1B2A6B.toInt()
    const val CREAM = 0xFFFFF8E6.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    /** Les petits titres rouges. */
    const val LABEL = 0xFFB8231B.toInt()
    const val GREEN = 0xFF17663A.toInt()
    const val GREEN_BG = 0xFFDFF3E3.toInt()
    const val GREEN_TEXT = 0xFF145A33.toInt()
    const val GREEN_DEEP = 0xFF0F4527.toInt()
    const val WRONG_BG = 0xFFFDE3E1.toInt()
    const val WRONG_TEXT = 0xFF8F1D16.toInt()
    const val WRONG_DEEP = 0xFF5E130E.toInt()
    const val TRACK = 0xFFECE4CF.toInt()
    const val LOCKED = 0xFF8A8AA8.toInt()
    const val MUTED = 0xFF5A5F86.toInt()
    const val GRAY = 0xFF4A4F6E.toInt()
    const val DASH = 0xFFB9B2A0.toInt()
    const val DASH_LIGHT = 0xFFD9CFBD.toInt()
}

/** La police Bricolage Grotesque : une seule police, réglée en épaisseur et en taille d'œil. */
object Fonts {
    private const val NAME = "bricolage.ttf"
    private val cache = HashMap<Int, Typeface>()
    private var file: File? = null
    private val sizes = intArrayOf(12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 56)

    fun init(ctx: Context) {
        if (file != null) return
        // Copiée une fois sur le téléphone : Android la lit alors directement, sans la recharger à chaque fois.
        val f = File(ctx.filesDir, "bricolage-1.ttf")
        try {
            if (!f.exists() || f.length() == 0L) {
                val tmp = File(ctx.filesDir, "bricolage.tmp")
                ctx.assets.open(NAME).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(f)
            }
            file = f
        } catch (e: Exception) {
            file = null
        }
    }

    /** [weight] de 200 à 800 ; [size] la taille du texte, pour choisir le bon dessin des lettres. */
    fun get(weight: Int, size: Float): Typeface {
        val opsz = sizes.minByOrNull { abs(it - size) }!!
        val key = weight * 100 + opsz
        return cache.getOrPut(key) {
            val f = file
            try {
                if (f == null) null
                else Typeface.Builder(f).setFontVariationSettings("'wght' $weight, 'opsz' $opsz").build()
            } catch (e: Exception) {
                null
            } ?: Typeface.create(Typeface.SANS_SERIF, if (weight >= 700) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

/** « 1 240 » */
fun Number.fr(): String = NumberFormat.getIntegerInstance(Locale.FRANCE).format(this)

fun String.capitalized(): String = replaceFirstChar { it.uppercase() }

/** Les espaces insécables du français : jamais de « » », de « ? » ou de « : » seul au début d'une ligne. */
fun String.frenchSpaces(): String =
    replace("« ", "«\u00A0").replace(" »", "\u00A0»").replace(" ?", "\u00A0?").replace(" !", "\u00A0!")
        .replace(" :", "\u00A0:").replace(" ;", "\u00A0;")

fun Context.text(
    value: CharSequence,
    size: Float,
    weight: Int = 500,
    color: Int = C.NAVY,
    spacing: Float = 0f,
    line: Float = 1.15f,
): TextView = TextView(this).apply {
    text = if (value is String) value.frenchSpaces() else value
    textSize = size
    setTextColor(color)
    typeface = Fonts.get(weight, size)
    includeFontPadding = false
    letterSpacing = spacing
    setLineSpacing(0f, line)
}

/** Un petit titre en capitales, par exemple « MOT DU JOUR ». */
fun Context.label(value: String, size: Float = 13f, color: Int = C.LABEL, spacing: Float = 0.1f): TextView =
    text(value, size, 800, color, spacing)

fun Context.vbox(gap: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gap > 0) gaps(gap)
}

fun Context.hbox(gap: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gap > 0) gaps(gap)
}

/** Un espace entre les éléments d'une ligne ou d'une colonne. */
fun LinearLayout.gaps(gap: Int) {
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = object : Drawable() {
        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSPARENT
        override fun getIntrinsicWidth() = context.dp(gap)
        override fun getIntrinsicHeight() = context.dp(gap)
    }
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun View.pad(l: Int, t: Int, r: Int, b: Int) {
    val c = context
    setPadding(c.dp(l), c.dp(t), c.dp(r), c.dp(b))
}

/** Un fond arrondi avec un contour épais et une ombre décalée, comme sur les maquettes. */
class BoxDrawable(
    var fill: Int,
    var radius: Float,
    var stroke: Int = C.NAVY,
    var strokeWidth: Float = 0f,
    var shadow: Int = 0,
    var shadowOffset: Float = 0f,
    var dashed: Boolean = false,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val line = RectF()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        val b = bounds
        val off = if (shadow != 0) shadowOffset else 0f
        box.set(b.left.toFloat(), b.top.toFloat(), b.right - off, b.bottom - off)
        val r = min(radius, min(box.width(), box.height()) / 2f)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        if (off > 0f && !pressed) {
            paint.color = shadow
            canvas.save()
            canvas.translate(off, off)
            canvas.drawRoundRect(box, r, r, paint)
            canvas.restore()
        }
        paint.color = fill
        canvas.drawRoundRect(box, r, r, paint)
        if (strokeWidth > 0f) {
            val h = strokeWidth / 2f
            line.set(box.left + h, box.top + h, box.right - h, box.bottom - h)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = stroke
            if (dashed) paint.pathEffect = DashPathEffect(floatArrayOf(strokeWidth * 2.4f, strokeWidth * 1.6f), 0f)
            canvas.drawRoundRect(line, max(0f, r - h), max(0f, r - h), paint)
        }
    }

    override fun isStateful() = shadow != 0

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Donne à [view] un fond « Jeepney ». Avec une ombre, la vue garde la place de l'ombre à droite et en bas,
 * et s'enfonce dedans quand on appuie.
 */
fun View.box(
    fill: Int,
    radius: Number,
    stroke: Int = C.NAVY,
    strokeWidth: Number = 0,
    shadow: Int = 0,
    shadowOffset: Number = 0,
    dashed: Boolean = false,
) {
    val c = context
    val off = if (shadow != 0) c.dpf(shadowOffset) else 0f
    val old = background as? BoxDrawable
    val oldOff = if (old != null && old.shadow != 0) old.shadowOffset else 0f
    background = BoxDrawable(fill, c.dpf(radius), stroke, c.dpf(strokeWidth), shadow, off, dashed)
    if (off != oldOff) {
        val d = (off - oldOff).roundToInt()
        setPadding(paddingLeft, paddingTop, paddingRight + d, paddingBottom + d)
    }
    if (off > 0f) pressable(off) else stateListAnimator = null
}

/** Quand on appuie, la vue glisse dans son ombre. */
fun View.pressable(offset: Float) {
    val down = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, offset),
        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, offset),
    ).setDuration(50)
    val up = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f),
        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f),
    ).setDuration(90)
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), down)
        addState(intArrayOf(), up)
    }
}

/** Une vue sans ombre qui se tasse un peu quand on appuie. */
fun View.squishy() {
    val down = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f),
    ).setDuration(60)
    val up = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
    ).setDuration(90)
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), down)
        addState(intArrayOf(), up)
    }
}

fun View.onTap(action: () -> Unit) {
    isClickable = true
    isFocusable = true
    setOnClickListener { action() }
}

fun Context.icon(res: Int, color: Int): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(color, PorterDuff.Mode.SRC_IN)
    scaleType = ImageView.ScaleType.FIT_CENTER
}

fun ViewGroup.addIcon(res: Int, sizeDp: Int, color: Int): ImageView {
    val v = context.icon(res, color)
    addView(v, LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp)))
    return v
}

/** Gros bouton rouge, comme « C'EST PARTI » : contour bleu et ombre bleue. */
fun Context.primaryButton(label: String, height: Int = 60, size: Float = 19f, onClick: () -> Unit): TextView =
    text(label, size, 800, C.WHITE, 0.02f).apply {
        gravity = Gravity.CENTER
        minHeight = dp(height + 5)
        pad(16, 0, 16, 0)
        box(C.RED, 16, C.NAVY, 3, C.NAVY, 5)
        onTap(onClick)
    }

/** Bouton bleu plein, comme « CONTINUER » ou « RETOUR À L'ACCUEIL ». */
fun Context.navyButton(label: String, height: Int = 56, size: Float = 18f, onClick: () -> Unit): TextView =
    text(label, size, 800, C.WHITE, 0.02f).apply {
        gravity = Gravity.CENTER
        minHeight = dp(height)
        pad(16, 0, 16, 0)
        box(C.NAVY, 16)
        squishy()
        onTap(onClick)
    }

/** Bouton blanc à contour, comme « Encore une séance ». */
fun Context.outlineButton(label: String, color: Int = C.NAVY, height: Int = 50, size: Float = 16f, onClick: () -> Unit): TextView =
    text(label, size, 700, color).apply {
        gravity = Gravity.CENTER
        minHeight = dp(height)
        pad(16, 8, 16, 8)
        box(C.WHITE, 16, color, 3)
        squishy()
        onTap(onClick)
    }

/** Bouton carré blanc du haut de l'écran (réglages, retour, fermer). */
fun Context.squareButton(res: Int, description: String, shadow: Boolean, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        contentDescription = description
        val size = 44 + if (shadow) 3 else 0
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        if (shadow) box(C.WHITE, 12, C.NAVY, 3, C.NAVY, 3) else box(C.WHITE, 12, C.NAVY, 3)
        if (!shadow) squishy()
        val i = icon(res, C.NAVY)
        addView(i, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        onTap(onClick)
    }

/** Les bandes rouge, bleu, blanc du jeepney. */
class Stripes(ctx: Context, private val colors: IntArray = intArrayOf(C.RED, C.NAVY, C.WHITE, C.RED, C.NAVY, C.WHITE)) : View(ctx) {
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat() / colors.size
        colors.forEachIndexed { i, c ->
            paint.color = c
            canvas.drawRect(i * w, 0f, (i + 1) * w + 1f, height.toFloat(), paint)
        }
    }
}

fun Context.stripes(): Stripes = Stripes(this).apply { layoutParams = lp(MATCH, dp(12)) }

/** Une barre de progression arrondie, avec son contour. */
class Bar(ctx: Context, private val track: Int, private val fillColor: Int, private val border: Float) : View(ctx) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = h / 2f
        paint.style = Paint.Style.FILL
        paint.color = track
        r.set(0f, 0f, w, h)
        canvas.drawRoundRect(r, rad, rad, paint)
        if (fraction > 0f) {
            val inner = h - 2 * border
            val fw = max(inner, (w - 2 * border) * fraction)
            paint.color = fillColor
            r.set(border, border, border + fw, h - border)
            canvas.drawRoundRect(r, inner / 2f, inner / 2f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = border
        paint.color = C.NAVY
        r.set(border / 2f, border / 2f, w - border / 2f, h - border / 2f)
        canvas.drawRoundRect(r, rad - border / 2f, rad - border / 2f, paint)
    }
}

/** Les 5 petites barres du niveau d'un mot. */
class LevelBars(ctx: Context, var level: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(5 * 7 + 4 * 3), context.dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val w = context.dpf(7)
        val gap = context.dpf(3)
        val rad = context.dpf(2)
        for (i in 0 until 5) {
            paint.color = if (i < level) C.NAVY else C.DASH_LIGHT
            r.set(i * (w + gap), 0f, i * (w + gap) + w, height.toFloat())
            canvas.drawRoundRect(r, rad, rad, paint)
        }
    }
}

/** Un trait pointillé (horizontal), entre deux lignes d'une liste. */
class Dashes(ctx: Context, private val color: Int, private val thickness: Float) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(thickness * 2.5f, thickness * 2f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        paint.strokeWidth = thickness
        val y = height / 2f
        canvas.drawLine(0f, y, width.toFloat(), y, paint)
    }
}

fun Context.dashes(color: Int = C.DASH_LIGHT, thickness: Number = 2): Dashes =
    Dashes(this, color, dpf(thickness)).apply { layoutParams = lp(MATCH, dp(thickness)) }

/** L'interrupteur des réglages. */
class Toggle(ctx: Context, on: Boolean, private val changed: (Boolean) -> Unit) : View(ctx) {
    var on = on
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            this.on = !this.on
            invalidate()
            changed(this.on)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(56), context.dp(32))
    }

    override fun onDraw(canvas: Canvas) {
        val b = context.dpf(3)
        val h = height.toFloat()
        val w = width.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = if (on) C.RED else C.WHITE
        r.set(0f, 0f, w, h)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = b
        paint.color = C.NAVY
        r.set(b / 2, b / 2, w - b / 2, h - b / 2)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (on) C.WHITE else C.NAVY
        val knob = context.dpf(10)
        val cx = if (on) w - b - context.dpf(3) - knob else b + context.dpf(3) + knob
        canvas.drawCircle(cx, h / 2, knob, paint)
    }
}

/** Des éléments les uns à côté des autres, qui passent à la ligne quand il n'y a plus de place. */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int, private val centered: Boolean = false) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) {
                y += rowH + vGap
                x = 0
                rowH = 0
            }
            x += c.measuredWidth + hGap
            rowH = max(rowH, c.measuredHeight)
            widest = max(widest, x - hGap)
        }
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec)
        else widest + paddingLeft + paddingRight
        val h = y + rowH + paddingTop + paddingBottom
        setMeasuredDimension(w, resolveSize(max(h, suggestedMinimumHeight), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val rows = mutableListOf<MutableList<View>>(mutableListOf())
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                rows.add(mutableListOf())
                x = 0
            }
            rows.last().add(c)
            x += c.measuredWidth + hGap
        }
        var y = paddingTop
        for (row in rows) {
            if (row.isEmpty()) continue
            val rowW = row.sumOf { it.measuredWidth } + hGap * (row.size - 1)
            var cx = paddingLeft + if (centered) (maxW - rowW) / 2 else 0
            val rowH = row.maxOf { it.measuredHeight }
            for (c in row) {
                val top = y + (rowH - c.measuredHeight) / 2
                c.layout(cx, top, cx + c.measuredWidth, top + c.measuredHeight)
                cx += c.measuredWidth + hGap
            }
            y += rowH + vGap
        }
    }
}

/** L'appli dessine sous les barres du téléphone : [top] prend la hauteur de la barre d'état, [bottom] celle des boutons. */
fun Activity.edgeToEdge(root: View, top: View, bottom: View, lightNavigation: Boolean) {
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val wanted = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            (if (lightNavigation) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)
        window.insetsController?.setSystemBarsAppearance(wanted, light)
    } else {
        @Suppress("DEPRECATION")
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        @Suppress("DEPRECATION")
        if (lightNavigation && Build.VERSION.SDK_INT >= 27) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }
    val bottomBase = bottom.paddingBottom
    root.setOnApplyWindowInsetsListener { _, insets ->
        val t: Int
        val b: Int
        val l: Int
        val r: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            t = bars.top
            b = max(bars.bottom, ime.bottom)
            l = bars.left
            r = bars.right
        } else {
            @Suppress("DEPRECATION")
            t = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            b = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            l = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            r = insets.systemWindowInsetRight
        }
        top.layoutParams = top.layoutParams.apply { height = t }
        top.requestLayout()
        bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bottomBase + b)
        root.setPadding(l, 0, r, 0)
        insets
    }
    root.requestApplyInsets()
}

/** Une fenêtre de dialogue au style de l'appli : une carte blanche, un titre, un texte et deux boutons. */
fun Activity.confirm(
    title: String,
    message: String,
    yes: String,
    no: String,
    danger: Boolean = false,
    onNo: () -> Unit = {},
    onYes: () -> Unit,
): Dialog {
    // Toucher à côté ferme la fenêtre sans rien faire.
    val d = Dialog(this, R.style.Theme_BiBisaya_Dialog)
    val card = vbox(14).apply {
        pad(22, 22, 22, 22)
        box(C.WHITE, 22, C.NAVY, 3, C.NAVY, 6)
    }
    card.addView(text(title, 22f, 800, line = 1.05f))
    card.addView(text(message, 16f, 500, line = 1.3f))
    val buttons = vbox(10)
    val yesButton = if (danger) outlineButton(yes, C.LABEL, 52, 17f) { d.dismiss(); onYes() }
    else primaryButton(yes, 52, 17f) { d.dismiss(); onYes() }
    buttons.addView(yesButton, lp())
    buttons.addView(text(no, 16f, 700).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        onTap { d.dismiss(); onNo() }
    }, lp())
    card.addView(buttons, lp())
    val frame = FrameLayout(this)
    frame.addView(card, FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(20), dp(20), dp(20), dp(20)) })
    d.setContentView(frame)
    d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    d.show()
    return d
}

/** Une fenêtre d'information, avec un seul bouton pour la fermer. */
fun Activity.sheet(build: LinearLayout.(Dialog) -> Unit): Dialog {
    val d = Dialog(this, R.style.Theme_BiBisaya_Dialog)
    val card = vbox(12).apply {
        pad(22, 22, 22, 22)
        box(C.WHITE, 22, C.NAVY, 3, C.NAVY, 6)
    }
    card.build(d)
    card.addView(navyButton("FERMER", 50, 16f) { d.dismiss() }, lp().apply { topMargin = dp(6) })
    val frame = FrameLayout(this)
    frame.addView(card, FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(20), dp(20), dp(20), dp(20)) })
    d.setContentView(frame)
    d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    d.show()
    return d
}

/** La petite bulle qui donne le sens d'un mot touché dans une phrase. */
object Gloss {
    private var popup: PopupWindow? = null

    fun show(anchor: View, word: String, meaning: String?) {
        popup?.dismiss()
        val c = anchor.context
        val bubble = c.vbox(2).apply {
            pad(14, 10, 14, 12)
            box(C.NAVY, 14, shadow = C.RED, shadowOffset = 3)
            stateListAnimator = null
        }
        bubble.addView(c.text(word, 17f, 800, C.YELLOW))
        bubble.addView(c.text(meaning ?: "pas de traduction pour ce petit mot", 15f, 600, C.WHITE))
        // Pas plus large que l'écran : un long sens passe à la ligne.
        val maxWidth = c.resources.displayMetrics.widthPixels - c.dp(40)
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val p = PopupWindow(bubble, bubble.measuredWidth, WRAP, false)
        p.isOutsideTouchable = true
        p.isTouchable = true
        p.elevation = 0f
        val xOff = (anchor.width - bubble.measuredWidth) / 2
        p.showAsDropDown(anchor, xOff, -anchor.height - bubble.measuredHeight - c.dp(6))
        popup = p
        anchor.postDelayed({ if (popup === p) p.dismiss() }, 2600)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}

/** Une phrase dont chaque mot se touche pour voir sa traduction. */
fun Context.tappableSentence(
    sentence: String,
    size: Float,
    weight: Int,
    color: Int,
    meaning: (String) -> String?,
    centered: Boolean = false,
    underline: Int = 0,
): FlowLayout {
    val flow = FlowLayout(this, dp(size * 0.28f), dp(2), centered)
    for (word in sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        val t = text(word, size, weight, color)
        t.pad(0, 2, 0, 3)
        if (underline != 0) t.background = DottedUnderline(underline, dpf(2))
        t.onTap {
            val clean = com.thomas.bibisaya.engine.Content.cleanToken(word)
            Gloss.show(t, clean, meaning(clean))
        }
        flow.addView(t)
    }
    return flow
}

/** Un soulignement en pointillés, pour montrer qu'un mot se touche. */
class DottedUnderline(private val color: Int, private val thickness: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thickness
        pathEffect = DashPathEffect(floatArrayOf(thickness * 1.2f, thickness * 1.6f), 0f)
    }

    override fun draw(canvas: Canvas) {
        paint.color = color
        val b: Rect = bounds
        val y = b.bottom - thickness / 2
        canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Un texte où les passages entre ** sont en gras : « La bonne réponse est **bata**. » */
fun Context.rich(value: String, size: Float, weight: Int = 500, color: Int = C.NAVY, line: Float = 1.35f): TextView {
    val sb = SpannableStringBuilder()
    value.frenchSpaces().split("**").forEachIndexed { i, part ->
        val start = sb.length
        sb.append(part)
        if (i % 2 == 1) sb.setSpan(FontSpan(Fonts.get(800, size)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return text(sb, size, weight, color, line = line)
}

/** Change la police d'un passage (marche aussi sur les anciens Android). */
class FontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = typeface
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = typeface
    }
}

/** Appelé à chaque lettre tapée. */
fun EditText.onChange(block: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            block(s?.toString() ?: "")
        }
    })
}

/** Petite vibration qui accompagne une réponse. */
object Buzz {
    private fun vibrator(ctx: Context): Vibrator? = ctx.getSystemService(Vibrator::class.java)

    fun right(ctx: Context) = once(ctx, 30)
    fun wrong(ctx: Context) = pattern(ctx, longArrayOf(0, 40, 70, 40))
    fun win(ctx: Context) = pattern(ctx, longArrayOf(0, 40, 80, 40, 80, 90))

    private fun once(ctx: Context, ms: Long) = try {
        vibrator(ctx)?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) {
    }

    private fun pattern(ctx: Context, p: LongArray) = try {
        vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(p, -1))
    } catch (e: Exception) {
    }
}

/** Les dates en français. */
object Fr {
    private val locale = Locale.FRANCE

    /** « 12 octobre » */
    fun day(epochDay: Long): String {
        val d = LocalDate.ofEpochDay(epochDay)
        return "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, locale)}"
    }

    /** « 1 jour », « 12 jours » */
    fun count(n: Int, one: String, many: String) = "${n.fr()} ${if (n <= 1) one else many}"
}

/** Petit balancement de gauche à droite, quand une réponse est fausse. */
fun View.shake() {
    ObjectAnimator.ofFloat(this, View.TRANSLATION_X, 0f, -14f, 12f, -8f, 5f, 0f).apply {
        duration = 360
        start()
    }
}

/** Petit saut, quand une réponse est juste. */
fun View.pop() {
    ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f, 1f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f, 1f),
    ).apply {
        duration = 280
        start()
    }
}

/** Le panneau du bas qui monte doucement. */
fun View.slideUp() {
    translationY = context.dpf(80)
    alpha = 0f
    animate().translationY(0f).alpha(1f).setDuration(220).start()
}
