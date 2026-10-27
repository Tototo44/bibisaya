package com.thomas.bibisaya.engine

import com.thomas.bibisaya.engine.Francais.capital

/** « **kita**, c'est « nous (avec toi) ». Pour « nous (sans toi) », c'est **kami**. » */
private fun otherNote(form: String, meaning: String, mine: String, answer: String) =
    "**$form**, c'est « $meaning ». Pour « $mine », c'est **$answer**."

/** Une majuscule quand le mot commence la phrase. */
private fun at(before: String, form: String) = if (before.isEmpty()) capital(form) else form

/** Homme ou femme, pour un nom qui peut être les deux (higala : ami, amie). */
private fun gender(w: Words, n: Nom) = w.feminine(n)

/** Où, en français : « ici », « là (près de toi) » en fin de phrase, « là, près de toi, » quand la phrase continue. */
private fun whereFr(d: Distance, last: Boolean) = when (d) {
    Distance.HERE -> "ici"
    Distance.THERE -> if (last) "là (près de toi)" else "là, près de toi"
    Distance.FAR -> "là-bas"
}

// ---------- Leçon 1 ----------

/** Leçon 1 : ako, ka, siya… après l'adjectif (formes longues), ko, ka, ta… (formes courtes). */
object Pronoms : Theme(1) {
    override val title = "LA RONDE DES PERSONNES"
    override val subtitle = "2 tours de 7 questions : la personne change à chaque fois"
    override val question = "QUI ?"
    override val rows = Person.values().map { Row(it.name, it.who, person = it) }
    override fun round(second: Boolean) = if (second) "formes courtes" else "formes longues"
    override val columns = "LONGUE" to "COURTE"
    override val hint = "Écris la forme courte quand elle existe."
    override val endNote = "Pour « tu », après le mot, c'est toujours **ka** : **ikaw** se met au début de la phrase."

    override fun intro(second: Boolean) = if (second) {
        "Les 7 personnes encore, avec un autre mot. Cette fois, écris la forme courte : **Maestro ko.** = Je suis professeur.\n\n" +
            "**Siya** et **sila** n'ont pas de forme courte : écris-les comme ça."
    } else {
        "Une seule phrase, et la personne change à chaque question. Touche la forme longue : **ako**, **kita**, **kami**…\n\n" +
            "Pour « tu », c'est toujours **ka** après le mot : **ikaw** se met au début de la phrase."
    }

    fun form(p: Person, second: Boolean) = if (second) p.short else p.subject

    override fun helpForm(row: Row) = row.person!!.subject

    private fun forms(p: Person) = listOf(p.subject, p.short, p.long)

    /** Ce qu'on est : un adjectif ou un métier, avec ses 4 formes en français (fort, forte, forts, fortes). */
    class Pred(val ceb: String, val fem: String?, val fr: List<String>, val job: Boolean = false) {
        fun ceb(feminine: Boolean) = if (feminine && fem != null) fem else ceb
    }

    private val jobs = listOf(
        Pred("maestro", "maestra", listOf("professeur", "professeure", "professeurs", "professeures"), job = true),
        Pred("estudyante", null, listOf("élève", "élève", "élèves", "élèves"), job = true),
        Pred("doktor", null, listOf("médecin", "médecin", "médecins", "médecins"), job = true),
    )

    /** « Kusgan kami. » = « Nous sommes forts (pas toi). », « Mga maestro sila. » = « Ils sont professeurs. » */
    fun frase(pred: Pred, p: Person, second: Boolean, feminine: Boolean): Frase {
        val fem = feminine && p.gendered
        val word = pred.ceb(fem)
        val before = capital(if (pred.job && p.plural) "mga $word" else word)
        val answer = form(p, second)
        val (m, f, mp, fp) = pred.fr
        val french = when (p) {
            Person.ME -> "Je suis $m."
            Person.YOU -> "Tu es $m."
            Person.HE -> if (fem) "Elle est $f." else "Il est $m."
            Person.WE_IN -> "Nous sommes $mp (toi et moi)."
            Person.WE_EX -> "Nous sommes $mp (pas toi)."
            Person.YOU_PL -> "Vous êtes $mp."
            Person.THEY -> if (fem) "Elles sont $fp." else "Ils sont $mp."
        }
        val sentence = Frase.join(before, answer, "", ".")
        val accepted = linkedMapOf<String, String?>()
        for (form in forms(p)) if (Answers.normalize(form) != Answers.normalize(answer)) accepted[form] = if (p == Person.YOU) {
            "Après le mot, on dit **ka** : $sentence **Ikaw** se met au début de la phrase."
        } else {
            "La forme ${if (second) "courte" else "longue"}, c'est **$answer** : $sentence"
        }
        val wrong = linkedMapOf<String, String>()
        for (o in Person.values()) if (o != p) for (form in forms(o)) wrong.putIfAbsent(form, otherNote(form, o.who, p.who, answer))
        for (o in Person.values()) for (form in listOfNotNull(o.before, o.after, o.afterShort)) {
            wrong.putIfAbsent(form, "**$form**, c'est « ${o.whose} » (la leçon 4). Ici, c'est **$answer**.")
        }
        return Frase(
            before, answer, "", french, Person.values().map { form(it, second) },
            accepted = accepted, wrong = wrong,
            rightNote = if (p == Person.YOU && !second) Ronde.YOU_NOTE else null,
        )
    }

    /** La phrase de la ronde avec un adjectif de la leçon (contenu.txt : « ronde-adjectif: kusgan = fort, forte… »). */
    fun rondeFrase(word: RondeWord, p: Person, second: Boolean, feminine: Boolean) = frase(Pred(word.ceb, null, word.fr), p, second, feminine)

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val word = w.pick(w.content.stopByKey("c1")?.lesson?.rondeWords.orEmpty()) { it.ceb } ?: return null
        return Pass(word.ceb, plan.map { (row, second) -> row to rondeFrase(word, row.person!!, second, w.chance(50)) })
    }

    override fun build(w: Words, source: Item): Built? {
        val pred = if (w.chance(25)) w.pick(jobs) { it.ceb }!! else {
            val a = w.pick(Lexique.adjectifs.filter { Sorte.PERSON in it.sortes }) { it.ceb } ?: return null
            Pred(a.ceb, a.fem, a.fr.forms)
        }
        val p = w.any(Person.values().toList())
        val second = w.chance(40)
        val fem = w.chance(50)
        val f = frase(pred, p, second, fem)
        // Avec « Mga maestro », d'abord des pièges au pluriel : sinon on devine sans lire.
        val same = w.shuffled(Person.values().filter { it != p && (!pred.job || it.plural == p.plural) })
        val others = w.shuffled(Person.values().filter { it != p && it !in same })
        return built(source, f, (same + others).map { form(it, second) }, same.map { frase(pred, it, second, fem) })
    }
}

// ---------- Leçon 2 ----------

/** Leçon 2 : si devant un prénom, ang devant un nom, ang mga pour plusieurs, sila si pour plusieurs prénoms. */
object Articles : Theme(2) {
    override val title = "LA RONDE DES PETITS MOTS"
    override val subtitle = "si, ang, ang mga, sila si : ce qui suit change à chaque fois"
    override val question = "DEVANT"
    private val SI = Row("si", "un prénom")
    private val ANG = Row("ang", "un nom (le, la)")
    private val MGA = Row("ang-mga", "des noms (les)")
    private val SILA = Row("sila-si", "deux prénoms")
    override val rows = listOf(SI, ANG, MGA, SILA)
    override val hint = "Écris le petit mot qui va devant."

    override fun intro(second: Boolean) = if (second) {
        "Les mêmes petits mots, avec un autre adjectif. Cette fois, écris-les !"
    } else {
        "Une seule phrase, et ce qui suit le trou change à chaque question : un prénom, un nom, plusieurs… " +
            "Touche le bon petit mot : **si**, **ang**, **ang mga** ou **sila si**."
    }

    private fun form(r: Row) = when (r) {
        SI -> "si"
        ANG -> "ang"
        MGA -> "ang mga"
        else -> "sila si"
    }

    override fun helpForm(row: Row) = form(row)
    override val helpTitle = "LES 4 PETITS MOTS"
    override fun helpLabel(row: Row) = where(row)

    private fun where(r: Row) = when (r) {
        SI -> "devant un prénom"
        ANG -> "devant un nom (le, la)"
        MGA -> "devant plusieurs noms (les)"
        else -> "devant plusieurs prénoms"
    }

