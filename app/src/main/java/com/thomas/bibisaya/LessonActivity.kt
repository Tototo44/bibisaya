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

    override fun onPause() {
        super.onPause()
        Gloss.dismiss()
    }

    companion object {
        const val EXTRA_STOP = "stop"
        const val EXTRA_SHEET = "sheet"
    }
}
