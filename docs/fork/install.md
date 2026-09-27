# Install Reikai JP and keep it updated

Reikai JP installs beside upstream Reikai: two separate apps with separate libraries. You install
it once by hand; after that the app offers each new version itself.

## First install (once)

1. On the tablet or phone, open
   [github.com/Zatsuy/Reikai/releases/latest](https://github.com/Zatsuy/Reikai/releases/latest) in
   the browser.
2. Under **Assets**, tap `reikai-jp-arm64-v8a-r….apk` (right for almost every recent device; the
   file without `arm64-v8a` in its name works on any device but is larger).
3. When the download finishes, tap **Open**. Android says the browser is not allowed to install
   apps: tap **Settings**, turn on **Allow from this source**, go back.
4. Tap **Install**. Google Play Protect may ask to scan the app: tap **Scan app** or **Install
   without scanning**, either works.
5. Open **Reikai JP**. You should see the usual Reikai start screen; the app name under the icon
   reads "Reikai JP".

*Your library from upstream Reikai:* in upstream Reikai, **More > Backup and restore > Create
backup**; in Reikai JP, **More > Backup and restore > Restore backup** and pick that file.

## Updates (automatic)

A new version is published at most once a day, when the app changed. When you open Reikai JP and a
newer one exists, a **new version** screen appears:

1. Tap **Download**, then **Install** when it finishes.
2. The first time only, Android asks to allow Reikai JP to install apps: tap **Settings**, turn on
   **Allow from this source**, go back, tap **Install** again.

To check by hand: **More > About > Check for updates**. The version there reads `Nightly r<number>`;
a higher number is newer.

## If something goes wrong

- *"App not installed" or "conflicts with an existing package":* a Reikai JP signed differently is
  already installed (for example a developer build). Back up, uninstall it, install again.
- *No update screen although a newer release exists:* open **More > About > Check for updates**; if
  that says there is none, an agent can look into it (`/debug`).
