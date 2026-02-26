package space.u2re.service.daemon

import android.app.Activity
import android.provider.ContactsContract

data class ContactItem(val id: String, val name: String, val phones: List<String>, val emails: List<String>)

suspend fun readContacts(activity: Activity): List<ContactItem> {
    if (!PermissionManager.hasPermission(activity, android.Manifest.permission.READ_CONTACTS)) {
        return emptyList()
    }

    val resolver = activity.contentResolver
    val results = mutableListOf<ContactItem>()
    var cursor = resolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        null,
        null,
        null,
        null
    ) ?: return emptyList()

    cursor.use {
        val idIdx = it.getColumnIndex(ContactsContract.Contacts._ID)
        val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
        val hasPhoneIdx = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
        while (it.moveToNext()) {
            try {
                val id = it.getString(idIdx) ?: ""
                val name = it.getString(nameIdx) ?: ""
                val hasPhone = it.getInt(hasPhoneIdx) > 0

                val phones = if (hasPhone) {
                    val pCursor = resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
                        arrayOf(id),
                        null
                    )
                    pCursor.use {
                        val numIdx = it?.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER) ?: -1
                        buildList {
                            while (it?.moveToNext() == true && numIdx >= 0) {
                                val raw = it.getString(numIdx) ?: ""
                                raw.trim().takeIf { it.isNotEmpty() }?.let(::add)
                            }
                        }
                    } ?: emptyList()
                } else {
                    emptyList()
                }

                val emailCursor = resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    null,
                    "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?",
                    arrayOf(id),
                    null
                )
                val emails = emailCursor.use {
                    val emailIdx = it?.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA) ?: -1
                    buildList {
                        while (it?.moveToNext() == true && emailIdx >= 0) {
                            val raw = it.getString(emailIdx) ?: ""
                            raw.trim().takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }

                results.add(ContactItem(id = id, name = name, phones = phones, emails = emails))
            } catch (e: Exception) {
                DaemonLog.warn("ContactsReader", "read contact failed", e)
            }
        }
    }

    return results
}
