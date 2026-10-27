package com.thomas.bibisaya.engine

import kotlin.random.Random

/**
 * Les 7 personnes, avec leurs formes : ako · ko (leçon 1), akong · nako (leçon 4), kanako (leçon 9).
 * [subject] : la forme longue après le mot. Pour « tu », c'est ka (« Kusgan ka ») : ikaw ne se met qu'en début de phrase.
 */
enum class Person(
    val long: String,
    val subject: String,
    val short: String,
    val before: String,
    val after: String,
    /** La forme courte de [after] qu'on entend aussi (« balay ko »), ou null. */
    val afterShort: String?,
    /** Après un verbe (leçon 9) : kanako, kanimo… */
    val oblique: String,
    /** L'autre façon de le dire : sa ako, sa imo… */
    val saForm: String,
    /** « nous (sans toi) » (leçon 1), « à nous (sans toi) » (leçon 4), « moi », « toi »… (leçon 9). */
    val who: String,
    val whose: String,
    val stressed: String,
    /** Pour le dessin : moi, toi, et combien d'autres. */
    val me: Boolean,
    val you: Boolean,
    val others: Int,
) {
    ME("ako", "ako", "ko", "akong", "nako", "ko", "kanako", "sa ako", "je", "à moi", "moi", true, false, 0),
    YOU("ikaw", "ka", "ka", "imong", "nimo", "mo", "kanimo", "sa imo", "tu", "à toi", "toi", false, true, 0),
    HE("siya", "siya", "siya", "iyang", "niya", null, "kaniya", "sa iya", "il, elle", "à lui, à elle", "lui, elle", false, false, 1),
    WE_IN("kita", "kita", "ta", "atong", "nato", "ta", "kanato", "sa ato", "nous (avec toi)", "à nous (avec toi)", "nous (avec toi)", true, true, 0),
    WE_EX("kami", "kami", "mi", "among", "namo", null, "kanamo", "sa amo", "nous (sans toi)", "à nous (sans toi)", "nous (sans toi)", true, false, 1),
    YOU_PL("kamo", "kamo", "mo", "inyong", "ninyo", null, "kaninyo", "sa inyo", "vous", "à vous", "vous", false, true, 1),
    THEY("sila", "sila", "sila", "ilang", "nila", null, "kanila", "sa ila", "ils, elles", "à eux, à elles", "eux, elles", false, false, 2);

    val plural get() = this == WE_IN || this == WE_EX || this == YOU_PL || this == THEY

    /** La même personne en français : de 0 (je) à 5 (ils). */
    val fr: Int
        get() = when (this) {
            ME -> 0
            YOU -> 1
            HE -> 2
            WE_IN, WE_EX -> 3
            YOU_PL -> 4
            THEY -> 5
        }

    /** Le « nous » du cebuano, précisé en français : « (toi et moi) » ou « (pas toi) ». */
    val weNote: String
        get() = when (this) {
            WE_IN -> " (toi et moi)"
            WE_EX -> " (pas toi)"
            else -> ""
        }

    /** Il ou elle, ils ou elles : la seule personne où l'on choisit entre homme et femme. */
    val gendered get() = this == HE || this == THEY
}

/** Les mots des rondes des leçons 1 et 4, lus dans contenu.txt : des adjectifs (leçon 1) ou des noms (leçon 4). */
enum class RondeKind { PRONOUNS, POSSESSIVES }

/**
 * Un mot de la ronde : un adjectif avec ses 4 formes en français (fort, forte, forts, fortes),
 * ou un nom avec son article (la maison).
 */
class RondeWord(val ceb: String, val fr: List<String>)

/**
 * Une question de la ronde : une phrase à trou, pour une ligne (une personne, une distance, un moment…).
 * [scene] : le mot de la phrase (le même pendant tout un passage). Au 1er tour, on touche le bon mot parmi
 * [options] ; au 2e, on l'écrit.
 */
class RondeQuestion(
    val stop: Stop,
    val theme: Theme,
    val row: Row,
    val frase: Frase,
    val second: Boolean,
    val tap: Boolean,
    val options: List<String>,
    val scene: String,
) {
    val answer get() = frase.answer
    val before get() = frase.before
    val after get() = frase.after
    val sentence get() = frase.sentence
    val french get() = frase.french
    val person get() = row.person

    /** Ce que montre le bandeau : la ligne, ou ce que la phrase demande (« RELIE : dako + nga »). */
    val band get() = frase.band ?: row
    val question get() = frase.bandQuestion ?: theme.question

    fun sentenceWith(form: String) = frase.sentenceWith(form)
}

/** Le verdict d'une réponse de la ronde, avec une explication quand il y en a une. */
class RondeCheck(val correct: Boolean, val note: String?)

/**
 * La ronde d'une leçon : une phrase, et une seule partie change à chaque question (la personne, la distance,
 * le moment…). Un tour = chaque ligne dans le désordre (deux fois quand il n'y en a que 3 ou 4).
 * Au 1er tour on touche le bon mot, au 2e on l'écrit.
 */
object Ronde {
    /** Une petite ronde le soir au plus tous les 3 jours (une leçon après l'autre). */
    const val EVENING_GAP = 3
    /** La petite ronde du soir : 2 mots à toucher, 1 à écrire. */
    const val MINI = 3