    private fun make(adj: String, row: Row, after: String, french: String): Frase {
        val answer = form(row)
        val example = "$answer $after"
        val wrong = linkedMapOf<String, String>()
        for (o in rows) if (o != row) wrong[form(o)] = "**${form(o)}** se met ${where(o)}. Ici, c'est **$answer** : $example."
        wrong["sa"] = "**sa** veut dire « à, dans, de » (leçon 5). Ici, c'est **$answer** : $example."
        wrong["ni"] = "**ni** veut dire « de » devant un prénom (leçon 5). Ici, c'est **$answer** : $example."
        return Frase(capital(adj), answer, after, french, rows.map { form(it) }, wrong = wrong)
    }

    /** « Buotan si Ernesto. », « Buotan ang bata. », « Buotan ang mga bata. », « Buotan sila si Marvin ug Annie. » */
    private fun frase(adj: Adj, row: Row, w: Words): Frase? {
        val people = Sorte.PERSON in adj.sortes
        return when (row) {
            SI -> {
                if (!people) return null
                val n = w.pick(Lexique.prenoms) { it.name } ?: return null
                make(adj.ceb(n.feminine), row, n.name, "${n.name} est ${adj.fr.agree(n.feminine, false)}.")
            }
            SILA -> {
                if (!people) return null
                val a = w.pick(Lexique.prenoms) { it.name } ?: return null
                val b = w.pick(Lexique.prenoms.filter { it !== a }) { it.name } ?: return null
                val fem = a.feminine && b.feminine
                make(adj.ceb(fem), row, "${a.name} ug ${b.name}", "${a.name} et ${b.name} sont ${adj.fr.agree(fem, true)}.")
            }
            else -> {
                val plural = row == MGA
                val noun = w.pick(Lexique.noms.filter { it.sorte in adj.sortes && !(plural && it.mass) }) { it.ceb } ?: return null
                val fem = gender(w, noun)
                val nom = noun.fr(fem)
                val french = Francais.sentence("${nom.le(plural)} ${if (plural) "sont" else "est"} ${adj.fr.agree(nom.feminine, plural)}")
                make(adj.ceb(fem && noun.sorte == Sorte.PERSON), row, noun.ceb, french)
            }
        }
    }

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val adj = w.pick(Lexique.adjectifs.filter { Sorte.PERSON in it.sortes }) { it.ceb } ?: return null
        return Pass(adj.ceb, plan.map { (row, _) -> row to (frase(adj, row, w) ?: return null) })
    }

    override fun build(w: Words, source: Item): Built? {
        val row = w.any(rows)
        val adj = w.pick(Lexique.adjectifs.filter { row == ANG || row == MGA || Sorte.PERSON in it.sortes }) { it.ceb } ?: return null
        val f = frase(adj, row, w) ?: return null
        val traps = when (row) {
            SI -> listOf("ang", "sa", "ni")
            ANG -> listOf("si", "sa", "ni")
            MGA -> listOf("si", "sa", "sila si")
            else -> listOf("si", "ang", "ang mga")
        }
        // « Sila si Perla ug si Tessie » : le 2e si est juste aussi.
        val alts = if (row == SILA) listOf(f.sentence.replace(" ug ", " ug si ")) else emptyList()
        return built(source, f, traps, rows.filter { it != row }.mapNotNull { frase(adj, it, w) }, alts = alts)
    }
}

// ---------- Leçon 3 ----------

/** Leçon 3 : kini (près de moi), kana (près de toi), kadto (là-bas), et leurs formes courtes ni, na, to. */
object Demonstratifs : Theme(3) {
    override val title = "LA RONDE DES DISTANCES"
    override val subtitle = "kini, kana, kadto : la chose change de place à chaque fois"
    override val question = "OÙ ?"
    override val rows = listOf(
        Row("HERE", "près de moi", distance = Distance.HERE),
        Row("THERE", "près de toi", distance = Distance.THERE),
        Row("FAR", "là-bas", distance = Distance.FAR),
    )
    override fun round(second: Boolean) = if (second) "formes courtes" else "formes longues"
    override val columns = "LONGUE" to "COURTE"
    override val hint = "Écris la forme courte : ni, na ou to."

    override fun intro(second: Boolean) = if (second) {
        "Les mêmes distances, avec une autre chose. Cette fois, écris la forme courte : **ni**, **na** ou **to**.\n\n" +
            "**Libro ni.** = C'est un livre (près de moi)."
    } else {
        "Une seule phrase, et la chose change de place à chaque question : près de moi, près de toi ou là-bas. " +
            "Regarde le dessin, et touche **kini**, **kana** ou **kadto**."
    }

    val long = listOf("kini", "kana", "kadto")
    private val short = listOf("ni", "na", "to")
    /** Le sens de chaque mot, pour expliquer une erreur dans la leçon 8. */
    val meanings = listOf("ceci (près de moi)", "cela (près de toi)", "cela (là-bas)")

    fun form(d: Distance, second: Boolean) = (if (second) short else long)[d.ordinal]

    override fun helpForm(row: Row) = "${long[row.distance!!.ordinal]} · ${short[row.distance.ordinal]}"
    override val helpTitle = "LES 3 DISTANCES"

    private val things get() = Lexique.noms.filter { it.sorte != Sorte.PERSON && it.sorte != Sorte.OTHER }

    /** « Libro kini. » = « C'est un livre (près de moi). », « Mga libro to. » = « Là-bas, ce sont des livres. » */
    fun frase(noun: Nom, plural: Boolean, row: Row, second: Boolean): Frase {
        val d = row.distance!!
        val before = capital(if (plural) "mga ${noun.ceb}" else noun.ceb)
        val answer = form(d, second)
        val what = if (plural) "ce sont des ${noun.fr().pl}" else "c'est ${noun.some}"
        val french = when (d) {
            Distance.HERE -> Francais.sentence("$what (près de moi)")
            Distance.THERE -> Francais.sentence("$what (près de toi)")
            Distance.FAR -> "Là-bas, $what."
        }
        val sentence = Frase.join(before, answer, "", ".")
        val accepted = mapOf<String, String?>(form(d, !second) to "La forme ${if (second) "courte" else "longue"}, c'est **$answer** : $sentence")
        val wrong = linkedMapOf<String, String>()
        for (o in rows) if (o != row) for (f in listOf(form(o.distance!!, second), form(o.distance, !second))) {
            wrong.putIfAbsent(f, otherNote(f, o.label, row.label, answer))
        }
        Lieux.forms.forEachIndexed { i, h ->
            wrong.putIfAbsent(h, "**$h** dit où on est : « ${Lieux.rows[i].label} » (leçon 8). Pour une chose ${row.label}, c'est **$answer**.")
        }
        return Frase(before, answer, "", french, rows.map { form(it.distance!!, second) }, accepted = accepted, wrong = wrong)
    }

    /** « Kini ang tubig. » = « Voici l'eau (près de moi). » */
    private fun voici(noun: Nom, row: Row): Frase {
        val le = noun.fr().le()
        val french = when (row.distance!!) {
            Distance.HERE -> "Voici $le (près de moi)."
            Distance.THERE -> "Voilà $le (près de toi)."
            Distance.FAR -> "Là-bas, c'est $le."
        }
        return Frase("", capital(form(row.distance, false)), "ang ${noun.ceb}", french, long.map { capital(it) })
    }

    /** « Unsa kana? » = « Qu'est-ce que c'est (près de toi) ? », « Kinsa kadto? » = « Qui est-ce, là-bas ? » */
    private fun ask(person: Boolean, row: Row, second: Boolean): Frase {
        val q = if (person) "Qui est-ce" else "Qu'est-ce que c'est"
        val french = when (row.distance!!) {
            Distance.HERE -> "$q (près de moi) ?"
            Distance.THERE -> "$q (près de toi) ?"
            Distance.FAR -> "$q, là-bas ?"
        }
        return Frase(if (person) "Kinsa" else "Unsa", form(row.distance, second), "", french, rows.map { form(it.distance!!, second) }, end = "?")
    }

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val noun = w.pick(things) { it.ceb } ?: return null
        val plural = !noun.mass && w.chance(30)
        return Pass(noun.ceb, plan.map { (row, second) -> row to frase(noun, plural, row, second) })
    }

    override fun build(w: Words, source: Item): Built? {
        val row = w.any(rows)
        val second = w.chance(35)
        val shape = w.random.nextInt(100)
        val noun = w.pick(things) { it.ceb } ?: return null
        val plural = !noun.mass && w.chance(30)
        val person = w.chance(40)
        fun make(r: Row) = when {
            shape < 15 -> ask(person, r, second)
            shape < 30 -> voici(noun, r)
            else -> frase(noun, plural, r, second)
        }
        val f = make(row)
        val extra = when {
            shape in 15 until 30 -> "Siya"
            second -> "ka"
            else -> "siya"
        }
        return built(source, f, f.choices.filter { it != f.answer } + extra, rows.filter { it != row }.map { make(it) })
    }
}

