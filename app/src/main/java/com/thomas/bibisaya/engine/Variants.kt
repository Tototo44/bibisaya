package com.thomas.bibisaya.engine

import kotlin.random.Random

/**
 * Les pièges « presque pareils » : la même phrase avec un seul mot changé (ako -> ikaw, nimo -> nako,
 * nagtrabaho -> magtrabaho…). On ne peut plus deviner la réponse sans lire chaque mot.
 */
object Variants {
    /** Une phrase presque pareille : le mot n° [index] remplacé ([from] -> [to]). */
    class Variant(val text: String, val index: Int, val from: String, val to: String)

    /** Une « personne » d'un groupe : sa forme longue puis sa forme courte, et son sens. */
    private class Person(val forms: List<String>, val meaning: String, val family: String = meaning)

    /**
     * Les mots qu'on échange entre eux. Jamais deux formes de la même personne (ako / ko),
     * ni « nous avec toi » contre « nous sans toi » (le français dit « on » pour les deux).
     */
    private val groups: List<List<Person>> = listOf(
        listOf(
            Person(listOf("ako", "ko"), "je"),
            Person(listOf("ikaw", "ka"), "tu"),
            Person(listOf("siya"), "il, elle"),
            Person(listOf("kita", "ta"), "nous (avec toi)", "nous"),
            Person(listOf("kami", "mi"), "nous (sans toi)", "nous"),
            Person(listOf("kamo", "mo"), "vous"),
            Person(listOf("sila"), "ils, elles"),
        ),
        listOf(
            Person(listOf("nako"), "mon, à moi"),
            Person(listOf("nimo"), "ton, à toi"),
            Person(listOf("niya"), "son, à lui"),
            Person(listOf("nato"), "notre (avec toi)", "nous"),
            Person(listOf("namo"), "notre (sans toi)", "nous"),
            Person(listOf("ninyo"), "votre"),
            Person(listOf("nila"), "leur"),
        ),
        listOf(
            Person(listOf("akong"), "mon"),
            Person(listOf("imong"), "ton"),
            Person(listOf("iyang"), "son"),
            Person(listOf("atong"), "notre (avec toi)", "nous"),
            Person(listOf("among"), "notre (sans toi)", "nous"),
            Person(listOf("inyong"), "votre"),
            Person(listOf("ilang"), "leur"),
        ),
        listOf(
            Person(listOf("kanako"), "à moi"),
            Person(listOf("kanimo"), "à toi"),
            Person(listOf("kaniya"), "à lui, à elle"),
            Person(listOf("kanato"), "à nous (avec toi)", "nous"),
            Person(listOf("kanamo"), "à nous (sans toi)", "nous"),
            Person(listOf("kaninyo"), "à vous"),
            Person(listOf("kanila"), "à eux"),
        ),
        // Les formes courtes ni, na, to veulent aussi dire « de », « déjà »… : on ne les échange pas.
        listOf(
            Person(listOf("kini"), "ceci (près de moi)"),
            Person(listOf("kana"), "cela (près de toi)"),
            Person(listOf("kadto"), "cela (là-bas)"),
        ),
        listOf(
            Person(listOf("dinhi", "diri"), "ici"),
            Person(listOf("dinha", "dira"), "là (près de toi)"),
            Person(listOf("didto"), "là-bas"),
        ),
        listOf(
            Person(listOf("buntag"), "le matin"),
            Person(listOf("udto"), "midi"),
            Person(listOf("hapon"), "l'après-midi"),
            Person(listOf("gabii"), "le soir"),
        ),
        listOf(
            Person(listOf("karon"), "maintenant"),
            Person(listOf("ugma"), "demain"),
            Person(listOf("ganiha"), "tout à l'heure"),
            Person(listOf("gahapon"), "hier"),
        ),
    )

    /** Les débuts de verbe : mo- (ça va se faire), ni- / mi- (c'est fait), mag- / nag- / pag-. */
    private val prefixes = listOf(listOf("mo", "ni", "mi"), listOf("mag", "nag", "pag"))

    /** Les formes courtes : jamais en début de phrase (« Mo istorya ka… » n'est pas « vous »). */
    private val short = setOf("ko", "ka", "ta", "mi", "mo", "diri", "dira")

    private fun person(word: String): Pair<List<Person>, Person>? {
        for (g in groups) for (p in g) if (word in p.forms) return g to p
        return null
    }

    /** Le sens d'un mot qu'on échange, pour l'explication. */
    fun meaning(content: Content, word: String): String? {
        val w = Content.cleanToken(word).lowercase()
        return person(w)?.second?.meaning ?: content.gloss(w)
    }

