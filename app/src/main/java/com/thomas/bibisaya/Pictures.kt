package com.thomas.bibisaya

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import com.thomas.bibisaya.engine.Demonstratifs
import com.thomas.bibisaya.engine.Distance
import com.thomas.bibisaya.engine.Moment
import com.thomas.bibisaya.engine.Row
import com.thomas.bibisaya.engine.Theme

/** Les pinceaux des petits dessins des rondes : une personne, une forme en couleur ou en pointillés, une étiquette. */
class Drawing(private val ctx: Context) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = Path()
    val rect = RectF()
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dash = DashPathEffect(floatArrayOf(ctx.dpf(3.5f), ctx.dpf(2.5f)), 0f)

    /** Une personne : une tête ronde et des épaules, en couleur ou en pointillés. */
    fun figure(canvas: Canvas, cx: Float, top: Float, h: Float, color: Int, filled: Boolean) {
        val r = h * 0.2f
        val bw = r * 2.7f
        rect.set(cx - bw / 2, top + 2 * r + h * 0.06f, cx + bw / 2, top + h)
        val round = bw / 2
        val foot = ctx.dpf(3)
        path.reset()
        path.addRoundRect(rect, floatArrayOf(round, round, round, round, foot, foot, foot, foot), Path.Direction.CW)
        path.addCircle(cx, top + r, r, Path.Direction.CW)
        shape(canvas, color, filled)
    }

    /** Dessine [path] : en couleur avec un contour bleu, ou en pointillés (ce n'est pas celui-là). */
    fun shape(canvas: Canvas, color: Int, filled: Boolean) {
        paint.strokeWidth = ctx.dpf(2)
        if (filled) {
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.color = C.NAVY
            canvas.drawPath(path, paint)
        } else {
            paint.pathEffect = dash
            paint.style = Paint.Style.STROKE
            paint.color = C.DASH
            canvas.drawPath(path, paint)
        }
    }

    /** Un trait plein (le temps qui passe, un signe dans un rond). */
    fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        paint.pathEffect = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = width
        paint.color = color
        canvas.drawLine(x1, y1, x2, y2, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    /** Une étiquette sous le dessin, en gras quand c'est la bonne ; elle ne dépasse jamais du bord. */
    fun label(canvas: Canvas, value: String, x: Float, y: Float, strong: Boolean, maxX: Float) {
        words.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, ctx.resources.displayMetrics)
        words.typeface = Fonts.get(if (strong) 800 else 600, 11f)
        words.color = if (strong) C.NAVY else C.MUTED
        val half = words.measureText(value) / 2
        canvas.drawText(value, if (maxX > 2 * half) x.coerceIn(half, maxX - half) else maxX / 2, y, words)
    }
}

/** Les dessins sont réglés pour 150 × 48 dp (plus 16 dp d'étiquettes), puis mis à l'échelle. */
private const val DESIGN_W = 150f
private const val DESIGN_H = 48f
private const val LABELS_H = 16f

/** La place du dessin : l'échelle, et où il commence (centré dans la vue). */
private class Frame(view: View, labels: Boolean) {
    val k: Float
    val left: Float
    val top: Float

    init {
        val ctx = view.context
        val labelH = if (labels) ctx.dpf(LABELS_H) else 0f
        k = minOf(view.width / ctx.dpf(DESIGN_W), (view.height - labelH) / ctx.dpf(DESIGN_H))
        left = (view.width - ctx.dpf(DESIGN_W) * k) / 2
        top = (view.height - labelH - ctx.dpf(DESIGN_H) * k) / 2
    }

    fun x(ctx: Context, v: Float) = left + ctx.dpf(v) * k
    fun y(ctx: Context, v: Float) = top + ctx.dpf(v) * k
    fun size(ctx: Context, v: Float) = ctx.dpf(v) * k
}

/**
 * Le dessin des distances (leçons 3, 8 et 11) : moi (rouge), toi (bleu), et trois places : près de moi, près de toi,
 * et là-bas (plus petite, plus loin). La place de la question est en jaune, les autres en pointillés.
 * [place] : un endroit (une épingle, leçons 8 et 11) plutôt qu'une chose (une boîte, leçon 3).
 * Sans [distance], les trois places sont en jaune (la petite image de la leçon).
 */
class DistanceView(ctx: Context, private val distance: Distance?, private val place: Boolean, private val labels: Boolean) : View(ctx) {
    private val draw = Drawing(ctx)

