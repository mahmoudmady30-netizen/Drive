# DriveVoice v0.7 Deep Test Plan

## Static checks
- Kotlin source scan
- XML resource parse
- Manifest permission/service declarations
- No placeholder `$needle` / TODO compile markers
- ZIP integrity

## Deterministic unit tests
- Arabic wake-word command normalization
- Navigation / AddStop precedence
- Traffic delay threshold and ETA
- Deadline calculation
- Media search intent creation path

## Runtime limitations
A full Android Gradle build requires Android SDK + Gradle dependency resolution. This environment cannot guarantee a device/emulator build. GitHub Actions remains the authoritative build gate.

## Production traffic
Live traffic is intentionally provider-neutral. With `TRAFFIC_ENDPOINT` blank, the app never pretends that OSRM has live traffic. Configure a licensed traffic provider endpoint/key for real-time traffic.
