package com.thomas.bibisaya.engine

import com.thomas.bibisaya.engine.Francais.Adjectif
import com.thomas.bibisaya.engine.Francais.Nom as FrNom
import com.thomas.bibisaya.engine.Francais.Verbe as FrVerbe

/**
 * Ce qu'est un nom, pour savoir quels adjectifs lui vont : une personne, un animal, un objet, un bâtiment,
 * une plante, de la nourriture, un fruit, une boisson (ou autre chose : un film).
 */
enum class Sorte { PERSON, ANIMAL, OBJECT, BUILDING, PLANT, FOOD, FRUIT, DRINK, OTHER }

/**
 * Un nom cebuano et sa traduction. [mass] : « du pain » plutôt que « un pain ».
 * Une personne peut être un homme, une femme, ou les deux ([genders] : "m", "f" ou "mf"), avec son mot au féminin ([fem]).
 */
class Nom(
    val ceb: String,
    private val fr: FrNom,
    val sorte: Sorte,
    val mass: Boolean = false,
    private val fem: FrNom? = null,
    private val genders: String = "mf",
) {
    fun canBe(feminine: Boolean) = sorte != Sorte.PERSON || (if (feminine) 'f' else 'm') in genders

    /** Le même mot pour un homme et une femme (l'enfant, l'élève) : on garde le masculin, plus simple à lire. */
    val oneWord get() = (fem ?: fr).sg == fr.sg

    /** Le mot français : « ami » ou « amie ». */
    fun fr(feminine: Boolean = false): FrNom = if (feminine) fem ?: fr else fr

    /** Ce qu'on mange, boit ou achète (avec ug) : « du pain », « une banane ». */
    val some: String get() = if (mass) fr.du() else fr.un()
}

/**
 * Un adjectif cebuano. [sortes] : ce qu'il peut décrire. [fem] : la forme pour une femme (gwapa).
 * [linked] : faux quand la règle de nga est discutée (gamay, bugnaw…) : pas dans la leçon 10.
 */
class Adj(
    val ceb: String,
    val fr: Adjectif,
    val sortes: Set<Sorte>,
    val fem: String? = null,
    val linked: Boolean = true,
    /** Les noms qui ne vont pas avec lui en français (une belle mère, c'est une belle-mère). */
    val avoid: Set<String> = emptySet(),
) {
    fun ceb(feminine: Boolean) = if (feminine && fem != null) fem else ceb

    fun fits(n: Nom) = n.sorte in sortes && n.ceb !in avoid
}

/** Un lieu, avec sa traduction : sa merkado = au marché. */
class Lieu(val ceb: String, val fr: String)

/** Un prénom du cours de Thomas. */
class Prenom(val name: String, val feminine: Boolean)

/** Comment un verbe parle d'une personne (leçon 9) : « va me suivre » ou « va venir avec moi ». */
enum class Objet { DIRECT, AVEC }

/**
 * Un verbe : sa racine, mo- (leçon 6) ou mag- (leçon 7), sa traduction, et ce qui peut le suivre.
 * [things] : ce qu'on achète, mange… (avec ug). [places] : où ça se passe (avec sa).
 * [alone] : il se dit sans rien après (« Mokaon ko. »). [order] : il se dit bien comme un ordre.
 * [person] : il peut avoir une personne pour complément (leçon 9). [beneficiary] : on peut le faire pour quelqu'un.
 */
class Verbe(
    val root: String,
    val mag: Boolean,
    val fr: FrVerbe,
    val things: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val alone: Boolean = true,
    val order: Boolean = true,
    val person: Objet? = null,
    val beneficiary: Boolean = false,
    /** Les choses qu'il peut faire (pour un animal) : manger, boire, courir… */
    val animals: Boolean = false,
) {
    /** mag-andam, mag-istorya : un tiret devant une voyelle (pas devant un h : maghulat). */
    private val glue get() = if (mag && root.first() in "aeiou") "-" else ""

    /** mopalit, magtrabaho, mag-andam : ça va se faire. */
    val future get() = (if (mag) "mag" else "mo") + glue + root
    /** nipalit, nagtrabaho : c'est fait (ou en train, pour mag-). */
    val past get() = (if (mag) "nag" else "ni") + glue + root
    /** mipalit : l'autre forme de ni-, juste aussi. */
    val pastAlt: String? get() = if (mag) null else "mi$root"
    /** palit !, pagtrabaho ! */
    val command get() = if (mag) "pag$glue$root" else root
}

