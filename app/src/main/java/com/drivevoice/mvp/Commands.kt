package com.drivevoice.mvp

enum class PlaceKind(val searchQuery: String, val label: String) {
    FUEL("gas station", "محطة بنزين"),
    COFFEE("cafe", "قهوة"),
    FOOD("restaurant", "مطعم"),
    PARKING("parking", "موقف"),
    CHARGING("EV charging station", "محطة شحن")
}

data class Deadline(val hour: Int, val minute: Int) {
    override fun toString() = "%02d:%02d".format(hour, minute)
}

data class TrafficRule(val rerouteIfDelayMinutes: Int, val auto: Boolean = false)

sealed class VoiceCommand {
    data class Navigate(val destination: String) : VoiceCommand()
    data class SearchPlace(val query: String) : VoiceCommand()
    data class Nearby(val kind: PlaceKind) : VoiceCommand()       // search only, never adds a stop
    data class AddStop(val kind: PlaceKind?, val query: String? = null) : VoiceCommand()
    data object RemoveStop : VoiceCommand()
    data object Reroute : VoiceCommand()
    data class TrafficAlternative(val fastest: Boolean = false) : VoiceCommand()
    data object TrafficStatus : VoiceCommand()
    data class ArrivalBy(val deadline: Deadline) : VoiceCommand()
    data class SetTrafficRule(val rule: TrafficRule) : VoiceCommand()
    data object Hurry : VoiceCommand()
    data object Eta : VoiceCommand()
    data object WhereAmI : VoiceCommand()
    data object CancelRoute : VoiceCommand()
    data object Repeat : VoiceCommand()
    data object Play : VoiceCommand()
    data object Pause : VoiceCommand()
    data object Next : VoiceCommand()
    data object Previous : VoiceCommand()
    data object VolumeUp : VoiceCommand()
    data object VolumeDown : VoiceCommand()
    data object Mute : VoiceCommand()
    data class MusicSearch(val query: String) : VoiceCommand()
    data class Call(val name: String) : VoiceCommand()
    data object Unknown : VoiceCommand()
}

/** Result of parsing one utterance. [commands] preserves spoken order; stops keep their order. */
data class ParsedUtterance(
    val raw: String,
    val hadWakeWord: Boolean,
    val commands: List<VoiceCommand>
) {
    val destination: String? get() = commands.filterIsInstance<VoiceCommand.Navigate>().firstOrNull()?.destination
    val stops: List<VoiceCommand.AddStop> get() = commands.filterIsInstance<VoiceCommand.AddStop>()
    val deadline: Deadline? get() = commands.filterIsInstance<VoiceCommand.ArrivalBy>().firstOrNull()?.deadline
    val trafficRule: TrafficRule? get() = commands.filterIsInstance<VoiceCommand.SetTrafficRule>().firstOrNull()?.rule
    val isNavigation: Boolean get() = destination != null || deadline != null || VoiceCommand.Hurry in commands
    val isCompound: Boolean get() = commands.count { it != VoiceCommand.Unknown } > 1
    val isWakeOnly: Boolean get() = hadWakeWord && commands.isEmpty()
    val isUnknown: Boolean get() = commands.isNotEmpty() && commands.all { it == VoiceCommand.Unknown }
}
