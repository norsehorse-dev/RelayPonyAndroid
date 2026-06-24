# Contributing to RelayPony

Thanks for your interest in RelayPony. Contributions of all kinds are welcome: bug reports, fixes, translation review, and documentation.

## Getting set up

See the **Building** section of the README. In short: JDK 17, the Android SDK (compileSdk 36), and a clone of `AgePonyAndroid` beside this repository for the composite build. Then `./gradlew :app:assembleDebug`.

## Pull requests

- Keep changes focused; one logical change per PR is easiest to review.
- Match the existing Kotlin and Compose style in the surrounding code.
- The on-wire format is intentionally frozen. If a change touches it, bump `WIRE_VERSION` and explain the compatibility story in the PR.
- For UI strings, add the key to `app/src/main/res/values/strings.xml` and, where you can, the other locale files.

## Translations

Each language is one file: `app/src/main/res/values-<locale>/strings.xml`. Native-speaker review of the existing translations is especially valuable — open a PR against the relevant file, keeping the `%1$s` / `%1$d` placeholders intact.

## Reporting bugs

Please include the device model, Android version, and whether the transfer used local Wi-Fi or Wi-Fi Direct. Steps to reproduce a stalled or failed transfer are the most useful thing you can provide.

## Security

For anything that looks like a security or privacy issue, please report it privately to NorseHorse@norsehor.se rather than opening a public issue.
