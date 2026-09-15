package app.lawnchair.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.lawnchair.search.adapter.createFileViewIntent
import app.lawnchair.search.algorithms.data.FileInfo
import app.lawnchair.search.algorithms.engine.provider.mergeFileResultSources
import app.lawnchair.ui.preferences.components.search.applyFolderGrantToSearchPreferences
import app.lawnchair.ui.util.isPlayStoreChannel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FileAccessPolicyTest {

    @Test
    fun playPolicy_recognizesBothPlayDistributedChannels() {
        assertThat(isPlayStoreChannel("play")).isTrue()
        assertThat(isPlayStoreChannel("expressive")).isTrue()
        assertThat(isPlayStoreChannel("github")).isFalse()
        assertThat(isPlayStoreChannel("nightly")).isFalse()
    }

    @Test
    fun persistedFolderGrant_enablesFolderProviderAndMainFileSearch() {
        var selectedFolderEnabled = false
        var mainFilesEnabled = false

        val applied = applyFolderGrantToSearchPreferences(
            grantPersisted = true,
            enableSelectedFolderSearch = { selectedFolderEnabled = true },
            enableMainFilesSearch = { mainFilesEnabled = true },
        )

        assertThat(applied).isTrue()
        assertThat(selectedFolderEnabled).isTrue()
        assertThat(mainFilesEnabled).isTrue()
    }

    @Test
    fun failedFolderGrant_doesNotEnableEitherSearchPreference() {
        var selectedFolderEnabled = false
        var mainFilesEnabled = false

        val applied = applyFolderGrantToSearchPreferences(
            grantPersisted = false,
            enableSelectedFolderSearch = { selectedFolderEnabled = true },
            enableMainFilesSearch = { mainFilesEnabled = true },
        )

        assertThat(applied).isFalse()
        assertThat(selectedFolderEnabled).isFalse()
        assertThat(mainFilesEnabled).isFalse()
    }

    @Test
    fun revokedOldTree_doesNotInvalidateNewlySelectedTree() {
        val oldTree = Uri.parse("content://documents/tree/old")
        val newTree = Uri.parse("content://documents/tree/new")

        assertThat(shouldInvalidateSelectedTreeAccess(newTree, oldTree)).isFalse()
        assertThat(shouldInvalidateSelectedTreeAccess(oldTree, oldTree)).isTrue()
    }

    @Test
    fun expressivePolicy_doesNotTreatUndeclaredAllFilesAppOpAsAccess() {
        val state = resolveAllFilesAccessState(
            sdkInt = 37,
            canRequestManageAllFiles = false,
            isExternalStorageManager = true,
            hasSelectedTreeAccess = false,
            hasLegacyReadPermission = false,
        )

        assertThat(state).isEqualTo(FileAccessState.Denied)
    }

    @Test
    fun persistedSelectedFolder_isPartialGeneralFileAccess() {
        val state = resolveAllFilesAccessState(
            sdkInt = 37,
            canRequestManageAllFiles = false,
            isExternalStorageManager = false,
            hasSelectedTreeAccess = true,
            hasLegacyReadPermission = false,
        )

        assertThat(state).isEqualTo(FileAccessState.Partial)
    }

    @Test
    fun sideloadChannelWithGrantedSpecialAccess_isFull() {
        val state = resolveAllFilesAccessState(
            sdkInt = 37,
            canRequestManageAllFiles = true,
            isExternalStorageManager = true,
            hasSelectedTreeAccess = false,
            hasLegacyReadPermission = false,
        )

        assertThat(state).isEqualTo(FileAccessState.Full)
    }

    @Test
    fun playPolicy_ignoresVisualMediaPermissionState() {
        val state = resolveVisualMediaAccessState(
            canRequestBroadVisualMedia = false,
            hasAllFilesAccess = false,
            hasReadImagesPermission = true,
            hasReadVideoPermission = true,
            hasSelectedVisualMediaPermission = true,
        )

        assertThat(state).isEqualTo(FileAccessState.Denied)
    }

    @Test
    fun sideloadChannelWithBothVisualCollections_isFull() {
        val state = resolveVisualMediaAccessState(
            canRequestBroadVisualMedia = true,
            hasAllFilesAccess = false,
            hasReadImagesPermission = true,
            hasReadVideoPermission = true,
            hasSelectedVisualMediaPermission = false,
        )

        assertThat(state).isEqualTo(FileAccessState.Full)
    }

    @Test
    fun sideloadChannelWithOnlyImages_isPartial() {
        val state = resolveVisualMediaAccessState(
            canRequestBroadVisualMedia = true,
            hasAllFilesAccess = false,
            hasReadImagesPermission = true,
            hasReadVideoPermission = false,
            hasSelectedVisualMediaPermission = false,
        )

        assertThat(state).isEqualTo(FileAccessState.Partial)
    }

    @Test
    fun sideloadChannelWithOnlyVideo_isPartial() {
        val state = resolveVisualMediaAccessState(
            canRequestBroadVisualMedia = true,
            hasAllFilesAccess = false,
            hasReadImagesPermission = false,
            hasReadVideoPermission = true,
            hasSelectedVisualMediaPermission = false,
        )

        assertThat(state).isEqualTo(FileAccessState.Partial)
    }

    @Test
    fun sideloadChannelWithSelectedVisualMedia_isPartial() {
        val state = resolveVisualMediaAccessState(
            canRequestBroadVisualMedia = true,
            hasAllFilesAccess = false,
            hasReadImagesPermission = false,
            hasReadVideoPermission = false,
            hasSelectedVisualMediaPermission = true,
        )

        assertThat(state).isEqualTo(FileAccessState.Partial)
    }

    @Test
    fun imageOnlyGrant_queriesOnlyTheImageCollection() {
        val access = resolveVisualMediaCollectionAccess(
            canRequestBroadVisualMedia = true,
            hasAllFilesAccess = false,
            hasReadImagesPermission = true,
            hasReadVideoPermission = false,
            hasSelectedVisualMediaPermission = false,
        )

        assertThat(access.canReadImages).isTrue()
        assertThat(access.canReadVideos).isFalse()
    }

    @Test
    fun videoOnlyGrant_queriesOnlyTheVideoCollection() {
        val access = resolveVisualMediaCollectionAccess(
            canRequestBroadVisualMedia = true,
            hasAllFilesAccess = false,
            hasReadImagesPermission = false,
            hasReadVideoPermission = true,
            hasSelectedVisualMediaPermission = false,
        )

        assertThat(access.canReadImages).isFalse()
        assertThat(access.canReadVideos).isTrue()
    }

    @Test
    fun selectedVisualMediaGrant_canQueryBothFilteredCollections() {
        val access = resolveVisualMediaCollectionAccess(
            canRequestBroadVisualMedia = true,
            hasAllFilesAccess = false,
            hasReadImagesPermission = false,
            hasReadVideoPermission = false,
            hasSelectedVisualMediaPermission = true,
        )

        assertThat(access.canReadImages).isTrue()
        assertThat(access.canReadVideos).isTrue()
    }

    @Test
    fun crossSourceMerge_deduplicatesSafAndMediaStoreIdentity() {
        val safResult = FileInfo(
            fileId = "document:proof",
            path = "content://documents/document/proof",
            name = "proof.mp3",
            size = 42,
            dateModified = 10_000,
            mimeType = "audio/mpeg",
            contentUri = "content://documents/document/proof",
        )
        val mediaStoreResult = safResult.copy(
            fileId = "17",
            path = "/storage/emulated/0/Music/proof.mp3",
            contentUri = null,
        )

        val merged = mergeFileResultSources(
            sources = listOf(listOf(safResult), listOf(mediaStoreResult)),
            maxResults = 10,
        )

        assertThat(merged).containsExactly(safResult)
    }

    @Test
    fun providerMerge_roundRobinsBeforeApplyingResultLimit() {
        val treeFirst = testFile("tree-first.txt", "content://documents/tree-first")
        val treeSecond = testFile("tree-second.txt", "content://documents/tree-second")
        val mediaFirst = testFile("media-first.txt", "/storage/emulated/0/media-first.txt")

        val merged = mergeFileResultSources(
            sources = listOf(listOf(treeFirst, treeSecond), listOf(mediaFirst)),
            maxResults = 2,
        )

        assertThat(merged).containsExactly(treeFirst, mediaFirst).inOrder()
    }

    @Test
    fun manageAllFilesIntent_prefersAppPageAndProvidesOemFallback() {
        val intents = manageAllFilesAccessIntents("dev.launcher.expressive.l3")

        assertThat(intents).hasSize(2)
        assertThat(intents[0].action)
            .isEqualTo(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        assertThat(intents[0].data?.schemeSpecificPart)
            .isEqualTo("dev.launcher.expressive.l3")
        assertThat(intents[1].action)
            .isEqualTo(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        assertThat(intents[1].data).isNull()
        intents.forEach { intent ->
            assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
            assertThat(intent.flags and Intent.FLAG_ACTIVITY_NO_HISTORY).isNotEqualTo(0)
            assertThat(intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
        }
    }

    @Test
    fun selectedTreeSearchTarget_opensOriginalContentUriWithReadGrant() {
        val contentUri = "content://example.documents/tree/root/document/file%3Aproof"
        val intent = createFileViewIntent(
            FileInfo(
                fileId = "file:proof",
                path = contentUri,
                name = "proof.txt",
                size = 12,
                dateModified = 1_000,
                mimeType = "text/plain",
                contentUri = contentUri,
            ),
        )

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data).isEqualTo(Uri.parse(contentUri))
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    private fun testFile(name: String, path: String) = FileInfo(
        fileId = name,
        path = path,
        name = name,
        size = name.length.toLong(),
        dateModified = 1_000,
        mimeType = "text/plain",
        contentUri = path.takeIf { it.startsWith("content://") },
    )
}
