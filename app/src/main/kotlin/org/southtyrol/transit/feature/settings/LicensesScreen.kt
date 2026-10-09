package org.southtyrol.transit.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.southtyrol.transit.R
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.ui.Navigator

/**
 * Third-party libraries in this build and their licences. The list and the licence texts are generated
 * at build time by the AboutLibraries Gradle plugin (res/raw/aboutlibraries.json), so they always match
 * what the APK contains; Apache 2.0 and the BSD licences ask for these notices to ship with the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(navigator: Navigator) {
    val context = LocalContext.current
    val libraries by produceState<List<Library>?>(null) {
        value = withContext(Dispatchers.IO) {
            val json = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
            Libs.Builder().withJson(json).build().libraries.sortedBy { it.name.lowercase() }
        }
    }
    var open by remember { mutableStateOf<Library?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val list = libraries
        if (list == null) {
            TransitLoading(contained = true, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    stringResource(R.string.licenses_intro, list.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(list, key = { it.uniqueId }) { library ->
                Column(
                    Modifier.fillMaxWidth().clickable { open = library }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(library.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(library.artifactVersion, shownLicenses(library).joinToString(", ") { it.name }.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    open?.let { library -> LicenseSheet(library, onDismiss = { open = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(library: Library, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(library.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val holders = (library.developers.mapNotNull { it.name } + listOfNotNull(library.organization?.name)).distinct()
            if (holders.isNotEmpty()) Text(holders.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            library.website?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { site ->
                TextButton(onClick = { uri.openUri(site) }, contentPadding = PaddingValues(0.dp)) { Text(site) }
            }
            shownLicenses(library).forEach { license ->
                Text(license.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(license.licenseContent?.let(::licenseText) ?: license.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** SPDX template fields look like `<<var;name=copyright;original=<year> <owner>;match=.+>>`. */
private val spdxVariable = Regex("<<var;[^>]*?original=(.*?);match=.*?>>", RegexOption.DOT_MATCHES_ALL)

/**
 * The licences to show: a generic SPDX template (with a placeholder for the copyright line) is dropped
 * when the library also carries its real licence file, added from app/licenses-config at build time.
 */
private fun shownLicenses(library: Library): List<com.mikepenz.aboutlibraries.entity.License> {
    val all = library.licenses.toList()
    val real = all.filterNot { it.licenseContent.orEmpty().contains("<<var;") }
    return if (real.isNotEmpty() && real.size < all.size) real else all
}

/** Licence files are hard-wrapped for 80 columns: rejoin each paragraph, keep blank lines and list items. */
private val softBreak = Regex("""(?<!\n)[ \t]*\n(?![ \t]*(?:[\n*\-•]|\d+\.|\(\w\) ))[ \t]*""")

internal fun licenseText(content: String): String =
    content.replace("\r\n", "\n").replace("<br />", "").replace(spdxVariable) { it.groupValues[1] }
        .replace("<<beginOptional>>", "").replace("<<endOptional>>", "")
        .replace(softBreak, " ").trim()
