@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.lerp
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.ThemeMode
import org.southtyrol.transit.di.AppLanguage
import org.southtyrol.transit.ui.Navigator
import javax.inject.Inject

/** The theme as the search bar's menu shows and changes it. */
@HiltViewModel
class SearchBarMenuViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val theme: StateFlow<ThemeMode> = settings.settings.map { it.theme }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** System, then light, then dark, then back to the system's. */
    fun nextTheme() {
        val next = when (theme.value) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        viewModelScope.launch { settings.setTheme(next) }
    }
}

/**
 * The app mark at the end of the map's search bar. A tap reports where it is ([onOpen], in root
 * coordinates); the map screen then shows [SearchBarMenu] over the map, from that spot.
 */
@Composable
fun SearchBarMenuButton(onOpen: (Rect) -> Unit) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
        MarkButton(progress = 0f, label = stringResource(R.string.map_menu), onClick = { onOpen(anchor) })
    }
}

/**
 * The menu the app mark opens: Saved, the language, the theme and Settings. The mark itself morphs into
 * the menu's close button. A Material FAB menu rising from it when the bar is at the bottom; the same
 * items dropping below it when the bar is at the top, which Material's menu cannot do. Language turns
 * the menu into the choices; the theme changes at once and the menu stays.
 *
 * Drawn in the map screen's own layout, over the map, like the locate button's menu: a popup window,
 * created on every opening and first sized to its content, made the opening stutter and the items appear
 * to slide in from the screen's edge.
 */
