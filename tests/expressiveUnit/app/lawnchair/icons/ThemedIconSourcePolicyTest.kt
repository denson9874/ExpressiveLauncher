package app.lawnchair.icons

import app.lawnchair.util.Constants.LAWNICONS_PACKAGE_NAME
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemedIconSourcePolicyTest {
    @Test
    fun lawniconsSelectedAsNormalPack_usesNativeArtworkForUnthemedSurfaces() {
        assertThat(unthemedIconPackPackage(LAWNICONS_PACKAGE_NAME)).isEmpty()
        assertThat(themedIconSourcePackage(LAWNICONS_PACKAGE_NAME, ""))
            .isEqualTo(LAWNICONS_PACKAGE_NAME)
    }

    @Test
    fun colorfulNormalPack_keepsItsArtworkAndIsNotAnImplicitMonochromeSource() {
        assertThat(unthemedIconPackPackage("example.colorful.pack"))
            .isEqualTo("example.colorful.pack")
        assertThat(themedIconSourcePackage("example.colorful.pack", "")).isEmpty()
    }

    @Test
    fun onlyExactLawniconsPackage_isTreatedAsMonochromeOnly() {
        assertThat(unthemedIconPackPackage("$LAWNICONS_PACKAGE_NAME.extra"))
            .isEqualTo("$LAWNICONS_PACKAGE_NAME.extra")
        assertThat(themedIconSourcePackage("$LAWNICONS_PACKAGE_NAME.extra", "")).isEmpty()
    }

    @Test
    fun explicitThemedSource_takesPriorityOverNormalPack() {
        assertThat(themedIconSourcePackage(LAWNICONS_PACKAGE_NAME, "example.mono.pack"))
            .isEqualTo("example.mono.pack")
        assertThat(themedIconSourcePackage("example.colorful.pack", "example.mono.pack"))
            .isEqualTo("example.mono.pack")
        assertThat(unthemedIconPackPackage("")).isEmpty()
        assertThat(themedIconSourcePackage("", "")).isEmpty()
    }

    @Test
    fun unchangedEnabledSource_reusesItsLoadedMap() {
        val cache = ThemedIconMapCache<String>()
        var loads = 0
        val load: (String) -> Map<String, String> = { source ->
            loads++
            mapOf("example.app" to source)
        }

        val first = cache.get(true, "sourceA", load)
        val second = cache.get(true, "sourceA", load)

        assertThat(second).isSameInstanceAs(first)
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun disablingThenReenabling_reloadsTheSourceInsteadOfRetainingAnEmptyMap() {
        val cache = ThemedIconMapCache<Int>()
        var loads = 0
        val load: (String) -> Map<String, Int> = { mapOf("example.app" to ++loads) }

        assertThat(cache.get(true, "sourceA", load)).containsEntry("example.app", 1)
        assertThat(cache.get(false, "sourceA", load)).isEmpty()
        assertThat(loads).isEqualTo(1)
        assertThat(cache.get(true, "sourceA", load)).containsEntry("example.app", 2)
    }

    @Test
    fun disabledCache_doesNotLoadAndReenableUsesTheCurrentSource() {
        val cache = ThemedIconMapCache<String>()
        val loaded = mutableListOf<String>()
        val load: (String) -> Map<String, String> = { source ->
            loaded += source
            mapOf("example.app" to source)
        }

        assertThat(cache.get(false, "sourceA", load)).isEmpty()
        assertThat(cache.get(false, "sourceB", load)).isEmpty()
        assertThat(loaded).isEmpty()
        assertThat(cache.get(true, "sourceB", load)).containsEntry("example.app", "sourceB")
        assertThat(loaded).containsExactly("sourceB")
    }

    @Test
    fun switchingFromOneSourceToAnotherAndThenSystem_removesPreviousEntries() {
        val cache = ThemedIconMapCache<String>()
        val loaded = mutableListOf<String>()
        val load: (String) -> Map<String, String> = { source ->
            loaded += source
            when (source) {
                "sourceA" -> mapOf("only.a" to "a")
                "sourceB" -> mapOf("only.b" to "b")
                else -> mapOf("system.app" to "system")
            }
        }

        assertThat(cache.get(true, "sourceA", load)).containsExactly("only.a", "a")
        assertThat(cache.get(true, "sourceB", load)).containsExactly("only.b", "b")
        assertThat(cache.get(true, "", load)).containsExactly("system.app", "system")
        assertThat(loaded).containsExactly("sourceA", "sourceB", "").inOrder()
    }

    @Test
    fun clearingAfterPackageUpdate_reloadsEvenWhenPackageNameDoesNotChange() {
        val cache = ThemedIconMapCache<Int>()
        var version = 1
        val load: (String) -> Map<String, Int> = { mapOf("example.app" to version) }

        assertThat(cache.get(true, "sourceA", load)).containsEntry("example.app", 1)
        version = 2
        cache.clear()

        assertThat(cache.get(true, "sourceA", load)).containsEntry("example.app", 2)
    }
}