// ---------- Leçon 4 ----------

/** Leçon 4 : akong… avant la chose, nako… après (mon, ton, son…). */
object Possessifs : Theme(4) {
    override val title = "LA RONDE DES PERSONNES"
    override val subtitle = "2 tours de 7 questions : la personne change à chaque fois"
    override val question = "À QUI ?"
    override val rows = Person.values().map { Row(it.name, it.whose, person = it) }
    override fun round(second: Boolean) = if (second) "après le mot" else "avant le mot"
    override val columns = "AVANT" to "APRÈS"
    override val hint = "Écris le mot qui se met après la chose."

    override fun intro(second: Boolean) = if (second) {
        "Les 7 personnes encore, avec un autre mot. Cette fois, le mot se met **après** la chose : **balay nako** = ma maison. Écris-le !"
    } else {
        "Une seule phrase, et la personne change à chaque question. Touche le mot qui se met **avant** la chose : **akong balay** = ma maison."
    }

    fun form(p: Person, second: Boolean) = if (second) p.after else p.before

    override fun helpForm(row: Row) = row.person!!.before

    /** Les 3 phrases : « Kini ang akong balay. », « Nindot ang akong balay. », « Akong balay kini. » */
    enum class Shape { VOICI, ADJ, CEST }

    private fun note(p: Person) = when (p) {
        Person.WE_IN -> " (à toi et moi)"
        Person.WE_EX -> " (pas à toi)"
        else -> ""
    }

    private val owned = setOf(Sorte.PERSON, Sorte.OBJECT, Sorte.BUILDING, Sorte.ANIMAL)

    /** « Kini ang akong balay. » = « Voici ma maison. », « Kini ang balay nako. » (2e tour : le mot après la chose). */
    fun frase(shape: Shape, ceb: String, nom: Francais.Nom, adj: Adj?, feminine: Boolean, p: Person, second: Boolean): Frase {
        val head = when (shape) {
            Shape.VOICI -> "Kini ang"
            Shape.ADJ -> capital(adj!!.ceb(feminine)) + " ang"
            Shape.CEST -> ""
        }
        val tail = if (shape == Shape.CEST) "kini" else ""
        val before = if (second) (if (head.isEmpty()) capital(ceb) else "$head $ceb") else head
        val after = if (second) tail else listOf(ceb, tail).filter { it.isNotEmpty() }.joinToString(" ")
        val answer = at(before, form(p, second))
        val poss = Francais.possessive(p.fr, nom) + note(p)
        val french = when (shape) {
            Shape.VOICI -> "Voici $poss."
            Shape.ADJ -> Francais.sentence("$poss est ${adj!!.fr.agree(nom.feminine, false)}")
            Shape.CEST -> "C'est $poss."
        }
        val sentence = Frase.join(before, answer, after, ".")
        val accepted = linkedMapOf<String, String?>()
        val wrong = linkedMapOf<String, String>()
        if (second) {
            p.afterShort?.let { accepted[it] = "On dit aussi **${p.after}** : $sentence" }
            wrong[p.before] = "**${p.before}** se met avant la chose : ${p.before} $ceb. Après, c'est **${p.after}** : $ceb ${p.after}."
        } else {
            for (f in listOfNotNull(p.after, p.afterShort)) {
                wrong[f] = "**${p.after}** se met après la chose : $ceb ${p.after}. Avant, c'est **${p.before}** : ${p.before} $ceb."
            }
        }
        for (o in Person.values()) if (o != p) for (f in listOfNotNull(o.before, o.after, o.afterShort)) {
            wrong.putIfAbsent(f, otherNote(f, o.whose, p.whose, answer))
        }
        for (o in Person.values()) for (f in listOf(o.subject, o.short, o.long)) {
            wrong.putIfAbsent(f, "**$f**, c'est « ${o.who} » (la leçon 1). Ici, c'est **$answer**.")
        }
        return Frase(before, answer, after, french, Person.values().map { at(before, form(it, second)) }, accepted = accepted, wrong = wrong)
    }

    /** La phrase de la ronde avec un nom de la leçon (contenu.txt : « ronde-nom: balay = la maison ») : Kini ang akong balay. */
    fun rondeFrase(word: RondeWord, p: Person, second: Boolean): Frase {
        // « la maison », « l'école » : le nom et son genre.
        val full = word.fr.first()
        val nom = Francais.Nom(full.substringAfter("'").removePrefix("le ").removePrefix("la "), "", full.startsWith("la "))
        return frase(Shape.VOICI, word.ceb, nom, null, false, p, second)
    }

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val word = w.pick(w.content.stopByKey("c4")?.lesson?.rondeWords.orEmpty()) { it.ceb } ?: return null
        return Pass(word.ceb, plan.map { (row, second) -> row to rondeFrase(word, row.person!!, second) })
    }

    override fun build(w: Words, source: Item): Built? {
        val shape = w.any(Shape.values().toList())
        val noun = w.pick(Lexique.noms.filter { it.sorte in owned && it.ceb != "tawo" }) { it.ceb } ?: return null
        val fem = gender(w, noun)
        val nom = noun.fr(fem)
        val adj = if (shape == Shape.ADJ) w.pick(Lexique.adjectifs.filter { noun.sorte in it.sortes }) { it.ceb } ?: return null else null
        val p = w.any(Person.values().toList())
        val second = w.chance(50)
        val female = fem && noun.sorte == Sorte.PERSON
        val f = frase(shape, noun.ceb, nom, adj, female, p, second)
        val others = w.shuffled(Person.values().filter { it != p })
        return built(source, f, others.map { at(f.before, form(it, second)) }, others.map { frase(shape, noun.ceb, nom, adj, female, it, second) })
    }
}

// ---------- Leçon 5 ----------

/** Leçon 5 : ni (de, devant un prénom), sa (de, devant un nom) ; et aussi sa (à, dans) et ug (un, du). */
object De : Theme(5) {
    override val title = "LA RONDE DE « DE »"
    override val subtitle = "ni, sa, sa mga : à qui est la chose ?"
    override val question = "DE QUI ?"
    private val NI = Row("ni", "un prénom")
    private val SA = Row("sa", "un nom (le, la)")
    private val MGA = Row("sa-mga", "des noms (les)")
    override val rows = listOf(NI, SA, MGA)
    override val hint = "Écris le petit mot qui veut dire « de »."

    override fun intro(second: Boolean) = if (second) {
        "Les mêmes petits mots, avec une autre chose. Cette fois, écris-les !"
    } else {
        "Une seule phrase, et la personne à qui est la chose change à chaque question. " +
            "Devant un prénom, c'est **ni** ; devant un nom, **sa** ; devant plusieurs, **sa mga**."
    }

    private fun form(r: Row) = when (r) {
        NI -> "ni"
        SA -> "sa"
        else -> "sa mga"
    }

    override fun helpForm(row: Row) = form(row)
    override val helpTitle = "LES 3 PETITS MOTS"
    override fun helpLabel(row: Row) = where(row)

    private fun where(r: Row) = when (r) {
        NI -> "devant un prénom"
        SA -> "devant un nom"
        else -> "devant plusieurs noms"
    }

    /** À qui est la chose : « Lorie » (de Lorie), « maestra » (de la professeure), « bata » après sa mga (des enfants). */
    private class Owner(val ceb: String, val fr: String)

    private fun owner(row: Row, w: Words): Owner? {
        if (row == NI) {
            val n = w.pick(Lexique.prenoms) { it.name } ?: return null
            return Owner(n.name, Francais.elide("de", n.name))
        }
        val n = w.pick(Lexique.noms.filter { it.sorte == Sorte.PERSON && it.ceb != "tawo" }) { it.ceb } ?: return null
        return Owner(n.ceb, n.fr(gender(w, n)).of(row == MGA))
    }

    /** « Kini ang balay ni Lorie. », « Balay ni Lorie kini. », « Nindot ang balay ni Lorie. » */
    enum class Shape { VOICI, CEST, ADJ }

    private val things get() = Lexique.noms.filter { it.sorte == Sorte.OBJECT || it.sorte == Sorte.BUILDING || it.sorte == Sorte.ANIMAL }

