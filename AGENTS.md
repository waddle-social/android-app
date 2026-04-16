# waddle android-app Development Guidelines

- Use `jj` when `.jj` exists, `git` otherwise.
- XMPP Native. Never use out-of-band / non-XMPP APIs where an XEP exists; REST is only for operations XMPP does not cover (provider discovery, file upload slots, member management).

## Active Technologies

- Kotlin 2.3.x, AGP 9.1.x, Android SDK 36 (min SDK 26), JVM 17
- Jetpack Compose (Material3), Navigation3, Hilt 2.59.x, Room 2.8.x, DataStore
- Ktor 3.4.x (HTTP), Kotlinx Serialization 1.11.x, Kotlinx Coroutines 1.10.x
- Smack 4.5.x (XMPP over WebSocket + OkHttp), AppAuth 0.11.x (OAuth 2.0 / OIDC)
- Detekt 2.0.x, ktlint 1.8.x

## Project Structure

```text
app/src/main/kotlin/social/waddle/android/
  auth/        OAuth + secure session storage
  data/        repositories, Room DB, network models
  di/          Hilt modules
  ui/          Compose screens + theme
  xmpp/        Smack client + XEP extensions
```

## Commands

`./gradlew verify` (runs ktlint, detekt, lint, unit tests).

- Strict warnings hard rule:
  - `allWarningsAsErrors = true` for Kotlin and `warningsAsErrors = true` for Android lint.
  - Never silence a warning with `@Suppress` unless the underlying problem is impossible to fix without a deeper refactor.

- ktlint + detekt hard rule:
  - Every PR MUST pass `./gradlew verify` locally before review.
  - Keep the detekt config strict (`LongMethod` 90, `MaxLineLength` 140); prefer refactors over raising thresholds.

- XEP parity hard rule:
  - Android features MUST advertise the same XEPs the server implements (XEP-0184, 0333, 0308, 0424, 0444, 0363, 0447, 0085, 0198, 0030).
  - Register new XEP support through `XmppFeatureRegistry` so compliance checks stay honest.
  - Any XEP added to the server SHOULD land on Android in the same release cycle; advertise only what actually works.

- XMPP message hard rule:
  - Never construct XMPP stanzas with string concatenation. Use Smack's builders / PacketExtension classes.

## Code Style

Kotlin: follow the detekt + ktlint configuration committed in `config/detekt/detekt.yml`. Compose composables use `@Composable` + `Modifier` as the first default parameter where the composable accepts layout modifiers.

- Breaking changes by default: do not add backwards-compatibility shims, renamed-variable aliases, or legacy migration paths unless explicitly requested.
- Assume no production servers / users / data for this project; prioritize clean design over compatibility.
- Keep the codebase clean: remove dead compatibility code immediately instead of preserving legacy paths.
