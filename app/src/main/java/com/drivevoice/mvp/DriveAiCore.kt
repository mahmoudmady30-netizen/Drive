package com.drivevoice.mvp

import java.util.Locale

/**
 * Local, deterministic conversational planner for compound driving requests.
 * It converts one utterance into a structured plan without requiring an API key.
 */
object DriveAiCore {
    enum class StopType(val query: String, val label: String) {
        FUEL("gas station", "محطة بنزين"),
        COFFEE("coffee cafe", "قهوة"),
        FOOD("restaurant", "مطعم"),
        PARKING("parking", "موقف سيارات"),
        CHARGING("EV charging station", "محطة شحن")
    }

    data class Deadline(val hour: Int, val minute: Int)
    data class PlannedStop(val type: StopType, val query: String = type.query, val label: String = type.label)
    data class DrivingPlan(
        val destinationQuery: String? = null,
        val deadline: Deadline? = null,
        val rerouteIfDelayMinutes: Int? = null,
        val avoidTraffic: Boolean = false,
        val stops: List<PlannedStop> = emptyList(),
        val musicQuery: String? = null,
        val callName: String? = null,
        val hasNavigationIntent: Boolean = false
    ) {
        val isCompound: Boolean
            get() = listOf(destinationQuery, deadline, rerouteIfDelayMinutes, musicQuery, callName)
                .count { it != null } + stops.size > 1
    }

    fun plan(raw: String): DrivingPlan {
        val t = normalize(raw)
        val deadline = parseDeadline(t)
        val delay = parseRerouteDelay(t)
        val avoidTraffic = containsAny(t, "لو الطريق فيه زحمة", "لو فيه زحمة", "لو الطريق زحمة", "تجنب الزحمة", "ابعد عن الزحمة", "avoid traffic", "if traffic")
        val stops = parseStops(t)
        val music = parseMusic(t)
        val call = parseCall(t)
        val destination = parseDestination(t)
        val navigationIntent = destination != null || hasNavigationIntent(t)
        return DrivingPlan(
            destinationQuery = destination,
            deadline = deadline,
            rerouteIfDelayMinutes = delay ?: if (avoidTraffic) 5 else null,
            avoidTraffic = avoidTraffic || delay != null,
            stops = stops,
            musicQuery = music,
            callName = call,
            hasNavigationIntent = navigationIntent
        )
    }

    private fun normalize(raw: String): String = raw.trim().lowercase(Locale.forLanguageTag("ar-AE"))
        .replace("٠", "0").replace("١", "1").replace("٢", "2").replace("٣", "3").replace("٤", "4")
        .replace("٥", "5").replace("٦", "6").replace("٧", "7").replace("٨", "8").replace("٩", "9")
        .replace("يا فارس", "")
        .replace("فارس", "")
        .replace("لو سمحت", "")
        .replace("من فضلك", "")
        .replace("ممكن", "")
        .replace("عايز", "")
        .replace("عاوز", "")
        .replace("محتاج", "")
        .replace("أنا", "")
        .replace("انا", "")
        .replace("،", ",")
        .replace("  ", " ")
        .trim()

    private fun parseDeadline(t: String): Deadline? {
        val m = Regex("(?:قبل|الساعة|الساعه|by|before)\\s*(\\d{1,2})(?:[:.](\\d{2}))?").find(t) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return Deadline(hour, minute)
    }

    private fun parseRerouteDelay(t: String): Int? {
        val number = "(?:\\d{1,2}|واحد|واحدة|اتنين|اثنين|ثلاثة|تلاتة|أربعة|اربعة|أربع|خمسة|خمس|ستة|ست|سبعة|سبع|ثمانية|تمانية|تسعة|تسع|عشرة|عشر|أحد عشر|احد عشر|اثنا عشر|اثني عشر|اتناشر|ثلاثة عشر|تلاتاشر|خمسة عشر|خمسطاشر|عشرون|عشرين|ثلاثون|تلاتين|أربعون|اربعين|خمسون|خمسين|ستون|ستين)"
        val regexes = listOf(
            Regex("(?:أكتر|اكثر|أكثر)\\s*من\\s*($number)\\s*(?:دقيقة|دقايق|دقائق|دقيقه)"),
            Regex("(?:أكتر|اكثر|أكثر)\\s*($number)\\s*(?:دقيقة|دقايق|دقائق|دقيقه)"),
            Regex("(?:more than|over)\\s*(\\d{1,2})\\s*minutes?")
        )
        val direct = regexes.firstNotNullOfOrNull { regex ->
            regex.find(t)?.groupValues?.getOrNull(1)?.let(::parseNumber)
        }
        if (direct != null) return direct.takeIf { it in 1..120 }
        if (containsAny(t, "ربع ساعة", "ربع ساعه")) return 15
        if (containsAny(t, "نص ساعة", "نصف ساعة", "نص ساعه", "نصف ساعه")) return 30
        return null
    }

