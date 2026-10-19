package com.thomas.bibisaya.engine

/** Les 3 séances de la journée. */
enum class Slot(val code: String, val label: String, val adjective: String) {
    MATIN("M", "Matin", "du matin"),
    MIDI("D", "Midi", "du midi"),
    SOIR("S", "Soir", "du soir");

    companion object {
        fun of(code: String) = values().firstOrNull { it.code == code }
    }
}

/** Où en est un mot : niveau 1 à 5 et jour où il doit revenir. */
class ItemState(var level: Int, var dueDay: Long, var lastUpDay: Long, val introDay: Long)

/** Ce qui a changé pour un mot après une réponse. */
class Change(val levelBefore: Int, val levelAfter: Int, val dueInDays: Long) {
    val known get() = levelAfter >= Progress.KNOWN
}

enum class Badge(val title: String, val subtitle: String, val icon: String) {
    SAKAY("Sakay na !", "1re séance", "ticket"),
    STREAK7("7 jours", "d'affilée", "flame"),
    STREAK30("30 jours", "d'affilée", "flame"),
    PERFECT("Journée parfaite", "3 séances sur 3", "star"),
    PERFECT10("10 journées parfaites", "au total", "star"),
    FLAWLESS("Sans faute", "10 sur 10", "check"),
    WORDS50("50 mots", "bien connus", "book"),
    WORDS100("100 mots", "bien connus", "book"),
    LINE_VOCAB("Vocabulaire", "ligne finie", "book"),
    LINE_SIMPLE("Phrases simples", "ligne finie", "bubble"),
    LINE_COMPLEX("Phrases compliquées", "ligne finie", "layers"),
    TERMINUS("Terminus !", "les 3 lignes", "flag");

    companion object {
        fun forLine(line: Line) = when (line) {
            Line.VOCAB -> LINE_VOCAB
            Line.SIMPLE -> LINE_SIMPLE
            Line.COMPLEX -> LINE_COMPLEX
        }
    }
}

/** Le résultat d'une séance finie. */
class Outcome(
    val points: Int,
    val perfectDay: Boolean,
    val streak: Int,
    val newBadges: List<Badge>,
    val completedStops: List<Stop>,
)

/** Toute la progression de Thomas (gardée sur le téléphone). */
class Progress {
    val items = mutableMapOf<String, ItemState>()
    var installDay = -1L
    var points = 0L
    var streak = 0
    var lastActiveDay = -1L
    var perfectDays = 0
    var lastPerfectDay = -1L
    var sessionsDone = 0
    var flawless = false
    val badges = linkedMapOf<Badge, Long>()
    val completedStops = mutableSetOf<String>()
    val lessonsSeen = mutableSetOf<String>()
    val daySlots = mutableMapOf<Long, MutableSet<Slot>>()

    fun state(item: Item): ItemState? = items[item.id]
    fun isIntroduced(item: Item) = item.id in items
    fun level(item: Item) = items[item.id]?.level ?: 0
    fun isKnown(item: Item) = level(item) >= KNOWN

    /** La série affichée : elle tombe à zéro si hier et aujourd'hui sont vides. */
    fun currentStreak(today: Long) = if (lastActiveDay >= today - 1) streak else 0

    fun slotsDone(day: Long): Set<Slot> = daySlots[day] ?: emptySet()

    // ---------- Révisions ----------

    fun introduce(item: Item, today: Long) {
        if (item.id in items) return
        items[item.id] = ItemState(level = 1, dueDay = today, lastUpDay = -1, introDay = today)
    }

    /** Juste : +1 niveau (une fois par jour au plus). Faux : -1 niveau, et il revient à la prochaine séance. */
    fun answer(item: Item, correct: Boolean, today: Long): Change {
        val s = items.getOrPut(item.id) { ItemState(1, today, -1, today) }
        val before = s.level
        if (correct) {
            if (s.lastUpDay != today) {
                s.level = (s.level + 1).coerceAtMost(MAX_LEVEL)
                s.lastUpDay = today
            }
            s.dueDay = today + interval(s.level)
        } else {
            s.level = (s.level - 1).coerceAtLeast(1)
            s.dueDay = today
        }
        return Change(before, s.level, s.dueDay - today)
    }

