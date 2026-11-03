@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.alerts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.AlertMatcher
import org.southtyrol.transit.data.AlertRepository
import org.southtyrol.transit.data.AlertsState
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LineRepository
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TopLip
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.di.AppLanguage
import org.southtyrol.transit.feature.common.AlertCard
import org.southtyrol.transit.feature.common.PollWhileVisible
import org.southtyrol.transit.feature.common.groupShape
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.localized
import org.southtyrol.transit.ui.Navigator
import javax.inject.Inject

/** The alerts with the saved lines and stops, in their saved order; [savedStops] maps station key to name. */
data class AlertsUi(val state: AlertsState, val savedStops: Map<String, String>, val savedLines: List<Line>, val loaded: Boolean)

/**
 * Human-readable scope of an alert: public line names and stop names, plus the GTFS route behind a line
 * name where one is known, so the line can be drawn as its badge (mode and colour).
 */
data class AlertScope(val lines: Set<String>, val stops: List<String>, val routes: Map<String, org.southtyrol.transit.model.Route> = emptyMap())

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val alerts: AlertRepository,
    saved: SavedRepository,
    private val lines: LineRepository,
    private val language: LanguageProvider,
    private val schedule: org.southtyrol.transit.model.TransitScheduleDataSource,
    private val handle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {
    private var resolvedLines: Map<String, Line?> = emptyMap()
    private val routes = HashMap<String, org.southtyrol.transit.model.Route>()
    private val stopNames = HashMap<String, String?>()
    /** Lines named only in planner notices (no GTFS route id), looked up by their public name. */
    private val linesByName = HashMap<String, Line?>()

    private val _query = kotlinx.coroutines.flow.MutableStateFlow(handle.get<String>("q").orEmpty())
    val query: StateFlow<String> = _query
    fun query(value: String) { handle["q"] = value; _query.value = value }

    /** GTFS route ids and stop ids resolved to names so alerts can be filtered by line or stop. */
    val scopes: StateFlow<Map<String, AlertScope>> = alerts.state.map { state ->
        val lang = language.current()
        val missingRoutes = state.alerts.flatMap { it.routes }.filter { it !in routes }.toSet()
        if (missingRoutes.isNotEmpty()) runCatching { schedule.routes(missingRoutes) }.getOrNull()?.let { routes.putAll(it) }
        state.alerts.associate { alert ->
            val stations = alert.stops.filter { it.count { c -> c == ':' } == 2 }.toSet()
            for (key in stations) if (key !in stopNames) stopNames[key] = runCatching { schedule.stop(key, lang)?.name }.getOrNull()
            val named = alert.routes.mapNotNull { id -> routes[id]?.let { r -> r.shortName.ifBlank { r.longName } to r } }.filter { it.first.isNotBlank() }.toMap()
            // Planner notices name lines without a route id: find the line by name so its badge gets the
            // right mode icon and colour (the first exact match; none when the timetable is missing).
            val byName = alert.lineNames.filter { it !in named }.mapNotNull { name ->
                val line = linesByName.getOrPut(name) {
                    runCatching { lines.search(name) }.getOrNull()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
                }
                line?.let { name to org.southtyrol.transit.model.Route(it.key, it.name, it.longName, it.mode, color = it.color, textColor = it.textColor) }
            }
            alert.id to AlertScope(
                alert.lineNames + named.keys,
                stations.mapNotNull { stopNames[it] }.distinct().sorted(),
                named + byName,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun matches(alert: ServiceAlert, scope: AlertScope?, query: String, language: String): Boolean {
        val q = org.southtyrol.transit.model.TextNormalizer.key(query)
        if (q.isBlank()) return true
        val lines = scope?.lines ?: alert.lineNames
        return lines.any { org.southtyrol.transit.model.TextNormalizer.key(it) == q || org.southtyrol.transit.model.TextNormalizer.key(it).startsWith(q) } ||
            scope?.stops.orEmpty().any { org.southtyrol.transit.model.TextNormalizer.key(it).contains(q) } ||
            org.southtyrol.transit.model.TextNormalizer.key(alert.headers.localized(language)).contains(q)
    }

    val ui: StateFlow<AlertsUi?> = combine(alerts.state, saved.saved) { state, items ->
        val stops = items.filter { it.kind == SavedKind.STOP }.associate { it.id to it.label }
        val lineKeys = items.filter { it.kind == SavedKind.LINE }.map { it.id }
        val missing = lineKeys.filter { it !in resolvedLines }
        if (missing.isNotEmpty()) resolvedLines = resolvedLines + missing.associateWith { runCatching { lines.line(it) }.getOrNull() }
        AlertsUi(state, stops, lineKeys.mapNotNull { resolvedLines[it] }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    suspend fun refresh(force: Boolean = false) = alerts.refresh(language.current(), force)

    fun onSavedLine(alert: ServiceAlert, ui: AlertsUi) = ui.savedLines.any { AlertMatcher.affectsLine(alert, it) }
    fun atSavedStop(alert: ServiceAlert, ui: AlertsUi) = ui.savedStops.keys.any { AlertMatcher.affectsStop(alert, it) }
}


/** What the sheet shows: every alert, or only those on the saved lines or at the saved stops (a dashboard card). */
private enum class AlertFilter { ALL, LINES, STOPS }

/**
 * Service alerts under a top lip, like Plan: search on the left, Settings on the right. Under the title the
 * lip holds a dashboard of two cards, the saved lines and the saved stops with an alert in force now; a tap
 * on a card shows only those alerts, a second tap shows all again. Search opens at the bottom of the lip and
 * filters the sheet as you type, without closing on a touch of the list. The sheet groups the alerts into
 * "In force now" and "Coming up".
 */
@Composable
fun AlertsScreen(navigator: Navigator, viewModel: AlertsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scopes by viewModel.scopes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(AlertFilter.ALL) }
    // A query handed over on the way in (a line's alerts) opens the screen with search open.
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var focusSearch by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val now = rememberNow(60_000)
    val language = AppLanguage.textLanguages()
    PollWhileVisible(120_000) { viewModel.refresh() }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val openSearch = { searching = true; focusSearch = true }
    val closeSearch = { searching = false; viewModel.query("") }
    // Back closes search, and clears it, before it leaves the screen.
    BackHandler(enabled = searching) { closeSearch() }
    LaunchedEffect(searching, focusSearch) {
        if (searching && focusSearch) {
            focusSearch = false
            // The field joins the composition with the lip's opening: wait a frame for it to attach.
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    Scaffold(
        // The lip reaches under the status bar itself.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { padding ->
        TopLip(
            title = stringResource(R.string.alerts_title),
            start = {
                IconToggleButton(
                    checked = searching, onCheckedChange = { if (it) openSearch() else closeSearch() },
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primary, checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Icon(Icons.Rounded.Search, contentDescription = stringResource(if (searching) R.string.alerts_close_search else R.string.alerts_search)) }
            },
            end = {
                IconButton(onClick = navigator::settings) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_title))
                }
            },
            expanded = searching,
            modal = false,
            onDismiss = closeSearch,
            lipContent = {
                ui?.let { current ->
                    AlertDashboard(
                        current, now, filter,
                        onFilter = { picked -> filter = if (filter == picked) AlertFilter.ALL else picked },
                        onChoose = navigator::saved,
                    )
                }
            },
            expandedContent = {
                AlertSearchField(query, viewModel::query, onDone = { keyboard?.hide() }, modifier = Modifier.focusRequester(focus))
            },
            modifier = Modifier.padding(padding),
        ) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshing = true; viewModel.refresh(force = true); refreshing = false } },
                modifier = Modifier.fillMaxSize(),
            ) {
                val current = ui
                if (current == null) { TransitLoading(contained = true); return@PullToRefreshBox }
                val picked: (ServiceAlert) -> Boolean = when (filter) {
                    AlertFilter.ALL -> { _ -> true }
                    AlertFilter.LINES -> { alert -> viewModel.onSavedLine(alert, current) }
                    AlertFilter.STOPS -> { alert -> viewModel.atSavedStop(alert, current) }
                }
                val shown = current.state.alerts.filter { picked(it) && viewModel.matches(it, scopes[it.id], query, language) }
                val inForce = shown.filter { it.active(now) }
                val comingUp = shown.filter { it.upcoming(now) }
                val showAll = { filter = AlertFilter.ALL }
                // Alerts form grouped lists (2 dp apart); the heading and section labels keep their own spacing.
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "heading") {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val headingText = when (filter) {
                                AlertFilter.ALL -> R.string.alerts_all
                                AlertFilter.LINES -> R.string.alerts_on_your_lines
                                AlertFilter.STOPS -> R.string.alerts_at_your_stops
                            }
                            Text(
                                stringResource(headingText), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            // While filtered, "Show all" takes the freshness pill's place: both would crowd a narrow screen.
                            if (filter == AlertFilter.ALL) FreshnessIndicator(current.state.freshness, current.state.fetchedAt, now, compact = true)
                            else FilledTonalButton(onClick = showAll, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.alerts_show_all))
                            }
                        }
                    }
                    if (current.state.freshness == Freshness.STALE) item(key = "stale") { StatusBanner(stringResource(R.string.alerts_stale), Modifier.padding(bottom = 8.dp), kind = BannerKind.WARNING) }
                    current.state.error?.let { item(key = "error") { StatusBanner(Format.error(it), Modifier.padding(bottom = 8.dp), kind = BannerKind.ERROR) } }
                    // A line badge on an alert searches for that line, so search opens with it.
                    val onLine: (String) -> Unit = { name -> viewModel.query(name); searching = true }
                    listOf(R.string.alerts_in_force to inForce, R.string.alerts_coming_up to comingUp).forEachIndexed { section, (label, alerts) ->
                        if (alerts.isEmpty()) return@forEachIndexed
                        item(key = "section$section", contentType = "section") {
                            Text(
                                stringResource(label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = if (section > 0 && inForce.isNotEmpty()) 12.dp else 0.dp, bottom = 4.dp).semantics { heading() },
                            )
                        }
                        itemsIndexed(alerts, key = { _, it -> it.id }, contentType = { _, _ -> "alert" }) { index, alert ->
                            val alertScope = scopes[alert.id]
                            AlertCard(
                                alert, language, now, Modifier.animateItem(),
                                onLine = onLine,
                                lineNames = alertScope?.lines ?: alert.lineNames,
                                stopNames = alertScope?.stops.orEmpty(),
                                lineRoutes = alertScope?.routes.orEmpty(),
                                shape = groupShape(index, alerts.size),
                            )
                        }
                    }
                    if (inForce.isEmpty() && comingUp.isEmpty()) item(key = "empty") {
                        if (filter != AlertFilter.ALL || query.isNotBlank()) MessageState(
                            stringResource(R.string.alerts_no_match), body = stringResource(R.string.alerts_no_match_hint),
                            icon = Icons.Rounded.SearchOff,
                            action = stringResource(R.string.alerts_show_all), onAction = { showAll(); closeSearch() },
                        ) else MessageState(
                            stringResource(if (current.state.fetchedAt == null) R.string.alerts_none_loaded else R.string.alerts_none),
                            icon = Icons.Rounded.CheckCircle,
                        )
                    }
                    item(key = "source") {
                        Text(
                            stringResource(R.string.alerts_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The lip's dashboard: the saved lines and the saved stops, each a card with how many have an alert in force
 * now, how many more have one coming up, and the affected ones as marks. A card is a toggle for its filter; with
 * nothing saved yet it opens Saved instead.
 */
@Composable
private fun AlertDashboard(
    ui: AlertsUi,
    now: java.time.Instant,
    filter: AlertFilter,
    onFilter: (AlertFilter) -> Unit,
    onChoose: () -> Unit,
) {
    val inForce = ui.state.alerts.filter { it.active(now) }
    val comingUp = ui.state.alerts.filter { it.upcoming(now) }
    val linesNow = ui.savedLines.filter { line -> inForce.any { AlertMatcher.affectsLine(it, line) } }
    val linesLater = ui.savedLines.count { line -> line !in linesNow && comingUp.any { AlertMatcher.affectsLine(it, line) } }
    val stopsNow = ui.savedStops.filter { (key, _) -> inForce.any { AlertMatcher.affectsStop(it, key) } }
    val stopsLater = ui.savedStops.count { (key, _) -> key !in stopsNow && comingUp.any { AlertMatcher.affectsStop(it, key) } }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val linesSelected = filter == AlertFilter.LINES
        DashboardCard(
            label = stringResource(R.string.alerts_your_lines), affected = linesNow.size, total = ui.savedLines.size, later = linesLater,
            selected = linesSelected, onToggle = { onFilter(AlertFilter.LINES) }, onChoose = onChoose,
            empty = stringResource(R.string.alerts_no_saved_lines),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            linesNow.take(MAX_LINE_MARKS).forEach { line -> LineBadge(line.name, line.mode, color = line.color, textColor = line.textColor) }
            MoreMark(linesNow.size - MAX_LINE_MARKS)
        }
        val stopsSelected = filter == AlertFilter.STOPS
        DashboardCard(
            label = stringResource(R.string.alerts_your_stops), affected = stopsNow.size, total = ui.savedStops.size, later = stopsLater,
            selected = stopsSelected, onToggle = { onFilter(AlertFilter.STOPS) }, onChoose = onChoose,
            empty = stringResource(R.string.alerts_no_saved_stops),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            stopsNow.values.firstOrNull()?.let { name ->
                val colors = MaterialTheme.colorScheme
                Text(
                    name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (stopsSelected) colors.primary else colors.onSecondaryContainer,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(11.dp))
                        .background(if (stopsSelected) colors.onPrimary else colors.secondaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            MoreMark(stopsNow.size - 1)
        }
    }
}

private const val MAX_LINE_MARKS = 3

/** "+2" after the marks that fit. */
@Composable
private fun MoreMark(hidden: Int) {
    if (hidden > 0) Text("+$hidden", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
}

/**
 * One dashboard card in the lip: in the `surface` tone, `primary` while its filter is on, so it neither
 * vanishes into the lip nor hides its state. TalkBack hears one sentence and the toggle state.
 */
@Composable
private fun DashboardCard(
    label: String,
    affected: Int,
    total: Int,
    later: Int,
    selected: Boolean,
    onToggle: () -> Unit,
    onChoose: () -> Unit,
    empty: String,
    modifier: Modifier = Modifier,
    marks: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(if (selected) colors.primary else colors.surface, motion.defaultEffectsSpec(), label = "cardContainer")
    val content by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface, motion.defaultEffectsSpec(), label = "cardContent")
    val subColor = if (selected) colors.onPrimary else colors.onSurfaceVariant
    val nowText = stringResource(R.string.alerts_card_now)
    val laterText = if (later > 0) stringResource(R.string.alerts_card_later, later) else null
    val ofText = stringResource(R.string.alerts_card_of, total)
    val chooseText = stringResource(R.string.alerts_card_choose)
    val description = if (total == 0) "$label. $empty. $chooseText"
    else listOfNotNull("$label: $affected $ofText $nowText", laterText).joinToString(", ")
    val action = if (total == 0) {
        Modifier.clickable(role = Role.Button, onClick = onChoose)
    } else {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
    }
    Box(
        modifier.heightIn(min = 148.dp).clip(RoundedCornerShape(24.dp)).background(container)
            .then(action).semantics { contentDescription = description },
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Column(Modifier.fillMaxSize().clearAndSetSemantics { }.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                if (total == 0) {
                    Text(empty, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(chooseText, style = MaterialTheme.typography.labelMedium, color = subColor, modifier = Modifier.padding(top = 2.dp))
                } else {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "$affected", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Text(ofText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.alignByBaseline())
                    }
                    Text(
                        listOfNotNull(nowText, laterText).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = subColor,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                    Row(
                        Modifier.fillMaxWidth().clipToBounds(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                        content = marks,
                    )
                }
            }
        }
    }
}

/** The search field at the bottom of the lip, in the `surface` tone so it stands out from the lip. */
@Composable
private fun AlertSearchField(query: String, onQuery: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text(stringResource(R.string.alerts_search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
