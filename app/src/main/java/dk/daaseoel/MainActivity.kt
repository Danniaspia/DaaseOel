package dk.daaseoel

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    companion object {
        private const val REQ_LOCATION = 1
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // Grafit, kort, kobber og varm hvid – samme udtryk som AirTooth.
        private val CARD = 0xFF1C1C20.toInt()
        private val TILE = 0xFF2A2A2E.toInt()
        private val COPPER = 0xFFD08A4E.toInt()
        private val TEXT = 0xFFF2EFEA.toInt()
        private val MUTED = 0xFF8A8A90.toInt()
        private val PILL_ON = 0xFF2A2118.toInt()
        private val PILL_ON_TEXT = 0xFFE8B48A.toInt()

        private var current: WeakReference<MainActivity>? = null

        /** Kaldes fra baggrundsjobbet, når der er nye data. */
        fun notifyChanged() {
            current?.get()?.let { a -> a.runOnUiThread { a.render() } }
        }
    }

    private val prefs by lazy { Prefs(this) }
    private var askedPermission = false

    private lateinit var heroLabel: TextView
    private lateinit var heroPrice: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroFooter: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var radiusText: TextView
    private lateinit var locRow: LinearLayout
    private lateinit var locStatus: TextView
    private lateinit var addressRow: LinearLayout
    private lateinit var addressInput: EditText
    private lateinit var brandGrid: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var refreshButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        RefreshJob.schedule(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        render()
        if (prefs.useGps) updateGpsLocation(ask = !askedPermission)
        else if (stale()) refresh()
    }

    override fun onPause() {
        super.onPause()
        if (current?.get() === this) current = null
    }

    private fun stale() = System.currentTimeMillis() - prefs.updatedAt > 15 * 60 * 1000L

    // ---------- Data ----------

    private fun refresh() {
        if (prefs.refreshing) return
        prefs.refreshing = true
        render()
        Thread { RefreshJob.refresh(applicationContext) }.start()
    }

    private fun updateGpsLocation(ask: Boolean) {
        if (!Locator.hasPermission(this)) {
            if (ask) {
                askedPermission = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            }
            render()
            return
        }
        locStatus.text = "Finder din position…"
        Locator.current(this) { loc ->
            if (loc == null) {
                render()
                if (stale()) refresh()
                return@current
            }
            val moved = prefs.location(true)?.let { (lat, lng) ->
                val r = FloatArray(1)
                Location.distanceBetween(lat, lng, loc.latitude, loc.longitude, r)
                r[0] > 1000
            } ?: true
            Thread {
                val label = Locator.label(applicationContext, loc.latitude, loc.longitude)
                prefs.setLocation(true, loc.latitude, loc.longitude, label)
                runOnUiThread {
                    render()
                    if (moved || stale()) refresh()
                }
            }.start()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            updateGpsLocation(ask = false)
        } else {
            render()
        }
    }

    private fun searchAddress() {
        val text = addressInput.text.toString().trim()
        if (text.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(addressInput.windowToken, 0)
        locStatus.text = "Søger…"
        Thread {
            val hit = Locator.geocode(applicationContext, text)
            runOnUiThread {
                if (hit == null) {
                    locStatus.text = "Kunne ikke finde \"$text\""
                } else {
                    prefs.address = text
                    prefs.setLocation(false, hit.first, hit.second, hit.third)
                    render()
                    refresh()
                }
            }
        }.start()
    }

    // ---------- Visning ----------

    fun render() {
        if (!::heroPrice.isInitialized) return
        val mode = prefs.mode
        val ranked = prefs.ranked()
        val best = ranked.firstOrNull()

        heroLabel.text = "BILLIGSTE DÅSEØL · ${mode.label.uppercase()} · ${prefs.radiusKm} KM"
        if (best != null) {
            heroPrice.text = Format.value(best, mode)
            heroTitle.text = "${best.deal.dealer} · ${best.deal.brands.joinToString(" / ")}"
            heroSub.text = "${Format.note(best, mode)}\n${best.deal.heading}"
        } else {
            heroPrice.text = "–"
            heroTitle.text = if (prefs.updatedAt == 0L) "Ingen data endnu" else "Ingen tilbud fundet"
            heroSub.text = prefs.error ?: if (prefs.updatedAt == 0L) "Vælg position nedenfor" else "Prøv en større afstand eller flere mærker"
        }
        heroFooter.text = listOfNotNull(
            best?.let { Format.period(it.deal) }?.takeIf { it.isNotEmpty() },
            when {
                prefs.refreshing -> "Opdaterer…"
                prefs.error != null && prefs.updatedAt > 0 -> prefs.error
                prefs.updatedAt > 0 -> "opdateret ${Format.time(prefs.updatedAt)}"
                else -> null
            },
        ).joinToString(" · ")

        for (i in 0 until modeRow.childCount) styleChip(modeRow.getChildAt(i) as TextView, Mode.entries[i] == mode)
        radiusText.text = "${prefs.radiusKm} km"

        styleChip(locRow.getChildAt(0) as TextView, prefs.useGps)
        styleChip(locRow.getChildAt(1) as TextView, !prefs.useGps)
        addressRow.visibility = if (prefs.useGps) View.GONE else View.VISIBLE
        locStatus.text = when {
            prefs.useGps && !Locator.hasPermission(this) -> "Giv adgang til position – eller vælg en fast adresse"
            prefs.useGps -> prefs.locationLabel(true).ifEmpty { if (prefs.location(true) != null) "Din position" else "Finder din position…" }
                .let { if (prefs.location(true) != null) "Nær $it" else it }
            prefs.location(false) != null -> "Nær ${prefs.locationLabel(false)}"
            else -> "Skriv et postnummer eller en adresse"
        }

        val selected = prefs.brands
        var k = 0
        for (r in 0 until brandGrid.childCount) {
            val row = brandGrid.getChildAt(r) as LinearLayout
            for (c in 0 until row.childCount) {
                val chip = row.getChildAt(c) as TextView
                styleChip(chip, OfferParser.BRANDS[k++] in selected)
            }
        }

        list.removeAllViews()
        ranked.drop(1).take(9).forEachIndexed { i, r -> list.addView(listRow(i + 2, r, mode)) }
        if (ranked.size <= 1) list.addView(text(if (ranked.isEmpty()) "" else "Ingen andre tilbud.", 13f, MUTED))

        refreshButton.text = if (prefs.refreshing) "Opdaterer…" else "Opdater nu"
        BeerWidget.updateAll(this)
    }

    // ---------- Opbygning ----------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(32))
        }

        col.addView(text("DåseØl", 28f, TEXT, bold = true))
        col.addView(text("Det billigste tilbud på øl på dåse i nærheden", 14f, MUTED).apply { setPadding(0, dp(2), 0, dp(16)) })

        // Det bedste tilbud – samme indhold som widgetten.
        val hero = card()
        heroLabel = text("", 11f, MUTED).apply { letterSpacing = 0.08f }
        heroPrice = text("", 40f, COPPER, bold = true)
        heroTitle = text("", 17f, TEXT, bold = true)
        heroSub = text("", 13f, MUTED).apply { setPadding(0, dp(2), 0, 0) }
        heroFooter = text("", 12f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        listOf(heroLabel, heroPrice, heroTitle, heroSub, heroFooter).forEach(hero::addView)
        col.addView(hero)

        col.addView(section("Visning"))
        modeRow = row()
        Mode.entries.forEach { m ->
            modeRow.addView(chip(m.label) { prefs.mode = m; render() }, weighted())
        }
        col.addView(modeRow)

        col.addView(section("Afstand"))
        val radiusCard = card()
        radiusText = text("", 16f, TEXT, bold = true)
        radiusCard.addView(radiusText)
        radiusCard.addView(SeekBar(this).apply {
            max = 49
            progress = prefs.radiusKm - 1
            setPadding(dp(4), dp(10), dp(4), dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (user) radiusText.text = "${p + 1} km"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    prefs.radiusKm = s.progress + 1
                    render()
                    refresh()
                }
            })
        })
        col.addView(radiusCard)

        col.addView(section("Position"))
        locRow = row()
        locRow.addView(chip("Min position") {
            prefs.useGps = true
            render()
            updateGpsLocation(ask = true)
        }, weighted())
        locRow.addView(chip("Fast adresse") {
            prefs.useGps = false
            render()
            if (prefs.location(false) != null) refresh() else addressInput.requestFocus()
        }, weighted())
        col.addView(locRow)
        locStatus = text("", 13f, MUTED).apply { setPadding(dp(4), dp(8), 0, 0) }
        col.addView(locStatus)
        addressRow = row().apply { setPadding(0, dp(8), 0, 0) }
        addressInput = EditText(this).apply {
            hint = "Postnummer eller adresse"
            setText(prefs.address)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(TILE, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { _, _, _ -> searchAddress(); true }
        }
        addressRow.addView(addressInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        addressRow.addView(button("Søg") { searchAddress() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        col.addView(addressRow)

        col.addView(section("Mærker"))
        brandGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        OfferParser.BRANDS.chunked(3).forEach { names ->
            val r = row().apply { setPadding(0, 0, 0, dp(8)) }
            names.forEach { name ->
                r.addView(chip(name) {
                    val s = prefs.brands.toMutableSet()
                    if (!s.remove(name)) s.add(name)
                    if (s.isNotEmpty()) {
                        prefs.brands = s
                        render()
                        refresh()
                    }
                }, weighted())
            }
            repeat(3 - names.size) { r.addView(View(this), weighted()) }
            brandGrid.addView(r)
        }
        col.addView(brandGrid)

        col.addView(section("Flere tilbud"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        refreshButton = button("Opdater nu") { refresh() }
        col.addView(refreshButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        col.addView(text(
            "Priser er uden pant. \"Omregnet\" betyder at prisen er regnet om til 18 eller 24 dåser ud fra tilbuddets egen pakning. " +
                "Rammevisning tæller kun 25–33 cl dåser; literpris tæller op til 50 cl. Data: eTilbudsavis.",
            12f, MUTED,
        ).apply { setPadding(dp(4), dp(14), dp(4), 0) })

        return ScrollView(this).apply { addView(col) }
    }

    private fun listRow(rank: Int, r: Ranked, mode: Mode): View {
        val c = card(topMargin = 8)
        val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("$rank. ${r.deal.dealer}", 15f, TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(text(Format.value(r, mode), 16f, COPPER, bold = true))
        c.addView(top)
        c.addView(text("${r.deal.brands.joinToString(" / ")} · ${Format.note(r, mode)}", 13f, MUTED))
        c.addView(text(Format.period(r.deal), 12f, MUTED))
        return c
    }

    private fun section(title: String) = text(title.uppercase(), 12f, MUTED).apply {
        letterSpacing = 0.1f
        setPadding(dp(4), dp(22), 0, dp(8))
    }

    private fun card(topMargin: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 20)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { this.topMargin = dp(topMargin) }
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun weighted() = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) }

    private fun chip(label: String, onClick: () -> Unit) = text(label, 14f, TEXT).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(8), dp(11), dp(8), dp(11))
        setOnClickListener { onClick() }
    }

    private fun styleChip(v: TextView, on: Boolean) {
        v.background = rounded(if (on) PILL_ON else TILE, 14).apply { if (on) setStroke(dp(1), COPPER) }
        v.setTextColor(if (on) PILL_ON_TEXT else TEXT)
        v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun button(label: String, onClick: () -> Unit) = text(label, 15f, 0xFF111113.toInt(), bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(COPPER, 14)
        setPadding(dp(18), dp(13), dp(18), dp(13))
        setOnClickListener { onClick() }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
