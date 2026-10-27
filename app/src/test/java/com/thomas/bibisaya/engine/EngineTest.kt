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
        assertEquals(null, Planner.buildTranslation(content, p, words = false, today = 0))
        // Rien de vu : les 3 premiers mots de la route, chacun avec sa carte.
        val start = Planner.buildTranslation(content, Progress().apply { installDay = 0 }, words = true, today = 0, random = Random(1))!!
        assertEquals(3, start.gradedTotal)
        assertEquals(content.items(Line.VOCAB).take(3), start.steps.filterIsInstance<NewStep>().map { it.item })
        checkSteps(start)
        val words = content.items(Line.VOCAB).take(30)
        words.forEach { p.introduce(it, 0) }
        words.drop(10).forEach { p.answer(it, true, 0) }
        // 20 mots sur 30 à peu près maîtrisés (67 %) : pas de mot nouveau, on consolide.
        assertEquals(emptyList<Item>(), Planner.newWordsToTranslate(content, p))
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
    fun translateAddsNewWordsWhenMostAreKnown() {
        val p = Progress().apply { installDay = 0 }
        val words = content.items(Line.VOCAB).take(30)
        words.forEach { p.introduce(it, 0) }
        words.drop(6).forEach { p.answer(it, true, 0) }
        // 24 mots sur 30 à peu près maîtrisés (80 %) : 3 mots nouveaux, les suivants sur la route.
        val next = content.items(Line.VOCAB).drop(30).take(3)
        assertEquals(next, Planner.newWordsToTranslate(content, p))
        assertEquals(10, Planner.translationSize(content, p, words = true))
        val s = Planner.buildTranslation(content, p, words = true, today = 1, random = Random(8))!!
        assertEquals(10, s.gradedTotal)
        val cards = s.steps.withIndex().filter { it.value is NewStep }
        assertEquals(next, cards.map { (it.value as NewStep).item })
        assertEquals("avant la 1re, la 4e et la 7e question", listOf(0, 4, 8), cards.map { it.index })
        for ((at, card) in cards) {
            // Sa question vient 2 questions après la carte : on le traduit de mémoire.
            val ask = s.steps.indexOfFirst { it is Exercise && it.item == (card as NewStep).item }
            assertEquals(at + 3, ask)
            assertEquals(Kind.WRITE, (s.steps[ask] as Exercise).kind)
        }
        checkSteps(s)
        playSession(s, Random(9), 1.0)
        assertTrue(next.all { p.isIntroduced(it) })
        assertEquals(next, s.newItems)
        // 23 sur 30 (77 %) : il faut d'abord mieux connaître les mots déjà vus.
        val q = Progress().apply { installDay = 0 }
        words.forEach { q.introduce(it, 0) }
        words.drop(7).forEach { q.answer(it, true, 0) }
        assertEquals(emptyList<Item>(), Planner.newWordsToTranslate(content, q))
        assertEquals(10, Planner.translationSize(content, q, words = true))
        assertEquals(0, Planner.buildTranslation(content, q, words = true, today = 1)!!.steps.count { it is NewStep })
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
        assertEquals("5 nouveautés au plus", 5, s.steps.count { it is NewStep })
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

    @Test
    fun discoverAStopFarAhead() {
        val p = Progress().apply { installDay = 0 }
        val stop = content.stops(Line.VOCAB)[9]
        val s = Planner.buildLessonPractice(content, p, stop, 0, Random(10))
        assertEquals(10, s.gradedTotal)
        assertEquals(stop.items.take(5), s.steps.filterIsInstance<NewStep>().map { it.item }.sortedBy { stop.items.indexOf(it) })
        assertTrue(s.steps.filterIsInstance<Exercise>().all { it.item in stop.items.take(5) })
        checkSteps(s)
        playSession(s, Random(11), 1.0)
        assertEquals(5, stop.items.count { p.isIntroduced(it) })
        // La fois d'après : les 5 suivants, puis les déjà vus.
        val again = Planner.buildLessonPractice(content, p, stop, 1, Random(12))
        assertEquals(stop.items.drop(5).take(5).toSet(), again.steps.filterIsInstance<NewStep>().map { it.item }.toSet())
        assertEquals(10, again.gradedTotal)
        checkSteps(again)
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

    // ---------- Les rondes ----------

    private val c1 get() = content.stopByKey("c1")!!
    private val c4 get() = content.stopByKey("c4")!!

    /** Une question de la ronde des leçons 1 et 4, avec un mot de la leçon (le premier si [word] est null). */
    private fun question(stop: Stop, person: Person, second: Boolean, word: String? = null, feminine: Boolean = false): RondeQuestion {
        val words = stop.lesson!!.rondeWords
        val w = if (word == null) words.first() else words.first { it.ceb == word }
        val theme = Fabrique.theme(stop)!!
        val f = if (theme === Pronoms) Pronoms.rondeFrase(w, person, second, feminine) else Possessifs.rondeFrase(w, person, second)
        val row = theme.rows.first { it.person == person }
        return RondeQuestion(stop, theme, row, f, second, !second, if (second) emptyList() else f.choices, w.ceb)
    }

    @Test
    fun rondeContent() {
        // Une ronde pour chacune des 11 leçons.
        assertEquals((1..11).toList(), content.lessons.filter { Fabrique.theme(it) != null }.map { it.number })
        assertEquals(listOf("c1", "c4"), content.lessons.filter { it.lesson!!.rondeWords.isNotEmpty() }.map { it.key })
        for (stop in listOf(c1, c4)) {
            val words = stop.lesson!!.rondeWords
            assertTrue(words.size >= 6)
            // Que des mots du vocabulaire : Thomas les a déjà vus (ou les verra), et ils se touchent pour voir leur sens.
            for (w in words) assertTrue("${w.ceb} dans le vocabulaire", content.itemsWithForm(Answers.normalize(w.ceb)).any { it.line == Line.VOCAB })
            for (second in listOf(false, true)) {
                val forms = Person.values().map { question(stop, it, second).answer }
                assertEquals("7 formes différentes", 7, forms.toSet().size)
            }
        }
        // Après le mot, « tu » se dit ka (ikaw seulement en début de phrase).
        assertEquals("ka", Pronoms.form(Person.YOU, false))
        assertEquals(listOf("ako", "ka", "siya", "kita", "kami", "kamo", "sila"), Person.values().map { Pronoms.form(it, false) })
        assertEquals(listOf("ko", "ka", "siya", "ta", "mi", "mo", "sila"), Person.values().map { Pronoms.form(it, true) })
        assertEquals(listOf("akong", "imong", "iyang", "atong", "among", "inyong", "ilang"), Person.values().map { Possessifs.form(it, false) })
        assertEquals(listOf("nako", "nimo", "niya", "nato", "namo", "ninyo", "nila"), Person.values().map { Possessifs.form(it, true) })
    }

    @Test
    fun rondeSentencesInBothLanguages() {
        assertEquals("Kusgan kami.", question(c1, Person.WE_EX, false, "kusgan").sentence)
        assertEquals("Nous sommes forts (pas toi).", question(c1, Person.WE_EX, false, "kusgan").french)
        assertEquals("Nous sommes forts (toi et moi).", question(c1, Person.WE_IN, true, "kusgan").french)
        assertEquals("Kusgan ta.", question(c1, Person.WE_IN, true, "kusgan").sentence)
        assertEquals("Elle est forte.", question(c1, Person.HE, false, "kusgan", feminine = true).french)
        assertEquals("Ils sont forts.", question(c1, Person.THEY, false, "kusgan").french)
        assertEquals("Elles sont fortes.", question(c1, Person.THEY, false, "kusgan", feminine = true).french)
        assertEquals("Kusgan ka.", question(c1, Person.YOU, false, "kusgan").sentence)
        assertEquals("Kini ang akong balay.", question(c4, Person.ME, false, "balay").sentence)
        assertEquals("Kini ang balay nako.", question(c4, Person.ME, true, "balay").sentence)
        assertEquals("Voici ma maison.", question(c4, Person.ME, true, "balay").french)
        assertEquals("Voici notre maison (à toi et moi).", question(c4, Person.WE_IN, false, "balay").french)
        assertEquals("Voici leur maison.", question(c4, Person.THEY, false, "balay").french)
        assertEquals("devant une voyelle : mon", "Voici mon école.", question(c4, Person.ME, false, "eskwelahan").french)
        assertEquals("Voici son école.", question(c4, Person.HE, false, "eskwelahan").french)
        assertEquals("Voici ton livre.", question(c4, Person.YOU, false, "libro").french)
        // Toutes les phrases se font, pour toutes les personnes et tous les mots.
        for (stop in listOf(c1, c4)) for (w in stop.lesson!!.rondeWords) for (p in Person.values()) for (second in listOf(false, true)) {
            val q = question(stop, p, second, w.ceb)
            assertTrue(q.french, q.french.endsWith(".") && "  " !in q.french)
            assertTrue(q.sentence, q.sentence.endsWith(".") && q.sentence.first().isUpperCase())
        }
    }

    @Test
    fun rondeAnswersAreChecked() {
        fun check(q: RondeQuestion, given: String) = Ronde.check(q, given)
        // Leçon 1, 1er tour : « tu », c'est ka, et l'appli dit pourquoi.
        val you = check(question(c1, Person.YOU, false), "ka")
        assertTrue(you.correct)
        assertEquals(Ronde.YOU_NOTE, you.note)
        assertEquals(null, check(question(c1, Person.WE_EX, false), "kami").note)
        val wrongWe = check(question(c1, Person.WE_EX, false), "kita")
        assertFalse(wrongWe.correct)
        assertEquals("**kita**, c'est « nous (avec toi) ». Pour « nous (sans toi) », c'est **kami**.", wrongWe.note)
        // Leçon 1, 2e tour : la forme courte, mais la forme longue est juste aussi.
        val me = question(c1, Person.ME, true)
        assertTrue(check(me, "ko").correct)
        assertEquals(null, check(me, " Ko ").note)
        assertTrue(check(me, "${me.scene} ko").correct)
        val long = check(me, "ako")
        assertTrue(long.correct)
        assertTrue(long.note!!.startsWith("La forme courte, c'est **ko**"))
        val ikaw = check(question(c1, Person.YOU, true), "ikaw")
        assertTrue(ikaw.correct)
        assertTrue(ikaw.note!!.contains("**ka**"))
        assertFalse(check(me, "ka").correct)
        assertEquals("**ka**, c'est « tu ». Pour « je », c'est **ko**.", check(me, "ka").note)
        assertEquals("**mo**, c'est « vous ». Pour « tu », c'est **ka**.", check(question(c1, Person.YOU, true), "mo").note)
        assertTrue(check(me, "nako").note!!.contains("leçon 4"))
        assertEquals("La bonne réponse est **ko**.", check(me, "bata").note)
        assertFalse("pas de faute de frappe permise", check(question(c1, Person.WE_EX, true), "mo").correct)
        // Leçon 4 : avant ou après la chose.
        val mine = question(c4, Person.ME, true, "balay")
        assertTrue(check(mine, "nako").correct)
        assertTrue(check(mine, "Kini ang balay nako").correct)
        val ko = check(mine, "ko")
        assertTrue(ko.correct)
        assertEquals("On dit aussi **nako** : Kini ang balay nako.", ko.note)
        val before = check(mine, "akong")
        assertFalse(before.correct)
        assertEquals("**akong** se met avant la chose : akong balay. Après, c'est **nako** : balay nako.", before.note)
        assertEquals("**nimo**, c'est « à toi ». Pour « à moi », c'est **nako**.", check(mine, "nimo").note)
        assertEquals("**mo**, c'est « à toi ». Pour « à vous », c'est **ninyo**.", check(question(c4, Person.YOU_PL, true), "mo").note)
        val among = check(question(c4, Person.WE_EX, false), "atong")
        assertFalse(among.correct)
        assertEquals("**atong**, c'est « à nous (avec toi) ». Pour « à nous (sans toi) », c'est **among**.", among.note)
    }

    @Test
    fun theFullRondeHasTwoRoundsOfSeven() {
        val p = Progress().apply { installDay = 0 }
        val s = Ronde.full(content, p, c1, 10, Random(3))
        assertEquals(Mode.RONDE, s.mode)
        assertEquals(c1, s.stop)
        assertEquals(16, s.steps.size)
        assertEquals(14, s.gradedTotal)
        assertTrue(s.steps[0] is RondeIntroStep && s.steps[8] is RondeIntroStep)
        val first = s.steps.subList(1, 8).map { (it as RondeStep).q }
        val second = s.steps.subList(9, 16).map { (it as RondeStep).q }
        assertEquals(Person.values().toSet(), first.map { it.person }.toSet())
        assertEquals(Person.values().toSet(), second.map { it.person }.toSet())
        assertTrue(first.all { it.tap && !it.second && it.options.size == 7 && it.answer in it.options && it.options == first[0].options })
        assertTrue(second.all { !it.tap && it.second && it.options.isEmpty() })
        assertEquals("un mot par tour", 1, first.map { it.scene }.toSet().size)
        assertTrue("un autre mot au 2e tour", first[0].scene != second[0].scene)
        checkSteps(s)
        // Deux erreurs : elles sont gardées pour le tableau de fin, et ces personnes reviendront le soir.
        var n = 0
        while (!s.finished) {
            val step = s.current!!
            s.onShown(step)
            if (step is RondeStep) s.submitRonde(step, if (n++ in setOf(2, 9)) "xyz" else step.q.answer)
            s.next()
        }
        assertEquals(14, s.rondeAnswers.size)
        assertEquals(12, s.correctFirst)
        assertEquals(14, s.gradedAnswered)
        val missed = s.rondeAnswers.filter { !it.check.correct }.map { it.q.row.key }
        assertEquals(2, missed.size)
        for (row in missed) assertTrue(p.misses(c1, row) >= 1)
        assertEquals(10L, p.rondeDays["c1"])
        assertEquals(12 * Progress.POINTS_ANSWER, p.finishPractice(content, 10, s.correctFirst).points)
    }

    @Test
    fun aSmallRondeSometimesEndsTheEvening() {
        val p = Progress().apply { installDay = 0 }
        assertEquals("pas avant d'avoir vu la leçon 1", null, Ronde.eveningLesson(content, p, 0))
        p.lessonsSeen.add("c1")
        p.rondeMisses["c1:WE_EX"] = 3
        val s = Planner.build(content, p, Slot.SOIR, Line.COMPLEX, 2, Random(4))
        assertEquals(10, s.gradedTotal)
        val ronde = s.steps.filterIsInstance<RondeStep>()
        assertEquals(Ronde.MINI, ronde.size)
        assertEquals("à la fin de la séance", ronde, s.steps.takeLast(Ronde.MINI))
        assertEquals("la personne la plus ratée d'abord", Person.WE_EX, ronde.first().q.person)
        assertEquals(listOf(true, true, false), ronde.map { it.q.tap })
        assertEquals(1, ronde.map { it.q.scene }.toSet().size)
        checkSteps(s)
        playSession(s, Random(5), 1.0)
        assertEquals(2L, p.rondeDays["c1"])
        assertEquals("une bonne réponse : elle revient un peu moins", 2, p.misses(c1, "WE_EX"))
        // Pas tous les soirs : au plus tous les 3 jours, et pas le matin ni les soirs de vocabulaire.
        assertTrue(Planner.build(content, p, Slot.SOIR, Line.COMPLEX, 4, Random(6)).steps.none { it is RondeStep })
        assertTrue(Planner.build(content, p, Slot.MATIN, Line.VOCAB, 6, Random(6)).steps.none { it is RondeStep })
        assertTrue(Planner.build(content, p, Slot.SOIR, Line.VOCAB, 6, Random(6)).steps.none { it is RondeStep })
        // La leçon 4 vue aussi : sa ronde passe d'abord (elle n'a encore jamais été faite).
        p.lessonsSeen.add("c4")
        val later = Planner.build(content, p, Slot.SOIR, Line.COMPLEX, 6, Random(7))
        assertEquals(10, later.gradedTotal)
        assertTrue(later.steps.filterIsInstance<RondeStep>().all { it.q.stop == c4 && it.q.theme === Possessifs })
        // Une ronde ratée d'une autre leçon (une distance) revient aussi le soir.
        p.lessonsSeen.add("c3")
        p.rondeDays["c4"] = 6
        p.rondeDays["c1"] = 6
        p.rondeMisses["c3:FAR"] = 4
        val far = Planner.build(content, p, Slot.SOIR, Line.COMPLEX, 9, Random(8)).steps.filterIsInstance<RondeStep>()
        assertEquals(Ronde.MINI, far.size)
        assertTrue(far.all { it.q.stop.key == "c3" })
        assertEquals(Distance.FAR, far.first().q.row.distance)
    }

    @Test
    fun everyLessonHasItsRonde() {
        val p = Progress().apply { installDay = 0 }
        for (stop in content.lessons) for (seed in 1..6) {
            val theme = Fabrique.theme(stop)!!
            val s = Ronde.full(content, p, stop, 10, Random(seed * 31 + stop.number))
            val rounds = s.steps.filterIsInstance<RondeIntroStep>()
            assertEquals(listOf(false, true), rounds.map { it.second })
            val questions = s.steps.filterIsInstance<RondeStep>().map { it.q }
            for (second in listOf(false, true)) {
                val round = questions.filter { it.second == second }
                // Chaque ligne du tableau (personne, distance, moment…) revient à chaque passage.
                assertEquals("${stop.key} : ${round.map { it.row.key }}", theme.passes * theme.rows.size, round.size)
                assertEquals(theme.rows.map { it.key }.toSet(), round.map { it.row.key }.toSet())
                for (q in round) assertEquals(theme.passes, round.count { it.row.key == q.row.key })
            }
            checkSteps(s)
            for (q in questions) checkRondeQuestion(q)
        }
    }

    @Test
    fun theEveningRondeWorksForEveryLesson() {
        val p = Progress().apply { installDay = 0 }
        for (stop in content.lessons) for (seed in 1..5) {
            val mini = Ronde.mini(content, p, stop, Random(seed))
            assertEquals(stop.key, Ronde.MINI, mini.size)
            assertEquals(listOf(true, true, false), mini.map { it.q.tap })
            assertEquals("une seule scène", 1, mini.map { it.q.scene }.toSet().size)
            assertEquals("trois lignes différentes", Ronde.MINI, mini.map { it.q.row.key }.toSet().size)
            for (step in mini) checkRondeQuestion(step.q)
        }
    }

    @Test
    fun rondesOfTheOtherLessons() {
        fun questions(key: String, seed: Int = 1) =
            Ronde.full(content, Progress(), content.stopByKey(key)!!, 10, Random(seed)).steps.filterIsInstance<RondeStep>().map { it.q }
        // Leçon 3 : kini, kana, kadto au 1er tour ; ni, na, to au 2e. L'autre forme est juste aussi.
        val c3 = questions("c3")
        assertEquals(setOf("kini", "kana", "kadto"), c3.filter { !it.second }.map { it.answer }.toSet())
        assertEquals(setOf("ni", "na", "to"), c3.filter { it.second }.map { it.answer }.toSet())
        val far = c3.first { it.second && it.row.distance == Distance.FAR }
        assertTrue(Ronde.check(far, "kadto").correct)
        assertEquals("**na**, c'est « près de toi ». Pour « là-bas », c'est **to**.", Ronde.check(far, "na").note)
        assertTrue(Ronde.check(far, "didto").note!!.contains("leçon 8"))
        // Leçons 6 et 7 : le verbe en entier ; mi- est juste aussi pour « c'est fait ».
        val past = questions("c6").first { it.row.moment == Moment.PAST }
        assertTrue(past.answer.startsWith("Ni"))
        assertTrue(Ronde.check(past, "Mi" + past.answer.drop(2)).correct)
        assertFalse(Ronde.check(past, "Mo" + past.answer.drop(2)).correct)
        val order = questions("c7").first { it.row.moment == Moment.ORDER }
        assertTrue(order.answer.startsWith("Pag") && order.sentence.endsWith("!") && order.french.endsWith(" !"))
        // Leçon 8 : dinhi, dinha, didto ; diri et dira sont justes aussi.
        val here = questions("c8").first { it.row.distance == Distance.HERE }
        assertEquals("dinhi", here.answer)
        assertTrue(Ronde.check(here, "diri").correct)
        assertTrue(Ronde.check(here, "kini").note!!.contains("leçon 3"))
        // Leçon 9 : kanako, kanimo… ; nako et « sa ako » sont justes aussi.
        val me = questions("c9").first { it.person == Person.ME }
        assertEquals("kanako", me.answer)
        assertTrue(Ronde.check(me, "sa ako").correct)
        assertTrue(Ronde.check(me, "nako").correct)
        assertFalse(Ronde.check(me, "kanimo").correct)
        // Leçon 10 : au 1er tour on touche le lien, au 2e on écrit l'adjectif relié.
        val c10 = questions("c10", 2)
        val vowel = c10.first { !it.second && it.row.key == "ng" }
        assertEquals("-ng", vowel.answer)
        assertFalse(Ronde.check(vowel, "nga").correct)
        val n = c10.first { !it.second && it.row.key == "g" }
        assertTrue("après un n, nga est juste aussi", Ronde.check(n, "nga").correct)
        val written = c10.first { it.second && it.row.key == "ng" }
        assertTrue(written.answer.endsWith("ng") && " " !in written.answer)
        assertEquals("RELIE", written.question)
        assertFalse(Ronde.check(written, written.answer.dropLast(2)).correct)
        // Leçon 11 : une longue phrase ; le bandeau dit ce qui change.
        val c11 = questions("c11")
        assertEquals(setOf("QUAND ?", "QUI ?", "OÙ ?", "POUR QUI ?"), c11.map { it.question }.toSet())
        assertTrue(c11.all { it.sentence.split(" ").size >= 6 })
    }

    /** Une question de ronde bien faite : la phrase, sa traduction, les choix, et les mots qu'on peut toucher. */
    private fun checkRondeQuestion(q: RondeQuestion) {
        val f = q.frase
        assertTrue(q.sentence, q.sentence.first().isUpperCase() && q.sentence.last() in ".!?" && "  " !in q.sentence)
        assertTrue(q.french, q.french.first().isUpperCase() && q.french.last() in ".!?" && "  " !in q.french && " ," !in q.french)
        assertTrue("${q.answer} dans ${q.sentence}", f.glued || Answers.normalize(q.sentence).contains(Answers.normalize(q.answer)))
        assertTrue(Ronde.check(q, q.answer).correct)
        assertTrue("la phrase recopiée : ${q.sentence}", Ronde.check(q, q.sentence).correct)
        if (q.tap) {
            assertEquals(f.choices.toSet(), q.options.toSet())
            assertEquals(f.choices.size, f.choices.map { Answers.normalize(it) }.toSet().size)
            for (o in q.options) {
                val right = Answers.same(o, q.answer) || f.accepted.keys.any { Answers.same(it, o) }
                val c = Ronde.check(q, o)
                assertEquals("${q.sentence} : $o", right, c.correct)
                if (!right) assertNotNull(c.note)
            }
        }
        for (form in f.accepted.keys) assertTrue("$form pour ${q.sentence}", Ronde.check(q, form).correct)
        for (form in f.wrong.keys) assertFalse("$form pour ${q.sentence}", Ronde.check(q, form).correct)
        // Chaque mot se touche pour voir son sens.
        for (t in Content.tokenize(q.sentence)) assertNotNull("$t dans ${q.sentence}", content.gloss(t))
    }

    // ---------- Les phrases fabriquées ----------

    @Test
    fun frenchGrammar() {
        val maison = Francais.Nom("maison", "maisons", true)
        val arbre = Francais.Nom("arbre", "arbres", false)
        val ecole = Francais.Nom("école", "écoles", true)
        assertEquals("la maison", maison.le())
        assertEquals("l'arbre", arbre.le())
        assertEquals("les arbres", arbre.le(true))
        assertEquals("de l'école", ecole.of())
        assertEquals("à l'école", ecole.at())
        assertEquals("cette maison", maison.ce())
        assertEquals("cet arbre", arbre.ce())
        assertEquals("mon école", Francais.possessive(0, ecole))
        assertEquals("sa maison", Francais.possessive(2, maison))
        assertEquals("leurs arbres", Francais.possessive(5, arbre, true))
        val beau = Francais.Adjectif(listOf("beau", "belle", "beaux", "belles"), before = true, bel = "bel")
        assertEquals("un bel arbre", beau.un(arbre))
        assertEquals("de belles maisons", beau.un(maison, true))
        val noir = Francais.Adjectif(listOf("noir", "noire", "noirs", "noires"))
        assertEquals("une maison noire", noir.un(maison))
        val il = Francais.pronoun(2, false)
        val elles = Francais.pronoun(5, true)
        val je = Francais.pronoun(0, false)
        val partir = Francais.Verbe("partir", "parti", "pars", etre = true)
        assertEquals("elles sont parties", partir.past(elles))
        assertEquals("je vais partir", partir.future(je))
        val asseoir = Francais.Verbe("asseoir", "assis", "assieds-toi", pronominal = true)
        assertEquals("il va s'asseoir", asseoir.future(il))
        assertEquals("elles se sont assises", asseoir.past(elles))
        assertEquals("je me suis assis", asseoir.past(je))
        assertEquals("Assieds-toi", asseoir.order())
        val acheter = Francais.Verbe("acheter", "acheté", "achète")
        assertEquals("j'ai acheté", acheter.past(je))
        val suivre = Francais.Verbe("suivre", "suivi", "suis")
        assertEquals("il va me suivre", suivre.future(il, Francais.objectPronoun(0, false)))
        assertEquals("il va la suivre", suivre.future(il, Francais.objectPronoun(2, true)))
        val manger = Francais.Verbe("manger", "mangé", "mange")
        assertEquals("nous mangeons", manger.now(Francais.pronoun(3, false)))
        assertEquals("Mange un œuf !", Francais.sentence("${manger.order()} un œuf", '!'))
    }

    @Test
    fun verbForms() {
        val wait = Lexique.verbe("hulat")!!
        assertEquals(listOf("maghulat", "naghulat", "paghulat"), listOf(wait.future, wait.past, wait.command))
        val ready = Lexique.verbe("andam")!!
        assertEquals(listOf("mag-andam", "nag-andam", "pag-andam"), listOf(ready.future, ready.past, ready.command))
        val eat = Lexique.verbe("kaon")!!
        assertEquals(listOf("mokaon", "nikaon", "mikaon", "kaon"), listOf(eat.future, eat.past, eat.pastAlt, eat.command))
        // Les verbes conjugués, les prénoms et les adjectifs reliés se touchent aussi.
        assertEquals("va manger", content.gloss("mokaon"))
        assertEquals("va attendre", content.gloss("Maghulat"))
        assertEquals("Lorie (un prénom)", content.gloss("Lorie"))
        assertEquals("Ruth (un prénom)", Fabrique.gloss("ruth"))
        assertEquals("grand (dako + ng)", content.gloss("dakong"))
        assertEquals("gentil (buotan + g)", content.gloss("buotang"))
        // ni- et mi- disent la même chose : jamais l'un comme piège de l'autre.
        val past = Item("t:nikaon", Line.SIMPLE, 1, "Nikaon ko.", "J'ai mangé.", null, emptyList(), null, null, false, null, null, null)
        val variants = Variants.all(content, past).map { it.text }
        assertTrue("$variants", "Mokaon ko." in variants && variants.none { it.startsWith("Mikaon") })
    }

    @Test
    fun fabricatedSentencesAreValid() {
        val p = Progress().apply { installDay = 0 }
        val random = Random(21)
        for (item in content.items(Line.COMPLEX)) repeat(12) {
            val b = Fabrique.build(content, p, item, random) ?: error("rien pour ${item.ceb}")
            checkBuilt(b, item)
        }
    }

    @Test
    fun fabricatedSentencesInTheSessions() {
        val p = Progress().apply { installDay = 0 }
        content.items.forEach { p.introduce(it, 0) }
        val random = Random(22)
        var phrases = 0
        var built = 0
        repeat(40) { day ->
            val s = Planner.build(content, p, Slot.SOIR, Line.COMPLEX, day.toLong(), random)
            assertEquals(Planner.EXERCISES, s.gradedTotal)
            checkSteps(s)
            for (ex in s.steps.filterIsInstance<Exercise>().filter { it.kind != Kind.MATCH }) for (item in ex.items) if (item.line == Line.COMPLEX) {
                phrases++
                if (item is Built) built++
            }
            playSession(s, random, 0.8)
        }
        println("Phrases fabriquées dans les séances : $built sur $phrases")
        assertTrue("$built sur $phrases", built * 100 >= phrases * 70 && built < phrases)
        // La progression reste celle de la phrase du cours.
        assertTrue(p.items.keys.all { it in content.byId })
        // Traduire des phrases : des phrases fabriquées aussi, jamais trop longues à écrire.
        val q = Progress().apply { installDay = 0 }
        content.items(Line.COMPLEX).forEach { q.introduce(it, 0) }
        val t = Planner.buildTranslation(content, q, words = false, today = 1, random = Random(23))!!
        checkSteps(t)
        assertTrue(t.steps.filterIsInstance<Exercise>().count { it.item is Built } >= 5)
        // S'entraîner sur une leçon : des phrases fabriquées pour les phrases déjà vues.
        val practice = Planner.buildLessonPractice(content, p, content.stopByKey("c9")!!, 50, Random(24))
        checkSteps(practice)
        assertTrue(practice.steps.filterIsInstance<Exercise>().count { it.item is Built } >= 5)
    }

    @Test
    fun aFabricatedSentenceUpdatesItsCourseSentence() {
        val p = Progress().apply { installDay = 0 }
        val item = content.items(Line.COMPLEX).first { it.ceb == "Taas ako." }
        p.introduce(item, 0)
        val b = Fabrique.build(content, p, item, Random(3))!!
        assertEquals(item.id, b.id)
        assertEquals(item, b.source)
        val s = Session(content, p, null, Line.COMPLEX, 0, mutableListOf(Planner.exercise(content, Kind.WRITE, b, true, Random(4))), Random(5))
        s.onShown(s.current!!)
        s.submit(s.current as Exercise, true)
        assertEquals(2, p.level(item))
        assertEquals(setOf(item.id), p.items.keys)
    }

    /** Une phrase fabriquée bien faite : la même progression, une phrase et une traduction propres, de bons pièges. */
    private fun checkBuilt(b: Built, source: Item) {
        val where = "${b.ceb} (pour ${source.ceb})"
        assertEquals(source.id, b.id)
        assertEquals(source.line, b.line)
        assertEquals(source.stop, b.stop)
        assertTrue(where, b.ceb.first().isUpperCase() && b.ceb.last() in ".!?" && "  " !in b.ceb)
        assertTrue("${b.fr} ($where)", b.fr.first().isUpperCase() && b.fr.last() in ".!?" && "  " !in b.fr && " ," !in b.fr && "((" !in b.fr)
        if (b.hasGap) {
            assertEquals(where, 4, b.gapOptions!!.size)
            assertEquals(where, 4, b.gapOptions!!.map { Answers.normalize(it) }.toSet().size)
            assertEquals(where, b.ceb, b.gapBefore + b.gapAnswer + b.gapAfter)
        }
        val accepted = (listOf(b.ceb) + b.alts).map { Answers.normalize(it) }
        for (v in b.variants) assertTrue("${v.text} ($where)", Answers.normalize(v.text) !in accepted)
        for (t in b.frTraps) assertFalse("${t.fr} ($where)", Answers.same(t.fr, b.fr))
        // Chaque mot se touche pour voir son sens, même les pièges.
        val words = b.tokens + (b.gapOptions ?: emptyList()).flatMap { Content.tokenize(it) }
        for (t in words) assertNotNull("$t dans $where", content.gloss(t))
        for (s in listOf(b.ceb) + b.alts) assertEquals(Answers.Verdict.RIGHT, Answers.checkSentence(s, b, content).verdict)
        assertEquals(Answers.Verdict.RIGHT, Answers.checkFrench(b.fr, b))
        val random = Random(b.ceb.hashCode())
        val kinds = listOf(Kind.CHOOSE_CEB, Kind.CHOOSE_FR) + (if (b.tokens.size >= 2) listOf(Kind.ORDER) else emptyList()) + (if (b.hasGap) listOf(Kind.GAP) else emptyList())
        for (kind in kinds) {
            checkExercise(Planner.exercise(content, kind, b, true, random))
        }
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
    fun sessionsAlwaysBringNewWords() {
        // Les 2 premiers arrêts sont vus, mais pas encore bien connus : le jeepney n'a pas bougé.
        val p = Progress().apply { installDay = 0 }
        val stops = content.stops(Line.VOCAB)
        (stops[0].items + stops[1].items).forEach { p.introduce(it, 0) }
        assertEquals(stops[0], p.currentStop(content, Line.VOCAB))
        val s = Planner.build(content, p, Slot.MATIN, Line.VOCAB, 1, Random(13))
        val news = s.steps.filterIsInstance<NewStep>().map { it.item }
        assertEquals("les mots de l'arrêt 3", stops[2].items.take(3), news)
        checkSteps(s)
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
        p.rondeAnswer(content.stopByKey("c1")!!, "WE_EX", false, 20000)
        p.rondeAnswer(content.stopByKey("c4")!!, "THEY", false, 19998)
        val copy = Progress.decode(p.encode())
        assertEquals(p.encode(), copy.encode())
        assertEquals(p.items.size, copy.items.size)
        assertEquals(p.points, copy.points)
        assertEquals(p.slotsDone(20000), copy.slotsDone(20000))
        assertEquals(p.badges.keys.toList(), copy.badges.keys.toList())
        assertEquals(setOf("c4", "questions"), copy.lessonsRead)
        assertEquals(mapOf("c1:WE_EX" to 2, "c4:THEY" to 2), copy.rondeMisses)
        assertEquals(mapOf("c1" to 20000L, "c4" to 19998L), copy.rondeDays)
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
            if (step is RondeStep) {
                val ok = random.nextDouble() < accuracy
                assertEquals(ok, s.submitRonde(step, if (ok) step.q.answer else "bata").correct)
            }
            s.next()
        }
    }

    /** Chaque carte est suivie de sa question, et chaque question a de bons choix. */
    private fun checkSteps(s: Session) {
        s.steps.forEachIndexed { i, step ->
            when (step) {
                is LessonStep -> assertTrue(s.steps[i + 1] is NewStep)
                // En séance, la question suit la carte ; en traduction, elle vient un peu plus loin.
                is NewStep -> if (s.mode.isTranslation) {
                    assertTrue(s.steps.drop(i + 1).any { it is Exercise && it.item == step.item })
                } else {
                    assertEquals(step.item, (s.steps[i + 1] as Exercise).item)
                }
                is Exercise -> checkExercise(step)
                is RondeIntroStep -> assertTrue(s.steps[i + 1] is RondeStep)
                is RondeStep -> {
                    val q = step.q
                    if (q.tap) assertEquals(q.frase.choices.toSet(), q.options.toSet())
                    assertTrue(Ronde.check(q, q.answer).correct)
                }
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
