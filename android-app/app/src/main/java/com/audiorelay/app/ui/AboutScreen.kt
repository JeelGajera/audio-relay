package com.audiorelay.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.audiorelay.app.BuildConfig
import com.audiorelay.app.R
import com.audiorelay.app.ui.components.SectionCard
import com.audiorelay.app.ui.components.SettingRow

/**
 * Direct dependencies and their licences. Not the full transitive tree —
 * that is hundreds of artifacts and would drift out of date immediately.
 * Mirrors the same list in `windows-app/src/ui/about.rs`.
 */
private val ThirdPartyLicenses = listOf(
    "AndroidX / Jetpack Compose" to "Apache-2.0",
    "Material Components for Android" to "Apache-2.0",
    "Kotlin standard library" to "Apache-2.0",
    "kotlinx.coroutines" to "Apache-2.0",
    "kotlinx.serialization" to "Apache-2.0",
)

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.padding(top = 24.dp)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.about_build_title)) {
            SettingRow(title = stringResource(R.string.about_version)) {
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium)
            }
            SettingRow(
                title = stringResource(R.string.about_commit),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(BuildConfig.GIT_HASH, style = MaterialTheme.typography.bodyMedium)
            }
            SettingRow(
                title = stringResource(R.string.about_commit_date),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(BuildConfig.GIT_COMMIT_DATE, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard(title = stringResource(R.string.about_project_title)) {
            LinkRow(stringResource(R.string.about_source)) {
                uriHandler.openUri(BuildConfig.GITHUB_URL)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LinkRow(stringResource(R.string.about_report_issue)) {
                uriHandler.openUri("${BuildConfig.GITHUB_URL}/issues")
            }
        }

        SectionCard(
            title = stringResource(R.string.about_licenses_title),
            subtitle = stringResource(R.string.about_licenses_hint),
        ) {
            ThirdPartyLicenses.forEach { (name, license) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(Modifier.padding(bottom = 24.dp)) {}
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}
