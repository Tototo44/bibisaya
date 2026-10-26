package com.thomas.bibisaya.engine

import kotlin.random.Random

/** Les types d'exercices. */
enum class Kind {
    /** On lit le français, on choisit le cebuano. */
    CHOOSE_CEB,
    /** On lit le cebuano, on choisit le français. */
    CHOOSE_FR,
    /** Phrase à trou : on choisit le mot qui manque. */
    GAP,
    /** Relier 3 à 5 mots à leur traduction. */
    MATCH,
    /** Remettre la phrase dans l'ordre avec des étiquettes. */
    ORDER,
    /** Écrire le mot (ou la phrase) en cebuano. */
    WRITE,
    /** On lit le cebuano, on écrit en français (mode « Traduire »). */
    TRANSLATE_FR,
}

/** Ce que fait la série de questions : une séance du jour (ou un bonus), une traduction, ou s'entraîner sur une leçon. */
enum class Mode {
    SESSION,
    TRANSLATE_WORDS,
    TRANSLATE_PHRASES,
    LESSON;

    val isTranslation get() = this == TRANSLATE_WORDS || this == TRANSLATE_PHRASES
}

sealed class Step

/** La petite leçon au début d'un arrêt des phrases compliquées. */
class LessonStep(val stop: Stop) : Step()

/** La carte « nouveau mot » ou « nouvelle phrase ». */
class NewStep(val item: Item) : Step()

/**
 * Un exercice. [options] : les 4 choix (CHOOSE, GAP) ou les étiquettes (ORDER).
 * [graded] est faux pour une question qui revient après une erreur : elle ne compte pas dans les 10.
 */
class Exercise(
    val kind: Kind,
    val items: List<Item>,
    val options: List<String>,
    val graded: Boolean,
    /** Pour MATCH : l'ordre des traductions dans la colonne de droite. */
    val rightOrder: List<Int> = emptyList(),
) : Step() {
    val item get() = items.first()

    /** La bonne réponse parmi [options] (CHOOSE et GAP). */
    val answer: String
        get() = when (kind) {
            Kind.CHOOSE_CEB -> item.ceb
            Kind.CHOOSE_FR -> item.fr
            Kind.GAP -> item.gapAnswer!!
            Kind.TRANSLATE_FR -> item.fr
            else -> item.ceb
        }
}

/** Ce que l'appli montre après une réponse. */
class Feedback(val correct: Boolean, val change: Change?, val retry: Boolean)

