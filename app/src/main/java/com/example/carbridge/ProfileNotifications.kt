package com.example.carbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Explicit Android notification access, independent of HeadSwap. No disk history or network. */
class ProfileNotifications : NotificationListenerService() {
    companion object {
        @Volatile internal var current: ProfileNotifications? = null
        internal fun snapshot(): List<StatusBarNotification> = runCatching {
            current?.activeNotifications.orEmpty().filter {
                it.packageName != "com.example.carbridge.headswap" &&
                    it.notification.category != Notification.CATEGORY_TRANSPORT &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.isNotBlank() == true
            }.sortedByDescending { it.postTime }.take(30)
        }.getOrDefault(emptyList())
    }
    override fun onListenerConnected() { super.onListenerConnected(); current = this }
    override fun onListenerDisconnected() { current = null; super.onListenerDisconnected() }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
}
