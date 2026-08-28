package com.audiorelay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiorelay.app.R
import com.audiorelay.app.state.PairedLaptop
import com.audiorelay.app.state.RelayState
import com.audiorelay.app.state.SettingsStore
import com.audiorelay.app.state.ThemeMode
import com.audiorelay.app.ui.components.SectionCard
import com.audiorelay.app.ui.components.SettingRow

@Composable
fun SettingsScreen(
    onSelectOutputDevice: (String?) -> Unit,
    onSetJitterDepth: (Int) -> Unit,
    onForgetLaptop: (String) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp),
        )

        OutputDeviceCard(onSelectOutputDevice)
        LatencyCard(onSetJitterDepth)
        AppearanceCard(onSetThemeMode, onSetDynamicColor)
        PairedLaptopsCard(onForgetLaptop)

        Column(Modifier.padding(bottom = 24.dp)) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputDeviceCard(onSelectOutputDevice: (String?) -> Unit) {
    val devices by RelayState.availableOutputDevices.collectAsStateWithLifecycle()
    val selectedKey by RelayState.preferredOutputDeviceKey.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val selectedLabel = devices.firstOrNull { it.key == selectedKey }?.label
        ?: stringResource(R.string.settings_output_automatic)

    SectionCard(
        title = stringResource(R.string.settings_output_title),
        subtitle = stringResource(R.string.settings_output_hint),
    ) {
        SettingRow(title = selectedLabel) {
            Button(onClick = { sheetOpen = true }) {
                Text(stringResource(R.string.action_change))
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheetState) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text(
                    stringResource(R.string.settings_output_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                // "Automatic" first and always present — it is the default and
                // the thing to come back to when a manual choice misbehaves.
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_output_automatic)) },
                    supportingContent = { Text(stringResource(R.string.settings_output_automatic_hint)) },
                    leadingContent = {
                        RadioButton(selected = selectedKey == null, onClick = {
                            onSelectOutputDevice(null)
                            sheetOpen = false
                        })
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                devices.forEach { device ->
                    ListItem(
                        headlineContent = { Text(device.label) },
                        leadingContent = {
                            RadioButton(selected = selectedKey == device.key, onClick = {
                                onSelectOutputDevice(device.key)
                                sheetOpen = false
                            })
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (devices.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_output_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyCard(onSetJitterDepth: (Int) -> Unit) {
    val stored by RelayState.jitterTargetDepthMs.collectAsStateWithLifecycle()
    // Local state during the drag so the slider tracks the finger; the
    // committed value only changes on release.
    var sliderValue by remember(stored) { mutableFloatStateOf(stored.toFloat()) }

    SectionCard(
        title = stringResource(R.string.settings_buffer_title),
        subtitle = stringResource(R.string.settings_buffer_hint),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSetJitterDepth(sliderValue.toInt()) },
                valueRange = SettingsStore.MIN_JITTER_DEPTH_MS.toFloat()..
                    SettingsStore.MAX_JITTER_DEPTH_MS.toFloat(),
                // One notch per 10ms — fine enough to tune, coarse enough
                // that the slider still feels like discrete choices.
                steps = (SettingsStore.MAX_JITTER_DEPTH_MS - SettingsStore.MIN_JITTER_DEPTH_MS) / 10 - 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.settings_buffer_value, sliderValue.toInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
) {
    val themeMode by RelayState.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by RelayState.dynamicColor.collectAsStateWithLifecycle()

    val labels = listOf(
        ThemeMode.SYSTEM to R.string.settings_theme_system,
        ThemeMode.LIGHT to R.string.settings_theme_light,
        ThemeMode.DARK to R.string.settings_theme_dark,
    )

    SectionCard(title = stringResource(R.string.settings_appearance_title)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, (mode, labelRes) ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onSetThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                ) {
                    Text(stringResource(labelRes))
                }
            }
        }
        Column(Modifier.padding(top = 16.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_hint),
            ) {
                Switch(checked = dynamicColor, onCheckedChange = onSetDynamicColor)
            }
        }
    }
}

@Composable
private fun PairedLaptopsCard(onForgetLaptop: (String) -> Unit) {
    val laptops by RelayState.pairedLaptops.collectAsStateWithLifecycle()
    var pendingForget by remember { mutableStateOf<PairedLaptop?>(null) }

    SectionCard(
        title = stringResource(R.string.settings_paired_title),
        subtitle = stringResource(R.string.settings_paired_hint),
    ) {
        if (laptops.isEmpty()) {
            Text(
                stringResource(R.string.settings_paired_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        laptops.forEach { laptop ->
            SettingRow(
                title = laptop.name,
                subtitle = laptop.deviceId.take(8),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                TextButton(onClick = { pendingForget = laptop }) {
                    Text(stringResource(R.string.action_forget))
                }
            }
        }
    }

    // Forgetting means re-entering a pairing code on that laptop, which is
    // annoying enough to be worth confirming rather than doing on one tap.
    val target = pendingForget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text(stringResource(R.string.settings_forget_title, target.name)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onForgetLaptop(target.deviceId)
                    pendingForget = null
                }) {
                    Text(stringResource(R.string.action_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingForget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