/** Une séance de 10 exercices, en train de se dérouler. */
class Session(
    private val content: Content,
    private val progress: Progress,
    val slot: Slot?,
    /** La ligne de la séance, ou null quand la séance mélange les lignes. */
    val line: Line?,
    val today: Long,
    val steps: MutableList<Step>,
    private val random: Random,
    val mode: Mode = Mode.SESSION,
    /** L'arrêt (la leçon) travaillé, en mode LESSON. */
    val stop: Stop? = null,
) {
    var index = 0
        private set
    var correctFirst = 0
        private set
    var gradedAnswered = 0
        private set
    val newItems = mutableListOf<Item>()
    /** Les mots ratés du premier coup (la liste « À revoir »). */
    val missed = mutableListOf<Item>()
    private val retried = mutableSetOf<String>()
    private var undo: Undo? = null

    /** De quoi annuler la dernière réponse (« Ma réponse était juste aussi »). */
    private class Undo(
        val ex: Exercise,
        val states: Map<String, ItemState?>,
        val gradedAnswered: Int,
        val correctFirst: Int,
        val missed: Int,
        val retried: Set<String>,
        val steps: List<Step>,
    )

    val current: Step? get() = steps.getOrNull(index)
    val gradedTotal: Int get() = steps.count { it is Exercise && it.graded }
    val finished get() = index >= steps.size
    val mixed get() = line == null

    /** À appeler quand une étape s'affiche (le mot est alors « vu »). */
    fun onShown(step: Step) {
        when (step) {
            is NewStep -> {
                if (!progress.isIntroduced(step.item)) newItems.add(step.item)
                progress.introduce(step.item, today)
            }
            is LessonStep -> progress.lessonsSeen.add(step.stop.key)
            is Exercise -> step.items.forEach { progress.introduce(it, today) }
        }
    }

    /**
     * Enregistre une réponse. Pour MATCH, [wrongItems] contient les mots mal reliés au moins une fois.
     * Un mot raté revient un peu plus tard dans la séance (une seule fois).
     */
    fun submit(ex: Exercise, correct: Boolean, wrongItems: Set<Item> = emptySet()): Feedback {
        undo = Undo(
            ex,
            ex.items.associate { it.id to progress.state(it)?.let { s -> ItemState(s.level, s.dueDay, s.lastUpDay, s.introDay) } },
            gradedAnswered, correctFirst, missed.size, retried.toSet(), steps.toList(),
        )
        if (ex.graded) {
            gradedAnswered++
            if (correct) correctFirst++
        }
        if (!ex.graded) return Feedback(correct, null, retry = false)
        val failed = if (ex.kind == Kind.MATCH) wrongItems.toList() else if (!correct) listOf(ex.item) else emptyList()
        missed.addAll(failed.filter { it !in missed })
        var change: Change? = null
        for (item in ex.items) {
            val c = progress.answer(item, item !in failed, today)
            if (item == ex.item) change = c
        }
        var retry = false
        // En mode « Traduire », pas de deuxième essai : la liste « À revoir » le dit à la fin.
        if (mode.isTranslation) return Feedback(correct, change, retry = false)
        for (item in failed.take(2)) {
            if (!retried.add(item.id)) continue
            val kind = when {
                ex.kind != Kind.MATCH -> ex.kind
                item.isWord -> Kind.CHOOSE_CEB
                else -> Planner.firstKind(item)
            }
            val again = Planner.exercise(content, kind, item, graded = false, random = random)
            // Un peu plus tard, mais jamais entre une carte « nouveau mot » et sa question.
            var at = (index + 3).coerceAtMost(steps.size)
            while (at < steps.size && (steps[at - 1] is NewStep || steps[at - 1] is LessonStep)) at++
            steps.add(at, again)
            retry = true
        }
        return Feedback(correct, change, retry)
    }

    /** « Ma réponse était juste aussi » : la dernière réponse, comptée fausse, devient juste. */
    fun acceptLast(): Feedback? {
        val u = undo ?: return null
        for ((id, state) in u.states) if (state == null) progress.items.remove(id) else progress.items[id] = state
        gradedAnswered = u.gradedAnswered
        correctFirst = u.correctFirst
        while (missed.size > u.missed) missed.removeAt(missed.size - 1)
        retried.retainAll(u.retried)
        // On retire la question qui devait revenir, sans toucher à celle qui est à l'écran.
        val kept = u.steps.toSet()
        steps.removeAll { it !in kept }
        undo = null
        return submit(u.ex, true)
    }

    fun next() {
        index++
    }

    /** « 4/10 » : la question en cours parmi les 10. */
    val counter: Int get() = (gradedAnswered + 1).coerceAtMost(gradedTotal)
}

/** Prépare les séances : quelques nouveautés, puis des révisions, le plus fragile d'abord. */
object Planner {
    const val EXERCISES = 10
    /** Une phrase à écrire en entier pendant une séance : pas plus de 6 mots. */
    const val MAX_WRITE_TOKENS = 6
    /** En mode « Traduire », une phrase à écrire en cebuano : pas plus de 8 mots. */
    const val MAX_TRANSLATE_TOKENS = 8

    fun newQuota(line: Line) = when (line) {
        Line.VOCAB -> 3
        Line.SIMPLE, Line.COMPLEX -> 2
    }

