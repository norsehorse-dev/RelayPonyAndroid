package com.relaypony.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.relaypony.android.R
import java.text.Normalizer
import java.util.Locale

/** A list this long gets a search field; shorter ones are quicker to read than to search. */
const val DEVICE_SEARCH_MIN = 5

/** Lowercased with accents stripped, so "jose" finds "Jos\u00e9". */
private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)

/**
 * Whether a device matches what was typed. Every word of [query] has to appear somewhere in
 * [fields] (the name shown, and the name the device gave when that differs), in any order.
 */
fun matchesDeviceQuery(query: String, vararg fields: String): Boolean {
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = fields.map { fold(it) }
    return words.all { word -> haystack.any { it.contains(word) } }
}

/** The search field above a list of paired devices. */
@Composable
fun DeviceSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.devices_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.devices_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}
