package com.drivevoice.mvp

/**
 * Egyptian-Arabic driving NLU. Pure Kotlin. One utterance -> ordered list of structured [VoiceCommand]s.
 * Enforced distinctions:
 *  - "فين أقرب بنزين"  -> Nearby(FUEL)   (search only, never a stop)
 *  - "ضيف بنزين" / "في النص هاتلي بنزين" -> AddStop(FUEL)
 */
object CommandParser {
    private val kindWords: List<Pair<PlaceKind, List<String>>> = listOf(
        PlaceKind.FUEL to listOf("بنزين", "وقود", "fuel", "petrol", "gas", "سولار"),
        PlaceKind.COFFEE to listOf("قهوه", "كوفي", "كافيه", "coffee", "cafe", "كوفى"),
        PlaceKind.FOOD to listOf("مطعم", "اكل", "اكله", "غدا", "عشا", "فطار", "restaurant", "food"),
        PlaceKind.PARKING to listOf("موقف", "باركينج", "parking", "جراج"),
        PlaceKind.CHARGING to listOf("شحن", "شاحن", "charging", "charger")
    )
    private val allKindWords = kindWords.flatMap { it.second }.toSet()

    private val navMarkers = listOf(
        listOf("take", "me", "to"), listOf("navigate", "to"), listOf("go", "to"), listOf("ملاحه", "الي"),
        listOf("خدني"), listOf("خدنا"), listOf("وديني"), listOf("توديني"), listOf("وصلني"),
        listOf("اروح"), listOf("روح"), listOf("اذهب"), listOf("اوصل")
    )
    private val boundaryWords = setOf(
        "قبل", "الساعه", "ساعه", "لو", "اذا", "لما", "انا", "عايز", "عاوز", "محتاج", "مستعجل",
        "هات", "هاتلي", "ضيف", "اضف", "غير", "خد", "عشان", "بس", "بعدين", "كمان", "ثم",
        "دلوقتي", "حالا", "بسرعه", "ممكن", "سمحت", "before", "by", "and", "then", "شغل", "اتصل", "كلم"
    )
    private val connectors = setOf("الي", "الى", "ل", "ع", "علي", "to")
    private val genericMusic = setOf(
        "الموسيقي", "موسيقي", "المزيكا", "مزيكا", "الاغاني", "اغاني", "الاغنيه", "اغنيه", "music", "song", "songs", "الراديو"
    )
    private val conditionalWords = setOf("لو", "ولو", "اذا", "واذا", "لما")

    private class Toks(val light: List<String>, val fold: List<String>) {
        val size get() = fold.size
        fun joined() = fold.joinToString(" ")
        fun has(vararg phrases: String): Boolean {
            val j = " ${joined()} "
            return phrases.any { j.contains(" ${ArabicText.normalize(it)} ") }
        }
        fun hasToken(vararg ws: String) = fold.any { it in ws }
    }