    /** Le mot de la leçon 1 : pour « tu », après le mot, c'est ka. */
    const val YOU_NOTE = "Après le mot, « tu » se dit toujours **ka**. **Ikaw** se met au début de la phrase."

    /** Une série de phrases sur une même scène (quelques essais : certaines scènes ne vont pas avec toutes les lignes). */
    private fun pass(theme: Theme, w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        repeat(20) { theme.pass(w, plan)?.let { return it } }
        return null
    }

    /** Les choix gardent le même ordre pendant tout un tour : on retrouve vite chaque mot. */
    private class Orders(val random: Random) {
        private val orders = mutableMapOf<Set<String>, List<String>>()

        fun of(f: Frase): List<String> = orders.getOrPut(f.choices.map { Answers.normalize(it) }.toSet()) { f.choices.shuffled(random) }
    }

    /** La ronde complète : 2 tours, chacun avec sa petite présentation et une autre scène. */
    fun full(content: Content, progress: Progress, stop: Stop, today: Long, random: Random = Random.Default): Session {
        val theme = Fabrique.theme(stop)!!
        val w = Words(content, progress, random)
        val steps = mutableListOf<Step>()
        for (second in listOf(false, true)) {
            steps.add(RondeIntroStep(stop, second))
            val orders = Orders(random)
            repeat(theme.passes) {
                val plan = theme.rows.shuffled(random).map { it to second }
                val pass = pass(theme, w, plan) ?: return@repeat
                for ((row, f) in pass.frases) {
                    val options = if (second) emptyList() else orders.of(f)
                    steps.add(RondeStep(RondeQuestion(stop, theme, row, f, second, !second, options, pass.scene)))
                }
            }
        }
        return Session(content, progress, null, stop.line, today, steps, random, Mode.RONDE, stop)
    }

    /** La petite ronde du soir : 3 questions sur une scène, les lignes souvent ratées d'abord. */
    fun mini(content: Content, progress: Progress, stop: Stop, random: Random): List<RondeStep> {
        val theme = Fabrique.theme(stop) ?: return emptyList()
        val rows = theme.rows.shuffled(random).sortedByDescending { progress.misses(stop, it.key) }.take(MINI)
        val plan = rows.mapIndexed { i, r -> r to (i == rows.size - 1) }
        val pass = pass(theme, Words(content, progress, random), plan) ?: return emptyList()
        val orders = Orders(random)
        return pass.frases.mapIndexed { i, (row, f) ->
            val write = plan[i].second
            RondeStep(RondeQuestion(stop, theme, row, f, write, !write, if (write) emptyList() else orders.of(f), pass.scene))
        }
    }

    /**
     * La leçon de la petite ronde du soir : une leçon déjà vue, celle dont la ronde est la plus ancienne.
     * Null s'il n'y en a pas encore, ou s'il y a eu une ronde ces 3 derniers jours.
     */
    fun eveningLesson(content: Content, progress: Progress, today: Long): Stop? {
        val lessons = content.lessons.filter { Fabrique.theme(it) != null && (it.key in progress.lessonsSeen || it.key in progress.rondeDays) }
        if (lessons.isEmpty()) return null
        val last = progress.rondeDays.values.maxOrNull()
        if (last != null && today - last < EVENING_GAP) return null
        return lessons.minByOrNull { progress.rondeDays[it.key] ?: Long.MIN_VALUE }
    }

    /** Ce qui a été tapé à la place du trou : le mot seul, ou la phrase recopiée (« Kusgan ko »). */
    private fun typedWord(q: RondeQuestion, given: String): String {
        var words = Content.tokenize(given).map { Answers.normalize(it) }.filter { it.isNotEmpty() }
        val before = Content.tokenize(q.before).map { Answers.normalize(it) }
        val after = Content.tokenize(q.after).map { Answers.normalize(it) }
        if (words.size > before.size && words.take(before.size) == before) words = words.drop(before.size)
        if (words.size > after.size && words.takeLast(after.size) == after) words = words.dropLast(after.size)
        return words.joinToString("")
    }

    /**
     * Vérifie une réponse. Une autre forme juste (ako pour ko, mikaon pour nikaon) est acceptée avec un petit rappel ;
     * les erreurs prévues ont leur explication. Pas de faute de frappe permise : ces mots sont trop courts.
     */
    fun check(q: RondeQuestion, given: String): RondeCheck {
        val f = q.frase
        // La phrase recopiée en entier (aussi quand le lien se colle au mot : « Dakong balay kini. »).
        val whole = Answers.normalize(given)
        if (whole == Answers.normalize(f.sentence)) return RondeCheck(true, f.rightNote)
        for ((form, note) in f.accepted) if (whole == Answers.normalize(f.sentenceWith(form))) return RondeCheck(true, note)
        val g = typedWord(q, given)
        if (g == Answers.normalize(f.answer)) return RondeCheck(true, f.rightNote)
        for ((form, note) in f.accepted) if (g == Answers.normalize(form)) return RondeCheck(true, note)
        for ((form, note) in f.wrong) if (g == Answers.normalize(form)) return RondeCheck(false, note)
        return RondeCheck(false, "La bonne réponse est **${f.answer}**." + if (f.glued) " ${f.sentence}" else "")
    }
}