/** Les mots pour fabriquer des phrases neuves : ceux du vocabulaire et des leçons de Thomas. */
object Lexique {
    private fun fr(sg: String, f: Boolean = false, pl: String = sg + "s") = FrNom(sg, pl, f)

    private fun person(ceb: String, m: String, f: String? = null, genders: String = "mf", pl: String = m + "s", fpl: String = (f ?: m) + "s") =
        Nom(ceb, fr(m, genders == "f", pl), Sorte.PERSON, fem = f?.let { fr(it, true, fpl) }, genders = genders)

    val noms: List<Nom> = listOf(
        // Des personnes
        Nom("tawo", fr("personne", true), Sorte.PERSON),
        person("bata", "enfant", "enfant"),
        person("lalake", "homme", genders = "m"),
        person("babaye", "femme", genders = "f"),
        person("higala", "ami", "amie"),
        person("silingan", "voisin", "voisine"),
        person("estudyante", "élève", "élève"),
        person("maestro", "professeur", genders = "m"),
        person("maestra", "professeure", genders = "f"),
        person("doktor", "médecin", genders = "m"),
        person("amahan", "père", genders = "m"),
        person("inahan", "mère", genders = "f"),
        person("lolo", "grand-père", genders = "m", pl = "grands-pères"),
        person("lola", "grand-mère", genders = "f", pl = "grands-mères"),
        person("anak", "enfant", "enfant"),
        person("bisita", "visiteur", "visiteuse"),
        // Des animaux
        Nom("iro", fr("chien"), Sorte.ANIMAL),
        Nom("iring", fr("chat"), Sorte.ANIMAL),
        Nom("langgam", fr("oiseau", pl = "oiseaux"), Sorte.ANIMAL),
        // Des bâtiments et des pièces
        Nom("balay", fr("maison", true), Sorte.BUILDING),
        Nom("kwarto", fr("chambre", true), Sorte.BUILDING),
        Nom("eskwelahan", fr("école", true), Sorte.BUILDING),
        // Des objets
        Nom("lamesa", fr("table", true), Sorte.OBJECT),
        Nom("bangko", fr("chaise", true), Sorte.OBJECT),
        Nom("katre", fr("lit"), Sorte.OBJECT),
        Nom("bintana", fr("fenêtre", true), Sorte.OBJECT),
        Nom("libro", fr("livre"), Sorte.OBJECT),
        Nom("lapis", fr("crayon"), Sorte.OBJECT),
        Nom("bolpen", fr("stylo"), Sorte.OBJECT),
        Nom("kwaderno", fr("cahier"), Sorte.OBJECT),
        Nom("sanina", fr("chemise", true), Sorte.OBJECT),
        Nom("relo", fr("montre", true), Sorte.OBJECT),
        Nom("payong", fr("parapluie"), Sorte.OBJECT),
        Nom("selpon", fr("téléphone"), Sorte.OBJECT),
        Nom("bag", fr("sac"), Sorte.OBJECT),
        Nom("bola", fr("ballon"), Sorte.OBJECT),
        Nom("baso", fr("verre"), Sorte.OBJECT),
        Nom("plato", fr("assiette", true), Sorte.OBJECT),
        // Des plantes
        Nom("bulak", fr("fleur", true), Sorte.PLANT),
        Nom("kahoy", fr("arbre"), Sorte.PLANT),
        // Ce qui se mange
        Nom("pan", fr("pain"), Sorte.FOOD, mass = true),
        Nom("isda", fr("poisson"), Sorte.FOOD, mass = true),
        Nom("manok", fr("poulet"), Sorte.FOOD, mass = true),
        Nom("kan-on", fr("riz"), Sorte.FOOD, mass = true),
        Nom("sabaw", fr("soupe", true), Sorte.FOOD, mass = true),
        Nom("itlog", fr("œuf"), Sorte.FOOD),
        Nom("kamatis", fr("tomate", true), Sorte.FOOD),
        Nom("saging", fr("banane", true), Sorte.FRUIT),
        Nom("mangga", fr("mangue", true), Sorte.FRUIT),
        Nom("mansanas", fr("pomme", true), Sorte.FRUIT),
        // Ce qui se boit
        Nom("tubig", fr("eau", true, "eaux"), Sorte.DRINK, mass = true),
        Nom("gatas", fr("lait"), Sorte.DRINK, mass = true),
        Nom("kape", fr("café"), Sorte.DRINK, mass = true),
        // Autre chose
        Nom("sine", fr("film"), Sorte.OTHER),
    )

