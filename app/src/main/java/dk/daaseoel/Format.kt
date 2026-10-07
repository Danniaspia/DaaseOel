package dk.daaseoel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Danske tekster til priser, pakninger og datoer. */
object Format {
    private val DK = Locale("da", "DK")

    fun kr(v: Double): String =
        if (v >= 100 || v == Math.floor(v)) String.format(DK, "%.0f kr", v) else String.format(DK, "%.2f kr", v)

    fun value(r: Ranked, mode: Mode): String =
        if (mode == Mode.LITER) String.format(DK, "%.2f kr/l", r.value) else String.format(DK, "%.0f kr", r.value)

    fun cl(v: Double) = if (v == Math.floor(v)) "${v.toInt()} cl" else String.format(DK, "%.1f cl", v)

    /** "18 × 33 cl til 69,95 kr" eller "1 × 33 cl á 4,50 kr". */
    fun pack(d: Deal): String =
        if (d.pieces == 1) "1 × ${cl(d.cl)} á ${String.format(DK, "%.2f kr", d.price)}"
        else "${d.pieces} × ${cl(d.cl)} til ${String.format(DK, "%.2f kr", d.price)}"

    fun note(r: Ranked, mode: Mode): String = when {
        mode == Mode.LITER -> pack(r.deal)
        r.exactFrame -> "Ægte ramme · ${pack(r.deal)}"
        else -> "Omregnet fra ${pack(r.deal)}"
    }

    fun period(d: Deal, now: Long = System.currentTimeMillis()): String = when {
        d.runFrom > now -> "fra " + SimpleDateFormat("EEE d/M", DK).format(Date(d.runFrom))
        d.runTill > 0 -> "t.o.m. " + SimpleDateFormat("EEE d/M", DK).format(Date(d.runTill - 1000))
        else -> ""
    }

    fun time(t: Long): String = SimpleDateFormat("HH:mm", DK).format(Date(t))
}
