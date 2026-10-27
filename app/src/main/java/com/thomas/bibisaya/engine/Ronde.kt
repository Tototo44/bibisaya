package com.thomas.bibisaya.engine

import kotlin.random.Random

/**
 * Les 7 personnes de la ronde, avec leurs formes : ako · ko (leçon 1), akong · nako (leçon 4).
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
    /** « nous (sans toi) » (leçon 1), « à nous (sans toi) » (leçon 4). */
    val who: String,
    val whose: String,
    /** Pour le dessin : moi, toi, et combien d'autres. */
    val me: Boolean,
    val you: Boolean,
    val others: Int,
) {
    ME("ako", "ako", "ko", "akong", "nako", "ko", "je", "à moi", true, false, 0),
    YOU("ikaw", "ka", "ka", "imong", "nimo", "mo", "tu", "à toi", false, true, 0),
    HE("siya", "siya", "siya", "iyang", "niya", null, "il, elle", "à lui, à elle", false, false, 1),
    WE_IN("kita", "kita", "ta", "atong", "nato", "ta", "nous (avec toi)", "à nous (avec toi)", true, true, 0),
    WE_EX("kami", "kami", "mi", "among", "namo", null, "nous (sans toi)", "à nous (sans toi)", true, false, 1),
    YOU_PL("kamo", "kamo", "mo", "inyong", "ninyo", null, "vous", "à vous", false, true, 1),
    THEY("sila", "sila", "sila", "ilang", "nila", null, "ils, elles", "à eux, à elles", false, false, 2),
}

/** La ronde de la leçon 1 (ako, ka…) ou de la leçon 4 (akong, nako…). */
enum class RondeKind(
    /** Ce que demande chaque tour. */
    val rounds: Pair<String, String>,
    /** Les colonnes du tableau de fin. */
    val columns: Pair<String, String>,
    val question: String,
) {
    PRONOUNS("formes longues" to "formes courtes", "LONGUE" to "COURTE", "QUI ?"),
    POSSESSIVES("avant le mot" to "après le mot", "AVANT" to "APRÈS", "À QUI ?");

    /** La bonne réponse : au 1er tour la forme longue (ou le mot avant), au 2e la forme courte (ou le mot après). */
    fun form(p: Person, second: Boolean): String = when (this) {
        PRONOUNS -> if (second) p.short else p.subject
        POSSESSIVES -> if (second) p.after else p.before
    }

    fun label(p: Person): String = if (this == PRONOUNS) p.who else p.whose

    fun round(second: Boolean): String = if (second) rounds.second else rounds.first

    /** Toutes les formes d'une personne, pour reconnaître une réponse. */
    fun forms(p: Person): List<String> = when (this) {
        PRONOUNS -> listOf(p.subject, p.short, p.long)
        POSSESSIVES -> listOfNotNull(p.before, p.after, p.afterShort)
    }
}

/**
 * Un mot de la ronde : un adjectif avec ses 4 formes en français (fort, forte, forts, fortes),
 * ou un nom avec son article (la maison).
 */
class RondeWord(val ceb: String, val fr: List<String>)

