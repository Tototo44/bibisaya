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
        p.completedStops.add("v1")
        val copy = Progress.decode(p.encode())
        assertEquals(p.encode(), copy.encode())
        assertEquals(p.items.size, copy.items.size)
        assertEquals(p.points, copy.points)
        assertEquals(p.slotsDone(20000), copy.slotsDone(20000))
        assertEquals(p.badges.keys.toList(), copy.badges.keys.toList())
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
                val tokens = ex.item.tokens
                assertTrue(tokens.size >= 2)
                assertTrue(ex.options.size in tokens.size + 1..tokens.size + 2)
                val left = ex.options.toMutableList()
                for (t in tokens) assertTrue("$ex : $t dans ${ex.options}", left.remove(t))
                assertTrue(left.none { Answers.normalize(it) in tokens.map(Answers::normalize) })
            }
            Kind.MATCH -> {
                assertTrue(ex.items.size in 3..5)
                assertEquals(ex.items.size, ex.items.map { Answers.normalize(it.ceb) }.toSet().size)
                assertEquals(ex.items.size, ex.items.map { Answers.normalize(it.fr) }.toSet().size)
                assertEquals(ex.items.indices.toSet(), ex.rightOrder.toSet())
                assertEquals(1, ex.items.map { it.line }.toSet().size)
            }
            Kind.WRITE -> assertTrue(ex.item.isWord || ex.item.isShort)
        }
    }
}
