package app.lawnchair.search.algorithms.engine.provider

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContactsSearchProviderTest {
    private lateinit var application: Application
    private lateinit var provider: FixtureContactsProvider

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        provider = FixtureContactsProvider()
        provider.attachInfo(application, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
    }

    @Test
    fun customPhoneLabel_keepsTheActualNumber() = runBlocking {
        provider.rows = listOf(dataRow(1, Phone.CONTENT_ITEM_TYPE, "2025550123", label = "Work"))

        val result = ContactsSearchProvider.findContactsByName(application, "Alex", 5).single()

        assertThat(result.number).isEqualTo("2025550123")
        assertThat(result.name).isEqualTo("Alex Parity")
        assertThat(result.contactId).isEqualTo("10")
        assertThat(provider.lastUri).isEqualTo(Data.CONTENT_URI)
        assertThat(provider.lastSelection).isEqualTo("${Data.DISPLAY_NAME} LIKE ?")
        assertThat(provider.lastSelectionArgs!!.toList()).containsExactly("%Alex%")
        assertThat(provider.lastCursor!!.isClosed).isTrue()
    }

    @Test
    fun emailAfterPhone_cannotReplaceTheDestination() = runBlocking {
        provider.rows = listOf(
            dataRow(1, Phone.CONTENT_ITEM_TYPE, "2025550123"),
            dataRow(2, Email.CONTENT_ITEM_TYPE, "alex@example.com", label = "Office"),
        )

        assertThat(ContactsSearchProvider.findContactsByName(application, "Alex", 5).single().number)
            .isEqualTo("2025550123")
    }

    @Test
    fun emailBeforePhone_stillFindsTheNumber() = runBlocking {
        provider.rows = listOf(
            dataRow(1, Email.CONTENT_ITEM_TYPE, "alex@example.com"),
            dataRow(2, Phone.CONTENT_ITEM_TYPE, "2025550123"),
        )

        assertThat(ContactsSearchProvider.findContactsByName(application, "Alex", 5).single().number)
            .isEqualTo("2025550123")
    }

    @Test
    fun emailOnlyContact_remainsAvailableWithoutAPhoneDestination() = runBlocking {
        provider.rows = listOf(dataRow(1, Email.CONTENT_ITEM_TYPE, "alex@example.com"))

        val result = ContactsSearchProvider.findContactsByName(application, "Alex", 5).single()

        assertThat(result.number).isEmpty()
        assertThat(result.name).isEqualTo("Alex Parity")
        assertThat(result.contactId).isEqualTo("10")
        assertThat(result.uri).isEqualTo("content://com.android.contacts/contacts/10/photo")
    }

    @Test
    fun blankPhoneRows_doNotReplaceAValidNumberOrTreatLabelsAsNumbers() = runBlocking {
        provider.rows = listOf(
            dataRow(1, Phone.CONTENT_ITEM_TYPE, "2025550123"),
            dataRow(2, Phone.CONTENT_ITEM_TYPE, null, label = "Office"),
            dataRow(3, Phone.CONTENT_ITEM_TYPE, "   ", label = "Travel"),
        )

        assertThat(ContactsSearchProvider.findContactsByName(application, "Alex", 5).single().number)
            .isEqualTo("2025550123")
    }

    @Test
    fun missingContactId_isSkippedWithoutDiscardingOtherContacts() = runBlocking {
        provider.rows = listOf(
            dataRow(1, Phone.CONTENT_ITEM_TYPE, "2025550100", contactId = null),
            dataRow(2, Phone.CONTENT_ITEM_TYPE, "2025550123"),
        )

        val result = ContactsSearchProvider.findContactsByName(application, "Alex", 5)

        assertThat(result.map { it.contactId }).containsExactly("10")
        assertThat(result.single().number).isEqualTo("2025550123")
    }

    @Test
    fun resultLimit_finishesSelectedContactEvenWhenOtherRowsIntervene() = runBlocking {
        provider.rows = listOf(
            dataRow(1, Email.CONTENT_ITEM_TYPE, "alex@example.com"),
            dataRow(2, Phone.CONTENT_ITEM_TYPE, "2025550199", contactId = "20"),
            dataRow(3, Phone.CONTENT_ITEM_TYPE, "2025550123"),
        )

        val result = ContactsSearchProvider.findContactsByName(application, "Alex", 1)

        assertThat(result.map { it.contactId }).containsExactly("10")
        assertThat(result.single().number).isEqualTo("2025550123")
    }

    @Test
    fun superPrimaryNumber_winsRegardlessOfCursorOrder() = runBlocking {
        val rows = listOf(
            dataRow(3, Phone.CONTENT_ITEM_TYPE, "2025550103"),
            dataRow(2, Phone.CONTENT_ITEM_TYPE, "2025550102", primary = true),
            dataRow(1, Phone.CONTENT_ITEM_TYPE, "2025550101", primary = true, superPrimary = true),
        )

        for (orderedRows in listOf(rows, rows.reversed())) {
            provider.rows = orderedRows
            assertThat(ContactsSearchProvider.findContactsByName(application, "Alex", 1).single().number)
                .isEqualTo("2025550101")
        }
    }

    @Test
    fun primaryNumberWins_andEqualPriorityUsesAStableDataId() = runBlocking {
        val rows = listOf(
            dataRow(1, Phone.CONTENT_ITEM_TYPE, "2025550101"),
            dataRow(20, Phone.CONTENT_ITEM_TYPE, "2025550120", primary = true),
            dataRow(3, Phone.CONTENT_ITEM_TYPE, "2025550103", primary = true),
        )

        for (orderedRows in listOf(rows, rows.reversed())) {
            provider.rows = orderedRows
            assertThat(ContactsSearchProvider.findContactsByName(application, "Alex", 5).single().number)
                .isEqualTo("2025550103")
        }
    }

    @Test
    fun excludedNameRows_keepExistingResultPolicy() = runBlocking {
        provider.rows = listOf(dataRow(1, StructuredName.CONTENT_ITEM_TYPE, "Alex Parity"))

        assertThat(ContactsSearchProvider.findContactsByName(application, "Alex", 5)).isEmpty()
    }

    @Test
    fun emptyQueryOrNonpositiveLimit_doesNotQueryContacts() = runBlocking {
        assertThat(ContactsSearchProvider.findContactsByName(application, " ", 5)).isEmpty()
        assertThat(ContactsSearchProvider.findContactsByName(application, "Alex", 0)).isEmpty()
        assertThat(ContactsSearchProvider.findContactsByName(application, "Alex", -1)).isEmpty()
        assertThat(provider.queryCount).isEqualTo(0)
    }

    @Test
    fun missingReadContactsPermission_remainsDeniedUntilGranted() {
        shadowOf(application).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertThat(ContactsSearchProvider.checkPermission(application)).isFalse()

        shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertThat(ContactsSearchProvider.checkPermission(application)).isTrue()
    }

    private fun dataRow(
        id: Long,
        mimeType: String,
        value: String?,
        contactId: String? = "10",
        label: String? = null,
        primary: Boolean = false,
        superPrimary: Boolean = false,
    ): Map<String, Any?> = mapOf(
        Data._ID to id,
        Data.CONTACT_ID to contactId,
        Data.DISPLAY_NAME to "Alex Parity",
        Data.DATA1 to value,
        Data.DATA3 to label,
        Data.DATA5 to null,
        Data.MIMETYPE to mimeType,
        Data.PHOTO_URI to "content://com.android.contacts/contacts/10/photo",
        Data.IS_PRIMARY to if (primary) 1 else 0,
        Data.IS_SUPER_PRIMARY to if (superPrimary) 1 else 0,
    )

    /** Returns real cursors in the requested projection; production owns all row interpretation. */
    class FixtureContactsProvider : ContentProvider() {
        var rows: List<Map<String, Any?>> = emptyList()
        var queryCount = 0
        var lastUri: Uri? = null
        var lastSelection: String? = null
        var lastSelectionArgs: Array<out String>? = null
        var lastCursor: MatrixCursor? = null

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            queryCount++
            lastUri = uri
            lastSelection = selection
            lastSelectionArgs = selectionArgs
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).also { cursor ->
                rows.forEach { row -> cursor.addRow(columns.map { row[it] }.toTypedArray()) }
                lastCursor = cursor
            }
        }

        override fun getType(uri: Uri): String = Data.CONTENT_TYPE
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    }
}
