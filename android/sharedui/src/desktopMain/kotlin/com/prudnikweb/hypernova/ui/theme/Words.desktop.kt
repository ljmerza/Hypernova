package com.prudnikweb.hypernova.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings

/** The last computer's words: it changes when another server says what it is, which is seldom. */
private var worded: Pair<String, Strings>? = null

private fun wordedFor(computer: String): Strings = worded?.takeIf { it.first == computer }?.second
    ?: ComputerWords.wordedFor(Core.platform.strings, computer).also { worded = computer to it }

@Composable
@ReadOnlyComposable
actual fun wordsNow(): Strings = wordedFor(ComputerWords.platform)

@Composable
@ReadOnlyComposable
actual fun stringResource(key: Str): String = wordsNow().getString(key)

@Composable
@ReadOnlyComposable
actual fun stringResource(key: Str, vararg args: Any): String = wordsNow().getString(key, *args)

@Composable
@ReadOnlyComposable
actual fun pluralStringResource(key: Plural, count: Int): String = wordsNow().getQuantityString(key, count)

@Composable
@ReadOnlyComposable
actual fun pluralStringResource(key: Plural, count: Int, vararg args: Any): String = wordsNow().getQuantityString(key, count, *args)
