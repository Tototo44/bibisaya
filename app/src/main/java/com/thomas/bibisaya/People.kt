package com.thomas.bibisaya

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.thomas.bibisaya.engine.Person

/**
 * Le petit dessin de la ronde : moi (rouge), toi (bleu) et les autres (jaune). Ceux qui sont dans la personne
 * sont en couleur, les autres en pointillés : on voit tout de suite si « nous » est avec toi (kita) ou sans toi (kami).
 * Sans [person], tout le monde est en couleur (la petite image de la leçon).
 */
class PeopleView(ctx: Context, private val person: Person?, private val labels: Boolean) : View(ctx) {
    private val draw = Drawing(ctx)

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
        draw.figure(canvas, context.dpf(24) * k, top, h, C.RED, me)
        draw.figure(canvas, context.dpf(66) * k, top, h, C.NAVY, you)
        if (others == 1) {
            draw.figure(canvas, context.dpf(116) * k, top, h, C.YELLOW, true)
        } else {
            // Deux autres (ils, elles), ou personne d'autre : les deux places restent en pointillés.
            draw.figure(canvas, context.dpf(103) * k, top, h, C.YELLOW, others > 0)
            draw.figure(canvas, context.dpf(129) * k, top, h, C.YELLOW, others > 0)
        }
        if (!labels) return
        val y = height - context.dpf(3)
        val w = width.toFloat()
        draw.label(canvas, "moi", context.dpf(24) * k, y, me, w)
        draw.label(canvas, "toi", context.dpf(66) * k, y, you, w)
        val them = when {
            person?.others == 1 && !me && !you -> "lui, elle"
            person?.others == 2 -> "eux, elles"
            else -> "les autres"
        }
        draw.label(canvas, them, context.dpf(116) * k, y, others > 0, w)
    }

    companion object {
        const val WIDTH = 150
        const val HEIGHT = 64
    }
}
