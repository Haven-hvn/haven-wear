package haven.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import dagger.hilt.android.AndroidEntryPoint
import haven.wear.MainActivity
import haven.wear.playback.LastPlayedStore
import javax.inject.Inject

/**
 * One glance, one tap: what you were listening to, and a button that carries on.
 *
 * Plain protolayout (no Material tile components) so it depends on as little API surface as
 * possible. Colours match the app theme.
 */
@OptIn(ExperimentalHorologistApi::class)
@AndroidEntryPoint
class ResumeTileService : SuspendingTileService() {

    @Inject lateinit var lastPlayed: LastPlayedStore

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources =
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val last = lastPlayed.read()
        val root = if (last == null) {
            column(
                text("Haven", 16f, WHITE, bold = true),
                spacer(6f),
                text("Nothing played yet", 13f, MUTED),
                spacer(12f),
                pill("Open", launch(resume = false)),
            )
        } else {
            column(
                text("Continue listening", 12f, MUTED),
                spacer(6f),
                text(last.title.ifBlank { "Untitled" }, 17f, WHITE, bold = true, maxLines = 2),
                text(last.artist, 13f, MUTED),
                spacer(14f),
                pill("Resume", launch(resume = true)),
            )
        }
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(0)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    private fun column(vararg children: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .apply { children.forEach { addContent(it) } }
                    .build(),
            )
            .build()

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 1) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(maxLines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()

    private fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private fun pill(label: String, onClick: ActionBuilders.Action) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(wrap())
            .setHeight(dp(44f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId("resume").setOnClick(onClick).build())
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(ACCENT))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(22f)).build())
                            .build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(22f)).setEnd(dp(22f)).build())
                    .build(),
            )
            .addContent(text(label, 15f, ON_ACCENT, bold = true))
            .build()

    private fun launch(resume: Boolean): ActionBuilders.Action =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_RESUME, ActionBuilders.AndroidBooleanExtra.Builder().setValue(resume).build())
                    .build(),
            )
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val MUTED = 0xFFA7A3A0.toInt()
        const val ACCENT = 0xFFFF7A59.toInt()
        const val ON_ACCENT = 0xFF1A0E0A.toInt()
    }
}