    init {
        if (distance != null) contentDescription = when (distance) {
            Distance.HERE -> if (place) "ici, près de moi" else "près de moi"
            Distance.THERE -> "près de toi"
            Distance.FAR -> "là-bas"
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(if (labels) PeopleView.WIDTH else 76), context.dp(if (labels) PeopleView.HEIGHT else 36))
    }

    override fun onDraw(canvas: Canvas) {
        val c = context
        val f = Frame(this, labels)
        val ground = f.y(c, 46f)
        draw.figure(canvas, f.x(c, ME), f.y(c, 2f), f.size(c, 44f), C.RED, true)
        draw.figure(canvas, f.x(c, YOU), f.y(c, 2f), f.size(c, 44f), C.NAVY, true)
        // Des petits points jusqu'à là-bas : c'est loin.
        for (x in listOf(115f, 121f, 127f)) {
            draw.paint.pathEffect = null
            draw.paint.style = Paint.Style.FILL
            draw.paint.color = C.DASH
            canvas.drawCircle(f.x(c, x), f.y(c, 44.5f), f.size(c, 1.4f), draw.paint)
        }
        Distance.values().forEach { d ->
            val size = f.size(c, if (d == Distance.FAR) 13f else 18f)
            val on = distance == null || distance == d
            if (place) pin(canvas, f.x(c, SPOTS[d.ordinal]), ground, size, on) else box(canvas, f.x(c, SPOTS[d.ordinal]), ground, size, on)
        }
        if (!labels) return
        val y = height - c.dpf(3)
        val w = width.toFloat()
        draw.label(canvas, "moi", f.x(c, ME), y, distance == null || distance == Distance.HERE, w)
        draw.label(canvas, "toi", f.x(c, YOU), y, distance == null || distance == Distance.THERE, w)
        draw.label(canvas, "là-bas", f.x(c, SPOTS[2]), y, distance == null || distance == Distance.FAR, w)
    }

    /** Une chose (leçon 3) : une boîte avec son couvercle. */
    private fun box(canvas: Canvas, cx: Float, bottom: Float, s: Float, on: Boolean) {
        draw.rect.set(cx - s / 2, bottom - s, cx + s / 2, bottom)
        val r = s * 0.18f
        draw.path.reset()
        draw.path.addRoundRect(draw.rect, r, r, Path.Direction.CW)
        draw.shape(canvas, C.YELLOW, on)
        if (on) draw.line(canvas, cx - s / 2, bottom - s * 0.62f, cx + s / 2, bottom - s * 0.62f, C.NAVY, context.dpf(2))
    }

    /** Un endroit (leçons 8 et 11) : une épingle de carte. */
    private fun pin(canvas: Canvas, cx: Float, bottom: Float, s: Float, on: Boolean) {
        val r = s * 0.36f
        val cy = bottom - s + r
        draw.path.reset()
        draw.path.moveTo(cx, bottom)
        draw.rect.set(cx - r, cy - r, cx + r, cy + r)
        draw.path.arcTo(draw.rect, 150f, 240f)
        draw.path.close()
        draw.shape(canvas, C.YELLOW, on)
        if (!on) return
        draw.paint.style = Paint.Style.FILL
        draw.paint.color = C.NAVY
        canvas.drawCircle(cx, cy, r * 0.38f, draw.paint)
    }

    private companion object {
        const val ME = 36f
        const val YOU = 76f
        /** Près de moi, près de toi, là-bas. */
        val SPOTS = floatArrayOf(12f, 100f, 140f)
    }
}

/**
 * Le dessin du temps (leçons 6, 7 et 11) : une flèche, d'hier à demain. Hier (c'est fait) : une coche ;
 * au milieu, un ordre : « ! » ; demain (ça va se faire) : une flèche. Le moment de la question est en couleur.
 * Sans [moment], les trois sont en couleur (la petite image de la leçon).
 */
class TimeView(ctx: Context, private val moment: Moment?, private val labels: Boolean) : View(ctx) {
    private val draw = Drawing(ctx)

    init {
        if (moment != null) contentDescription = when (moment) {
            Moment.PAST -> "hier : c'est fait"
            Moment.ORDER -> "un ordre"
            Moment.FUTURE -> "demain : ça va se faire"
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(if (labels) PeopleView.WIDTH else 76), context.dp(if (labels) PeopleView.HEIGHT else 36))
    }