    /** Les mots qui pourraient remplacer le mot n° [index] de [tokens]. */
    private fun swaps(content: Content, tokens: List<String>, index: Int): List<String> {
        val core = tokens[index]
        val w = core.lowercase()
        if (w in short && index == 0) return emptyList()
        // « usa ka kilo » : ce ka-là n'est pas « tu ».
        if (w == "ka" && index > 0 && tokens[index - 1].lowercase() == "usa") return emptyList()
        val out = mutableListOf<String>()
        person(w)?.let { (group, me) ->
            val isShort = me.forms.indexOf(w) > 0
            for (other in group) {
                if (other === me || other.family == me.family) continue
                out.add(if (isShort && other.forms.size > 1) other.forms[1] else other.forms[0])
            }
        }
        for (group in prefixes) {
            val p = group.firstOrNull { w.startsWith(it) && w.length > it.length + 2 } ?: continue
            val root = w.substring(p.length)
            for (q in group) {
                if (q == p) continue
                // Seulement un vrai mot (nindot ne devient pas « mondot »).
                if (content.gloss(q + root) != null) out.add(q + root)
            }
        }
        val inSentence = tokens.map { Answers.normalize(it) }.toSet()
        return out.filter { Answers.normalize(it) !in inSentence }.map { if (core.first().isUpperCase()) it.replaceFirstChar { c -> c.uppercase() } else it }
    }

    /** Toutes les phrases à un seul mot de différence avec [item] (pas ses autres réponses acceptées). */
    fun all(content: Content, item: Item): List<Variant> {
        val accepted = (listOf(item.ceb) + item.alts).map { Answers.normalize(it) }.toSet()
        val out = mutableListOf<Variant>()
        val words = item.ceb.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cores = words.map { Content.cleanToken(it) }
        val tokens = cores.filter { it.isNotEmpty() }
        if (item.hasGap) {
            val index = Content.tokenize(item.gapBefore ?: "").size
            for (o in item.gapOptions!!.drop(1)) {
                val text = (item.gapBefore ?: "") + o + (item.gapAfter ?: "")
                if (Answers.normalize(text) !in accepted) out.add(Variant(text, index, item.gapAnswer!!, o))
            }
            return out
        }
        var t = 0
        words.forEachIndexed { i, word ->
            val core = cores[i]
            if (core.isEmpty()) return@forEachIndexed
            val start = word.indexOf(core)
            for (rep in swaps(content, tokens, t)) {
                val changed = word.substring(0, start) + rep + word.substring(start + core.length)
                val text = (words.take(i) + changed + words.drop(i + 1)).joinToString(" ")
                if (Answers.normalize(text) !in accepted) out.add(Variant(text, t, core, rep))
            }
            t++
        }
        return out
    }

    /**
     * Jusqu'à [n] pièges presque pareils, tous sur le même mot : ainsi la bonne réponse
     * n'est pas « celle qui ressemble le plus aux autres ».
     */
    fun pick(content: Content, item: Item, random: Random, n: Int = 3): List<Variant> {
        val byIndex = all(content, item).groupBy { it.index }
        if (byIndex.isEmpty()) return emptyList()
        val best = byIndex.values.maxOf { it.size.coerceAtMost(n) }
        val index = byIndex.filter { it.value.size.coerceAtMost(n) == best }.keys.random(random)
        return byIndex.getValue(index).shuffled(random).take(n)
    }

    /** Les étiquettes-pièges qui ressemblent à un mot de la phrase (ka pour ko, magtrabaho pour nagtrabaho). */
    fun lookAlikes(content: Content, item: Item): List<Pair<Int, String>> {
        val tokens = item.tokens
        val inSentence = tokens.map { Answers.normalize(it) }.toSet()
        val out = mutableListOf<Pair<Int, String>>()
        if (item.hasGap) {
            val index = Content.tokenize(item.gapBefore ?: "").size
            for (o in item.gapOptions!!.drop(1)) for (w in Content.tokenize(o)) {
                if (Answers.normalize(w) !in inSentence) out.add(index to w)
            }
        }
        for (i in tokens.indices) for (w in swaps(content, tokens, i)) out.add(i to w)
        return out.distinctBy { Answers.normalize(it.second) }
    }

    /**
     * Si [chosen] et [answer] ne diffèrent que d'un mot : « Un seul mot change : **ka**, c'est « tu »,
     * et **ko**, c'est « je ». »
     */
    fun explain(content: Content, chosen: String, answer: String): String? {
        val a = Content.tokenize(chosen)
        val b = Content.tokenize(answer)
        if (a.size != b.size) return null
        val diff = a.indices.filter { Answers.normalize(a[it]) != Answers.normalize(b[it]) }
        if (diff.size != 1) return null
        val i = diff.single()
        val wrong = a[i].lowercase()
        val right = b[i].lowercase()
        val mw = meaning(content, wrong) ?: return null
        val mr = meaning(content, right) ?: return null
        return "Un seul mot change : ${describe(wrong, mw)}, et ${describe(right, mr)}."
    }

    /** « **ka**, c'est « tu » », ou « **si** (devant un prénom) » quand le sens est une simple remarque. */
    fun describe(word: String, meaning: String): String =
        if (meaning.startsWith("(") && meaning.endsWith(")")) "**$word** $meaning" else "**$word**, c'est « $meaning »"
}
