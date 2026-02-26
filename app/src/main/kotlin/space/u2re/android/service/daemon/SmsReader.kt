package space.u2re.service.daemon

import android.app.Activity
import android.net.Uri

data class SmsItem(val id: String, val address: String, val body: String, val date: Long, val type: Int)

suspend fun readSmsInbox(activity: Activity, limit: Int = 50): List<SmsItem> {
    if (!PermissionManager.hasPermission(activity, android.Manifest.permission.READ_SMS)) {
        return emptyList()
    }

    val resolver = activity.contentResolver
    val uri = Uri.parse("content://sms")
    val cursor = resolver.query(uri, null, null, null, "date DESC") ?: return emptyList()

    return cursor.use {
        val idIdx = it.getColumnIndex("_id")
        val addrIdx = it.getColumnIndex("address")
        val bodyIdx = it.getColumnIndex("body")
        val dateIdx = it.getColumnIndex("date")
        val typeIdx = it.getColumnIndex("type")
        val out = mutableListOf<SmsItem>()

        while (it.moveToNext() && out.size < limit) {
            out.add(
                SmsItem(
                    id = it.getString(idIdx) ?: "",
                    address = it.getString(addrIdx) ?: "",
                    body = it.getString(bodyIdx) ?: "",
                    date = it.getLong(dateIdx),
                    type = it.getInt(typeIdx),
                )
            )
        }
        out
    }
}
