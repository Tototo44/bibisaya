package com.thomas.bibisaya

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.thomas.bibisaya.engine.Content
import com.thomas.bibisaya.engine.Fabrique
import com.thomas.bibisaya.engine.Item
import com.thomas.bibisaya.engine.Line
import com.thomas.bibisaya.engine.Planner
import com.thomas.bibisaya.engine.Progress
import com.thomas.bibisaya.engine.Sheet
import com.thomas.bibisaya.engine.Stop
import com.thomas.bibisaya.engine.Theme

/**
 * Une leçon de grammaire ou une fiche, à lire quand on veut (depuis l'onglet Leçons ou la route).
 * Un arrêt sans leçon (des mots ou des phrases simples) montre ses mots, et de quoi les découvrir
 * avant que le jeepney y arrive.
 */
class LessonActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: Content
    private var stop: Stop? = null
    private var sheet: Sheet? = null
    private lateinit var scroll: ScrollView
    private lateinit var footer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        store = Store(this)
        content = Data.content(this)
        val stop = intent.getStringExtra(EXTRA_STOP)?.let { content.stopByKey(it) }
        val sheet = intent.getStringExtra(EXTRA_SHEET)?.let { content.sheet(it) }
        if (stop == null && sheet == null) {
            finish()
            return
        }
        this.stop = stop
        this.sheet = sheet
        // Ouverte une fois : la leçon (ou la fiche) est « déjà vue » dans l'onglet Leçons.
        val read = if (stop != null) stop.key.takeIf { stop.lesson != null } else sheet!!.key
        if (read != null) {
            val pr = store.progress()
            if (pr.lessonsRead.add(read)) store.save(pr)
        }

        val root = vbox()
        val top = View(this).apply { setBackgroundColor(C.YELLOW) }
        root.addView(top, lp(MATCH, 0))
        val header = when {
            stop?.lesson != null -> backHeader(stop.line.label.uppercase(), "LEÇON ${stop.number}") { finish() }
            stop != null -> backHeader(stop.line.label.uppercase(), "ARRÊT ${stop.number}") { finish() }
            else -> backHeader("FICHE · D'APRÈS TON COURS", sheet!!.title.uppercase()) { finish() }
        }
        root.addView(header, lp())
        root.addView(stripes())
        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        root.addView(scroll, lp(MATCH, 0, 1f))
        // Le bouton « Découvrir » d'un arrêt reste en bas de l'écran, sous la liste de ses mots.
        footer = FrameLayout(this).apply { setBackgroundColor(C.CREAM) }
        root.addView(footer, lp())
        setContentView(root)
        val wordsPage = stop != null && stop.lesson == null
        edgeToEdge(root, top, if (wordsPage) footer else scroll, lightNavigation = true)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Remplit la page ; refaite au retour d'un entraînement, car les mots vus ont changé. */
    private fun render() {
        val stop = stop
        val pr = store.progress()
        val y = scroll.scrollY
        val body = vbox(16).apply { pad(20, 20, 20, 24) }
        footer.removeAllViews()
        when {
            stop?.lesson != null -> {
                addLesson(body, stop, content)
                body.addView(View(this), lp(MATCH, 0, 1f))
                val practice = vbox(8)
                practice.addView(practiceButton("S'ENTRAÎNER SUR CETTE LEÇON", stop), lp())
                practice.addView(note(stop, pr), lp())
                Fabrique.theme(stop)?.let { practice.addView(rondeCard(stop, it), lp().apply { topMargin = dp(8) }) }
                body.addView(practice, lp().apply { topMargin = dp(4) })
            }
            stop != null -> {
                addStop(body, stop, pr)
                val col = vbox(8).apply { pad(20, 10, 20, 14) }
                val fresh = stop.items.any { !pr.isIntroduced(it) }
                col.addView(practiceButton(if (fresh) "DÉCOUVRIR CET ARRÊT" else "S'ENTRAÎNER SUR CET ARRÊT", stop), lp())
                col.addView(note(stop, pr), lp())
                footer.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
            }
            else -> addSheet(body, sheet!!)
        }
        scroll.removeAllViews()
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    /** Le gros bouton rouge, sur une seule ligne même sur un petit téléphone (le texte rapetisse un peu s'il le faut). */
    private fun practiceButton(title: String, stop: Stop): View {
        val room = resources.displayMetrics.widthPixels - dp(20 + 20 + 16 + 16 + 11 + 10)
        val size = listOf(18f, 17f, 16f, 15f).firstOrNull { textWidth(title, it, 800) <= room } ?: 14f
        return primaryButton(title, 60, size) {
            startActivity(Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_PRACTICE, stop.key))
        }
    }

    /** « 10 questions, dont 5 mots nouveaux » sous le bouton. */
    private fun note(stop: Stop, pr: Progress): View {
        val fresh = minOf(Planner.PRACTICE_NEW, stop.items.count { !pr.isIntroduced(it) })
        val n = Planner.EXERCISES
        val value = when {
            fresh > 0 && stop.line == Line.VOCAB -> "$n questions, dont ${Fr.count(fresh, "mot nouveau", "mots nouveaux")}"
            fresh > 0 -> "$n questions, dont ${Fr.count(fresh, "nouvelle phrase", "nouvelles phrases")}"
            stop.lesson != null -> "$n questions sur les phrases de cette leçon"
            stop.line == Line.VOCAB -> "$n questions sur les mots de cet arrêt"
            else -> "$n questions sur les phrases de cet arrêt"
        }
        return text(value, 14f, 600, C.GRAY).apply { gravity = Gravity.CENTER }
    }

    /** Un arrêt sans leçon : son nom, où en est le jeepney, et la liste de ses mots (vus ou à découvrir). */
    private fun addStop(body: LinearLayout, stop: Stop, pr: Progress) {
        val words = stop.line == Line.VOCAB
        val head = vbox(8)
        head.addView(text(stop.title, 28f, 800, line = 1.05f))
        val (tag, fill) = when {
            pr.isStopDone(stop) -> "ARRÊT RÉUSSI" to C.GREEN
            stop == pr.currentStop(content, stop.line) -> "LE JEEPNEY EST ICI" to C.RED
            else -> "PLUS LOIN SUR LA ROUTE" to C.NAVY
        }
        head.addView(text(tag, 12f, 800, C.WHITE, 0.08f).apply {
            pad(10, 4, 10, 4)
            box(fill, 8)
        }, lp(WRAP, WRAP))
        val n = stop.items.size
        val seen = stop.items.count { pr.isIntroduced(it) }
        val known = stop.items.count { pr.isKnown(it) }
        val count = if (words) Fr.count(n, "mot", "mots") else Fr.count(n, "phrase", "phrases")
        // « vu », « vus », « vue », « vues »
        fun agree(k: Int, word: String) = word + (if (words) "" else "e") + (if (k > 1) "s" else "")
        val all = if (words) "tous" else "toutes"
        val summary = when (seen) {
            0 -> "$count, $all à découvrir"
            n -> "$count, $all déjà ${agree(n, "vu")} · $known bien ${agree(known, "connu")}"
            else -> "$count : $seen déjà ${agree(seen, "vu")}, ${n - seen} à découvrir"
        }
        head.addView(text(summary, 15f, 600, C.GRAY, line = 1.25f))
        body.addView(head, lp())

        val list = vbox().apply {
            pad(16, 6, 16, 6)
            box(C.WHITE, 16, C.NAVY, 3)
        }
        stop.items.forEachIndexed { i, item ->
            if (i > 0) list.addView(dashes(), lp(MATCH, dp(2)))
            list.addView(itemRow(item, pr), lp())
        }
        body.addView(list, lp())
    }

    /** Un mot de l'arrêt : son niveau s'il est déjà vu, sinon « à découvrir ». */
    private fun itemRow(item: Item, pr: Progress): View {
        val row = hbox(12).apply { pad(0, 8, 0, 8) }
        val words = vbox(1)
        words.addView(text(item.ceb, if (item.isWord) 18f else 16f, 800, line = 1.15f))
        words.addView(text(item.fr, 15f, 500, line = 1.2f))
        row.addView(words, lp(0, WRAP, 1f))
        if (pr.isIntroduced(item)) {
            row.addView(LevelBars(this, pr.level(item)).apply { contentDescription = "Niveau ${pr.level(item)} sur 5" })
        } else {
            row.addView(text("à découvrir", 13f, 700, C.LABEL).apply {
                pad(10, 3, 10, 3)
                box(C.WHITE, 999, C.LABEL, 2)
            })
        }
        return row
    }

    /** La carte jaune de la ronde de la leçon : son dessin (ou deux de ses petits mots), son titre, ce qui change. */
    private fun rondeCard(stop: Stop, theme: Theme): View {
        val card = hbox(12).apply {
            pad(14, 12, 14, 12)
            box(C.YELLOW, 18, C.NAVY, 3, C.NAVY, 4)
            contentDescription = theme.title.lowercase().capitalized()
            onTap { startActivity(Intent(this@LessonActivity, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_RONDE, stop.key)) }
        }
        card.addView(rondeIcon(theme))
        val words = vbox(2)
        words.addView(text(theme.title, 16f, 800, line = 1.1f))
        words.addView(text(theme.subtitle, 14f, 600, line = 1.2f))
        card.addView(words, lp(0, WRAP, 1f))
        card.addIcon(R.drawable.ic_chevron, 20, C.NAVY)
        return card
    }

    override fun onPause() {
        super.onPause()
        Gloss.dismiss()
    }

    companion object {
        const val EXTRA_STOP = "stop"
        const val EXTRA_SHEET = "sheet"
    }
}
