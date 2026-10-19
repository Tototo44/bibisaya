package com.thomas.bibisaya

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.thomas.bibisaya.engine.Answers
import com.thomas.bibisaya.engine.Badge
import com.thomas.bibisaya.engine.Content
import com.thomas.bibisaya.engine.Item
import com.thomas.bibisaya.engine.Line
import com.thomas.bibisaya.engine.Plan
import com.thomas.bibisaya.engine.Progress
import com.thomas.bibisaya.engine.Slot
import com.thomas.bibisaya.engine.SlotState
import com.thomas.bibisaya.engine.Stop
import com.thomas.bibisaya.engine.Times

/** L'accueil, avec ses 4 onglets : Accueil, Route, Lexique, Badges. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: Content
    private lateinit var progress: Progress
    private lateinit var page: FrameLayout
    private val navItems = mutableListOf<Pair<ImageView, TextView>>()
    private var tab = TAB_HOME
    private var routeLine = Line.VOCAB
    private var lexFilter = 0
    private var lexQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_DEMO, false)) Store.demo = true
        Fonts.init(this)
        store = Store(this)
        content = Data.content(this)
        if (Store.demo && store.progress().items.isEmpty()) Data.fillDemo(store, content)
        if (Store.demo && savedInstanceState == null) openDemo()
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra(EXTRA_TAB, TAB_HOME)
        routeLine = Line.values()[savedInstanceState?.getInt("routeLine") ?: intent.getIntExtra(EXTRA_LINE, 0)]

        val root = vbox()
        val top = View(this).apply { setBackgroundColor(C.YELLOW) }
        root.addView(top, lp(MATCH, 0))
        page = FrameLayout(this)
        root.addView(page, lp(MATCH, 0, 1f))
        val nav = buildNav()
        root.addView(nav, lp())
        setContentView(root)
        edgeToEdge(root, top, nav, lightNavigation = false)

        Notify.ensureChannel(this)
        askNotifications()
    }

    override fun onResume() {
        super.onResume()
        progress = store.progress()
        render()
        Notify.schedule(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) tab = intent.getIntExtra(EXTRA_TAB, TAB_HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("routeLine", routeLine.ordinal)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != TAB_HOME) select(TAB_HOME) else super.onBackPressed()
    }

    /** Mode démo (captures d'écran) : ouvre directement un exercice ou les réglages. */
    private fun openDemo() {
        val step = intent.getStringExtra(SessionActivity.EXTRA_DEMO_STEP) ?: return
        if (step == "SETTINGS") {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (step == "NOTIF") {
            Notify.show(this, Slot.SOIR)
            return
        }
        startActivity(
            Intent(this, SessionActivity::class.java)
                .putExtra(SessionActivity.EXTRA_DEMO_STEP, step)
                .putExtra(SessionActivity.EXTRA_DEMO_ANSWER, intent.getStringExtra(SessionActivity.EXTRA_DEMO_ANSWER))
        )
    }

    /** Sur Android 13 et plus, il faut demander la permission d'afficher les rappels. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33 || Store.demo || store.askedNotifications) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        store.askedNotifications = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun select(t: Int) {
        tab = t
        render()
    }

    private fun render() {
        Gloss.dismiss()
        (currentFocus ?: page).let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
        page.removeAllViews()
        page.addView(
            when (tab) {
                TAB_ROUTE -> route()
                TAB_LEXICON -> lexicon()
                TAB_BADGES -> badges()
                else -> home()
            }
        )
        navItems.forEachIndexed { i, (icon, label) ->
            val on = i == tab
            val color = if (on) C.YELLOW else C.WHITE
            icon.setColorFilter(color)
            label.setTextColor(color)
            label.typeface = Fonts.get(if (on) 800 else 600, 13f)
        }
    }

    // ---------- La barre du bas ----------

    private fun buildNav(): LinearLayout {
        val nav = hbox().apply {
            setBackgroundColor(C.NAVY)
            pad(8, 10, 8, 14)
        }
        val items = listOf(
            R.drawable.ic_home to "Accueil",
            R.drawable.ic_route to "Route",
            R.drawable.ic_book to "Lexique",
            R.drawable.ic_medal to "Badges",
        )
        items.forEachIndexed { i, (res, name) ->
            val cell = vbox(3).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                pad(0, 2, 0, 2)
                contentDescription = name
                onTap { select(i) }
            }
            val icon = cell.addIcon(res, 22, C.WHITE)
            val label = text(name, 13f, 600, C.WHITE)
            cell.addView(label)
            nav.addView(cell, lp(0, WRAP, 1f))
            navItems.add(icon to label)
        }
        return nav
    }

    private fun header(gap: Int): LinearLayout = vbox(gap).apply {
        setBackgroundColor(C.YELLOW)
        pad(20, 22, 20, 16)
    }

    private fun screen(header: View, body: View): LinearLayout = vbox().apply {
        addView(header, lp())
        addView(stripes())
        addView(body, lp(MATCH, 0, 1f))
    }

    private fun scroll(body: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    // ---------- Accueil ----------

    private fun home(): View {
        val today = Store.today()
        val minute = Store.minuteOfDay()
        val times = store.times

        val h = header(14)
        val row = hbox().apply { gravity = Gravity.TOP }
        val hello = vbox(2)
        hello.addView(text(Plan.greeting(minute), 15f, 700))
        hello.addView(text("THOMAS !", 32f, 800, line = 0.95f))
        row.addView(hello, lp(0, WRAP, 1f))
        row.addView(squareButton(R.drawable.ic_settings, "Réglages", shadow = true) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        h.addView(row, lp())
        val pills = hbox(10)
        val streak = progress.currentStreak(today)
        val streakPill = hbox(6).apply {
            pad(14, 6, 14, 6)
            box(C.RED, 999, C.NAVY, 3, C.NAVY, 3)
            stateListAnimator = null
        }
        streakPill.addIcon(R.drawable.ic_flame, 16, C.WHITE)
        streakPill.addView(text(if (streak == 0) "Pas encore de série" else "${Fr.count(streak, "jour", "jours")} d'affilée", 15f, 800, C.WHITE))
        pills.addView(streakPill)
        pills.addView(text("${progress.points.fr()} points", 15f, 800).apply {
            pad(14, 6, 14, 6)
            box(C.WHITE, 999, C.NAVY, 3)
        })
        h.addView(pills, lp(WRAP, WRAP))

        val body = vbox(12).apply { pad(20, 14, 20, 22) }

        // Le mot du jour
        val word = Plan.wordOfTheDay(content, progress, today)
        val wod = hbox(12).apply {
            pad(18, 14, 18, 14)
            box(C.NAVY, 18, shadow = C.RED, shadowOffset = 5)
            onTap { wordSheet(word) }
        }
        val wodText = vbox(2)
        wodText.addView(label("MOT DU JOUR", 12f, C.YELLOW))
        wodText.addView(text(word.ceb.capitalized(), 28f, 800, C.WHITE))
        wodText.addView(text(word.fr.capitalized(), 15f, 500, C.WHITE))
        wod.addView(wodText, lp(0, WRAP, 1f))
        wod.addIcon(R.drawable.ic_star, 40, C.YELLOW)
        body.addView(wod, lp())

        // Les 3 séances du jour
        val done = progress.slotsDone(today).size
        val todayRow = hbox()
        todayRow.addView(text("AUJOURD'HUI", 18f, 800), lp(0, WRAP, 1f))
        todayRow.addView(text(if (done == 3) "3 séances sur 3 !" else "$done séance${if (done > 1) "s" else ""} sur 3", 14f, 700))
        body.addView(todayRow, lp().apply { topMargin = dp(4) })
        val slots = vbox(10)
        for (slot in Slot.values()) slots.addView(slotRow(slot, today, minute, times), lp())
        if (done == 3) {
            slots.addView(outlineButton("Encore une séance (en bonus)") { startSession(null) }, lp())
        }
        body.addView(slots, lp())

        // Les 3 lignes
        body.addView(linesCard(), lp().apply { topMargin = dp(4) })
        return screen(h, scroll(body))
    }

    private fun slotRow(slot: Slot, today: Long, minute: Int, times: Times): View {
        val state = Plan.state(slot, progress, today, minute, times)
        val line = Data.lineOf(content, progress, slot, today)
        val name = line?.label ?: "Les 3 lignes mélangées"
        val whenText = "${slot.label.uppercase()} · ${Times.upper(times.of(slot))}"
        val row = hbox(10)
        val left = vbox()
        val open = state == SlotState.OPEN
        val color = if (open) C.WHITE else C.NAVY
        left.addView(text(whenText, 12f, 700, color, 0.06f))
        left.addView(text(name, if (open) 19f else 17f, 800, color))
        row.addView(left, lp(0, WRAP, 1f))
        when (state) {
            SlotState.DONE -> {
                row.pad(16, 8, 16, 8)
                row.box(C.WHITE, 16, C.NAVY, 3)
                val ok = hbox(6)
                ok.addIcon(R.drawable.ic_check, 18, C.GREEN)
                ok.addView(text("FAITE", 15f, 800, C.GREEN))
                row.addView(ok)
            }
            SlotState.OPEN -> {
                row.pad(16, 10, 16, 10)
                row.box(C.RED, 16, C.NAVY, 3, C.NAVY, 5)
                row.addView(text("C'EST PARTI →", 16f, 800, C.WHITE))
                row.onTap { startSession(slot) }
            }
            SlotState.LATER -> {
                row.pad(16, 8, 16, 8)
                row.box(C.WHITE, 16, C.NAVY, 3, dashed = true)
                row.squishy()
                row.addView(text("plus tard", 15f, 700))
                row.onTap {
                    confirm(
                        "Commencer en avance ?",
                        "La séance ${slot.adjective} s'ouvre à ${Times.spoken(times.of(slot))}. Tu peux la faire dès maintenant si tu veux.",
                        "OUI, MAINTENANT", "Non, j'attends",
                    ) { startSession(slot) }
                }
            }
        }
        row.minimumHeight = dp(if (state == SlotState.OPEN) 64 else 56)
        return row
    }

    private fun linesCard(): View {
        val card = vbox(10).apply {
            pad(16, 14, 16, 14)
            box(C.WHITE, 16, C.NAVY, 3)
        }
        val title = hbox()
        title.addView(text("Tes 3 lignes", 20f, 800), lp(0, WRAP, 1f))
        title.addView(ImageView(this).apply { setImageResource(R.drawable.jeepney) }, LinearLayout.LayoutParams(dp(44), dp(30)))
        card.addView(title, lp())
        for (line in Line.values()) {
            val stop = progress.currentStop(content, line)
            val row = vbox(4).apply {
                onTap {
                    routeLine = line
                    select(TAB_ROUTE)
                }
            }
            val top = hbox(8)
            top.addView(text(line.label, 14f, 800), lp(0, WRAP, 1f))
            top.addView(text(if (stop == null) "Terminus !" else "arrêt ${stop.number} · ${stop.title}", 14f, 600).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(top, lp())
            val bar = Bar(this, C.TRACK, C.YELLOW, dpf(2))
            bar.fraction = if (stop == null) 1f else progress.knownCount(stop).toFloat() / stop.items.size
            row.addView(bar, lp(MATCH, dp(14)))
            card.addView(row, lp())
        }
        return card
    }

    private fun startSession(slot: Slot?) {
        val i = Intent(this, SessionActivity::class.java)
        if (slot != null) i.putExtra(SessionActivity.EXTRA_SLOT, slot.code) else i.putExtra(SessionActivity.EXTRA_BONUS, true)
        startActivity(i)
    }

    // ---------- Route ----------

    private fun route(): View {
        val h = header(12)
        val titles = vbox(2)
        titles.addView(text("TA ROUTE", 30f, 800, line = 0.95f))
        val what = if (routeLine.isPhrases) "les phrases" else "les mots"
        val hint = "Le jeepney avance quand tu connais bien $what d'un arrêt." +
            if (routeLine == Line.COMPLEX) " Touche un arrêt pour revoir sa leçon." else ""
        titles.addView(text(hint, 14f, 600, line = 1.2f))
        h.addView(titles, lp())
        val tabs = hbox(8)
        val subs = listOf("matin", "midi", "soir")
        Line.values().forEachIndexed { i, line ->
            val on = line == routeLine
            val t = vbox().apply {
                gravity = Gravity.CENTER
                pad(4, 6, 4, 6)
                box(if (on) C.NAVY else C.WHITE, 12, C.NAVY, 3)
                squishy()
                onTap {
                    routeLine = line
                    render()
                }
            }
            val color = if (on) C.YELLOW else C.NAVY
            t.addView(text(line.short, 13f, 800, color).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp(WRAP, WRAP))
            t.addView(text(subs[i], 12f, 600, color).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP))
            tabs.addView(t, lp(0, WRAP, 1f))
        }
        h.addView(tabs, lp())

        val body = vbox().apply { pad(20, 14, 20, 20) }
        val stops = content.stops(routeLine)
        val current = progress.currentStop(content, routeLine)
        // Le jeepney est passé partout avant l'arrêt où il se trouve : là, le trait est plein.
        val reached = if (current == null) stops.size else stops.indexOf(current)
        var currentRow: View? = null
        stops.forEachIndexed { i, stop ->
            val state = when {
                progress.isStopDone(stop) -> RouteRail.DONE
                stop == current -> RouteRail.CURRENT
                else -> RouteRail.FUTURE
            }
            val above = if (i == 0) null else i <= reached
            val below = if (i == stops.size - 1) null else i + 1 <= reached
            val row = stopRow(stop, state, above, below)
            if (state == RouteRail.CURRENT) currentRow = row
            body.addView(row, lp())
        }
        val end = vbox(2).apply {
            pad(14, 12, 14, 12)
            box(C.NAVY, 14)
        }
        end.addView(label("TERMINUS DE LA LIGNE ${routeLine.label.uppercase()}", 12f, C.YELLOW, 0.08f))
        val left = stops.count { !progress.isStopDone(it) }
        end.addView(
            text(
                if (left == 0) "Bravo, ligne finie ! Le badge « ${routeLine.label} » est à toi."
                else "Encore ${Fr.count(left, "arrêt", "arrêts")}. Un badge t'attend au bout de la ligne.",
                15f, 600, C.WHITE, line = 1.25f,
            )
        )
        body.addView(end, lp().apply { topMargin = dp(12) })
        val sv = scroll(body)
        currentRow?.let { r -> sv.post { sv.scrollTo(0, (r.top - dp(120)).coerceAtLeast(0)) } }
        return screen(h, sv)
    }

    private fun stopRow(stop: Stop, state: Int, above: Boolean?, below: Boolean?): View {
        val row = hbox(14).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(RouteRail(this, state, above, below), LinearLayout.LayoutParams(dp(44), MATCH))
        val known = progress.knownCount(stop)
        val total = stop.items.size
        val unit = if (stop.line.isPhrases) "phrases" else "mots"
        when (state) {
            RouteRail.DONE -> {
                val t = vbox().apply { pad(0, 6, 0, 6) }
                t.addView(text("${stop.number} · ${stop.title}", 16f, 800))
                t.addView(text("${stop.preview} · $known/$total", 13f, 600).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(t, lp(0, WRAP, 1f))
                row.minimumHeight = dp(52)
            }
            RouteRail.CURRENT -> {
                val card = hbox(8).apply {
                    pad(12, 8, 12, 8)
                    box(C.WHITE, 14, C.NAVY, 3, C.RED, 4)
                    stateListAnimator = null
                }
                val t = vbox()
                t.addView(label("TU ES ICI", 12f, C.RED, 0.08f))
                t.addView(text("${stop.number} · ${stop.title}", 17f, 800))
                t.addView(text("$known $unit sur $total bien connu${if (stop.line.isPhrases) "e" else ""}s", 13f, 600))
                card.addView(t, lp(0, WRAP, 1f))
                card.addView(ImageView(this).apply { setImageResource(R.drawable.jeepney) }, LinearLayout.LayoutParams(dp(44), dp(30)))
                row.addView(card, lp(0, WRAP, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
                row.minimumHeight = dp(76)
            }
            else -> {
                row.addView(text("${stop.number} · ${stop.title}", 16f, 700, C.MUTED), lp(0, WRAP, 1f))
                row.minimumHeight = dp(48)
            }
        }
        if (stop.lesson != null && state != RouteRail.FUTURE) {
            row.onTap {
                startActivity(Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_LESSON, stop.key))
            }
        }
        return row
    }

    // ---------- Lexique ----------

    private fun lexicon(): View {
        val seen = content.items.filter { progress.isIntroduced(it) }
        val h = header(12)
        val top = hbox()
        top.addView(text("LEXIQUE", 30f, 800, line = 0.95f), lp(0, WRAP, 1f))
        top.addView(text("${seen.size.fr()} vus · ${seen.count { progress.isKnown(it) }.fr()} bien connus", 14f, 700))
        h.addView(top, lp())
        val search = vbox(4)
        search.addView(text("Chercher un mot", 13f, 700))
        val field = hbox(8).apply {
            pad(12, 0, 12, 0)
            box(C.WHITE, 14, C.NAVY, 3)
        }
        field.addIcon(R.drawable.ic_search, 20, C.NAVY)
        val input = EditText(this).apply {
            background = null
            hint = "en français ou en cebuano"
            setHintTextColor(C.MUTED)
            setTextColor(C.NAVY)
            textSize = 16f
            typeface = Fonts.get(500, 16f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            setText(lexQuery)
        }
        field.addView(input, lp(0, dp(48), 1f))
        search.addView(field, lp(MATCH, dp(48)))
        h.addView(search, lp())
        val chips = hbox(8)
        val chipViews = mutableListOf<TextView>()
        listOf("Tout", "Mots", "Phrases").forEachIndexed { i, name ->
            val chip = text(name, 14f, 700).apply {
                pad(14, 6, 14, 6)
                squishy()
            }
            chipViews.add(chip)
            chips.addView(chip)
        }
        h.addView(chips, lp(WRAP, WRAP))

        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            pad(20, 10, 20, 20)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val empty = text("", 16f, 600, C.MUTED, line = 1.3f).apply {
            gravity = Gravity.CENTER
            pad(30, 30, 30, 30)
        }
        val adapter = LexiconAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            (adapter.getItem(position) as? Item)?.let { wordSheet(it) }
        }

        fun refresh() {
            val q = Answers.normalize(lexQuery)
            val shown = seen.filter { item ->
                (lexFilter == 0 || (lexFilter == 1) == item.isWord) &&
                    (q.isEmpty() || Answers.normalize(item.ceb).contains(q) || Answers.normalize(item.fr).contains(q) ||
                        item.alts.any { Answers.normalize(it).contains(q) })
            }
            adapter.show(shown)
            empty.text = when {
                seen.isEmpty() -> "Ton lexique se remplit à chaque séance.\nFais ta première séance pour y voir tes premiers mots !"
                else -> "Rien trouvé pour « $lexQuery »."
            }
            chipViews.forEachIndexed { i, chip ->
                val on = i == lexFilter
                chip.box(if (on) C.NAVY else C.WHITE, 999, C.NAVY, 3)
                chip.setTextColor(if (on) C.YELLOW else C.NAVY)
                chip.typeface = Fonts.get(if (on) 800 else 700, 14f)
            }
        }
        chipViews.forEachIndexed { i, chip ->
            chip.onTap {
                lexFilter = i
                refresh()
            }
        }
        input.onChange {
            lexQuery = it
            refresh()
        }
        input.setOnEditorActionListener { v, _, _ ->
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        list.emptyView = empty
        refresh()
        return screen(h, frame)
    }

    /** La liste du lexique : un titre par arrêt, puis ses mots. */
    private inner class LexiconAdapter : BaseAdapter() {
        private val rows = mutableListOf<Any>()

        fun show(items: List<Item>) {
            rows.clear()
            var last: Stop? = null
            for (item in items) {
                val stop = content.stopOf(item)
                if (stop != last) {
                    rows.add(stop)
                    last = stop
                }
                rows.add(item)
            }
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Stop) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            if (row is Stop) {
                val t = (convertView as? TextView) ?: label("", 13f).apply { pad(0, 12, 0, 4) }
                val line = when (row.line) {
                    Line.VOCAB -> "MOTS"
                    Line.SIMPLE -> "PHRASES SIMPLES"
                    Line.COMPLEX -> "COMPLIQUÉES"
                }
                t.text = "$line · ARRÊT ${row.number} · ${row.title.uppercase()}"
                return t
            }
            val item = row as Item
            val holder = (convertView?.tag as? WordHolder) ?: WordHolder()
            holder.ceb.text = item.ceb
            holder.ceb.textSize = if (item.isWord) 18f else 16f
            holder.fr.text = item.fr
            holder.bars.level = progress.level(item)
            holder.bars.invalidate()
            val next = rows.getOrNull(position + 1)
            holder.line.visibility = if (next is Item) View.VISIBLE else View.INVISIBLE
            return holder.view
        }
    }

    private inner class WordHolder {
        val view = vbox()
        val ceb = text("", 18f, 800)
        val fr = text("", 15f, 500, line = 1.2f)
        val bars = LevelBars(this@MainActivity, 0)
        val line = dashes()

        init {
            val row = hbox(12).apply { pad(0, 6, 0, 6) }
            val words = vbox(1)
            words.addView(ceb)
            words.addView(fr)
            row.addView(words, lp(0, WRAP, 1f))
            row.addView(bars)
            view.addView(row, lp())
            view.addView(line)
            view.tag = this
        }
    }

    /** La fiche d'un mot : son sens, son astuce et son niveau. */
    private fun wordSheet(item: Item) {
        sheet {
            val stop = content.stopOf(item)
            addView(label("${item.line.label.uppercase()} · ARRÊT ${stop.number}", 12f))
            addView(text(item.ceb, if (item.isWord) 30f else 24f, 800, line = 1.05f))
            addView(text(item.fr.capitalized(), 18f, 600, line = 1.2f))
            if (item.alts.isNotEmpty()) addView(text("On dit aussi : ${item.alts.joinToString(", ")}", 15f, 600, C.MUTED))
            item.tip?.let { tip ->
                val box = hbox(10).apply {
                    gravity = Gravity.TOP
                    pad(14, 12, 14, 12)
                    box(C.NAVY, 16)
                }
                box.addIcon(R.drawable.ic_bulb, 22, C.YELLOW)
                box.addView(text(tip, 15f, 500, C.WHITE, line = 1.3f), lp(0, WRAP, 1f))
                addView(box, lp())
            }
            val state = progress.state(item)
            if (state != null) {
                val level = hbox(10)
                level.addView(LevelBars(this@MainActivity, state.level))
                level.addView(text("Niveau ${state.level} sur 5${if (state.level >= Progress.KNOWN) " · bien connu" else ""}", 15f, 700))
                addView(level, lp())
                val days = state.dueDay - Store.today()
                val it = if (item.isWord) "Il" else "Elle"
                addView(
                    text(
                        when {
                            days <= 0 -> "$it revient à la prochaine séance."
                            days == 1L -> "$it revient demain."
                            else -> "$it revient dans $days jours."
                        },
                        14f, 600, C.MUTED,
                    )
                )
            } else {
                addView(text("Pas encore vu en séance.", 14f, 600, C.MUTED))
            }
        }
    }

    // ---------- Badges ----------

    private fun badges(): View {
        val today = Store.today()
        val h = header(4)
        h.addView(text("BADGES", 30f, 800, line = 0.95f))
        h.addView(text("${progress.badges.size} badge${if (progress.badges.size > 1) "s" else ""} sur ${Badge.values().size} · ${progress.points.fr()} points", 15f, 700))
        val earned = progress.badges.entries.sortedBy { it.value }.map { it.key }
        val order = earned + Badge.values().filter { it !in progress.badges }
        val grid = vbox(16).apply { pad(16, 18, 16, 22) }
        for (chunk in order.chunked(3)) {
            val row = hbox(10).apply { gravity = Gravity.TOP }
            for (badge in chunk) row.addView(badgeCell(badge, today), lp(0, WRAP, 1f))
            repeat(3 - chunk.size) { row.addView(View(this), lp(0, 1, 1f)) }
            grid.addView(row, lp())
        }
        return screen(h, scroll(grid))
    }

    private fun badgeCell(badge: Badge, today: Long): View {
        val got = badge in progress.badges
        val color = if (got) C.NAVY else C.MUTED
        val cell = vbox(6).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            onTap { badgeSheet(badge, today) }
        }
        cell.addView(badgeCircle(badge, got, 70, 32), LinearLayout.LayoutParams(dp(if (got) 73 else 70), dp(if (got) 73 else 70)))
        cell.addView(text(badge.title, 14f, 800, color).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        val (cur, target) = progress.badgeProgress(content, badge, today)
        val counted = badge in listOf(Badge.STREAK7, Badge.STREAK30, Badge.PERFECT10, Badge.WORDS50, Badge.WORDS100)
        val sub = if (!got && counted) "${cur.fr()} sur ${target.fr()}" else badge.subtitle
        cell.addView(text(sub, 12f, 600, color).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        return cell
    }

    private fun badgeSheet(badge: Badge, today: Long) {
        val got = badge in progress.badges
        sheet {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(badgeCircle(badge, got, 90, 42), LinearLayout.LayoutParams(dp(if (got) 93 else 90), dp(if (got) 93 else 90)))
            addView(text(badge.title, 24f, 800).apply { gravity = Gravity.CENTER }, lp())
            addView(text(badgeText(badge), 16f, 500, line = 1.3f).apply { gravity = Gravity.CENTER }, lp())
            val (cur, target) = progress.badgeProgress(content, badge, today)
            val status = when {
                got -> "Gagné le ${Fr.day(progress.badges[badge]!!)}."
                target > 1 -> "Pour l'instant : ${cur.fr()} sur ${target.fr()}."
                else -> "Pas encore gagné."
            }
            addView(text(status, 15f, 700, if (got) C.GREEN else C.MUTED).apply { gravity = Gravity.CENTER }, lp())
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_LINE = "line"
        const val EXTRA_DEMO = "demo"
        const val TAB_HOME = 0
        const val TAB_ROUTE = 1
        const val TAB_LEXICON = 2
        const val TAB_BADGES = 3

        fun badgeIcon(badge: Badge) = when (badge.icon) {
            "ticket" -> R.drawable.ic_ticket
            "flame" -> R.drawable.ic_flame
            "star" -> R.drawable.ic_star
            "check" -> R.drawable.ic_check_circle
            "bubble" -> R.drawable.ic_bubble
            "layers" -> R.drawable.ic_layers
            "flag" -> R.drawable.ic_flag
            else -> R.drawable.ic_book
        }

        fun badgeText(badge: Badge) = when (badge) {
            Badge.SAKAY -> "Ta toute première séance. « Sakay na ! », c'est ce que crie le chauffeur du jeepney : « Monte ! »"
            Badge.STREAK7 -> "Au moins une séance par jour, 7 jours de suite."
            Badge.STREAK30 -> "Au moins une séance par jour, 30 jours de suite."
            Badge.PERFECT -> "Les 3 séances d'une même journée."
            Badge.PERFECT10 -> "10 journées parfaites, pas forcément de suite."
            Badge.FLAWLESS -> "Une séance de 10 questions sans aucune erreur."
            Badge.WORDS50 -> "50 mots bien connus (niveau 3 ou plus)."
            Badge.WORDS100 -> "100 mots bien connus (niveau 3 ou plus)."
            Badge.LINE_VOCAB -> "Arriver au terminus de la ligne Vocabulaire."
            Badge.LINE_SIMPLE -> "Arriver au terminus de la ligne Phrases simples."
            Badge.LINE_COMPLEX -> "Arriver au terminus de la ligne Phrases compliquées."
            Badge.TERMINUS -> "Arriver au bout des 3 lignes. Maayo kaayo !"
        }
    }
}

/** La pastille ronde d'un badge (jaune si gagné, grise sinon). */
fun android.content.Context.badgeCircle(badge: Badge, got: Boolean, size: Int, iconSize: Int): FrameLayout =
    FrameLayout(this).apply {
        if (got) box(C.YELLOW, 999, C.NAVY, 3, C.RED, 3) else box(C.TRACK, 999, C.LOCKED, 3)
        stateListAnimator = null
        val i = icon(MainActivity.badgeIcon(badge), if (got) C.NAVY else C.MUTED)
        addView(i, FrameLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply {
            leftMargin = dp((size - iconSize) / 2)
            topMargin = dp((size - iconSize) / 2)
        })
    }

/**
 * Le rail de la route : une pastille par arrêt, et le trait au-dessus et au-dessous
 * (plein là où le jeepney est passé, en pointillés ensuite, rien au bout de la ligne).
 */
class RouteRail(ctx: android.content.Context, private val state: Int, private val above: Boolean?, private val below: Boolean?) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = ctx.getDrawable(R.drawable.ic_check)?.mutate()?.apply { setTint(C.WHITE) }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val node = context.dpf(
            when (state) {
                DONE -> 30
                CURRENT -> 40
                else -> 24
            }
        ) / 2f
        above?.let { segment(canvas, cx, 0f, cy - node, solid = it) }
        below?.let { segment(canvas, cx, cy + node, height.toFloat(), solid = it) }
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        when (state) {
            DONE -> {
                paint.color = C.NAVY
                canvas.drawCircle(cx, cy, node, paint)
                val s = context.dp(9)
                check?.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
                check?.draw(canvas)
            }
            CURRENT -> {
                paint.color = C.RED
                canvas.drawCircle(cx, cy, node, paint)
                ring(canvas, cx, cy, node, context.dpf(4), C.NAVY)
            }
            else -> {
                paint.color = C.TRACK
                canvas.drawCircle(cx, cy, node, paint)
                ring(canvas, cx, cy, node, context.dpf(3), C.LOCKED)
            }
        }
    }

    private fun ring(canvas: Canvas, cx: Float, cy: Float, r: Float, w: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w
        paint.color = color
        canvas.drawCircle(cx, cy, r - w / 2f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun segment(canvas: Canvas, x: Float, from: Float, to: Float, solid: Boolean) {
        if (to <= from) return
        paint.style = Paint.Style.STROKE
        if (solid) {
            paint.color = C.NAVY
            paint.strokeWidth = context.dpf(8)
            paint.pathEffect = null
        } else {
            paint.color = C.DASH
            paint.strokeWidth = context.dpf(6)
            paint.pathEffect = DashPathEffect(floatArrayOf(context.dpf(9), context.dpf(7)), 0f)
        }
        canvas.drawLine(x, from, x, to, paint)
    }

    companion object {
        const val DONE = 0
        const val CURRENT = 1
        const val FUTURE = 2
    }
}