    fun parse(raw: String, wake: String = "فارس"): ParsedUtterance {
        val light = ArabicText.light(raw).split(' ').filter { it.isNotEmpty() }
        val wakeFold = ArabicText.normalize(wake)
        val hadWake = light.any { ArabicText.fold(it) == wakeFold || ArabicText.fold(it) == "يا$wakeFold" }
        val kept = light.filterNot { ArabicText.fold(it) == wakeFold || ArabicText.fold(it) == "يا$wakeFold" }
            .let { l -> if (l.firstOrNull() == "يا") l.drop(1) else l }
        if (kept.isEmpty()) return ParsedUtterance(raw, hadWake, emptyList())
        val t = Toks(kept, kept.map { ArabicText.fold(it) })
        val out = mutableListOf<VoiceCommand>()

        // --- destination -------------------------------------------------------------
        val (dest, destRange) = parseDestination(t)
        if (dest != null) out += VoiceCommand.Navigate(dest)

        // --- deadline / hurry / traffic rule ----------------------------------------
        val deadline = parseDeadline(t)
        if (deadline != null) out += VoiceCommand.ArrivalBy(deadline)
        if (t.hasToken("مستعجل", "ومستعجل", "بسرعه")) out += VoiceCommand.Hurry
        val conditional = t.fold.any { it in conditionalWords } && t.has("زحمه", "التاخير", "تاخير", "واقف", "زحمة")
        if (conditional) out += VoiceCommand.SetTrafficRule(parseTrafficRule(t))

        // --- stops / nearby ----------------------------------------------------------
        out += parseStops(t, destRange, dest != null)

        // --- remaining control commands ---------------------------------------------
        val alt = t.has("غير الطريق", "غير المسار", "غير الخط", "طريق تاني", "طريق تانيه", "طريق بديل", "طريق اخر",
            "خد البديل", "الطريق البديل", "الطريق الاسرع", "اسرع طريق", "طريق اسرع", "تغيير الطريق")
        if (alt && !conditional) out += VoiceCommand.TrafficAlternative(fastest = t.has("اسرع"))
        else if (!conditional && t.has("عيد حساب", "عيد الطريق", "احسب الطريق", "احسبلي الطريق", "reroute", "recalculate", "اعاده حساب"))
            out += VoiceCommand.Reroute
        if (!conditional && dest == null && !alt && (t.has("الطريق عامل", "حاله الطريق", "الترافيك", "traffic") ||
                (t.hasToken("الزحمه", "زحمه") && !t.hasToken("غير")) || t.has("فيه زحمه")))
            out += VoiceCommand.TrafficStatus
        if (t.has("انا فين", "فين انا", "موقعي", "مكاني", "where am i", "my location")) out += VoiceCommand.WhereAmI
        if (t.has("هوصل امتي", "هوصل امتى", "فاضل قد ايه", "فاضل كام", "فاضل", "فاضلي", "وقت الوصول", "كم باقي", "كام دقيقه", "eta", "when will i arrive") &&
            deadline == null) out += VoiceCommand.Eta
        if (t.hasToken("الغي", "الغ", "الغاء", "كنسل", "cancel", "انهي") && !t.hasToken("الاغنيه", "اغنيه") ||
            t.has("وقف الملاحه", "وقف الرحله", "stop navigation")) out += VoiceCommand.CancelRoute
        if (t.hasToken("كرر", "اعد", "اعيد", "repeat") || t.has("قول تاني", "قولها تاني")) out += VoiceCommand.Repeat
        if (t.has("شيل المحطه", "امسح المحطه", "شيل البنزين", "شيل القهوه", "remove stop")) out += VoiceCommand.RemoveStop
        out += parseMedia(t)
        parseCall(t)?.let { out += VoiceCommand.Call(it) }

        return ParsedUtterance(raw, hadWake, if (out.isEmpty()) listOf(VoiceCommand.Unknown) else out)
    }

    // ----------------------------------------------------------------------------------

    private fun isBoundary(t: Toks, i: Int): Boolean {
        val w = t.fold[i]
        if (w in boundaryWords) return true
        if ((w == "في" || w == "وفي") && t.fold.getOrNull(i + 1) == "النص") return true
        if (w.length > 2 && w.startsWith("و")) {
            val r = w.drop(1)
            if (r in boundaryWords || r in allKindWords || r in conditionalWords || r == "في") return r != "في" || t.fold.getOrNull(i + 1) == "النص"
        }
        return false
    }

    /** Takes tokens from [start] until a boundary. Returns original-spelling text and its token range. */
    private fun takeSpan(t: Toks, start: Int): Pair<String?, IntRange> {
        var i = start
        while (i < t.size && t.fold[i] in connectors) i++
        val from = i
        while (i < t.size && !isBoundary(t, i)) i++
        if (i <= from) return null to IntRange.EMPTY
        val toks = t.light.subList(from, i).toMutableList()
        if (toks[0].startsWith("لل") && toks[0].length > 2) toks[0] = toks[0].substring(1)
        return toks.joinToString(" ") to (from until i)
    }