    // ---------- Arrêts et lignes ----------

    fun isStopDone(stop: Stop) = stop.key in completedStops

    /** L'arrêt où se trouve le jeepney (null quand la ligne est finie). */
    fun currentStop(content: Content, line: Line): Stop? = content.stops(line).firstOrNull { !isStopDone(it) }

    fun lineFinished(content: Content, line: Line) = currentStop(content, line) == null

    fun knownCount(stop: Stop) = stop.items.count { isKnown(it) }

    /** Un arrêt est réussi quand tous ses mots sont bien connus ; ensuite il le reste. */
    fun refreshStops(content: Content): List<Stop> {
        val done = mutableListOf<Stop>()
        for (line in Line.values()) for (stop in content.stops(line)) {
            if (!isStopDone(stop) && stop.items.all { isKnown(it) }) {
                completedStops.add(stop.key)
                done.add(stop)
            }
        }
        return done
    }

    /** Les prochains mots à découvrir sur une ligne, sans trop s'éloigner de l'arrêt du jeepney. */
    fun nextNewItems(content: Content, line: Line, max: Int): List<Item> {
        if (max <= 0) return emptyList()
        val current = currentStop(content, line) ?: return emptyList()
        return content.stops(line)
            .filter { it.number <= current.number + LOOKAHEAD }
            .flatMap { it.items }
            .filter { !isIntroduced(it) }
            .take(max)
    }

    fun hasNewItems(content: Content, line: Line) = nextNewItems(content, line, 1).isNotEmpty()

    fun knownWords(content: Content) = content.items(Line.VOCAB).count { isKnown(it) }

    // ---------- Fin de séance ----------

    fun finishSession(content: Content, slot: Slot?, today: Long, correct: Int, total: Int): Outcome {
        var gained = correct * POINTS_ANSWER + POINTS_SESSION
        streak = when (lastActiveDay) {
            today -> streak.coerceAtLeast(1)
            today - 1 -> streak + 1
            else -> 1
        }
        lastActiveDay = today
        sessionsDone++
        if (total >= 10 && correct >= total) flawless = true
        var perfect = false
        if (slot != null) {
            daySlots.getOrPut(today) { mutableSetOf() }.add(slot)
            if (slotsDone(today).size == Slot.values().size && lastPerfectDay != today) {
                lastPerfectDay = today
                perfectDays++
                gained += POINTS_PERFECT_DAY
                perfect = true
            }
        }
        daySlots.keys.removeAll { it < today - 7 }
        points += gained
        val stops = refreshStops(content)
        val newBadges = Badge.values().filter { it !in badges && earned(content, it, today) }
        for (b in newBadges) badges[b] = today
        return Outcome(gained, perfect, streak, newBadges, stops)
    }

    fun earned(content: Content, badge: Badge, today: Long): Boolean {
        val (cur, target) = badgeProgress(content, badge, today)
        return cur >= target
    }

    /** Où on en est pour un badge, par exemple 12 sur 30. */
    fun badgeProgress(content: Content, badge: Badge, today: Long): Pair<Int, Int> = when (badge) {
        Badge.SAKAY -> sessionsDone.coerceAtMost(1) to 1
        Badge.STREAK7 -> currentStreak(today) to 7
        Badge.STREAK30 -> currentStreak(today) to 30
        Badge.PERFECT -> perfectDays.coerceAtMost(1) to 1
        Badge.PERFECT10 -> perfectDays to 10
        Badge.FLAWLESS -> (if (flawless) 1 else 0) to 1
        Badge.WORDS50 -> knownWords(content) to 50
        Badge.WORDS100 -> knownWords(content) to 100
        Badge.LINE_VOCAB -> stopsDone(content, Line.VOCAB)
        Badge.LINE_SIMPLE -> stopsDone(content, Line.SIMPLE)
        Badge.LINE_COMPLEX -> stopsDone(content, Line.COMPLEX)
        Badge.TERMINUS -> Line.values().count { lineFinished(content, it) } to 3
    }

