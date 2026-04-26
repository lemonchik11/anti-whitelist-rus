package dev.yakovsava.antiwhitelist
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
class AntiWhitelistApp : Application() {
    companion object { const val CHANNEL_VPN = "antiwhitelist_vpn" }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_VPN, "VPN Туннель", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
    }
}
