# Reikai JP changelog

Changes you will notice in the app, newest first. Upstream Reikai's own changes are in its
[CHANGELOG.md](../../CHANGELOG.md); this file lists only what the fork adds or changes.

## Unreleased

### Browse
- Browse → Extensions → Filter now works for novels too: novel plugins and novel extension apps on
  offer only show in the languages you switch on there. It is one setting for manga and novels,
  so switching 日本語 on shows Japanese manga extensions and sources as well. Extensions you already
  installed always stay listed, whatever the filter says. After this update the languages of the
  plugins you already installed are switched on once, so none of their repos' plugins vanish.
  Browse → Sources → Filter → Novels still has its own switches for installed novels.

### Other
- The app is called Reikai JP and installs beside upstream Reikai. New versions are published on
  this fork's GitHub Releases and the app offers them itself ([install guide](install.md)).
- About shows the version as "Nightly r" plus a number (higher is newer) and its GitHub link
  opens this fork. Like
  upstream's nightly builds, the app has its own icon colour and a few developer options.
- The fork is based on upstream Reikai's newest development branch (0.4.0 in progress).
- The fork runs none of upstream's publishing pipelines; its own checks run on GitHub instead.
- Licence: GPL-3.0-or-later (upstream's Apache-2.0 notices kept).