    private fun adj(m: String, f: String = m, mp: String = m + "s", fp: String = f + "s", before: Boolean = false, bel: String? = null) =
        Adjectif(listOf(m, f, mp, fp), before, bel)

    private val people = setOf(Sorte.PERSON)
    private val alive = setOf(Sorte.PERSON, Sorte.ANIMAL)
    private val made = setOf(Sorte.OBJECT, Sorte.BUILDING)

    val adjectifs: List<Adj> = listOf(
        // Les gens (et parfois les animaux)
        Adj("taas", adj("grand", "grande", before = true), people),
        Adj("mubo", adj("petit", "petite", before = true), people),
        Adj("tambok", adj("gros", "grosse", "gros", "grosses", before = true), alive),
        Adj("niwang", adj("mince"), people),
        Adj("kusgan", adj("fort", "forte"), alive),
        Adj("buotan", adj("gentil", "gentille"), alive),
        Adj("kugihan", adj("travailleur", "travailleuse"), people),
        Adj("tapulan", adj("paresseux", "paresseuse", "paresseux", "paresseuses"), alive),
        Adj("dato", adj("riche"), people),
        Adj("pobre", adj("pauvre"), people),
        Adj("malipayon", adj("heureux", "heureuse", "heureux", "heureuses"), alive),
        Adj("brayt", adj("intelligent", "intelligente"), people),
        Adj("bugoy", adj("turbulent", "turbulente"), alive, linked = false),
        Adj("hilumon", adj("calme"), alive),
        Adj("gwapo", adj("beau", "belle", "beaux", "belles", before = true, bel = "bel"), people, fem = "gwapa", avoid = setOf("amahan", "inahan")),
        Adj("Pilipino", adj("philippin", "philippine"), people, fem = "Pilipina", linked = false),
        Adj("Pranses", adj("français", "française", "français", "françaises"), people, linked = false),
        Adj("Amerikano", adj("américain", "américaine"), people, fem = "Amerikana", linked = false),
        // Les choses
        Adj("nindot", adj("beau", "belle", "beaux", "belles", before = true, bel = "bel"), made + Sorte.PLANT + Sorte.ANIMAL),
        Adj("dako", adj("grand", "grande", before = true), made + Sorte.PLANT + Sorte.ANIMAL),
        Adj("gamay", adj("petit", "petite", before = true), made + Sorte.PLANT + Sorte.ANIMAL, linked = false),
        Adj("bag-o", adj("neuf", "neuve"), made),
        Adj("daan", adj("vieux", "vieille", "vieux", "vieilles", before = true, bel = "vieil"), made),
        Adj("lig-on", adj("solide"), made),
        Adj("hugaw", adj("sale"), made + Sorte.ANIMAL, linked = false),
        Adj("puti", adj("blanc", "blanche"), made + Sorte.ANIMAL),
        Adj("itom", adj("noir", "noire"), made + Sorte.ANIMAL),
        Adj("pula", adj("rouge"), made),
        Adj("asul", adj("bleu", "bleue"), made),
        Adj("berde", adj("vert", "verte"), made),
        // Ce qui se mange ou se boit
        Adj("lami", adj("délicieux", "délicieuse", "délicieux", "délicieuses"), setOf(Sorte.FOOD, Sorte.FRUIT, Sorte.DRINK)),
        Adj("init", adj("chaud", "chaude"), setOf(Sorte.FOOD, Sorte.DRINK)),
        Adj("bugnaw", adj("froid", "froide"), setOf(Sorte.FOOD, Sorte.DRINK), linked = false),
        Adj("tam-is", adj("sucré", "sucrée"), setOf(Sorte.FRUIT, Sorte.DRINK)),
    )

