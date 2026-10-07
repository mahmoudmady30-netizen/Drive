# DriveVoice — MVP 0.2 (Fares Voice Edition)

Voice-first driving assistant for Android, designed around a driver-safe dark UI.

## Wake word
The wake word is **فارس**. In wake mode, speech is ignored unless the recognized phrase contains “فارس”. Examples:
- “فارس، وديني دبي مول”
- “فارس، أقرب محطة بنزين”
- “فارس، شغل الموسيقى”
- “فارس، التالي”
- “فارس، وطي الصوت”
- “فارس، كم فاضل على الوصول؟”
- “فارس، اتصل بمحمود”
- “فارس، الغي الطريق”

## 0.2 additions
- Turn-by-turn spoken maneuver prompts from routing steps.
- Live GPS updates while a route is active.
- Android Auto navigation `CarAppService` scaffold using AndroidX Car App 1.7.0.
- Navigation intent filters for `geo:` and Android Auto navigation intents.
- Responsive orientation (portrait/landscape).
- Voice UI wording updated to “فارس”.

## Build
GitHub Actions uses Gradle 8.7 and Java 17 and outputs `app-debug.apk`.

## Production notes
The wake-word layer is currently a prototype based on Android speech recognition sessions. For a commercial always-on wake word, replace it with an on-device wake-word engine plus a properly designed foreground audio service.

The map/search endpoints are demo infrastructure. Production should use a dedicated tile, geocoding and routing provider with explicit usage limits and terms.

Android Auto navigation is driver-distraction constrained and must be validated with the Android Auto Desktop Head Unit before Play submission.


## v0.3 — Strong driving architecture
- Voice engine moved to a foreground microphone service so the voice layer can remain active while the map screen is not focused.
- Android 14+ microphone foreground-service permissions and notification channel included.
- Wake word remains **فارس**.
- Expanded Egyptian-Arabic command parser: navigation, nearby search, calls, music, volume, mute, cancel, repeat, ETA, and location.
- Automatic route recalculation when the vehicle appears significantly off-route, with a cooldown to avoid repeated rerouting.
- Android Auto navigation service remains included as the car integration foundation. Full host-validated turn-by-turn Trip/NavigationManager integration is the next production-hardening layer.

### Important Android behavior
The app starts the microphone foreground service from the visible Activity after `RECORD_AUDIO` is granted. Android 14+ requires the `microphone` foreground-service type and `FOREGROUND_SERVICE_MICROPHONE`; background starts are restricted.

### Production note
The current wake word is still a speech-recognition based prototype, not a dedicated always-on keyword detector. For a commercial release, replace this layer with a purpose-built on-device wake-word engine and keep the foreground service only for the supported capture/session lifecycle.


## v0.4 PRODUCTION CAR jump
- Shared in-process navigation state between phone and Android Auto.
- Real `NavigationManager.navigationStarted/navigationEnded/updateTrip` integration.
- Android Auto `Trip` with destination, turn-by-turn `Step`/`Maneuver`, travel estimates and current road.
- GPS position and current step are propagated into the car navigation state.
- Host `onStopNavigation` is handled and clears active navigation state.
- Wake word remains **فارس**.

This is the production-car foundation; a real map surface on the car display and dedicated traffic/offline routing providers remain separate provider/device integration work.

## v0.4 verification checklist
- Android Auto declares the navigation category and navigation template permission.
- `NavigationManager` lifecycle is wired: callback registration, navigation start/end, and `updateTrip`.
- `Trip` contains a destination, remaining travel estimate, up to 8 upcoming turn steps, maneuver types, and current road.
- Phone GPS updates shared navigation state; Android Auto receives the current step and recalculated remaining estimate.
- Host stop-navigation events terminate active routing state.
- Unit tests cover core Arabic wake-word command parsing.


## v0.5 DRIVE AI

