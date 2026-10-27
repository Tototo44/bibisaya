package com.thomas.bibisaya.engine

/**
 * Un peu de grammaire française, pour traduire les phrases que l'appli fabrique :
 * les articles, l'accord des adjectifs et la conjugaison (aller + verbe, passé composé, présent, ordre).
 */
object Francais {
    /** Les 6 personnes : je, tu, il/elle, nous, vous, ils/elles. */
    private val allerForms = listOf("vais", "vas", "va", "allons", "allez", "vont")
    private val avoirForms = listOf("ai", "as", "a", "avons", "avez", "ont")
    private val etreForms = listOf("suis", "es", "est", "sommes", "êtes", "sont")
    private val reflexive = listOf("me", "te", "se", "nous", "vous", "se")
    /** Les pronoms compléments : « va me suivre », « va les aider ». */
    private val objects = listOf("me", "te", "le", "nous", "vous", "les")
    /** Après « avec », « pour » : « avec moi », « avec eux ». */
    private val stressed = listOf("moi", "toi", "lui", "nous", "vous", "eux")
    private val indirect = listOf("me", "te", "lui", "nous", "vous", "leur")

    /** Commence par une voyelle (ou un h muet) : « l'école », « j'ai », « l'hôpital ». */
    fun vowel(s: String): Boolean = s.firstOrNull()?.lowercaseChar()?.let { it in "aeiouyéèêëàâîïôûœh" } == true

    /** je + ai = j'ai, le + enfant = l'enfant, me + appeler = m'appeler… */
    fun elide(word: String, next: String): String =
        if (word in setOf("je", "me", "te", "se", "le", "la", "de", "ne") && vowel(next)) word.dropLast(1) + "'" + next else "$word $next"

    /** La majuscule du début de phrase. */
    fun capital(s: String) = s.replaceFirstChar { it.uppercase() }

    /** Qui fait l'action : un pronom, un prénom ou un nom (« l'enfant »). [p] : la personne, de 0 (je) à 5 (ils). */
    class Sujet(val text: String, val p: Int, val feminine: Boolean) {
        val plural get() = p >= 3
    }

    fun pronoun(p: Int, feminine: Boolean): Sujet = Sujet(
        listOf("je", "tu", if (feminine) "elle" else "il", "nous", "vous", if (feminine) "elles" else "ils")[p], p, feminine,
    )

    /** Un nom français : son singulier, son pluriel et son genre. */
    class Nom(val sg: String, val pl: String, val feminine: Boolean) {
        fun word(plural: Boolean) = if (plural) pl else sg

        /** le livre, la table, l'école, les livres */
        fun le(plural: Boolean = false) = if (plural) "les $pl" else elide(if (feminine) "la" else "le", sg)

        /** un livre, une table, des livres */
        fun un(plural: Boolean = false) = if (plural) "des $pl" else "${if (feminine) "une" else "un"} $sg"

        /** du pain, de la soupe, de l'eau, des légumes */
        fun du(plural: Boolean = false) = when {
            plural -> "des $pl"
            vowel(sg) -> "de l'$sg"
            feminine -> "de la $sg"
            else -> "du $sg"
        }

        /** (la maison) du professeur, de la mère, de l'enfant, des enfants */
        fun of(plural: Boolean = false) = du(plural)

        /** ce livre, cette table, cet enfant */
        fun ce() = when {
            feminine -> "cette $sg"
            vowel(sg) -> "cet $sg"
            else -> "ce $sg"
        }

        /** au marché, à la maison, à l'école, aux enfants */
        fun at(plural: Boolean = false) = when {
            plural -> "aux $pl"
            vowel(sg) -> "à l'$sg"
            feminine -> "à la $sg"
            else -> "au $sg"
        }
    }

    /** mon, ma, ton, ta… (« mon école » : devant une voyelle, toujours mon, ton, son). [p] de 0 à 5. */
    fun possessive(p: Int, nom: Nom, plural: Boolean = false): String {
        val word = nom.word(plural)
        val det = if (plural) listOf("mes", "tes", "ses", "nos", "vos", "leurs")[p]
        else when (p) {
            0 -> if (nom.feminine && !vowel(word)) "ma" else "mon"
            1 -> if (nom.feminine && !vowel(word)) "ta" else "ton"
            2 -> if (nom.feminine && !vowel(word)) "sa" else "son"
            3 -> "notre"
            4 -> "votre"
            else -> "leur"
        }
        return "$det $word"
    }

