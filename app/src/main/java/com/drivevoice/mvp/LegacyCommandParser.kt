package com.drivevoice.mvp

sealed class VoiceAction {
    data class Navigate(val query: String): VoiceAction()
    data class SearchPlace(val query: String): VoiceAction()
    data class AddStop(val query: String): VoiceAction()
    data class Call(val name: String): VoiceAction()
    data object PlayPause: VoiceAction()
    data object Next: VoiceAction()
    data object Previous: VoiceAction()
    data object VolumeUp: VoiceAction()
    data object VolumeDown: VoiceAction()
    data object Mute: VoiceAction()
    data object CancelRoute: VoiceAction()
    data object Repeat: VoiceAction()
    data class ArrivalBy(val hour: Int, val minute: Int): VoiceAction()
    data object Eta: VoiceAction()
    data object WhereAmI: VoiceAction()
    data object Reroute: VoiceAction()
    data object TrafficAlternative: VoiceAction()
    data object TrafficStatus: VoiceAction()
    data class MusicSearch(val query: String): VoiceAction()
    data object NearbyFuel: VoiceAction()
    data object NearbyCoffee: VoiceAction()
    data object NearbyFood: VoiceAction()
    data object NearbyParking: VoiceAction()
    data object NearbyCharging: VoiceAction()
    data object Unknown: VoiceAction()
}

/** Local, offline intent layer: understands natural Egyptian/Arabic driving phrases without an API key. */
object LegacyCommandParser {
    private val navigation = listOf("وديني", "توديني", "خذني", "روح", "اذهب", "وصلني", "ملاحة إلى", "ملاحة ل", "navigate to", "take me to", "go to")
    private val search = listOf("أقرب", "قريب", "ابحث عن", "دور على", "find", "search")

    private fun normalized(raw: String): String = raw.trim().lowercase(LocaleHolder.arabic)
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
        .trim()

