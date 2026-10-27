package com.thomas.bibisaya.engine

import kotlin.random.Random

/** La distance (leçons 3 et 8) : près de moi, près de toi, ou là-bas. */
enum class Distance { HERE, THERE, FAR }

/** Le moment (leçons 6, 7 et 11) : ça va se faire (demain), c'est fait (hier), ou un ordre. */
enum class Moment(val meaning: String) {
    FUTURE("« ça va se faire »"),
    PAST("« c'est fait »"),
    ORDER("un ordre"),
}

/**
 * Ce qui change d'une question à l'autre dans une ronde : une personne, une distance, un moment, un petit mot…
 * [key] reste dans la progression : les lignes ratées reviennent plus souvent le soir.
 * [label] : ce que dit le bandeau (« nous (sans toi) », « près de toi », « demain »).
 */
class Row(
    val key: String,
    val label: String,
    val person: Person? = null,
    val distance: Distance? = null,
    val moment: Moment? = null,
)

/**
 * Une phrase à trou : le texte avant, la bonne réponse, le texte après, et la traduction.
 * [choices] : les réponses de toutes les lignes (les mots à toucher).
 * [accepted] : d'autres réponses justes, avec leur petit mot. [wrong] : les erreurs prévues, avec leur explication.
 * [band] et [bandQuestion] : ce que montre le bandeau, quand ce n'est pas la ligne elle-même (« RELIE : dako + nga »).
 */
class Frase(
    val before: String,
    val answer: String,
    val after: String,
    val french: String,
    val choices: List<String>,
    val end: String = ".",
    accepted: Map<String, String?> = emptyMap(),
    wrong: Map<String, String> = emptyMap(),
    val rightNote: String? = null,
    val band: Row? = null,
    val bandQuestion: String? = null,
) {
    val accepted: Map<String, String?> = accepted.filterKeys { !Answers.same(it, answer) }
    /** Jamais une forme juste (ta, c'est aussi la forme courte de nato, leçon 4 : pour « nous », c'est juste). */
    val wrong: Map<String, String> = wrong.filterKeys { k -> !Answers.same(k, answer) && this.accepted.keys.none { Answers.same(it, k) } }

    /** La phrase complète : « Kusgan kami. », « Dakong balay kini. » */
    val sentence: String get() = sentenceWith(answer)

    fun sentenceWith(form: String) = join(before, form, after, end)

    /** La réponse se colle au mot d'avant (dako + -ng = dakong) : pas de trou séparé. */
    val glued get() = answer.startsWith("-")

    companion object {
        /** Une réponse qui commence par un tiret se colle au mot d'avant : « Dako » + « -ng » = « Dakong ». */
        fun join(before: String, form: String, after: String, end: String): String {
            val head = when {
                form.startsWith("-") -> before + form.drop(1)
                before.isEmpty() -> form
                else -> "$before $form"
            }
            val all = if (after.isEmpty()) head else "$head $after"
            return Francais.capital(all) + end
        }
    }
}

/** Les phrases d'une même scène (« kusgan » pour la leçon 1), une par ligne demandée. */
class Pass(val scene: String, val frases: List<Pair<Row, Frase>>)

/**
 * Choisit les mots des phrases fabriquées : de préférence ceux que Thomas a déjà vus (8 fois sur 10),
 * et pas deux fois le même dans une série.
 */
class Words(val content: Content, private val progress: Progress?, val random: Random) {
    private val used = mutableSetOf<String>()

    /** Un mot du vocabulaire déjà vu. */
    fun known(ceb: String): Boolean {
        val p = progress ?: return false
        return content.itemsWithForm(Answers.normalize(ceb)).any { it.line == Line.VOCAB && p.isIntroduced(it) }
    }

    fun <T> pick(list: List<T>, key: (T) -> String): T? {
        if (list.isEmpty()) return null
        val fresh = list.filter { key(it) !in used }.ifEmpty { list }
        val seen = fresh.filter { known(key(it)) }
        val pool = if (seen.isNotEmpty() && random.nextInt(10) < 8) seen else fresh
        return pool.random(random).also { used.add(key(it)) }
    }

    fun chance(percent: Int) = random.nextInt(100) < percent

    /** Homme ou femme, pour un nom qui peut être les deux (higala : ami, amie ; bata : toujours « enfant »). */
    fun feminine(n: Nom) = n.canBe(true) && (!n.canBe(false) || (!n.oneWord && chance(50)))

    fun <T> any(list: List<T>): T = list.random(random)

    fun <T> shuffled(list: List<T>): List<T> = list.shuffled(random)
}

