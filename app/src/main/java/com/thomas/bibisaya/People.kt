package com.thomas.bibisaya

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.thomas.bibisaya.engine.Person

/**
 * Le petit dessin de la ronde : moi (rouge), toi (bleu) et les autres (jaune). Ceux qui sont dans la personne
 * sont en couleur, les autres en pointillés : on voit tout de suite si « nous » est avec toi (kita) ou sans toi (kami).
 * Sans [person], tout le monde est en couleur (la petite image de la leçon).
 */
class PeopleView(ctx: Context, private val person: Person?, private val labels: Boolean) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()
    private val dash = DashPathEffect(floatArrayOf(ctx.dpf(3.5f), ctx.dpf(2.5f)), 0f)

    init {
        if (person != null) contentDescription = when {
            person.me && person.you -> "moi et toi"
            person.me && person.others > 0 -> "moi et les autres, sans toi"
            person.you && person.others > 0 -> "toi et les autres"
            person.me -> "moi"
            person.you -> "toi"
            person.others > 1 -> "les autres"
            else -> "lui ou elle"
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(if (labels) WIDTH else 76), context.dp(if (labels) HEIGHT else 36))
    }

    override fun onDraw(canvas: Canvas) {
        // Les places sont réglées pour 150 × 64 dp, puis mises à l'échelle.
        val k = width / context.dpf(WIDTH)
        val labelH = if (labels) context.dpf(16) else 0f
        val top = context.dpf(2)
        val h = height - labelH - top
        val me = person?.me ?: true
        val you = person?.you ?: true
        val others = person?.others ?: 2
        figure(canvas, context.dpf(24) * k, top, h, C.RED, me)
        figure(canvas, context.dpf(66) * k, top, h, C.NAVY, you)
        if (others == 1) {
            figure(canvas, context.dpf(116) * k, top, h, C.YELLOW, true)
        } else {
            // Deux autres (ils, elles), ou personne d'autre : les deux places restent en pointillés.
            figure(canvas, context.dpf(103) * k, top, h, C.YELLOW, others > 0)
            figure(canvas, context.dpf(129) * k, top, h, C.YELLOW, others > 0)
        }
        if (!labels) return
        val y = height - context.dpf(3)
        label(canvas, "moi", context.dpf(24) * k, y, me)
        label(canvas, "toi", context.dpf(66) * k, y, you)
        val them = when {
            person?.others == 1 && !me && !you -> "lui, elle"
            person?.others == 2 -> "eux, elles"
            else -> "les autres"
        }
        label(canvas, them, context.dpf(116) * k, y, others > 0)
    }

    /** Une personne : une tête ronde et des épaules, en couleur ou en pointillés. */
    private fun figure(canvas: Canvas, cx: Float, top: Float, h: Float, color: Int, filled: Boolean) {
        val r = h * 0.2f
        val bw = r * 2.7f
        rect.set(cx - bw / 2, top + 2 * r + h * 0.06f, cx + bw / 2, top + h)
        val round = bw / 2
        val foot = context.dpf(3)
        path.reset()
        path.addRoundRect(rect, floatArrayOf(round, round, round, round, foot, foot, foot, foot), Path.Direction.CW)
        path.addCircle(cx, top + r, r, Path.Direction.CW)
        paint.strokeWidth = context.dpf(2)
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

    private fun label(canvas: Canvas, value: String, x: Float, y: Float, inside: Boolean) {
        words.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        words.typeface = Fonts.get(if (inside) 800 else 600, 11f)
        words.color = if (inside) C.NAVY else C.MUTED
        canvas.drawText(value, x, y, words)
    }

    companion object {
        const val WIDTH = 150
        const val HEIGHT = 64
    }
}
