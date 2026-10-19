package com.thomas.bibisaya.engine

/** Les heures des 3 séances, en minutes depuis minuit. */
data class Times(val matin: Int = 8 * 60, val midi: Int = 12 * 60 + 30, val soir: Int = 19 * 60) {
    fun of(slot: Slot) = when (slot) {
        Slot.MATIN -> matin
        Slot.MIDI -> midi
        Slot.SOIR -> soir
    }

    fun with(slot: Slot, minutes: Int) = when (slot) {
        Slot.MATIN -> copy(matin = minutes)
        Slot.MIDI -> copy(midi = minutes)
        Slot.SOIR -> copy(soir = minutes)
    }

    companion object {
        /** 750 -> « 12H30 » */
        fun upper(minutes: Int) = "%dH%02d".format(minutes / 60, minutes % 60)

        /** 750 -> « 12:30 » */
        fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

        /** 750 -> « 12h30 » */
        fun spoken(minutes: Int) = "%dh%02d".format(minutes / 60, minutes % 60)
    }
}

/** L'état d'une séance du jour sur l'accueil. */
enum class SlotState { DONE, OPEN, LATER }

/** Le programme de la journée. */
object Plan {
    /** Matin : vocabulaire. Midi : phrases simples. Soir : un jour sur deux, vocabulaire ou phrases compliquées. */
    fun lineFor(slot: Slot, day: Long, installDay: Long): Line = when (slot) {
        Slot.MATIN -> Line.VOCAB
        Slot.MIDI -> Line.SIMPLE
        Slot.SOIR -> if (Math.floorMod(day - installDay, 2L) == 0L) Line.COMPLEX else Line.VOCAB
    }

    /** Une séance s'ouvre à son heure et reste disponible jusqu'à minuit. */
    fun state(slot: Slot, progress: Progress, day: Long, minuteOfDay: Int, times: Times): SlotState = when {
        slot in progress.slotsDone(day) -> SlotState.DONE
        minuteOfDay >= times.of(slot) -> SlotState.OPEN
        else -> SlotState.LATER
    }

    /** Ce que la séance fera vraiment : sa ligne, ou un mélange si cette ligne est finie. */
    fun effectiveLine(content: Content, progress: Progress, slot: Slot, day: Long): Line? {
        val line = lineFor(slot, day, progress.installDay)
        return if (progress.lineFinished(content, line)) null else line
    }

    fun greeting(minuteOfDay: Int) = when {
        minuteOfDay < 4 * 60 -> "Maayong gabii,"
        minuteOfDay < 11 * 60 -> "Maayong buntag,"
        minuteOfDay < 13 * 60 -> "Maayong udto,"
        minuteOfDay < 18 * 60 -> "Maayong hapon,"
        else -> "Maayong gabii,"
    }

    /** Un mot déjà vu, différent chaque jour. Avant le tout premier mot : « palihug ». */
    fun wordOfTheDay(content: Content, progress: Progress, day: Long): Item {
        val seen = content.items(Line.VOCAB).filter { progress.isIntroduced(it) }
        if (seen.isEmpty()) return content.items(Line.VOCAB).firstOrNull { it.ceb == "palihug" } ?: content.items(Line.VOCAB).first()
        val index = Math.floorMod(day * 7919L + 13L, seen.size.toLong()).toInt()
        return seen[index]
    }

    /** La petite question de la notification : un mot à réviser, sinon le prochain mot nouveau. */
    fun teaser(content: Content, progress: Progress, line: Line?, day: Long): Item? {
        val lines = if (line == null) Line.values().toList() else listOf(line)
        val due = content.items.filter { it.line in lines && progress.isIntroduced(it) && progress.state(it)!!.dueDay <= day && it.tokens.size <= 4 }
            .minByOrNull { progress.level(it) }
        if (due != null) return due
        return lines.firstNotNullOfOrNull { progress.nextNewItems(content, it, 1).firstOrNull() }
    }

    fun sessionTitle(slot: Slot?, line: Line?): String {
        val what = line?.label ?: "Les 3 lignes mélangées"
        return if (slot == null) "Séance bonus · $what" else "Séance ${slot.adjective} · $what"
    }

    /** « Il reviendra dans 3 jours. » */
    fun dueText(change: Change, isWord: Boolean): String {
        val it = if (isWord) "il" else "elle"
        val start = when {
            change.levelAfter >= Progress.KNOWN -> if (isWord) "Tu connais bien ce mot : " else "Tu connais bien cette phrase : "
            else -> ""
        }
        val Il = if (start.isEmpty()) it.replaceFirstChar { c -> c.uppercase() } else it
        val whenText = when (change.dueInDays) {
            0L -> "reviendra à la prochaine séance."
            1L -> "reviendra demain."
            7L -> "reviendra dans 1 semaine."
            14L -> "reviendra dans 2 semaines."
            21L -> "reviendra dans 3 semaines."
            else -> "reviendra dans ${change.dueInDays} jours."
        }
        return "$start$Il $whenText"
    }
}
