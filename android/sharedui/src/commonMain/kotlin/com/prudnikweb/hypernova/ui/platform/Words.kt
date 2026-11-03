package com.prudnikweb.hypernova.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings

/* The app's words as the drawing reads them (UiWords). */

@Composable
@ReadOnlyComposable
fun stringResource(key: Str): String = SharedUi.platform.words.text(key)

@Composable
@ReadOnlyComposable
fun stringResource(key: Str, vararg args: Any): String = SharedUi.platform.words.text(key, *args)

@Composable
@ReadOnlyComposable
fun pluralStringResource(key: Plural, count: Int): String = SharedUi.platform.words.quantity(key, count)

@Composable
@ReadOnlyComposable
fun pluralStringResource(key: Plural, count: Int, vararg args: Any): String = SharedUi.platform.words.quantity(key, count, *args)

/** What a screen read from `LocalContext.current.resources`: these words, for a line built from several. */
@Composable
@ReadOnlyComposable
fun wordsNow(): Strings = SharedUi.platform.words.strings()