    private fun parseNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        return when (value.trim()) {
            "واحد", "واحدة" -> 1
            "اتنين", "اثنين" -> 2
            "ثلاثة", "تلاتة" -> 3
            "أربعة", "اربعة", "أربع" -> 4
            "خمسة", "خمس" -> 5
            "ستة", "ست" -> 6
            "سبعة", "سبع" -> 7
            "ثمانية", "تمانية" -> 8
            "تسعة", "تسع" -> 9
            "عشرة", "عشر" -> 10
            "أحد عشر", "احد عشر" -> 11
            "اثنا عشر", "اثني عشر", "اتناشر" -> 12
            "ثلاثة عشر", "تلاتاشر" -> 13
            "خمسة عشر", "خمسطاشر" -> 15
            "ستة عشر", "ستاشر" -> 16
            "سبعة عشر", "سبعتاشر" -> 17
            "ثمانية عشر", "تمانتاشر" -> 18
            "تسعة عشر", "تسعتاشر" -> 19
            "عشرون", "عشرين" -> 20
            "ثلاثون", "تلاتين" -> 30
            "أربعون", "اربعين" -> 40
            "خمسون", "خمسين" -> 50
            "ستون", "ستين" -> 60
            else -> null
        }
    }
    private fun parseStops(t: String): List<PlannedStop> {
        val explicitStopIntent = containsAny(
            t, "في النص", "هاتلي", "هات لي", "خليلي", "خلي لي", "ضيف",
            "وقف عند", "عدي على", "محطة في الطريق", "on the way", "add a stop", "stop at"
        )
        if (!explicitStopIntent) return emptyList()

        val out = mutableListOf<PlannedStop>()
        if (containsAny(t, "بنزين", "محطة بنزين", "وقود", "petrol", "gas station", "fuel")) out += PlannedStop(StopType.FUEL)
        if (containsAny(t, "قهوة", "كوفي", "كافيه", "coffee", "cafe")) out += PlannedStop(StopType.COFFEE)
        if (containsAny(t, "مطعم", "أكل", "غدا", "عشا", "food", "restaurant")) out += PlannedStop(StopType.FOOD)
        if (containsAny(t, "باركينج", "موقف سيارات", "مواقف", "parking")) out += PlannedStop(StopType.PARKING)
        if (containsAny(t, "شحن", "شاحن", "محطة شحن", "electric charging", "ev charger")) out += PlannedStop(StopType.CHARGING)
        return out.distinctBy { it.type }
    }
    private fun parseMusic(t: String): String? {
        val markers = listOf("شغل", "شغللي", "شغل لي", "شغل أغاني", "شغل اغاني", "play")
        val marker = markers.firstOrNull { t.contains(it) } ?: return null
        val tail = t.substringAfter(marker).trim(' ', ',', '،', '.')
        if (tail.isBlank() || containsAny(tail, "الموسيقى", "music", "الأغاني", "الاغاني", "المزيكا")) return null
        return tail.substringBefore("لو ").substringBefore("ولو ").trim().ifBlank { null }
    }

    private fun parseCall(t: String): String? {
        val marker = listOf("اتصل بـ", "اتصل ب", "كلم", "كلملي", "call ").firstOrNull { t.contains(it) } ?: return null
        return t.substringAfter(marker).trim().substringBefore(",").substringBefore(" و").ifBlank { null }
    }

    /**
     * Navigation intent can exist even when the user does not name a destination
     * yet, e.g. "أنا عايز أوصل قبل ٨". In that case destinationQuery stays null,
     * but the planner must still classify the utterance as a navigation request.
     */
    private fun hasNavigationIntent(t: String): Boolean {
        return containsAny(
            t,
            "وديني", "توديني", "خذني", "خدني", "وصلني",
            "عايز أوصل", "عاوز أوصل", "محتاج أوصل", "أوصل",
            "روح", "اذهب", "ملاحة", "navigate", "go to", "take me to"
        )
    }

    private fun parseDestination(t: String): String? {
        val markers = listOf("وديني", "توديني", "خذني", "خدني", "وصلني", "عايز أوصل", "عاوز أوصل", "أوصل", "روح", "اذهب", "ملاحة إلى", "ملاحة ل", "take me to", "go to", "navigate to")
        val marker = markers.firstOrNull { t.contains(it) } ?: return null
        var q = t.substringAfter(marker).trim()
        q = q.removePrefix("إلى ").removePrefix("الى ").removePrefix("ل ").trim()
        val boundaries = listOf(",", " وعايز", " وعاوز", " ومحتاج", " ولو", " لو", " وفي النص", " وعايز أوصل", " وعاوز أوصل", " عايز أوصل", " قبل ", " الساعة ", " by ", " before ")
        val cut = boundaries.mapNotNull { b -> q.indexOf(b).takeIf { it >= 0 } }.minOrNull()
        if (cut != null) q = q.substring(0, cut).trim()
        val stopOnly = listOf("بنزين", "قهوة", "مطعم", "موقف", "شحن")
        if (q.isBlank() || q.startsWith("قبل ") || q.startsWith("الساعة ") || stopOnly.any { q == it }) return null
        return q
    }

    private fun containsAny(text: String, vararg values: String): Boolean = values.any { text.contains(it) }
}
