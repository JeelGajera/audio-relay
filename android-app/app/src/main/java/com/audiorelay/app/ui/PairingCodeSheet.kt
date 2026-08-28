package com.audiorelay.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.audiorelay.app.R
import com.audiorelay.app.ui.theme.PairingCodeDigitStyle

private const val CODE_LENGTH = 6

/**
 * Pairing-code entry.
 *
 * Replaces a plain `OutlinedTextField` inside an `AlertDialog` whose
 * `onDismissRequest` was a no-op — there was literally no way to back out of
 * pairing once it started. This has a cancel action, a number keyboard, and
 * per-digit boxes matching how the laptop displays the code, so the user is
 * copying a shape they can see rather than parsing a run of six characters.
 *
 * The boxes are a display over one hidden field rather than six real fields:
 * six fields means six focus targets, and the resulting focus-juggling is
 * where per-digit inputs usually go wrong (backspace on an empty box, paste,
 * autofill).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingCodeSheet(
    laptopName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Set when the laptop refused the previous code, so the retry says why. */
    rejected: Boolean = false,
) {
    var code by remember { mutableStateOf("") }
    // A refusal clears the boxes, so the retry starts from empty rather than
    // leaving the rejected digits sitting there looking accepted.
    LaunchedEffect(rejected) { if (rejected) code = "" }
    val focusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.pairing_title, laptopName),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(if (rejected) R.string.pairing_rejected else R.string.pairing_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = if (rejected) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Box {
                // Invisible, but focusable and typeable — the boxes below are
                // purely a rendering of its value.
                BasicTextField(
                    value = code,
                    onValueChange = { new ->
                        code = new.filter(Char::isDigit).take(CODE_LENGTH)
                        if (code.length == CODE_LENGTH) onSubmit(code)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(focusRequester),
                    // Drawn off-screen effectively; the decoration box is empty.
                    decorationBox = { },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(CODE_LENGTH) { index ->
                        DigitBox(
                            digit = code.getOrNull(index),
                            focused = index == code.length,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { onSubmit(code) },
                    enabled = code.length == CODE_LENGTH,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_pair))
                }
            }
        }
    }
}

@Composable
private fun DigitBox(digit: Char?, focused: Boolean) {
    val borderColor = if (focused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.small,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit?.toString() ?: "",
            style = PairingCodeDigitStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (digit == null && focused) {
            // A caret, so the next box to fill is obvious without a real
            // cursor being visible in the hidden field.
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(width = 18.dp, height = 2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
