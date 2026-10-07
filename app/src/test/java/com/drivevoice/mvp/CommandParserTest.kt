package com.drivevoice.mvp

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {
    @Test fun egyptianNavigation() { assertEquals(VoiceAction.Navigate("دبي مول"), LegacyCommandParser.parse("يا فارس ممكن توديني دبي مول")) }
    @Test fun nearbyFuel() { assertEquals(VoiceAction.NearbyFuel, LegacyCommandParser.parse("فارس أنا عايز أقرب محطة بنزين")) }
    @Test fun coffee() { assertEquals(VoiceAction.NearbyCoffee, LegacyCommandParser.parse("فارس عايز كافيه قريب")) }
    @Test fun addStop() { assertEquals(VoiceAction.AddStop("ماكدونالدز"), LegacyCommandParser.parse("فارس ضيف محطة ماكدونالدز")) }
    @Test fun reroute() { assertEquals(VoiceAction.TrafficAlternative, LegacyCommandParser.parse("فارس الطريق واقف غير الطريق")) }
    @Test fun media() { assertEquals(VoiceAction.Next, LegacyCommandParser.parse("فارس التالي")) }
    @Test fun music_search_is_natural() {
        assertEquals(VoiceAction.MusicSearch("عمرو دياب"), LegacyCommandParser.parse("فارس شغل عمرو دياب"))
    }

    @Test fun traffic_status_beats_generic_search() {
        assertEquals(VoiceAction.TrafficStatus, LegacyCommandParser.parse("فارس حالة الطريق"))
    }
}

