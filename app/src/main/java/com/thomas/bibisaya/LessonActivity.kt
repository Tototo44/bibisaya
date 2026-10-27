package com.thomas.bibisaya

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import com.thomas.bibisaya.engine.Planner

/** Une leçon de grammaire ou une fiche, à lire quand on veut (depuis l'onglet Leçons ou la route). */
class LessonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        val store = Store(this)
        val content = Data.content(this)
        val stop = intent.getStringExtra(EXTRA_STOP)?.let { content.stopByKey(it) }?.takeIf { it.lesson != null }
        val sheet = intent.getStringExtra(EXTRA_SHEET)?.let { content.sheet(it) }
        if (stop == null && sheet == null) {
            finish()
            return
        }
        // Ouverte une fois : elle est « déjà vue » dans l'onglet Leçons.
        val pr = store.progress()
        if (pr.lessonsRead.add(stop?.key ?: sheet!!.key)) store.save(pr)

        val root = vbox()
        val top = View(this).apply { setBackgroundColor(C.YELLOW) }
        root.addView(top, lp(MATCH, 0))
        val header = if (stop != null) backHeader(stop.line.label.uppercase(), "LEÇON ${stop.number}") { finish() }
        else backHeader("FICHE · D'APRÈS TON COURS", sheet!!.title.uppercase()) { finish() }
        root.addView(header, lp())
        root.addView(stripes())

        val body = vbox(16).apply { pad(20, 20, 20, 24) }
        if (stop != null) {
            addLesson(body, stop, content)
            body.addView(View(this), lp(MATCH, 0, 1f))
            val practice = vbox(8)
            // Sur une seule ligne, même sur un petit téléphone : le texte rapetisse un peu s'il le faut.
            val title = "S'ENTRAÎNER SUR CETTE LEÇON"
            val room = resources.displayMetrics.widthPixels - dp(20 + 20 + 16 + 16 + 11 + 10)
            val size = listOf(18f, 17f, 16f, 15f).firstOrNull { textWidth(title, it, 800) <= room } ?: 14f
            practice.addView(primaryButton(title, 60, size) {
                startActivity(Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_PRACTICE, stop.key))
            }, lp())
            val n = minOf(Planner.EXERCISES, stop.items.size)
            practice.addView(text("$n questions sur les phrases de cette leçon", 14f, 600, C.GRAY).apply { gravity = Gravity.CENTER }, lp())
            if (stop.lesson?.ronde != null) practice.addView(rondeCard(stop.key), lp().apply { topMargin = dp(8) })
            body.addView(practice, lp().apply { topMargin = dp(4) })
        } else {
            addSheet(body, sheet!!)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
        edgeToEdge(root, top, scroll, lightNavigation = true)
    }

    /** La carte jaune de la ronde des personnes (leçons 1 et 4). */
    private fun rondeCard(key: String): View {
        val card = hbox(12).apply {
            pad(14, 12, 14, 12)
            box(C.YELLOW, 18, C.NAVY, 3, C.NAVY, 4)
            contentDescription = "La ronde des personnes"
            onTap { startActivity(Intent(this@LessonActivity, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_RONDE, key)) }
        }
        card.addView(PeopleView(this, null, labels = false))
        val words = vbox(2)
        words.addView(text("LA RONDE DES PERSONNES", 16f, 800, line = 1.1f))
        words.addView(text("2 tours de 7 questions : la personne change à chaque fois", 14f, 600, line = 1.2f))
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
