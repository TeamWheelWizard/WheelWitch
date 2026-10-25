package com.skiletro.wheelwitch.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Build a modifier that morphs the title text from the Online Menu hub
 * into the destination page's screen header.
 *
 * The caller supplies the shared element key. The
 * returned modifier is a no-op when either [sharedTransitionScope] or
 * [animatedContentScope] is null, so screens can be used standalone
 * without shared transitions.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTitleModifier(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedContentScope: AnimatedVisibilityScope?
): Modifier {
    if (sharedTransitionScope == null || animatedContentScope == null) {
        return Modifier
    }
    val state = sharedTransitionScope.rememberSharedContentState(key)
    return with(sharedTransitionScope) {
        Modifier.sharedElement(state, animatedContentScope)
    }
}
