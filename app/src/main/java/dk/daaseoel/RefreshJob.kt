package dk.daaseoel

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Henter nye tilbud i baggrunden: hver 3. time, og med det samme når man beder om det. */
class RefreshJob : JobService() {

    companion object {
        private const val PERIODIC_ID = 1
        private const val NOW_ID = 2
        private const val PERIOD_MS = 3 * 60 * 60 * 1000L

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(PERIODIC_ID) != null) return
            js.schedule(
                JobInfo.Builder(PERIODIC_ID, ComponentName(ctx, RefreshJob::class.java))
                    .setPeriodic(PERIOD_MS, 30 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun runNow(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(NOW_ID, ComponentName(ctx, RefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }

        /** Selve opdateringen. Blokerer – kald fra en baggrundstråd. */
        fun refresh(ctx: Context) {
            val p = Prefs(ctx)
            p.refreshing = true
            BeerWidget.updateAll(ctx)
            try {
                if (p.useGps) {
                    Locator.lastKnown(ctx)?.let { loc ->
                        if (loc.time > p.locationTime(true)) {
                            val label = Locator.label(ctx, loc.latitude, loc.longitude)
                            p.setLocation(true, loc.latitude, loc.longitude, label)
                        }
                    }
                }
                val (lat, lng) = p.location() ?: run {
                    p.error = if (p.useGps) "Åbn appen for at finde din position" else "Vælg en adresse i appen"
                    return
                }
                val brands = p.brands
                val now = System.currentTimeMillis()
                val soon = now + 36 * 60 * 60 * 1000L
                val deals = TjekApi.fetchBeerOffers(lat, lng, p.radiusKm * 1000, brands)
                    .flatMap { OfferParser.parse(it, brands) }
                    .filter { (it.runTill == 0L || it.runTill > now) && it.runFrom <= soon }
                p.deals = deals
                p.updatedAt = now
                p.error = null
            } catch (e: Exception) {
                p.error = "Ingen forbindelse – prøver igen senere"
            } finally {
                p.refreshing = false
                BeerWidget.updateAll(ctx)
                MainActivity.notifyChanged()
            }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            refresh(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
