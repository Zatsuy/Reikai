package jp.reikai

/**
 * Where Reikai JP is published. Its GitHub Releases feed the in-app updater (the fork ships the
 * nightly build type, tagged r<commit count> by .github/workflows/fork-release.yml) and its page is
 * the About screen's GitHub link.
 */
object ForkRelease {
    const val REPO = "Zatsuy/Reikai"
    const val REPO_URL = "https://github.com/$REPO"
}