/** Une question de la ronde : une phrase à trou, pour une personne. */
class RondeQuestion(
    val stop: Stop,
    val kind: RondeKind,
    val word: RondeWord,
    val person: Person,
    /** 2e tour : la forme courte (leçon 1), le mot après la chose (leçon 4). */
    val second: Boolean,
    /** Toucher le bon mot parmi [options], sinon l'écrire. */
    val tap: Boolean,
    val options: List<String>,
    /** « Elle est forte » plutôt que « Il est fort » (pour il, elle et ils, elles). */
    val feminine: Boolean,
) {
    val answer: String get() = kind.form(person, second)

    /** Le texte avant le trou : « Kusgan », « Kini ang », « Kini ang balay ». */
    val before: String
        get() = when {
            kind == RondeKind.PRONOUNS -> word.ceb.replaceFirstChar { it.uppercase() }
            second -> "Kini ang ${word.ceb}"
            else -> "Kini ang"
        }

    /** Le texte après le trou : « balay » (leçon 4, 1er tour), sinon rien. */
    val after: String get() = if (kind == RondeKind.POSSESSIVES && !second) word.ceb else ""

    /** La phrase complète : « Kusgan kami. » */
    val sentence: String get() = sentenceWith(answer)

    fun sentenceWith(form: String): String = listOf(before, form, after).filter { it.isNotEmpty() }.joinToString(" ") + "."

    /** « Nous sommes forts (pas toi). », « Voici notre maison (pas à toi). » */
    val french: String
        get() = if (kind == RondeKind.PRONOUNS) frenchPronoun() else frenchPossessive()

    private fun frenchPronoun(): String {
        val (m, f, mp, fp) = word.fr
        return when (person) {
            Person.ME -> "Je suis $m."
            Person.YOU -> "Tu es $m."
            Person.HE -> if (feminine) "Elle est $f." else "Il est $m."
            Person.WE_IN -> "Nous sommes $mp (toi et moi)."
            Person.WE_EX -> "Nous sommes $mp (pas toi)."
            Person.YOU_PL -> "Vous êtes $mp."
            Person.THEY -> if (feminine) "Elles sont $fp." else "Ils sont $mp."
        }
    }

    private fun frenchPossessive(): String {
        val full = word.fr.first()
        val noun = full.substringAfter("'").removePrefix("le ").removePrefix("la ")
        // « ma maison », mais « mon école » : devant une voyelle, toujours mon, ton, son.
        val feminine = full.startsWith("la ")
        val mine = when (person) {
            Person.ME -> if (feminine) "ma" else "mon"
            Person.YOU -> if (feminine) "ta" else "ton"
            Person.HE -> if (feminine) "sa" else "son"
            Person.WE_IN, Person.WE_EX -> "notre"
            Person.YOU_PL -> "votre"
            Person.THEY -> "leur"
        }
        val note = when (person) {
            Person.WE_IN -> " (à toi et moi)"
            Person.WE_EX -> " (pas à toi)"
            else -> ""
        }
        return "Voici $mine $noun$note."
    }
}

/** Le verdict d'une réponse de la ronde, avec une explication quand il y en a une. */
class RondeCheck(val correct: Boolean, val note: String?)

/**
 * « La ronde des personnes » (leçons 1 et 4) : une phrase, et la personne change à chaque question.
 * Un tour = les 7 personnes dans le désordre.
 */
object Ronde {
    /** Une petite ronde le soir au plus tous les 3 jours (une leçon après l'autre). */
    const val EVENING_GAP = 3
    /** La petite ronde du soir : 2 mots à toucher, 1 à écrire. */
    const val MINI = 3

    /** Le mot de la leçon 1 : pour « tu », après le mot, c'est ka. */
    const val YOU_NOTE = "Après le mot, « tu » se dit toujours **ka**. **Ikaw** se met au début de la phrase."

    /** Le mot du vocabulaire qui correspond à un mot de la ronde. */
    fun vocab(content: Content, word: RondeWord): Item? =
        content.itemsWithForm(Answers.normalize(word.ceb)).firstOrNull { it.line == Line.VOCAB }

    /** Les mots de la ronde, ceux déjà vus d'abord (chaque fois dans un autre ordre). */
    fun words(content: Content, progress: Progress, stop: Stop, random: Random): List<RondeWord> {
        val all = stop.lesson?.rondeWords ?: emptyList()
        val (seen, other) = all.shuffled(random).partition { w -> vocab(content, w)?.let { progress.isIntroduced(it) } == true }
        return seen + other
    }

    /** La ronde complète : 2 tours de 7 questions, chacun avec un autre mot et sa petite présentation. */
    fun full(content: Content, progress: Progress, stop: Stop, today: Long, random: Random = Random.Default): Session {
        val kind = stop.lesson!!.ronde!!
        val words = words(content, progress, stop, random)
        val steps = mutableListOf<Step>()
        for (second in listOf(false, true)) {
            val word = words[if (second) 1 % words.size else 0]
            // Au 1er tour, les 7 mots restent affichés, dans le même ordre pour tout le tour.
            val options = if (second) emptyList() else Person.values().map { kind.form(it, false) }.shuffled(random)
            steps.add(RondeIntroStep(stop, second))
            for (p in Person.values().toList().shuffled(random)) {
                steps.add(RondeStep(RondeQuestion(stop, kind, word, p, second, !second, options, random.nextBoolean())))
            }
        }
        return Session(content, progress, null, stop.line, today, steps, random, Mode.RONDE, stop)
    }

