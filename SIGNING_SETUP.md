# DriveVoice release signing

The repository does not contain a private signing key. This is intentional: a production keystore must never be committed to Git.

Create four GitHub Actions repository secrets:

- `DRIVEVOICE_KEYSTORE_B64`: base64 of the `.jks` keystore
- `DRIVEVOICE_STORE_PASSWORD`: keystore password
- `DRIVEVOICE_KEY_ALIAS`: key alias
- `DRIVEVOICE_KEY_PASSWORD`: key password

The workflow will then build and verify a signed `DriveVoice` release APK with `apksigner`.

For local builds, put the same Gradle properties in a local-only `gradle.properties` or pass them from CI. Never commit passwords or the keystore.
