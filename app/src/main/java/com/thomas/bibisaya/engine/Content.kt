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

/** Une petite leçon. [ronde] : la leçon a sa « ronde des personnes », avec ces mots pour faire les phrases. */
class Lesson(
    val intro: String,
    val rules: List<Rule>,
    val examples: List<Example>,
    val ronde: RondeKind? = null,
    val rondeWords: List<RondeWord> = emptyList(),
)

/** Une ligne de la fiche « Les questions » : « unsa | quoi | Unsa ni? = Qu'est-ce que c'est ? ». */
class SheetWord(val word: String, val meaning: String, val example: Example?)

/** Une astuce de fiche, avec son exemple en cebuano. */
class SheetTip(val text: String, val example: String?)

/** Un tableau de fiche. Une case peut avoir deux formes : « ako · ko ». */
class SheetTable(val title: String, val columns: List<String>, val rows: List<SheetRow>, val note: String?, val examples: List<Example>)

class SheetRow(val label: String, val cells: List<String>)

/** Une fiche tirée des cours de Thomas : des mots, des tableaux et des astuces. */
class Sheet(
    val key: String,
    val title: String,
    val subtitle: String?,
    val intro: String?,
    val words: List<SheetWord>,
    val tables: List<SheetTable>,
    val tips: List<SheetTip>,
)

