package com.example.mockwalker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mockwalker.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt

// OSM tile and Nominatim policies require a distinct, stable User-Agent naming the app;
// the package name (com.example.*) or a library default gets blocked
private const val USER_AGENT = "MockWalker/${BuildConfig.VERSION_NAME} (Android)"

class MainActivity : AppCompatActivity() {

    private enum class Mode { CIRCLE, ROUTE }

    private data class LibItem(val title: String, val subtitle: String)

    private lateinit var b: ActivityMainBinding
    private lateinit var store: Store
    private lateinit var circle: Polygon
    private lateinit var polyline: Polyline
    private lateinit var centerMarker: Marker
    private lateinit var walkerMarker: Marker
    private val waypointMarkers = mutableListOf<Marker>()

    private var mode = Mode.CIRCLE
    private var center: GeoPoint? = null
    private var suggestedRouteName: String? = null // default name offered by Save after a shape
    private val waypoints = mutableListOf<GeoPoint>()
    private val waypointBreaks = mutableSetOf<Int>() // indices that start a new stroke (pen-up jump)
    private val routeLines = mutableListOf<Polyline>() // one per stroke, drawn where `polyline` sits
    private var running = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
                toast("Location permission is required to run the mock service")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid needs a user agent and a writable cache before the MapView is created
        Configuration.getInstance().apply {
            userAgentValue = USER_AGENT
            val base = File(cacheDir, "osmdroid").apply { mkdirs() }
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = Store(this)

        setupMap()
        setupControls()
        observeState()

        if (!hasLocationPermission()) requestPermissions()
    }

    // ---------- map ----------