    /** [line] = la ligne de la séance, ou null pour une séance bonus (les 3 lignes mélangées). */
    fun build(content: Content, progress: Progress, slot: Slot?, line: Line?, today: Long, random: Random = Random.Default): Session {
        val newItems = mutableListOf<Item>()
        val reviewLines: List<Line>
        val sessionLine: Line?
        if (line != null && !progress.lineFinished(content, line)) {
            // La ligne de la séance : ses nouveautés (s'il y en a) et ses révisions.
            val seen = content.items(line).count { progress.isIntroduced(it) }
            val quota = newQuota(line) + if (seen < 6) 1 else 0
            newItems += progress.nextNewItems(content, line, quota)
            reviewLines = listOf(line)
            sessionLine = line
        } else {
            // Séance bonus, ou ligne finie : on fait avancer les autres lignes et on révise tout.
            val quota = if (line == null) 2 else 3
            newItems += mixedNewItems(content, progress, exclude = line, quota = quota)
            reviewLines = Line.values().toList()
            sessionLine = null
        }

        val pool = content.items
            .filter { it.line in reviewLines && progress.isIntroduced(it) && it !in newItems }
            .map { it to progress.state(it)!! }
            .sortedWith(compareBy<Pair<Item, ItemState>>({ if (it.second.dueDay <= today) 0 else 1 }, { it.second.level }, { it.second.dueDay }))
            .map { it.first }
            .let { shuffleTies(it, progress, today, random) }

        val steps = mutableListOf<Step>()
        val graded = mutableListOf<Exercise>()
        val reviews = ArrayDeque(pool)

        // Le jeu « relier » : les nouveautés et quelques révisions courtes de la même ligne.
        val match = buildMatch(content, newItems, pool, progress, random)
        val introCount = newItems.size
        val reviewCount = EXERCISES - introCount - (if (match != null) 1 else 0)
        val reviewExercises = mutableListOf<Exercise>()
        while (reviewExercises.size < reviewCount && reviews.isNotEmpty()) {
            val item = reviews.removeFirst()
            reviewExercises.add(exercise(content, reviewKind(item, progress.level(item), random), item, true, random))
        }
        // Pas assez de révisions (premières séances) : une 2e question sur les nouveautés, puis encore une.
        var round = 0
        while (reviewExercises.size < reviewCount && (newItems.isNotEmpty() || pool.isNotEmpty()) && round < 6) {
            val source = if (newItems.isNotEmpty()) newItems else pool
            for (item in source) {
                if (reviewExercises.size >= reviewCount) break
                reviewExercises.add(exercise(content, secondKind(item, round), item, true, random))
            }
            round++
        }

        val lessonsAdded = mutableSetOf<String>()
        val reviewQueue = ArrayDeque(reviewExercises)
        for (item in newItems) {
            val stop = content.stopOf(item)
            if (stop.lesson != null && stop.key !in progress.lessonsSeen && lessonsAdded.add(stop.key)) {
                steps.add(LessonStep(stop))
            }
            steps.add(NewStep(item))
            val intro = exercise(content, firstKind(item), item, true, random)
            steps.add(intro)
            graded.add(intro)
            reviewQueue.removeFirstOrNull()?.let { steps.add(it); graded.add(it) }
        }
        if (match != null) {
            steps.add(match)
            graded.add(match)
        }
        while (reviewQueue.isNotEmpty()) {
            val ex = reviewQueue.removeFirst()
            steps.add(ex)
            graded.add(ex)
        }
        return Session(content, progress, slot, sessionLine, today, steps, random)
    }

    /** Les nouveautés encore à découvrir, toutes lignes confondues. */
    fun newItemsLeft(content: Content, progress: Progress): List<Line> =
        Line.values().filter { progress.hasNewItems(content, it) }

    private fun mixedNewItems(content: Content, progress: Progress, exclude: Line?, quota: Int): List<Item> {
        val lines = Line.values().filter { it != exclude && progress.hasNewItems(content, it) }
            .ifEmpty { Line.values().filter { progress.hasNewItems(content, it) } }
        val taken = mutableMapOf<Line, Int>()
        val out = mutableListOf<Item>()
        var progressMade = true
        while (out.size < quota && progressMade) {
            progressMade = false
            for (l in lines) {
                if (out.size >= quota) break
                val n = taken.getOrDefault(l, 0)
                val next = progress.nextNewItems(content, l, n + 1).getOrNull(n) ?: continue
                out.add(next)
                taken[l] = n + 1
                progressMade = true
            }
        }
        return out
    }

    /** Mélange un peu les mots de même priorité, pour ne pas toujours voir le même ordre. */
    private fun shuffleTies(items: List<Item>, progress: Progress, today: Long, random: Random): List<Item> {
        val groups = items.groupBy { val s = progress.state(it)!!; (if (s.dueDay <= today) 0 else 1) * 10 + s.level }
        return groups.keys.sorted().flatMap { groups.getValue(it).shuffled(random) }
    }

    /** La question juste après la carte « nouveau mot ». */
    fun firstKind(item: Item): Kind = when {
        item.line == Line.COMPLEX && item.hasGap -> Kind.GAP
        item.line == Line.COMPLEX -> Kind.ORDER
        else -> Kind.CHOOSE_CEB
    }

    private fun canOrder(item: Item) = item.tokens.size >= 2

    /** Assez court pour l'écrire en entier. */
    fun canWrite(item: Item) = item.isWord || item.tokens.size <= MAX_WRITE_TOKENS

