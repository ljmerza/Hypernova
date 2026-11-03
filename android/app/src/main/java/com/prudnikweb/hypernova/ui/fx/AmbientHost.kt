package com.prudnikweb.hypernova.ui.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.prudnikweb.hypernova.ui.theme.LocalMotion

@Composable
fun ProvideAmbientClock(clock: AmbientClock, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, motion) {
        if (motion.reduced) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            clock.run({ FrameBudget.fps(it, motion.heat, motion.powerSave) }, FrameBudget.grainFps(motion.heat, motion.powerSave))
        }
    }
    CompositionLocalProvider(LocalAmbient provides clock, content = content)
}
