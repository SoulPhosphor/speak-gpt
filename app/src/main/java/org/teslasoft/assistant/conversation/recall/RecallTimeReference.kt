/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Retrieval stage 2 (memory development plan G.6): turns a clear time
 * phrase in the message into a date range by plain rules, with no AI call.
 * Only clear phrases are read; anything else gives null and time then plays
 * no part. A range only favours passages written inside it (G.6); it never
 * excludes one.
 *
 * Read phrases, in English: "today", "yesterday", "N days ago", "this week",
 * "last week", "this month", "last month", "on Tuesday" / "last Tuesday"
 * (the most recent such day before today), "in March" / "back in March" /
 * "last March" (the most recent March up to now), "March 3", "3 March",
 * "March 3, 2026" and "2026-03-03". Weeks start on Monday.
 */
object RecallTimeReference {

    /** A range of instants, both ends inclusive, in epoch milliseconds. */
    data class Range(val fromMillis: Long, val toMillis: Long, val phrase: String) {
        operator fun contains(millis: Long): Boolean = millis in fromMillis..toMillis
    }

    private val MONTHS = Month.entries.associateBy { it.name.lowercase() } +
        Month.entries.associateBy { it.name.lowercase().take(3) }.filterKeys { it != "may" }
    private val DAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }
    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "a" to 1
    )
    private val MONTH_NAME = Month.entries.joinToString("|") { m ->
        val full = m.name.lowercase()
        if (full.length > 3) "$full|${full.take(3)}" else full
    }
    private val DAY_NAME = DayOfWeek.entries.joinToString("|") { it.name.lowercase() }

    private val ISO = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val MONTH_DAY = Regex("""\b($MONTH_NAME)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4}))?""")
    private val DAY_MONTH = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTH_NAME)\b(?:,?\s+(\d{4}))?""")
    private val DAYS_AGO = Regex("""\b(\d{1,2}|${NUMBER_WORDS.keys.joinToString("|")})\s+days?\s+ago\b""")
    private val WEEKDAY = Regex("""\b(on|last|this past)\s+($DAY_NAME)\b""")
    private val IN_MONTH = Regex("""\b(in|back in|last|during)\s+($MONTH_NAME)\b(?:\s+(\d{4}))?""")
    private val TODAY = Regex("""\btoday\b|\bearlier today\b|\bthis morning\b""")
    private val YESTERDAY = Regex("""\byesterday\b""")
    private val LAST_WEEK = Regex("""\blast week\b""")
    private val THIS_WEEK = Regex("""\b(this|earlier this) week\b""")
    private val LAST_MONTH = Regex("""\blast month\b""")
    private val THIS_MONTH = Regex("""\b(this|earlier this) month\b""")

    /** The first clear time phrase in [message], read relative to [nowMillis] in [zone], or null. */
    fun read(message: String, nowMillis: Long, zone: ZoneId): Range? {
        val text = message.lowercase().replace('’', '\'')
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        fun days(from: LocalDate, to: LocalDate, phrase: String): Range? {
            if (from.isAfter(to)) return null
            val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return Range(start, end, phrase)
        }

        // Every clear phrase with where it starts; the earliest in the message is read.
        val found = ArrayList<Pair<Int, Range>>()
        fun add(at: Int, range: Range?) { if (range != null) found.add(at to range) }
        ISO.findAll(text).forEach { m ->
            dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { add(m.range.first, days(it, it, m.value)) }
        }
        MONTH_DAY.findAll(text).forEach { m ->
            val month = MONTHS[m.groupValues[1]] ?: return@forEach
            writtenDate(month, m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), today)?.let { add(m.range.first, days(it, it, m.value)) }
        }
        DAY_MONTH.findAll(text).forEach { m ->
            val month = MONTHS[m.groupValues[2]] ?: return@forEach
            writtenDate(month, m.groupValues[1].toInt(), m.groupValues[3].toIntOrNull(), today)?.let { add(m.range.first, days(it, it, m.value)) }
        }
        DAYS_AGO.findAll(text).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: return@forEach
            if (n in 1..31) today.minusDays(n.toLong()).let { add(m.range.first, days(it, it, m.value)) }
        }
        YESTERDAY.find(text)?.let { m -> today.minusDays(1).let { add(m.range.first, days(it, it, "yesterday")) } }
        TODAY.find(text)?.let { m -> add(m.range.first, days(today, today, "today")) }
        LAST_WEEK.find(text)?.let { m ->
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            add(m.range.first, days(monday, monday.plusDays(6), m.value))
        }
        THIS_WEEK.find(text)?.let { m -> add(m.range.first, days(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today, m.value)) }
        LAST_MONTH.find(text)?.let { m ->
            val first = today.withDayOfMonth(1).minusMonths(1)
            add(m.range.first, days(first, first.with(TemporalAdjusters.lastDayOfMonth()), m.value))
        }
        THIS_MONTH.find(text)?.let { m -> add(m.range.first, days(today.withDayOfMonth(1), today, m.value)) }
        WEEKDAY.find(text)?.let { m ->
            val day = DAYS[m.groupValues[2]] ?: return@let
            today.with(TemporalAdjusters.previous(day)).let { add(m.range.first, days(it, it, m.value)) }
        }
        IN_MONTH.find(text)?.let { m ->
            val month = MONTHS[m.groupValues[2]] ?: return@let
            val year = m.groupValues[3].toIntOrNull()
                ?: if (month.value <= today.monthValue) today.year else today.year - 1
            val first = LocalDate.of(year, month, 1)
            val last = first.with(TemporalAdjusters.lastDayOfMonth())
            add(m.range.first, days(first, if (last.isAfter(today) && first.isBefore(today.plusDays(1))) today else last, m.value))
        }
        // At one position the longer phrase wins ("last week" over "last").
        return found.minWithOrNull(compareBy<Pair<Int, Range>> { it.first }.thenByDescending { it.second.phrase.length })?.second
    }

    /** A written day of a month: in [year] when given, otherwise the most recent such day up to today. */
    private fun writtenDate(month: Month, day: Int, year: Int?, today: LocalDate): LocalDate? {
        if (year != null) return dateOrNull(year, month.value, day)
        val thisYear = dateOrNull(today.year, month.value, day) ?: return null
        return if (thisYear.isAfter(today)) dateOrNull(today.year - 1, month.value, day) else thisYear
    }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        try { LocalDate.of(year, month, day) } catch (_: Exception) { null }
}