    /** Un adjectif et ses 4 formes : fort, forte, forts, fortes. [bel] : la forme devant une voyelle (bel, vieil). */
    class Adjectif(val forms: List<String>, val before: Boolean = false, val bel: String? = null) {
        fun agree(feminine: Boolean, plural: Boolean) = forms[(if (plural) 2 else 0) + (if (feminine) 1 else 0)]

        /** « grande maison », « bel arbre », « médecin gentil » (sans l'article). */
        fun with(nom: Nom, plural: Boolean = false): String {
            val noun = nom.word(plural)
            if (!before) return "$noun ${agree(nom.feminine, plural)}"
            val adj = if (!plural && !nom.feminine && bel != null && vowel(noun)) bel else agree(nom.feminine, plural)
            return "$adj $noun"
        }

        /** « une grande maison », « un bel arbre », « de grandes maisons » (des devient de devant l'adjectif). */
        fun un(nom: Nom, plural: Boolean = false): String = when {
            !plural -> "${if (nom.feminine) "une" else "un"} ${with(nom)}"
            before -> "de ${with(nom, true)}"
            else -> "des ${with(nom, true)}"
        }
    }

    /**
     * Un verbe français. [participe] sans accord (allé, assis) ; [etre] : passé composé avec être.
     * [present] : les 6 formes quand le verbe n'est pas un verbe en -er tout simple.
     * [complement] : ce qui suit toujours le verbe (« prendre un café », « laver le linge »).
     */
    class Verbe(
        val infinitif: String,
        val participe: String,
        val imperatif: String,
        val etre: Boolean = false,
        val pronominal: Boolean = false,
        val present: List<String>? = null,
        val complement: String = "",
    ) {
        private fun tail() = if (complement.isEmpty()) "" else " $complement"

        private fun agreed(s: Sujet): String {
            if (!etre && !pronominal) return participe
            var pp = participe
            if (s.feminine) pp += "e"
            if (s.plural && !pp.endsWith("s")) pp += "s"
            return pp
        }

        private fun presentForm(p: Int): String = present?.get(p) ?: run {
            val stem = infinitif.removeSuffix("er")
            // mangeons, commençons
            val soft = when {
                p == 3 && stem.endsWith("g") -> stem + "e"
                p == 3 && stem.endsWith("c") -> stem.dropLast(1) + "ç"
                else -> stem
            }
            soft + listOf("e", "es", "e", "ons", "ez", "ent")[p]
        }

        /** « je vais acheter », « Ana va s'asseoir », « elle va me suivre » ([objet] : le pronom complément). */
        fun future(s: Sujet, objet: String? = null): String {
            val inf = when {
                pronominal -> elide(reflexive[s.p], infinitif)
                objet != null -> elide(objet, infinitif)
                else -> infinitif
            }
            return subject(s, "${allerForms[s.p]} $inf${tail()}")
        }

        /** « j'ai acheté », « elle est partie », « ils se sont assis » */
        fun past(s: Sujet): String {
            val aux = if (etre || pronominal) etreForms[s.p] else avoirForms[s.p]
            val verb = if (pronominal) elide(reflexive[s.p], aux) else aux
            return subject(s, "$verb ${agreed(s)}${tail()}")
        }

        /** « je travaille », « nous nous préparons », « il m'attend » ([objet] : le pronom complément). */
        fun now(s: Sujet, objet: String? = null): String {
            val form = presentForm(s.p)
            val verb = when {
                pronominal -> elide(reflexive[s.p], form)
                objet != null -> elide(objet, form)
                else -> form
            }
            return subject(s, "$verb${tail()}")
        }

        /** « Mange », « Assieds-toi » (sans le point d'exclamation). */
        fun order(): String = capital(imperatif + tail())
    }

    /** « je » + « ai acheté » = « j'ai acheté » ; « Ana » + « va manger ». */
    fun subject(s: Sujet, rest: String): String = if (s.text == "je") elide("je", rest) else "${s.text} $rest"

    /** Le pronom complément : « me », « te », « le » ou « la », « nous », « vous », « les ». */
    fun objectPronoun(p: Int, feminine: Boolean): String = if (p == 2 && feminine) "la" else objects[p]

    /** Le pronom de « donner à » : « va me donner », « va lui donner », « va leur donner ». */
    fun indirectPronoun(p: Int): String = indirect[p]

    /** Après une préposition : « avec moi », « pour elle », « avec eux ». */
    fun stressedPronoun(p: Int, feminine: Boolean): String = when {
        p == 2 && feminine -> "elle"
        p == 5 && feminine -> "elles"
        else -> stressed[p]
    }

    /** Une phrase finie : la majuscule, et le point (ou « ! », « ? » après une espace). */
    fun sentence(s: String, end: Char = '.'): String = capital(s.trim()) + if (end == '.') "." else " $end"
}
