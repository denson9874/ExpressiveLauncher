package app.lawnchair.search.algorithms.engine.provider

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.algorithms.data.ContactInfo
import app.lawnchair.search.algorithms.engine.SearchPermission
import app.lawnchair.search.algorithms.engine.SearchProvider
import app.lawnchair.search.algorithms.engine.SearchResult
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ContactsSearchProvider : SearchProvider, SearchPermission {
    override val id: String = "contacts"

    override fun search(
        context: Context,
        query: String,
    ): Flow<List<SearchResult>> = flow {
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)

        val permissionsGranted = checkPermission(context)
        if (query.isBlank() || !prefs.searchResultPeople.get() || !permissionsGranted) {
            emit(emptyList())
            return@flow
        }

        val maxResults = prefs2.maxPeopleResultCount.firstCached()

        val contactInfoList = findContactsByName(context, query, maxResults)

        val searchResults = contactInfoList.map { contactInfo ->
            SearchResult.Contact(data = contactInfo)
        }
        emit(searchResults)
    }

    override fun checkPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    internal suspend fun findContactsByName(context: Context, query: String, max: Int): List<ContactInfo> {
        try {
            if (query.isEmpty() || query.isBlank() || max <= 0) return emptyList()
            val exceptionHandler = CoroutineExceptionHandler { _, e ->
                Log.e("ContactSearch", "Something went wrong ", e)
            }
            return withContext(Dispatchers.IO + exceptionHandler) {
                val contactMap = LinkedHashMap<String, ContactInfo>()
                val phonePreferences = HashMap<String, Pair<Int, Long>>()

                val projection = arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.DISPLAY_NAME,
                    Phone.NUMBER,
                    ContactsContract.Data.DATA5,
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.PHOTO_URI,
                    ContactsContract.Data.IS_PRIMARY,
                    ContactsContract.Data.IS_SUPER_PRIMARY,
                )

                val selection = ContactsContract.Data.DISPLAY_NAME + " LIKE ?"
                val selectionArgs = arrayOf("%$query%")

                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use {
                    val dataIdIndex = it.getColumnIndexOrThrow(ContactsContract.Data._ID)
                    val contactIdIndex = it.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                    val displayNameIndex = it.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME)
                    val numberIndex = it.getColumnIndexOrThrow(Phone.NUMBER)
                    val data5Index = it.getColumnIndexOrThrow(ContactsContract.Data.DATA5)
                    val mimeTypeIndex = it.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                    val photoUriIndex = it.getColumnIndexOrThrow(ContactsContract.Data.PHOTO_URI)
                    val primaryIndex = it.getColumnIndexOrThrow(ContactsContract.Data.IS_PRIMARY)
                    val superPrimaryIndex = it.getColumnIndexOrThrow(ContactsContract.Data.IS_SUPER_PRIMARY)
                    // Rows for one contact may be interleaved. Reaching the result limit must
                    // not prevent reading the selected contacts' later phone or default rows.
                    while (it.moveToNext()) {
                        val contactId = it.getString(contactIdIndex)?.takeIf { id -> id.isNotBlank() } ?: continue
                        if (contactId !in contactMap && contactMap.size >= max) continue
                        val displayName = it.getString(displayNameIndex).orEmpty()
                        val data5 = it.getString(data5Index)
                        val mimeType = it.getString(mimeTypeIndex) ?: continue
                        val photoUri = it.getString(photoUriIndex)
                        val imageUri = photoUri ?: ""
                        if (!EXCLUDED_MIME_TYPES.contains(mimeType)) {
                            // Non-phone data still makes a useful contact-detail result, but
                            // DATA1/DATA3/DATA5 have MIME-specific meanings, not phone fallbacks.
                            val contact = contactMap.getOrPut(contactId) {
                                ContactInfo(contactId, displayName, "", imageUri, contactId + displayName)
                            }
                            val phoneNumber = if (mimeType == Phone.CONTENT_ITEM_TYPE) {
                                it.getString(numberIndex)?.takeIf { number -> number.isNotBlank() }
                            } else {
                                null
                            }
                            if (phoneNumber != null) {
                                val priority = when {
                                    it.getInt(superPrimaryIndex) != 0 -> 2
                                    it.getInt(primaryIndex) != 0 -> 1
                                    else -> 0
                                }
                                val dataId = it.getLong(dataIdIndex)
                                val previous = phonePreferences[contactId]
                                if (previous == null || priority > previous.first ||
                                    (priority == previous.first && dataId < previous.second)
                                ) {
                                    contact.number = phoneNumber
                                    phonePreferences[contactId] = priority to dataId
                                }
                            }
                        } else {
                            if (contactMap.containsKey(contactId)) {
                                val existingContact = contactMap[contactId]
                                val jsonArray = buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(CONTACT_ACCOUNT_ID, contactId)
                                            put(CONTACT_ACCOUNT_TITLE, data5)
                                            put(CONTACT_ACCOUNT_MIME, mimeType)
                                        },
                                    )
                                }
                                existingContact?.packages = jsonArray.toString()
                            }
                        }
                    }
                }
                contactMap.values.toList()
            }
        } catch (e: Exception) {
            Log.e("ContactSearch", "Something went wrong ", e)
            return emptyList()
        }
    }

    private const val CONTACT_ACCOUNT_ID = "contact.id"
    private const val CONTACT_ACCOUNT_MIME = "contact.mime"
    private const val CONTACT_ACCOUNT_TITLE = "contact.title"

    private val EXCLUDED_MIME_TYPES = arrayOf(
        "vnd.android.cursor.item/name",
        "vnd.android.cursor.item/nickname",
        "vnd.android.cursor.item/note",
        "vnd.android.cursor.item/photo",
        "vnd.com.google.cursor.item/contact_misc",
        "vnd.android.cursor.item/identity",
        "vnd.android.cursor.item/website",
    )
}
