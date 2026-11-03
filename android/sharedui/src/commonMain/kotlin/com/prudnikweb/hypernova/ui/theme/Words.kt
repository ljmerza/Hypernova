package com.prudnikweb.hypernova.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings

/*
 * The app's words as the shared drawing reads them, named by resource ([Str], [Plural]). On the phone these are Compose's
 * own resource calls, so a screen reads what it always read; on the desktop they're the same text from the same files
 * (DesktopStrings), reworded for the computer on screen as the phone's Resources are.
 */

@Composable
@ReadOnlyComposable
expect fun stringResource(key: Str): String

@Composable
@ReadOnlyComposable
expect fun stringResource(key: Str, vararg args: Any): String

@Composable
@ReadOnlyComposable
expect fun pluralStringResource(key: Plural, count: Int): String

@Composable
@ReadOnlyComposable
expect fun pluralStringResource(key: Plural, count: Int, vararg args: Any): String

/** What a screen read from `LocalContext.current.resources`: these words, for a line built from several. */
@Composable
@ReadOnlyComposable
expect fun wordsNow(): Strings
