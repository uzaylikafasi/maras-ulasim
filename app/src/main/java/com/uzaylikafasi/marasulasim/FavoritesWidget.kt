package com.uzaylikafasi.marasulasim

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class FavoritesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(6_000) { render(context, manager, ids) } }
            finally { pending.finish() }
        }
    }
    companion object {
        @Volatile private var lastText: String? = null
        /** Harita her yenilendiğinde çağrılır; içerik değişmediyse widget'a dokunulmaz. */
        suspend fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FavoritesWidget::class.java))
            if (ids.isNotEmpty()) withContext(Dispatchers.IO) { render(context, manager, ids, onlyIfChanged = true) }
        }
        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray, onlyIfChanged: Boolean = false) {
            val settings = (context.applicationContext as TransportApplication).store.settings.first()
            val text = settings.lines.take(3).joinToString("\n") { "${it.code} · ${it.directionName}" }
                .ifBlank { "Favori hatlarını uygulamadan ekle." }
            if (onlyIfChanged && text == lastText) return
            lastText = text
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.favorites_widget)
                views.setTextViewText(R.id.widget_lines, text)
                views.setTextViewText(R.id.widget_info, "Canlı konumlar için haritayı aç.")
                views.setOnClickPendingIntent(R.id.widget_open, open)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