This release adds a local driving-intent layer designed for no-touch driving. The wake word remains **فارس**. Natural Egyptian/Arabic phrasing is normalized locally, so the core commands do not require an AI API key.

New intents include nearby fuel, coffee, food, parking and EV charging, adding a stop to an active route, rerouting/alternative route requests, and broader conversational phrasing.

The app can add a stop using a three-point OSRM route (current position → stop → destination). This is an MVP route-chain implementation; traffic-aware routing and provider-grade live traffic require a traffic-enabled production map provider.

Android microphone foreground-service behavior follows current Android 14+ restrictions: RECORD_AUDIO must be granted and the microphone foreground service must be started from an allowed visible/user-initiated context.

The app continues to use external media key events for universal play/pause/next/previous control. A future provider-specific media integration can use Media3 MediaController/MediaSession where the target media app exposes a compatible session.

## v0.7 DRIVE AI + Deep Verification

This release adds provider-backed alternative routing (`alternatives=true`), multi-stop route step parsing, arrival-deadline intent (`"فارس عايز أوصل قبل الساعة 8"`), and a safer Android Auto host validator for release builds. The alternative route feature is **not** advertised as live-traffic-aware unless the routing provider supplies traffic data.

### Deep verification

- `tools/deep_feature_audit.sh` checks manifest permissions, Android Auto navigation hooks, wake-word presence, route alternatives, multi-stop routing, media controls, and test assets.
- `app/src/test/.../DeepFeatureTest.kt` covers Arabic/Egyptian navigation, POI categories, rerouting, alternatives, ETA, arrival deadline, media controls, volume, mute, calls, cancellation, and location intent.
- CI now runs the static audit, `testDebugUnitTest`, and `assembleDebug`.

### Important production constraints

Android 14+ requires the correct foreground-service type and permission for microphone capture; `RECORD_AUDIO` is also required, and background-start restrictions still apply. Start the microphone service while the app is visible or through an allowed Android mechanism. See Android's official foreground-service documentation.

`HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` is retained only for debuggable builds. Release builds use the AndroidX sample host allow-list baseline. Production signing/review should still validate Android Auto hosts and Play policy requirements.

The current routing/geocoding endpoints are demo/public infrastructure. A commercial release should move to a dedicated provider/backend with explicit rate limits, attribution, caching, reliability monitoring, and traffic data where available.

## v0.8 — FARES AI CORE

**فارس** now has a local conversational planning layer for compound driving requests. One utterance can produce a multi-step `DrivingPlan` instead of a single `VoiceAction`.

Examples:
- `فارس أنا مستعجل وعايز أوصل قبل ٨، ولو الطريق فيه زحمة خد طريق تاني، وفي النص هاتلي بنزين وقهوة`
- `فارس وديني دبي مول، ولو الزحمة أكتر من عشر دقايق غير الطريق`
- `فارس خدني المطار وفي النص هاتلي بنزين وقهوة`
- `فارس وديني المطار، شغل هادي`

The planner extracts:
- destination
- arrival deadline
- traffic/reroute threshold
- ordered stop categories (fuel, coffee, food, parking, charging)
- music search request
- phone call request

The executor first resolves the destination and a base route, then searches for requested POIs near proportional points on that route and requests one OSRM route containing all resolved stops. If a live traffic provider is configured, the plan can automatically request an alternative route when the configured delay threshold is exceeded. If no live traffic provider is configured, فارس explicitly says so instead of fabricating traffic data.

This is intentionally local/deterministic in v0.8: no AI API key is required. A future release can add an optional LLM layer for harder conversational ambiguity while retaining the deterministic safety-critical execution layer.

## v0.9 FARES REAL DRIVER AI
- Stateful local conversation context across voice turns.
- Follow-up instructions can modify the current trip without repeating the destination.
- Remembers destination, deadline, traffic threshold, stops, music and call intent.
- Short confirmations such as “تمام” preserve the current trip context.
- “الغى الرحلة” clears the conversation state.
- No external AI API key required.
