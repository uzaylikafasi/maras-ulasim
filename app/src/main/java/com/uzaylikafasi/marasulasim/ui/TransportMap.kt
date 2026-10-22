package com.uzaylikafasi.marasulasim.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.uzaylikafasi.marasulasim.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.expressions.Expression

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val ARROW = "yon-oku"
private const val FOCUS_SOURCE = "focus-source"
private const val FOCUS_LAYER = "focus"
private const val ALL_STOPS = "all-stops"
private const val ALL_STOPS_ICON = "all-stops-simge"
private const val STOP_COLOR = "#F59E0B"
private const val PLACES = "yerler"
private const val CHEVRON = "yol-oku"

/** Önemli yer türleri: renk ve vektör simge (emoji yerine; her cihazda aynı görünür). */
private val placeKinds = mapOf(
    "cami" to ("#0F766E" to androidx.compose.material.icons.Icons.Filled.Mosque),
    "tarih" to ("#92400E" to androidx.compose.material.icons.Icons.Filled.Castle),
    "carsi" to ("#DB2777" to androidx.compose.material.icons.Icons.Filled.ShoppingBag),
    "park" to ("#16A34A" to androidx.compose.material.icons.Icons.Filled.Park),
    "hastane" to ("#DC2626" to androidx.compose.material.icons.Icons.Filled.LocalHospital),
    "okul" to ("#4F46E5" to androidx.compose.material.icons.Icons.Filled.School),
    "ulasim" to ("#0891B2" to androidx.compose.material.icons.Icons.Filled.DirectionsBus),
    "ucak" to ("#475569" to androidx.compose.material.icons.Icons.Filled.Flight),
    "resmi" to ("#7C2D12" to androidx.compose.material.icons.Icons.Filled.AccountBalance),
    "kultur" to ("#9333EA" to androidx.compose.material.icons.Icons.Filled.Museum))

