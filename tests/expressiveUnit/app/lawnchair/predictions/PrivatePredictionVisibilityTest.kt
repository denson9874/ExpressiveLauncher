package app.lawnchair.predictions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivatePredictionVisibilityTest {

    @Test
    fun mainProfileIsUnaffectedByPrivateState() {
        assertTrue(
            shouldIncludePredictionUser(
                isPrivateProfile = false,
                isQuietModeEnabled = true,
                isPrivateSpaceHidden = true,
            ),
        )
    }

    @Test
    fun unlockedVisiblePrivateProfileCanBePredicted() {
        assertTrue(
            shouldIncludePredictionUser(
                isPrivateProfile = true,
                isQuietModeEnabled = false,
                isPrivateSpaceHidden = false,
            ),
        )
    }

    @Test
    fun lockedPrivateProfileIsExcluded() {
        assertFalse(
            shouldIncludePredictionUser(
                isPrivateProfile = true,
                isQuietModeEnabled = true,
                isPrivateSpaceHidden = false,
            ),
        )
    }

    @Test
    fun hiddenPrivateProfileIsExcluded() {
        assertFalse(
            shouldIncludePredictionUser(
                isPrivateProfile = true,
                isQuietModeEnabled = false,
                isPrivateSpaceHidden = true,
            ),
        )
    }
}
