package com.michal.radiotest

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Pastelowe palety kolorów (jasna i ciemna). 0 = fiołkowa (domyślna, jak dotąd). */
object Motywy {
    val nazwy = listOf("Fiołkowy", "Miętowy", "Brzoskwiniowy", "Błękitny", "Różany", "Cappuccino")

    private class P(
        val pri: Long, val priC: Long, val onPriC: Long, val sec: Long, val secC: Long, val onSecC: Long,
        val tlo: Long, val pow: Long, val powW: Long, val powWW: Long,
        val dPri: Long, val dOnPri: Long, val dPriC: Long, val dOnPriC: Long, val dSec: Long, val dSecC: Long, val dOnSecC: Long,
        val dTlo: Long, val dPow: Long, val dPowW: Long, val dPowWW: Long,
    )

    private val palety = listOf(
        null,
        // miętowy
        P(0xFF2E6B5A, 0xFFB4EFD9, 0xFF00201A, 0xFF4B635B, 0xFFCDE8DD, 0xFF07201A,
          0xFFF5FBF7, 0xFFF5FBF7, 0xFFE3EEE8, 0xFFDDE8E2,
          0xFF98D3BD, 0xFF00382D, 0xFF0F5142, 0xFFB4EFD9, 0xFFB2CCC1, 0xFF344C44, 0xFFCDE8DD,
          0xFF0F1513, 0xFF0F1513, 0xFF1F2A26, 0xFF2A3430),
        // brzoskwiniowy
        P(0xFF8D4E2A, 0xFFFFDBCB, 0xFF341100, 0xFF765749, 0xFFFFDBCB, 0xFF2C160B,
          0xFFFFF8F5, 0xFFFFF8F5, 0xFFF6E7E0, 0xFFF0E0D9,
          0xFFFFB693, 0xFF552100, 0xFF703715, 0xFFFFDBCB, 0xFFE6BEAD, 0xFF5C4033, 0xFFFFDBCB,
          0xFF1A120E, 0xFF1A120E, 0xFF2A201B, 0xFF352A25),
        // błękitny
        P(0xFF3A6190, 0xFFD3E4FF, 0xFF001C38, 0xFF545F71, 0xFFD8E3F8, 0xFF111C2B,
          0xFFF8F9FF, 0xFFF8F9FF, 0xFFE6E8F0, 0xFFE0E2EA,
          0xFFA4C9FE, 0xFF00315B, 0xFF1F4876, 0xFFD3E4FF, 0xFFBCC7DB, 0xFF3C4758, 0xFFD8E3F8,
          0xFF111318, 0xFF111318, 0xFF1D2026, 0xFF282A30),
        // różany
        P(0xFF8E4957, 0xFFFFD9DF, 0xFF3A0716, 0xFF75565C, 0xFFFFD9DF, 0xFF2C1519,
          0xFFFFF8F8, 0xFFFFF8F8, 0xFFF5E5E7, 0xFFEFDFE1,
          0xFFFFB1C0, 0xFF551D2A, 0xFF72333F, 0xFFFFD9DF, 0xFFE4BDC3, 0xFF5B3F44, 0xFFFFD9DF,
          0xFF191113, 0xFF191113, 0xFF2A1F21, 0xFF35292B),
        // cappuccino (jasny: krem i kawa z mlekiem, ciemny: espresso)
        P(0xFF7A5640, 0xFFEADBCF, 0xFF2E1F15, 0xFF6E5E54, 0xFFEADBCF, 0xFF2E1F15,
          0xFFFBF7F3, 0xFFFBF7F3, 0xFFF1E9E2, 0xFFEADFD6,
          0xFFE2BFA6, 0xFF3F2A1C, 0xFF5C4030, 0xFFF1DFD2, 0xFFBCA89B, 0xFF4A382D, 0xFFF1DFD2,
          0xFF1A1411, 0xFF1A1411, 0xFF2A211C, 0xFF352A24),
    )

    fun schemat(paleta: Int, ciemny: Boolean): ColorScheme {
        val p = palety.getOrNull(paleta) ?: return if (ciemny) darkColorScheme() else lightColorScheme()
        return if (!ciemny) lightColorScheme(
            primary = Color(p.pri), onPrimary = Color.White, primaryContainer = Color(p.priC), onPrimaryContainer = Color(p.onPriC),
            secondary = Color(p.sec), onSecondary = Color.White, secondaryContainer = Color(p.secC), onSecondaryContainer = Color(p.onSecC),
            tertiary = Color(p.sec), background = Color(p.tlo), surface = Color(p.pow),
            surfaceVariant = Color(p.powWW), surfaceContainer = Color(p.powW), surfaceContainerHigh = Color(p.powW),
            surfaceContainerHighest = Color(p.powWW), surfaceContainerLow = Color(p.tlo),
        ) else darkColorScheme(
            primary = Color(p.dPri), onPrimary = Color(p.dOnPri), primaryContainer = Color(p.dPriC), onPrimaryContainer = Color(p.dOnPriC),
            secondary = Color(p.dSec), secondaryContainer = Color(p.dSecC), onSecondaryContainer = Color(p.dOnSecC),
            tertiary = Color(p.dSec), background = Color(p.dTlo), surface = Color(p.dPow),
            surfaceVariant = Color(p.dPowWW), surfaceContainer = Color(p.dPowW), surfaceContainerHigh = Color(p.dPowW),
            surfaceContainerHighest = Color(p.dPowWW), surfaceContainerLow = Color(p.dTlo),
        )
    }
}