    private fun frase(shape: Shape, thing: Nom, adj: Adj?, row: Row, o: Owner): Frase {
        val answer = form(row)
        val (before, after) = when (shape) {
            Shape.VOICI -> "Kini ang ${thing.ceb}" to o.ceb
            Shape.CEST -> capital(thing.ceb) to "${o.ceb} kini"
            Shape.ADJ -> "${capital(adj!!.ceb)} ang ${thing.ceb}" to o.ceb
        }
        val nom = thing.fr()
        val french = when (shape) {
            Shape.VOICI -> "Voici ${nom.le()} ${o.fr}."
            Shape.CEST -> "C'est ${nom.le()} ${o.fr}."
            Shape.ADJ -> Francais.sentence("${nom.le()} ${o.fr} est ${adj!!.fr.agree(nom.feminine, false)}")
        }
        val example = "$answer ${o.ceb}"
        val wrong = linkedMapOf<String, String>()
        for (r in rows) if (r != row) wrong[form(r)] = "**${form(r)}** se met ${where(r)}. Ici, c'est **$answer** : $example."
        wrong["si"] = "**si** se met devant un prénom qui fait l'action (leçon 2). Pour dire « de », c'est **$answer** : $example."
        wrong["ang"] = "**ang**, c'est « le, la » (leçon 2). Pour dire « de », c'est **$answer** : $example."
        wrong["ang mga"] = "**ang mga**, c'est « les » (leçon 2). Pour dire « de », c'est **$answer** : $example."
        wrong["sila si"] = "**sila si** se met devant des prénoms qui font l'action (leçon 2). Pour dire « de », c'est **$answer** : $example."
        return Frase(before, answer, after, french, rows.map { form(it) }, wrong = wrong)
    }

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val thing = w.pick(things) { it.ceb } ?: return null
        return Pass(thing.ceb, plan.map { (row, _) -> row to frase(Shape.VOICI, thing, null, row, owner(row, w) ?: return null) })
    }

    override fun build(w: Words, source: Item): Built? {
        val kind = w.random.nextInt(100)
        if (kind < 20) return place(w, source)
        if (kind < 40) return taken(w, source)
        val row = w.any(rows)
        val shape = w.any(Shape.values().toList())
        val thing = w.pick(things) { it.ceb } ?: return null
        val adj = if (shape == Shape.ADJ) w.pick(Lexique.adjectifs.filter { thing.sorte in it.sortes }) { it.ceb } ?: return null else null
        val f = frase(shape, thing, adj, row, owner(row, w) ?: return null)
        val traps = when (row) {
            NI -> listOf("sa", "si", "ang")
            SA -> listOf("ni", "si", "ang")
            else -> listOf("ni", "si", "sila si")
        }
        return built(source, f, traps, rows.filter { it != row }.mapNotNull { r -> owner(r, w)?.let { frase(shape, thing, adj, r, it) } })
    }

    /** « Moadto ko sa merkado. » = « Je vais aller au marché. » : sa devant un lieu. */
    private fun place(w: Words, source: Item): Built? {
        // Seulement les verbes qui se disent sans complément (pas « apporter à la maison »).
        val verb = w.pick(Lexique.verbes.filter { it.places.isNotEmpty() && it.person == null && (it.alone || it.things.isEmpty()) }) { it.root } ?: return null
        val lieu = w.pick(verb.places.map { Lexique.lieu(it) }) { it.ceb } ?: return null
        val who = Who.any(w) ?: return null
        val past = w.chance(40)
        val action = if (past) verb.fr.past(who.fr) else verb.fr.future(who.fr)
        val f = Frase(
            "${capital(if (past) verb.past else verb.future)} ${who.ceb}", "sa", lieu.ceb,
            Francais.sentence("$action ${lieu.fr}${who.note}"), listOf("sa", "ni", "si", "ang"),
        )
        return built(source, f, listOf("ni", "si", "ang"), emptyList())
    }

    /** « Nikaon siya ug pan. » = « Il a mangé du pain. » : ug devant ce qu'on prend, mange, achète… */
    private fun taken(w: Words, source: Item): Built? {
        val verb = w.pick(Lexique.verbes.filter { it.things.isNotEmpty() }) { it.root } ?: return null
        val noun = w.pick(verb.things.map { Lexique.nom(it) }) { it.ceb } ?: return null
        val who = Who.any(w) ?: return null
        val past = w.chance(50)
        val action = if (past) verb.fr.past(who.fr) else verb.fr.future(who.fr)
        val f = Frase(
            "${capital(if (past) verb.past else verb.future)} ${who.ceb}", "ug", noun.ceb,
            Francais.sentence("$action ${noun.some}${who.note}"), listOf("ug", "si", "ni", "ang"),
        )
        return built(source, f, listOf("si", "ni", "ang"), emptyList())
    }
}

// ---------- Leçons 6 et 7 ----------

/** Leçons 6 et 7 : mo- (ça va se faire), ni- ou mi- (c'est fait), la racine (un ordre) ; mag-, nag-, pag-. */
class Verbes private constructor(lesson: Int, private val mag: Boolean) : Theme(lesson) {
    override val title = "LA RONDE DU TEMPS"
    override val subtitle = if (mag) "mag-, nag-, pag- : demain, hier ou un ordre" else "mo-, ni-, la racine : demain, hier ou un ordre"
    override val question = "QUAND ?"
    private val future = Row("FUTURE", "demain", moment = Moment.FUTURE)
    private val past = Row("PAST", "hier", moment = Moment.PAST)
    private val order = Row("ORDER", "un ordre", moment = Moment.ORDER)
    override val rows = listOf(future, past, order)
    override val hint = "Écris le verbe en entier."

    override fun intro(second: Boolean) = when {
        second -> "Les mêmes moments, avec un autre verbe. Cette fois, écris le verbe en entier !"
        mag -> "Une seule phrase, et le moment change à chaque question : demain (**mag-**), hier (**nag-**) ou un ordre (**pag-**). Touche le bon verbe."
        else -> "Une seule phrase, et le moment change à chaque question : demain (**mo-**), hier (**ni-**) ou un ordre (la racine seule). Touche le bon verbe."
    }

    private val verbs get() = Lexique.verbes.filter { it.mag == mag && it.person != Objet.AVEC }

    override fun helpForm(row: Row) = when (row) {
        future -> if (mag) "mag-" else "mo-"
        past -> if (mag) "nag-" else "ni- · mi-"
        else -> if (mag) "pag-" else "la racine"
    }

    override val helpTitle = "LES 3 MOMENTS"

    override fun helpLabel(row: Row) = when (row) {
        future -> "demain (ça va se faire)"
        past -> "hier (c'est fait)"
        else -> "un ordre"
    }

    /** Les mots du moment : ugma (demain), unya (plus tard), gahapon (hier), ganiha (tout à l'heure). */
    private val later = listOf("ugma" to "demain", "unya" to "plus tard")
    private val earlier = listOf("gahapon" to "hier", "ganiha" to "tout à l'heure")

    private fun rondeTime(row: Row) = when (row) {
        future -> later[0]
        past -> earlier[0]
        else -> null
    }