    /** Plus un mot est connu, plus on l'écrit soi-même (au lieu de le choisir). */
    fun reviewKind(item: Item, level: Int, random: Random): Kind {
        val k = when (item.line) {
            Line.VOCAB -> when {
                level <= 1 -> if (random.nextBoolean()) Kind.CHOOSE_CEB else Kind.CHOOSE_FR
                level == 2 -> listOf(Kind.CHOOSE_FR, Kind.WRITE, Kind.CHOOSE_CEB).random(random)
                else -> if (random.nextInt(10) < 8) Kind.WRITE else Kind.CHOOSE_CEB
            }
            Line.SIMPLE -> when {
                level <= 1 -> Kind.CHOOSE_CEB
                level == 2 -> if (random.nextBoolean()) Kind.ORDER else Kind.CHOOSE_FR
                canWrite(item) && random.nextInt(10) < 6 -> Kind.WRITE
                else -> Kind.ORDER
            }
            Line.COMPLEX -> when {
                level <= 1 -> if (item.hasGap) Kind.GAP else Kind.ORDER
                level == 2 -> if (item.hasGap && random.nextBoolean()) Kind.GAP else Kind.ORDER
                canWrite(item) && random.nextInt(10) < 4 -> Kind.WRITE
                else -> Kind.ORDER
            }
        }
        return if (k == Kind.ORDER && !canOrder(item)) Kind.CHOOSE_CEB else k
    }

    /** Une question de plus sur un mot déjà vu dans la séance (au début, quand il y a encore peu à réviser). */
    private fun secondKind(item: Item, round: Int): Kind {
        val cycle = when (item.line) {
            Line.VOCAB -> listOf(Kind.CHOOSE_FR, Kind.CHOOSE_CEB, Kind.WRITE)
            Line.SIMPLE -> listOf(Kind.ORDER, Kind.CHOOSE_FR, Kind.CHOOSE_CEB)
            Line.COMPLEX -> if (item.hasGap) listOf(Kind.ORDER, Kind.CHOOSE_FR, Kind.CHOOSE_CEB) else listOf(Kind.CHOOSE_FR, Kind.CHOOSE_CEB, Kind.ORDER)
        }
        val k = cycle[round % cycle.size]
        return if (k == Kind.ORDER && !canOrder(item)) Kind.CHOOSE_FR else k
    }

    fun exercise(content: Content, kind: Kind, item: Item, graded: Boolean, random: Random): Exercise = when (kind) {
        Kind.CHOOSE_CEB -> Exercise(kind, listOf(item), if (item.isWord) choices(content, item, random) { it.ceb } else phraseChoices(content, item, random, ceb = true), graded)
        Kind.CHOOSE_FR -> Exercise(kind, listOf(item), if (item.isWord) choices(content, item, random) { it.fr } else phraseChoices(content, item, random, ceb = false), graded)
        Kind.GAP -> Exercise(kind, listOf(item), item.gapOptions!!.shuffled(random), graded)
        Kind.ORDER -> Exercise(kind, listOf(item), tiles(content, item, random), graded)
        Kind.WRITE, Kind.TRANSLATE_FR -> Exercise(kind, listOf(item), emptyList(), graded)
        Kind.MATCH -> error("MATCH se prépare avec buildMatch")
    }

    /** La forme d'une phrase : question, exclamation ou phrase simple, et avec ou sans nom propre. */
    private fun shape(text: String): Pair<Char, Boolean> {
        val end = text.trimEnd().lastOrNull { it in "?!." } ?: '.'
        return end to Content.tokenize(text).drop(1).any { it.first().isUpperCase() }
    }