    val lieux: List<Lieu> = listOf(
        Lieu("merkado", "au marché"),
        Lieu("eskwelahan", "à l'école"),
        Lieu("balay", "à la maison"),
        Lieu("kusina", "dans la cuisine"),
        Lieu("tindahan", "à la boutique"),
        Lieu("simbahan", "à l'église"),
        Lieu("ospital", "à l'hôpital"),
        Lieu("Cebu", "à Cebu"),
        Lieu("kwarto", "dans la chambre"),
        Lieu("sinehan", "au cinéma"),
        Lieu("dagat", "à la mer"),
        Lieu("uma", "à la ferme"),
    )

    /** Les prénoms des leçons de Thomas. */
    val prenoms: List<Prenom> = listOf(
        Prenom("Pablo", false), Prenom("Ernesto", false), Prenom("Marvin", false), Prenom("Paul", false),
        Prenom("James", false), Prenom("Tony", false), Prenom("Rey", false), Prenom("Bert", false),
        Prenom("Rolly", false), Prenom("John", false),
        Prenom("Lorie", true), Prenom("Lorna", true), Prenom("Lisa", true), Prenom("Perlita", true),
        Prenom("Annie", true), Prenom("Ruth", true), Prenom("Edna", true), Prenom("Susan", true),
        Prenom("Stacy", true), Prenom("Mae", true), Prenom("Maria", true), Prenom("Tessie", true),
    )

    private fun v(inf: String, pp: String, imp: String, etre: Boolean = false, refl: Boolean = false, present: String? = null, complement: String = "") =
        FrVerbe(inf, pp, imp, etre, refl, present?.split(" "), complement)

    private val eat = listOf("pan", "isda", "manok", "kan-on", "itlog", "saging", "mangga", "mansanas", "sabaw")
    private val drink = listOf("tubig", "gatas", "kape")
    private val buy = listOf("pan", "isda", "saging", "mangga", "mansanas", "kamatis", "itlog", "libro", "bolpen", "relo", "payong", "sanina", "bola", "bulak")

