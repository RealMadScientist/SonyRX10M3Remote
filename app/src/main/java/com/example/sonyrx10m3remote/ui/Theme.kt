package com.example.sonyrx10m3remote.ui.theme

import android.util.TypedValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.sonyrx10m3remote.R

@Composable
fun RX10M3RemoteTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val typedValue = remember { TypedValue() }
    val theme = context.theme

    // Helper to resolve a color attribute to Compose Color
    fun resolveColor(attrRes: Int): Color {
        val resolved = theme.resolveAttribute(attrRes, typedValue, true)
        return if (resolved) {
            Color(typedValue.data)
        } else {
            Color.Unspecified
        }
    }

    // Get colors dynamically from theme attributes
    val primary = resolveColor(R.attr.colorPrimary)
    val primaryVariant = resolveColor(R.attr.colorPrimaryVariant)
    val onPrimary = resolveColor(R.attr.colorOnPrimary)

    val secondary = resolveColor(R.attr.colorSecondary)
    val secondaryVariant = resolveColor(R.attr.colorSecondaryVariant)
    val onSecondary = resolveColor(R.attr.colorOnSecondary)

    val isDark = isSystemInDarkTheme()

    // Compose Material3 ColorScheme doesn't have primaryVariant or secondaryVariant directly,
    // but you can map primaryVariant to primaryContainer, secondaryVariant to secondaryContainer
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryVariant,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryVariant,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryVariant,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryVariant,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}