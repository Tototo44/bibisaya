package com.thomas.bibisaya

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Paint
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.thomas.bibisaya.engine.Answers
import com.thomas.bibisaya.engine.Badge
import com.thomas.bibisaya.engine.Content
import com.thomas.bibisaya.engine.Exercise
import com.thomas.bibisaya.engine.Feedback
import com.thomas.bibisaya.engine.Item
import com.thomas.bibisaya.engine.Kind
import com.thomas.bibisaya.engine.LessonStep
import com.thomas.bibisaya.engine.Line
import com.thomas.bibisaya.engine.NewStep
import com.thomas.bibisaya.engine.Plan
import com.thomas.bibisaya.engine.Planner
import com.thomas.bibisaya.engine.Progress
import com.thomas.bibisaya.engine.Session
import com.thomas.bibisaya.engine.Slot
import com.thomas.bibisaya.engine.SlotState
import com.thomas.bibisaya.engine.Step
import com.thomas.bibisaya.engine.Stop
import kotlin.random.Random

/** Une séance : petite leçon, nouveaux mots, 10 exercices, puis l'écran de fin. */
class SessionActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: Content
    private var run: Running? = null
    private lateinit var head: FrameLayout
    private lateinit var body: FrameLayout
    private lateinit var footer: FrameLayout
    /** Vrai dès qu'une réponse est donnée : évite de répondre deux fois. */
    private var busy = false
    private var lessonOnly = false
    /** Pour les captures d'écran : répond tout seul à l'exercice affiché. */
    private var demoHook: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        store = Store(this)
        content = Data.content(this)
        val root = vbox()
        val top = View(this).apply { setBackgroundColor(C.YELLOW) }
        root.addView(top, lp(MATCH, 0))
        head = FrameLayout(this)
        root.addView(head, lp())
        body = FrameLayout(this)
        root.addView(body, lp(MATCH, 0, 1f))
        footer = FrameLayout(this)
        root.addView(footer, lp())
        setContentView(root)
        edgeToEdge(root, top, footer, lightNavigation = true)

        intent.getStringExtra(EXTRA_LESSON)?.let { key ->
            val stop = Line.values().flatMap { content.stops(it) }.firstOrNull { it.key == key }
            if (stop?.lesson != null) {
                lessonOnly = true
                lessonHeader()
                showLesson(stop)
                return
            }
        }
        if (Store.demo && intent.hasExtra(EXTRA_DEMO_STEP)) {
            startDemo(intent.getStringExtra(EXTRA_DEMO_STEP)!!, intent.getStringExtra(EXTRA_DEMO_ANSWER))
            return
        }

        val slot = Slot.of(intent.getStringExtra(EXTRA_SLOT) ?: "")
        val today = Store.today()
        val existing = Data.running
        // Une séance commencée (ou un écran recréé) reprend là où elle en était.
        val resume = existing != null && existing.slot == slot && existing.day == today &&
            (existing.outcome == null || savedInstanceState != null)
        run = if (resume) existing else {
            val pr = store.progress()
            if (slot != null && slot in pr.slotsDone(today)) {
                Toast.makeText(this, "La séance ${slot.adjective} est déjà faite. Bravo !", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val line = if (slot == null) null else Plan.lineFor(slot, today, pr.installDay)
            Running(slot, slot == null, today, pr, Planner.build(content, pr, slot, line, today)).also { Data.running = it }
        }
        slot?.let { Notify.cancel(this, it) }
        showCurrent()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (lessonOnly) finish() else askStop()
    }

    override fun onPause() {
        super.onPause()
        Gloss.dismiss()
    }

    private fun askStop() {
        val r = run
        if (r == null || r.outcome != null) {
            goHome()
            return
        }
        confirm(
            "Arrêter la séance ?",
            "Les mots déjà vus restent dans ton lexique, mais cette séance ne comptera pas pour aujourd'hui.",
            "CONTINUER LA SÉANCE", "Arrêter quand même",
            onNo = {
                Data.running = null
                finish()
            },
        ) {}
    }

    private fun goHome() {
        Data.running = null
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** Affiche l'étape en cours (ou l'écran de fin). */
    private fun showCurrent() {
        val r = run ?: return
        Gloss.dismiss()
        demoHook = null
        if (r.outcome != null || r.session.finished) {
            showEnd()
            return
        }
        val step = r.session.current!!
        r.session.onShown(step)
        store.save(r.progress)
        busy = false
        sessionHeader(step)
        when (step) {
            is LessonStep -> showLesson(step.stop)
            is NewStep -> showNew(step.item)
            is Exercise -> when (step.kind) {
                Kind.CHOOSE_CEB, Kind.CHOOSE_FR, Kind.GAP -> showChoice(step)
                Kind.WRITE -> showWrite(step)
                Kind.ORDER -> showOrder(step)
                Kind.MATCH -> showMatch(step)
            }
        }
    }

    private fun next() {
        run?.session?.next()
        showCurrent()
    }

    // ---------- Le cadre : en-tête, page, bas de l'écran ----------

    private fun sessionHeader(step: Step) {
        val s = run!!.session
        val total = s.gradedTotal.coerceAtLeast(1)
        val row = hbox(12).apply {
            setBackgroundColor(C.YELLOW)
            pad(18, 16, 18, 14)
        }
        row.addView(squareButton(R.drawable.ic_close, "Fermer la séance", shadow = false) { askStop() })
        val bar = Bar(this, C.WHITE, C.RED, dpf(3))
        bar.fraction = if (step is LessonStep) 0.06f + s.gradedAnswered.toFloat() / total else s.counter.toFloat() / total
        row.addView(bar, lp(0, dp(24), 1f))
        row.addView(text("${s.counter}/${s.gradedTotal}", 16f, 800))
        setHead(row)
    }

    private fun lessonHeader() {
        val row = hbox(12).apply {
            setBackgroundColor(C.YELLOW)
            pad(18, 16, 18, 14)
        }
        row.addView(squareButton(R.drawable.ic_close, "Fermer", shadow = false) { finish() })
        row.addView(text("Revoir la leçon", 20f, 800), lp(0, WRAP, 1f))
        setHead(row)
    }

    private fun setHead(v: View, line: Boolean = true) {
        head.removeAllViews()
        val col = vbox()
        col.addView(v, lp())
        if (line) col.addView(View(this).apply { setBackgroundColor(C.NAVY) }, lp(MATCH, dp(3)))
        head.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun page(gap: Int = 22, top: Int = 24, build: LinearLayout.() -> Unit) {
        val col = vbox(gap).apply { pad(20, top, 20, 24) }
        col.build()
        val sv = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        body.removeAllViews()
        body.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun setFooter(v: View?, bg: Int = C.CREAM) {
        footer.removeAllViews()
        footer.setBackgroundColor(bg)
        if (v != null) footer.addView(v, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun actionFooter(vararg views: View) {
        val col = vbox(12).apply { pad(20, 10, 20, 18) }
        for (v in views) col.addView(v, lp())
        setFooter(col)
    }

    private fun question(value: String, base: Float = 28f): TextView {
        val size = when {
            value.length <= 30 -> base
            value.length <= 50 -> base - 4
            else -> base - 7
        }
        return text(value, size, 800, line = 1.12f)
    }

    private fun tagRow(tag: String, where: String): LinearLayout {
        val row = hbox(10)
        row.addView(text(tag, 13f, 800, C.WHITE, 0.08f).apply {
            pad(10, 4, 10, 4)
            box(C.RED, 8)
        })
        row.addView(text(where, 14f, 700).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(0, WRAP, 1f))
        return row
    }

    // ---------- Petite leçon ----------

    private fun showLesson(stop: Stop) {
        val lesson = stop.lesson!!
        page(18) {
            addView(tagRow("PETITE LEÇON", "${stop.line.label} · Arrêt ${stop.number}"), lp())
            val top = vbox(6)
            top.addView(text(stop.title, 32f, 800, line = 1.0f))
            top.addView(text(lesson.intro, 16f, 500, line = 1.35f))
            addView(top, lp())
            if (lesson.rules.isNotEmpty()) {
                val table = vbox().apply {
                    pad(3, 3, 3, 3)
                    box(C.WHITE, 18, C.NAVY, 3)
                }
                // Les formes à gauche si la plus longue tient dans une colonne, sinon chacune au-dessus de son sens.
                val formPaint = Paint().apply {
                    typeface = Fonts.get(800, 22f)
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
                }
                val widest = lesson.rules.maxOf { formPaint.measureText(it.form) }.toInt() + dp(4)
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
                addView(table, lp())
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
                addView(box, lp())
            }
        }
        actionFooter(
            if (lessonOnly) navyButton("FERMER", 56, 18f) { finish() }
            else primaryButton("J'AI COMPRIS →", 60, 20f) { next() }
        )
    }

    // ---------- Nouveau mot ----------

    private fun showNew(item: Item) {
        val stop = content.stopOf(item)
        page {
            addView(tagRow(if (item.isWord) "NOUVEAU MOT" else "NOUVELLE PHRASE", "Arrêt ${stop.number} · ${stop.title}"), lp())
            val card = vbox(16).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                pad(20, 32, 20, 32)
                box(C.WHITE, 22, C.NAVY, 3, C.NAVY, 6)
                stateListAnimator = null
            }
            if (item.isWord) {
                val size = when {
                    item.ceb.length <= 7 -> 56f
                    item.ceb.length <= 10 -> 46f
                    item.ceb.length <= 14 -> 38f
                    else -> 32f
                }
                card.addView(text(item.ceb, size, 800, line = 1.0f).apply { gravity = Gravity.CENTER }, lp())
            } else {
                val size = when {
                    item.ceb.length <= 16 -> 34f
                    item.ceb.length <= 28 -> 28f
                    else -> 24f
                }
                card.addView(tappableSentence(item.ceb, size, 800, C.NAVY, { content.gloss(it) }, centered = true, underline = C.DASH), lp())
            }
            val margin = (resources.displayMetrics.widthPixels * 0.12f).toInt()
            card.addView(dashes(C.DASH, 3), lp(MATCH, dp(3)).apply {
                leftMargin = margin
                rightMargin = margin
            })
            card.addView(text(item.fr, if (item.isWord) 26f else 20f, 700, line = 1.15f).apply { gravity = Gravity.CENTER }, lp())
            if (item.alts.isNotEmpty()) {
                card.addView(text("On dit aussi : ${item.alts.joinToString(", ")}", 14f, 600, C.MUTED).apply { gravity = Gravity.CENTER }, lp())
            }
            addView(card, lp())
            item.tip?.let { addView(tipBox(it), lp()) }
            if (!item.isWord) {
                addView(text("Touche un mot pour voir sa traduction.", 14f, 600, C.MUTED).apply { gravity = Gravity.CENTER }, lp())
            }
        }
        actionFooter(primaryButton("J'AI RETENU →", 60, 20f) { next() })
    }

    private fun tipBox(tip: String): View {
        val box = hbox(12).apply {
            gravity = Gravity.TOP
            pad(16, 14, 16, 14)
            box(C.NAVY, 18)
        }
        box.addIcon(R.drawable.ic_bulb, 26, C.YELLOW)
        val words = vbox(2)
        words.addView(label("ASTUCE", 12f, C.YELLOW))
        words.addView(text(tip, 15f, 500, C.WHITE, line = 1.35f))
        box.addView(words, lp(0, WRAP, 1f))
        return box
    }

    // ---------- Choisir parmi 4 (et phrase à trou) ----------

    private fun showChoice(ex: Exercise) {
        val item = ex.item
        val views = mutableListOf<Pair<String, OptionView>>()
        page {
            val q = vbox(6)
            q.addView(label(if (ex.kind == Kind.GAP) "COMPLÈTE LA PHRASE" else "CHOISIS LA BONNE TRADUCTION"))
            when (ex.kind) {
                Kind.CHOOSE_CEB -> q.addView(question(if (item.isWord) "Comment dit-on « ${item.fr} » ?" else "Comment dit-on :\n« ${item.fr} »"))
                Kind.CHOOSE_FR -> q.addView(question(if (item.isWord) "Que veut dire « ${item.ceb} » ?" else "Que veut dire :\n« ${item.ceb} »"))
                else -> {
                    q.addView(question("${item.gapBefore.orEmpty()}_____${item.gapAfter.orEmpty()}".trim(), 30f))
                    q.addView(text("« ${item.fr} »", 17f, 600, C.MUTED, line = 1.25f))
                }
            }
            addView(q, lp())
            val long = ex.options.any { it.length > 13 }
            val grid = vbox(14)
            if (long) {
                for (o in ex.options) {
                    val v = OptionView(o, long = true)
                    grid.addView(v, lp())
                    views.add(o to v)
                }
            } else {
                for (pair in ex.options.chunked(2)) {
                    val row = hbox(14)
                    for (o in pair) {
                        val v = OptionView(o, long = false)
                        row.addView(v, lp(0, dp(86), 1f))
                        views.add(o to v)
                    }
                    grid.addView(row, lp())
                }
            }
            addView(grid, lp())
        }
        for ((o, v) in views) v.onTap { answerChoice(ex, o, views) }
        setFooter(null)
        demoHook = { mode -> answerChoice(ex, if (mode == "right") ex.answer else ex.options.first { it != ex.answer }, views) }
    }

    private fun answerChoice(ex: Exercise, chosen: String, views: List<Pair<String, OptionView>>) {
        if (busy) return
        busy = true
        demoHook = null
        val r = run!!
        val correct = chosen == ex.answer
        val fb = r.session.submit(ex, correct)
        r.session.next()
        store.save(r.progress)
        var picked: View? = null
        for ((o, v) in views) {
            v.isClickable = false
            v.stateListAnimator = null
            when {
                o == ex.answer -> v.right()
                o == chosen -> v.wrong()
                else -> v.alpha = 0.6f
            }
            if (o == chosen) picked = v
        }
        if (correct) {
            picked?.pop()
            Buzz.right(this)
            rightPanel(ex.item, fb)
        } else {
            picked?.shake()
            Buzz.wrong(this)
            val lines = mutableListOf<String>()
            meaningOf(ex, chosen)?.let { lines.add(it) }
            lines.add(if (ex.kind == Kind.GAP) "La bonne phrase : **${ex.item.ceb}**" else "La bonne réponse est **${ex.answer}**.")
            wrongPanel(ex.item, fb, lines)
        }
    }

    /** Le sens de la mauvaise réponse choisie : « balay », c'est la maison. */
    private fun meaningOf(ex: Exercise, chosen: String): String? = when (ex.kind) {
        Kind.CHOOSE_CEB -> content.itemsWithForm(Answers.normalize(chosen)).firstOrNull { it.line == ex.item.line }?.let { meaning(it.ceb, it) }
        Kind.CHOOSE_FR -> content.items(ex.item.line).firstOrNull { it.fr == chosen }?.let { o -> "« ${o.fr} » se dit **${o.ceb}**." }
        Kind.GAP -> content.gloss(chosen)?.let { "**$chosen** veut dire « $it »." }
        else -> null
    }

    /** « **balay**, c'est la maison. » */
    private fun meaning(ceb: String, o: Item): String =
        if (o.isWord) "**$ceb**, c'est ${o.fr}." else "**$ceb** veut dire « ${o.fr} »."

    private inner class OptionView(value: String, long: Boolean) : LinearLayout(this@SessionActivity) {
        private val size = if (long) 18f else 22f
        private val mark = icon(R.drawable.ic_check, C.GREEN_TEXT).apply { visibility = View.GONE }
        private val words = text(value, size, 700, line = 1.15f).apply { gravity = Gravity.CENTER }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            pad(12, 12, 12, 12)
            minimumHeight = dp(if (long) 64 else 86)
            box(C.WHITE, 18, C.NAVY, 3)
            squishy()
            addView(mark, LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
            addView(words, LayoutParams(WRAP, WRAP))
        }

        fun right() {
            box(C.GREEN_BG, 18, C.GREEN, 3)
            words.setTextColor(C.GREEN_TEXT)
            words.typeface = Fonts.get(800, size)
            mark.setImageResource(R.drawable.ic_check)
            mark.setColorFilter(C.GREEN_TEXT)
            mark.visibility = View.VISIBLE
        }

        fun wrong() {
            box(C.WRONG_BG, 18, C.LABEL, 3)
            words.setTextColor(C.WRONG_TEXT)
            words.typeface = Fonts.get(800, size)
            mark.setImageResource(R.drawable.ic_close)
            mark.setColorFilter(C.WRONG_TEXT)
            mark.visibility = View.VISIBLE
        }
    }

    // ---------- Écrire ----------

    private fun showWrite(ex: Exercise) {
        val item = ex.item
        val stop = content.stopOf(item)
        val input = EditText(this).apply {
            // Le type de clavier d'abord : il remettrait sinon une police à chasse fixe.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Fonts.get(700, 26f)
            textSize = 26f
            setTextColor(C.NAVY)
            setHintTextColor(C.DASH)
            hint = if (item.isWord) "le mot en cebuano" else "la phrase en cebuano"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            pad(16, 0, 16, 0)
            box(C.WHITE, 14, C.NAVY, 3)
        }
        page {
            addView(label("ÉCRIS EN CEBUANO"))
            val card = vbox(6).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                pad(20, 28, 20, 28)
                box(C.WHITE, 22, C.NAVY, 3, C.NAVY, 6)
                stateListAnimator = null
            }
            val size = when {
                item.fr.length <= 10 -> 44f
                item.fr.length <= 18 -> 34f
                else -> 26f
            }
            card.addView(text(item.fr, size, 800, line = 1.05f).apply { gravity = Gravity.CENTER }, lp())
            card.addView(text("Arrêt ${stop.number} · ${stop.title}", 14f, 600).apply { gravity = Gravity.CENTER }, lp())
            addView(card, lp())
            val answer = vbox(8)
            answer.addView(text("Ta réponse", 15f, 700))
            answer.addView(input, lp(MATCH, dp(62)))
            addView(answer, lp())
        }
        val check = primaryButton("VÉRIFIER", 60, 20f) { answerWrite(ex, input) }
        fun enable(on: Boolean) {
            check.isEnabled = on
            check.alpha = if (on) 1f else 0.45f
        }
        enable(false)
        input.onChange { enable(it.isNotBlank() && !busy) }
        input.setOnEditorActionListener { _, _, _ ->
            answerWrite(ex, input)
            true
        }
        actionFooter(check)
        input.requestFocus()
        input.postDelayed({ getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 250)
        demoHook = { mode ->
            input.setText(
                when (mode) {
                    "right" -> item.ceb
                    "typo" -> item.ceb.dropLast(1)
                    else -> "bato"
                }
            )
            answerWrite(ex, input)
        }
    }

    private fun answerWrite(ex: Exercise, input: EditText) {
        val typed = input.text.toString()
        if (busy || typed.isBlank()) return
        busy = true
        demoHook = null
        val r = run!!
        val item = ex.item
        val verdict = Answers.checkWritten(typed, item, content)
        val correct = verdict != Answers.Verdict.WRONG
        val fb = r.session.submit(ex, correct)
        r.session.next()
        store.save(r.progress)
        input.isFocusable = false
        input.isCursorVisible = false
        input.keyListener = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.box(C.WHITE, 14, if (correct) C.GREEN else C.LABEL, 3)
        input.setTextColor(if (correct) C.GREEN_TEXT else C.WRONG_TEXT)
        if (correct) {
            input.pop()
            Buzz.right(this)
        } else {
            input.shake()
            Buzz.wrong(this)
        }
        when (verdict) {
            Answers.Verdict.RIGHT -> rightPanel(item, fb)
            Answers.Verdict.TYPO -> rightPanel(
                item, fb,
                title = if (fb.change != null) "Presque parfait ! +${Progress.POINTS_ANSWER} points" else "Presque parfait !",
                lines = listOf("Ça s'écrit **${item.ceb}**."),
            )
            Answers.Verdict.WRONG -> {
                val lines = mutableListOf("La bonne réponse est **${item.ceb}**.")
                content.itemsWithForm(Answers.normalize(typed)).firstOrNull()?.let { o -> lines.add(0, meaning(typed.trim(), o)) }
                wrongPanel(item, fb, lines)
            }
        }
    }

    // ---------- Remettre dans l'ordre ----------

    private fun showOrder(ex: Exercise) {
        val item = ex.item
        val tiles = ex.options
        val placed = mutableListOf<Int>()
        val answerFlow = FlowLayout(this, dp(10), dp(10)).apply {
            minimumHeight = dp(110)
            pad(0, 0, 0, 14)
        }
        val pool = FlowLayout(this, dp(12), dp(12), centered = true)
        val poolViews = mutableListOf<TextView>()
        val check = primaryButton("VÉRIFIER", 60, 20f) { answerOrder(ex, placed.map { tiles[it] }, answerFlow) }

        fun refresh() {
            answerFlow.removeAllViews()
            for (i in placed) {
                val t = text(tiles[i], 21f, 700, C.WHITE).apply {
                    pad(16, 10, 16, 10)
                    box(C.NAVY, 12)
                    squishy()
                }
                t.onTap {
                    if (!busy) {
                        placed.remove(i)
                        refresh()
                    }
                }
                answerFlow.addView(t)
            }
            poolViews.forEachIndexed { i, v -> tile(v, used = i in placed) }
            check.isEnabled = placed.isNotEmpty() && !busy
            check.alpha = if (check.isEnabled) 1f else 0.45f
        }

        tiles.forEachIndexed { i, word ->
            val v = text(word, 21f, 700).apply { pad(18, 10, 18, 10) }
            v.onTap {
                if (!busy && i !in placed) {
                    placed.add(i)
                    refresh()
                }
            }
            v.setOnLongClickListener {
                Gloss.show(v, word, content.gloss(word))
                true
            }
            poolViews.add(v)
            pool.addView(v)
        }
        page {
            val q = vbox(6)
            q.addView(label("REMETS DANS L'ORDRE · ${item.line.label.uppercase()}"))
            q.addView(question("« ${item.fr} »", 30f))
            addView(q, lp())
            val area = vbox()
            area.addView(answerFlow, lp())
            area.addView(View(this@SessionActivity).apply { setBackgroundColor(C.NAVY) }, lp(MATCH, dp(3)))
            addView(area, lp())
            addView(pool, lp())
            addView(text("Appuie longtemps sur un mot pour voir sa traduction.", 13f, 600, C.MUTED).apply { gravity = Gravity.CENTER }, lp())
        }
        actionFooter(check)
        refresh()
        demoHook = { mode ->
            val used = mutableSetOf<Int>()
            val order = item.tokens.mapNotNull { tok -> tiles.indices.firstOrNull { tiles[it] == tok && it !in used }?.also { used.add(it) } }
            placed.clear()
            placed.addAll(if (mode == "right") order else order.take(2))
            refresh()
            if (mode == "right") answerOrder(ex, placed.map { tiles[it] }, answerFlow)
        }
    }

    /** Une étiquette du bas : blanche, ou vide (beige) quand elle est déjà placée. */
    private fun tile(v: TextView, used: Boolean) {
        if (used) {
            v.box(C.TRACK, 12, C.TRACK, 3, C.CREAM, 4)
            v.setTextColor(C.TRACK)
        } else {
            v.box(C.WHITE, 12, C.NAVY, 3, C.NAVY, 4)
            v.setTextColor(C.NAVY)
        }
        v.isClickable = !used
        v.isLongClickable = !used
    }

    private fun answerOrder(ex: Exercise, chosen: List<String>, answerFlow: FlowLayout) {
        if (busy || chosen.isEmpty()) return
        busy = true
        demoHook = null
        val r = run!!
        val item = ex.item
        val correct = Answers.checkOrder(chosen, item)
        val fb = r.session.submit(ex, correct)
        r.session.next()
        store.save(r.progress)
        for (i in 0 until answerFlow.childCount) {
            val t = answerFlow.getChildAt(i) as TextView
            t.isClickable = false
            t.stateListAnimator = null
            t.box(if (correct) C.GREEN_BG else C.WRONG_BG, 12, if (correct) C.GREEN else C.LABEL, 3)
            t.setTextColor(if (correct) C.GREEN_TEXT else C.WRONG_TEXT)
        }
        if (correct) {
            answerFlow.pop()
            Buzz.right(this)
            rightPanel(item, fb)
        } else {
            answerFlow.shake()
            Buzz.wrong(this)
            wrongPanel(item, fb, listOf("La bonne phrase : **${item.ceb}**"))
        }
    }

    // ---------- Relier les paires ----------

    private fun showMatch(ex: Exercise) {
        val items = ex.items
        val right = ex.rightOrder
        val n = items.size
        var selLeft = -1
        var selRight = -1
        val matched = mutableSetOf<Int>()
        val wrong = linkedSetOf<Item>()
        val long = items.any { it.ceb.length > 12 || it.fr.length > 12 }
        val leftViews = items.map { MatchView(it.ceb, long) }
        val rightViews = right.map { MatchView(items[it].fr, long) }
        val dots = hbox(6)
        val count = text("", 16f, 700)

        fun update() {
            leftViews.forEachIndexed { i, v ->
                v.show(
                    when {
                        i in matched -> M_DONE
                        i == selLeft -> M_SELECTED
                        else -> M_IDLE
                    }
                )
            }
            rightViews.forEachIndexed { j, v ->
                v.show(
                    when {
                        right[j] in matched -> M_DONE
                        j == selRight -> M_SELECTED
                        else -> M_IDLE
                    }
                )
            }
            dots.removeAllViews()
            repeat(n) { k ->
                val done = k < matched.size
                dots.addView(View(this).apply {
                    background = BoxDrawable(if (done) C.GREEN else 0, dpf(7), C.NAVY, if (done) 0f else dpf(3))
                }, LinearLayout.LayoutParams(dp(14), dp(14)))
            }
            count.text = "${matched.size} paire${if (matched.size > 1) "s" else ""} sur $n"
        }

        fun attempt(li: Int, rj: Int, fromLeft: Boolean) {
            val target = right[rj]
            selLeft = -1
            selRight = -1
            if (target == li) {
                matched.add(li)
                update()
                leftViews[li].pop()
                rightViews[rj].pop()
                Buzz.right(this)
                if (matched.size == n) finishMatch(ex, wrong)
            } else {
                // Le mot touché en premier est celui qu'on ne connaissait pas encore bien.
                wrong.add(if (fromLeft) items[li] else items[target])
                update()
                leftViews[li].show(M_WRONG)
                rightViews[rj].show(M_WRONG)
                leftViews[li].shake()
                rightViews[rj].shake()
                Buzz.wrong(this)
                leftViews[li].postDelayed({ if (!busy) update() }, 550)
            }
        }

        leftViews.forEachIndexed { i, v ->
            v.onTap {
                if (busy || i in matched) return@onTap
                if (selRight >= 0) attempt(i, selRight, fromLeft = false)
                else {
                    selLeft = if (selLeft == i) -1 else i
                    update()
                }
            }
        }
        rightViews.forEachIndexed { j, v ->
            v.onTap {
                if (busy || right[j] in matched) return@onTap
                if (selLeft >= 0) attempt(selLeft, j, fromLeft = true)
                else {
                    selRight = if (selRight == j) -1 else j
                    update()
                }
            }
        }
        page {
            val q = vbox(6)
            q.addView(label("RELIE LES PAIRES"))
            q.addView(text("Touche un mot, puis sa traduction.", 26f, 800, line = 1.12f))
            addView(q, lp())
            val grid = vbox(12)
            for (row in 0 until n) {
                val h = hbox(14)
                h.addView(leftViews[row], lp(0, WRAP, 1f))
                h.addView(rightViews[row], lp(0, WRAP, 1f))
                grid.addView(h, lp())
            }
            addView(grid, lp())
        }
        val bottom = hbox(10).apply {
            gravity = Gravity.CENTER
            pad(20, 14, 20, 18)
        }
        bottom.addView(dots)
        bottom.addView(count)
        setFooter(bottom)
        update()
        demoHook = { _ ->
            attempt(0, right.indexOf(0), fromLeft = true)
            attempt(1, right.indexOf(1), fromLeft = true)
            selLeft = 2
            update()
        }
    }

    private fun finishMatch(ex: Exercise, wrong: Set<Item>) {
        if (busy) return
        busy = true
        demoHook = null
        val r = run!!
        val correct = wrong.isEmpty()
        val fb = r.session.submit(ex, correct, wrong)
        r.session.next()
        store.save(r.progress)
        if (correct) {
            rightPanel(ex.item, fb, lines = listOf("Toutes les paires du premier coup !"), due = false)
        } else {
            val lines = wrong.take(3).map { meaning(it.ceb, it) }
            wrongPanel(wrong.first(), fb, lines, several = wrong.size > 1)
        }
    }

    private inner class MatchView(value: String, long: Boolean) : LinearLayout(this@SessionActivity) {
        private val size = if (long) 16f else 19f
        private val mark = icon(R.drawable.ic_check, C.GREEN_TEXT).apply { visibility = View.GONE }
        private val words = text(value, size, 700, line = 1.1f).apply { gravity = Gravity.CENTER }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            pad(8, 8, 8, 8)
            minimumHeight = dp(66)
            addView(mark, LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) })
            addView(words, LayoutParams(WRAP, WRAP))
            show(M_IDLE)
        }

        fun show(state: Int) {
            when (state) {
                M_SELECTED -> {
                    box(C.NAVY, 14, C.NAVY, 3, C.RED, 4)
                    words.setTextColor(C.YELLOW)
                    words.typeface = Fonts.get(800, size)
                    mark.visibility = View.GONE
                }
                M_DONE -> {
                    box(C.GREEN_BG, 14, C.GREEN, 3, C.CREAM, 4)
                    words.setTextColor(C.GREEN_TEXT)
                    words.typeface = Fonts.get(700, size)
                    mark.visibility = View.VISIBLE
                    isClickable = false
                }
                M_WRONG -> {
                    box(C.WRONG_BG, 14, C.LABEL, 3, C.CREAM, 4)
                    words.setTextColor(C.WRONG_TEXT)
                }
                else -> {
                    box(C.WHITE, 14, C.NAVY, 3, C.NAVY, 4)
                    words.setTextColor(C.NAVY)
                    words.typeface = Fonts.get(700, size)
                    mark.visibility = View.GONE
                }
            }
        }
    }

    // ---------- Le panneau du bas, après une réponse ----------

    private fun praise(): String = listOf("Maayo kaayo !", "Sakto !", "Nindot !")[(run?.session?.correctFirst ?: 0) % 3]

    private fun rightPanel(item: Item, fb: Feedback, title: String? = null, lines: List<String> = emptyList(), due: Boolean = true) {
        val graded = fb.change != null
        val all = lines.toMutableList()
        if (due) fb.change?.let { all.add(Plan.dueText(it, item.isWord)) }
        if (!graded && lines.isEmpty()) all.add("Cette fois, c'est la bonne réponse.")
        panel(true, title ?: if (graded) "${praise()} +${Progress.POINTS_ANSWER} points" else "Sakto !", all)
    }

    private fun wrongPanel(item: Item, fb: Feedback, lines: List<String>, several: Boolean = false) {
        val what = when {
            several -> "ces mots"
            item.isWord -> "ce mot"
            else -> "cette phrase"
        }
        val pronoun = when {
            several -> "les"
            item.isWord -> "le"
            else -> "la"
        }
        val note = if (fb.retry) "Pas grave : $what ${if (several) "reviendront" else "reviendra"} un peu plus tard dans la séance."
        else "Pas grave : tu $pronoun reverras à la prochaine séance."
        panel(false, "Pas tout à fait…", lines + note)
    }

    private fun panel(right: Boolean, title: String, lines: List<String>) {
        val col = vbox(if (right) 8 else 10).apply { pad(20, 16, 20, 18) }
        val titleColor = if (right) C.GREEN_TEXT else C.WRONG_TEXT
        if (right) {
            val row = hbox(10)
            row.addIcon(R.drawable.ic_check_circle, 28, C.GREEN_TEXT)
            row.addView(text(title, if (title.length > 25) 19f else 22f, 800, titleColor), lp(0, WRAP, 1f))
            col.addView(row, lp())
        } else {
            col.addView(text(title, 22f, 800, titleColor))
        }
        lines.forEachIndexed { i, l ->
            col.addView(rich(l, if (i == 0 && !right) 16f else 15f, 500, if (right) C.GREEN_DEEP else C.WRONG_DEEP))
        }
        val next = if (right) primaryButton("CONTINUER", 58, 19f) { showCurrent() } else navyButton("CONTINUER", 56, 18f) { showCurrent() }
        col.addView(next, lp().apply { topMargin = dp(8) })
        val wrap = vbox()
        wrap.addView(View(this).apply { setBackgroundColor(if (right) C.GREEN else C.LABEL) }, lp(MATCH, dp(3)))
        wrap.addView(col, lp())
        setFooter(wrap, if (right) C.GREEN_BG else C.WRONG_BG)
        wrap.slideUp()
    }

    // ---------- Fin de séance ----------

    private fun showEnd() {
        val r = run!!
        val s = r.session
        val outcome = r.outcome ?: r.progress.finishSession(content, r.slot, r.day, s.correctFirst, s.gradedTotal).also {
            r.outcome = it
            store.save(r.progress)
            r.slot?.let { slot -> Notify.cancel(this, slot) }
            Buzz.win(this)
        }
        Gloss.dismiss()
        val ratio = if (s.gradedTotal == 0) 1f else s.correctFirst.toFloat() / s.gradedTotal
        val hello = when {
            ratio >= 0.8f -> "Maayo kaayo, Thomas !"
            ratio >= 0.5f -> "Maayo, Thomas !"
            else -> "Padayon, Thomas ! Continue comme ça."
        }
        val top = vbox(4).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(C.YELLOW)
            pad(22, 30, 22, 24)
        }
        top.addView(text(Plan.sessionTitle(r.slot, s.line).uppercase(), 14f, 700, spacing = 0.08f).apply { gravity = Gravity.CENTER }, lp())
        top.addView(text("SÉANCE FINIE !", 42f, 800, line = 0.95f).apply { gravity = Gravity.CENTER }, lp())
        top.addView(text(hello, 19f, 700).apply { gravity = Gravity.CENTER }, lp())
        val h = vbox()
        h.addView(top, lp())
        h.addView(stripes())
        setHead(h, line = false)

        page(16, top = 20) {
            if (outcome.perfectDay) addView(perfectCard(), lp())
            val grid = vbox(12)
            val n = s.newItems.size
            val newLabel = when {
                s.line == null -> if (n <= 1) "nouveauté" else "nouveautés"
                s.line == Line.VOCAB -> if (n <= 1) "nouveau mot" else "nouveaux mots"
                else -> if (n <= 1) "nouvelle phrase" else "nouvelles phrases"
            }
            val cells = listOf(
                "+${outcome.points}" to "points gagnés",
                "${s.correctFirst} / ${s.gradedTotal}" to "bonnes réponses",
                "${outcome.streak}" to if (outcome.streak > 1) "jours d'affilée" else "jour d'affilée",
                "$n" to newLabel,
            )
            for (pair in cells.chunked(2)) {
                val row = hbox(12)
                for ((big, small) in pair) row.addView(stat(big, small), lp(0, WRAP, 1f))
                grid.addView(row, lp())
            }
            addView(grid, lp())
            for (b in outcome.newBadges) addView(badgeCard(b), lp())
            if (outcome.completedStops.isNotEmpty()) addView(stopsCard(outcome.completedStops), lp())
            if (n > 0) {
                val list = vbox(8)
                val title = when {
                    s.newItems.all { it.isWord } -> "TES NOUVEAUX MOTS"
                    s.newItems.none { it.isWord } -> "TES NOUVELLES PHRASES"
                    else -> "TES NOUVEAUTÉS"
                }
                list.addView(text(title, 16f, 800))
                s.newItems.forEachIndexed { i, item ->
                    if (i > 0) list.addView(dashes(C.DASH, 2))
                    if (item.isWord) {
                        val row = hbox(12)
                        row.addView(text(item.ceb, 17f, 800), lp(0, WRAP, 1f))
                        row.addView(text(item.fr, 17f, 500).apply { gravity = Gravity.END })
                        list.addView(row, lp())
                    } else {
                        val one = vbox(1)
                        one.addView(text(item.ceb, 16f, 800, line = 1.2f))
                        one.addView(text(item.fr, 15f, 500, line = 1.2f))
                        list.addView(one, lp())
                    }
                }
                addView(list, lp())
            }
        }

        val buttons = mutableListOf<View>(navyButton("RETOUR À L'ACCUEIL", 58, 18f) { goHome() })
        if (!Store.demo) {
            val today = Store.today()
            val minute = Store.minuteOfDay()
            val times = store.times
            val open = Slot.values().firstOrNull { Plan.state(it, r.progress, today, minute, times) == SlotState.OPEN }
            when {
                open != null -> buttons.add(outlineButton("Enchaîner : séance ${open.adjective}") { startNew(open) })
                r.progress.slotsDone(today).size == Slot.values().size -> buttons.add(outlineButton("Encore une séance (en bonus)") { startNew(null) })
            }
        } else {
            buttons.add(outlineButton("Encore une séance (en bonus)") {})
        }
        actionFooter(*buttons.toTypedArray())
    }

    private fun startNew(slot: Slot?) {
        Data.running = null
        val i = Intent(this, SessionActivity::class.java)
        if (slot != null) i.putExtra(EXTRA_SLOT, slot.code) else i.putExtra(EXTRA_BONUS, true)
        startActivity(i)
        finish()
    }

    private fun stat(big: String, small: String): View {
        val box = vbox().apply {
            pad(14, 12, 14, 12)
            box(C.WHITE, 16, C.NAVY, 3)
        }
        box.addView(text(big, 28f, 800))
        box.addView(text(small, 14f, 600))
        return box
    }

    private fun perfectCard(): View {
        val card = hbox(12).apply {
            pad(16, 14, 16, 14)
            box(C.RED, 18, C.NAVY, 3, C.NAVY, 5)
            stateListAnimator = null
        }
        val words = vbox(2)
        words.addView(text("JOURNÉE PARFAITE !", 20f, 800, C.WHITE))
        words.addView(text("3 séances sur 3 · +${Progress.POINTS_PERFECT_DAY} points bonus", 15f, 600, C.WHITE))
        card.addView(words, lp(0, WRAP, 1f))
        val dots = hbox(4)
        repeat(3) {
            dots.addView(View(this).apply { background = BoxDrawable(C.YELLOW, dpf(11), C.NAVY, dpf(2)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        card.addView(dots)
        return card
    }

    private fun badgeCard(badge: Badge): View {
        val card = hbox(14).apply {
            pad(14, 14, 14, 14)
            box(C.YELLOW, 18, C.NAVY, 3, C.NAVY, 5)
            stateListAnimator = null
        }
        card.addView(badgeCircle(badge, true, 56, 26), LinearLayout.LayoutParams(dp(59), dp(59)))
        val words = vbox(2)
        words.addView(label("NOUVEAU BADGE !", 12f, C.LABEL))
        words.addView(text(badge.title, 20f, 800))
        words.addView(text(MainActivity.badgeText(badge), 14f, 500, line = 1.25f))
        card.addView(words, lp(0, WRAP, 1f))
        return card
    }

    private fun stopsCard(stops: List<Stop>): View {
        val card = vbox(6).apply {
            pad(16, 14, 16, 14)
            box(C.NAVY, 18)
        }
        val top = hbox(10)
        top.addView(label("LE JEEPNEY AVANCE !", 12f, C.YELLOW), lp(0, WRAP, 1f))
        top.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.jeepney) }, LinearLayout.LayoutParams(dp(36), dp(24)))
        card.addView(top, lp())
        for (stop in stops) {
            card.addView(text("${stop.line.short} · arrêt ${stop.number} · ${stop.title} : réussi !", 15f, 600, C.WHITE, line = 1.25f))
        }
        return card
    }

    // ---------- Démo (captures d'écran) ----------

    private fun startDemo(kind: String, answer: String?) {
        var pr = store.progress()
        if (pr.items.isEmpty()) {
            Data.fillDemo(store, content)
            pr = store.progress()
        }
        val today = Store.today()
        val line = when (kind) {
            "LESSON", "GAP" -> Line.COMPLEX
            "ORDER" -> Line.SIMPLE
            else -> Line.VOCAB
        }
        val slot = when {
            kind == "END" -> Slot.SOIR
            line == Line.VOCAB -> Slot.MATIN
            line == Line.SIMPLE -> Slot.MIDI
            else -> Slot.SOIR
        }
        val random = Random(5)
        val built = Planner.build(content, pr, slot, line, today, random)
        val first: Step? = when (kind) {
            "END" -> null
            "LESSON" -> built.steps.firstOrNull { it is LessonStep } ?: LessonStep(content.stops(Line.COMPLEX).first { it.lesson != null })
            "NEW" -> built.steps.firstOrNull { it is NewStep } ?: NewStep(content.items(Line.VOCAB).first { !pr.isIntroduced(it) })
            else -> {
                val k = Kind.valueOf(kind)
                built.steps.firstOrNull { it is Exercise && it.kind == k } ?: demoExercise(k, line, pr, random)
            }
        }
        val steps = mutableListOf<Step>()
        if (first != null) steps.add(first)
        steps.addAll(built.steps.filter { it !== first })
        run = Running(slot, false, today, pr, Session(content, pr, slot, line, today, steps, random))
        if (kind == "END") {
            val s = run!!.session
            var n = 0
            while (!s.finished) {
                val step = s.current!!
                s.onShown(step)
                if (step is Exercise) s.submit(step, n++ != 3)
                s.next()
            }
        }
        showCurrent()
        if (answer != null) body.postDelayed({ demoHook?.invoke(answer) }, 700)
    }

    private fun demoExercise(kind: Kind, line: Line, pr: Progress, random: Random): Exercise {
        val seen = content.items(line).filter { pr.isIntroduced(it) }.ifEmpty { content.items(line).take(8) }
        return when (kind) {
            Kind.MATCH -> Planner.buildMatch(content, emptyList(), seen, pr, random)!!
            Kind.GAP -> Planner.exercise(content, kind, content.items(Line.COMPLEX).first { it.hasGap }, true, random)
            Kind.ORDER -> Planner.exercise(content, kind, seen.first { it.tokens.size >= 3 }, true, random)
            else -> Planner.exercise(content, kind, seen.first(), true, random)
        }
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        const val EXTRA_BONUS = "bonus"
        const val EXTRA_LESSON = "lesson"
        const val EXTRA_DEMO_STEP = "demoStep"
        const val EXTRA_DEMO_ANSWER = "demoAnswer"
        private const val M_IDLE = 0
        private const val M_SELECTED = 1
        private const val M_DONE = 2
        private const val M_WRONG = 3
    }
}
