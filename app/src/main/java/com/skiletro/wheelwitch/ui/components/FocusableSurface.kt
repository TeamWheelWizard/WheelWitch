package com.skiletro.wheelwitch.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme
import com.skiletro.wheelwitch.ui.theme.buttonShape

/**
 * Card with a gamepad focus ring; [selected] keeps the ring visible without focus.
 * [enabled] controls focus and clicking; a null [onClick] leaves an enabled card focusable only.
 */
@Composable
fun FocusableSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = buttonShape,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val clickModifier = if (onClick != null && enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        shape = shape,
        color = color,
        modifier = modifier
            .then(clickModifier)
            .focusable(enabled = enabled)
            .onFocusChanged { isFocused = it.isFocused }
            .focusBorder(
                isFocused = isFocused || selected,
                shape = shape
            )
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
private fun FocusableSurfacePreview() {
    WheelWitchPreviewTheme {
        FocusableSurface(
            modifier = Modifier.padding(16.dp),
            onClick = {},
        ) {
            Text(
                text = "Sample focusable card",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
