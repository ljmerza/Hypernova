package com.prudnikweb.hypernova.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings

@Composable
@ReadOnlyComposable
actual fun stringResource(key: Str): String = androidx.compose.ui.res.stringResource(AndroidUi.stringId(key))

@Composable
@ReadOnlyComposable
actual fun stringResource(key: Str, vararg args: Any): String = androidx.compose.ui.res.stringResource(AndroidUi.stringId(key), *args)

@Composable
@ReadOnlyComposable
actual fun pluralStringResource(key: Plural, count: Int): String = androidx.compose.ui.res.pluralStringResource(AndroidUi.pluralId(key), count)

@Composable
@ReadOnlyComposable
actual fun pluralStringResource(key: Plural, count: Int, vararg args: Any): String = androidx.compose.ui.res.pluralStringResource(AndroidUi.pluralId(key), count, *args)

@Composable
@ReadOnlyComposable
actual fun wordsNow(): Strings = AndroidUi.words(LocalContext.current.resources)
