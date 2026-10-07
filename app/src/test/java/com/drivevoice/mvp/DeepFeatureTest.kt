package com.drivevoice.mvp

import org.junit.Assert.*
import org.junit.Test

class DeepFeatureTest {
    @Test fun navigationArabic() = assertEquals(VoiceAction.Navigate("دبي مول"), LegacyCommandParser.parse("فارس وديني دبي مول"))
    @Test fun navigationEgyptian() = assertEquals(VoiceAction.Navigate("مطار دبي"), LegacyCommandParser.parse("يا فارس لو سمحت خدني لمطار دبي"))
    @Test fun stop() = assertEquals(VoiceAction.AddStop("بنزين"), LegacyCommandParser.parse("فارس ضيف محطة بنزين"))
    @Test fun fuel() = assertEquals(VoiceAction.NearbyFuel, LegacyCommandParser.parse("فارس أقرب محطة بنزين"))
    @Test fun coffee() = assertEquals(VoiceAction.NearbyCoffee, LegacyCommandParser.parse("فارس عايز أقرب كافيه"))
    @Test fun food() = assertEquals(VoiceAction.NearbyFood, LegacyCommandParser.parse("فارس دور على مطعم قريب"))
    @Test fun parking() = assertEquals(VoiceAction.NearbyParking, LegacyCommandParser.parse("فارس أقرب موقف"))
    @Test fun charging() = assertEquals(VoiceAction.NearbyCharging, LegacyCommandParser.parse("فارس أقرب محطة شحن"))
    @Test fun reroute() = assertEquals(VoiceAction.Reroute, LegacyCommandParser.parse("فارس أعد الطريق"))
    @Test fun alternative() = assertEquals(VoiceAction.TrafficAlternative, LegacyCommandParser.parse("فارس غير الطريق"))
    @Test fun eta() = assertEquals(VoiceAction.Eta, LegacyCommandParser.parse("فارس كم باقي؟"))
    @Test fun arrivalBy() = assertEquals(VoiceAction.ArrivalBy(20, 0), LegacyCommandParser.parse("فارس عايز أوصل قبل الساعة 20"))
    @Test fun media() = assertEquals(VoiceAction.PlayPause, LegacyCommandParser.parse("فارس شغل الموسيقى"))
    @Test fun next() = assertEquals(VoiceAction.Next, LegacyCommandParser.parse("فارس التالي"))
    @Test fun previous() = assertEquals(VoiceAction.Previous, LegacyCommandParser.parse("فارس السابق"))
    @Test fun volume() = assertEquals(VoiceAction.VolumeUp, LegacyCommandParser.parse("فارس زود الصوت"))
    @Test fun mute() = assertEquals(VoiceAction.Mute, LegacyCommandParser.parse("فارس اكتم الصوت"))
    @Test fun cancel() = assertEquals(VoiceAction.CancelRoute, LegacyCommandParser.parse("فارس الغي الطريق"))
    @Test fun whereAmI() = assertEquals(VoiceAction.WhereAmI, LegacyCommandParser.parse("فارس فين أنا"))
    @Test fun call() = assertEquals(VoiceAction.Call("أحمد"), LegacyCommandParser.parse("فارس اتصل بـ أحمد"))
    @Test fun noWakeWordStillParsesForButtonMode() = assertEquals(VoiceAction.Navigate("دبي مول"), LegacyCommandParser.parse("وديني دبي مول"))
}
