package com.drivevoice.mvp

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DriveNluTest {
    private fun p(s: String) = CommandParser.parse(s)
    private fun first(s: String) = p(s).commands.first()

    // ---- critical cases from the product brief -------------------------------------------------
    @Test fun navigate_airport() {
        val u = p("فارس خدني المطار")
        assertTrue(u.hadWakeWord)
        assertEquals(VoiceCommand.Navigate("المطار"), u.commands.first())
    }
    @Test fun add_fuel_stop() = assertEquals(listOf<VoiceCommand>(VoiceCommand.AddStop(PlaceKind.FUEL)), p("فارس ضيف محطة بنزين").commands)
    @Test fun nearby_fuel_is_not_a_stop() {
        val u = p("فارس فين أقرب بنزين؟")
        assertEquals(listOf<VoiceCommand>(VoiceCommand.Nearby(PlaceKind.FUEL)), u.commands)
        assertTrue(u.stops.isEmpty())
    }
    @Test fun nearby_coffee_is_not_a_stop() = assertEquals(VoiceCommand.Nearby(PlaceKind.COFFEE), first("فارس شوفلي قهوة"))
    @Test fun destination_with_two_stops_keeps_order() {
        val u = p("فارس خدني المطار وفي النص هاتلي بنزين وقهوة")
        assertEquals("المطار", u.destination)
        assertEquals(listOf(PlaceKind.FUEL, PlaceKind.COFFEE), u.stops.map { it.kind })
    }
    @Test fun full_compound_command() {
        val u = p("فارس أنا مستعجل وعايز أوصل قبل ٨، ولو الطريق فيه زحمة خد طريق تاني، وفي النص هاتلي بنزين وقهوة")
        assertTrue(u.isNavigation)
        assertEquals(Deadline(8, 0), u.deadline)
        assertNotNull(u.trafficRule)
        assertTrue(u.trafficRule!!.auto)
        assertEquals(listOf(PlaceKind.FUEL, PlaceKind.COFFEE), u.stops.map { it.kind })
        assertTrue(u.isCompound)
        assertNull(u.destination)
    }
    @Test fun full_product_sentence_with_destination() {
        val u = p("فارس خدني المطار، وأنا مستعجل، لو الطريق زحمة غيره، وفي النص هاتلي بنزين وقهوة")
        assertEquals("المطار", u.destination)
        assertEquals(2, u.stops.size)
        assertNotNull(u.trafficRule)
    }

    // ---- Egyptian phrasing ---------------------------------------------------------------------
    @Test fun wanna_go() = assertEquals("المطار", p("فارس أنا عايز أروح المطار").destination)
    @Test fun waddini() = assertEquals("المطار", p("فارس وديني المطار").destination)
    @Test fun ll_prefix_is_removed() = assertEquals("المطار", p("فارس خدني للمطار").destination)
    @Test fun keeps_original_spelling_for_geocoding() = assertEquals("المعادي", p("فارس خدني المعادي").destination)
    @Test fun wake_word_inside_other_word_is_not_stripped() = assertEquals("فارسكور", p("خدني فارسكور").destination)
    @Test fun change_route() = assertEquals(VoiceCommand.TrafficAlternative(), first("فارس غير الطريق"))
    @Test fun fastest_route() = assertEquals(VoiceCommand.TrafficAlternative(fastest = true), first("فارس خد الطريق الأسرع"))
    @Test fun eta_questions() {
        assertEquals(VoiceCommand.Eta, first("فارس فاضل قد إيه؟"))
        assertEquals(VoiceCommand.Eta, first("فارس هوصل إمتى؟"))
    }
    @Test fun traffic_status() = assertEquals(VoiceCommand.TrafficStatus, first("فارس الطريق عامل إيه؟"))
    @Test fun where_am_i() = assertEquals(VoiceCommand.WhereAmI, first("فارس أنا فين؟"))
    @Test fun hurry() = assertTrue(VoiceCommand.Hurry in p("فارس أنا مستعجل").commands)
    @Test fun cancel_and_repeat() {
        assertEquals(VoiceCommand.CancelRoute, first("فارس ألغي الرحلة"))
        assertEquals(VoiceCommand.Repeat, first("فارس كرر"))
    }
    @Test fun conditional_traffic_phrase_is_a_rule_not_a_reroute() {
        val u = p("فارس لو الطريق زحمة غير الطريق")
        assertEquals(1, u.commands.size)
        assertTrue(u.commands[0] is VoiceCommand.SetTrafficRule)
    }
    @Test fun add_named_stop() = assertEquals(VoiceCommand.AddStop(null, "ماكدونالدز"), first("فارس ضيف محطة ماكدونالدز"))

    // ---- deadlines & numbers -------------------------------------------------------------------
    @Test fun deadline_arabic_digits() = assertEquals(Deadline(8, 0), p("فارس عايز أوصل قبل ٨").deadline)
    @Test fun deadline_word_number() = assertEquals(Deadline(8, 0), p("فارس لازم أوصل الساعة تمانية").deadline)
    @Test fun deadline_half() = assertEquals(Deadline(8, 30), p("فارس عايز أوصل قبل 8 ونص").deadline)
    @Test fun deadline_quarter() = assertEquals(Deadline(8, 15), p("فارس عايز أوصل قبل 8 وربع").deadline)
    @Test fun deadline_pm_marker() = assertEquals(Deadline(20, 0), p("فارس عايز أوصل الساعة 8 مساء").deadline)
    @Test fun delay_threshold_digits() = assertEquals(5, p("فارس لو التأخير أكتر من 5 دقايق غير الطريق").trafficRule!!.rerouteIfDelayMinutes)
    @Test fun delay_threshold_words() = assertEquals(10, p("فارس لو فيه زحمة أكتر من عشرة دقايق غير الطريق").trafficRule!!.rerouteIfDelayMinutes)
    @Test fun arabic_numbers() { assertEquals("2026", ArabicText.digits("٢٠٢٦")); assertEquals(8, ArabicText.number("تمانية")) }

    // ---- media / calls -------------------------------------------------------------------------
    @Test fun music() {
        assertEquals(VoiceCommand.Play, first("فارس شغل الموسيقى"))
        assertEquals(VoiceCommand.MusicSearch("عمرو دياب"), first("فارس شغل عمرو دياب"))
        assertEquals(VoiceCommand.Next, first("فارس الأغنية اللي بعدها"))
        assertEquals(VoiceCommand.VolumeDown, first("فارس وطي الصوت"))
        assertEquals(VoiceCommand.Pause, first("فارس وقف الموسيقى"))
    }
    @Test fun calls() {
        assertEquals(VoiceCommand.Call("أحمد"), first("فارس كلم أحمد"))
        assertEquals(VoiceCommand.Call("محمود"), first("فارس اتصل بمحمود"))
    }

    // ---- two-stage wake / duplicates -----------------------------------------------------------
    private var now = 1_000L
    private fun convo() = DriveConversation({ now })

    @Test fun two_stage_wake() {
        now = 1_000L; val c = convo()
        assertTrue(c.onSpeech("فارس") is DriveConversation.Result.WakeAck)
        now += 3_000
        val r = c.onSpeech("خدني المطار")
        assertTrue(r is DriveConversation.Result.Command)
        assertEquals("المطار", (r as DriveConversation.Result.Command).utterance.destination)
    }
    @Test fun one_shot_wake() {
        now = 1_000L; val c = convo()
        assertTrue(c.onSpeech("فارس خدني المطار") is DriveConversation.Result.Command)
    }
    @Test fun window_expires() {
        now = 1_000L; val c = convo()
        c.onSpeech("فارس"); now += 20_000
        assertTrue(c.onSpeech("خدني المطار") is DriveConversation.Result.Ignored)
    }
    @Test fun speech_without_wake_is_ignored() {
        now = 1_000L; assertTrue(convo().onSpeech("خدني المطار") is DriveConversation.Result.Ignored)
    }
    @Test fun duplicate_result_is_ignored() {
        now = 1_000L; val c = convo()
        assertTrue(c.onSpeech("فارس خدني المطار") is DriveConversation.Result.Command)
        now += 500
        assertTrue(c.onSpeech("فارس خدني المطار") is DriveConversation.Result.Ignored)
    }
    @Test fun repeated_wake_word_keeps_one_window() {
        now = 1_000L; val c = convo()
        c.onSpeech("فارس"); now += 3_100; c.onSpeech("فارس")
        now += 3_000
        assertTrue(c.onSpeech("ضيف بنزين") is DriveConversation.Result.Command)
    }

    // ---- trip context --------------------------------------------------------------------------
    private fun run(s: TripState, text: String) = TripPlanner.apply(s, p(text))

    @Test fun navigate_then_reroute_uses_current_trip() {
        val a = run(TripState(), "فارس خدني المطار")
        assertTrue(a.effects.first() is Effect.StartNavigation)
        val b = run(a.state, "غير الطريق")
        assertTrue(b.effects.any { it is Effect.SwitchToAlternative })
        assertEquals("المطار", b.state.destination)
    }
    @Test fun navigate_then_add_fuel_rebuilds_route() {
        val a = run(TripState(), "فارس خدني المطار")
        val b = run(a.state, "ضيف بنزين")
        assertEquals(listOf(PlaceKind.FUEL), b.state.stops.map { it.kind })
        assertTrue(b.effects.any { it is Effect.RebuildRoute })
    }
    @Test fun multi_stop_trip_preserves_order() {
        val r = run(TripState(), "فارس خدني المطار وفي النص هاتلي بنزين وقهوة")
        assertEquals(listOf(PlaceKind.FUEL, PlaceKind.COFFEE), r.state.stops.map { it.kind })
        assertTrue(r.effects.first() is Effect.StartNavigation)
    }
    @Test fun compound_sets_deadline_and_rule_on_state() {
        val r = run(TripState(), "فارس خدني المطار، وأنا مستعجل، وعايز أوصل قبل 8، لو الطريق زحمة غير الطريق")
        assertEquals(Deadline(8, 0), r.state.deadline)
        assertNotNull(r.state.trafficRule)
        assertTrue(r.state.hurry)
        assertTrue(r.effects.any { it is Effect.EvaluateDeadline })
    }
    @Test fun cancel_clears_trip() {
        val a = run(TripState(), "فارس خدني المطار")
        val b = run(a.state, "ألغي الرحلة")
        assertFalse(b.state.isActive)
        assertTrue(b.effects.any { it is Effect.CancelTrip })
    }
    @Test fun commands_without_trip_answer_honestly() {
        val r = run(TripState(), "غير الطريق")
        assertTrue(r.effects.single() is Effect.Speak)
        val e = run(TripState(), "هوصل إمتى")
        assertTrue(e.effects.single() is Effect.Speak)
    }
    @Test fun nearby_never_changes_stops() {
        val a = run(TripState(), "فارس خدني المطار")
        val b = run(a.state, "فين أقرب بنزين")
        assertTrue(b.state.stops.isEmpty())
        assertTrue(b.effects.single() is Effect.SearchNearby)
    }
    @Test fun unknown_speech_is_not_executed() = assertTrue(p("فارس بلا بلا بلا").isUnknown)

    // ---- ETA / deadline / traffic / off-route / trip math --------------------------------------
    private val zone = ZoneOffset.UTC
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 7, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun deadline_late() {
        val v = DeadlineMath.evaluate(Deadline(8, 0), 75 * 60L, at(7, 0), zone)
        assertEquals(15, v.lateMinutes); assertFalse(v.onTime); assertEquals("8:15", v.arrivalText)
        assertTrue(DeadlineMath.speak(v).contains("15"))
    }
    @Test fun deadline_on_time() = assertTrue(DeadlineMath.evaluate(Deadline(8, 0), 30 * 60L, at(7, 0), zone).onTime)
    @Test fun evening_deadline_resolves_to_pm() {
        val due = DeadlineMath.resolve(Deadline(8, 0), ZonedDateTime.of(2026, 10, 7, 19, 0, 0, 0, zone))
        assertEquals(20, due.hour)
    }
    @Test fun reroute_threshold() {
        val rule = TrafficRule(5, auto = false)
        assertEquals(RerouteDecision.Kind.SUGGEST, RerouteDecision.evaluate(2400, 1980, rule, 1_000_000, 0).kind)
        assertEquals(RerouteDecision.Kind.NONE, RerouteDecision.evaluate(2400, 2300, rule, 1_000_000, 0).kind)
        assertEquals(RerouteDecision.Kind.NONE, RerouteDecision.evaluate(2400, 1000, null, 1_000_000, 0).kind)
        assertEquals(RerouteDecision.Kind.AUTO, RerouteDecision.evaluate(2400, 1980, rule.copy(auto = true), 1_000_000, 0).kind)
    }
    @Test fun reroute_cooldown() =
        assertEquals(RerouteDecision.Kind.NONE, RerouteDecision.evaluate(2400, 1000, TrafficRule(5), 100_000, 50_000).kind)
    @Test fun off_route_needs_consecutive_fixes() {
        val d = OffRouteDetector()
        assertFalse(d.onFix(200.0, 10f)); assertFalse(d.onFix(200.0, 10f)); assertTrue(d.onFix(200.0, 10f))
    }
    @Test fun off_route_ignores_gps_spike_and_bad_accuracy() {
        val d = OffRouteDetector()
        assertFalse(d.onFix(500.0, 10f)); assertFalse(d.onFix(5.0, 10f)); assertFalse(d.onFix(500.0, 10f))
        repeat(5) { assertFalse(d.onFix(900.0, 200f)) }
    }
}