    private fun stopsDone(content: Content, line: Line) =
        content.stops(line).count { isStopDone(it) } to content.stops(line).size

    // ---------- Sauvegarde ----------

    fun encode(): String = buildString {
        append("v=1\n")
        append("install=$installDay\n")
        append("points=$points\n")
        append("streak=$streak\n")
        append("active=$lastActiveDay\n")
        append("perfect=$perfectDays\n")
        append("perfectDay=$lastPerfectDay\n")
        append("sessions=$sessionsDone\n")
        append("flawless=$flawless\n")
        append("badges=${badges.entries.joinToString(",") { "${it.key.name}:${it.value}" }}\n")
        append("stops=${completedStops.sorted().joinToString(",")}\n")
        append("lessons=${lessonsSeen.sorted().joinToString(",")}\n")
        append("days=${daySlots.entries.joinToString(",") { e -> "${e.key}:${e.value.joinToString("") { it.code }}" }}\n")
        for ((id, s) in items) append("i:$id=${s.level},${s.dueDay},${s.lastUpDay},${s.introDay}\n")
    }

    companion object {
        const val KNOWN = 3
        const val MAX_LEVEL = 5
        const val LOOKAHEAD = 1
        const val POINTS_ANSWER = 10
        const val POINTS_SESSION = 20
        const val POINTS_PERFECT_DAY = 50

        /** Dans combien de jours un mot revient, selon son niveau. */
        fun interval(level: Int): Long = when (level) {
            1 -> 0
            2 -> 1
            3 -> 3
            4 -> 7
            else -> 21
        }

        fun decode(text: String?): Progress {
            val p = Progress()
            if (text.isNullOrBlank()) return p
            for (line in text.lines()) {
                if ('=' !in line) continue
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                try {
                    when {
                        key.startsWith("i:") -> {
                            val n = value.split(',').map { it.toLong() }
                            p.items[key.removePrefix("i:")] = ItemState(n[0].toInt(), n[1], n[2], n[3])
                        }
                        key == "install" -> p.installDay = value.toLong()
                        key == "points" -> p.points = value.toLong()
                        key == "streak" -> p.streak = value.toInt()
                        key == "active" -> p.lastActiveDay = value.toLong()
                        key == "perfect" -> p.perfectDays = value.toInt()
                        key == "perfectDay" -> p.lastPerfectDay = value.toLong()
                        key == "sessions" -> p.sessionsDone = value.toInt()
                        key == "flawless" -> p.flawless = value.toBoolean()
                        key == "badges" -> value.split(',').filter { it.isNotBlank() }.forEach {
                            val b = Badge.values().firstOrNull { b -> b.name == it.substringBefore(':') }
                            if (b != null) p.badges[b] = it.substringAfter(':').toLong()
                        }
                        key == "stops" -> p.completedStops.addAll(value.split(',').filter { it.isNotBlank() })
                        key == "lessons" -> p.lessonsSeen.addAll(value.split(',').filter { it.isNotBlank() })
                        key == "days" -> value.split(',').filter { it.isNotBlank() }.forEach {
                            val slots = it.substringAfter(':').mapNotNull { c -> Slot.of(c.toString()) }
                            p.daySlots[it.substringBefore(':').toLong()] = slots.toMutableSet()
                        }
                    }
                } catch (e: Exception) {
                    // Une ligne abîmée ne doit pas tout faire perdre.
                }
            }
            return p
        }
    }
}