    /**
     * « Mokaon ko ug pan ugma. » = « Je vais manger du pain demain. », « Nikaon ko ug pan gahapon. », « Kaon ug pan! ».
     * [time] : le mot du moment, ou null. [now] : nag- au présent (« Nagtrabaho ko. » = « Je travaille. »).
     */
    fun frase(a: Act, row: Row, time: Pair<String, String>?, now: Boolean = false): Frase? {
        val v = a.verb
        val forms = listOf(v.future, v.past, v.command).map { capital(it) }
        val t = time?.let { " ${it.first}" } ?: ""
        val tf = time?.let { " ${it.second}" } ?: ""
        val answer: String
        val after: String
        val french: String
        var end = "."
        when (row) {
            future -> {
                answer = forms[0]
                after = "${a.who.ceb}${a.ceb}$t"
                french = Francais.sentence("${v.fr.future(a.who.fr)}${a.fr}$tf${a.who.note}")
            }
            past -> {
                answer = forms[1]
                after = "${a.who.ceb}${a.ceb}$t"
                french = Francais.sentence("${if (now) v.fr.now(a.who.fr) else v.fr.past(a.who.fr)}${a.fr}$tf${a.who.note}")
            }
            else -> {
                if (!v.order) return null
                answer = forms[2]
                after = a.ceb.trim()
                french = Francais.sentence("${v.fr.order()}${a.fr}", '!')
                end = "!"
            }
        }
        val alt = v.pastAlt?.let { capital(it) }
        val accepted = if (row == past && alt != null) mapOf<String, String?>(alt to "**$alt** et **$answer**, c'est pareil : c'est fait.") else emptyMap()
        val wrong = linkedMapOf<String, String>()
        val mine = if (row == order) "un ordre" else "« ${row.label} »"
        rows.forEachIndexed { i, o ->
            if (o == row) return@forEachIndexed
            wrong[forms[i]] = if (o == order) "**${forms[i]}** seul, c'est un ordre. Pour $mine, c'est **$answer**."
            else "**${forms[i]}**, c'est ${o.moment!!.meaning}. Pour $mine, c'est **$answer**."
        }
        if (row != past && alt != null) wrong[alt] = "**$alt**, c'est « c'est fait ». Pour $mine, c'est **$answer**."
        // L'autre famille de la leçon d'à côté (magkaon, motrabaho…).
        val family = if (mag) "**mag-**, **nag-** et **pag-**" else "**mo-**, **ni-** et la racine seule"
        for (pre in if (mag) listOf("mo", "ni", "mi") else listOf("mag", "nag", "pag")) {
            wrong.putIfAbsent(capital(pre + v.root), "Dans cette leçon, **${v.root}** se dit avec $family. Pour $mine, c'est **$answer**.")
        }
        return Frase("", answer, after, french, forms, end = end, accepted = accepted, wrong = wrong)
    }

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val verb = w.pick(verbs.filter { it.order }) { it.root } ?: return null
        val act = Act.make(w, verb, Who.any(w) ?: return null)
        return Pass(verb.root, plan.map { (row, _) -> row to (frase(act, row, rondeTime(row)) ?: return null) })
    }

    override fun build(w: Words, source: Item): Built? {
        val verb = w.pick(verbs) { it.root } ?: return null
        val row = when {
            verb.order && w.chance(20) -> order
            w.chance(50) -> future
            else -> past
        }
        val act = Act.make(w, verb, Who.any(w) ?: return null)
        val time = if (!w.chance(70)) null else when (row) {
            future -> w.any(later)
            past -> w.any(earlier)
            else -> null
        }
        // Sans mot du moment, nag- se dit aussi au présent : « Nagtrabaho ko. » = « Je travaille. »
        val now = mag && row == past && time == null && w.chance(50)
        val f = frase(act, row, time, now) ?: return null
        val other = w.pick(verbs.filter { it !== verb }) { it.root } ?: return null
        val fourth = when {
            !mag && row != past -> capital(verb.pastAlt!!)
            row == future -> capital(other.future)
            row == past -> capital(other.past)
            else -> capital(other.command)
        }
        val others = rows.filter { it != row }.mapNotNull { r -> frase(act, r, if (time == null) null else rondeTime(r), now) }
        return built(source, f, f.choices.filter { it != f.answer } + fourth, others)
    }

    companion object {
        val MO = Verbes(6, false)
        val MAG = Verbes(7, true)
    }
}

// ---------- Leçon 8 ----------

/** Leçon 8 : dinhi (ici), dinha (là, près de toi), didto (là-bas). */
object Lieux : Theme(8) {
    override val title = "LA RONDE DES DISTANCES"
    override val subtitle = "dinhi, dinha, didto : l'endroit change à chaque fois"
    override val question = "OÙ ?"
    override val rows = listOf(
        Row("HERE", "ici", distance = Distance.HERE),
        Row("THERE", "là, près de toi", distance = Distance.THERE),
        Row("FAR", "là-bas", distance = Distance.FAR),
    )
    override val hint = "Écris dinhi, dinha ou didto."

    override fun intro(second: Boolean) = if (second) {
        "Les mêmes endroits, avec une autre phrase. Cette fois, écris le mot !"
    } else {
        "Une seule phrase, et l'endroit change à chaque question : ici (**dinhi**), là, près de toi (**dinha**), " +
            "ou là-bas (**didto**). Regarde le dessin !"
    }

    val forms = listOf("dinhi", "dinha", "didto")
    private val others = listOf(listOf("diri"), listOf("dira", "diha"), emptyList())

    override fun helpForm(row: Row) = forms[row.distance!!.ordinal]
    override val helpTitle = "LES 3 DISTANCES"

    /** Les verbes qui se disent bien avec un endroit : manger ici, s'asseoir là… */
    private val roots = setOf(
        "kaon", "inom", "lingkod", "tindog", "higda", "palit", "basa", "tan-aw", "kanta", "dagan",
        "trabaho", "tuon", "hulat", "luto", "dula", "kape", "laba", "ampo", "dala",
    )
    private val verbs get() = Lexique.verbes.filter { it.root in roots }

    private fun act(w: Words, verb: Verbe): Act? {
        val who = Who.any(w) ?: return null
        if (verb.things.isNotEmpty() && (!verb.alone || w.chance(50))) {
            val thing = w.pick(verb.things.map { Lexique.nom(it) }) { it.ceb }!!
            return Act(verb, who, " ug ${thing.ceb}", " ${thing.some}")
        }
        return Act(verb, who, "", "")
    }

    /** « Mokaon mi didto sa kusina. » = « Nous allons manger là-bas, dans la cuisine (pas toi). » */
    private fun frase(a: Act, moment: Moment, place: Lieu?, row: Row): Frase {
        val d = row.distance!!.ordinal
        val v = a.verb
        val before = when (moment) {
            Moment.FUTURE -> "${capital(v.future)} ${a.who.ceb}${a.ceb}"
            Moment.PAST -> "${capital(v.past)} ${a.who.ceb}${a.ceb}"
            Moment.ORDER -> capital(v.command) + a.ceb
        }
        val answer = forms[d]
        val note = if (moment == Moment.ORDER) "" else a.who.note
        val where = whereFr(row.distance, place == null && note.isEmpty()) + (place?.let { ", ${it.fr}" } ?: "")
        val fr = when (moment) {
            Moment.FUTURE -> Francais.sentence("${v.fr.future(a.who.fr)}${a.fr} $where$note")
            Moment.PAST -> Francais.sentence("${v.fr.past(a.who.fr)}${a.fr} $where$note")
            Moment.ORDER -> Francais.sentence("${v.fr.order()}${a.fr} $where")
        }
        val accepted = others[d].associateWith<String, String?> { "**$it** est juste aussi : on dit **$answer** ou **$it**." }
        val wrong = linkedMapOf<String, String>()
        for (o in rows) if (o != row) for (f in listOf(forms[o.distance!!.ordinal]) + others[o.distance.ordinal]) {
            wrong[f] = otherNote(f, o.label, row.label, answer)
        }
        Demonstratifs.long.forEachIndexed { i, x ->
            wrong[x] = "**$x**, c'est « ${Demonstratifs.meanings[i]} » : une chose (leçon 3). Pour dire où, c'est **$answer**."
        }
        return Frase(before, answer, place?.let { "sa ${it.ceb}" } ?: "", fr, forms, accepted = accepted, wrong = wrong)
    }

    private fun place(w: Words, verb: Verbe): Lieu? =
        if (verb.places.isNotEmpty() && w.chance(40)) w.pick(verb.places.map { Lexique.lieu(it) }) { it.ceb } else null

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val verb = w.pick(verbs) { it.root } ?: return null
        val moment = if (verb.order && w.chance(35)) Moment.ORDER else Moment.FUTURE
        val a = act(w, verb) ?: return null
        val place = place(w, verb)
        return Pass(verb.root, plan.map { (row, _) -> row to frase(a, moment, place, row) })
    }

    override fun build(w: Words, source: Item): Built? {
        val verb = w.pick(verbs) { it.root } ?: return null
        val moment = when {
            verb.order && w.chance(25) -> Moment.ORDER
            w.chance(60) -> Moment.FUTURE
            else -> Moment.PAST
        }
        val a = act(w, verb) ?: return null
        val place = place(w, verb)
        val row = w.any(rows)
        val f = frase(a, moment, place, row)
        val traps = forms.filter { it != f.answer } + Demonstratifs.long[row.distance!!.ordinal]
        return built(source, f, traps, rows.filter { it != row }.map { frase(a, moment, place, it) })
    }
}

// ---------- Leçon 9 ----------

/** Leçon 9 : kanako, kanimo… (me suivre, avec toi, pour eux…), et para kang, para sa, kang. */
object PourQui : Theme(9) {
    override val title = "LA RONDE DES PERSONNES"
    override val subtitle = "kanako, kanimo… : la personne change à chaque fois"
    override val question = "QUI ?"
    override val rows = Person.values().map { Row(it.name, it.stressed, person = it) }
    override val hint = "Écris kanako, kanimo, kaniya…"

