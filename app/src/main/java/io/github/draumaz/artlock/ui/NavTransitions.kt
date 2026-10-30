package io.github.draumaz.artlock.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry

private typealias Enter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
private typealias Exit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

/*
 * Screen transitions in the style of Android's predictive back: going back, the top screen shrinks
 * and slides toward the edge with rounded corners over the previous screen, and only fades out
 * at the very end. The NavHost seeks these with the back gesture's progress, so every part shares
 * one fixed duration (springs would make the same gesture progress look different each time).
 */
private const val DURATION_MS = 400
private const val FADE_MS = 120

private fun <T> spec(delayMillis: Int = 0, durationMillis: Int = DURATION_MS - delayMillis) =
    tween<T>(durationMillis, delayMillis, FastOutSlowInEasing)

val navEnter: Enter = { slideInHorizontally(spec()) { it / 10 } + fadeIn(spec()) }
val navExit: Exit = { slideOutHorizontally(spec()) { -it / 10 } + fadeOut(spec()) }

// The previous screen is fully visible behind the one being swiped away
val navPopEnter: Enter = { EnterTransition.None }
val navPopExit: Exit = {
    scaleOut(spec(), targetScale = 0.9f) +
        slideOutHorizontally(spec()) { it / 10 } +
        fadeOut(spec(delayMillis = DURATION_MS - FADE_MS))
}

/** Rounds the screen's corners as it leaves, so a shrinking screen reads as a card. */
@Composable
fun AnimatedContentScope.TransitionCorners(content: @Composable () -> Unit) {
    val corner by transition.animateDp(transitionSpec = { spec() }, label = "screenCorner") { state ->
        if (state == EnterExitState.PostExit) 32.dp else 0.dp
    }
    Box(Modifier.clip(RoundedCornerShape(corner))) {
        content()
    }
}
