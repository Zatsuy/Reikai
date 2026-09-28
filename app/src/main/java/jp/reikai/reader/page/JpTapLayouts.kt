package jp.reikai.reader.page

import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import reikai.domain.novel.NovelTapLayout
import reikai.presentation.reader.NovelTapAction
import reikai.presentation.reader.NovelTapZones
import reikai.presentation.reader.navigation.BottomNavigation
import reikai.presentation.reader.navigation.CenterNavigation
import reikai.presentation.reader.navigation.ThirdsNavigation

/**
 * The reader's tap zones as rectangles, read off the same navigation layouts `NovelTapZones` answers
 * from (its own navigation is private), inversion applied.
 */
internal fun NovelTapZones.toJpTapLayout(): JpTapLayout {
    val navigation: ViewerNavigation = when (layout) {
        NovelTapLayout.DISABLED -> DisabledNavigation()
        NovelTapLayout.THIRDS -> ThirdsNavigation()
        NovelTapLayout.L_SHAPED -> LNavigation()
        NovelTapLayout.KINDLISH -> KindlishNavigation()
        NovelTapLayout.EDGE -> EdgeNavigation()
        NovelTapLayout.RIGHT_AND_LEFT -> RightAndLeftNavigation()
        NovelTapLayout.CENTER -> CenterNavigation()
        NovelTapLayout.CENTER_LARGE -> CenterNavigation(large = true)
        NovelTapLayout.BOTTOM -> BottomNavigation(bottomZoneHeightPercent / 100f)
    }.also { it.invertMode = invert }
    val zones = navigation.getRegions().map { region ->
        JpTapZone(
            left = region.rectF.left,
            top = region.rectF.top,
            right = region.rectF.right,
            bottom = region.rectF.bottom,
            action = when (region.type) {
                NavigationRegion.MENU -> NovelTapAction.MENU
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> NovelTapAction.FORWARD
                NavigationRegion.PREV, NavigationRegion.LEFT -> NovelTapAction.BACK
            },
        )
    }
    return JpTapLayout(zones, zoneOnly = layout.isZoneOnly)
}