    override fun intro(second: Boolean) = if (second) {
        "Les 7 personnes encore, avec une autre phrase. Cette fois, écris le mot : **kanako**, **kanimo**…"
    } else {
        "Une seule phrase, et la personne change à chaque question : moi, toi, eux… " +
            "Touche le bon mot : **kanako** (moi), **kanimo** (toi), **kaniya** (lui, elle)…"
    }

    override fun helpForm(row: Row) = row.person!!.oblique

    /** Ce qu'on fait pour quelqu'un : le suivre, venir avec lui, faire quelque chose pour lui, ou lui donner quelque chose. */
    private enum class Frame { DIRECT, AVEC, POUR, DONNER }

    private class Scene(val frame: Frame, val verb: Verbe, val who: Who, val thing: Nom?)

    private val voir = Francais.Verbe("voir", "vu", "vois", present = listOf("vois", "vois", "voit", "voyons", "voyez", "voient"))

    private fun scene(w: Words, frame: Frame, who: Who): Scene? {
        val verb = w.pick(Lexique.verbes.filter { v ->
            when (frame) {
                Frame.DIRECT -> v.person == Objet.DIRECT
                Frame.AVEC -> v.person == Objet.AVEC
                Frame.POUR -> v.beneficiary && v.root != "hatag"
                Frame.DONNER -> v.root == "hatag"
            }
        }) { it.root } ?: return null
        val thing = if ((frame == Frame.POUR || frame == Frame.DONNER) && verb.things.isNotEmpty()) {
            w.pick(verb.things.map { Lexique.nom(it) }) { it.ceb }
        } else null
        return Scene(frame, verb, who, thing)
    }

    /** « Mosunod si Lorie kanako. » = « Lorie va me suivre. », « Mopalit siya ug pan para kanimo. » = « Il va acheter du pain pour toi. » */
    private fun frase(s: Scene, p: Person, feminine: Boolean, past: Boolean = false): Frase {
        val v = s.verb
        val fem = feminine && p.gendered
        val answer = p.oblique
        val thing = s.thing?.let { " ug ${it.ceb}" } ?: ""
        val thingFr = s.thing?.let { " ${it.some}" } ?: ""
        val before = "${capital(if (past) v.past else v.future)} ${s.who.ceb}$thing" + if (s.frame == Frame.POUR) " para" else ""
        val me = Francais.stressedPronoun(p.fr, fem)
        val fr = when (s.frame) {
            Frame.DIRECT -> v.fr.future(s.who.fr, Francais.objectPronoun(p.fr, fem))
            Frame.AVEC -> "${v.fr.future(s.who.fr)} avec $me"
            Frame.POUR -> "${if (past) v.fr.past(s.who.fr) else v.fr.future(s.who.fr)}$thingFr pour $me"
            Frame.DONNER -> v.fr.future(s.who.fr, Francais.indirectPronoun(p.fr)) + thingFr
        }
        // « Nous allons te suivre » : avec toi ou vous en face, « (pas toi) » ne sert à rien.
        val note = (if (p.you) "" else s.who.note) + p.weNote
        val accepted = linkedMapOf<String, String?>()
        for (f in listOf(p.after, p.saForm)) accepted[f] = "**$f** est juste aussi : on dit **$answer** ou **$f**."
        val wrong = linkedMapOf<String, String>()
        for (o in Person.values()) if (o != p) for (f in listOf(o.oblique, o.after, o.saForm)) wrong.putIfAbsent(f, otherNote(f, o.stressed, p.stressed, answer))
        for (o in Person.values()) for (f in listOf(o.subject, o.short, o.long)) {
            wrong.putIfAbsent(f, "**$f**, c'est « ${o.who} » : celui qui fait l'action (leçon 1). Pour « ${p.stressed} », c'est **$answer**.")
        }
        for (o in Person.values()) wrong.putIfAbsent(o.before, "**${o.before}**, c'est « ${o.whose} », devant une chose (leçon 4). Ici, c'est **$answer**.")
        return Frase(before, answer, "", Francais.sentence(fr + note), Person.values().map { it.oblique }, accepted = accepted, wrong = wrong)
    }

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val s = scene(w, w.any(Frame.values().toList()), Who.third(w) ?: return null) ?: return null
        return Pass(s.verb.root, plan.map { (row, _) -> row to frase(s, row.person!!, w.chance(50)) })
    }

    /** Je et nous, tu et vous… : deux personnes qui se recouvrent (« Mosunod ko kanato » ne se dit pas). */
    private fun overlap(a: Person, b: Person) =
        (a.me && b.me) || (a.you && b.you) || (a.others > 0 && b.others > 0)

    override fun build(w: Words, source: Item): Built? {
        if (w.chance(30)) return named(w, source)
        val frame = w.any(Frame.values().toList())
        val p = w.any(Person.values().toList())
        val subjects = Person.values().filter { !overlap(it, p) }
        val who = (if (subjects.isNotEmpty() && w.chance(40)) Who.pronoun(w.any(subjects), w.chance(50)) else Who.third(w)) ?: return null
        val s = scene(w, frame, who) ?: return null
        val past = frame == Frame.POUR && w.chance(40)
        val fem = w.chance(50)
        val f = frase(s, p, fem, past)
        val others = w.shuffled(Person.values().filter { it != p })
        return built(source, f, others.map { it.oblique }, others.map { frase(s, it, fem, past) })
    }

    /** « … para kang Lisa. », « … para sa mga bata. », « Moadto ko kang James. » */
    private fun named(w: Words, source: Item): Built? {
        val who = Who.any(w) ?: return null
        if (w.chance(25)) {
            val n = w.pick(Lexique.prenoms) { it.name } ?: return null
            val f = Frase(
                "${capital(Lexique.verbe("adto")!!.future)} ${who.ceb}", "kang", n.name,
                Francais.sentence("${voir.future(who.fr)} ${n.name}${who.note}"), listOf("kang", "sa", "ni", "si"),
            )
            return built(source, f, listOf("sa", "ni", "si"), emptyList())
        }
        val verb = w.pick(Lexique.verbes.filter { it.beneficiary && it.root != "hatag" }) { it.root } ?: return null
        val thing = if (verb.things.isEmpty()) null else w.pick(verb.things.map { Lexique.nom(it) }) { it.ceb }
        val past = w.chance(40)
        val head = "${capital(if (past) verb.past else verb.future)} ${who.ceb}" + (thing?.let { " ug ${it.ceb}" } ?: "")
        val action = (if (past) verb.fr.past(who.fr) else verb.fr.future(who.fr)) + (thing?.let { " ${it.some}" } ?: "")
        if (w.chance(50)) {
            val n = w.pick(Lexique.prenoms) { it.name } ?: return null
            val f = Frase(head, "para kang", n.name, Francais.sentence("$action pour ${n.name}${who.note}"), listOf("para kang", "para sa", "sa", "ni"))
            return built(source, f, listOf("para sa", "sa", "ni"), emptyList())
        }
        val n = w.pick(Lexique.noms.filter { it.sorte == Sorte.PERSON }) { it.ceb } ?: return null
        val plural = w.chance(40)
        val nom = n.fr(gender(w, n))
        val f = Frase(
            head, "para sa", (if (plural) "mga " else "") + n.ceb,
            Francais.sentence("$action pour ${nom.le(plural)}${who.note}"), listOf("para sa", "para kang", "ni", "si"),
        )
        return built(source, f, listOf("para kang", "ni", "si"), emptyList())
    }
}

// ---------- Leçon 10 ----------

/** Leçon 10 : nga relie un adjectif et un nom ; après une voyelle il devient -ng, après un n, -g. */
object Liens : Theme(10) {
    override val title = "LA RONDE DE NGA"
    override val subtitle = "nga, -ng, -g : l'adjectif change à chaque fois"
    override val question = "LE MOT FINIT PAR"
    val NGA = Row("nga", "une consonne")
    val NG = Row("ng", "une voyelle")
    val G = Row("g", "un n")
    override val rows = listOf(NGA, NG, G)
    override val hint = "Écris l'adjectif avec son lien : nga, -ng ou -g."

    override fun intro(second: Boolean) = if (second) {
        "Cette fois, écris l'adjectif avec son lien : **taas nga**, **dakong**, **buotang**…"
    } else {
        "Une seule phrase, et l'adjectif change à chaque question. Après une consonne : **nga**. " +
            "Après une voyelle : **-ng**. Après un n : **-g**."
    }

    /** La ligne d'un adjectif, selon sa dernière lettre (null pour y et w : pas dans cette leçon). */
    fun rowOf(adj: String): Row? = when (adj.lowercase().last()) {
        'a', 'e', 'i', 'o', 'u' -> NG
        'n' -> G
        'y', 'w' -> null
        else -> NGA
    }

