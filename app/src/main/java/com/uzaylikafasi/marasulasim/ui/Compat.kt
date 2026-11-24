package com.uzaylikafasi.marasulasim.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Uygulamanın bildirim ayarlarını açar. Android 8'den eskide ayrı bildirim ekranı yok; uygulama bilgisi ekranı açılır. */
internal fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= 26)
        Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
}