/** Un mot ou une phrase à apprendre (ou une phrase fabriquée par l'appli, voir [Built]). */
open class Item(
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
class Content(
    private val stopsByLine: Map<Line, List<Stop>>,
    private val glosses: Map<String, String>,
    val sheets: List<Sheet> = emptyList(),
) {
    val items: List<Item> = Line.values().flatMap { line -> stops(line).flatMap { it.items } }
    val byId: Map<String, Item> = items.associateBy { it.id }

    /** Les noms propres (Thomas, Cebu, Bisaya…) : ils gardent leur majuscule même en début de phrase. */
    val properNouns: Set<String> = buildSet {
        for (item in items) {
            val sentences = listOf(item.ceb) + item.alts + (item.gapOptions ?: emptyList()).map { (item.gapBefore ?: "") + it + (item.gapAfter ?: "") }
            for (sentence in sentences) {
                tokenize(sentence).drop(1).filter { it.first().isUpperCase() }.forEach { add(Answers.normalize(it)) }
            }
        }
        // Les prénoms et les lieux des phrases fabriquées.
        Lexique.prenoms.forEach { add(Answers.normalize(it.name)) }
        Lexique.lieux.filter { it.ceb.first().isUpperCase() }.forEach { add(Answers.normalize(it.ceb)) }
    }

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

    /** Le sens d'un mot touché dans une phrase (aussi les verbes conjugués et les prénoms des phrases fabriquées). */
    fun gloss(token: String): String? {
        val key = cleanToken(token).lowercase()
        return glosses[key] ?: wordGloss[key] ?: Fabrique.gloss(key)
    }

    /** Un mot que l'appli connaît (dans une phrase, une glose ou le vocabulaire). */
    fun isKnownWord(token: String): Boolean = gloss(token) != null || itemsWithForm(Answers.normalize(token)).isNotEmpty()

    fun sheet(key: String): Sheet? = sheets.firstOrNull { it.key == key }

    /** Toutes les leçons (une par arrêt des phrases compliquées). */
    val lessons: List<Stop> get() = Line.values().flatMap { stops(it) }.filter { it.lesson != null }

    fun stopByKey(key: String): Stop? = Line.values().flatMap { stops(it) }.firstOrNull { it.key == key }

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
            var ronde: RondeKind? = null
            var rondeWords = mutableListOf<RondeWord>()
            val sheets = mutableListOf<Sheet>()
            val sheet = SheetBuilder()

            fun closeStop() {
                if (section == "FICHES") {
                    sheet.build()?.let { sheets.add(it) }
                    return
                }
                val h = stopHeader ?: return
                val line = Line.valueOf(section!!)
                val lesson = intro?.let { Lesson(it, rules.toList(), examples.toList(), ronde, rondeWords.toList()) }
                stops.getOrPut(line) { mutableListOf() }.add(Stop(line, h.first, h.second, h.third, lesson, items.toList()))
                stopHeader = null
                items = mutableListOf()
                intro = null
                rules = mutableListOf()
                examples = mutableListOf()
                ronde = null
                rondeWords = mutableListOf()
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
                    l.startsWith("--- ") && section == "FICHES" -> {
                        closeStop()
                        val parts = l.removePrefix("--- ").split("|").map { it.trim() }
                        sheet.start(parts[0], parts.getOrElse(1) { fail("titre de fiche") }, parts.getOrNull(2))
                    }
                    l.startsWith(">") && section == "FICHES" -> {
                        val body = l.removePrefix(">").trim()
                        if (!sheet.open) fail("ligne hors d'une fiche")
                        sheet.add(body.substringBefore(":").trim(), body.substringAfter(":").trim())?.let { fail(it) }
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
                            "ronde-adjectif", "ronde-nom" -> {
                                val kind = if (key == "ronde-nom") RondeKind.POSSESSIVES else RondeKind.PRONOUNS
                                if (ronde != null && ronde != kind) fail("une ronde d'adjectifs ou de noms, pas les deux")
                                if (" = " !in value) fail("mot de ronde sans « = »")
                                val fr = value.substringAfter(" = ").split(",").map { it.trim() }
                                if (kind == RondeKind.PRONOUNS && fr.size != 4) fail("adjectif de ronde en 4 formes : fort, forte, forts, fortes")
                                if (kind == RondeKind.POSSESSIVES && (fr.size != 1 || listOf("le ", "la ", "l'").none { fr[0].startsWith(it) })) {
                                    fail("nom de ronde avec son article : la maison, le livre, l'école")
                                }
                                ronde = kind
                                rondeWords.add(RondeWord(value.substringBefore(" = ").trim(), fr))
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
            return Content(stops, glosses, sheets)
        }

        /** Lit une fiche ligne par ligne. */
        private class SheetBuilder {
            private var key: String? = null
            private var title = ""
            private var subtitle: String? = null
            private var intro: String? = null
            private val words = mutableListOf<SheetWord>()
            private val tables = mutableListOf<SheetTable>()
            private val tips = mutableListOf<SheetTip>()
            private var table: TableBuilder? = null

            val open get() = key != null

            fun start(key: String, title: String, subtitle: String?) {
                this.key = key
                this.title = title
                this.subtitle = subtitle
            }

            /** Rend un message d'erreur, ou null si la ligne est bonne. */
            fun add(k: String, value: String): String? {
                val p = value.split("|").map { it.trim() }
                when (k) {
                    "intro" -> intro = value
                    "mot" -> {
                        if (p.size !in 2..3) return "mot en 2 ou 3 parties"
                        val ex = p.getOrNull(2)?.let { e ->
                            if (" = " !in e) return "exemple sans « = »"
                            Example(e.substringBefore(" = ").trim(), e.substringAfter(" = ").trim())
                        }
                        words.add(SheetWord(p[0], p[1], ex))
                    }
                    "astuce" -> tips.add(SheetTip(p[0], p.getOrNull(1)))
                    "titre" -> {
                        closeTable()
                        table = TableBuilder(value)
                    }
                    "colonnes" -> (table ?: return "colonnes sans titre").columns = p
                    "ligne" -> {
                        val t = table ?: return "ligne sans titre"
                        if (p.size != t.columns.size + 1) return "ligne avec ${p.size - 1} cases pour ${t.columns.size} colonnes"
                        t.rows.add(SheetRow(p[0], p.drop(1)))
                    }
                    "note" -> (table ?: return "note sans titre").note = value
                    "exemple" -> {
                        if (" = " !in value) return "exemple sans « = »"
                        (table ?: return "exemple sans titre").examples.add(Example(value.substringBefore(" = ").trim(), value.substringAfter(" = ").trim()))
                    }
                    else -> return "clé de fiche inconnue"
                }
                return null
            }

            private fun closeTable() {
                table?.let { tables.add(SheetTable(it.title, it.columns, it.rows.toList(), it.note, it.examples.toList())) }
                table = null
            }

            fun build(): Sheet? {
                val k = key ?: return null
                closeTable()
                val s = Sheet(k, title, subtitle, intro, words.toList(), tables.toList(), tips.toList())
                key = null
                subtitle = null
                intro = null
                words.clear()
                tables.clear()
                tips.clear()
                return s
            }
        }

        private class TableBuilder(val title: String) {
            var columns: List<String> = emptyList()
            val rows = mutableListOf<SheetRow>()
            var note: String? = null
            val examples = mutableListOf<Example>()
        }

        private const val PUNCT = ".,!?;:«»\"()…"

        fun cleanToken(t: String): String = t.trim().trim { it in PUNCT }

        /** « Asa ka padulong? » -> [Asa, ka, padulong] */
        fun tokenize(s: String): List<String> = s.split(Regex("\\s+")).map { cleanToken(it) }.filter { it.isNotEmpty() }

        fun slug(s: String): String =
            Answers.stripAccents(s.lowercase()).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}