    override fun onDraw(canvas: Canvas) {
        val c = context
        val f = Frame(this, labels)
        val cy = f.y(c, 24f)
        val r = f.size(c, 13f)
        val stroke = f.size(c, 2.5f).coerceAtLeast(c.dpf(1.5f))
        // La flèche du temps, entre les ronds.
        for ((a, b) in listOf(39f to 55f, 89f to 105f, 139f to 148f)) draw.line(canvas, f.x(c, a), cy, f.x(c, b), cy, C.NAVY, stroke)
        draw.line(canvas, f.x(c, 148f), cy, f.x(c, 143f), cy - f.size(c, 5f), C.NAVY, stroke)
        draw.line(canvas, f.x(c, 148f), cy, f.x(c, 143f), cy + f.size(c, 5f), C.NAVY, stroke)
        for ((m, x) in listOf(Moment.PAST to 22f, Moment.ORDER to 72f, Moment.FUTURE to 122f)) {
            val on = moment == null || moment == m
            val cx = f.x(c, x)
            draw.path.reset()
            draw.path.addCircle(cx, cy, r, Path.Direction.CW)
            val fill = when (m) {
                Moment.PAST -> C.NAVY
                Moment.ORDER -> C.RED
                Moment.FUTURE -> C.YELLOW
            }
            draw.shape(canvas, fill, on)
            val ink = when {
                !on -> C.DASH
                m == Moment.FUTURE -> C.NAVY
                else -> C.WHITE
            }
            val w = f.size(c, 2.6f).coerceAtLeast(c.dpf(1.5f))
            when (m) {
                Moment.PAST -> {
                    draw.line(canvas, cx - r * 0.42f, cy + r * 0.02f, cx - r * 0.1f, cy + r * 0.34f, ink, w)
                    draw.line(canvas, cx - r * 0.1f, cy + r * 0.34f, cx + r * 0.45f, cy - r * 0.32f, ink, w)
                }
                Moment.ORDER -> {
                    draw.line(canvas, cx, cy - r * 0.5f, cx, cy + r * 0.12f, ink, w)
                    draw.paint.style = Paint.Style.FILL
                    draw.paint.color = ink
                    canvas.drawCircle(cx, cy + r * 0.45f, w * 0.65f, draw.paint)
                }
                Moment.FUTURE -> {
                    draw.line(canvas, cx - r * 0.45f, cy, cx + r * 0.45f, cy, ink, w)
                    draw.line(canvas, cx + r * 0.45f, cy, cx + r * 0.1f, cy - r * 0.35f, ink, w)
                    draw.line(canvas, cx + r * 0.45f, cy, cx + r * 0.1f, cy + r * 0.35f, ink, w)
                }
            }
        }
        if (!labels) return
        val y = height - c.dpf(3)
        val w = width.toFloat()
        draw.label(canvas, "hier", f.x(c, 22f), y, moment == null || moment == Moment.PAST, w)
        draw.label(canvas, "un ordre", f.x(c, 72f), y, moment == null || moment == Moment.ORDER, w)
        draw.label(canvas, "demain", f.x(c, 122f), y, moment == null || moment == Moment.FUTURE, w)
    }
}

/** Le dessin d'une ligne de la ronde (une personne, une distance, un moment), ou null s'il n'y en a pas. */
fun Context.rowPicture(theme: Theme, row: Row, labels: Boolean): View? = when {
    row.person != null -> PeopleView(this, row.person, labels)
    row.distance != null -> DistanceView(this, row.distance, place = theme !== Demonstratifs, labels = labels)
    row.moment != null -> TimeView(this, row.moment, labels)
    else -> null
}

/** La petite image d'une ronde, sur la carte de la leçon : le dessin de la leçon, ou deux de ses petits mots. */
fun Context.rondeIcon(theme: Theme): View {
    val first = theme.rows.first()
    return when {
        first.person != null -> PeopleView(this, null, labels = false)
        first.distance != null -> DistanceView(this, null, place = theme !== Demonstratifs, labels = false)
        first.moment != null -> TimeView(this, null, labels = false)
        else -> {
            val row = hbox(5)
            for (r in theme.rows.take(2)) {
                row.addView(text(theme.helpForm(r).substringBefore(" · "), 15f, 800).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(30)
                    pad(7, 3, 7, 4)
                    box(C.WHITE, 9, C.NAVY, 2)
                })
            }
            row
        }
    }
}
