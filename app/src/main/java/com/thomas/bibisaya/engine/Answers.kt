package com.thomas.bibisaya.engine

import java.text.Normalizer

/** Vérifie les réponses : les majuscules, les accents et les tirets ne comptent pas. */
object Answers {
    /** RIGHT : juste. TYPO : petite faute de frappe, acceptée. WRONG : faux. */
    enum class Verdict { RIGHT, TYPO, WRONG }

    /** Ce qu'est devenu un mot de la bonne phrase dans la phrase tapée. */
    enum class Mark { OK, TYPO, WRONG, MISSING }

    /** Un mot de la bonne phrase (avec sa ponctuation). [given] : le mot tapé à sa place. */
    class WordCheck(val word: String, val mark: Mark, val given: String?)

    /** Une phrase tapée, corrigée mot par mot. [expected] : la bonne réponse la plus proche. */
    class SentenceCheck(val verdict: Verdict, val expected: String, val words: List<WordCheck>, val extra: List<String>) {
        val missing get() = words.filter { it.mark == Mark.MISSING }
        val wrong get() = words.filter { it.mark == Mark.WRONG }
        internal var cost = 0
    }

    fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** « Lig-on! » -> « ligon » */
    fun normalize(s: String): String =
        stripAccents(s.lowercase()).filter { it.isLetterOrDigit() }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /** Combien de fautes de frappe on pardonne pour un mot de cette longueur. */
    private fun allowedTypos(length: Int) = when {
        length >= 9 -> 2
        length >= 4 -> 1
        else -> 0
    }

    /**
     * Réponse tapée au clavier pour [item]. Avec [content], un autre mot de l'appli n'est jamais pris pour
     * une faute de frappe (bata n'est pas bato).
     */
    fun checkWritten(input: String, item: Item, content: Content? = null): Verdict {
        val given = normalize(input)
        if (given.isEmpty()) return Verdict.WRONG
        val accepted = (listOf(item.ceb) + item.alts).map { normalize(it) }
        if (given in accepted) return Verdict.RIGHT
        if (content != null && content.itemsWithForm(given).isNotEmpty()) return Verdict.WRONG
        val close = accepted.any { levenshtein(given, it) <= allowedTypos(it.length) }
        return if (close) Verdict.TYPO else Verdict.WRONG
    }

    /** Les mots qui s'écrivent de deux façons, ou qui ont une forme courte : les deux sont justes. */
    private val equivalents: Set<Pair<String, String>> = listOf(
        "ako" to "ko", "ikaw" to "ka", "kita" to "ta", "kami" to "mi", "kamo" to "mo",
        "nako" to "ko", "nimo" to "mo", "nato" to "ta",
        "ug" to "og", "palihug" to "palihog", "pod" to "sab",
        "dinhi" to "diri", "dinha" to "dira", "dinha" to "diha", "dira" to "diha",
    ).flatMap { listOf(it, it.second to it.first) }.toSet()

    private fun equivalent(expected: String, given: String) = expected == given || (expected to given) in equivalents

    /**
     * Une phrase tapée en entier pour [item] : juste, juste avec des fautes de frappe, ou fausse.
     * La correction dit quels mots manquent, sont faux ou en trop.
     */
    fun checkSentence(input: String, item: Item, content: Content): SentenceCheck {
        val forms = listOf(item.ceb) + item.alts
        // Mêmes lettres (sans espaces, tirets ni ponctuation) : c'est juste.
        val whole = normalize(input)
        forms.firstOrNull { normalize(it) == whole && whole.isNotEmpty() }?.let { f ->
            return SentenceCheck(Verdict.RIGHT, f, words(f).map { WordCheck(it, Mark.OK, it) }, emptyList())
        }
        val given = Content.tokenize(input)
        return forms.map { align(it, given, content) }
            .minWith(compareBy<SentenceCheck>({ it.verdict.ordinal }, { it.cost }))
    }