/** Qui fait l'action : « ko », « si Lorie », « ang bata », et le même en français. [note] : « (pas toi) » pour kami. */
class Who(val ceb: String, val fr: Francais.Sujet, val note: String = "", val person: Person? = null) {
    companion object {
        fun pronoun(p: Person, feminine: Boolean) =
            Who(p.short, Francais.pronoun(p.fr, feminine && p.gendered), p.weNote, p)

        fun name(w: Words, feminine: Boolean? = null): Who? {
            val n = w.pick(Lexique.prenoms.filter { feminine == null || it.feminine == feminine }) { it.name } ?: return null
            return Who("si ${n.name}", Francais.Sujet(n.name, 2, n.feminine))
        }

        /** « ang bata » = l'enfant, « ang mga silingan » = les voisines… */
        fun noun(w: Words, plural: Boolean): Who? {
            val n = w.pick(Lexique.noms.filter { it.sorte == Sorte.PERSON }) { it.ceb } ?: return null
            val fr = n.fr(w.feminine(n))
            return Who("ang ${if (plural) "mga " else ""}${n.ceb}", Francais.Sujet(fr.le(plural), if (plural) 5 else 2, fr.feminine))
        }

        /** Un pronom (le plus souvent), un prénom ou une personne. */
        fun any(w: Words, persons: List<Person> = Person.values().toList()): Who? = when {
            w.chance(55) -> pronoun(w.any(persons), w.chance(50))
            w.chance(60) -> name(w)
            else -> noun(w, w.chance(25))
        }

        /** Quelqu'un d'autre : un prénom ou une personne (jamais je ni tu). */
        fun third(w: Words): Who? = if (w.chance(60)) name(w) else noun(w, w.chance(25))
    }
}

/**
 * Une action : le verbe, qui le fait, et ce qui suit (« ug pan », « sa merkado »), en cebuano et en français.
 * [ceb] et [fr] commencent par une espace (ou sont vides).
 */
class Act(val verb: Verbe, val who: Who, val ceb: String, val fr: String) {
    companion object {
        fun make(w: Words, verb: Verbe, who: Who): Act {
            if (verb.things.isNotEmpty() && (!verb.alone || w.chance(60))) {
                val thing = w.pick(verb.things.map { Lexique.nom(it) }) { it.ceb }!!
                return Act(verb, who, " ug ${thing.ceb}", " ${thing.some}")
            }
            if (verb.places.isNotEmpty() && (!verb.alone || w.chance(50))) {
                val place = w.pick(verb.places.map { Lexique.lieu(it) }) { it.ceb }!!
                return Act(verb, who, " sa ${place.ceb}", " ${place.fr}")
            }
            return Act(verb, who, "", "")
        }
    }
}

/**
 * Une phrase fabriquée par l'appli, à la place d'une phrase de leçon : même identifiant (la progression reste
 * celle de la phrase du cours), mais d'autres mots, et une autre personne (ou un autre moment, une autre distance…).
 */
class Built(
    val source: Item,
    ceb: String,
    fr: String,
    alts: List<String>,
    gapBefore: String?,
    gapOptions: List<String>?,
    gapAfter: String?,
    /** Les phrases presque pareilles (un seul mot change) : les pièges. */
    val variants: List<Variants.Variant>,
    /** Les traductions presque pareilles (« Nous sommes forts (toi et moi). »), avec leur cebuano. */
    val frTraps: List<Example>,
) : Item(source.id, source.line, source.stop, ceb, fr, null, alts, null, null, false, gapBefore, gapOptions, gapAfter)

/**
 * La grammaire d'une leçon : de quoi fabriquer ses phrases (pour les séances), et sa ronde
 * (une phrase dont une seule partie change à chaque question).
 */
abstract class Theme(val lesson: Int) {
    /** « LA RONDE DES PERSONNES » */
    abstract val title: String
    /** Sous le titre, sur la carte de la leçon. */
    abstract val subtitle: String
    /** Au-dessus de la ligne, dans le bandeau : « QUI ? », « OÙ ? »… */
    abstract val question: String
    abstract val rows: List<Row>
    /** Ce que demande chaque tour, en haut de l'écran. */
    open fun round(second: Boolean) = if (second) "à écrire" else "à toucher"
    /** Les colonnes du tableau de fin. */
    open val columns = "TOUCHÉ" to "ÉCRIT"
    /** La présentation de chaque tour. */
    abstract fun intro(second: Boolean): String
    /** Sous la case à remplir, au 2e tour. */
    abstract val hint: String
    /** Un petit rappel sous le tableau de fin. */
    open val endNote: String? = null
    /** Un passage par tour pour 7 personnes ; deux quand il n'y a que 3 ou 4 lignes. */
    val passes get() = if (rows.size >= 6) 1 else 2