/** Renkli daire, beyaz kenar ve ortada beyaz simge. */
private fun placeBitmap(fill: String, icon: androidx.compose.ui.graphics.vector.ImageVector): android.graphics.Bitmap {
    val size = 72
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.argb(60, 0, 0, 0); c.drawCircle(size / 2f, size / 2f + 2f, size / 2f - 4f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(size / 2f, size / 2f, size / 2f - 4f, p)
    p.color = android.graphics.Color.parseColor(fill); c.drawCircle(size / 2f, size / 2f, size / 2f - 9f, p)
    val glyph = 34f
    c.save(); c.translate((size - glyph) / 2f, (size - glyph) / 2f); c.scale(glyph / icon.viewportWidth, glyph / icon.viewportHeight)
    p.color = android.graphics.Color.WHITE
    fun draw(group: androidx.compose.ui.graphics.vector.VectorGroup) {
        group.forEach { node ->
            when (node) {
                is androidx.compose.ui.graphics.vector.VectorPath -> c.drawPath(
                    androidx.compose.ui.graphics.vector.PathParser().addPathNodes(node.pathData).toPath().let { (it as androidx.compose.ui.graphics.AndroidPath).internalPath }, p)
                is androidx.compose.ui.graphics.vector.VectorGroup -> draw(node)
            }
        }
    }
    draw(icon.root); c.restore()
    return bmp
}

/** Sağı gösteren ok başı; güzergâh boyunca gidiş yönünü gösterir (SDF). */
private fun chevronBitmap(): android.graphics.Bitmap {
    val size = 32
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE; strokeWidth = 6f
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    android.graphics.Canvas(bmp).drawPath(android.graphics.Path().apply { moveTo(10f, 6f); lineTo(22f, 16f); lineTo(10f, 26f) }, paint)
    return bmp
}

private const val BUS_HEADING = "otobus-yon"
private const val BUS_DOT = "otobus-nokta"

/** Otobüs işareti: daire ve üstünde yöne bakan sivri uç (damla); SDF, rengi katmanda verilir ve açıyla döner. */
private fun busBitmap(withTip: Boolean): android.graphics.Bitmap {
    val size = 96; val cx = size / 2f; val cy = size / 2f; val r = 26f
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    val c = android.graphics.Canvas(bmp)
    c.drawCircle(cx, cy, r, paint)
    if (withTip) c.drawPath(android.graphics.Path().apply {
        moveTo(cx, cy - r - 16f); lineTo(cx + r * 0.62f, cy - r * 0.62f); lineTo(cx - r * 0.62f, cy - r * 0.62f); close() }, paint)
    return bmp
}

/** Hat adından gidilen yer: "K. SANAYİ - BİNEVLER (Gidiş)" → "BİNEVLER". */
fun destinationOf(line: Line): String =
    line.name.replace(Regex("\\s*\\(.*?\\)\\s*"), " ").substringAfterLast(" - ").trim().ifBlank { line.directionName }

/** Yukarıyı gösteren dolu üçgen; SDF olarak eklenir, rengi katmanda verilir. */
private fun arrowBitmap(): android.graphics.Bitmap {
    val size = 72
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    val path = android.graphics.Path().apply { moveTo(size / 2f, 6f); lineTo(size - 10f, size - 14f); lineTo(size / 2f, size - 26f); lineTo(10f, size - 14f); close() }
    android.graphics.Canvas(bmp).drawPath(path, paint)
    return bmp
}

@Composable
fun TransportMap(snapshots: List<LineSnapshot>, focus: Stop?, favoritePlates: Set<String>, showVehicles: Boolean, locate: Pair<Long, Coordinate>?, modifier: Modifier = Modifier,
                 onStopClick: (Stop) -> Unit, onVehicleClick: (Line, Vehicle) -> Unit, onPlaceClick: (String, Coordinate) -> Unit, onError: (String?) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(snapshots)
    val plates by rememberUpdatedState(favoritePlates)
    val vehiclesOn by rememberUpdatedState(showVehicles)
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { kotlinx.coroutines.delay(5_000); tick = System.currentTimeMillis() }
    } }
    val stopClick by rememberUpdatedState(onStopClick)
    val vehicleClick by rememberUpdatedState(onVehicleClick)
    val placeClick by rememberUpdatedState(onPlaceClick)
    val reportError by rememberUpdatedState(onError)
    val holder = remember {
        MapHolder(context) { reportError(it) }.apply {
            ready = { render(latest, plates, showVehicles = vehiclesOn) }
            clicked = { stopClick(it) }
            vehicleClicked = { line, vehicle -> vehicleClick(line, vehicle) }
            placeClicked = { name, at -> placeClick(name, at) }
        }
    }
    DisposableEffect(owner, holder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) holder.view.onPause()
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) holder.view.onStop()
            holder.view.onDestroy()
        }
    }
    LaunchedEffect(locate) { locate?.let { holder.locate(it.second) } }
    LaunchedEffect(focus) {
        if (focus?.latitude != null && focus.longitude != null) holder.focus(focus)
    }
    val now = tick
    AndroidView(factory = { holder.view }, modifier = modifier, update = { holder.render(snapshots, favoritePlates, now, showVehicles) })
}

