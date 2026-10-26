package com.thomas.bibisaya

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.thomas.bibisaya.engine.Content
import com.thomas.bibisaya.engine.Sheet
import com.thomas.bibisaya.engine.SheetTable
import com.thomas.bibisaya.engine.Stop

/** La largeur d'un texte, pour savoir s'il tient dans une colonne (ou sur un bouton). */
fun Context.textWidth(value: String, size: Float, weight: Int): Int {
    val paint = Paint().apply {
        typeface = Fonts.get(weight, size)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
    }
    return paint.measureText(value).toInt() + dp(4)
}

/** Une petite leçon : son titre, son explication, le tableau des formes et les exemples. */
fun Context.addLesson(into: LinearLayout, stop: Stop, content: Content) {
    val lesson = stop.lesson ?: return
    val top = vbox(6)
    top.addView(text(stop.title, 32f, 800, line = 1.0f))
    top.addView(text(lesson.intro, 16f, 500, line = 1.35f))
    into.addView(top, lp())
    if (lesson.rules.isNotEmpty()) {
        val table = vbox().apply {
            pad(3, 3, 3, 3)
            box(C.WHITE, 18, C.NAVY, 3)
        }
        // Les formes à gauche si la plus longue tient dans une colonne, sinon chacune au-dessus de son sens.
        val widest = lesson.rules.maxOf { textWidth(it.form, 22f, 800) }
        val rowWidth = resources.displayMetrics.widthPixels - dp(20 + 20 + 6 + 28)
        val side = widest <= rowWidth * 0.42f
        val column = maxOf(dp(78), widest)
        lesson.rules.forEachIndexed { i, rule ->
            if (i > 0) table.addView(dashes(C.DASH, 2))
            val row = if (side) hbox(14) else vbox(4)
            row.pad(14, 12, 14, 12)
            val form = text(rule.form, 22f, 800, C.LABEL, line = 1.1f)
            val words = vbox(1)
            words.addView(text(rule.meaning, 15f, 700, line = 1.25f))
            words.addView(text(rule.example, 15f, 500, line = 1.25f))
            if (side) {
                row.addView(form, LinearLayout.LayoutParams(column, WRAP))
                row.addView(words, lp(0, WRAP, 1f))
            } else {
                row.addView(form, lp())
                row.addView(words, lp())
            }
            table.addView(row, lp())
        }
        into.addView(table, lp())
    }
    if (lesson.examples.isNotEmpty()) {
        val box = vbox(10).apply {
            pad(16, 14, 16, 14)
            box(C.NAVY, 18)
        }
        box.addView(label("EXEMPLES", 12f, C.YELLOW))
        for (ex in lesson.examples) {
            val one = vbox(1)
            one.addView(tappableSentence(ex.ceb, 17f, 800, C.WHITE, { content.gloss(it) }, underline = 0x66FFFFFF), lp())
            one.addView(text(ex.fr, 15f, 500, C.WHITE, line = 1.25f))
            box.addView(one, lp())
        }
        box.addView(text("Touche un mot pour voir sa traduction.", 13f, 600, C.YELLOW))
        into.addView(box, lp())
    }
}

