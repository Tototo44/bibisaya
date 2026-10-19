package com.thomas.bibisaya.engine

/** Les 3 lignes du jeepney. */
enum class Line(val code: String, val label: String, val short: String) {
    VOCAB("v", "Vocabulaire", "Vocabulaire"),
    SIMPLE("s", "Phrases simples", "Phrases simples"),
    COMPLEX("c", "Phrases compliquées", "Compliquées");

    val isPhrases get() = this != VOCAB
}

/** Une ligne du tableau de la petite leçon : « nag- | c'est fait | nagtuon = a étudié ». */
class Rule(val form: String, val meaning: String, val example: String)

class Example(val ceb: String, val fr: String)

class Lesson(val intro: String, val rules: List<Rule>, val examples: List<Example>)

/** Un mot ou une phrase à apprendre. */
class Item(
    val id: String,
    val line: Line,
    val stop: Int,
    /** Le mot ou la phrase complète en cebuano. */
    val ceb: String,
    val fr: String,
    val tip: String?,
    /** Autres réponses acceptées. */
    val alts: List<String>,
    /** Groupe de mots proches : jamais proposés comme piège l'un pour l'autre. */
    val syn: String?,
    val en: String?,
    /** Ajouté par Claude (pas dans les cours de Thomas) : à faire relire. */
    val added: Boolean,
    /** Trou à compléter : le texte avant, les choix (le premier est le bon), le texte après. */
    val gapBefore: String?,
    val gapOptions: List<String>?,
    val gapAfter: String?,
) {
    val tokens: List<String> = Content.tokenize(ceb)
    val isWord get() = line == Line.VOCAB
    val hasGap get() = gapOptions != null
    val gapAnswer get() = gapOptions?.first()
    /** Assez court pour « Relier » ou « Écrire ». */
    val isShort get() = tokens.size <= 2

    override fun toString() = id
}

class Stop(
    val line: Line,
    val number: Int,
    val title: String,
    val subtitle: String?,
    val lesson: Lesson?,
    val items: List<Item>,
) {
    val key get() = "${line.code}$number"

    /** « tawo, lalake, babaye, bata… » */
    val preview: String
        get() = subtitle ?: (items.take(4).joinToString(", ") { it.ceb } + "…")
}

/** Tout le contenu de l'appli (lu depuis assets/contenu.txt). */
class Content(private val stopsByLine: Map<Line, List<Stop>>, private val glosses: Map<String, String>) {
    val items: List<Item> = Line.values().flatMap { line -> stops(line).flatMap { it.items } }
    val byId: Map<String, Item> = items.associateBy { it.id }

    private val wordGloss: Map<String, String> = buildMap {
        for (item in items) {
            if (item.line != Line.VOCAB) continue
            putIfAbsent(item.ceb.lowercase(), item.fr)
            for (alt in item.alts) putIfAbsent(alt.lowercase(), item.fr)
        }
    }

    private val byForm: Map<String, List<Item>> by lazy {
        val map = mutableMapOf<String, MutableList<Item>>()
        for (item in items) for (form in listOf(item.ceb) + item.alts) {
            map.getOrPut(Answers.normalize(form)) { mutableListOf() }.add(item)
        }
        map
    }

    /** Les mots ou phrases qui s'écrivent comme [normalized] (sans majuscules, accents ni tirets). */
    fun itemsWithForm(normalized: String): List<Item> = byForm[normalized] ?: emptyList()

    fun stops(line: Line): List<Stop> = stopsByLine[line] ?: emptyList()

    fun stop(line: Line, number: Int): Stop = stops(line).first { it.number == number }

    fun stopOf(item: Item): Stop = stop(item.line, item.stop)

    fun items(line: Line): List<Item> = stops(line).flatMap { it.items }

    /** Le sens d'un mot touché dans une phrase. */
    fun gloss(token: String): String? {
        val key = cleanToken(token).lowercase()
        return glosses[key] ?: wordGloss[key]
    }

