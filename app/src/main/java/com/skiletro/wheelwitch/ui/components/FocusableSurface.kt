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
 * A focusable card that draws a focus border when focused and optionally handles click events.
 *
 * Replaces the repeated `.clickable {} + .focusable() + .onFocusChanged {} + .focusBorder()` +
 * `Surface` pattern that was copy-pasted across screens.
 *
 * - Clickable cards use clickable as their sole focus target; passive cards use one explicit
 *   focusable target.
 * - When [selected] is true, the focus border is shown regardless of focus state (used for things
 *   like a currently-selected license slot).
 * - Focus state is hoisted to a `remember` scope (NOT inside the clickable lambda) so the state
 *   survives recomposition correctly.
 * - The focus border is drawn with [shape], which may be a [RoundedCornerShape] or [CircleShape]
 *   (or falls back to [buttonShape]).
 */
@Composable
fun FocusableSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = buttonShape,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit,
) {
  var isFocused by remember { mutableStateOf(false) }

  val focusTargetModifier =
      if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
      } else {
        Modifier.focusable(enabled = enabled)
      }

  Surface(
      shape = shape,
      color = color,
      modifier =
          modifier
              .onFocusChanged { isFocused = it.isFocused }
              .then(focusTargetModifier)
              .focusBorder(
                  isFocused = isFocused || selected,
                  shape = shape,
              ),
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
