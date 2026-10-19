package com.thomas.bibisaya.engine

import java.text.Normalizer

/** Vérifie les réponses : les majuscules, les accents et les tirets ne comptent pas. */
object Answers {
    /** RIGHT : juste. TYPO : petite faute de frappe, acceptée. WRONG : faux. */
    enum class Verdict { RIGHT, TYPO, WRONG }

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

    /** Phrase remise dans l'ordre (étiquettes choisies) pour [item]. */
    fun checkOrder(tiles: List<String>, item: Item): Boolean {
        val given = tiles.joinToString(" ") { normalize(it) }
        val accepted = (listOf(item.ceb) + item.alts).map { s -> Content.tokenize(s).joinToString(" ") { normalize(it) } }
        return given in accepted
    }

    fun same(a: String, b: String) = normalize(a) == normalize(b)
}
