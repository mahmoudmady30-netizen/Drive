# FARES v0.9 — Deep Code Review & Hardening

## Findings fixed

### P0 / build-blocking
- `CommandParser.kt` had an invalid `when` branch for music-search parsing: a `let {}` expression was placed directly as a `when` condition without `->`. This could prevent Kotlin compilation. Reworked into a deterministic pre-check.

### P1 / behavior correctness
- Follow-up commands such as `غير الطريق`, `كم باقي`, `حالة الطريق`, and media controls could be treated as conversational filler after a destination because the conversation engine did not preserve direct intents. Added `directAction` handling.
- Nearby searches such as `فين أقرب محطة بنزين` could accidentally become automatic route stops because stop extraction matched any mention of fuel/coffee. Stop extraction now requires explicit stop intent.
- Arabic-Indic digits (`٨`, `١٠`) are normalized.
- Common Arabic number words are supported for traffic thresholds (`عشر`, `عشرين`, `ثلاثين`, etc.) plus quarter/half-hour phrases.
- Destination parsing now stops before deadline phrases such as `قبل ٨` / `الساعة ٨`.

### P1 / security & lifecycle hardening
- Disabled application backup for a navigation/voice app.
- Disabled cleartext network traffic.
- TTS calls are lifecycle guarded and marshalled to the UI thread.
- Receiver unregister is defensive.
- Contacts and phone-call permissions are not required during initial app startup; they remain on-demand for calling.

### P2 / CI quality
- Corrected CI artifact name from the old v0.6 label to v0.9.
- Added production-hardening tests.

## Verification performed
- Deep feature audit: PASS.
- XML parsing: PASS.
- Kotlin structural balance: PASS.
- Core Kotlin compilation (`CommandParser`, `DriveAiCore`, `FaresConversationEngine`): PASS.
- Deterministic smoke suite: PASS (`SMOKE_OK`).

## Not claimed
A complete Android Gradle build was not executed in this environment because a complete Android SDK/Gradle dependency environment is unavailable here. GitHub Actions remains the authoritative full Android build/test gate.
