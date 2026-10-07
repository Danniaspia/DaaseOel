package dk.daaseoel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Telefonens position (uden Google Play Services) og opslag af en fast adresse. */
object Locator {
    private val DK = Locale("da", "DK")

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Seneste position, som systemet allerede kender. Virker også fra baggrunden, hvis den er ny nok. */
    @Suppress("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        return lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Henter en frisk position (appen skal være i forgrunden). Kalder [done] på hovedtråden, evt. med null. */
    @Suppress("MissingPermission")
    fun current(ctx: Context, done: (Location?) -> Unit) {
        if (!hasPermission(ctx)) return done(null)
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in lm.allProviders && runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return done(lastKnown(ctx))
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val finish = { loc: Location? -> if (!finished) { finished = true; done(loc ?: lastKnown(ctx)) } }
        main.postDelayed({ finish(null) }, 15_000)
        lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc -> finish(loc) }
    }

    /** Postnummer/by for en position, f.eks. "2300 København S". Blokerer – kald fra en baggrundstråd. */
    @Suppress("DEPRECATION")
    fun label(ctx: Context, lat: Double, lng: Double): String? = runCatching {
        val a = Geocoder(ctx, DK).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return null
        listOfNotNull(a.postalCode, a.locality ?: a.subAdminArea).joinToString(" ").ifBlank { null }
    }.getOrNull()

    /** Slår en adresse eller et postnummer op. Blokerer – kald fra en baggrundstråd. */
    @Suppress("DEPRECATION")
    fun geocode(ctx: Context, text: String): Triple<Double, Double, String>? = runCatching {
        val a = Geocoder(ctx, DK).getFromLocationName("$text, Danmark", 1)?.firstOrNull() ?: return null
        val label = listOfNotNull(a.thoroughfare?.let { t -> listOfNotNull(t, a.subThoroughfare).joinToString(" ") },
            listOfNotNull(a.postalCode, a.locality).joinToString(" ").ifBlank { null }).joinToString(", ")
        Triple(a.latitude, a.longitude, label.ifBlank { text })
    }.getOrNull()
}