    /** Le mot (ou le début de mot) de chaque ligne, pour la présentation : kita, kini, mo-, para sa… */
    abstract fun helpForm(row: Row): String
    /** Le titre de la carte de présentation (« LES 3 DISTANCES »). */
    open val helpTitle = "À RETENIR"
    /** Ce que veut dire chaque ligne, sur la carte de présentation (« devant un prénom »). */
    open fun helpLabel(row: Row) = row.label

    /** Une scène et ses phrases : chaque ligne de [plan], au 1er tour (faux) ou au 2e (vrai). Null si ça ne se fait pas. */
    abstract fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass?

    /** Une phrase fabriquée pour une séance, à la place de [source]. */
    abstract fun build(w: Words, source: Item): Built?

    /**
     * Une phrase fabriquée : la phrase [f], ses pièges (les réponses d'autres lignes, 3 au plus) et les traductions
     * d'autres lignes ([others]). Sans [gap], pas de phrase à trou (quand la réponse se colle au mot d'avant).
     */
    protected fun built(
        source: Item,
        f: Frase,
        traps: List<String>,
        others: List<Frase>,
        gap: Boolean = !f.glued,
        alts: List<String> = emptyList(),
    ): Built? {
        val taken = mutableSetOf(Answers.normalize(f.answer))
        f.accepted.keys.forEach { taken.add(Answers.normalize(it)) }
        val t = traps.filter { taken.add(Answers.normalize(it)) }.take(3)
        if (gap && t.size < 3) return null
        val index = Content.tokenize(f.before).size
        val french = mutableSetOf(Answers.normalize(f.french))
        return Built(
            source, f.sentence, f.french,
            f.accepted.keys.map { f.sentenceWith(it) } + alts,
            if (gap) (if (f.before.isEmpty()) "" else "${f.before} ") else null,
            if (gap) listOf(f.answer) + t else null,
            if (gap) (if (f.after.isEmpty()) "" else " ${f.after}") + f.end else null,
            t.map { Variants.Variant(f.sentenceWith(it), index, f.answer, it) },
            others.filter { french.add(Answers.normalize(it.french)) }.take(3).map { Example(it.sentence, it.french) },
        )
    }
}

/** Les rondes et les phrases fabriquées des 11 leçons. */
object Fabrique {
    /** Un peu plus de 8 phrases de leçon sur 10 sont fabriquées : les autres restent celles du cours. */
    const val PERCENT = 85

    private val themes: Map<Int, Theme> by lazy {
        listOf(
            Pronoms, Articles, Demonstratifs, Possessifs, De, Verbes.MO, Verbes.MAG, Lieux, PourQui, Liens, Revision,
        ).associateBy { it.lesson }
    }

    /** La grammaire d'une leçon (les arrêts des phrases compliquées), ou null. */
    fun theme(stop: Stop): Theme? = if (stop.line == Line.COMPLEX && stop.lesson != null) themes[stop.number] else null

    /** Une phrase fabriquée à la place de [item] (une phrase de leçon), d'au plus [maxTokens] mots. Null si ça ne se fait pas. */
    fun build(content: Content, progress: Progress?, item: Item, random: Random, maxTokens: Int = Int.MAX_VALUE): Built? {
        if (item.line != Line.COMPLEX) return null
        val theme = theme(content.stopOf(item)) ?: return null
        repeat(10) {
            val b = theme.build(Words(content, progress, random), item)
            if (b != null && b.tokens.size <= maxTokens) return b
        }
        return null
    }

    /**
     * Le sens d'un mot des phrases fabriquées qui n'est pas dans la liste des gloses :
     * un verbe conjugué (mokanta = va chanter), un adjectif relié (bag-ong = neuf + ng) ou un prénom.
     */
    fun gloss(word: String): String? {
        val w = word.lowercase()
        Lexique.prenoms.firstOrNull { it.name.lowercase() == w }?.let { return "${it.name} (un prénom)" }
        val il = Francais.Sujet("il", 2, false)
        fun bare(s: String) = s.removePrefix("il ")
        for (v in Lexique.verbes) when (w) {
            v.future -> return bare(v.fr.future(il))
            v.past -> return if (v.mag) "${bare(v.fr.past(il))}, ou ${bare(v.fr.now(il))}" else bare(v.fr.past(il))
            v.pastAlt -> return bare(v.fr.past(il))
            v.command -> return v.fr.order().replaceFirstChar { it.lowercase() } + " !"
        }
        Lexique.adjectifs.firstOrNull { it.fem?.lowercase() == w }?.let { return "${it.fr.forms[1]} (pour une femme)" }
        for (a in Lexique.adjectifs) for (form in listOfNotNull(a.ceb, a.fem)) {
            val row = Liens.rowOf(form) ?: continue
            if (row.key == "nga" || w != Liens.linked(form, row).lowercase()) continue
            return "${a.fr.forms[if (form == a.fem) 1 else 0]} ($form + ${row.key})"
        }
        return null
    }
}