private class MapHolder(private val context: Context, private val error: (String?) -> Unit) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var pendingFocus: Stop? = null
    var ready: (() -> Unit)? = null
    var clicked: ((Stop) -> Unit)? = null
    var vehicleClicked: ((Line, Vehicle) -> Unit)? = null
    var placeClicked: ((String, Coordinate) -> Unit)? = null
    private var knownStops = emptyMap<Long, Stop>()
    private var locationOn = false
    private var rendered = emptySet<String>()
    private var knownVehicles = emptyMap<String, Pair<Line, Vehicle>>()
    private val sourceContents = mutableMapOf<String, String>()
    private fun updateSource(source: GeoJsonSource, id: String, contents: String) {
        if (sourceContents[id] != contents) { source.setGeoJson(contents); sourceContents[id] = contents }
    }
    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener { error("Harita yüklenemedi. Bağlantıyı kontrol et; durak listesi kullanılabilir.") }
        view.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(LatLng(37.577, 36.877)).zoom(11.5).build()
            m.uiSettings.isCompassEnabled = true
            // MapLibre logosu zorunlu değil, kaldırıldı. OpenStreetMap/OpenMapTiles künyesi (i) düğmesinde kalır.
            m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                // Yön oku tek renkli (SDF) çizilir; her hat kendi rengiyle boyar
                style.addImage(ARROW, arrowBitmap(), true)
                style.addImage(BUS_HEADING, busBitmap(true), true)
                style.addImage(BUS_DOT, busBitmap(false), true)
                style.addImage(CHEVRON, chevronBitmap(), true)
                greenAreas(style)
                addAllStops(style)
                addPlaces(style)
                showLocation(style)
                error(null); ready?.invoke(); pendingFocus?.let(::focus)
            }
            m.addOnMapClickListener { point ->
                val pixel = m.projection.toScreenLocation(point)
                val vehicle = nearest(m, pixel, rendered.map { "vehicles-$it" })?.getStringProperty("vehicle")?.let { knownVehicles[it] }
                if (vehicle != null) { vehicleClicked?.invoke(vehicle.first, vehicle.second); return@addOnMapClickListener true }
                val id = nearest(m, pixel, rendered.map { "stops-$it" } + FOCUS_LAYER)?.getNumberProperty("id")?.toLong()
                id?.let { knownStops[it] }?.let { clicked?.invoke(it); return@addOnMapClickListener true }
                // Seçili hatlarda olmayan durak: şehirdeki bütün duraklar katmanından
                val stop = nearest(m, pixel, listOf(ALL_STOPS, ALL_STOPS_ICON))?.let { f -> runCatching { Stop(f.getNumberProperty("id").toLong(), f.getStringProperty("code"),
                    f.getStringProperty("name"), f.getNumberProperty("lat").toDouble(), f.getNumberProperty("lng").toDouble()) }.getOrNull() }
                if (stop != null) { clicked?.invoke(stop); return@addOnMapClickListener true }
                val place = nearest(m, pixel, listOf(PLACES))
                place?.let { f -> runCatching { placeClicked?.invoke(f.getStringProperty("ad"),
                    Coordinate(f.getNumberProperty("lat").toDouble(), f.getNumberProperty("lng").toDouble())) } }
                place != null
            }
        }
    }
    /** Parmak ucu büyüklüğünde alan içindeki en yakın nesne: küçük durak noktalarına tek dokunuşta isabet edilir. */
    private fun nearest(m: MapLibreMap, pixel: android.graphics.PointF, layers: List<String>): org.maplibre.geojson.Feature? {
        val present = layers.filter { m.style?.getLayer(it) != null }.toTypedArray()
        if (present.isEmpty()) return null
        val r = 22f * context.resources.displayMetrics.density
        val box = android.graphics.RectF(pixel.x - r, pixel.y - r, pixel.x + r, pixel.y + r)
        return m.queryRenderedFeatures(box, *present).minByOrNull { f ->
            val p = f.geometry() as? org.maplibre.geojson.Point ?: return@minByOrNull Float.MAX_VALUE
            val at = m.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
            (at.x - pixel.x) * (at.x - pixel.x) + (at.y - pixel.y) * (at.y - pixel.y)
        }
    }
    private fun permitted() = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    /** Kullanıcının konumu mavi nokta olarak; yalnız izin verilmişse. Kamera kendiliğinden kaymaz. */
    @Suppress("MissingPermission")
    private fun showLocation(style: Style): Boolean {
        val m = map ?: return false
        if (!permitted()) return false
        return runCatching {
            val lc = m.locationComponent
            if (!lc.isLocationComponentActivated) lc.activateLocationComponent(
                org.maplibre.android.location.LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(true).build())
            lc.isLocationComponentEnabled = true
            lc.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
            lc.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
            true
        }.getOrDefault(false)
    }
    /** "Konumum": noktayı açar ve kamerayı konuma getirir. */
    fun locate(at: Coordinate) {
        val m = map ?: return
        val style = m.style ?: return
        if (showLocation(style) && runCatching { m.locationComponent.lastKnownLocation }.getOrNull() == null)
            runCatching { m.locationComponent.forceLocationUpdate(android.location.Location("uygulama").apply { latitude = at.latitude; longitude = at.longitude }) }
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(at.latitude, at.longitude), 15.5))
    }
    fun focus(stop: Stop) {
        pendingFocus = stop
        if (stop.latitude == null || stop.longitude == null) return
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(stop.latitude, stop.longitude), 16.0))
        // "Haritada göster" ile açılan durak, seçili hatlarda olmasa da işaretli görünür
        val style = map?.style ?: return
        val json = JSONObject().put("type", "Feature").put("properties", JSONObject().put("id", stop.id))
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(stop.longitude, stop.latitude)))).toString()
        val source = style.getSourceAs<GeoJsonSource>(FOCUS_SOURCE)
        if (source == null) {
            style.addSource(GeoJsonSource(FOCUS_SOURCE, json))
            style.addLayer(CircleLayer(FOCUS_LAYER, FOCUS_SOURCE).withProperties(circleRadius(9f), circleColor("#FFB020"),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
        } else source.setGeoJson(json)
        knownStops = knownStops + (stop.id to stop)
    }

    /** Yeni eklenen hattın güzergâhı ilk geldiğinde kamera o hatta gider. */
    private var fitted = emptySet<String>()
    private fun fitNewLines(snapshots: List<LineSnapshot>, keys: Set<String>) {
        val m = map ?: return
        val yeni = snapshots.filter { it.line.key.replace(':', '-') !in fitted && it.shape?.data is JSONObject }
        if (yeni.isEmpty()) { fitted = fitted intersect keys; return }
        val bounds = yeni.mapNotNull { shapeBounds(it.shape!!.data as JSONObject) }
        fitted = (fitted intersect keys) + yeni.map { it.line.key.replace(':', '-') }
        if (bounds.isEmpty() || pendingFocus != null) return
        val b = LatLngBounds.Builder()
        bounds.forEach { (sw, ne) -> b.include(LatLng(sw.latitude, sw.longitude)); b.include(LatLng(ne.latitude, ne.longitude)) }
        runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120)) }
    }
    /** Şehirdeki bütün duraklar (uygulamaya gömülü liste): uzaktan küçük nokta, yakınlaşınca adlar. */
    private fun addAllStops(style: Style) {
        if (style.getSource(ALL_STOPS) != null) return
        val features = JSONArray()
        runCatching {
            val stops = JSONObject(context.assets.open("all-stops.json").bufferedReader().use { it.readText() }).getJSONArray("stops")
            for (i in 0 until stops.length()) {
                val o = stops.getJSONObject(i)
                features.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("id", o.getLong("id"))
                    .put("code", o.optString("code")).put("name", o.optString("name")).put("lat", o.getDouble("lat")).put("lng", o.getDouble("lng")))
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(o.getDouble("lng"), o.getDouble("lat"))))))
            }
        }
        style.addSource(GeoJsonSource(ALL_STOPS, JSONObject().put("type", "FeatureCollection").put("features", features).toString()))
        // Duraklar turuncu ve iri (Samet/Ömer: beyaz-gri noktalar görünmüyordu); yakında içinde otobüs simgesi
        style.addLayer(CircleLayer(ALL_STOPS, ALL_STOPS).withProperties(
            circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 4.5f), Expression.stop(16, 8f))),
            circleColor(STOP_COLOR), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
            circleOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0f), Expression.stop(14.6, 1f))),
            circleStrokeOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0f), Expression.stop(14.6, 1f)))).apply { minZoom = 14f; maxZoom = 16f })
        runCatching { style.addImage(ALL_STOPS_ICON, placeBitmap(STOP_COLOR, androidx.compose.material.icons.Icons.Filled.DirectionsBus)) }
        style.addLayer(SymbolLayer(ALL_STOPS_ICON, ALL_STOPS).withProperties(iconImage(ALL_STOPS_ICON),
            iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(16, 0.9f), Expression.stop(18, 1.3f))),
            iconAllowOverlap(true), iconIgnorePlacement(true)).apply { minZoom = 16f })
        style.addLayer(SymbolLayer("$ALL_STOPS-ad", ALL_STOPS).withProperties(textField(Expression.get("name")), textSize(10f),
            textColor("#3B4C50"), textHaloColor("#FFFFFF"), textHaloWidth(1.2f), textOffset(arrayOf(0f, 1.5f)), textAnchor(Property.TEXT_ANCHOR_TOP),
            textFont(arrayOf("Noto Sans Regular")), textOptional(true)).apply { minZoom = 16f })
    }

    /** Parklar, bahçeler ve ormanlık alanlar yeşil görünür (Positron bunları neredeyse gri çiziyor). */
    private fun greenAreas(style: Style) {
        style.getLayerAs<FillLayer>("park")?.setProperties(fillColor("#D4E9C8"))
        style.getLayerAs<FillLayer>("landcover_wood")?.setProperties(fillColor("#C9E2BA"))
        if (style.getSource("openmaptiles") == null || style.getLayer("yesil-alan") != null) return
        val green = FillLayer("yesil-alan", "openmaptiles").withProperties(fillColor("#CDE7BF"), fillOpacity(0.9f)).apply {
            setSourceLayer("landcover"); setFilter(Expression.match(Expression.get("class"), Expression.literal(false),
                Expression.stop("grass", true), Expression.stop("wood", true)))
        }
        if (style.getLayer("building") != null) style.addLayerBelow(green, "building") else style.addLayer(green)
        if (style.getLayer("park-alani") == null) style.addLayerBelow(FillLayer("park-alani", "openmaptiles").withProperties(fillColor("#C3E3B0")).apply {
            setSourceLayer("landuse"); setFilter(Expression.match(Expression.get("class"), Expression.literal(false),
                Expression.stop("park", true), Expression.stop("garden", true), Expression.stop("playground", true), Expression.stop("cemetery", true)))
        }, "yesil-alan")
    }

    /** Şehrin önemli yerleri: simgeler uzaktan, adlar yakınlaşınca. Dokununca çevresindeki duraklar açılır. */
    private fun addPlaces(style: Style) {
        if (style.getSource(PLACES) != null) return
        placeKinds.forEach { (kind, look) -> runCatching { style.addImage("yer-$kind", placeBitmap(look.first, look.second)) } }
        val features = JSONArray()
        runCatching {
            val places = JSONObject(context.assets.open("yerler.json").bufferedReader().use { it.readText() }).getJSONArray("yerler")
            for (i in 0 until places.length()) {
                val o = places.getJSONObject(i)
                features.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("ad", o.getString("ad"))
                    .put("tur", o.getString("tur")).put("lat", o.getDouble("lat")).put("lng", o.getDouble("lng")))
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(o.getDouble("lng"), o.getDouble("lat"))))))
            }
        }
        style.addSource(GeoJsonSource(PLACES, JSONObject().put("type", "FeatureCollection").put("features", features).toString()))
        style.addLayer(SymbolLayer(PLACES, PLACES).withProperties(
            iconImage(Expression.concat(Expression.literal("yer-"), Expression.get("tur"))),
            iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11, 0.5f), Expression.stop(16, 0.8f))),
            iconAllowOverlap(true), iconIgnorePlacement(true),
            textField(Expression.step(Expression.zoom(), Expression.literal(""), Expression.stop(13, Expression.get("ad")))),
            textSize(11f), textColor("#26363A"), textHaloColor("#FFFFFF"), textHaloWidth(1.5f), textFont(arrayOf("Noto Sans Bold")),
            textOffset(arrayOf(0f, 1.5f)), textAnchor(Property.TEXT_ANCHOR_TOP), textOptional(true)).apply { minZoom = 10.5f })
    }

    /** Otobüsler yeni konumlarına zıplamadan, 1,5 saniyede kayarak gider. */
    private val shownBuses = mutableMapOf<String, Coordinate>()
    private val busTargets = mutableMapOf<String, List<Triple<JSONObject, Coordinate, Coordinate>>>()
    private var busSignature = ""
    private var busAnimator: android.animation.ValueAnimator? = null
    private fun busCollection(items: List<Triple<JSONObject, Coordinate, Coordinate>>, t: Float): String {
        val features = JSONArray()
        items.forEach { (props, from, to) ->
            val lat = from.latitude + (to.latitude - from.latitude) * t
            val lng = from.longitude + (to.longitude - from.longitude) * t
            features.put(JSONObject().put("type", "Feature").put("properties", props)
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(lng, lat)))))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }
    private fun animateBuses(style: Style) {
        val signature = busTargets.entries.sortedBy { it.key }.joinToString("|") { (id, items) ->
            id + items.joinToString(",") { "${it.first.optString("vehicle")}@${it.third.latitude},${it.third.longitude},${it.first.optString("stroke")}" } }
        if (signature == busSignature) return
        busSignature = signature
        busAnimator?.cancel()
        val targets = busTargets.toMap()
        val finish = {
            shownBuses.clear()
            targets.values.flatten().forEach { (props, _, to) -> shownBuses[props.optString("vehicle")] = to }
        }
        val step = { t: Float -> targets.forEach { (id, items) -> style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(busCollection(items, t)) } }
        if (targets.values.flatten().none { it.second != it.third }) { step(1f); finish(); return }
        busAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { runCatching { step(it.animatedValue as Float) } }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { finish() }
            })
            start()
        }
    }

    fun render(snapshots: List<LineSnapshot>, favoritePlates: Set<String>, now: Long = System.currentTimeMillis(), showVehicles: Boolean = true) {
        val style = map?.style ?: return
        if (!locationOn) locationOn = showLocation(style)
        val keys = snapshots.map { it.line.key.replace(':', '-') }.toSet()
        (rendered - keys).forEach { key ->
            style.removeLayer("route-$key"); style.removeLayer("route-casing-$key"); style.removeLayer("route-arrows-$key"); style.removeLayer("stops-$key")
            style.removeLayer("vehicle-label-$key"); style.removeLayer("vehicles-$key"); style.removeLayer("vehicles-bg-$key"); style.removeLayer("vehicle-dir-$key")
            style.removeSource("route-source-$key"); style.removeSource("stop-source-$key")
            style.removeSource("vehicle-source-$key")
        }
        val stopIndex = mutableMapOf<Long, Stop>()
        val vehicleIndex = mutableMapOf<String, Pair<Line, Vehicle>>()
        val codes = snapshots.map { it.line.code }.distinct()
        snapshots.forEach { snapshot ->
            val key = snapshot.line.key.replace(':', '-')
            val color = lineColorFor(snapshot.line.code, codes)
            // Aynı renk; dönüş güzergâhı kesikli çizgiyle ayrılır
            val donus = snapshot.line.direction != 1
            val shape = snapshot.shape?.data as? JSONObject
            // Aynı yoldan geçen farklı hatlar da ayrı şeritte: her hat kodu biraz daha dışa kayar
            val lane = codes.indexOf(snapshot.line.code).coerceAtLeast(0)
            val offset = Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12, 0f), Expression.stop(16, 4f + lane * 7f))
            if (shape != null) {
                val sourceId = "route-source-$key"
                val source = style.getSourceAs<GeoJsonSource>(sourceId)
                if (source == null) {
                    style.addSource(GeoJsonSource(sourceId, shape.toString()))
                    // Güzergâh durakların altında, haritanın üstünde: beyaz kenarlı kalın çizgi ve yön okları
                    // İki yön aynı yolda üst üste binmesin: her yön kendi sağına kayar
                    val width = Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 3f), Expression.stop(16, 8f))
                    val casing = LineLayer("route-casing-$key", sourceId).withProperties(lineColor("#FFFFFF"),
                        lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 5f), Expression.stop(16, 12f))),
                        lineOffset(offset), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))
                    val route = LineLayer("route-$key", sourceId).withProperties(lineColor(color), lineWidth(width), lineOffset(offset),
                        lineCap(if (donus) Property.LINE_CAP_BUTT else Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))
                    if (donus) route.setProperties(lineDasharray(arrayOf(1.6f, 1f)))
                    val arrows = SymbolLayer("route-arrows-$key", sourceId).withProperties(symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                        symbolSpacing(90f), iconImage(CHEVRON), iconColor("#FFFFFF"), iconAllowOverlap(true), iconIgnorePlacement(true),
                        iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(13, 0.4f), Expression.stop(16, 0.6f)))).apply { minZoom = 13f }
                    // Beyaz kenarlar bütün hat çizgilerinin altında kalır; yoksa bir yönün kenarı ötekini örter
                    val firstRoute = style.layers.firstOrNull { it.id.startsWith("route-") }?.id
                    val below = if (style.getLayer(ALL_STOPS) != null) ALL_STOPS else null
                    when { firstRoute != null -> style.addLayerBelow(casing, firstRoute); below != null -> style.addLayerBelow(casing, below); else -> style.addLayer(casing) }
                    listOf(route, arrows).forEach { if (below != null) style.addLayerBelow(it, below) else style.addLayer(it) }
                } else updateSource(source, sourceId, shape.toString())
                style.getLayerAs<LineLayer>("route-$key")?.setProperties(lineColor(color), lineOffset(offset))
                style.getLayerAs<LineLayer>("route-casing-$key")?.setProperties(lineOffset(offset))
            }
            val stops = runCatching { snapshot.stops?.objectData?.requiredArray("lineStops")?.objects()?.map(::parseStop) }.getOrNull().orEmpty()
            stops.forEach { stopIndex[it.id] = it }
            val features = JSONArray()
            stops.filter { it.latitude != null && it.longitude != null }.forEach { stop ->
                features.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("id", stop.id))
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(stop.longitude, stop.latitude)))))
            }
            val json = JSONObject().put("type", "FeatureCollection").put("features", features).toString()
            val sourceId = "stop-source-$key"
            val source = style.getSourceAs<GeoJsonSource>(sourceId)
            if (source == null) {
                style.addSource(GeoJsonSource(sourceId, json))
                style.addLayer(CircleLayer("stops-$key", sourceId).withProperties(
                    circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12, 3.5f), Expression.stop(16, 8f))),
                    circleColor("#FFFFFF"), circleStrokeColor(color), circleStrokeWidth(3f)).apply { minZoom = 12f })
            } else updateSource(source, sourceId, json)
            style.getLayerAs<CircleLayer>("stops-$key")?.setProperties(circleStrokeColor(color))
            val vehicles = if (!showVehicles) emptyList() else runCatching {
                snapshot.vehicles?.takeUnless { it.isOld(java.time.Instant.ofEpochMilli(now)) }?.arrayData?.let(::parseVehicles)
            }.getOrNull().orEmpty()
            val moving = mutableListOf<Triple<JSONObject, Coordinate, Coordinate>>()
            vehicles.forEach { vehicle ->
                val vehicleKey = "${snapshot.line.key}:${vehicle.plate}"
                vehicleIndex[vehicleKey] = snapshot.line to vehicle
                val props = JSONObject().put("vehicle", vehicleKey)
                    .put("stroke", if (vehicle.plate in favoritePlates) "#FFC94A" else "#FFFFFF")
                    .put("label", snapshot.line.code).put("dest", "→ " + destinationOf(snapshot.line))
                vehicle.angle?.let { props.put("angle", it) }
                moving += Triple(props, shownBuses[vehicleKey] ?: vehicle.coordinate, vehicle.coordinate)
            }
            busTargets[ "vehicle-source-$key" ] = moving
            val busJson = busCollection(moving, 1f)
            val busSourceId = "vehicle-source-$key"
            val busSource = style.getSourceAs<GeoJsonSource>(busSourceId)
            if (busSource == null) {
                style.addSource(GeoJsonSource(busSourceId, busJson))
                // Tek parça işaret: uç gidiş yönüne döner (açı yoksa düz daire). Kenar, altta biraz büyük beyaz
                // (favori plakada sarı) kopyayla çizilir; SDF halo düz bitmapte işareti tamamen boyuyor.
                val image = Expression.switchCase(Expression.has("angle"), Expression.literal(BUS_HEADING), Expression.literal(BUS_DOT))
                val rotate = Expression.coalesce(Expression.toNumber(Expression.get("angle")), Expression.literal(0f))
                fun busLayer(id: String, scale: Float) = SymbolLayer(id, busSourceId).withProperties(iconImage(image), iconRotate(rotate),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(true),
                    iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11, 1.0f * scale), Expression.stop(16, 1.45f * scale))))
                style.addLayer(busLayer("vehicles-bg-$key", 1.22f).withProperties(iconColor(Expression.toColor(Expression.get("stroke")))))
                style.addLayer(busLayer("vehicles-$key", 1f).withProperties(iconColor(color)))
                style.addLayer(SymbolLayer("vehicle-label-$key", busSourceId).withProperties(textField(Expression.get("label")),
                    textSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11, 8.5f), Expression.stop(16, 11f))),
                    textColor("#FFFFFF"), textFont(arrayOf("Noto Sans Bold")), textAllowOverlap(true), textIgnorePlacement(true)))
                // Yakınlaşınca gittiği yer: "→ BİNEVLER"
                style.addLayer(SymbolLayer("vehicle-dir-$key", busSourceId).withProperties(textField(Expression.get("dest")),
                    textSize(11f), textColor(color), textHaloColor("#FFFFFF"), textHaloWidth(2f), textFont(arrayOf("Noto Sans Bold")),
                    textOffset(arrayOf(0f, 2.1f)), textAnchor(Property.TEXT_ANCHOR_TOP), textOptional(true), textMaxWidth(9f)).apply { minZoom = 13.5f })
            }
            style.getLayerAs<SymbolLayer>("vehicles-$key")?.setProperties(iconColor(color))
            style.getLayerAs<SymbolLayer>("vehicle-dir-$key")?.setProperties(textColor(color))
        }
        busTargets.keys.retainAll(keys.map { "vehicle-source-$it" }.toSet())
        animateBuses(style)
        fitNewLines(snapshots, keys)
        rendered = keys
        knownStops = stopIndex + listOfNotNull(pendingFocus?.let { it.id to it })
        knownVehicles = vehicleIndex
    }
}
