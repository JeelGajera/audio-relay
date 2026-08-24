package com.audiorelay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.audiorelay.app.BuildConfig

/**
 * Version/build metadata (from [BuildConfig], injected at build time in
 * `app/build.gradle.kts` — mirrors `windows-app`'s `build.rs`), a link back
 * to the repo, and license info. See `ui/AudioRelayApp.kt` for how this
 * fits into the three-tab shell.
 */
@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("About audio-relay", style = MaterialTheme.typography.headlineMedium)

        Text("Version ${BuildConfig.VERSION_NAME}")
        Text("Build commit ${BuildConfig.GIT_HASH} (${BuildConfig.GIT_COMMIT_DATE})")

        TextButton(onClick = { uriHandler.openUri(BuildConfig.GITHUB_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text("Source on GitHub")
        }
        TextButton(onClick = { uriHandler.openUri("${BuildConfig.GITHUB_URL}/issues") }, modifier = Modifier.fillMaxWidth()) {
            Text("Report an issue")
        }

        HorizontalDivider()
        Text("License", style = MaterialTheme.typography.titleMedium)
        Text("MIT — see the LICENSE file in the repository.")

        HorizontalDivider()
        Text("Third-party open source", style = MaterialTheme.typography.titleMedium)
        Text(
            "Direct dependencies and their licenses — see app/build.gradle.kts for the complete list.",
            style = MaterialTheme.typography.bodySmall,
        )
        ThirdPartyLicenses.forEach { (name, license) ->
            Text("$name — $license", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Notable direct dependencies, not the full transitive tree (that drifts
 * out of date immediately and isn't what a user actually wants to read) —
 * see `app/build.gradle.kts` for the authoritative, versioned list.
 */
private val ThirdPartyLicenses = listOf(
    "Kotlin / kotlinx.coroutines" to "Apache-2.0",
    "kotlinx.serialization" to "Apache-2.0",
    "AndroidX Core / Lifecycle / Activity / Media" to "Apache-2.0",
    "Jetpack Compose / Material3" to "Apache-2.0",
)
