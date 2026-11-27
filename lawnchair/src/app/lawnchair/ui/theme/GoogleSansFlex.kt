package app.lawnchair.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.android.launcher3.R

// Thanks https://gitlab.com/nongthaihoang/google-sans-prime/-/commit/0f7b9d29f6ffe5005d22d81af264a86106f2450d
@OptIn(ExperimentalTextApi::class)
private fun googleSansFlexFamily(
    weight: FontWeight,
    opticalSize: TextUnit,
    emphasized: Boolean = false,
): FontFamily = FontFamily(
    Font(
        R.font.googlesansflex_variable,
        variationSettings = if (emphasized) {
            FontVariation.Settings(
                FontVariation.weight(weight.weight),
                ExtendedFontVariation.round(100),
                FontVariation.width(100f),
                FontVariation.grade(0),
                FontVariation.opticalSizing(opticalSize),
            )
        } else {
            FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.width(100f),
                FontVariation.grade(0),
                FontVariation.opticalSizing(opticalSize),
            )
        },
    ),
)

object GoogleSansFlex {
    object Display {
        object Normal {
            val Large = googleSansFlexFamily(FontWeight.Normal, 57.sp)
            val Medium = googleSansFlexFamily(FontWeight.Normal, 45.sp)
            val Small = googleSansFlexFamily(FontWeight.Normal, 36.sp)
        }

        object Emphasized {
            val Large = googleSansFlexFamily(FontWeight.Medium, 57.sp, emphasized = true)
            val Medium = googleSansFlexFamily(FontWeight.Medium, 45.sp, emphasized = true)
            val Small = googleSansFlexFamily(FontWeight.Medium, 36.sp, emphasized = true)
        }
    }

    object Headline {
        object Normal {
            val Large = googleSansFlexFamily(FontWeight.Normal, 32.sp)
            val Medium = googleSansFlexFamily(FontWeight.Normal, 28.sp)
            val Small = googleSansFlexFamily(FontWeight.Normal, 24.sp)
        }

        object Emphasized {
            val Large = googleSansFlexFamily(FontWeight.Medium, 32.sp, emphasized = true)
            val Medium = googleSansFlexFamily(FontWeight.Medium, 28.sp, emphasized = true)
            val Small = googleSansFlexFamily(FontWeight.Medium, 24.sp, emphasized = true)
        }
    }

    object Title {
        object Normal {
            val Large = googleSansFlexFamily(FontWeight.Medium, 22.sp)
            val Medium = googleSansFlexFamily(FontWeight.Medium, 16.sp)
            val Small = googleSansFlexFamily(FontWeight.Medium, 14.sp)
        }

        object Emphasized {
            val Large = googleSansFlexFamily(FontWeight.Medium, 22.sp, emphasized = true)
            val Medium = googleSansFlexFamily(FontWeight.Medium, 16.sp, emphasized = true)
            val Small = googleSansFlexFamily(FontWeight.Medium, 14.sp, emphasized = true)
        }
    }

    object Body {
        object Normal {
            val Large = googleSansFlexFamily(FontWeight.Normal, 16.sp)
            val Medium = googleSansFlexFamily(FontWeight.Normal, 14.sp)
            val Small = googleSansFlexFamily(FontWeight.Normal, 12.sp)
        }

        object Emphasized {
            val Large = googleSansFlexFamily(FontWeight.Medium, 16.sp, emphasized = true)
            val Medium = googleSansFlexFamily(FontWeight.Medium, 14.sp, emphasized = true)
            val Small = googleSansFlexFamily(FontWeight.Medium, 12.sp, emphasized = true)
        }
    }

    object Label {
        object Normal {
            val Small = googleSansFlexFamily(FontWeight.Medium, 11.sp)
            val Medium = googleSansFlexFamily(FontWeight.Medium, 12.sp)
            val Large = googleSansFlexFamily(FontWeight.Medium, 14.sp)
        }

        object Emphasized {
            val Small = googleSansFlexFamily(FontWeight.SemiBold, 11.sp, emphasized = true)
            val Medium = googleSansFlexFamily(FontWeight.SemiBold, 12.sp, emphasized = true)
            val Large = googleSansFlexFamily(FontWeight.SemiBold, 14.sp, emphasized = true)
        }
    }
}
