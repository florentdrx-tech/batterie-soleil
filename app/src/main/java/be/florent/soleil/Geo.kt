package be.florent.soleil

import android.content.Context
import android.location.Geocoder
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Place(val name: String, val lat: Double, val lon: Double)

/** Transforme une adresse en coordonnées. À appeler hors du thread principal. */
object Geo {

    fun find(ctx: Context, query: String): Place? =
        runCatching { android(ctx, query) }.getOrNull()
            ?: runCatching { osm(query) }.getOrNull()

    /** Géocodeur intégré au téléphone (Google), sans clé. */
    @Suppress("DEPRECATION")
    private fun android(ctx: Context, q: String): Place? {
        if (!Geocoder.isPresent()) return null
        val list = Geocoder(ctx, Locale.FRENCH).getFromLocationName(q, 1)
        val a = list?.firstOrNull() ?: return null
        val parts = listOfNotNull(
            listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null },
            a.locality ?: a.subAdminArea
        )
        val name = if (parts.isEmpty()) q else parts.joinToString(", ")
        return Place(name, a.latitude, a.longitude)
    }

    /** Secours : OpenStreetMap (Nominatim). */
    private fun osm(q: String): Place? {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=fr&q=" +
                URLEncoder.encode(q, "UTF-8")
        )
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "SoleilBatterie/1.0 (Android)")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) return null
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            val name = o.getString("display_name").split(",").take(2).joinToString(",").trim()
            return Place(name, o.getString("lat").toDouble(), o.getString("lon").toDouble())
        } finally {
            c.disconnect()
        }
    }
}
