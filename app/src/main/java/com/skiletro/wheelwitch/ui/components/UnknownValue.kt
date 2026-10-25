package com.skiletro.wheelwitch.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.skiletro.wheelwitch.R

private const val UNKNOWN = "Unknown"

@Composable
fun unknownOrValue(value: String): String =
  if (value.isBlank() || value == UNKNOWN) stringResource(R.string.common_unknown) else value