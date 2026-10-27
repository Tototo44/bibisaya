package com.thomas.bibisaya

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
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
import com.thomas.bibisaya.engine.Mode
import com.thomas.bibisaya.engine.NewStep
import com.thomas.bibisaya.engine.Plan
import com.thomas.bibisaya.engine.Planner
import com.thomas.bibisaya.engine.Person
import com.thomas.bibisaya.engine.Progress
import com.thomas.bibisaya.engine.Ronde
import com.thomas.bibisaya.engine.RondeIntroStep
import com.thomas.bibisaya.engine.RondeKind
import com.thomas.bibisaya.engine.RondeQuestion
import com.thomas.bibisaya.engine.RondeStep
import com.thomas.bibisaya.engine.Session
import com.thomas.bibisaya.engine.Slot
import com.thomas.bibisaya.engine.SlotState
import com.thomas.bibisaya.engine.Step
import com.thomas.bibisaya.engine.Stop
import com.thomas.bibisaya.engine.Variants
import kotlin.random.Random

/**
 * Une série de questions : une séance (petite leçon, nouveaux mots, 10 exercices, écran de fin),
 * une traduction (10 mots ou 10 phrases), un entraînement sur une leçon, ou la ronde des personnes.
 */
class SessionActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: Content
    private var run: Running? = null
    private lateinit var head: FrameLayout
    private lateinit var body: FrameLayout
    private lateinit var footer: FrameLayout
    /** Vrai dès qu'une réponse est donnée : évite de répondre deux fois. */
    private var busy = false
    /** Pour les captures d'écran : répond tout seul à l'exercice affiché. */
    private var demoHook: ((String) -> Unit)? = null
    /** Pour les captures d'écran : « Ma réponse était juste aussi ». */
    private var acceptHook: (() -> Unit)? = null
    /** La ronde passe toute seule à la question suivante après une bonne réponse ; ce numéro annule un passage prévu. */
    private var advanceToken = 0

    private val mode: Mode get() = run?.session?.mode ?: Mode.SESSION

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

        if (Store.demo && intent.hasExtra(EXTRA_DEMO_STEP)) {
            startDemo(intent.getStringExtra(EXTRA_DEMO_STEP)!!, intent.getStringExtra(EXTRA_DEMO_ANSWER))
            return
        }

        val slot = Slot.of(intent.getStringExtra(EXTRA_SLOT) ?: "")
        val wanted = when {
            intent.hasExtra(EXTRA_TRANSLATE) -> if (intent.getStringExtra(EXTRA_TRANSLATE) == "PHRASES") Mode.TRANSLATE_PHRASES else Mode.TRANSLATE_WORDS
            intent.hasExtra(EXTRA_RONDE) -> Mode.RONDE
            intent.hasExtra(EXTRA_PRACTICE) -> Mode.LESSON
            else -> Mode.SESSION
        }
        val practiced = (intent.getStringExtra(EXTRA_RONDE) ?: intent.getStringExtra(EXTRA_PRACTICE))?.let { content.stopByKey(it) }
        if ((wanted == Mode.LESSON && practiced == null) || (wanted == Mode.RONDE && practiced?.lesson?.ronde == null)) {
            finish()
            return
        }
        val today = Store.today()
        val existing = Data.running
        // Une séance commencée (ou un écran recréé) reprend là où elle en était.
        val resume = existing != null && existing.slot == slot && existing.day == today &&
            existing.session.mode == wanted && existing.session.stop?.key == practiced?.key &&
            (existing.outcome == null || savedInstanceState != null)
        run = if (resume) existing else {
            val pr = store.progress()
            val session = when (wanted) {
                Mode.SESSION -> {
                    if (slot != null && slot in pr.slotsDone(today)) {
                        Toast.makeText(this, "La séance ${slot.adjective} est déjà faite. Bravo !", Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }
                    val line = if (slot == null) null else Plan.lineFor(slot, today, pr.installDay)
                    Planner.build(content, pr, slot, line, today)
                }
                Mode.LESSON -> Planner.buildLessonPractice(content, pr, practiced!!, today)
                Mode.RONDE -> Ronde.full(content, pr, practiced!!, today)
                else -> {
                    val built = Planner.buildTranslation(content, pr, wanted == Mode.TRANSLATE_WORDS, today, avoid = Data.lastTranslation[wanted] ?: emptySet())
                    if (built == null) {
                        finish()
                        return
                    }
                    Data.lastTranslation[wanted] = built.steps.filterIsInstance<Exercise>().map { it.item.id }.toSet()
                    built
                }
            }
            Running(slot, wanted == Mode.SESSION && slot == null, today, pr, session).also { Data.running = it }
        }
        slot?.let { Notify.cancel(this, it) }
        showCurrent()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        askStop()
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
        val practice = "Tes réponses comptent déjà pour tes révisions, mais les points ne sont gagnés qu'à la fin."
        val (title, message, keep) = when (r.session.mode) {
            Mode.SESSION -> Triple(
                "Arrêter la séance ?",
                "Les mots déjà vus restent dans ton lexique, mais cette séance ne comptera pas pour aujourd'hui.",
                "CONTINUER LA SÉANCE",
            )
            Mode.LESSON -> Triple("Arrêter l'entraînement ?", practice, "CONTINUER")
            Mode.RONDE -> Triple("Arrêter la ronde ?", practice, "CONTINUER")
            else -> Triple("Arrêter la traduction ?", practice, "CONTINUER")
        }
        confirm(
            title, message, keep, "Arrêter quand même",
            onNo = {
                Data.running = null
                finish()
            },
        ) {}
    }

    /** Retour à l'écran d'avant : l'accueil, ou la leçon (l'arrêt) pour un entraînement. */
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
        acceptHook = null
        advanceToken++
        if (r.outcome != null || r.session.finished) {
            hideKeyboard()
            when (r.session.mode) {
                Mode.SESSION -> showEnd()
                Mode.RONDE -> showRondeEnd()
                else -> showPracticeEnd()
            }
            return
        }
        val step = r.session.current!!
        r.session.onShown(step)
        store.save(r.progress)
        busy = false
        // Dans la ronde, le clavier reste ouvert d'une question à écrire à la suivante.
        if (!(step is RondeStep && !step.q.tap)) hideKeyboard()
        sessionHeader(step)
        when (step) {
            is LessonStep -> showLesson(step.stop)
            is RondeIntroStep -> showRondeIntro(step)
            is RondeStep -> showRonde(step)
            is NewStep -> showNew(step.item)
            is Exercise -> when (step.kind) {
                Kind.CHOOSE_CEB, Kind.CHOOSE_FR, Kind.GAP -> showChoice(step)
                Kind.WRITE -> showWrite(step)
                Kind.TRANSLATE_FR -> showTranslateFr(step)
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
        val close = when (s.mode) {
            Mode.SESSION -> "Fermer la séance"
            Mode.LESSON -> "Arrêter l'entraînement"
            Mode.RONDE -> "Arrêter la ronde"
            else -> "Arrêter la traduction"
        }
        row.addView(squareButton(R.drawable.ic_close, close, shadow = false) { askStop() })
        val bar = Bar(this, C.WHITE, C.RED, dpf(3))
        val intro = step is LessonStep || step is RondeIntroStep
        bar.fraction = if (intro) 0.06f + s.gradedAnswered.toFloat() / total else s.counter.toFloat() / total
        row.addView(bar, lp(0, dp(24), 1f))
        row.addView(text("${s.counter}/${s.gradedTotal}", 16f, 800))
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

    /** Le petit titre rouge d'un exercice ; en mode Traduire, avec « 10 mots » à droite. */
    private fun exerciseLabel(title: String): View {
        val s = run!!.session
        if (!s.mode.isTranslation) return label(title)
        val row = hbox(10)
        row.addView(label(title), lp(0, WRAP, 1f))
        val n = s.gradedTotal
        val what = if (s.mode == Mode.TRANSLATE_WORDS) Fr.count(n, "mot", "mots") else Fr.count(n, "phrase", "phrases")
        row.addView(text(what, 13f, 800).apply {
            pad(10, 3, 10, 3)
            box(C.WHITE, 999, C.NAVY, 2)
        })
        return row
    }

    // ---------- Petite leçon ----------

    private fun showLesson(stop: Stop) {
        page(18) {
            addView(tagRow("PETITE LEÇON", "${stop.line.label} · Arrêt ${stop.number}"), lp())
            addLesson(this, stop, content)
        }
        actionFooter(primaryButton("J'AI COMPRIS →", 60, 20f) { next() })
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
        // Des phrases presque pareilles se comparent mieux l'une sous l'autre.
        val list = (ex.kind != Kind.GAP && !item.isWord) || ex.options.any { it.length > 13 }
        val size = when {
            !list -> 22f
            ex.options.all { it.length <= 22 } -> 20f
            else -> 18f
        }
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
            if (list) {
                val column = vbox(12)
                for (o in ex.options) {
                    val v = OptionView(o, list = true, size = size)
                    column.addView(v, lp())
                    views.add(o to v)
                }
                addView(column, lp())
            } else {
                val grid = vbox(14)
                for (pair in ex.options.chunked(2)) {
                    val row = hbox(14)
                    for (o in pair) {
                        val v = OptionView(o, list = false, size = size)
                        row.addView(v, lp(0, dp(86), 1f))
                        views.add(o to v)
                    }
                    grid.addView(row, lp())
                }
                addView(grid, lp())
            }
        }
        for ((o, v) in views) v.onTap { answerChoice(ex, o, views) }
        setFooter(null)
        demoHook = { answer ->
            // Pour la capture « faux », de préférence un piège où un seul mot change.
            val wrong = ex.options.firstOrNull { it != ex.answer && whyWrong(ex, it)?.startsWith("Un seul mot") == true }
                ?: ex.options.first { it != ex.answer }
            answerChoice(ex, if (answer == "right") ex.answer else wrong, views)
        }
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
            val lines = mutableListOf<Any>()
            whyWrong(ex, chosen)?.let { lines.add(it) }
            lines.add(if (ex.kind == Kind.GAP) "La bonne phrase : **${ex.item.ceb}**" else "La bonne réponse est **${ex.answer}**${dot(ex.answer)}")
            wrongPanel(ex.item, fb, lines)
        }
    }

    /** Pourquoi le choix est faux : le seul mot qui change entre les deux phrases, sinon le sens du mauvais choix. */
    private fun whyWrong(ex: Exercise, chosen: String): String? {
        val item = ex.item
        val near = when (ex.kind) {
            Kind.CHOOSE_CEB -> if (item.isWord) null else Variants.explain(content, chosen, ex.answer)
            Kind.GAP -> Variants.explain(content, "${item.gapBefore.orEmpty()}$chosen${item.gapAfter.orEmpty()}", item.ceb)
            else -> null
        }
        return near ?: meaningOf(ex, chosen)
    }

    /** Le sens de la mauvaise réponse choisie : « balay », c'est la maison. */
    private fun meaningOf(ex: Exercise, chosen: String): String? = when (ex.kind) {
        Kind.CHOOSE_CEB -> content.itemsWithForm(Answers.normalize(chosen)).firstOrNull { it.line == ex.item.line }?.let { meaning(it.ceb, it) }
        Kind.CHOOSE_FR -> content.items(ex.item.line).firstOrNull { it.fr == chosen }?.let { o -> "« ${o.fr} » se dit **${o.ceb}**${dot(o.ceb)}" }
        Kind.GAP -> content.gloss(chosen)?.let { "**$chosen** veut dire « $it »." }
        else -> null
    }

    /** « **balay**, c'est la maison. » */
    private fun meaning(ceb: String, o: Item): String =
        if (o.isWord) "**$ceb**, c'est ${o.fr}${dot(o.fr)}" else "**$ceb** veut dire « ${o.fr} »."

    /** Le point de fin de phrase, sauf si le texte finit déjà par « . », « ? » ou « ! » (pas de « ko.. »). */
    private fun dot(value: String): String = if (value.trimEnd().lastOrNull()?.let { it in ".?!…" } == true) "" else "."

    private inner class OptionView(value: String, list: Boolean, private val size: Float) : LinearLayout(this@SessionActivity) {
        private val mark = icon(R.drawable.ic_check, C.GREEN_TEXT).apply { visibility = View.GONE }
        private val words = text(value, size, 700, line = 1.15f).apply { gravity = Gravity.CENTER }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            pad(12, 10, 12, 10)
            minimumHeight = dp(if (list) 60 else 86)
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

    // ---------- Écrire (en cebuano, ou en français en mode Traduire) ----------

    /**
     * Le champ « Ta réponse ». En cebuano, le clavier ne propose ni ne corrige rien (il prendrait les mots
     * pour du français). Une phrase passe à la ligne, mais la touche Entrée vérifie la réponse.
     */
    private fun answerField(hint: String, long: Boolean, cebuano: Boolean): EditText = EditText(this).apply {
        // Le type de clavier d'abord : il remettrait sinon une police à chasse fixe.
        inputType = if (cebuano) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val size = when {
            long -> 21f
            cebuano -> 26f
            else -> 23f
        }
        typeface = Fonts.get(700, size)
        textSize = size
        setTextColor(C.NAVY)
        setHintTextColor(C.DASH)
        this.hint = hint
        imeOptions = EditorInfo.IME_ACTION_DONE
        if (long) {
            setHorizontallyScrolling(false)
            maxLines = 4
            minHeight = dp(62)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setLineSpacing(0f, 1.15f)
            pad(14, 10, 14, 10)
        } else {
            isSingleLine = true
            pad(16, 0, 16, 0)
        }
        box(C.WHITE, 14, C.NAVY, 3)
    }

    /** La carte de ce qu'il faut traduire, avec l'arrêt d'où ça vient. */
    private fun promptCard(value: String, where: String, word: Boolean): View {
        val card = vbox(6).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            if (word) pad(20, 28, 20, 28) else pad(18, 22, 18, 22)
            box(C.WHITE, 22, C.NAVY, 3, C.NAVY, 6)
            stateListAnimator = null
        }
        val size = when {
            word && value.length <= 10 -> 44f
            word && value.length <= 18 -> 34f
            word -> 26f
            value.length <= 24 -> 30f
            value.length <= 40 -> 27f
            else -> 23f
        }
        card.addView(text(value, size, 800, line = if (word) 1.05f else 1.15f).apply { gravity = Gravity.CENTER }, lp())
        card.addView(text(where, 14f, 600).apply { gravity = Gravity.CENTER }, lp())
        return card
    }

    /** « Arrêt 4 · La maison » pour un mot, « Phrases simples · Arrêt 2 » pour une phrase. */
    private fun whereFrom(item: Item): String {
        val stop = content.stopOf(item)
        return if (item.isWord) "Arrêt ${stop.number} · ${stop.title}" else "${item.line.label} · Arrêt ${stop.number}"
    }

    /** L'écran « écris ta réponse » : la carte, le champ, le petit rappel, et VÉRIFIER en bas. */
    private fun writeScreen(ex: Exercise, title: String, prompt: String, input: EditText, onCheck: () -> Unit) {
        val item = ex.item
        page(if (item.isWord) 22 else 18) {
            addView(exerciseLabel(title), lp())
            addView(promptCard(prompt, whereFrom(item), item.isWord), lp())
            val answer = vbox(8)
            answer.addView(text("Ta réponse", 15f, 700))
            answer.addView(input, if (input.maxLines == 1) lp(MATCH, dp(62)) else lp())
            addView(answer, lp())
            addView(text(
                if (ex.kind == Kind.TRANSLATE_FR) "Pas besoin de mot pour mot : si ce n'est pas ma traduction, c'est toi qui juges."
                else "Les majuscules et les accents ne comptent pas, et une petite faute est permise.",
                14f, 600, C.GRAY, line = 1.35f,
            ).apply { gravity = Gravity.CENTER }, lp())
        }
        val check = primaryButton("VÉRIFIER", 60, 20f) { onCheck() }
        fun enable(on: Boolean) {
            check.isEnabled = on
            check.alpha = if (on) 1f else 0.45f
        }
        enable(false)
        input.onChange { enable(it.isNotBlank() && !busy) }
        input.setOnEditorActionListener { _, _, _ ->
            onCheck()
            true
        }
        actionFooter(check)
        input.requestFocus()
        input.postDelayed({ getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 250)
    }

    private fun showWrite(ex: Exercise) {
        val item = ex.item
        val input = answerField(if (item.isWord) "le mot en cebuano" else "la phrase en cebuano", long = !item.isWord, cebuano = true)
        val title = if (mode.isTranslation) "TRADUIS EN CEBUANO" else "ÉCRIS EN CEBUANO"
        writeScreen(ex, title, item.fr, input) { answerWrite(ex, input) }
        demoHook = { answer ->
            input.setText(demoTyping(item, answer))
            answerWrite(ex, input)
            if (answer == "accepted") input.postDelayed({ acceptHook?.invoke() }, 1200)
        }
    }

    /** Ce que la démo tape : juste, avec une petite faute, avec un mot en moins, ou faux. */
    private fun demoTyping(item: Item, answer: String): String {
        if (item.isWord) return when (answer) {
            "right" -> item.ceb
            "typo" -> item.ceb.dropLast(1)
            else -> "bato"
        }
        val words = item.ceb.split(" ")
        val longest = words.indices.maxByOrNull { words[it].length }!!
        return when (answer) {
            "right" -> item.ceb
            "typo" -> words.mapIndexed { i, w -> if (i == longest) w.removeRange(1, 2) else w }.joinToString(" ")
            "missing", "accepted" -> (words.dropLast(2) + words.last()).joinToString(" ")
            else -> Variants.all(content, item).firstOrNull()?.text ?: words.drop(1).joinToString(" ")
        }
    }

    /** Le champ ne se modifie plus, et le clavier se ferme. */
    private fun lock(input: EditText) {
        input.isFocusable = false
        input.isCursorVisible = false
        input.keyListener = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(body.windowToken, 0)
    }

    /** Le champ ne se modifie plus, mais le clavier reste ouvert pour la question suivante. */
    private fun freeze(input: EditText) {
        input.filters = arrayOf(InputFilter { _, _, _, dest, start, end -> dest.subSequence(start, end) })
        input.isCursorVisible = false
    }

    /** Le champ devient vert (juste) ou rouge (faux). */
    private fun mark(input: EditText, correct: Boolean) {
        input.box(C.WHITE, 14, if (correct) C.GREEN else C.LABEL, 3)
        input.setTextColor(if (correct) C.GREEN_TEXT else C.WRONG_TEXT)
        if (correct) {
            input.pop()
            Buzz.right(this)
        } else {
            input.shake()
            Buzz.wrong(this)
        }
    }

    private fun answerWrite(ex: Exercise, input: EditText) {
        val typed = input.text.toString()
        if (busy || typed.isBlank()) return
        busy = true
        demoHook = null
        val r = run!!
        val item = ex.item
        lock(input)
        val accept = { accepted(ex, input) }
        if (item.isWord) {
            val verdict = Answers.checkWritten(typed, item, content)
            val fb = r.session.submit(ex, verdict != Answers.Verdict.WRONG)
            r.session.next()
            store.save(r.progress)
            mark(input, verdict != Answers.Verdict.WRONG)
            when (verdict) {
                Answers.Verdict.RIGHT -> rightPanel(item, fb)
                Answers.Verdict.TYPO -> rightPanel(item, fb, title = almost(fb), lines = listOf("Ça s'écrit **${item.ceb}**${dot(item.ceb)}"))
                Answers.Verdict.WRONG -> {
                    val lines = mutableListOf<Any>("La bonne réponse est **${item.ceb}**${dot(item.ceb)}")
                    content.itemsWithForm(Answers.normalize(typed)).firstOrNull()?.let { o -> lines.add(0, meaning(typed.trim(), o)) }
                    wrongPanel(item, fb, lines, accept = accept)
                }
            }
            return
        }
        val check = Answers.checkSentence(typed, item, content)
        val fb = r.session.submit(ex, check.verdict != Answers.Verdict.WRONG)
        r.session.next()
        store.save(r.progress)
        mark(input, check.verdict != Answers.Verdict.WRONG)
        when (check.verdict) {
            Answers.Verdict.RIGHT -> {
                // Juste, mais pas tout à fait la phrase apprise (ako au lieu de ko…) : on la montre aussi.
                val other = !Answers.same(typed, check.expected)
                rightPanel(item, fb, lines = if (other) listOf("On peut aussi dire : **${check.expected}**") else emptyList())
            }
            Answers.Verdict.TYPO -> rightPanel(item, fb, title = almost(fb), lines = listOf("Ça s'écrit : **${check.expected}**"))
            Answers.Verdict.WRONG -> wrongPanel(item, fb, sentenceLines(check), accept = accept)
        }
    }

    private fun almost(fb: Feedback) = if (fb.change != null) "Presque parfait ! +${Progress.POINTS_ANSWER} points" else "Presque parfait !"

    /** Ce qui n'allait pas dans la phrase tapée : la bonne phrase, avec les mots qui manquaient surlignés. */
    private fun sentenceLines(check: Answers.SentenceCheck): List<Any> {
        val missing = check.missing
        val wrong = check.wrong
        val extra = check.extra
        val oneMissing = wrong.isEmpty() && extra.isEmpty() && missing.size == 1
        val oneWrong = missing.isEmpty() && extra.isEmpty() && wrong.size == 1
        val oneExtra = missing.isEmpty() && wrong.isEmpty() && extra.size == 1
        val intro = when {
            oneMissing -> "Il manquait un mot :"
            wrong.isEmpty() && extra.isEmpty() && missing.size > 1 -> "Il manquait des mots :"
            oneWrong -> "Un mot n'est pas le bon :"
            oneExtra -> "Il y avait un mot en trop :"
            else -> "La bonne phrase :"
        }
        // Presque tout à revoir : la phrase sans surligner, plus lisible.
        val marked = missing.size + wrong.size
        val highlight = marked <= maxOf(2, check.words.size / 2)
        val out = mutableListOf<Any>(intro, sentence(check, highlight))
        val why = when {
            oneMissing -> missing.single().let { w ->
                val word = Planner.tileText(content, Content.cleanToken(w.word))
                Variants.meaning(content, word)?.let { "${Variants.describe(word, it)}." }
            }
            oneWrong -> wrong.single().let { w ->
                val given = (w.given ?: "").lowercase()
                val right = Planner.tileText(content, Content.cleanToken(w.word))
                val mg = Variants.meaning(content, given)
                val mr = Variants.meaning(content, right)
                when {
                    mr != null && mg != null -> "Tu as écrit ${Variants.describe(given, mg)}. Ici, il faut ${Variants.describe(right, mr)}."
                    mr != null -> "Ici, il faut ${Variants.describe(right, mr)}."
                    else -> null
                }
            }
            oneExtra -> "Le mot **${extra.single()}** est en trop."
            else -> null
        }
        why?.let { out.add(it) }
        return out
    }

    /** La bonne phrase, en gras, avec les mots manquants ou faux sur fond jaune. */
    private fun sentence(check: Answers.SentenceCheck, highlight: Boolean): TextView {
        val sb = SpannableStringBuilder()
        check.words.forEachIndexed { i, w ->
            if (i > 0) sb.append(' ')
            val start = sb.length
            sb.append(w.word)
            if (highlight && (w.mark == Answers.Mark.MISSING || w.mark == Answers.Mark.WRONG)) {
                val core = Content.cleanToken(w.word)
                val at = w.word.indexOf(core)
                if (core.isNotEmpty() && at >= 0) {
                    sb.setSpan(Highlight(C.YELLOW, C.NAVY, dpf(6), dpf(5), dpf(1)), start + at, start + at + core.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return text(sb, 19f, 800, C.WRONG_DEEP, line = 1.4f)
    }

    /** « Ma réponse était juste aussi » : la réponse comptée fausse devient juste. */
    private fun accepted(ex: Exercise, input: EditText) {
        val r = run ?: return
        val fb = r.session.acceptLast() ?: return
        store.save(r.progress)
        mark(input, true)
        val title = if (fb.change != null) "D'accord, c'est juste ! +${Progress.POINTS_ANSWER} points" else "D'accord, c'est juste !"
        rightPanel(ex.item, fb, title = title, lines = listOf("Ta réponse compte comme juste."))
    }

    // ---------- Traduire en français ----------

    private fun showTranslateFr(ex: Exercise) {
        val item = ex.item
        val input = answerField(if (item.isWord) "en français" else "la phrase en français", long = !item.isWord, cebuano = false)
        writeScreen(ex, "TRADUIS EN FRANÇAIS", item.ceb, input) { answerFr(ex, input) }
        demoHook = { answer ->
            input.setText(
                when (answer) {
                    "right" -> item.fr
                    else -> if (item.ceb == "Asa ka nagpuyo?") "Tu habites où ?" else item.fr.split(" ").reversed().joinToString(" ")
                }
            )
            answerFr(ex, input)
        }
    }

    private fun answerFr(ex: Exercise, input: EditText) {
        val typed = input.text.toString()
        if (busy || typed.isBlank()) return
        busy = true
        demoHook = null
        lock(input)
        val verdict = Answers.checkFrench(typed, ex.item)
        if (verdict == Answers.Verdict.WRONG) {
            // Pas mot pour mot : c'est Thomas qui dit si sa phrase veut dire la même chose.
            judgePanel(ex, input)
            return
        }
        val r = run!!
        val fb = r.session.submit(ex, true)
        r.session.next()
        store.save(r.progress)
        mark(input, true)
        rightPanel(ex.item, fb, lines = if (verdict == Answers.Verdict.TYPO) listOf("Ma traduction : « ${ex.item.fr} »") else emptyList())
    }

    private fun judgePanel(ex: Exercise, input: EditText) {
        val item = ex.item
        val col = vbox(8).apply { pad(20, 16, 20, 18) }
        col.addView(text("Ce n'est pas mot pour mot ma traduction :", 16f, 500, line = 1.3f))
        col.addView(text("« ${item.fr} »", if (item.fr.length > 26) 21f else 25f, 800, line = 1.15f))
        col.addView(text("Ta réponse veut dire la même chose ?", 17f, 700, line = 1.3f))
        val row = hbox(12).apply { gravity = Gravity.TOP }
        val buttons = mutableListOf<View>()
        fun judged(same: Boolean) {
            buttons.forEach { it.isClickable = false }
            val r = run!!
            val fb = r.session.submit(ex, same)
            r.session.next()
            store.save(r.progress)
            mark(input, same)
            if (same) rightPanel(item, fb)
            else wrongPanel(item, fb, listOf("La bonne traduction est « ${item.fr} »."))
        }
        val yes = text("OUI, C'EST PAREIL", 17f, 800, C.WHITE).apply {
            gravity = Gravity.CENTER
            minHeight = dp(60)
            pad(8, 0, 8, 0)
            box(C.GREEN, 16, C.NAVY, 3, C.NAVY, 4)
            onTap { judged(true) }
        }
        val no = text("NON", 17f, 800).apply {
            gravity = Gravity.CENTER
            minHeight = dp(56)
            box(C.WHITE, 16, C.NAVY, 3)
            squishy()
            onTap { judged(false) }
        }
        buttons += listOf(yes, no)
        row.addView(yes, lp(0, WRAP, 2f))
        row.addView(no, lp(0, WRAP, 1f))
        col.addView(row, lp().apply { topMargin = dp(6) })
        val wrap = vbox()
        wrap.addView(View(this).apply { setBackgroundColor(C.NAVY) }, lp(MATCH, dp(3)))
        wrap.addView(col, lp())
        setFooter(wrap, C.PALE_YELLOW)
        wrap.slideUp()
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
            placed.forEachIndexed { k, i ->
                // Les étiquettes sont en minuscules : le premier mot placé prend la majuscule.
                val word = if (k == 0) tiles[i].replaceFirstChar { it.uppercase() } else tiles[i]
                val t = text(word, 21f, 700, C.WHITE).apply {
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
        demoHook = { answer ->
            val used = mutableSetOf<Int>()
            val order = item.tokens.mapNotNull { tok -> tiles.indices.firstOrNull { Answers.same(tiles[it], tok) && it !in used }?.also { used.add(it) } }
            placed.clear()
            placed.addAll(if (answer == "right") order else order.take(2))
            refresh()
            if (answer == "right") answerOrder(ex, placed.map { tiles[it] }, answerFlow)
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
            val lines = mutableListOf<Any>()
            // Le bon ordre, mais un mot-piège (magtrabaho pour nagtrabaho) : on explique la différence.
            Variants.explain(content, chosen.joinToString(" "), item.ceb)?.let { lines.add(it) }
            lines.add("La bonne phrase : **${item.ceb}**")
            wrongPanel(item, fb, lines)
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

    // ---------- La ronde des personnes ----------

    /** La présentation d'un tour : ce qu'on va faire, et au 1er tour, comment lire le dessin. */
    private fun showRondeIntro(step: RondeIntroStep) {
        val stop = step.stop
        val kind = stop.lesson!!.ronde!!
        val intro = when {
            kind == RondeKind.PRONOUNS && !step.second ->
                "Une seule phrase, et la personne change à chaque question. Touche la forme longue : **ako**, **kita**, **kami**…\n\n" +
                    "Pour « tu », c'est toujours **ka** après le mot : **ikaw** se met au début de la phrase."
            kind == RondeKind.PRONOUNS ->
                "Les 7 personnes encore, avec un autre mot. Cette fois, écris la forme courte : **Maestro ko.** = Je suis professeur.\n\n" +
                    "**Siya** et **sila** n'ont pas de forme courte : écris-les comme ça."
            !step.second ->
                "Une seule phrase, et la personne change à chaque question. Touche le mot qui se met **avant** la chose : **akong balay** = ma maison."
            else ->
                "Les 7 personnes encore, avec un autre mot. Cette fois, le mot se met **après** la chose : **balay nako** = ma maison. Écris-le !"
        }
        page(18) {
            addView(tagRow("LA RONDE", "Leçon ${stop.number} · ${stop.title}"), lp())
            val top = vbox(10)
            top.addView(text(if (step.second) "2e tour" else "1er tour", 34f, 800, line = 1.0f))
            top.addView(rich(intro, 17f, 500, line = 1.4f))
            addView(top, lp())
            if (!step.second) addView(drawingHelp(kind), lp())
        }
        actionFooter(primaryButton("C'EST PARTI →", 60, 20f) { next() })
    }

    /** Comment lire le dessin : kita (nous avec toi) et kami (nous sans toi). */
    private fun drawingHelp(kind: RondeKind): View {
        val card = vbox(12).apply {
            pad(16, 14, 16, 14)
            box(C.WHITE, 18, C.NAVY, 3)
        }
        card.addView(label("QUI EST DANS « NOUS » ?"))
        for (p in listOf(Person.WE_IN, Person.WE_EX)) {
            val row = hbox(14)
            row.addView(PeopleView(this, p, labels = true))
            val words = vbox(1)
            words.addView(text(kind.form(p, false), 24f, 800, C.LABEL))
            words.addView(text(if (p == Person.WE_IN) "nous, avec toi" else "nous, sans toi", 15f, 700))
            row.addView(words, lp(0, WRAP, 1f))
            card.addView(row, lp())
        }
        card.addView(text("En couleur : ceux qui sont dans « nous ». En pointillés : ceux qui n'y sont pas.", 14f, 600, C.GRAY, line = 1.3f))
        return card
    }

    private fun showRonde(step: RondeStep) {
        val q = step.q
        val s = run!!.session
        val round = q.kind.round(q.second)
        val where = if (s.mode == Mode.RONDE) "${if (q.second) "2e" else "1er"} tour · $round" else "Leçon ${q.stop.number} · $round"
        if (q.tap) {
            val blank = text("", 26f, 800).apply {
                gravity = Gravity.CENTER
                minWidth = dp(96)
                minHeight = dp(50)
                pad(12, 4, 12, 4)
                box(C.CREAM, 12, C.NAVY, 2.5f, dashed = true)
            }
            val chips = linkedMapOf<String, TextView>()
            val grid = vbox(10)
            for (row in q.options.chunked(3)) {
                val line = hbox(10)
                // Le 7e mot, seul sur sa ligne, reste au milieu.
                if (row.size == 1) line.addView(View(this), lp(0, 1, 1f))
                for (o in row) {
                    val chip = text(o, 21f, 800).apply {
                        gravity = Gravity.CENTER
                        box(C.WHITE, 14, C.NAVY, 3, C.NAVY, 4)
                        onTap { answerRondeTap(step, o, blank, chips) }
                    }
                    line.addView(chip, lp(0, dp(58), 1f))
                    chips[o] = chip
                }
                if (row.size == 1) line.addView(View(this), lp(0, 1, 1f))
                grid.addView(line, lp())
            }
            page(18, top = 20) {
                addView(tagRow("LA RONDE", where), lp())
                addView(rondeCard(q, blank), lp())
                addView(grid, lp())
            }
            setFooter(null)
            demoHook = { answer -> answerRondeTap(step, if (answer == "right") q.answer else demoWrong(q), blank, chips) }
            return
        }
        val input = answerField("?", long = false, cebuano = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(124)
            minHeight = dp(54)
            pad(12, 0, 12, 0)
        }
        lateinit var check: TextView
        check = primaryButton("VÉRIFIER", 60, 20f) { answerRondeWrite(step, input, check) }
        page(18, top = 20) {
            addView(tagRow("LA RONDE", where), lp())
            addView(rondeCard(q, input), lp())
            val hint = if (q.kind == RondeKind.PRONOUNS) "Écris la forme courte quand elle existe." else "Écris le mot qui se met après la chose."
            addView(text(hint, 15f, 600, C.GRAY, line = 1.35f).apply { gravity = Gravity.CENTER }, lp())
        }
        fun enable(on: Boolean) {
            check.isEnabled = on
            check.alpha = if (on) 1f else 0.45f
        }
        enable(false)
        input.onChange { enable(it.isNotBlank() && !busy) }
        input.setOnEditorActionListener { _, _, _ ->
            answerRondeWrite(step, input, check)
            true
        }
        actionFooter(check)
        input.requestFocus()
        input.postDelayed({ getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 250)
        demoHook = { answer ->
            input.setText(
                when (answer) {
                    "right" -> q.answer
                    // L'autre forme de la même personne (ako pour ko), ou le mot qui va avant la chose (akong pour nako).
                    "long" -> q.person.long
                    "short" -> q.person.afterShort ?: q.answer
                    "position" -> q.person.before
                    else -> demoWrong(q)
                }
            )
            answerRondeWrite(step, input, check)
        }
    }

    /** La carte de la question : à qui on pense (avec le dessin), puis la phrase à trou et sa traduction. */
    private fun rondeCard(q: RondeQuestion, gap: View): View {
        val card = vbox().apply {
            pad(3, 3, 3, 3)
            box(C.WHITE, 22, C.NAVY, 3, C.NAVY, 6)
            stateListAnimator = null
        }
        val r = dpf(19)
        val band = hbox(10).apply {
            pad(14, 10, 10, 10)
            background = GradientDrawable().apply {
                setColor(C.PALE_YELLOW)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val who = vbox(2)
        who.addView(label(q.kind.question, 12f))
        who.addView(text(keep(q.kind.label(q.person)), 22f, 800, line = 1.05f))
        band.addView(who, lp(0, WRAP, 1f))
        band.addView(PeopleView(this, q.person, labels = true))
        card.addView(band, lp())
        card.addView(View(this).apply { setBackgroundColor(C.NAVY) }, lp(MATCH, dp(3)))
        val bottom = vbox(10).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            pad(12, 16, 12, 16)
        }
        // La phrase : chaque mot se touche pour voir son sens, et le trou est au milieu.
        val size = if (q.before.length + q.after.length > 12) 26f else 30f
        val flow = FlowLayout(this, dp(9), dp(8), centered = true)
        fun words(value: String) {
            for (w in value.split(" ").filter { it.isNotEmpty() }) {
                val t = text(w, size, 800)
                t.pad(0, 2, 0, 3)
                t.background = DottedUnderline(C.DASH, dpf(2))
                t.onTap {
                    val clean = Content.cleanToken(w)
                    Gloss.show(t, clean, content.gloss(clean))
                }
                flow.addView(t)
            }
        }
        words(q.before)
        flow.addView(gap)
        words(q.after)
        bottom.addView(flow, lp())
        bottom.addView(text(keep(q.french), 16f, 600, C.MUTED, line = 1.25f).apply { gravity = Gravity.CENTER }, lp())
        card.addView(bottom, lp())
        return card
    }

    /**
     * « nous (sans toi) », « à nous », « tu » : la ligne ne se coupe jamais dans une parenthèse,
     * entre « à » et son mot, ni juste après un guillemet.
     */
    private fun keep(value: String) = value
        .replace(Regex("\\([^)]*\\)")) { it.value.replace(' ', '\u00A0') }
        .replace("« ", "«\u00A0")
        .replace(" »", "\u00A0»")
        .replace("à ", "à\u00A0")

    /** Pour la démo : la réponse fausse la plus tentante (kita pour kami, ka pour ko…). */
    private fun demoWrong(q: RondeQuestion): String {
        val other = when (q.person) {
            Person.WE_EX -> Person.WE_IN
            Person.WE_IN -> Person.WE_EX
            Person.ME -> Person.YOU
            Person.YOU -> Person.ME
            Person.YOU_PL -> Person.YOU
            Person.HE -> Person.THEY
            Person.THEY -> Person.HE
        }
        return q.kind.form(other, q.second)
    }

    private fun rondeRightTitle() = "${praise()} +${Progress.POINTS_ANSWER} points"

    private fun answerRondeTap(step: RondeStep, picked: String, blank: TextView, chips: Map<String, TextView>) {
        if (busy) return
        busy = true
        demoHook = null
        val r = run!!
        val q = step.q
        val check = r.session.submitRonde(step, picked)
        r.session.next()
        store.save(r.progress)
        for ((o, v) in chips) {
            v.isClickable = false
            v.stateListAnimator = null
            v.translationX = 0f
            v.translationY = 0f
            when (o) {
                q.answer -> {
                    v.box(C.GREEN_BG, 14, C.GREEN, 3)
                    v.setTextColor(C.GREEN_TEXT)
                }
                picked -> {
                    v.box(C.WRONG_BG, 14, C.LABEL, 3)
                    v.setTextColor(C.WRONG_TEXT)
                }
                else -> v.alpha = 0.5f
            }
        }
        // Le trou se remplit avec la bonne réponse : la phrase est complète.
        blank.text = q.answer
        blank.box(C.GREEN_BG, 12, C.GREEN, 3)
        blank.setTextColor(C.GREEN_TEXT)
        if (check.correct) {
            chips[picked]?.pop()
            blank.pop()
            Buzz.right(this)
            if (check.note == null) advanceSoon() else panel(true, rondeRightTitle(), listOf(keep(check.note!!)))
        } else {
            chips[picked]?.shake()
            Buzz.wrong(this)
            panel(false, "Pas tout à fait…", listOfNotNull(check.note?.let(::keep)))
        }
    }

    private fun answerRondeWrite(step: RondeStep, input: EditText, check: View) {
        val typed = input.text.toString()
        if (busy || typed.isBlank()) return
        busy = true
        demoHook = null
        val r = run!!
        val q = step.q
        val verdict = r.session.submitRonde(step, typed)
        r.session.next()
        store.save(r.progress)
        if (verdict.correct && verdict.note == null) {
            freeze(input)
            mark(input, true)
            check.isEnabled = false
            check.alpha = 0.45f
            advanceSoon()
            return
        }
        lock(input)
        mark(input, verdict.correct)
        if (verdict.correct) panel(true, rondeRightTitle(), listOf(keep(verdict.note!!)))
        else panel(false, "Pas tout à fait…", listOfNotNull(verdict.note?.let(::keep)) + "**${q.sentence}** = ${keep(q.french)}")
    }

    /** Après une bonne réponse sans rien à expliquer : la question suivante arrive toute seule. */
    private fun advanceSoon() {
        if (Store.demo) return
        val token = ++advanceToken
        body.postDelayed({ if (token == advanceToken && !isFinishing && !isDestroyed) showCurrent() }, ADVANCE_MS)
    }

    /** La fin de la ronde : les points, et le tableau des 7 personnes, les erreurs en rouge. */
    private fun showRondeEnd() {
        val r = run!!
        val s = r.session
        val outcome = r.outcome ?: r.progress.finishPractice(content, r.day, s.correctFirst).also {
            r.outcome = it
            store.save(r.progress)
            Buzz.win(this)
        }
        Gloss.dismiss()
        val stop = s.stop!!
        val kind = stop.lesson!!.ronde!!
        endHeader("LEÇON ${stop.number} · LA RONDE DES PERSONNES", "C'EST FINI !", s)
        page(16, top = 20) {
            val row = hbox(12)
            row.addView(stat("${s.correctFirst} / ${s.gradedTotal}", "bonnes réponses"), lp(0, WRAP, 1f))
            row.addView(stat("+${outcome.points}", "points gagnés"), lp(0, WRAP, 1f))
            addView(row, lp())
            for (b in outcome.newBadges) addView(badgeCard(b), lp())
            if (outcome.completedStops.isNotEmpty()) addView(stopsCard(outcome.completedStops), lp())
            addView(rondeTable(s, kind), lp())
            val notes = vbox(8)
            if (s.rondeAnswers.any { !it.check.correct }) {
                notes.addView(text("Les personnes en rouge reviendront plus souvent, à la fin de tes séances du soir.", 14f, 600, C.GRAY, line = 1.35f))
            }
            if (kind == RondeKind.PRONOUNS) {
                notes.addView(rich("Pour « tu », après le mot, c'est toujours **ka** : **ikaw** se met au début de la phrase.", 14f, 500, C.GRAY))
            }
            addView(notes, lp())
        }
        actionFooter(
            navyButton("RETOUR À LA LEÇON", 58, 18f) { goHome() },
            outlineButton("Encore une ronde") { startAgain(s) },
        )
    }

    /** « Ton tableau » : une ligne par personne, une colonne par tour (formes longues et courtes, avant et après). */
    private fun rondeTable(s: Session, kind: RondeKind): View {
        val col = vbox(8)
        col.addView(text("TON TABLEAU", 16f, 800))
        val card = vbox().apply {
            pad(3, 3, 3, 3)
            box(C.WHITE, 18, C.NAVY, 3)
        }
        val r = dpf(15)
        val head = hbox(6).apply {
            pad(12, 8, 12, 8)
            background = GradientDrawable().apply {
                setColor(C.NAVY)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val first = dp(100)
        head.addView(View(this), LinearLayout.LayoutParams(first, 1))
        for (c in listOf(kind.columns.first, kind.columns.second)) {
            head.addView(text(c, 12f, 800, C.YELLOW, 0.06f).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
        }
        card.addView(head, lp())
        Person.values().forEachIndexed { i, p ->
            if (i > 0) card.addView(dashes())
            val row = hbox(6).apply { pad(12, 6, 12, 6) }
            row.addView(text(keep(kind.label(p)), 13f, 700, line = 1.15f), LinearLayout.LayoutParams(first, WRAP))
            for (second in listOf(false, true)) row.addView(rondeCell(s, p, second, kind), lp(0, WRAP, 1f))
            card.addView(row, lp())
        }
        col.addView(card, lp())
        return col
    }

    /** Une case du tableau : la bonne forme, en vert (juste) ou en rouge avec ce qui avait été mis. */
    private fun rondeCell(s: Session, p: Person, second: Boolean, kind: RondeKind): View {
        val a = s.rondeAnswers.lastOrNull { it.q.person == p && it.q.second == second }
        val ok = a?.check?.correct
        val cell = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            pad(6, 5, 6, 5)
            when (ok) {
                true -> box(C.GREEN_BG, 10)
                false -> box(C.WRONG_BG, 10)
                null -> {}
            }
        }
        val color = when (ok) {
            true -> C.GREEN_TEXT
            false -> C.WRONG_TEXT
            null -> C.NAVY
        }
        cell.addView(text(kind.form(p, second), 17f, 800, color).apply { gravity = Gravity.CENTER }, lp())
        if (ok == false) {
            val given = a!!.given.trim().lowercase().take(14)
            cell.addView(text("tu as mis $given", 11f, 600, C.WRONG_TEXT, line = 1.1f).apply { gravity = Gravity.CENTER }, lp())
        }
        return cell
    }

    // ---------- Le panneau du bas, après une réponse ----------

    private fun praise(): String = listOf("Maayo kaayo !", "Sakto !", "Nindot !")[(run?.session?.correctFirst ?: 0) % 3]

    private fun rightPanel(item: Item, fb: Feedback, title: String? = null, lines: List<Any> = emptyList(), due: Boolean = true) {
        val graded = fb.change != null
        val all = lines.toMutableList()
        if (due) fb.change?.let { all.add(Plan.dueText(it, item.isWord)) }
        if (!graded && lines.isEmpty()) all.add("Cette fois, c'est la bonne réponse.")
        panel(true, title ?: if (graded) "${praise()} +${Progress.POINTS_ANSWER} points" else "Sakto !", all)
    }

    private fun wrongPanel(item: Item, fb: Feedback, lines: List<Any>, several: Boolean = false, accept: (() -> Unit)? = null) {
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
        val back = if (several) "reviendront" else "reviendra"
        // En mode Traduire, l'écran de fin dit ce qui est à revoir.
        val note = when {
            mode.isTranslation -> null
            fb.retry && mode == Mode.LESSON -> "Pas grave : $what $back un peu plus tard."
            fb.retry -> "Pas grave : $what $back un peu plus tard dans la séance."
            mode == Mode.LESSON -> "Pas grave : tu $pronoun reverras dans tes séances."
            else -> "Pas grave : tu $pronoun reverras à la prochaine séance."
        }
        panel(false, "Pas tout à fait…", if (note == null) lines else lines + note, accept)
    }

    /** [lines] : du texte (les ** en gras) ou une vue, comme la phrase corrigée. */
    private fun panel(right: Boolean, title: String, lines: List<Any>, accept: (() -> Unit)? = null) {
        val col = vbox(if (right) 8 else 10).apply { pad(20, 16, 20, if (accept != null) 6 else 18) }
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
            if (l is View) col.addView(l, lp())
            else col.addView(rich(l.toString(), if (i == 0 && !right) 16f else 15f, 500, if (right) C.GREEN_DEEP else C.WRONG_DEEP))
        }
        val next = if (right) primaryButton("CONTINUER", 58, 19f) { showCurrent() } else navyButton("CONTINUER", 56, 18f) { showCurrent() }
        col.addView(next, lp().apply { topMargin = dp(8) })
        if (accept != null) {
            val link = text("Ma réponse était juste aussi", 15f, 700, C.WRONG_DEEP).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                onTap { accept() }
            }
            col.addView(link, lp())
            acceptHook = accept
        }
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
        endHeader(Plan.sessionTitle(r.slot, s.line).uppercase(), "SÉANCE FINIE !", s)

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
            if (n > 0) addView(itemList(newTitle(s.newItems), s.newItems), lp())
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

    /** Le haut jaune de l'écran de fin : ce qui est fini, et un mot selon les bonnes réponses. */
    private fun endHeader(kicker: String, title: String, s: Session) {
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
        top.addView(text(kicker, 14f, 700, spacing = 0.08f).apply { gravity = Gravity.CENTER }, lp())
        top.addView(text(title, 42f, 800, line = 0.95f).apply { gravity = Gravity.CENTER }, lp())
        top.addView(text(hello, 19f, 700).apply { gravity = Gravity.CENTER }, lp())
        val h = vbox()
        h.addView(top, lp())
        h.addView(stripes())
        setHead(h, line = false)
    }

    private fun newTitle(items: List<Item>) = when {
        items.all { it.isWord } -> "TES NOUVEAUX MOTS"
        items.none { it.isWord } -> "TES NOUVELLES PHRASES"
        else -> "TES NOUVEAUTÉS"
    }

    /** Une liste de mots (cebuano à gauche, français à droite) ou de phrases (l'une sous l'autre). */
    private fun itemList(title: String, items: List<Item>, note: String? = null): View {
        val list = vbox(8)
        list.addView(text(title, 16f, 800))
        items.forEachIndexed { i, item ->
            if (i > 0) list.addView(dashes(C.DASH, 2))
            if (item.isWord) {
                val row = hbox(12)
                row.addView(text(item.ceb, 17f, 800), lp(0, WRAP, 1f))
                row.addView(text(item.fr, 17f, 500).apply { gravity = Gravity.END })
                list.addView(row, lp())
            } else {
                val one = vbox(1)
                one.addView(text(item.ceb, 17f, 800, line = 1.2f))
                one.addView(text(item.fr, 15f, 500, line = 1.2f))
                list.addView(one, lp())
            }
        }
        note?.let { list.addView(text(it, 14f, 600, C.GRAY, line = 1.35f).apply { pad(0, 4, 0, 0) }) }
        return list
    }

    // ---------- Fin d'une traduction ou d'un entraînement ----------

    private fun showPracticeEnd() {
        val r = run!!
        val s = r.session
        val outcome = r.outcome ?: r.progress.finishPractice(content, r.day, s.correctFirst).also {
            r.outcome = it
            store.save(r.progress)
            Buzz.win(this)
        }
        Gloss.dismiss()
        val total = s.gradedTotal
        val kicker = when (s.mode) {
            Mode.LESSON -> practiceWhere(s.stop) + if (s.newItems.isNotEmpty()) "DÉCOUVERTE" else "ENTRAÎNEMENT"
            Mode.TRANSLATE_WORDS -> "TRADUCTION · ${Fr.count(total, "MOT", "MOTS")}"
            else -> "TRADUCTION · ${Fr.count(total, "PHRASE", "PHRASES")}"
        }
        endHeader(kicker, "C'EST FINI !", s)

        page(16, top = 20) {
            val row = hbox(12)
            row.addView(stat("${s.correctFirst} / $total", "bonnes réponses"), lp(0, WRAP, 1f))
            row.addView(stat("+${outcome.points}", "points gagnés"), lp(0, WRAP, 1f))
            addView(row, lp())
            for (b in outcome.newBadges) addView(badgeCard(b), lp())
            if (outcome.completedStops.isNotEmpty()) addView(stopsCard(outcome.completedStops), lp())
            if (s.missed.isNotEmpty()) addView(itemList("À REVOIR", s.missed, reviewNote(s.missed)), lp())
            if (s.newItems.isNotEmpty()) addView(itemList(newTitle(s.newItems), s.newItems), lp())
        }

        val again = when (s.mode) {
            Mode.LESSON -> "Encore ${Fr.count(Planner.EXERCISES, "question", "questions")}"
            Mode.TRANSLATE_WORDS -> "Encore ${Fr.count(total, "mot", "mots")}"
            else -> "Encore ${Fr.count(total, "phrase", "phrases")}"
        }
        actionFooter(
            navyButton(
                when {
                    s.mode != Mode.LESSON -> "RETOUR À L'ACCUEIL"
                    s.stop?.lesson != null -> "RETOUR À LA LEÇON"
                    else -> "RETOUR À L'ARRÊT"
                },
                58, 18f,
            ) { goHome() },
            outlineButton(again) { startAgain(s) },
        )
    }

    /** « LEÇON 7 · » pour un arrêt avec sa leçon, sinon « ARRÊT 5 · » (des mots ou des phrases simples). */
    private fun practiceWhere(stop: Stop?): String = when {
        stop == null -> ""
        stop.lesson != null -> "LEÇON ${stop.number} · "
        else -> "ARRÊT ${stop.number} · "
    }

    /** « Ces 2 phrases reviendront plus souvent dans tes séances. » */
    private fun reviewNote(missed: List<Item>): String {
        val n = missed.size
        val words = missed.all { it.isWord }
        return when {
            n == 1 && words -> "Ce mot reviendra plus souvent dans tes séances."
            n == 1 -> "Cette phrase reviendra plus souvent dans tes séances."
            words -> "Ces $n mots reviendront plus souvent dans tes séances."
            else -> "Ces $n phrases reviendront plus souvent dans tes séances."
        }
    }

    /** « Encore 10 mots » : une nouvelle traduction (ou un nouvel entraînement), avec d'autres mots. */
    private fun startAgain(s: Session) {
        Data.running = null
        val i = Intent(this, SessionActivity::class.java)
        when (s.mode) {
            Mode.LESSON -> i.putExtra(EXTRA_PRACTICE, s.stop!!.key)
            Mode.RONDE -> i.putExtra(EXTRA_RONDE, s.stop!!.key)
            else -> i.putExtra(EXTRA_TRANSLATE, if (s.mode == Mode.TRANSLATE_WORDS) "WORDS" else "PHRASES")
        }
        startActivity(i)
        finish()
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
        val random = Random(5)
        if (kind.startsWith("RONDE")) {
            startRondeDemo(kind, pr, today, random)
            if (answer != null) body.postDelayed({ demoHook?.invoke(answer) }, 700)
            return
        }
        if (kind.startsWith("TR_") || kind == "PRACTICE" || kind == "DISCOVER_END") {
            startPracticeDemo(kind, pr, today, random)
            if (answer != null) body.postDelayed({ demoHook?.invoke(answer) }, 700)
            return
        }
        val line = when (kind) {
            "LESSON", "GAP" -> Line.COMPLEX
            "ORDER", "CHOOSE_PHRASE", "WRITE_PHRASE" -> Line.SIMPLE
            else -> Line.VOCAB
        }
        val slot = when {
            kind == "END" -> Slot.SOIR
            line == Line.VOCAB -> Slot.MATIN
            line == Line.SIMPLE -> Slot.MIDI
            else -> Slot.SOIR
        }
        val built = Planner.build(content, pr, slot, line, today, random)
        val first: Step? = when (kind) {
            "END" -> null
            "LESSON" -> built.steps.firstOrNull { it is LessonStep } ?: LessonStep(content.stops(Line.COMPLEX).first { it.lesson != null })
            "NEW" -> built.steps.firstOrNull { it is NewStep } ?: NewStep(content.items(Line.VOCAB).first { !pr.isIntroduced(it) })
            "CHOOSE_PHRASE" -> Planner.exercise(content, Kind.CHOOSE_CEB, demoItem("Gigutom ko."), true, random)
            "WRITE_PHRASE" -> Planner.exercise(content, Kind.WRITE, demoItem("Asa ka nagpuyo?"), true, random)
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

    /** Démo d'une traduction (un mot, une phrase, vers le français, l'écran de fin) ou d'un entraînement. */
    private fun startPracticeDemo(kind: String, pr: Progress, today: Long, random: Random) {
        val session = if (kind == "PRACTICE") {
            // La leçon la plus avancée : ses phrases sont déjà vues, on tombe sur une question.
            val stop = content.lessons.maxByOrNull { st -> st.items.count { pr.isIntroduced(it) } }!!
            Planner.buildLessonPractice(content, pr, stop, today, random)
        } else if (kind == "DISCOVER_END") {
            // Découvrir un arrêt de mots plus loin que le jeepney.
            Planner.buildLessonPractice(content, pr, Data.demoFutureStop(content, pr), today, random)
        } else {
            val built = Planner.buildTranslation(content, pr, kind == "TR_WORD", today, random)!!
            val first = when (kind) {
                "TR_WORD" -> Planner.exercise(content, Kind.WRITE, demoItem("kusina"), true, random)
                "TR_PHRASE" -> Planner.exercise(content, Kind.WRITE, demoItem("Nagtrabaho ko uban sa mga bata."), true, random)
                "TR_INVERSE" -> Planner.exercise(content, Kind.TRANSLATE_FR, demoItem("Asa ka nagpuyo?"), true, random)
                else -> null
            }
            if (first != null) {
                built.steps.removeAll { (it is Exercise && it.item == first.item) || (it is NewStep && it.item == first.item) }
                built.steps.add(0, first)
                // Toujours 10 questions : on enlève les dernières (et la carte d'un mot nouveau enlevé).
                while (built.gradedTotal > Planner.EXERCISES) {
                    val last = built.steps.indexOfLast { it is Exercise }
                    val item = (built.steps.removeAt(last) as Exercise).item
                    built.steps.removeAll { it is NewStep && it.item == item }
                }
            }
            built
        }
        run = Running(null, false, today, pr, session)
        if (kind == "TR_END" || kind == "DISCOVER_END") {
            var n = 0
            while (!session.finished) {
                val step = session.current!!
                session.onShown(step)
                if (step is Exercise) session.submit(step, n++ !in setOf(1, 5))
                session.next()
            }
        }
        showCurrent()
    }

    /**
     * Démo de la ronde : une question de la leçon 1 ou 4 (à toucher ou à écrire), la présentation d'un tour,
     * l'écran de fin (avec 2 erreurs), ou la petite ronde à la fin d'une séance du soir.
     */
    private fun startRondeDemo(kind: String, pr: Progress, today: Long, random: Random) {
        if (kind == "RONDE_EVENING") {
            startEveningRondeDemo(pr, today, random)
            return
        }
        val stop = content.stopByKey(if (kind.startsWith("RONDE_POSS")) "c4" else "c1")!!
        val s = Ronde.full(content, pr, stop, today, random)
        val second = kind.endsWith("_WRITE") || kind == "RONDE_INTRO2"
        // La question montrée, en premier dans son tour : « nous (sans toi) », « tu », ou « je » au 2e tour.
        val person = when (kind) {
            "RONDE_YOU" -> Person.YOU
            "RONDE_WRITE", "RONDE_POSS_WRITE" -> Person.ME
            else -> Person.WE_EX
        }
        val target = s.steps.first { it is RondeStep && it.q.second == second && it.q.person == person }
        s.steps.remove(target)
        s.steps.add(s.steps.indexOfFirst { it is RondeIntroStep && it.second == second } + 1, target)
        val stopAt: Step? = when (kind) {
            "RONDE_INTRO", "RONDE_INTRO2" -> s.steps.first { it is RondeIntroStep && it.second == second }
            "RONDE_END" -> null
            else -> target
        }
        // Les questions d'avant sont faites. Pour l'écran de fin, deux erreurs : kita pour kami, et ka pour mo.
        while (!s.finished && s.current !== stopAt) {
            val step = s.current!!
            s.onShown(step)
            if (step is RondeStep) {
                val q = step.q
                val wrong = kind == "RONDE_END" && ((q.person == Person.WE_EX && !q.second) || (q.person == Person.YOU_PL && q.second))
                s.submitRonde(step, if (wrong) demoWrong(q) else q.answer)
            }
            s.next()
        }
        run = Running(null, false, today, pr, s)
        showCurrent()
    }

    /** La petite ronde de la leçon 1 à la fin d'une séance du soir, après 7 questions. */
    private fun startEveningRondeDemo(pr: Progress, today: Long, random: Random) {
        val built = Planner.build(content, pr, Slot.SOIR, Line.COMPLEX, today, random)
        val steps = built.steps.filter { it !is RondeStep }.toMutableList()
        while (steps.count { it is Exercise && it.graded } > Planner.EXERCISES - Ronde.MINI) {
            steps.removeAt(steps.indexOfLast { it is Exercise && it.graded })
        }
        val ronde = Ronde.mini(content, pr, content.stopByKey("c1")!!, random)
        steps.addAll(ronde)
        val s = Session(content, pr, Slot.SOIR, Line.COMPLEX, today, steps, random)
        while (!s.finished && s.current !== ronde.first()) {
            val step = s.current!!
            s.onShown(step)
            if (step is Exercise) s.submit(step, true)
            s.next()
        }
        run = Running(Slot.SOIR, false, today, pr, s)
        showCurrent()
    }

    private fun demoItem(ceb: String): Item = content.items.first { it.ceb == ceb }

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
        /** « WORDS » ou « PHRASES » : une traduction de 10 mots ou de 10 phrases. */
        const val EXTRA_TRANSLATE = "translate"
        /** La clé d'un arrêt (« c7 », « v5 ») : s'entraîner sur sa leçon, ou découvrir ses mots avant le jeepney. */
        const val EXTRA_PRACTICE = "practice"
        /** La clé d'une leçon avec sa ronde des personnes (« c1 », « c4 »). */
        const val EXTRA_RONDE = "ronde"
        /** Après une bonne réponse, la ronde passe seule à la question suivante. */
        private const val ADVANCE_MS = 1100L
        const val EXTRA_DEMO_STEP = "demoStep"
        const val EXTRA_DEMO_ANSWER = "demoAnswer"
        private const val M_IDLE = 0
        private const val M_SELECTED = 1
        private const val M_DONE = 2
        private const val M_WRONG = 3
    }
}