/** Une fiche : son introduction, ses mots, ses tableaux et ses astuces. */
fun Context.addSheet(into: LinearLayout, sheet: Sheet) {
    sheet.intro?.let { into.addView(text(it, 16f, 500, line = 1.35f)) }
    if (sheet.words.isNotEmpty()) {
        val card = vbox().apply {
            pad(3, 3, 3, 3)
            box(C.WHITE, 18, C.NAVY, 3)
        }
        val column = maxOf(dp(72), sheet.words.maxOf { textWidth(it.word, 20f, 800) })
        sheet.words.forEachIndexed { i, w ->
            if (i > 0) card.addView(dashes())
            val row = hbox(12).apply {
                gravity = Gravity.TOP
                pad(14, 9, 14, 9)
            }
            row.addView(text(w.word, 20f, 800, C.LABEL), LinearLayout.LayoutParams(column, WRAP))
            val words = vbox(1)
            words.addView(text(w.meaning, 15f, 800, line = 1.2f))
            w.example?.let { ex ->
                words.addView(text(ex.ceb, 15f, 700, line = 1.2f))
                words.addView(text(ex.fr, 14f, 500, line = 1.2f))
            }
            row.addView(words, lp(0, WRAP, 1f))
            card.addView(row, lp())
        }
        into.addView(card, lp())
    }
    for (table in sheet.tables) addTable(into, table)
    if (sheet.tips.isNotEmpty()) {
        val box = vbox(10).apply {
            pad(16, 14, 16, 14)
            box(C.NAVY, 18)
        }
        box.addView(label("ASTUCES", 12f, C.YELLOW))
        for (tip in sheet.tips) {
            val one = vbox(2)
            one.addView(rich(tip.text, 15f, 500, C.WHITE))
            tip.example?.let { one.addView(text(it, 15f, 700, C.YELLOW)) }
            box.addView(one, lp())
        }
        into.addView(box, lp())
    }
}

/** Un tableau de fiche : une ligne de titres bleue, puis une ligne par personne ou par mot. */
private fun Context.addTable(into: LinearLayout, table: SheetTable) {
    into.addView(label(table.title, 13f).apply { pad(0, 6, 0, 0) })
    val card = vbox().apply {
        pad(3, 3, 3, 3)
        box(C.WHITE, 18, C.NAVY, 3)
    }
    // La 1re colonne (le sens) est plus étroite quand il y a 4 colonnes de mots.
    val first = dp(if (table.columns.size >= 4) 70 else 92)
    // La ligne des titres suit l'arrondi de la carte, en haut seulement.
    val r = dpf(15)
    val head = hbox(4).apply {
        pad(10, 8, 10, 8)
        background = GradientDrawable().apply {
            setColor(C.NAVY)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }
    head.addView(android.view.View(this), LinearLayout.LayoutParams(first, 1))
    // Un titre trop long (« APRÈS UN VERBE ») passe à la ligne plutôt que d'être coupé.
    for (c in table.columns) head.addView(text(c, 12f, 800, C.YELLOW, 0.06f, line = 1.1f), lp(0, WRAP, 1f))
    card.addView(head, lp())
    table.rows.forEachIndexed { i, row ->
        if (i > 0) card.addView(dashes())
        val line = hbox(4).apply { pad(10, 7, 10, 7) }
        line.addView(text(row.label, 13f, 700, line = 1.15f), LinearLayout.LayoutParams(first, WRAP))
        row.cells.forEachIndexed { k, value ->
            val forms = value.split(" · ")
            val col = vbox()
            col.addView(cell(forms[0], 15f, 800, if (k == 0) C.LABEL else C.NAVY), lp())
            // La forme pour plusieurs peut passer à la ligne (« kang … ug kang »).
            if (forms.size > 1) col.addView(text(forms[1], 12f, 600, C.NAVY, line = 1.1f), lp())
            line.addView(col, lp(0, WRAP, 1f))
        }
        card.addView(line, lp())
    }
    into.addView(card, lp())
    table.note?.let { into.addView(rich(it, 14f, 500, line = 1.4f)) }
    if (table.examples.isNotEmpty()) {
        val list = vbox(4)
        for (ex in table.examples) list.addView(rich("**${ex.ceb}** = ${ex.fr}", 14f, 500, line = 1.35f))
        into.addView(list, lp())
    }
}

/** Une case : sur une seule ligne, quitte à rapetisser un peu un mot trop long. */
private fun Context.cell(value: String, size: Float, weight: Int, color: Int): TextView =
    text(value, size, weight, color).apply {
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration((size * 0.7f).toInt().coerceAtLeast(9), size.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
    }