    /** taas nga, dakong, buotang */
    fun linked(adj: String, row: Row) = when (row) {
        NGA -> "$adj nga"
        NG -> adj + "ng"
        else -> adj + "g"
    }

    private fun link(row: Row) = when (row) {
        NGA -> "nga"
        NG -> "-ng"
        else -> "-g"
    }

    override fun helpForm(row: Row) = link(row)
    override val helpTitle = "LES 3 LIENS"
    override fun helpLabel(row: Row) = "après ${row.label}"

    /** Ce qu'on relie : un nom de chose (« balay kini ») ou de personne, avec son prénom (« babaye si Ruth »). */
    private class Scene(val noun: Nom, val feminine: Boolean, val name: Prenom?)

    private fun scene(w: Words): Scene? {
        val noun = w.pick(Lexique.noms.filter { it.sorte in setOf(Sorte.PERSON, Sorte.OBJECT, Sorte.BUILDING, Sorte.ANIMAL) }) { it.ceb } ?: return null
        if (noun.sorte != Sorte.PERSON) return Scene(noun, false, null)
        val fem = gender(w, noun)
        val name = w.pick(Lexique.prenoms.filter { it.feminine == fem }) { it.name } ?: return null
        return Scene(noun, fem, name)
    }

    private fun word(s: Scene, adj: Adj) = adj.ceb(s.feminine && s.noun.sorte == Sorte.PERSON)

    private fun adjective(w: Words, s: Scene, row: Row?): Adj? =
        w.pick(Lexique.adjectifs.filter { it.linked && it.fits(s.noun) && rowOf(word(s, it)) != null && (row == null || rowOf(word(s, it)) == row) }) { it.ceb }

    /**
     * « Taas nga babaye si Ruth. » = « Ruth est une grande femme. », « Dakong balay kini. » = « C'est une grande maison. »
     * Au 1er tour on touche le lien (nga, -ng, -g) ; au 2e on écrit l'adjectif relié (dakong).
     */
    private fun frase(s: Scene, adj: Adj, second: Boolean): Frase? {
        val word = word(s, adj)
        val row = rowOf(word) ?: return null
        val nom = s.noun.fr(s.feminine)
        val tail = if (s.name != null) "${s.noun.ceb} si ${s.name.name}" else "${s.noun.ceb} kini"
        val french = if (s.name != null) "${s.name.name} est ${adj.fr.un(nom)}." else "C'est ${adj.fr.un(nom)}."
        val good = linked(word, row)
        val both = "Juste aussi : après un n, on dit **${word}g** ou **$word nga**."
        if (!second) {
            val answer = link(row)
            val sentence = Frase.join(capital(word), answer, tail, ".")
            val accepted = if (row == G) mapOf<String, String?>("nga" to both) else emptyMap()
            val wrong = when (row) {
                NGA -> mapOf(
                    "-ng" to "Après une consonne, on garde **nga** : $sentence",
                    "-g" to "**-g**, c'est seulement après un n. Après une consonne, on garde **nga** : $sentence",
                )
                NG -> mapOf(
                    "nga" to "Après une voyelle, **nga** devient **-ng** : $sentence",
                    "-g" to "**-g**, c'est seulement après un n. Après une voyelle, c'est **-ng** : $sentence",
                )
                else -> mapOf("-ng" to "Après un n, on ajoute juste **-g** : $sentence")
            }
            return Frase(capital(word), answer, tail, french, rows.map { link(it) }, accepted = accepted, wrong = wrong)
        }
        val accepted = if (row == G) mapOf<String, String?>("$word nga" to both) else emptyMap()
        val wrong = linkedMapOf(word to "Il manque le petit mot qui relie : **$good**.")
        when (row) {
            NGA -> {
                wrong["${word}ng"] = "Après une consonne, on garde **nga** : **$good**."
                wrong["${word}g"] = "**-g**, c'est seulement après un n. Ici : **$good**."
            }
            NG -> {
                wrong["$word nga"] = "Après une voyelle, **nga** devient **-ng** : **$good**."
                wrong["${word}g"] = "**-g**, c'est seulement après un n. Ici : **$good**."
            }
            else -> wrong["${word}ng"] = "Après un n, on ajoute juste **-g** : **$good**."
        }
        return Frase(
            "", capital(good), tail, french, rows.map { capital(linked(word, it)) },
            accepted = accepted, wrong = wrong, band = Row(row.key, "$word + nga"), bandQuestion = "RELIE",
        )
    }

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val s = scene(w) ?: return null
        return Pass(s.noun.ceb, plan.map { (row, second) -> row to (adjective(w, s, row)?.let { frase(s, it, second) } ?: return null) })
    }

    override fun build(w: Words, source: Item): Built? {
        if (w.chance(25)) return pointing(w, source)
        val s = scene(w) ?: return null
        val adj = adjective(w, s, null) ?: return null
        val word = word(s, adj)
        if (rowOf(word) == NGA) return built(source, frase(s, adj, false) ?: return null, listOf("ang", "sa", "si"), emptyList())
        // dakong, buotang : pas de trou (le lien se colle à l'adjectif), mais des pièges (dako nga, dako).
        val f = frase(s, adj, true) ?: return null
        val traps = if (rowOf(word) == NG) listOf("${capital(word)} nga", capital(word)) else listOf(capital(word))
        return built(source, f, traps, emptyList(), gap = false)
    }

    private val pointers = listOf("kining", "kanang", "kadtong")
    private val places = listOf("(près de moi)", "(près de toi)", "(là-bas)")

    /** « Hilumon kanang bata. » = « Cet enfant (près de toi) est calme. » */
    private fun pointing(w: Words, source: Item): Built? {
        val noun = w.pick(Lexique.noms.filter { it.sorte != Sorte.OTHER }) { it.ceb } ?: return null
        val adj = w.pick(Lexique.adjectifs.filter { noun.sorte in it.sortes }) { it.ceb } ?: return null
        val fem = gender(w, noun)
        val nom = noun.fr(fem)
        val before = capital(adj.ceb(fem && noun.sorte == Sorte.PERSON))
        fun make(i: Int) = Frase(
            before, pointers[i], noun.ceb,
            Francais.sentence("${nom.ce()} ${places[i]} est ${adj.fr.agree(nom.feminine, false)}"), pointers,
        )
        val d = w.random.nextInt(3)
        val f = make(d)
        return built(source, f, pointers.filter { it != f.answer } + Demonstratifs.long[d], (0..2).filter { it != d }.map { make(it) })
    }
}

// ---------- Leçon 11 ----------

/** Leçon 11 : une longue phrase (le verbe, qui, quoi, où, pour qui) ; une seule partie change à chaque question. */
object Revision : Theme(11) {
    override val title = "LA GRANDE RONDE"
    override val subtitle = "une longue phrase : une seule partie change à chaque fois"
    override val question = "QUOI ?"
    private val VERB = Row("VERB", "le moment")
    private val WHO = Row("WHO", "qui")
    private val WHERE = Row("WHERE", "où")
    private val FOR = Row("FOR", "pour qui")
    override val rows = listOf(VERB, WHO, WHERE, FOR)
    override val hint = "Écris le mot qui a changé."

    override fun helpForm(row: Row) = when (row) {
        VERB -> "mo- · ni- · mag- · nag-"
        WHO -> "si · ang · ang mga"
        WHERE -> "dinhi · dinha · didto"
        else -> "para kang · para sa"
    }

    override val helpTitle = "CE QUI PEUT CHANGER"

    override fun intro(second: Boolean) = if (second) {
        "Une autre longue phrase. Cette fois, écris le mot qui change !"
    } else {
        "Une longue phrase, et une seule partie change à chaque question : le moment, qui, où ou pour qui. " +
            "Lis bien le bandeau, puis touche le bon mot."
    }

    /** Qui (ou pour qui) : un prénom, une personne, ou plusieurs. */
    private enum class Kind(val label: String, val where: String) {
        NAME("un prénom", "devant un prénom"),
        ONE("un nom (le, la)", "devant un nom (le, la)"),
        MANY("des noms (les)", "devant plusieurs noms (les)"),
    }

    private class Someone(val kind: Kind, val name: Prenom?, val noun: Nom?, val feminine: Boolean) {
        /** « Lorie », ou « bata » (après ang, ang mga, para sa…). */
        val word get() = name?.name ?: noun!!.ceb
        private val nom get() = noun!!.fr(feminine)
        val fr get() = name?.name ?: nom.le(kind == Kind.MANY)
        val sujet: Francais.Sujet
            get() = name?.let { Francais.Sujet(it.name, 2, it.feminine) }
                ?: Francais.Sujet(nom.le(kind == Kind.MANY), if (kind == Kind.MANY) 5 else 2, nom.feminine)
    }

