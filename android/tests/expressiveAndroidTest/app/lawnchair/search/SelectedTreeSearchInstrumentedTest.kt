package app.lawnchair.search

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.search.algorithms.engine.provider.FileSearchProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedTreeSearchInstrumentedTest {

    @Test
    fun selectedTreeSearch_traversesNestedDocumentsAndReturnsContentUris() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().context
            val authority = "${context.packageName}.filetree"
            val treeUri = DocumentsContract.buildTreeDocumentUri(authority, FakeSelectedTreeProvider.ROOT_ID)

            val results = FileSearchProvider.querySelectedTree(
                context = context,
                treeUri = treeUri,
                keyword = "proof",
                maxResult = 10,
            ).toList()

            assertThat(results.map { it.name })
                .containsExactly("proof-root.txt", "deep-proof.pdf")
            assertThat(results.map { it.contentUri }).doesNotContain(null)
            assertThat(results.map { Uri.parse(requireNotNull(it.contentUri)).authority }.distinct())
                .containsExactly(authority)
        }
    }

    @Test
    fun selectedTreeSearch_requeriesCloudProviderWhenChildrenAreStillLoading() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().context
            val authority = "${context.packageName}.filetree"
            context.contentResolver.call(
                authority,
                FakeSelectedTreeProvider.METHOD_RESET_LOADING_QUERY_COUNT,
                null,
                null,
            )
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                authority,
                FakeSelectedTreeProvider.LOADING_ROOT_ID,
            )

            val results = FileSearchProvider.querySelectedTree(
                context = context,
                treeUri = treeUri,
                keyword = "proof",
                maxResult = 10,
            ).toList()

            assertThat(results.map { it.name }).containsExactly("cloud-proof.txt")
            val providerQueryCount = context.contentResolver.call(
                authority,
                FakeSelectedTreeProvider.METHOD_GET_LOADING_QUERY_COUNT,
                null,
                null,
            )?.getInt(FakeSelectedTreeProvider.KEY_LOADING_QUERY_COUNT)
            assertThat(providerQueryCount).isAtLeast(2)
        }
    }
}
