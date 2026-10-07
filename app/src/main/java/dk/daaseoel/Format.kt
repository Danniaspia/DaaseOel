package dk.daaseoel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Danske tekster til priser, pakninger og datoer. */
object Format {
    private val DK = Locale("da", "DK")

    fun perLiter(d: Deal): String = String.format(DK, "%.2f kr/l", d.perLiter)

    fun cl(v: Double) = if (v == Math.floor(v)) "${v.toInt()} cl" else String.format(DK, "%.1f cl", v)

    /** "18 × 33 cl for 69,95 kr". */
    fun pack(d: Deal): String = "${d.pieces} × ${cl(d.cl)} for ${String.format(DK, "%.2f kr", d.price)}"

    fun period(d: Deal, now: Long = System.currentTimeMillis()): String = when {
        d.runFrom > now -> "fra " + SimpleDateFormat("EEE d/M", DK).format(Date(d.runFrom))
        d.runTill > 0 -> "t.o.m. " + SimpleDateFormat("EEE d/M", DK).format(Date(d.runTill - 1000))
        else -> ""
    }

    fun time(t: Long): String = SimpleDateFormat("HH:mm", DK).format(Date(t))
}