    /** La petite ronde du soir : 3 questions sur un mot, les personnes souvent ratées d'abord. */
    fun mini(content: Content, progress: Progress, stop: Stop, random: Random): List<RondeStep> {
        val kind = stop.lesson?.ronde ?: return emptyList()
        val word = words(content, progress, stop, random).firstOrNull() ?: return emptyList()
        val persons = Person.values().toList().shuffled(random).sortedByDescending { progress.misses(stop, it) }.take(MINI)
        val options = Person.values().map { kind.form(it, false) }.shuffled(random)
        return persons.mapIndexed { i, p ->
            val write = i == persons.size - 1
            RondeStep(RondeQuestion(stop, kind, word, p, write, !write, if (write) emptyList() else options, random.nextBoolean()))
        }
    }

    /**
     * La leçon de la petite ronde du soir : une leçon déjà vue, celle dont la ronde est la plus ancienne.
     * Null s'il n'y en a pas encore, ou s'il y a eu une ronde ces 3 derniers jours.
     */
    fun eveningLesson(content: Content, progress: Progress, today: Long): Stop? {
        val lessons = content.lessons.filter { it.lesson?.ronde != null && (it.key in progress.lessonsSeen || it.key in progress.rondeDays) }
        if (lessons.isEmpty()) return null
        val last = progress.rondeDays.values.maxOrNull()
        if (last != null && today - last < EVENING_GAP) return null
        return lessons.minByOrNull { progress.rondeDays[it.key] ?: Long.MIN_VALUE }
    }

    /** Le mot tapé : seul, ou au milieu de la phrase recopiée (« Kusgan ko »). */
    private fun typedWord(q: RondeQuestion, given: String): String {
        val words = Content.tokenize(given).map { Answers.normalize(it) }.filter { it.isNotEmpty() }
        if (words.size <= 1) return words.firstOrNull() ?: ""
        val left = words.toMutableList()
        for (w in Content.tokenize("${q.before} ${q.after}")) left.remove(Answers.normalize(w))
        return if (left.size == 1) left.single() else words.joinToString("")
    }

    /**
     * Vérifie une réponse. L'autre forme de la bonne personne est juste aussi (ako pour ko, balay ko pour balay nako),
     * avec un petit rappel. Pas de faute de frappe permise : ces mots sont trop courts.
     */
    fun check(q: RondeQuestion, given: String): RondeCheck {
        val g = typedWord(q, given)
        val kind = q.kind
        val p = q.person
        val answer = q.answer
        fun same(form: String?) = form != null && g == Answers.normalize(form)
        if (same(answer)) return RondeCheck(true, if (kind == RondeKind.PRONOUNS && p == Person.YOU && !q.second) YOU_NOTE else null)
        if (kind == RondeKind.PRONOUNS && kind.forms(p).any { same(it) }) {
            val note = if (p == Person.YOU) "Après le mot, on dit **ka** : ${q.sentence} **Ikaw** se met au début de la phrase."
            else "La forme ${if (q.second) "courte" else "longue"}, c'est **$answer** : ${q.sentence}"
            return RondeCheck(true, note)
        }
        if (kind == RondeKind.POSSESSIVES && q.second && same(p.afterShort)) {
            return RondeCheck(true, "On dit aussi **${p.after}** : ${q.sentence}")
        }
        val w = q.word.ceb
        if (kind == RondeKind.POSSESSIVES && q.second && same(p.before)) {
            return RondeCheck(false, "**${p.before}** se met avant la chose : ${p.before} $w. Après, c'est **${p.after}** : $w ${p.after}.")
        }
        if (kind == RondeKind.POSSESSIVES && !q.second && (same(p.after) || same(p.afterShort))) {
            return RondeCheck(false, "**${p.after}** se met après la chose : $w ${p.after}. Avant, c'est **${p.before}** : ${p.before} $w.")
        }
        for (o in Person.values()) {
            val form = kind.forms(o).firstOrNull { same(it) } ?: continue
            if (o == p) continue
            return RondeCheck(false, "**$form**, c'est « ${kind.label(o)} ». Pour « ${kind.label(p)} », c'est **$answer**.")
        }
        // Un mot de l'autre leçon (nako pour ko…).
        val other = RondeKind.values().first { it != kind }
        for (o in Person.values()) {
            val form = other.forms(o).firstOrNull { same(it) } ?: continue
            val lesson = if (other == RondeKind.PRONOUNS) "la leçon 1" else "la leçon 4"
            return RondeCheck(false, "**$form**, c'est « ${other.label(o)} » ($lesson). Ici, c'est **$answer**.")
        }
        return RondeCheck(false, "La bonne réponse est **$answer**.")
    }
}
