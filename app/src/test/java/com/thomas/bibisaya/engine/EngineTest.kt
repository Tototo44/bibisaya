package com.thomas.bibisaya.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class EngineTest {
    companion object {
        val content: Content by lazy { Content.parse(File("src/main/assets/contenu.txt").readText()) }

        fun word(ceb: String, vararg alts: String) =
            Item("t:$ceb", Line.VOCAB, 1, ceb, "fr", null, alts.toList(), null, null, false, null, null, null)
    }

    // ---------- Le contenu ----------

    @Test
    fun contentHasTheThreeLines() {
        assertTrue(content.items(Line.VOCAB).size >= 250)
        assertTrue(content.items(Line.SIMPLE).size >= 90)
        assertEquals(110, content.items(Line.COMPLEX).size)
        assertEquals(11, content.stops(Line.COMPLEX).size)
        for (stop in content.stops(Line.COMPLEX)) {
            val lesson = stop.lesson
            assertNotNull("leçon ${stop.key}", lesson)
            assertTrue("règles ${stop.key}", lesson!!.rules.isNotEmpty() || lesson.examples.isNotEmpty())
        }
        for (line in Line.values()) {
            val numbers = content.stops(line).map { it.number }
            assertEquals("arrêts de $line", (1..numbers.size).toList(), numbers)
            for (stop in content.stops(line)) assertTrue("arrêt vide ${stop.key}", stop.items.size >= 3)
        }
    }

    @Test
    fun idsAreUnique() {
        assertEquals(content.items.size, content.byId.size)
    }

    @Test
    fun gapsAreValid() {
        val gaps = content.items.filter { it.hasGap }
        assertTrue(gaps.size >= 50)
        for (item in gaps) {
            val options = item.gapOptions!!
            assertEquals(item.id, 4, options.size)
            assertEquals(item.id, 4, options.map { Answers.normalize(it) }.toSet().size)
            assertEquals(item.id, item.ceb, item.gapBefore + item.gapAnswer + item.gapAfter)
        }
    }

    @Test
    fun everyWordOfASentenceHasATranslation() {
        val missing = content.items.filter { it.line.isPhrases }
            .flatMap { item -> item.tokens + (item.gapOptions ?: emptyList()).flatMap { Content.tokenize(it) } }
            .filter { content.gloss(it) == null }
            .toSortedSet()
        assertTrue("sans traduction : $missing", missing.isEmpty())
    }

    @Test
    fun addedItemsHaveAnEnglishMeaning() {
        val added = content.items.filter { it.added }
        assertTrue(added.size >= 50)
        for (item in added) assertNotNull("en: pour ${item.id}", item.en)
    }

    @Test
    fun choicesAreNeverAmbiguous() {
        // Deux mots différents ne doivent pas avoir la même traduction dans une même ligne.
        for (line in Line.values()) {
            val byFr = content.items(line).groupBy { Answers.normalize(it.fr) }.filter { it.value.size > 1 }
            assertTrue("même traduction : ${byFr.values}", byFr.isEmpty())
        }
    }

    // ---------- Les réponses ----------

    @Test
    fun answersIgnoreCaseAccentsAndHyphens() {
        assertEquals(Answers.Verdict.RIGHT, Answers.checkWritten("Lig-on!", word("lig-on")))
        assertEquals(Answers.Verdict.RIGHT, Answers.checkWritten("ligon", word("lig-on")))
        assertEquals(Answers.Verdict.RIGHT, Answers.checkWritten(" Maáyo ", word("maayo")))
        assertEquals(Answers.Verdict.RIGHT, Answers.checkWritten("daku", word("dako", "daku")))
    }

    @Test
    fun smallTyposAreForgivenButNotOtherWords() {
        assertEquals(Answers.Verdict.TYPO, Answers.checkWritten("salamt", word("salamat")))
        assertEquals(Answers.Verdict.TYPO, Answers.checkWritten("estudyanteh", word("estudyante")))
        assertEquals(Answers.Verdict.WRONG, Answers.checkWritten("aka", word("ako")))
        assertEquals(Answers.Verdict.WRONG, Answers.checkWritten("", word("ako")))
        assertEquals(Answers.Verdict.WRONG, Answers.checkWritten("babaye", word("lalake")))
        // « bata » (l'enfant) n'est pas une faute de frappe pour « bato » s'il existe dans l'appli.
        val bata = content.items(Line.VOCAB).first { it.ceb == "bata" }
        val other = content.items(Line.VOCAB).first { it.ceb == "tawo" }
        assertEquals(Answers.Verdict.TYPO, Answers.checkWritten("tawu", other, content))
        assertEquals(Answers.Verdict.WRONG, Answers.checkWritten("bata", word("bato"), content))
        assertEquals(Answers.Verdict.RIGHT, Answers.checkWritten("Bata", bata, content))
    }

    @Test
    fun orderIsChecked() {
        val item = content.items(Line.SIMPLE).first { it.tokens.size >= 3 }
        assertTrue(Answers.checkOrder(item.tokens, item))
        assertFalse(Answers.checkOrder(item.tokens.reversed(), item))
        assertFalse(Answers.checkOrder(item.tokens.dropLast(1), item))
    }

    @Test
    fun sentencesAreCorrectedWordByWord() {
        val item = content.items(Line.SIMPLE).first { it.ceb == "Nagtrabaho ko uban sa mga bata." }
        fun check(s: String) = Answers.checkSentence(s, item, content)
        assertEquals(Answers.Verdict.RIGHT, check("nagtrabaho ko uban sa mga bata").verdict)
        assertEquals("forme longue acceptée", Answers.Verdict.RIGHT, check("Nagtrabaho ako uban sa mga bata.").verdict)
        assertEquals(Answers.Verdict.TYPO, check("Nagtrabho ko uban sa mga bata.").verdict)

        val missing = check("Nagtrabaho ko uban sa bata.")
        assertEquals(Answers.Verdict.WRONG, missing.verdict)
        assertEquals(listOf("mga"), missing.missing.map { it.word })
        assertTrue(missing.wrong.isEmpty())

        // Un autre vrai mot n'est pas une faute de frappe.
        val other = check("Magtrabaho ko uban sa mga bata.")
        assertEquals(Answers.Verdict.WRONG, other.verdict)
        assertEquals(listOf("Nagtrabaho"), other.wrong.map { it.word })
        assertEquals("ka n'est pas ko", Answers.Verdict.WRONG, check("Nagtrabaho ka uban sa mga bata.").verdict)

        val extra = check("Nagtrabaho ko uban sa mga bata karon.")
        assertEquals(Answers.Verdict.WRONG, extra.verdict)
        assertEquals(listOf("karon"), extra.extra)
        assertEquals(Answers.Verdict.WRONG, check("").verdict)

        val og = content.items(Line.SIMPLE).first { it.ceb == "Nagtuon ko og Bisaya." }
        assertEquals(Answers.Verdict.RIGHT, Answers.checkSentence("nagtuon ko ug bisaya", og, content).verdict)
        val kita = content.items(Line.SIMPLE).first { it.ceb == "Kita-kita ta ugma." }
        assertEquals("tiret ou espace", Answers.Verdict.RIGHT, Answers.checkSentence("kita kita ta ugma", kita, content).verdict)
        val alt = content.items(Line.SIMPLE).first { it.ceb == "Unsa ang imong pangalan?" }
        assertEquals("autre réponse acceptée", Answers.Verdict.RIGHT, Answers.checkSentence("Unsay imong pangalan", alt, content).verdict)
    }

    @Test
    fun frenchAnswers() {
        val friend = Item("t:higala", Line.VOCAB, 1, "higala", "l'ami, l'amie", null, emptyList(), null, null, false, null, null, null)
        assertEquals(Answers.Verdict.RIGHT, Answers.checkFrench("ami", friend))
        assertEquals(Answers.Verdict.RIGHT, Answers.checkFrench("L'amie", friend))
        assertEquals(Answers.Verdict.TYPO, Answers.checkFrench("l'amis", friend))
        assertEquals(Answers.Verdict.WRONG, Answers.checkFrench("le chien", friend))
        val stop = Item("t:ayaw", Line.VOCAB, 1, "ayaw", "ne… pas ! (pour interdire)", null, emptyList(), null, null, false, null, null, null)
        assertEquals(Answers.Verdict.RIGHT, Answers.checkFrench("ne pas", stop))
        val where = content.items(Line.SIMPLE).first { it.ceb == "Asa ka nagpuyo?" }
        assertEquals(Answers.Verdict.RIGHT, Answers.checkFrench("où habites tu", where))
        assertEquals("pas mot pour mot : à juger soi-même", Answers.Verdict.WRONG, Answers.checkFrench("Tu habites où ?", where))
    }

    // ---------- Des questions plus dures ----------

    @Test
    fun oneWordChangesInTheChoices() {
        val hungry = content.items(Line.SIMPLE).first { it.ceb == "Gigutom ko." }
        val options = Planner.exercise(content, Kind.CHOOSE_CEB, hungry, true, Random(1)).options
        assertEquals(4, options.size)
        assertTrue("$options", options.all { it.startsWith("Gigutom ") })
        assertTrue("jamais ako/ko : $options", options.none { Answers.same(it, "Gigutom ako.") })
        val wrong = options.first { it != hungry.ceb }
        val why = Variants.explain(content, wrong, hungry.ceb)!!
        assertTrue(why, why.startsWith("Un seul mot change : **") && "c'est « je »" in why)

        // Phrase à trou : les pièges sont les autres choix du trou.
        val tall = content.items(Line.COMPLEX).first { it.ceb == "Taas ako." }
        val gapOptions = Planner.exercise(content, Kind.CHOOSE_CEB, tall, true, Random(2)).options
        assertEquals(setOf("Taas ako.", "Taas ikaw.", "Taas siya.", "Taas sila."), gapOptions.toSet())

        // Un verbe : mo- devient ni- (seulement si c'est un vrai mot).
        val buy = content.items(Line.SIMPLE).first { it.ceb == "Mopalit ko ani." }
        val variants = Variants.all(content, buy).map { it.text }
        assertTrue("$variants", "Nipalit ko ani." in variants)
        val nice = content.items(Line.SIMPLE).first { it.ceb.startsWith("Nindot kaayo") }
        assertTrue(Variants.all(content, nice).none { it.text.startsWith("Mondot") })
    }

    @Test
    fun theAnswerCannotBeGuessedFromItsShape() {
        // Avant : une fois sur 7, la bonne phrase était la seule question, ou la seule avec un prénom.
        var asked = 0
        var giveaway = 0
        val random = Random(9)
        fun shape(t: String) = (t.trimEnd().lastOrNull { it in "?!." } ?: '.') to Content.tokenize(t).drop(1).any { it.first().isUpperCase() }
        repeat(3) {
            for (item in content.items.filter { it.line.isPhrases }) for (kind in listOf(Kind.CHOOSE_CEB, Kind.CHOOSE_FR)) {
                val ex = Planner.exercise(content, kind, item, true, random)
                checkExercise(ex)
                asked++
                val mine = shape(ex.answer)
                if (ex.options.count { shape(it) == mine } == 1) giveaway++
            }
        }
        println("Phrases devinables par leur forme : $giveaway sur $asked")
        assertTrue("$giveaway sur $asked", giveaway * 100 <= asked * 2)
    }

    @Test
    fun orderTilesAreLowercaseWithLookAlikeTraps() {
        val work = content.items(Line.COMPLEX).first { it.ceb == "Nagtrabaho ko sa eskwelahan." }
        val tiles = Planner.exercise(content, Kind.ORDER, work, true, Random(3)).options
        assertTrue("$tiles", "nagtrabaho" in tiles)
        assertEquals(6, tiles.size)
        val traps = tiles.filter { Answers.normalize(it) !in work.tokens.map(Answers::normalize) }
        val alike = setOf("magtrabaho", "pagtrabaho", "ka", "siya", "ta", "mi", "mo", "sila")
        assertTrue("pièges : $traps", traps.all { it in alike })
        // Les noms propres gardent leur majuscule.
        val thomas = content.items(Line.SIMPLE).first { it.ceb == "Ako si Thomas." }
        val t2 = Planner.exercise(content, Kind.ORDER, thomas, true, Random(4)).options
        assertTrue("$t2", "Thomas" in t2 && "ako" in t2)
        assertTrue(Answers.checkOrder(listOf("ako", "si", "Thomas"), thomas))
    }

    @Test
    fun wellKnownItemsAreWrittenMoreOften() {
        val random = Random(5)
        val word = content.items(Line.VOCAB).first()
        val writes = (1..1000).count { Planner.reviewKind(word, 4, random) == Kind.WRITE }
        assertTrue("$writes", writes in 720..880)
        val phrase = content.items(Line.SIMPLE).first { it.ceb == "Asa ka nagpuyo?" }
        assertTrue((1..200).any { Planner.reviewKind(phrase, 4, random) == Kind.WRITE })
        assertTrue((1..200).none { Planner.reviewKind(phrase, 1, random) == Kind.WRITE })
        val long = content.items(Line.COMPLEX).first { it.tokens.size > Planner.MAX_WRITE_TOKENS }
        assertTrue((1..200).none { Planner.reviewKind(long, 5, random) == Kind.WRITE })
    }

    @Test
    fun aWrongAnswerCanBeAcceptedAfterAll() {
        val p = Progress().apply { installDay = 0 }
        val s = Planner.build(content, p, Slot.MIDI, Line.SIMPLE, 0, Random(8))
        var ex: Exercise? = null
        while (ex == null) {
            val step = s.current!!
            s.onShown(step)
            if (step is Exercise && step.kind != Kind.MATCH && step.graded) ex = step else {
                if (step is Exercise) s.submit(step, true)
                s.next()
            }
        }
        val before = p.encode()
        val steps = s.steps.size
        val answered = s.gradedAnswered
        val fb = s.submit(ex, false)
        assertTrue(fb.retry)
        assertEquals(steps + 1, s.steps.size)
        s.next()
        val again = s.acceptLast()!!
        assertTrue(again.correct)
        assertEquals("la question ne revient plus", steps, s.steps.size)
        assertEquals(answered + 1, s.gradedAnswered)
        assertEquals(s.gradedAnswered, s.correctFirst)
        assertTrue(s.missed.isEmpty())
        // Comme si la réponse avait été juste du premier coup.
        val p2 = Progress.decode(before)
        p2.answer(ex.item, true, 0)
        assertEquals(p2.encode(), p.encode())
    }

    // ---------- Traduire ----------

    @Test
    fun translateTenWords() {
        val p = Progress().apply { installDay = 0 }
        assertEquals(null, Planner.buildTranslation(content, p, words = true, today = 0))
        val words = content.items(Line.VOCAB).take(30)
        words.forEach { p.introduce(it, 0) }
        words.drop(10).forEach { p.answer(it, true, 0) }
        val s = Planner.buildTranslation(content, p, words = true, today = 1, random = Random(2))!!
        assertEquals(Mode.TRANSLATE_WORDS, s.mode)
        val exercises = s.steps.map { it as Exercise }
        assertEquals(10, exercises.size)
        assertEquals(listOf(3, 7), exercises.indices.filter { exercises[it].kind == Kind.TRANSLATE_FR })
        assertTrue(exercises.all { it.kind == Kind.WRITE || it.kind == Kind.TRANSLATE_FR })
        assertEquals("les plus fragiles d'abord", words.take(10).toSet(), exercises.map { it.item }.toSet())
        // Pas de deuxième essai ; les ratés sont « à revoir ».
        val first = exercises.first()
        s.onShown(first)
        assertFalse(s.submit(first, false).retry)
        assertEquals(10, s.steps.size)
        assertEquals(listOf(first.item), s.missed)

        val again = Planner.buildTranslation(content, p, words = true, today = 1, random = Random(3), avoid = exercises.map { it.item.id }.toSet())!!
        assertTrue("pas les mêmes 10", again.steps.none { (it as Exercise).item in exercises.map { e -> e.item } })
    }

    @Test
    fun translateSentences() {
        val p = Progress().apply { installDay = 0 }
        content.items(Line.SIMPLE).take(4).forEach { p.introduce(it, 0) }
        content.items(Line.COMPLEX).forEach { p.introduce(it, 0) }
        val s = Planner.buildTranslation(content, p, words = false, today = 0, random = Random(4))!!
        val exercises = s.steps.map { it as Exercise }
        assertEquals(10, exercises.size)
        for (ex in exercises) {
            assertTrue(ex.item.line.isPhrases)
            if (ex.kind == Kind.WRITE) assertTrue(ex.item.tokens.size <= Planner.MAX_TRANSLATE_TOKENS)
        }
        val few = Progress().apply { installDay = 0 }
        content.items(Line.SIMPLE).take(3).forEach { few.introduce(it, 0) }
        assertEquals(3, Planner.buildTranslation(content, few, words = false, today = 0)!!.steps.size)
    }

    @Test
    fun practiceALesson() {
        val p = Progress().apply { installDay = 0 }
        val stop = content.stops(Line.COMPLEX)[6]
        stop.items.take(4).forEach { p.introduce(it, 0) }
        val s = Planner.buildLessonPractice(content, p, stop, 0, Random(6))
        assertEquals(Mode.LESSON, s.mode)
        assertEquals(stop, s.stop)
        assertEquals(10, s.gradedTotal)
        assertEquals(6, s.steps.count { it is NewStep })
        checkSteps(s)
        assertTrue(s.steps.filterIsInstance<Exercise>().all { it.item.stop == stop.number && it.item.line == Line.COMPLEX })
        playSession(s, Random(7), 1.0)
        val points = p.points
        val o = p.finishPractice(content, 0, s.correctFirst)
        assertEquals(10 * Progress.POINTS_ANSWER, o.points)
        assertEquals(points + 100, p.points)
        assertEquals("pas une des 3 séances", 0, p.sessionsDone)
        assertTrue(p.slotsDone(0).isEmpty())
        assertEquals(0, p.streak)
    }

    // ---------- Les fiches ----------

    @Test
    fun sheetsFromTheCourses() {
        assertEquals(listOf("questions", "tableau"), content.sheets.map { it.key })
        val q = content.sheet("questions")!!
        assertEquals(11, q.words.size)
        assertEquals(2, q.tips.size)
        assertEquals(null, q.words.first { it.word == "hain" }.example)
        val t = content.sheet("tableau")!!
        assertEquals(listOf(7, 2, 3), t.tables.map { it.rows.size })
        for (table in t.tables) for (row in table.rows) assertEquals(table.columns.size, row.cells.size)
        assertEquals(3, t.tables.last().examples.size)
        assertEquals(11, content.lessons.size)
        assertEquals("c7", content.stopByKey("c7")!!.key)
    }

    // ---------- Les révisions ----------

    @Test
    fun levelsGoUpOncePerDayAndDownAfterAMistake() {
        val p = Progress()
        val item = content.items.first()
        p.introduce(item, 10)
        assertEquals(1, p.level(item))
        assertEquals(10L, p.state(item)!!.dueDay)

        var c = p.answer(item, true, 10)
        assertEquals(2, c.levelAfter)
        assertEquals(1L, c.dueInDays)
        c = p.answer(item, true, 10)
        assertEquals("une seule montée par jour", 2, c.levelAfter)
        c = p.answer(item, true, 11)
        assertEquals(3, c.levelAfter)
        assertTrue(c.known)
        assertEquals(3L, c.dueInDays)
        c = p.answer(item, true, 14)
        assertEquals(7L, c.dueInDays)
        c = p.answer(item, true, 21)
        assertEquals(5, c.levelAfter)
        assertEquals(21L, c.dueInDays)
        c = p.answer(item, true, 42)
        assertEquals("niveau 5 au plus", 5, c.levelAfter)
        c = p.answer(item, false, 63)
        assertEquals(4, c.levelAfter)
        assertEquals(0L, c.dueInDays)
        repeat(5) { p.answer(item, false, 63) }
        assertEquals("niveau 1 au moins", 1, p.level(item))
    }

    @Test
    fun dueTexts() {
        assertEquals("Tu connais bien ce mot : il reviendra dans 3 jours.", Plan.dueText(Change(2, 3, 3), true))
        assertEquals("Elle reviendra demain.", Plan.dueText(Change(1, 2, 1), false))
        assertEquals("Il reviendra à la prochaine séance.", Plan.dueText(Change(2, 1, 0), true))
    }

    // ---------- Les séances ----------

    @Test
    fun firstVocabularySession() {
        val p = Progress().apply { installDay = 0 }
        val s = Planner.build(content, p, Slot.MATIN, Line.VOCAB, 0, Random(1))
        assertEquals(Line.VOCAB, s.line)
        assertEquals(10, s.gradedTotal)
        val news = s.steps.filterIsInstance<NewStep>()
        assertEquals("3 mots nouveaux + 1 pour bien démarrer", 4, news.size)
        assertEquals(content.items(Line.VOCAB).take(4), news.map { it.item })
        checkSteps(s)
        assertTrue(s.steps.any { it is Exercise && it.kind == Kind.MATCH })
    }

    @Test
    fun firstComplexSessionStartsWithTheLesson() {
        val p = Progress().apply { installDay = 0 }
        val s = Planner.build(content, p, Slot.SOIR, Line.COMPLEX, 0, Random(2))
        assertTrue(s.steps.first() is LessonStep)
        assertEquals(1, s.steps.count { it is LessonStep })
        assertEquals(3, s.steps.count { it is NewStep })
        assertEquals(10, s.gradedTotal)
        checkSteps(s)
        playSession(s, Random(3), 1.0)
        assertTrue("c1" in p.lessonsSeen)
        val again = Planner.build(content, p, Slot.SOIR, Line.COMPLEX, 2, Random(4))
        assertEquals("la leçon n'est montrée qu'une fois", 0, again.steps.count { it is LessonStep })
    }

    @Test
    fun aMistakeComesBackOnceWithoutCounting() {
        val p = Progress().apply { installDay = 0 }
        val s = Planner.build(content, p, Slot.MATIN, Line.VOCAB, 0, Random(5))
        var firstWrongDone = false
        while (!s.finished) {
            val step = s.current!!
            s.onShown(step)
            if (step is Exercise) {
                val wrong = !firstWrongDone && step.kind != Kind.MATCH && step.graded
                val before = s.steps.size
                val feedback = s.submit(step, !wrong)
                if (wrong) {
                    firstWrongDone = true
                    assertTrue(feedback.retry)
                    assertEquals(before + 1, s.steps.size)
                    val retry = s.steps.filterIsInstance<Exercise>().single { !it.graded }
                    assertEquals(step.item, retry.item)
                }
            }
            s.next()
        }
        assertTrue(firstWrongDone)
        assertEquals(10, s.gradedTotal)
        assertEquals(9, s.correctFirst)
    }

    @Test
    fun theEveningAlternates() {
        assertEquals(Line.COMPLEX, Plan.lineFor(Slot.SOIR, 10, 10))
        assertEquals(Line.VOCAB, Plan.lineFor(Slot.SOIR, 11, 10))
        assertEquals(Line.COMPLEX, Plan.lineFor(Slot.SOIR, 12, 10))
        assertEquals(Line.VOCAB, Plan.lineFor(Slot.MATIN, 11, 10))
        assertEquals(Line.SIMPLE, Plan.lineFor(Slot.MIDI, 11, 10))
    }

    @Test
    fun slotsOpenAtTheirTimeUntilMidnight() {
        val p = Progress()
        val t = Times()
        assertEquals(SlotState.LATER, Plan.state(Slot.MIDI, p, 5, 12 * 60, t))
        assertEquals(SlotState.OPEN, Plan.state(Slot.MIDI, p, 5, 12 * 60 + 30, t))
        assertEquals(SlotState.OPEN, Plan.state(Slot.MATIN, p, 5, 23 * 60 + 59, t))
        p.daySlots[5] = mutableSetOf(Slot.MATIN)
        assertEquals(SlotState.DONE, Plan.state(Slot.MATIN, p, 5, 9 * 60, t))
        assertEquals(SlotState.OPEN, Plan.state(Slot.MATIN, p, 6, 9 * 60, t))
        assertEquals("12H30", Times.upper(750))
        assertEquals("08:00", Times.clock(480))
        assertEquals("19h00", Times.spoken(1140))
    }

    // ---------- Points, série et badges ----------

    @Test
    fun pointsStreakAndPerfectDay() {
        val p = Progress().apply { installDay = 0 }
        var o = p.finishSession(content, Slot.MATIN, 0, 8, 10)
        assertEquals(8 * 10 + 20, o.points)
        assertEquals(1, o.streak)
        assertTrue(Badge.SAKAY in o.newBadges)
        p.finishSession(content, Slot.MIDI, 0, 10, 10)
        assertTrue(p.flawless)
        o = p.finishSession(content, Slot.SOIR, 0, 10, 10)
        assertTrue(o.perfectDay)
        assertEquals(10 * 10 + 20 + 50, o.points)
        assertTrue(Badge.PERFECT in o.newBadges)
        assertTrue(Badge.FLAWLESS in p.badges)
        assertEquals(1, p.perfectDays)
        // Une séance bonus ne refait pas la journée parfaite.
        o = p.finishSession(content, null, 0, 10, 10)
        assertFalse(o.perfectDay)
        assertEquals(1, p.perfectDays)

        p.finishSession(content, Slot.MATIN, 1, 5, 10)
        assertEquals(2, p.streak)
        assertEquals(2, p.currentStreak(2))
        assertEquals("un jour sans séance : la série retombe", 0, p.currentStreak(3))
        p.finishSession(content, Slot.MATIN, 3, 5, 10)
        assertEquals(1, p.streak)
        assertEquals(8 * 10 + 20 + 10 * 10 + 20 + 10 * 10 + 20 + 50 + 10 * 10 + 20 + (5 * 10 + 20) * 2, p.points.toInt())
    }

    @Test
    fun badgeProgressCounts() {
        val p = Progress().apply { installDay = 0 }
        for (day in 0L until 12) p.finishSession(content, Slot.MATIN, day, 5, 10)
        assertEquals(12 to 30, p.badgeProgress(content, Badge.STREAK30, 12))
        assertTrue(Badge.STREAK7 in p.badges)
        assertFalse(Badge.STREAK30 in p.badges)
        assertEquals(0 to 50, p.badgeProgress(content, Badge.WORDS50, 12))
        assertEquals(0 to content.stops(Line.VOCAB).size, p.badgeProgress(content, Badge.LINE_VOCAB, 12))
    }

    @Test
    fun saveAndLoad() {
        val p = Progress().apply { installDay = 20000 }
        val s = Planner.build(content, p, Slot.MATIN, Line.VOCAB, 20000, Random(6))
        playSession(s, Random(7), 0.7)
        p.finishSession(content, Slot.MATIN, 20000, s.correctFirst, s.gradedTotal)
        p.lessonsSeen.add("c1")
        p.lessonsRead.add("c4")
        p.lessonsRead.add("questions")
        p.completedStops.add("v1")
        val copy = Progress.decode(p.encode())
        assertEquals(p.encode(), copy.encode())
        assertEquals(p.items.size, copy.items.size)
        assertEquals(p.points, copy.points)
        assertEquals(p.slotsDone(20000), copy.slotsDone(20000))
        assertEquals(p.badges.keys.toList(), copy.badges.keys.toList())
        assertEquals(setOf("c4", "questions"), copy.lessonsRead)
        assertEquals(0, Progress.decode(null).items.size)
        assertEquals(0, Progress.decode("n'importe quoi\ni:x=abc").items.size)
    }

    // ---------- Sur plusieurs mois ----------

    @Test
    fun aRegularLearnerFinishesTheThreeLines() {
        for ((accuracy, maxDays) in listOf(0.95 to 120, 0.8 to 150)) {
            val days = simulate(accuracy, seed = 11)
            println("Avec ${(accuracy * 100).toInt()} % de bonnes réponses : les 3 lignes finies en $days jours")
            assertTrue("fini en $days jours", days <= maxDays)
        }
    }

    @Test
    fun theJeepneyNeverGetsStuck() {
        // Même avec beaucoup d'erreurs, chaque séance garde 10 questions et la ligne finit par avancer.
        val days = simulate(0.6, seed = 12, maxDays = 400)
        println("Avec 60 % de bonnes réponses : fini en $days jours")
        assertTrue(days < 400)
    }

    // ---------- Outils ----------

    /** Joue toute une année (au plus) : 3 séances par jour. Rend le nombre de jours pour tout finir. */
    private fun simulate(accuracy: Double, seed: Int, maxDays: Int = 300): Int {
        val p = Progress().apply { installDay = 0 }
        val random = Random(seed)
        for (day in 0L until maxDays) {
            for (slot in Slot.values()) {
                val line = Plan.lineFor(slot, day, p.installDay)
                val s = Planner.build(content, p, slot, line, day, random)
                assertEquals("jour $day, $slot : ${s.steps}", Planner.EXERCISES, s.gradedTotal)
                checkSteps(s)
                playSession(s, random, accuracy)
                p.finishSession(content, slot, day, s.correctFirst, s.gradedTotal)
            }
            if (Line.values().all { p.lineFinished(content, it) }) {
                assertTrue(Badge.TERMINUS in p.badges)
                return day.toInt() + 1
            }
        }
        return maxDays
    }

    private fun playSession(s: Session, random: Random, accuracy: Double) {
        while (!s.finished) {
            val step = s.current!!
            s.onShown(step)
            if (step is Exercise) {
                val ok = random.nextDouble() < accuracy
                val wrong = if (step.kind == Kind.MATCH && !ok) setOf(step.items.random(random)) else emptySet()
                s.submit(step, ok, wrong)
            }
            s.next()
        }
    }

    /** Chaque carte est suivie de sa question, et chaque question a de bons choix. */
    private fun checkSteps(s: Session) {
        s.steps.forEachIndexed { i, step ->
            when (step) {
                is LessonStep -> assertTrue(s.steps[i + 1] is NewStep)
                is NewStep -> assertEquals(step.item, (s.steps[i + 1] as Exercise).item)
                is Exercise -> checkExercise(step)
            }
        }
    }

    private fun checkExercise(ex: Exercise) {
        when (ex.kind) {
            Kind.CHOOSE_CEB, Kind.CHOOSE_FR, Kind.GAP -> {
                assertEquals("$ex ${ex.kind} ${ex.options}", 4, ex.options.size)
                assertEquals("$ex ${ex.options}", 4, ex.options.map { Answers.normalize(it) }.toSet().size)
                assertTrue(ex.answer in ex.options)
            }
            Kind.ORDER -> {
                val tokens = ex.item.tokens.map(Answers::normalize)
                assertTrue(tokens.size >= 2)
                assertTrue(ex.options.size in tokens.size + 1..tokens.size + 2)
                val left = ex.options.map(Answers::normalize).toMutableList()
                for (t in tokens) assertTrue("$ex : $t dans ${ex.options}", left.remove(t))
                assertTrue(left.none { it in tokens })
                // Pas de majuscule qui trahit le premier mot (sauf un nom propre).
                assertTrue("${ex.options}", ex.options.none { it.first().isUpperCase() && Answers.normalize(it) !in content.properNouns })
            }
            Kind.MATCH -> {
                assertTrue(ex.items.size in 3..5)
                assertEquals(ex.items.size, ex.items.map { Answers.normalize(it.ceb) }.toSet().size)
                assertEquals(ex.items.size, ex.items.map { Answers.normalize(it.fr) }.toSet().size)
                assertEquals(ex.items.indices.toSet(), ex.rightOrder.toSet())
                assertEquals(1, ex.items.map { it.line }.toSet().size)
            }
            Kind.WRITE -> assertTrue(ex.item.isWord || ex.item.tokens.size <= Planner.MAX_TRANSLATE_TOKENS)
            Kind.TRANSLATE_FR -> assertTrue(ex.options.isEmpty())
        }
    }
}