    private fun words(sentence: String) = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Met la phrase tapée en face de la bonne, mot par mot (le moins de changements possible). */
    private fun align(expected: String, given: List<String>, content: Content): SentenceCheck {
        val shown = words(expected).filter { Content.cleanToken(it).isNotEmpty() }
        val e = shown.map { normalize(Content.cleanToken(it)) }
        val g = given.map { normalize(it) }
        val n = e.size
        val m = g.size
        fun pairCost(i: Int, j: Int): Int = when {
            equivalent(e[i], g[j]) -> 0
            // Un autre vrai mot n'est pas une faute de frappe (ka n'est pas ko, magtrabaho n'est pas nagtrabaho).
            !content.isKnownWord(given[j]) && levenshtein(e[i], g[j]) <= allowedTypos(e[i].length) -> COST_TYPO
            else -> COST_CHANGE
        }
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i * COST_CHANGE
        for (j in 0..m) d[0][j] = j * COST_CHANGE
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = minOf(d[i - 1][j - 1] + pairCost(i - 1, j - 1), d[i - 1][j] + COST_CHANGE, d[i][j - 1] + COST_CHANGE)
        }
        val marks = arrayOfNulls<WordCheck>(n)
        val extra = mutableListOf<String>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val c = if (i > 0 && j > 0) pairCost(i - 1, j - 1) else -1
            if (c >= 0 && d[i][j] == d[i - 1][j - 1] + c) {
                val mark = when (c) {
                    0 -> Mark.OK
                    COST_TYPO -> Mark.TYPO
                    else -> Mark.WRONG
                }
                marks[i - 1] = WordCheck(shown[i - 1], mark, given[j - 1])
                i--
                j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + COST_CHANGE) {
                marks[i - 1] = WordCheck(shown[i - 1], Mark.MISSING, null)
                i--
            } else {
                extra.add(0, given[j - 1])
                j--
            }
        }
        val words = marks.map { it!! }
        val verdict = when {
            extra.isNotEmpty() || words.any { it.mark == Mark.WRONG || it.mark == Mark.MISSING } -> Verdict.WRONG
            words.any { it.mark == Mark.TYPO } -> Verdict.TYPO
            else -> Verdict.RIGHT
        }
        return SentenceCheck(verdict, expected, words, extra).also { it.cost = d[n][m] }
    }

    private const val COST_TYPO = 2
    private const val COST_CHANGE = 5

    /** Les petits mots du français qu'on peut mettre ou non devant un nom (« la maison » = « maison »). */
    private val articles = listOf("le ", "la ", "les ", "l'", "l’", "un ", "une ", "des ", "du ", "de la ", "de l'", "d'")

    private fun withoutArticle(s: String): String {
        val t = s.trim().lowercase()
        val a = articles.firstOrNull { t.startsWith(it) } ?: return t
        return t.removePrefix(a)
    }

    private fun withoutNotes(s: String) = s.replace(Regex("\\([^)]*\\)"), " ")

    /** Les traductions françaises acceptées : chaque sens (« l'ami, l'amie »), sans les remarques entre parenthèses. */
    fun frenchForms(item: Item): Set<String> {
        val plain = withoutNotes(item.fr)
        val parts = if (item.isWord) plain.split(Regex(",| ou |/")) + plain else listOf(plain)
        return parts.map { normalize(withoutArticle(it)) }.filter { it.isNotEmpty() }.toSet()
    }

    private fun frenchWords(s: String) = Content.tokenize(withoutArticle(withoutNotes(s))).map { normalize(it) }.filter { it.isNotEmpty() }

    /**
     * Une traduction tapée en français. WRONG veut seulement dire « pas mot pour mot » :
     * Thomas juge lui-même si sa phrase veut dire la même chose.
     */
    fun checkFrench(input: String, item: Item): Verdict {
        val given = normalize(withoutArticle(input))
        if (given.isEmpty()) return Verdict.WRONG
        val accepted = frenchForms(item)
        if (given in accepted) return Verdict.RIGHT
        if (item.isWord) return if (accepted.any { levenshtein(given, it) <= allowedTypos(it.length) }) Verdict.TYPO else Verdict.WRONG
        // Une phrase : une petite faute par mot, mais les mêmes mots dans le même ordre.
        val words = frenchWords(input)
        val expected = frenchWords(item.fr)
        val close = expected.size == words.size && expected.indices.all { levenshtein(expected[it], words[it]) <= allowedTypos(expected[it].length) }
        return if (close) Verdict.TYPO else Verdict.WRONG
    }

    /** Phrase remise dans l'ordre (étiquettes choisies) pour [item]. */
    fun checkOrder(tiles: List<String>, item: Item): Boolean {
        val given = tiles.joinToString(" ") { normalize(it) }
        val accepted = (listOf(item.ceb) + item.alts).map { s -> Content.tokenize(s).joinToString(" ") { normalize(it) } }
        return given in accepted
    }

    fun same(a: String, b: String) = normalize(a) == normalize(b)
}