    /**
     * 4 phrases de même forme (que des questions, ou toutes avec un prénom…) : on ne peut plus éliminer.
     * En cebuano, des pièges où un seul mot change (ako / ikaw, nagtrabaho / magtrabaho…).
     */
    fun phraseChoices(content: Content, item: Item, random: Random, ceb: Boolean): List<String> {
        val text: (Item) -> String = if (ceb) { i -> i.ceb } else { i -> i.fr }
        val answer = text(item)
        val taken = mutableSetOf(Answers.normalize(answer))
        if (ceb) item.alts.forEach { taken.add(Answers.normalize(it)) }
        val out = mutableListOf(answer)
        if (ceb) {
            for (v in Variants.pick(content, item, random)) if (taken.add(Answers.normalize(v.text))) out.add(v.text)
        }
        val form = shape(answer)
        val candidates = content.items(item.line).filter { it != item && (item.syn == null || it.syn != item.syn) }
        val tiers = listOf(
            candidates.filter { it.stop == item.stop },
            candidates.filter { it.stop != item.stop && kotlin.math.abs(it.stop - item.stop) <= 2 },
            candidates.filter { kotlin.math.abs(it.stop - item.stop) > 2 },
        )
        // D'abord la même forme et une longueur proche, puis le reste.
        fun gap(c: Item) = kotlin.math.abs(c.tokens.size - item.tokens.size).let { if (it <= 1) 0 else if (it <= 3) 1 else 2 }
        val same = tiers.flatMap { tier -> tier.filter { shape(text(it)) == form }.shuffled(random).sortedBy { gap(it) } }
        val others = tiers.flatMap { tier -> tier.filter { shape(text(it)) != form }.shuffled(random) }
        for (c in same + others) {
            if (out.size >= 4) break
            if (taken.add(Answers.normalize(text(c)))) out.add(text(c))
        }
        return out.shuffled(random)
    }

    /** 4 choix : la bonne réponse et 3 pièges pris dans la même ligne, de préférence dans le même arrêt. */
    fun choices(content: Content, item: Item, random: Random, text: (Item) -> String): List<String> {
        val answer = text(item)
        val taken = mutableSetOf(Answers.normalize(answer))
        val out = mutableListOf(answer)
        val candidates = content.items(item.line).filter { it != item && (item.syn == null || it.syn != item.syn) }
        val near = candidates.filter { it.stop == item.stop }.shuffled(random)
        val close = candidates.filter { it.stop != item.stop && kotlin.math.abs(it.stop - item.stop) <= 2 }.shuffled(random)
        val far = candidates.filter { kotlin.math.abs(it.stop - item.stop) > 2 }.shuffled(random)
        for (c in near + close + far) {
            if (out.size >= 4) break
            if (taken.add(Answers.normalize(text(c)))) out.add(text(c))
        }
        return out.shuffled(random)
    }

    /** Une étiquette en minuscule (la majuscule du début de phrase trahirait le premier mot), sauf un nom propre. */
    fun tileText(content: Content, token: String): String =
        if (Answers.normalize(token) in content.properNouns) token else token.replaceFirstChar { it.lowercase() }

    /** Les étiquettes : les mots de la phrase, plus 1 ou 2 pièges qui leur ressemblent, bien mélangés. */
    fun tiles(content: Content, item: Item, random: Random): List<String> {
        val words = item.tokens.map { tileText(content, it) }
        val inSentence = words.map { Answers.normalize(it) }.toSet()
        val count = if (words.size <= 3) 1 else 2
        val traps = mutableListOf<String>()
        val places = mutableSetOf<Int>()
        // D'abord des mots qui ressemblent à un mot de la phrase (ka pour ko, magtrabaho pour nagtrabaho)…
        for ((index, word) in Variants.lookAlikes(content, item).shuffled(random)) {
            if (traps.size >= count) break
            if (index in places || Answers.normalize(word) in inSentence) continue
            traps.add(tileText(content, word))
            places.add(index)
        }
        // … sinon des mots d'autres phrases de la même ligne.
        if (traps.size < count) {
            traps += content.items(item.line)
                .filter { it != item }
                .flatMap { other -> other.tokens.mapIndexedNotNull { i, t -> if (i == 0) tileText(content, t) else if (t.first().isUpperCase()) null else t } }
                .filter { Answers.normalize(it) !in inSentence && Answers.normalize(it).length >= 2 && traps.none { t -> Answers.same(t, it) } }
                .distinctBy { Answers.normalize(it) }
                .shuffled(random)
                .take(count - traps.size)
        }
        var mixed = (words + traps).shuffled(random)
        var tries = 0
        while (mixed.take(words.size) == words && tries++ < 10) mixed = mixed.shuffled(random)
        return mixed
    }