    val verbes: List<Verbe> = listOf(
        // mo- (leçon 6)
        Verbe("palit", false, v("acheter", "acheté", "achète"), things = buy, places = listOf("merkado", "tindahan"), alone = false, beneficiary = true),
        Verbe("kaon", false, v("manger", "mangé", "mange"), things = eat, places = listOf("balay", "kusina"), animals = true),
        Verbe("inom", false, v("boire", "bu", "bois"), things = drink, places = listOf("balay", "kusina"), animals = true),
        Verbe("adto", false, v("aller", "allé", "va", etre = true), places = listOf("merkado", "eskwelahan", "simbahan", "ospital", "Cebu", "sinehan", "dagat", "uma", "tindahan"), alone = false),
        Verbe("lakaw", false, v("partir", "parti", "pars", etre = true)),
        Verbe("uli", false, v("rentrer", "rentré", "rentre", etre = true)),
        Verbe("balhin", false, v("déménager", "déménagé", "déménage"), places = listOf("Cebu"), order = false),
        Verbe("lingkod", false, v("asseoir", "assis", "assieds-toi", refl = true)),
        Verbe("tindog", false, v("lever", "levé", "lève-toi", refl = true)),
        Verbe("higda", false, v("allonger", "allongé", "allonge-toi", refl = true), places = listOf("kwarto")),
        Verbe("suroy", false, v("promener", "promené", "promène-toi", refl = true), places = listOf("merkado", "dagat", "Cebu"), order = false),
        Verbe("sunod", false, v("suivre", "suivi", "suis"), order = false, person = Objet.DIRECT),
        Verbe("tawag", false, v("appeler", "appelé", "appelle"), order = false),
        Verbe("tabang", false, v("aider", "aidé", "aide"), order = false, person = Objet.DIRECT),
        Verbe("uban", false, v("venir", "venu", "viens", etre = true), alone = false, order = false, person = Objet.AVEC),
        Verbe("hulam", false, v("emprunter", "emprunté", "emprunte"), things = listOf("libro", "lapis", "bolpen", "payong", "bola"), places = listOf("eskwelahan"), alone = false, beneficiary = true),
        Verbe("basa", false, v("lire", "lu", "lis"), things = listOf("libro"), places = listOf("balay", "eskwelahan"), alone = false),
        Verbe("tan-aw", false, v("regarder", "regardé", "regarde"), things = listOf("sine"), places = listOf("sinehan"), alone = false),
        Verbe("kanta", false, v("chanter", "chanté", "chante"), places = listOf("simbahan", "eskwelahan"), beneficiary = true),
        Verbe("dagan", false, v("courir", "couru", "cours"), order = false, animals = true),
        Verbe("abot", false, v("arriver", "arrivé", "arrive", etre = true), places = listOf("Cebu"), order = false),
        Verbe("hatag", false, v("donner", "donné", "donne"), things = listOf("bola", "libro", "bulak", "saging", "mangga", "relo"), alone = false, beneficiary = true),
        // mag- (leçon 7)
        Verbe("trabaho", true, v("travailler", "travaillé", "travaille"), places = listOf("eskwelahan", "ospital", "merkado", "Cebu", "uma")),
        Verbe("tuon", true, v("étudier", "étudié", "étudie"), places = listOf("eskwelahan", "balay")),
        Verbe("hulat", true, v("attendre", "attendu", "attends", present = "attends attends attend attendons attendez attendent"), person = Objet.DIRECT),
        Verbe("luto", true, v("cuisiner", "cuisiné", "cuisine"), things = listOf("isda", "manok", "kan-on", "sabaw", "itlog"), places = listOf("kusina"), beneficiary = true),
        Verbe("dula", true, v("jouer", "joué", "joue"), places = listOf("balay", "eskwelahan"), animals = true),
        Verbe("kape", true, v("prendre", "pris", "prends", present = "prends prends prend prenons prenez prennent", complement = "un café")),
        Verbe("laba", true, v("laver", "lavé", "lave", complement = "le linge")),
        Verbe("limpyo", true, v("faire", "fait", "fais", present = "fais fais fait faisons faites font", complement = "le ménage")),
        Verbe("istorya", true, v("discuter", "discuté", "discute"), order = false),
        Verbe("ampo", true, v("prier", "prié", "prie"), places = listOf("simbahan")),
        Verbe("andam", true, v("préparer", "préparé", "prépare-toi", refl = true)),
        Verbe("dala", true, v("apporter", "apporté", "apporte"), things = listOf("tubig", "gatas", "pan", "libro", "bola"), places = listOf("balay", "eskwelahan"), alone = false, beneficiary = true),
        Verbe("simba", true, v("aller", "allé", "va", etre = true, present = "vais vas va allons allez vont", complement = "à la messe"), order = false),
    )

    fun nom(ceb: String): Nom = noms.first { it.ceb == ceb }
    fun lieu(ceb: String): Lieu = lieux.first { it.ceb == ceb }
    fun verbe(root: String): Verbe? = verbes.firstOrNull { it.root == root }
    fun adjectif(ceb: String): Adj? = adjectifs.firstOrNull { it.ceb == ceb || it.fem == ceb }
}
