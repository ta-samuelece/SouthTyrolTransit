@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.search

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
 * The app mark at the end of the map's search bar, and the menu it opens: Saved, the language, the
 * theme and Settings. A Material FAB menu rising from the mark (which turns into the close button) when
 * the bar is at the bottom; the same items dropping below it when the bar is at the top, which Material's
 * menu cannot do. Language turns the menu into the choices; the theme changes at once and the menu stays.
 */
@Composable
fun SearchBarMenuButton(navigator: Navigator, opensUp: Boolean, viewModel: SearchBarMenuViewModel = hiltViewModel()) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            // The mark in a ring of the accent colour, 2 dp apart.
            Box(
                Modifier.size(40.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_app_mark), contentDescription = stringResource(R.string.map_menu),
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp),
                )
            }
        }
        if (open) {
            val density = LocalDensity.current
            Popup(
                popupPositionProvider = remember(opensUp, density) { AtAnchorEnd(opensUp, density) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                SearchBarMenu(navigator, opensUp, viewModel, onClose = { open = false })
            }
        }
    }
}

@Composable
private fun SearchBarMenu(navigator: Navigator, opensUp: Boolean, viewModel: SearchBarMenuViewModel, onClose: () -> Unit) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Shown collapsed for one frame, so the items rise in with Material's stagger; collapsed again before closing.
    var expanded by remember { mutableStateOf(false) }
    var languages by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { expanded = true }
    fun close(then: () -> Unit = {}) {
        expanded = false
        scope.launch {
            delay(CLOSE_MS)
            onClose()
            then()
        }
    }
    val items = if (languages) languageItems(onPick = { option -> close { AppLanguage.select(option) } }, onBack = { languages = false })
    else mainItems(
        theme,
        onSaved = { close(navigator::saved) },
        onLanguage = { languages = true },
        onTheme = viewModel::nextTheme,
        onSettings = { close(navigator::settings) },
    )
    val closeButton: @Composable () -> Unit = { CloseButton(onClick = { close() }) }
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
            AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(expandFrom = Alignment.Top)) {
                Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.reversed().forEach { MenuPill(it) }
                }
            }
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
    val order = listOf(AppLanguage.Option.ENGLISH, AppLanguage.Option.GERMAN, AppLanguage.Option.ITALIAN, AppLanguage.Option.LADIN, AppLanguage.Option.SYSTEM)
    return order.map { option ->
        val name = languageName(option)
        // The current one checked, the phone's language its own icon, the others aligned with no icon.
        val icon = when {
            option == current -> Icons.Rounded.Check
            option == AppLanguage.Option.SYSTEM -> Icons.Rounded.Smartphone
            else -> null
        }
        MenuItem(name, icon, { onPick(option) }, selected = option == current)
    } + MenuItem(stringResource(R.string.action_back), Icons.AutoMirrored.Rounded.ArrowBack, onBack)
}

@Composable
private fun languageName(option: AppLanguage.Option): String = stringResource(
    when (option) {
        AppLanguage.Option.SYSTEM -> R.string.language_system
        AppLanguage.Option.ENGLISH -> R.string.language_english
        AppLanguage.Option.GERMAN -> R.string.language_german
        AppLanguage.Option.ITALIAN -> R.string.language_italian
        AppLanguage.Option.LADIN -> R.string.language_ladin
    },
)

/** An item's icon, or the same space empty, so the labels stay aligned. */
@Composable
private fun MenuIcon(icon: ImageVector?) {
    if (icon != null) Icon(icon, contentDescription = null) else Spacer(Modifier.size(24.dp))
}

/** The mark turned into the menu's close button: drawn exactly where the mark is. */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    val label = stringResource(R.string.map_menu_close)
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
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

/**
 * Places the menu so its close button lands exactly on the mark: Material's menu pads 16 dp at the sides
 * and below its button, the downward one 16 dp at the sides only.
 */
private class AtAnchorEnd(private val opensUp: Boolean, density: Density) : PopupPositionProvider {
    private val padding = with(density) { MENU_PADDING.roundToPx() }

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.right - popupContentSize.width + padding
        val y = if (opensUp) anchorBounds.bottom - popupContentSize.height + padding else anchorBounds.top
        return IntOffset(x, y)
    }
}

private val MENU_PADDING = 16.dp
private const val CLOSE_MS = 220L