    companion object {
        fun parse(text: String): Content {
            val stops = mutableMapOf<Line, MutableList<Stop>>()
            val glosses = mutableMapOf<String, String>()
            var section: String? = null
            var stopHeader: Triple<Int, String, String?>? = null
            var items = mutableListOf<Item>()
            var intro: String? = null
            var rules = mutableListOf<Rule>()
            var examples = mutableListOf<Example>()

            fun closeStop() {
                val h = stopHeader ?: return
                val line = Line.valueOf(section!!)
                val lesson = intro?.let { Lesson(it, rules.toList(), examples.toList()) }
                stops.getOrPut(line) { mutableListOf() }.add(Stop(line, h.first, h.second, h.third, lesson, items.toList()))
                stopHeader = null
                items = mutableListOf()
                intro = null
                rules = mutableListOf()
                examples = mutableListOf()
            }

            for ((index, raw) in text.lines().withIndex()) {
                val l = raw.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                fun fail(msg: String): Nothing = throw IllegalArgumentException("contenu.txt ligne ${index + 1} : $msg ($l)")
                when {
                    l.startsWith("=== ") -> {
                        closeStop()
                        section = l.removePrefix("=== ").trim()
                    }
                    l.startsWith("--- ") -> {
                        closeStop()
                        val parts = l.removePrefix("--- ").split("|").map { it.trim() }
                        val number = parts[0].toIntOrNull() ?: fail("numéro d'arrêt")
                        stopHeader = Triple(number, parts.getOrElse(1) { fail("titre") }, parts.getOrNull(2))
                    }
                    l.startsWith(">") -> {
                        val body = l.removePrefix(">").trim()
                        val key = body.substringBefore(":").trim()
                        val value = body.substringAfter(":").trim()
                        when (key) {
                            "intro" -> intro = value
                            "regle" -> {
                                val p = value.split("|").map { it.trim() }
                                if (p.size != 3) fail("règle en 3 parties")
                                rules.add(Rule(p[0], p[1], p[2]))
                            }
                            "exemple" -> {
                                if (" = " !in value) fail("exemple sans « = »")
                                examples.add(Example(value.substringBefore(" = ").trim(), value.substringAfter(" = ").trim()))
                            }
                            else -> fail("clé de leçon inconnue")
                        }
                    }
                    section == "GLOSES" -> {
                        if (" = " !in l) fail("glose sans « = »")
                        glosses[l.substringBefore(" = ").trim().lowercase()] = l.substringAfter(" = ").trim()
                    }
                    else -> {
                        val h = stopHeader ?: fail("mot hors d'un arrêt")
                        val line = Line.valueOf(section ?: fail("pas de ligne"))
                        val parts = l.split(" ; ")
                        var main = parts[0]
                        val added = main.startsWith("+")
                        if (added) main = main.substring(1)
                        if (" = " !in main) fail("pas de « = »")
                        val rawCeb = main.substringBefore(" = ").trim()
                        val fr = main.substringAfter(" = ").trim()
                        val fields = parts.drop(1).associate { f ->
                            if (": " !in f) fail("champ sans « : »")
                            f.substringBefore(": ").trim() to f.substringAfter(": ").trim()
                        }
                        var gapBefore: String? = null
                        var gapOptions: List<String>? = null
                        var gapAfter: String? = null
                        val open = rawCeb.indexOf('[')
                        val ceb = if (open >= 0) {
                            val close = rawCeb.indexOf(']', open)
                            if (close < 0) fail("crochet pas fermé")
                            gapBefore = rawCeb.substring(0, open)
                            gapOptions = rawCeb.substring(open + 1, close).split("|").map { it.trim() }
                            gapAfter = rawCeb.substring(close + 1)
                            if (gapOptions.size < 2) fail("trou avec un seul choix")
                            gapBefore + gapOptions.first() + gapAfter
                        } else rawCeb
                        val id = fields["id"] ?: "${line.code}:${slug(ceb)}"
                        items.add(
                            Item(
                                id = id, line = line, stop = h.first, ceb = ceb, fr = fr,
                                tip = fields["tip"],
                                alts = fields["alt"]?.split(" / ")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                                syn = fields["syn"], en = fields["en"], added = added,
                                gapBefore = gapBefore, gapOptions = gapOptions, gapAfter = gapAfter,
                            )
                        )
                    }
                }
            }
            closeStop()
            return Content(stops, glosses)
        }

        private const val PUNCT = ".,!?;:«»\"()…"

        fun cleanToken(t: String): String = t.trim().trim { it in PUNCT }

        /** « Asa ka padulong? » -> [Asa, ka, padulong] */
        fun tokenize(s: String): List<String> = s.split(Regex("\\s+")).map { cleanToken(it) }.filter { it.isNotEmpty() }

        fun slug(s: String): String =
            Answers.stripAccents(s.lowercase()).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}