    private fun parseDestination(t: Toks): Pair<String?, IntRange> {
        var best: Pair<Int, Int>? = null // index, marker length
        for (m in navMarkers) {
            for (i in 0..t.size - m.size) {
                if (m.indices.all { t.fold[i + it] == m[it] } && (best == null || i < best.first)) { best = i to m.size; break }
            }
        }
        val (idx, len) = best ?: return null to IntRange.EMPTY
        val (text, range) = takeSpan(t, idx + len)
        if (text == null) return null to IntRange.EMPTY
        val f = ArabicText.normalize(text)
        if (f in allKindWords || f in connectors || f.split(' ').all { it in allKindWords || it == "محطه" }) return null to IntRange.EMPTY
        return text to range
    }

    private val hourRegex: Regex by lazy {
        val num = ArabicText.numberAlternation()
        Regex("(?:قبل|الساعه|ساعه|before|by)\\s+($num)(?:\\s(\\d{2}))?(?=\\s|$)")
    }

    private fun parseDeadline(t: Toks): Deadline? {
        val joined = t.joined()
        val m = hourRegex.find(joined) ?: return null
        var hour = ArabicText.number(m.groupValues[1]) ?: return null
        var minute = m.groupValues[2].toIntOrNull() ?: 0
        val tail = joined.substring(m.range.last + 1).trim()
        when {
            tail.startsWith("ونص") || tail.startsWith("و نص") -> minute = 30
            tail.startsWith("وربع") || tail.startsWith("و ربع") -> minute = 15
            tail.startsWith("الا ربع") -> { minute = 45; hour -= 1 }
            tail.startsWith("الا خمسه") -> { minute = 55; hour -= 1 }
        }
        if (Regex("(مساء|بالليل|العصر|المغرب|pm)").containsMatchIn(tail) && hour in 1..11) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return null
        return Deadline(hour, minute)
    }

    private fun parseTrafficRule(t: Toks): TrafficRule {
        val joined = t.joined()
        val num = ArabicText.numberAlternation()
        val m = Regex("(?:اكتر|اكثر|اكتر من|اكثر من|more than|over)\\s+(?:من\\s+)?($num)\\s*(?:دقيقه|دقايق|دقائق|minutes?|min)").find(joined)
        val minutes = m?.groupValues?.get(1)?.let { ArabicText.number(it) }
            ?: if (t.has("ربع ساعه")) 15 else if (t.has("نص ساعه", "نصف ساعه")) 30 else 5
        val auto = t.has("غير", "غيره", "غيرو", "خد طريق", "بدل", "حول", "reroute", "طريق تاني", "طريق بديل")
        return TrafficRule(minutes.coerceIn(1, 120), auto)
    }

    private fun parseStops(t: Toks, destRange: IntRange, hasDest: Boolean): List<VoiceCommand> {
        val hits = mutableListOf<Pair<Int, PlaceKind>>()
        for (i in 0 until t.size) {
            if (i in destRange) continue
            val w = t.fold[i]
            for ((kind, words) in kindWords) {
                if (words.any { k -> w == k || w == "و$k" || w == "ال$k" || w == "وال$k" }) { hits += i to kind }
            }
        }
        val kinds = hits.sortedBy { it.first }.map { it.second }.distinct()
        val explicitAdd = t.has("ضيف", "اضف", "اضيف", "في النص", "عدي على", "في الطريق", "وقف عند", "نقف عند", "وانا ماشي", "add a stop", "stop at") ||
            t.hasToken("وضيف")
        val weakAdd = t.hasToken("هاتلي", "وهاتلي", "هات", "وهات") || t.has("هات لي")
        val searchCue = t.hasToken("فين", "اقرب", "قريب", "قريبه", "دور", "شوفلي", "شوف", "ابحث", "find", "nearest") || t.has("دور على")
        if (kinds.isNotEmpty()) {
            return when {
                explicitAdd -> kinds.map { VoiceCommand.AddStop(it) }
                searchCue -> listOf(VoiceCommand.Nearby(kinds.first()))
                weakAdd -> kinds.map { VoiceCommand.AddStop(it) }
                else -> listOf(VoiceCommand.Nearby(kinds.first()))
            }
        }
        if (explicitAdd) {
            val cue = t.fold.indexOfFirst { it == "ضيف" || it == "اضف" || it == "اضيف" || it == "وضيف" || it == "وقف" || it == "عدي" }
            if (cue >= 0) {
                var i = cue + 1
                while (i < t.size && t.fold[i] in setOf("محطه", "نقطه", "وقفه", "عند", "على", "علي")) i++
                val q = t.light.drop(i).takeWhile { true }.joinToString(" ")
                if (q.isNotBlank()) return listOf(VoiceCommand.AddStop(null, q))
            }
        }
        val s = t.fold.indexOfFirst { it == "دور" || it == "ابحث" || it == "find" || it == "search" }
        if (s >= 0 && !hasDest) {
            var i = s + 1
            while (i < t.size && t.fold[i] in setOf("على", "علي", "عن", "for")) i++
            val q = t.light.drop(i).joinToString(" ")
            if (q.isNotBlank()) return listOf(VoiceCommand.SearchPlace(q))
        }
        return emptyList()
    }

