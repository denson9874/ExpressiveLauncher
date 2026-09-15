package app.lawnchair

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LawnchairDatabaseCleanupTest {

    @Test
    fun activeDatabaseAndItsSidecarsAreNeverDeleted() {
        val activeDatabase = "launcher_5_by_5.db"

        assertThat(shouldDeleteLauncherDatabaseFile(activeDatabase, activeDatabase)).isFalse()
        assertThat(shouldDeleteLauncherDatabaseFile("$activeDatabase-wal", activeDatabase)).isFalse()
        assertThat(shouldDeleteLauncherDatabaseFile("$activeDatabase-shm", activeDatabase)).isFalse()
        assertThat(shouldDeleteLauncherDatabaseFile("$activeDatabase-journal", activeDatabase)).isFalse()
    }

    @Test
    fun obsoleteLauncherDatabaseIsDeleted() {
        assertThat(
                shouldDeleteLauncherDatabaseFile(
                    fileName = "launcher_4_by_5.db",
                    activeDbName = "launcher_5_by_5.db",
                ),
            )
            .isTrue()
    }

    @Test
    fun unrelatedDatabaseIsPreserved() {
        assertThat(
                shouldDeleteLauncherDatabaseFile(
                    fileName = "lawnchair_settings.db",
                    activeDbName = "launcher_5_by_5.db",
                ),
            )
            .isFalse()
    }
}
