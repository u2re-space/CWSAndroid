package space.u2re.service.daemon

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionManager {
    fun ensurePermission(activity: Activity, permission: String): Boolean {
        val granted = PackageManager.PERMISSION_GRANTED
        if (ContextCompat.checkSelfPermission(activity, permission) == granted) {
            return true
        }
        val requestCode = (1000..9999).random()
        ActivityCompat.requestPermissions(activity, arrayOf(permission), requestCode)
        return false
    }

    fun hasPermission(activity: Activity, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }
}
