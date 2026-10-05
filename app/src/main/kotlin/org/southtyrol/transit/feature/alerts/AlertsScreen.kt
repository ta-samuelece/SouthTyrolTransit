@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import org.southtyrol.transit.model.localized
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
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
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.di.AppLanguage
import org.southtyrol.transit.feature.common.AlertCard
import org.southtyrol.transit.feature.common.PollWhileVisible
import org.southtyrol.transit.feature.common.TopLevelBar
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.ui.Navigator
import javax.inject.Inject

data class AlertsUi(val state: AlertsState, val savedStops: Set<String>, val savedLines: List<Line>, val loaded: Boolean)

/** Human-readable scope of an alert: public line names and stop names. */
data class AlertScope(val lines: Set<String>, val stops: List<String>)

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
    private val routeNames = HashMap<String, String>()
    private val stopNames = HashMap<String, String?>()

    private val _query = kotlinx.coroutines.flow.MutableStateFlow(handle.get<String>("q").orEmpty())
    val query: StateFlow<String> = _query
    fun query(value: String) { handle["q"] = value; _query.value = value }

    /** GTFS route ids and stop ids resolved to names so alerts can be filtered by line or stop. */
    val scopes: StateFlow<Map<String, AlertScope>> = alerts.state.map { state ->
        val lang = language.current()
        val missingRoutes = state.alerts.flatMap { it.routes }.filter { it !in routeNames }.toSet()
        if (missingRoutes.isNotEmpty()) runCatching { schedule.routes(missingRoutes) }.getOrNull()?.forEach { (id, r) -> routeNames[id] = r.shortName.ifBlank { r.longName } }
        state.alerts.associate { alert ->
            val stations = alert.stops.filter { it.count { c -> c == ':' } == 2 }.toSet()
            for (key in stations) if (key !in stopNames) stopNames[key] = runCatching { schedule.stop(key, lang)?.name }.getOrNull()
            alert.id to AlertScope(
                alert.lineNames + alert.routes.mapNotNull { routeNames[it] }.filter { it.isNotBlank() },
                stations.mapNotNull { stopNames[it] }.distinct().sorted(),
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
        val stopKeys = items.filter { it.kind == SavedKind.STOP }.map { it.id }.toSet()
        val lineKeys = items.filter { it.kind == SavedKind.LINE }.map { it.id }
        val missing = lineKeys.filter { it !in resolvedLines }
        if (missing.isNotEmpty()) resolvedLines = resolvedLines + missing.associateWith { runCatching { lines.line(it) }.getOrNull() }
        AlertsUi(state, stopKeys, lineKeys.mapNotNull { resolvedLines[it] }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    suspend fun refresh(force: Boolean = false) = alerts.refresh(language.current(), force)

    fun mine(alert: ServiceAlert, ui: AlertsUi) = ui.savedStops.any { AlertMatcher.affectsStop(alert, it) } || ui.savedLines.any { AlertMatcher.affectsLine(alert, it) }
}

@Composable
fun AlertsScreen(navigator: Navigator, viewModel: AlertsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scopes by viewModel.scopes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val now = rememberNow(60_000)
    val language = AppLanguage.current()
    PollWhileVisible(120_000) { viewModel.refresh() }

    Scaffold(topBar = { TopLevelBar(stringResource(R.string.alerts_title)) }) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; viewModel.refresh(force = true); refreshing = false } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val current = ui
            if (current == null) { TransitLoading(contained = true); return@PullToRefreshBox }
            val all = current.state.alerts
            val filtered = all.filter { if (tab == 0) it.active(now) else it.upcoming(now) }.filter { !onlyMine || viewModel.mine(it, current) }
                .filter { viewModel.matches(it, scopes[it.id], query, language) }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.alerts_active)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.alerts_planned)) })
                    }
                }
                item {
                    androidx.compose.material3.OutlinedTextField(
                        value = query, onValueChange = viewModel::query, singleLine = true,
                        placeholder = { Text(stringResource(R.string.alerts_filter_hint)) },
                        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.FilterList, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { viewModel.query("") }) {
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = onlyMine, onClick = { onlyMine = !onlyMine }, label = { Text(stringResource(R.string.alerts_only_saved)) })
                    }
                }
                item { FreshnessIndicator(current.state.freshness, current.state.fetchedAt, now) }
                if (current.state.freshness == Freshness.STALE) item { StatusBanner(stringResource(R.string.alerts_stale), kind = BannerKind.WARNING) }
                current.state.error?.let { item { StatusBanner(Format.error(it), kind = BannerKind.ERROR) } }
                if (onlyMine && current.savedStops.isEmpty() && current.savedLines.isEmpty()) item {
                    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.alerts_nothing_saved), style = MaterialTheme.typography.bodyMedium)
                            androidx.compose.material3.FilledTonalButton(onClick = navigator::saved) { Text(stringResource(R.string.alerts_choose_saved)) }
                        }
                    }
                } else if (filtered.isEmpty()) item {
                    MessageState(
                        stringResource(if (current.state.fetchedAt == null) R.string.alerts_none_loaded else R.string.alerts_none),
                        icon = Icons.Rounded.CheckCircle,
                    )
                }
                items(filtered, key = { it.id }) { alert ->
                    val scope = scopes[alert.id]
                    AlertCard(
                        alert, language, now, Modifier.animateItem(),
                        onLine = { name -> viewModel.query(name) },
                        lineNames = scope?.lines ?: alert.lineNames,
                        stopNames = scope?.stops.orEmpty(),
                    )
                }
                item {
                    Text(stringResource(R.string.alerts_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
