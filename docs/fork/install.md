# Install Reikai JP and keep it updated

Reikai JP installs beside upstream Reikai: two separate apps with separate libraries. You install
it once by hand; after that the app offers each new version itself.

## First install (once)

1. On the tablet or phone, open
   [github.com/Zatsuy/Reikai/releases/latest](https://github.com/Zatsuy/Reikai/releases/latest) in
   the browser.
2. Under **Assets**, tap `reikai-jp-arm64-v8a-r….apk` (right for almost every recent device; the
   file without `arm64-v8a` in its name works on any device but is larger). If the browser warns
   that the file could be harmful, tap **Download anyway**.
3. When the download finishes, tap **Open**. Android says the browser is not allowed to install
   apps: tap **Settings**, turn on **Allow from this source**, go back.
4. Tap **Install**. Google Play Protect may ask to scan the app: either choice works.
5. Open **Reikai JP** (the icon's label reads "Reikai JP"). It starts with a **Welcome!** setup:
   tap **Next** through it. When it asks for a storage folder, pick a new one (for example
   `ReikaiJP`) rather than upstream Reikai's, so the two apps keep their downloads apart.

*Your library from upstream Reikai:* in upstream Reikai, **More > Data and storage > Create
backup**. Then in Reikai JP, tap **Restore backup** on the setup's last page, or later **More > Data
and storage > Restore backup**, and pick that file.

## Updates (automatic)

A new version is published at most once a day, when the app changed. Each time Reikai JP starts
fresh (after you swipe it away from recent apps, or the tablet restarts), it looks for one; when it
finds one, a **New version available!** screen appears:

1. Tap **Download**, then **Install** when it finishes.
2. The first time only, Android asks to allow Reikai JP to install apps: tap **Settings**, turn on
   **Allow from this source**, go back.
3. Android asks whether to update the app: tap **Update**.

To check by hand at any time: **More > About > Check for updates**. The version there reads
`Nightly r<number>`; a higher number is newer.

## If something goes wrong

- *"App not installed" or "conflicts with an existing package":* a Reikai JP signed with a
  different key is already installed. Back up, uninstall it, install again.
- *No update screen although a newer release exists:* open **More > About > Check for updates**; if
  that says there is none, an agent can look into it (`/debug`).
