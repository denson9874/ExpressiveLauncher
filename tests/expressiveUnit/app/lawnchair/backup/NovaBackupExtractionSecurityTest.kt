/*
 * Copyright 2026 Daryl Denson and Expressive Launcher contributors.
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/denson9874/ExpressiveLauncher
 */

package app.lawnchair.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NovaBackupExtractionSecurityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun traversalEntriesAreSkippedWhileExpectedFilesStillExtract() {
        val outside = temporaryFolder.newFile("outside.txt").apply { writeText("unchanged") }
        val destination = temporaryFolder.newFolder("import")
        val archive = archive(
            "../outside.txt" to "bad",
            outside.absolutePath to "bad",
            "nested/../../outside.txt" to "bad",
            "nested/nova.db" to "bad",
            "..\\outside.txt" to "bad",
            "%2e%2e/outside.txt" to "bad",
            "nova.xml/" to "",
            "unrelated-file" to "ignored",
            "nova.xml" to "settings",
            "nova.db" to "database",
        )

        extract(archive, destination, setOf("nova.xml", "nova.db"))

        assertThat(outside.readText()).isEqualTo("unchanged")
        assertThat(destination.list()!!.toList()).containsExactly("nova.xml", "nova.db")
        assertThat(File(destination, "nova.xml").readText()).isEqualTo("settings")
        assertThat(File(destination, "nova.db").readText()).isEqualTo("database")
    }

    @Test
    fun restoreExtractsOnlyTheRequestedDatabase() {
        val destination = temporaryFolder.newFolder("restore")
        extract(archive("nova.xml" to "settings", "nova.db" to "database"), destination, setOf("nova.db"))
        assertThat(destination.list()!!.toList()).containsExactly("nova.db")
        assertThat(File(destination, "nova.db").readText()).isEqualTo("database")
    }

    @Test
    fun allowedFilenameCannotOverwriteAnOutsideSymlinkTarget() {
        val destination = temporaryFolder.newFolder("import")
        // A sibling sharing the directory prefix also exercises the separator boundary.
        val sibling = temporaryFolder.newFolder("import-sibling")
        val outside = File(sibling, "nova.db").apply { writeText("unchanged") }
        Files.createSymbolicLink(File(destination, "nova.db").toPath(), outside.toPath())

        val failure = assertThrows(InvocationTargetException::class.java) {
            extract(archive("nova.db" to "bad"), destination, setOf("nova.db"))
        }

        assertThat(failure.cause).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(outside.readText()).isEqualTo("unchanged")
    }

    private fun archive(vararg entries: Pair<String, String>): File =
        temporaryFolder.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun extract(archive: File, destination: File, allowedNames: Set<String>) {
        val uri = Uri.fromFile(archive)
        val converter = NovaBackupConverter(ApplicationProvider.getApplicationContext(), uri)
        // Exercise the actual shared extraction boundary without triggering a launcher restore.
        val method = NovaBackupConverter::class.java.getDeclaredMethod(
            "extractFromZip", Uri::class.java, File::class.java, Set::class.java,
        )
        method.isAccessible = true
        method.invoke(converter, uri, destination, allowedNames)
    }
}