    private fun someone(w: Words, kind: Kind): Someone? {
        if (kind == Kind.NAME) return w.pick(Lexique.prenoms) { it.name }?.let { Someone(kind, it, null, it.feminine) }
        val n = w.pick(Lexique.noms.filter { it.sorte == Sorte.PERSON }) { it.ceb } ?: return null
        return Someone(kind, null, n, gender(w, n))
    }

    /** La longue phrase : le verbe, ce qu'on prend et le lieu restent ; le reste change. */
    private class Longue(
        val verb: Verbe,
        val thing: Nom?,
        val place: Lieu,
        var moment: Moment,
        var who: Someone,
        var where: Distance?,
        var forWhom: Someone?,
    ) {
        fun copy() = Longue(verb, thing, place, moment, who, where, forWhom)
    }

    private val whoForm = mapOf(Kind.NAME to "si", Kind.ONE to "ang", Kind.MANY to "ang mga")
    private val forForm = mapOf(Kind.NAME to "para kang", Kind.ONE to "para sa", Kind.MANY to "para sa mga")
    private val verbs get() = Lexique.verbes.filter { it.beneficiary && it.places.isNotEmpty() && it.root != "hatag" }

    private fun longue(w: Words): Longue? {
        val verb = w.pick(verbs) { it.root } ?: return null
        val thing = if (verb.things.isEmpty()) null else w.pick(verb.things.map { Lexique.nom(it) }) { it.ceb }
        val place = w.pick(verb.places.map { Lexique.lieu(it) }) { it.ceb } ?: return null
        val who = someone(w, w.any(Kind.values().toList())) ?: return null
        val forWhom = someone(w, w.any(Kind.values().toList())) ?: return null
        return Longue(verb, thing, place, w.any(listOf(Moment.FUTURE, Moment.PAST)), who, w.any(Distance.values().toList()), forWhom)
    }

    /** Une seule partie change (le moment, qui, où ou pour qui). Faux si ça ne se fait pas. */
    private fun change(w: Words, l: Longue, part: Row): Boolean {
        when (part) {
            VERB -> l.moment = if (l.moment == Moment.FUTURE) Moment.PAST else Moment.FUTURE
            WHO -> l.who = someone(w, w.any(Kind.values().filter { it != l.who.kind })) ?: return false
            WHERE -> l.where = w.any(Distance.values().filter { it != l.where })
            else -> l.forWhom = someone(w, w.any(Kind.values().filter { it != l.forWhom?.kind })) ?: return false
        }
        return true
    }

    /**
     * « Mopalit si Lorie ug pan didto sa merkado para kang Paul. » = « Lorie va acheter du pain là-bas, au marché, pour Paul. »
     * Le trou est sur la partie [part].
     */
    private fun frase(l: Longue, part: Row): Frase {
        val v = l.verb
        val forms = listOf(v.future, v.past, v.command).map { capital(it) }
        val verb = if (l.moment == Moment.FUTURE) forms[0] else forms[1]
        val whoWord = whoForm.getValue(l.who.kind)
        val thing = l.thing?.let { " ug ${it.ceb}" } ?: ""
        val where = l.where?.let { " ${Lieux.forms[it.ordinal]}" } ?: ""
        val place = "sa ${l.place.ceb}"
        val forWord = l.forWhom?.let { forForm.getValue(it.kind) }
        val forWhom = l.forWhom?.let { " $forWord ${it.word}" } ?: ""
        val action = if (l.moment == Moment.FUTURE) v.fr.future(l.who.sujet) else v.fr.past(l.who.sujet)
        val placeFr = if (l.where != null) " ${whereFr(l.where!!, false)}, ${l.place.fr}" else " ${l.place.fr}"
        val forFr = l.forWhom?.let { (if (l.where != null) ", pour " else " pour ") + it.fr } ?: ""
        val french = Francais.sentence("$action${l.thing?.let { " ${it.some}" } ?: ""}$placeFr$forFr")
        val wrong = linkedMapOf<String, String>()
        return when (part) {
            VERB -> {
                val label = if (l.moment == Moment.FUTURE) "ça va se faire" else "c'est fait"
                val other = if (l.moment == Moment.FUTURE) forms[1] else forms[0]
                wrong[other] = otherNote(other, if (l.moment == Moment.FUTURE) "c'est fait" else "ça va se faire", label, verb)
                wrong[forms[2]] = "**${forms[2]}** seul, c'est un ordre. Ici, quelqu'un le fait : **$verb**."
                Frase(
                    "", verb, "$whoWord ${l.who.word}$thing$where $place$forWhom", french, forms, wrong = wrong,
                    band = Row(VERB.key, label, moment = l.moment), bandQuestion = "QUAND ?",
                )
            }
            WHO -> {
                for (k in Kind.values()) if (k != l.who.kind) {
                    wrong[whoForm.getValue(k)] = "**${whoForm.getValue(k)}** se met ${k.where}. Ici, c'est **$whoWord** : $whoWord ${l.who.word}."
                }
                Frase(
                    verb, whoWord, "${l.who.word}$thing$where $place$forWhom", french, Kind.values().map { whoForm.getValue(it) }, wrong = wrong,
                    band = Row(WHO.key, l.who.kind.label), bandQuestion = "QUI ?",
                )
            }
            WHERE -> {
                val d = l.where!!
                val answer = Lieux.forms[d.ordinal]
                val labels = Lieux.rows.map { it.label }
                Lieux.forms.forEachIndexed { i, f -> if (i != d.ordinal) wrong[f] = otherNote(f, labels[i], labels[d.ordinal], answer) }
                val accepted = when (d) {
                    Distance.HERE -> listOf("diri")
                    Distance.THERE -> listOf("dira", "diha")
                    Distance.FAR -> emptyList()
                }.associateWith<String, String?> { "**$it** est juste aussi : on dit **$answer** ou **$it**." }
                Frase(
                    "$verb $whoWord ${l.who.word}$thing", answer, "$place$forWhom", french, Lieux.forms,
                    accepted = accepted, wrong = wrong, band = Row(WHERE.key, labels[d.ordinal], distance = d), bandQuestion = "OÙ ?",
                )
            }
            else -> {
                val f = l.forWhom!!
                for (k in Kind.values()) if (k != f.kind) {
                    wrong[forForm.getValue(k)] = "**${forForm.getValue(k)}** se met ${k.where}. Ici, c'est **$forWord** : $forWord ${f.word}."
                }
                Frase(
                    "$verb $whoWord ${l.who.word}$thing$where $place", forWord!!, f.word, french, Kind.values().map { forForm.getValue(it) },
                    wrong = wrong, band = Row(FOR.key, f.kind.label), bandQuestion = "POUR QUI ?",
                )
            }
        }
    }

    override fun pass(w: Words, plan: List<Pair<Row, Boolean>>): Pass? {
        val l = longue(w) ?: return null
        return Pass(l.verb.root, plan.map { (row, _) -> if (!change(w, l, row)) return null else row to frase(l, row) })
    }

    override fun build(w: Words, source: Item): Built? {
        val l = longue(w) ?: return null
        val part = w.any(rows)
        // Pour raccourcir : parfois sans « où » (didto) ou sans « pour qui ».
        if (part != WHERE && w.chance(30)) l.where = null
        if (part != FOR && w.chance(30)) l.forWhom = null
        val f = frase(l, part)
        val traps = when (part) {
            VERB -> {
                val other = w.pick(Lexique.verbes.filter { it !== l.verb }) { it.root } ?: return null
                f.choices.filter { it != f.answer } + capital(if (l.moment == Moment.FUTURE) other.future else other.past)
            }
            WHO -> listOf("si", "ang", "ang mga", "sila si").filter { it != f.answer }
            WHERE -> Lieux.forms.filter { it != f.answer } + "kini"
            else -> when (f.answer) {
                "para kang" -> listOf("para sa", "ni", "si")
                "para sa" -> listOf("para kang", "ni", "si")
                else -> listOf("para kang", "para sa", "ni")
            }
        }
        // Les mêmes phrases avec un autre moment ou une autre distance : les traductions presque pareilles.
        val others = when (part) {
            VERB -> listOf(l.copy().also { change(w, it, VERB) })
            WHERE -> Distance.values().filter { it != l.where }.map { d -> l.copy().also { it.where = d } }
            else -> emptyList()
        }.map { frase(it, part) }
        return built(source, f, traps, others)
    }
}