@Composable
fun SearchBarMenu(navigator: Navigator, opensUp: Boolean, anchor: Rect, onClose: () -> Unit, viewModel: SearchBarMenuViewModel = hiltViewModel()) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Collapsed for the first frame, so the items grow in and the mark morphs; collapsed again to close.
    var expanded by remember { mutableStateOf(false) }
    var languages by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { expanded = true }
    val progress by animateFloatAsState(if (expanded) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "mark")
    // Back to the map: the menu folds away and the close button turns back into the mark.
    fun dismiss() {
        expanded = false
        scope.launch {
            delay(CLOSE_MS)
            onClose()
        }
    }
    // Away from the map: gone at once, so nothing of it lingers over the next screen.
    fun leave(then: () -> Unit) {
        onClose()
        then()
    }
    BackHandler(onBack = ::dismiss)
    val items = if (languages) languageItems(onPick = { option -> leave { AppLanguage.select(option) } }, onBack = { languages = false })
    else mainItems(
        theme,
        onSaved = { leave(navigator::saved) },
        onLanguage = { languages = true },
        onTheme = viewModel::nextTheme,
        onSettings = { leave(navigator::settings) },
    )
    val closeButton: @Composable () -> Unit = { MarkButton(progress, stringResource(R.string.map_menu_close), onClick = ::dismiss) }
    Box(Modifier.fillMaxSize()) {
        // The rest of the map: a tap there closes the menu instead of reaching the map.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
        AtAnchor(anchor, opensUp) {
            if (opensUp) {
                FloatingActionButtonMenu(expanded = expanded, button = closeButton, horizontalAlignment = Alignment.End) {
                    items.forEach { item ->
                        FloatingActionButtonMenuItem(
                            onClick = item.onClick,
                            text = { Text(item.label) },
                            icon = { MenuIcon(item.icon) },
                            modifier = Modifier.menuItemSemantics(item),
                            containerColor = item.container(),
                            contentColor = contentColorFor(item.container()),
                        )
                    }
                }
            } else {
                // Mirrored: the close button over the mark, the items below it, the first at the top.
                Column(Modifier.padding(horizontal = MENU_PADDING), horizontalAlignment = Alignment.End) {
                    closeButton()
                    AnimatedVisibility(
                        expanded,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items.reversed().forEach { MenuPill(it) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Places [content] so its button lands on the mark ([anchor], in root coordinates, less this layout's
 * own position; nothing is drawn until that is known): Material's menu pads 16 dp beside and below its
 * button, the downward column 16 dp beside it.
 */
@Composable
private fun AtAnchor(anchor: Rect, opensUp: Boolean, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf<Offset?>(null) }
    Layout(
        content,
        Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
            .graphicsLayer { alpha = if (origin == null) 0f else 1f },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val padding = MENU_PADDING.roundToPx()
        val at = anchor.translate(-(origin ?: Offset.Zero))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = at.right.roundToInt() + padding - placeable.width
            val y = if (opensUp) at.bottom.roundToInt() + padding - placeable.height else at.top.roundToInt()
            placeable.place(x, y)
        }
    }
}

/** One entry of the menu: a page, a quick setting, or a choice. */
private class MenuItem(val label: String, val icon: ImageVector?, val onClick: () -> Unit, val selected: Boolean = false, val description: String? = null) {
    @Composable
    fun container(): Color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
}

/** TalkBack: the item's full description where its label alone is a value ("English"), and its selection. */
private fun Modifier.menuItemSemantics(item: MenuItem): Modifier = semantics {
    item.description?.let { contentDescription = it }
    if (item.selected) stateDescription = item.label
}

/** The main items, top to bottom as Material's menu lists them: Saved, language, theme, Settings. */
@Composable
private fun mainItems(theme: ThemeMode, onSaved: () -> Unit, onLanguage: () -> Unit, onTheme: () -> Unit, onSettings: () -> Unit): List<MenuItem> {
    val language = languageName(AppLanguage.selected())
    val themeName = stringResource(
        when (theme) {
            ThemeMode.SYSTEM -> R.string.theme_system
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
        },
    )
    val themeIcon = when (theme) {
        ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Rounded.LightMode
        ThemeMode.DARK -> Icons.Rounded.DarkMode
    }
    return listOf(
        MenuItem(stringResource(R.string.settings_saved), Icons.Rounded.Star, onSaved),
        MenuItem(language, Icons.Rounded.Translate, onLanguage, description = stringResource(R.string.map_menu_language, language)),
        // Stands out while the theme differs from the system's.
        MenuItem(themeName, themeIcon, onTheme, selected = theme != ThemeMode.SYSTEM, description = stringResource(R.string.map_menu_theme, themeName)),
        MenuItem(stringResource(R.string.settings_title), Icons.Rounded.Settings, onSettings),
    )
}

/** The language choices, each named in its own language, the phone's language last, then Back. */
@Composable
private fun languageItems(onPick: (AppLanguage.Option) -> Unit, onBack: () -> Unit): List<MenuItem> {
    val current = AppLanguage.selected()
    val order = listOf(AppLanguage.Option.ENGLISH, AppLanguage.Option.GERMAN, AppLanguage.Option.ITALIAN, AppLanguage.Option.SYSTEM)
    return order.map { option ->
        val name = languageName(option)
        // The current one checked, the others aligned with no icon.
        MenuItem(name, if (option == current) Icons.Rounded.Check else null, { onPick(option) }, selected = option == current)
    } + MenuItem(stringResource(R.string.action_back), Icons.AutoMirrored.Rounded.ArrowBack, onBack)
}

@Composable
private fun languageName(option: AppLanguage.Option): String = stringResource(
    when (option) {
        // The menu is narrow: "System", not "System default" as in Settings.
        AppLanguage.Option.SYSTEM -> R.string.language_system_short
        AppLanguage.Option.ENGLISH -> R.string.language_english
        AppLanguage.Option.GERMAN -> R.string.language_german
        AppLanguage.Option.ITALIAN -> R.string.language_italian
    },
)

/** An item's icon, or the same space empty, so the labels stay aligned. */
@Composable
private fun MenuIcon(icon: ImageVector?) {
    if (icon != null) Icon(icon, contentDescription = null) else Spacer(Modifier.size(24.dp))
}

/**
 * The app mark at [progress] 0 - white route on a primary disc in a primary ring - and the menu's close
 * button at 1 - an X on a larger primary disc. In between the ring fades into the growing disc and the
 * mark turns into the X, so opening and closing the menu morphs one into the other in place.
 */
@Composable
private fun MarkButton(progress: Float, label: String, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val ring = 1f - progress.coerceIn(0f, 1f)
        if (ring > 0f) Box(Modifier.size(40.dp).graphicsLayer { alpha = ring }.border(2.dp, primary, CircleShape))
        Box(Modifier.size(lerp(32.dp, 48.dp, progress)).background(primary, CircleShape))
        Icon(
            painterResource(R.drawable.ic_app_mark), contentDescription = null, tint = onPrimary,
            modifier = Modifier.size(20.dp).graphicsLayer { alpha = 1f - progress; rotationZ = 90f * progress },
        )
        Icon(
            Icons.Rounded.Close, contentDescription = null, tint = onPrimary,
            modifier = Modifier.size(24.dp).graphicsLayer { alpha = progress; rotationZ = -90f * (1f - progress) },
        )
    }
}

/** An item of the downward menu, sized and coloured as Material's FAB menu items. */
@Composable
private fun MenuPill(item: MenuItem) {
    val container = item.container()
    Surface(
        onClick = item.onClick, shape = RoundedCornerShape(28.dp), color = container, contentColor = contentColorFor(container),
        shadowElevation = 3.dp, modifier = Modifier.menuItemSemantics(item),
    ) {
        Row(Modifier.height(56.dp).padding(start = 16.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuIcon(item.icon)
            Spacer(Modifier.width(12.dp))
            Text(item.label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val MENU_PADDING = 16.dp
private const val CLOSE_MS = 220L
