package dk.daaseoel

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Hjemmeskærms-widgetten: det ene billigste tilbud for den valgte visning. */
class BeerWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "dk.daaseoel.REFRESH"
        private const val STALE_MS = 3 * 60 * 60 * 1000L

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BeerWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx))
        }

        private fun render(ctx: Context): RemoteViews {
            val p = Prefs(ctx)
            val mode = p.mode
            val v = RemoteViews(ctx.packageName, R.layout.widget_beer)
            val best = p.ranked().firstOrNull()

            v.setTextViewText(R.id.label, "BILLIGSTE DÅSEØL · ${mode.label.uppercase()} · ${p.radiusKm} KM")
            if (best != null) {
                val d = best.deal
                v.setTextViewText(R.id.price, Format.value(best, mode))
                v.setTextViewText(R.id.title, "${d.dealer} · ${d.brands.joinToString(" / ")}")
                v.setTextViewText(R.id.sub, Format.note(best, mode))
                v.setTextViewText(R.id.footer, listOf(Format.period(d), footerStatus(p)).filter { it.isNotEmpty() }.joinToString(" · "))
            } else {
                v.setTextViewText(R.id.price, "–")
                v.setTextViewText(R.id.title, if (p.updatedAt == 0L) "Ingen data endnu" else "Ingen tilbud lige nu")
                v.setTextViewText(R.id.sub, p.error ?: if (p.updatedAt == 0L) "Tryk for at åbne appen" else "Prøv større afstand eller flere mærker")
                v.setTextViewText(R.id.footer, footerStatus(p))
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val refresh = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, BeerWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.root, open)
            v.setOnClickPendingIntent(R.id.refresh, refresh)
            return v
        }

        private fun footerStatus(p: Prefs): String = when {
            p.refreshing -> "Opdaterer…"
            p.error != null && p.updatedAt > 0 -> p.error!!
            p.updatedAt > 0 -> "opdateret ${Format.time(p.updatedAt)}"
            else -> ""
        }
    }

    override fun onEnabled(context: Context) {
        RefreshJob.schedule(context)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        RefreshJob.schedule(context)
        mgr.updateAppWidget(ids, render(context))
        if (System.currentTimeMillis() - Prefs(context).updatedAt > STALE_MS) RefreshJob.runNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) RefreshJob.runNow(context)
    }
}