    private fun setupMap() {
        b.map.setTileSource(TileSourceFactory.MAPNIK)
        b.map.setMultiTouchControls(true)
        b.map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        b.map.controller.setZoom(18.0)
        b.map.controller.setCenter(initialPoint())

        circle = Polygon().apply {
            fillPaint.color = Color.argb(45, 30, 136, 229)
            outlinePaint.color = Color.rgb(30, 136, 229)
            outlinePaint.strokeWidth = 4f
            isEnabled = false
        }
        polyline = Polyline().apply {
            outlinePaint.color = Color.rgb(30, 136, 229)
            outlinePaint.strokeWidth = 8f
            isEnabled = false
        }
        centerMarker = Marker(b.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isEnabled = false
        }
        walkerMarker = Marker(b.map).apply {
            icon = dot(Color.rgb(229, 57, 53), 22)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isEnabled = false
        }

        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })

        // events overlay goes last so it is on top and receives taps first
        b.map.overlays.addAll(listOf(circle, polyline, centerMarker, walkerMarker, events))
    }

    private fun dot(color: Int, sizeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
        setSize(dp(sizeDp), dp(sizeDp))
    }

    private fun initialPoint(): GeoPoint {
        if (hasLocationPermission()) {
            try {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                for (p in listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )) {
                    lm.getLastKnownLocation(p)?.let { return GeoPoint(it.latitude, it.longitude) }
                }
            } catch (_: SecurityException) {
            }
        }
        return GeoPoint(25.0330, 121.5654) // fallback view until the user picks a spot
    }

    private fun onMapTap(p: GeoPoint) {
        if (running) {
            toast("Stop walking before editing")
            return
        }
        when (mode) {
            Mode.CIRCLE -> selectCenter(p)
            Mode.ROUTE -> {
                waypoints.add(p)
                refreshAll()
            }
        }
    }

    private fun selectCenter(p: GeoPoint) {
        center = p
        centerMarker.position = p
        refreshAll()
    }

    private fun refreshAll() {
        redrawCircle()
        redrawRoute()
        updateHeader()
        updateStatus()
    }

    private fun redrawCircle() {
        val c = center
        val show = mode == Mode.CIRCLE && c != null
        centerMarker.isEnabled = show
        circle.isEnabled = show
        if (show && c != null) {
            circle.setPoints(GeoMath.circlePoints(c.latitude, c.longitude, radiusM()))
        }
        b.map.invalidate()
    }

    private fun redrawRoute() {
        waypointMarkers.forEach { b.map.overlays.remove(it) }
        waypointMarkers.clear()
        routeLines.forEach { b.map.overlays.remove(it) }
        routeLines.clear()

        val show = mode == Mode.ROUTE
        polyline.isEnabled = false // only marks the z-order slot for routeLines
        if (show) {
            val lineAt = b.map.overlays.indexOf(polyline) + 1
            currentPath().strokes.forEach { stroke ->
                val line = Polyline().apply {
                    outlinePaint.color = polyline.outlinePaint.color
                    outlinePaint.strokeWidth = polyline.outlinePaint.strokeWidth
                    setPoints(stroke)
                }
                b.map.overlays.add(lineAt + routeLines.size, line)
                routeLines.add(line)
            }
            val insertAt = b.map.overlays.indexOf(walkerMarker) // keep the walker on top
            waypoints.forEachIndexed { i, p ->
                val m = Marker(b.map).apply {
                    position = p
                    icon = dot(if (i == 0) Color.rgb(67, 160, 71) else Color.rgb(251, 140, 0), 16)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                b.map.overlays.add(insertAt + waypointMarkers.size, m)
                waypointMarkers.add(m)
            }
        }
        b.map.invalidate()
    }

    private fun currentPath() = RoutePath(waypoints, endMode(), waypointBreaks)

    private fun endMode() = when (b.endGroup.checkedButtonId) {
        R.id.btnEndPingPong -> EndMode.PING_PONG
        R.id.btnEndLoop -> EndMode.LOOP
        else -> EndMode.ONCE
    }

    // ---------- controls ----------

    private fun radiusM() = b.sliderRadius.value.toDouble()
    private fun speedKph() = b.sliderSpeed.value.toDouble()

    private val lockWhileRunning: List<View>
        get() = listOf(
            b.btnModeCircle, b.btnModeRoute, b.btnEndOnce, b.btnEndPingPong, b.btnEndLoop,
            b.btnUndo, b.btnClear, b.btnPlaces, b.btnRoutes, b.btnShapes
        )

    private fun setupControls() {
        b.modeGroup.check(R.id.btnModeCircle)
        b.endGroup.check(R.id.btnEndOnce)

        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) setMode(if (id == R.id.btnModeRoute) Mode.ROUTE else Mode.CIRCLE)
        }
        b.endGroup.addOnButtonCheckedListener { _, _, checked ->
            if (checked) {
                redrawRoute()
                updateStatus()
            }
        }
        b.sliderRadius.addOnChangeListener { _, _, _ -> onParamsChanged() }
        b.sliderSpeed.addOnChangeListener { _, _, _ -> onParamsChanged() }

        b.btnStart.setOnClickListener { if (running) stopMock() else startMock() }
        b.btnSearch.setOnClickListener { doSearch() }
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        b.btnCollapse.setOnClickListener {
            val open = b.panelBody.visibility == View.VISIBLE
            b.panelBody.visibility = if (open) View.GONE else View.VISIBLE
            b.btnCollapse.text = if (open) "▴" else "▾"
        }

        // library
        b.btnSavePlace.setOnClickListener { savePlace() }
        b.btnPlaces.setOnClickListener { showPlaces() }
        b.btnUndo.setOnClickListener {
            waypoints.removeLastOrNull()
            waypointBreaks.removeAll { it >= waypoints.size }
            refreshAll()
        }
        b.btnClear.setOnClickListener {
            waypoints.clear()
            waypointBreaks.clear()
            suggestedRouteName = null
            refreshAll()
        }
        b.btnSaveRoute.setOnClickListener { saveRoute() }
        b.btnRoutes.setOnClickListener { showRoutes() }
        b.btnShapes.setOnClickListener { showShapes() }

        setMode(Mode.CIRCLE)
        onParamsChanged()
    }

    private fun setMode(m: Mode) {
        mode = m
        b.circleGroup.visibility = if (m == Mode.CIRCLE) View.VISIBLE else View.GONE
        b.routeGroup.visibility = if (m == Mode.ROUTE) View.VISIBLE else View.GONE
        refreshAll()
    }

    private fun onParamsChanged() {
        val r = radiusM()
        val v = speedKph()
        b.tvRadius.text = String.format(Locale.US, "Radius: %.0f m", r)
        b.tvSpeed.text = String.format(Locale.US, "Speed: %.1f km/h", v)
        redrawCircle()
        updateStatus()
        if (running) {
            startService(
                Intent(this, MockLocationService::class.java)
                    .setAction(MockLocationService.ACTION_UPDATE)
                    .putExtra(MockLocationService.EXTRA_RADIUS_M, r)
                    .putExtra(MockLocationService.EXTRA_SPEED_KPH, v)
            )
        }
    }

    private fun updateHeader() {
        b.tvCenter.text = when (mode) {
            Mode.CIRCLE -> center?.let {
                String.format(Locale.US, "Center: %.6f, %.6f", it.latitude, it.longitude)
            } ?: "Tap the map to choose a location"
            Mode.ROUTE -> "Route: ${waypoints.size} waypoints"
        }
    }

    private fun updateStatus() {
        if (running && MockState.arrived.value) {
            b.tvStatus.text = "Arrived at route end. Location is held until you stop."
            return
        }
        val v = speedKph()
        b.tvStatus.text = when (mode) {
            Mode.CIRCLE -> {
                val circ = 2 * PI * radiusM()
                String.format(Locale.US, "Circumference %.0f m, one lap %s", circ, mmss(circ / (v / 3.6)))
            }
            Mode.ROUTE ->
                if (waypoints.size < 2) "Tap the map to add at least 2 waypoints"
                else {
                    val len = currentPath().totalM
                    String.format(Locale.US, "Length %.0f m, one pass %s", len, mmss(len / (v / 3.6)))
                }
        }
    }

    private fun mmss(sec: Double): String {
        val s = sec.roundToInt()
        return String.format(Locale.US, "%d min %02d s", s / 60, s % 60)
    }

    // ---------- start / stop ----------

    private fun startMock() {
        if (!hasLocationPermission()) {
            requestPermissions()
            toast("Grant location permission, then tap Start again")
            return
        }
        val intent = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_SPEED_KPH, speedKph())

        when (mode) {
            Mode.CIRCLE -> {
                val c = center
                if (c == null) {
                    toast("Tap the map to choose a location first")
                    return
                }
                intent.putExtra(MockLocationService.EXTRA_MODE, "circle")
                    .putExtra(MockLocationService.EXTRA_LAT, c.latitude)
                    .putExtra(MockLocationService.EXTRA_LON, c.longitude)
                    .putExtra(MockLocationService.EXTRA_RADIUS_M, radiusM())
            }
            Mode.ROUTE -> {
                if (waypoints.size < 2) {
                    toast("Add at least 2 waypoints")
                    return
                }
                intent.putExtra(MockLocationService.EXTRA_MODE, "route")
                    .putExtra(
                        MockLocationService.EXTRA_PATH_LATS,
                        DoubleArray(waypoints.size) { waypoints[it].latitude }
                    )
                    .putExtra(
                        MockLocationService.EXTRA_PATH_LONS,
                        DoubleArray(waypoints.size) { waypoints[it].longitude }
                    )
                    .putExtra(MockLocationService.EXTRA_END_MODE, endMode().ordinal)
                    .putExtra(MockLocationService.EXTRA_PATH_BREAKS, waypointBreaks.toIntArray())
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMock() {
        stopService(Intent(this, MockLocationService::class.java))
    }

    // ---------- state from service ----------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MockState.running.collect { renderRunning(it) } }
                launch { MockState.position.collect { renderWalker(it) } }
                launch { MockState.arrived.collect { updateStatus() } }
                launch {
                    MockState.error.collect { msg ->
                        if (msg != null) {
                            showError(msg)
                            MockState.error.value = null
                        }
                    }
                }
            }
        }
    }

    private fun renderRunning(isRunning: Boolean) {
        running = isRunning
        b.btnStart.text = if (isRunning) "Stop walking" else "Start walking"
        lockWhileRunning.forEach { it.isEnabled = !isRunning }
        if (!isRunning) {
            walkerMarker.isEnabled = false
            b.map.invalidate()
        }
        updateStatus()
    }

    private fun renderWalker(p: GeoPoint?) {
        if (p == null) {
            walkerMarker.isEnabled = false
        } else {
            walkerMarker.position = p
            walkerMarker.isEnabled = true
        }
        b.map.invalidate()
    }

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Mock location unavailable")
            .setMessage(msg)
            .setPositiveButton("Developer options") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    toast("Open Settings > System > Developer options manually")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------- saved places ----------

    private fun savePlace() {
        val c = center
        if (c == null) {
            toast("Choose a location first")
            return
        }
        promptName("Save place", "Place ${store.places().size + 1}") { name ->
            store.upsertPlace(SavedPlace(name, c.latitude, c.longitude, radiusM().roundToInt()))
            toast("Saved \"$name\"")
        }
    }

    private fun showPlaces() {
        val places = store.places()
        showLibrary(
            "Saved places",
            places.map {
                LibItem(it.name, String.format(Locale.US, "%.5f, %.5f  radius %d m", it.lat, it.lon, it.radiusM))
            }.toMutableList(),
            "No saved places yet. Choose a location and tap Save place.",
            onPick = { i -> loadPlace(places[i]) },
            onDelete = { i ->
                places.removeAt(i)
                store.savePlaces(places)
            }
        )
    }

    private fun loadPlace(p: SavedPlace) {
        b.modeGroup.check(R.id.btnModeCircle)
        b.sliderRadius.value = p.radiusM.toFloat().coerceIn(1f, 100f)
        val gp = GeoPoint(p.lat, p.lon)
        selectCenter(gp)
        b.map.controller.setZoom(18.0)
        b.map.controller.animateTo(gp)
    }

    // ---------- saved routes ----------

    private fun saveRoute() {
        if (waypoints.size < 2) {
            toast("Add at least 2 waypoints first")
            return
        }
        val pts = waypoints.toList()
        val end = endMode()
        val breaks = waypointBreaks.toSet()
        promptName("Save route", suggestedRouteName ?: "Route ${store.routes().size + 1}") { name ->
            store.upsertRoute(SavedRoute(name, pts, end, breaks))
            toast("Saved \"$name\"")
        }
    }

    private fun showRoutes() {
        val routes = store.routes()
        showLibrary(
            "Saved routes",
            routes.map {
                val len = RoutePath(it.points, it.endMode, it.breaks).totalM
                LibItem(it.name, "${it.points.size} waypoints, ${len.roundToInt()} m, ${endLabel(it.endMode)}")
            }.toMutableList(),
            "No saved routes yet. Tap waypoints on the map and tap Save.",
            onPick = { i -> loadRoute(routes[i]) },
            onDelete = { i ->
                routes.removeAt(i)
                store.saveRoutes(routes)
            }
        )
    }

    private fun endLabel(m: EndMode) = when (m) {
        EndMode.ONCE -> "once"
        EndMode.PING_PONG -> "back & forth"
        EndMode.LOOP -> "loop"
    }

    private fun loadRoute(r: SavedRoute) {
        b.modeGroup.check(R.id.btnModeRoute)
        b.endGroup.check(
            when (r.endMode) {
                EndMode.ONCE -> R.id.btnEndOnce
                EndMode.PING_PONG -> R.id.btnEndPingPong
                EndMode.LOOP -> R.id.btnEndLoop
            }
        )
        waypoints.clear()
        waypoints.addAll(r.points)
        waypointBreaks.clear()
        waypointBreaks.addAll(r.breaks)
        suggestedRouteName = null
        refreshAll()
        zoomToPoints(r.points)
    }

    private fun zoomToPoints(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        val pad = 0.0005
        val box = BoundingBox(
            points.maxOf { it.latitude } + pad, points.maxOf { it.longitude } + pad,
            points.minOf { it.latitude } - pad, points.minOf { it.longitude } - pad
        )
        b.map.post { b.map.zoomToBoundingBox(box, true, dp(48)) }
    }

    // ---------- generated shapes and text ----------

    private enum class Shape(val label: String) {
        SPIRAL("Spiral"), TRIANGLE("Triangle"), PENTAGON("Pentagon"), HEXAGON("Hexagon"), TEXT("Text")
    }

    /** Replaces the waypoints with a shape centered on the middle of the map; Save stores it as a route. */
    private fun showShapes() {
        val shapes = Shape.values()
        val group = RadioGroup(this)
        shapes.forEachIndexed { i, s ->
            group.addView(RadioButton(this).apply { id = View.generateViewId(); text = s.label; tag = i })
        }
        val sizeLabel = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        val etSize = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val etText = EditText(this).apply {
            hint = "Letters A-Z and digits 0-9"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val etRotation = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setText("0")
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val isText = shapes[g.findViewById<View>(checkedId).tag as Int] == Shape.TEXT
            sizeLabel.text = if (isText) "Letter height (m)" else "Radius (m)"
            etSize.setText(if (isText) "40" else "50")
            etText.visibility = if (isText) View.VISIBLE else View.GONE
        }
        group.check(group.getChildAt(0).id)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(group)
            addView(sizeLabel)
            addView(etSize)
            addView(TextView(context).apply {
                text = "Rotation (degrees, clockwise)"
                setPadding(0, dp(12), 0, 0)
            })
            addView(etRotation)
            addView(etText)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Shapes & text")
            .setMessage("Centered on the middle of the map. Replaces the current waypoints.")
            .setView(content)
            .setPositiveButton("Create") { _, _ ->
                val shape = shapes[group.findViewById<View>(group.checkedRadioButtonId).tag as Int]
                val size = etSize.text.toString().toDoubleOrNull()
                if (size == null || size !in 5.0..5000.0) {
                    toast("Size must be between 5 and 5000 m")
                    return@setPositiveButton
                }
                val rotation = etRotation.text.toString().toDoubleOrNull() ?: 0.0
                applyShape(shape, size, rotation, etText.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyShape(shape: Shape, sizeM: Double, rotationDeg: Double, text: String) {
        val c = b.map.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
        val result = when (shape) {
            Shape.SPIRAL -> ShapeGen.spiral(c, sizeM, rotationDeg)
            Shape.TRIANGLE -> ShapeGen.polygon(c, 3, sizeM, rotationDeg)
            Shape.PENTAGON -> ShapeGen.polygon(c, 5, sizeM, rotationDeg)
            Shape.HEXAGON -> ShapeGen.polygon(c, 6, sizeM, rotationDeg)
            Shape.TEXT -> ShapeGen.text(c, text, sizeM, rotationDeg)
        }
        val pts = result?.points
        if (pts == null || pts.size < 2) {
            toast("Enter text using letters A-Z or digits 0-9")
            return
        }
        // polygons close on themselves; spiral and text read best walked once
        b.endGroup.check(
            when (shape) {
                Shape.TRIANGLE, Shape.PENTAGON, Shape.HEXAGON -> R.id.btnEndLoop
                else -> R.id.btnEndOnce
            }
        )
        waypoints.clear()
        waypoints.addAll(pts)
        waypointBreaks.clear()
        waypointBreaks.addAll(result.breaks)
        suggestedRouteName = if (shape == Shape.TEXT) "Text $text" else shape.label
        refreshAll()
        zoomToPoints(pts)
    }

    // ---------- dialogs ----------

    private fun promptName(title: String, default: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(default)
            setSelection(default.length)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLibrary(
        title: String,
        items: MutableList<LibItem>,
        emptyHint: String,
        onPick: (Int) -> Unit,
        onDelete: (Int) -> Unit
    ) {
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(title).setMessage(emptyHint)
                .setPositiveButton("OK", null).show()
            return
        }
        var dlg: AlertDialog? = null
        val adapter = object : ArrayAdapter<LibItem>(this, R.layout.item_saved, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_saved, parent, false)
                val item = items[position]
                v.findViewById<TextView>(R.id.tvName).text = item.title
                v.findViewById<TextView>(R.id.tvSub).text = item.subtitle
                v.findViewById<View>(R.id.btnDelete).setOnClickListener {
                    onDelete(position)
                    items.removeAt(position)
                    notifyDataSetChanged()
                    if (items.isEmpty()) dlg?.dismiss()
                }
                return v
            }
        }
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            dlg?.dismiss()
            onPick(pos)
        }
        dlg = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(list)
            .setNegativeButton("Close", null)
            .create()
        dlg?.show()
    }

    // ---------- search (Nominatim / OpenStreetMap) ----------

    private fun doSearch() {
        val q = b.etSearch.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.etSearch.windowToken, 0)

        parseLatLon(q)?.let {
            goTo(it)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { geocode(q) } }
            result.onSuccess { hit ->
                if (hit == null) toast("No result") else goTo(GeoPoint(hit.first, hit.second))
            }.onFailure { toast("Search failed") }
        }
    }

    private fun goTo(p: GeoPoint) {
        b.map.controller.animateTo(p)
        if (!running && mode == Mode.CIRCLE) selectCenter(p)
    }

    private fun parseLatLon(s: String): GeoPoint? {
        val m = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[, ]\s*(-?\d+(?:\.\d+)?)\s*$""").find(s) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
    }

    private fun geocode(query: String): Pair<Double, Double>? {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                URLEncoder.encode(query, "UTF-8")
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            return Pair(o.getString("lat").toDouble(), o.getString("lon").toDouble())
        } finally {
            conn.disconnect()
        }
    }

    // ---------- helpers ----------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        b.map.onResume()
    }

    override fun onPause() {
        b.map.onPause()
        super.onPause()
    }
}