    fun parse(raw: String): VoiceAction {
        val t = normalized(raw)
        val musicMarker = listOf("شغل", "شغللي", "شغل لي", "شغل أغنية", "play song").firstOrNull {
            t.startsWith(it) && t.length > it.length + 1
        }
        if (musicMarker != null) {
            val q = t.substringAfter(musicMarker).trim()
            if (q.isNotBlank() && !listOf("الموسيقى", "الأغاني", "الاغاني", "المزيكا", "music").contains(q)) {
                return VoiceAction.MusicSearch(q)
            }
        }
        when {
            listOf("فين أنا", "أين أنا", "موقعي", "مكاني", "where am i", "my location").any { t.contains(it) } -> return VoiceAction.WhereAmI
            listOf("حالة الطريق", "الزحمة", "فيه زحمة", "الترافيك", "traffic", "traffic status").any { t.contains(it) } -> return VoiceAction.TrafficStatus
            listOf("غير الطريق", "غير المسار", "طريق تاني", "طريق آخر", "اعمل طريق تاني", "reroute").any { t.contains(it) } -> return VoiceAction.TrafficAlternative
            listOf("عيد الطريق", "أعد الطريق", "احسب الطريق تاني", "recalculate", "re route").any { t.contains(it) } -> return VoiceAction.Reroute
            listOf("وقف عند", "ضيف محطة", "ضيف ", "عدي على", "خلينا نعدي على", "خليها محطة", "add a stop", "stop at").any { t.contains(it) } -> {
                val marker = listOf("وقف عند", "ضيف محطة", "ضيف ", "عدي على", "خلينا نعدي على", "خليها محطة", "add a stop", "stop at").first { t.contains(it) }
                val q = t.substringAfter(marker).trim().trim(',', '،', '.', ' ')
                if (q.isNotBlank()) return VoiceAction.AddStop(q)
            }
            listOf("بنزين", "محطة بنزين", "وقود", "petrol", "gas station", "fuel").any { t.contains(it) } && listOf("أقرب", "محطة", "فين", "دور", "find").any { t.contains(it) } -> return VoiceAction.NearbyFuel
            listOf("قهوة", "كوفي", "كافيه", "coffee", "cafe").any { t.contains(it) } && listOf("أقرب", "قريب", "على الطريق", "فين", "دور", "find").any { t.contains(it) } -> return VoiceAction.NearbyCoffee
            listOf("مطعم", "أكل", "غدا", "عشا", "food", "restaurant").any { t.contains(it) } && listOf("أقرب", "على الطريق", "فين", "دور", "find").any { t.contains(it) } -> return VoiceAction.NearbyFood
            listOf("باركينج", "موقف", "مواقف", "parking").any { t.contains(it) } -> return VoiceAction.NearbyParking
            listOf("شحن", "شاحن", "محطة شحن", "electric charging", "ev charger").any { t.contains(it) } -> return VoiceAction.NearbyCharging
            listOf("وقف الأغنية", "وقف الموسيقى", "pause", "stop music", "شغل الموسيقى", "play music", "شغل الأغنية", "play", "استأنف").any { t.contains(it) } -> return VoiceAction.PlayPause
            listOf("التالي", "الأغنية التالية", "next", "skip").any { t.contains(it) } -> return VoiceAction.Next
            listOf("السابق", "الأغنية السابقة", "previous", "back song").any { t.contains(it) } -> return VoiceAction.Previous
            listOf("ارفع الصوت", "علي الصوت", "زود الصوت", "volume up", "higher volume").any { t.contains(it) } -> return VoiceAction.VolumeUp
            listOf("وطي الصوت", "خفض الصوت", "نزل الصوت", "volume down", "lower volume").any { t.contains(it) } -> return VoiceAction.VolumeDown
            listOf("اكتم الصوت", "كتم الصوت", "mute").any { t.contains(it) } -> return VoiceAction.Mute
            listOf("الغ الطريق", "الغى الطريق", "الغي الطريق", "إلغاء الطريق", "وقف الملاحة", "cancel route", "stop navigation").any { t.contains(it) } -> return VoiceAction.CancelRoute
            listOf("كرر", "اعادة", "أعد", "repeat").any { t.contains(it) } -> return VoiceAction.Repeat
            listOf("كم فاضل", "كم باقي", "متى أوصل", "وقت الوصول", "eta", "how long", "when will i arrive").any { t.contains(it) } -> return VoiceAction.Eta
        }
        listOf("اتصل بـ", "اتصل ب", "كلم", "كلملي", "call ").firstOrNull { t.contains(it) }?.let { marker ->
            val name = t.substringAfter(marker).trim()
            if (name.isNotBlank()) return VoiceAction.Call(name)
        }
        parseArrivalBy(t)?.let { return it }
        listOf("خدني", "وديني", "توديني", "وصلني", "روح", "اذهب", "take me", "go to").firstOrNull { t.contains(it) }?.let { marker ->
            val q = t.substringAfter(marker).trim().trim(',', '،', '.', ' ').removePrefix("إلى ").removePrefix("الى ").removePrefix("ل ").let { value -> if (value.startsWith("ل") && value.length > 1 && value[1].isLetter()) value.substring(1) else value }
            if (q.isNotBlank()) return VoiceAction.Navigate(q)
        }
        navigation.firstOrNull { t.contains(it) }?.let { marker ->
            val q = t.substringAfter(marker).trim().trim(',', '،', '.', ' ').removePrefix("إلى ").removePrefix("الى ").removePrefix("ل ").let { value -> if (value.startsWith("ل") && value.length > 1 && value[1].isLetter()) value.substring(1) else value }
            if (q.isNotBlank()) return VoiceAction.Navigate(q)
        }
        search.firstOrNull { t.contains(it) }?.let { marker ->
            val q = t.substringAfter(marker).trim().trim(',', '،', '.', ' ')
            if (q.isNotBlank()) return VoiceAction.SearchPlace(q)
        }
        return VoiceAction.Unknown
    }

    private fun parseArrivalBy(t: String): VoiceAction.ArrivalBy? {
        val regex = Regex("(?:قبل|الساعة|الساعه|by|before)\\s*(\\d{1,2})(?:[:.](\\d{2}))?")
        val m = regex.find(t) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return VoiceAction.ArrivalBy(hour, minute)
    }

    private object LocaleHolder { val arabic = java.util.Locale.forLanguageTag("ar-AE") }
}