    /**
     * « Traduire » : 10 mots (ou phrases) déjà vus, les plus fragiles d'abord. 8 à écrire en cebuano,
     * 2 à écrire en français. [avoid] : ceux de la traduction d'avant, gardés pour plus tard.
     * Null s'il n'y a encore rien à traduire.
     */
    fun buildTranslation(
        content: Content,
        progress: Progress,
        words: Boolean,
        today: Long,
        random: Random = Random.Default,
        avoid: Set<String> = emptySet(),
    ): Session? {
        val lines = if (words) listOf(Line.VOCAB) else listOf(Line.SIMPLE, Line.COMPLEX)
        val seen = content.items.filter { it.line in lines && progress.isIntroduced(it) }
        if (seen.isEmpty()) return null
        val ranked = seen.shuffled(random).sortedWith(
            compareBy<Item>({ it.id in avoid }, { progress.level(it) }, { progress.state(it)!!.dueDay })
        )
        val total = minOf(EXERCISES, seen.size)
        val inverse = total / 5
        val toCeb = ranked.filter { words || it.tokens.size <= MAX_TRANSLATE_TOKENS }.take(total - inverse)
        val toFr = ranked.filter { it !in toCeb }.take(total - toCeb.size)
        val steps = mutableListOf<Step>()
        toCeb.shuffled(random).forEach { steps.add(exercise(content, Kind.WRITE, it, true, random)) }
        // Vers le français : à la 4e et à la 8e question.
        toFr.forEachIndexed { k, item ->
            val at = (3 + 4 * k).coerceAtMost(steps.size)
            steps.add(at, exercise(content, Kind.TRANSLATE_FR, item, true, random))
        }
        val mode = if (words) Mode.TRANSLATE_WORDS else Mode.TRANSLATE_PHRASES
        return Session(content, progress, null, null, today, steps, random, mode)
    }

    /**
     * S'entraîner sur une leçon : une question sur chaque phrase de l'arrêt, les plus fragiles d'abord.
     * Une phrase jamais vue a d'abord sa carte « nouvelle phrase ».
     */
    fun buildLessonPractice(content: Content, progress: Progress, stop: Stop, today: Long, random: Random = Random.Default): Session {
        val chosen = stop.items.shuffled(random).sortedBy { progress.level(it) }.take(EXERCISES)
        val steps = mutableListOf<Step>()
        for (item in chosen.shuffled(random)) {
            if (!progress.isIntroduced(item)) {
                steps.add(NewStep(item))
                steps.add(exercise(content, firstKind(item), item, true, random))
            } else {
                steps.add(exercise(content, practiceKind(item, progress.level(item), random), item, true, random))
            }
        }
        return Session(content, progress, null, stop.line, today, steps, random, Mode.LESSON, stop)
    }

    private fun practiceKind(item: Item, level: Int, random: Random): Kind {
        val kinds = mutableListOf(Kind.CHOOSE_CEB, Kind.ORDER)
        if (item.hasGap) kinds.add(Kind.GAP)
        if (level >= 2 && canWrite(item)) kinds.add(Kind.WRITE)
        val k = kinds.random(random)
        return if (k == Kind.ORDER && !canOrder(item)) Kind.CHOOSE_CEB else k
    }

    /** « Relier » : 3 à 5 paires de la même ligne, sans doublon ni mot trop proche. */
    fun buildMatch(content: Content, newItems: List<Item>, pool: List<Item>, progress: Progress, random: Random): Exercise? {
        val candidates = (newItems + pool).filter { it.isWord || it.tokens.size <= 3 }
        if (candidates.size < 3) return null
        val line = candidates.groupBy { it.line }.maxByOrNull { (l, list) -> list.size + if (newItems.any { it.line == l }) 100 else 0 }!!.key
        val chosen = mutableListOf<Item>()
        val cebs = mutableSetOf<String>()
        val frs = mutableSetOf<String>()
        val syns = mutableSetOf<String>()
        val ordered = candidates.filter { it.line == line }.let { list ->
            list.filter { it in newItems } + list.filter { it !in newItems }.sortedBy { progress.level(it) }
        }
        for (c in ordered) {
            if (chosen.size >= 5) break
            val ceb = Answers.normalize(c.ceb)
            val fr = Answers.normalize(c.fr)
            if (ceb in cebs || fr in frs || (c.syn != null && c.syn in syns)) continue
            chosen.add(c)
            cebs.add(ceb)
            frs.add(fr)
            c.syn?.let { syns.add(it) }
        }
        if (chosen.size < 3) return null
        val shuffled = chosen.shuffled(random)
        val right = shuffled.indices.shuffled(random)
        return Exercise(Kind.MATCH, shuffled, shuffled.map { it.ceb }, graded = true, rightOrder = right)
    }
}