    private fun parseMedia(t: Toks): List<VoiceCommand> {
        val out = mutableListOf<VoiceCommand>()
        val musicWord = t.hasToken(*genericMusic.toTypedArray())
        if (t.has("وطي الصوت", "اخفض الصوت", "نزل الصوت", "قلل الصوت", "volume down")) out += VoiceCommand.VolumeDown
        else if (t.has("علي الصوت", "ارفع الصوت", "زود الصوت", "عالي الصوت", "volume up")) out += VoiceCommand.VolumeUp
        else if (t.has("اكتم", "كتم الصوت", "اكتم الصوت", "mute")) out += VoiceCommand.Mute
        if (t.has("الاغنيه اللي بعدها", "الاغنيه التاليه", "الاغنيه الجايه", "اللي بعدها", "التالي", "next", "skip", "غير الاغنيه")) out += VoiceCommand.Next
        else if (t.has("الاغنيه السابقه", "الاغنيه اللي قبلها", "اللي قبلها", "السابق", "previous")) out += VoiceCommand.Previous
        else if ((t.hasToken("وقف", "اوقف", "سكت", "pause") && musicWord) || t.has("ايقاف مؤقت", "وقف الاغنيه", "pause")) out += VoiceCommand.Pause
        else {
            val i = t.fold.indexOfFirst { it == "شغل" || it == "شغللي" || it == "play" }
            if (i >= 0) {
                val (q, _) = takeSpan(t, i + 1)
                val f = q?.let { ArabicText.normalize(it) }
                out += if (q == null || f in genericMusic || f?.split(' ')?.all { it in genericMusic } == true) VoiceCommand.Play else VoiceCommand.MusicSearch(q)
            } else if (t.has("كمل الموسيقي", "كمل الاغنيه", "استانف", "resume")) out += VoiceCommand.Play
        }
        return out
    }

    private val callMarkers = listOf("اتصل", "كلم", "كلملي", "call", "رن")
    private val nameStartsWithB = setOf("باسم", "بشير", "بسمه", "بلال", "بهاء", "بسام", "باسل", "بدر", "بيشوي", "بيتر")

    private fun parseCall(t: Toks): String? {
        val i = t.fold.indexOfFirst { it in callMarkers }
        if (i < 0) return null
        var j = i + 1
        if (t.fold.getOrNull(j) == "ب") j++
        else if (t.fold[i] == "اتصل" && j < t.size) {
            val w = t.light[j]
            if (w.startsWith("ب") && w.length > 3 && w !in nameStartsWithB) {
                val rest = listOf(w.substring(1)) + t.light.drop(j + 1)
                return rest.takeWhile { true }.joinToString(" ").ifBlank { null }
            }
        }
        return t.light.drop(j).joinToString(" ").ifBlank { null }
    }
}
