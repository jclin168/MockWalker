package com.example.mockwalker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mockwalker.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// OSM tile and Nominatim policies require a distinct, stable User-Agent naming the app;
// the package name (com.example.*) or a library default gets blocked
private const val USER_AGENT = "MockWalker/${BuildConfig.VERSION_NAME} (Android)"

private val WALK_COLOR = Color.rgb(229, 57, 53) // the walker and whatever it is walking
private val LANDMARK_COLOR = Color.rgb(142, 36, 170)
private val FAVORITE_COLOR = Color.rgb(239, 108, 0)

class MainActivity : AppCompatActivity() {

    private enum class Mode { CIRCLE, ROUTE, LANDMARK }

    private data class LibItem(val title: String, val subtitle: String)

    private lateinit var b: ActivityMainBinding
    private lateinit var store: Store
    private lateinit var circle: Polygon
    private lateinit var polyline: Polyline
    private lateinit var centerMarker: Marker
    private lateinit var walkerMarker: Marker
    private val waypointMarkers = mutableListOf<Marker>()

    // the walk running in the service, drawn apart from the editor so editing never touches it
    private lateinit var activeCircle: Polygon
    private lateinit var activeSlot: Polyline // only marks the z-order slot for activeLines
    private val activeLines = mutableListOf<Polyline>()
    private var activeWalk: ActiveWalk? = null
    private var handOffDraft = false // clear the editor once the started route is running

    private var landmarks = listOf<SavedLandmark>()
    private val landmarkMarkers = mutableListOf<Marker>()

    private var mode = Mode.CIRCLE
    private var center: GeoPoint? = null
    private var suggestedRouteName: String? = null // default name offered by Save after a shape
    private val waypoints = mutableListOf<GeoPoint>()
    private val waypointBreaks = mutableSetOf<Int>() // indices that start a new stroke (pen-up jump)
    private val routeLines = mutableListOf<Polyline>() // one per stroke, drawn where `polyline` sits

    /** A path lifted off the editor, kept until it is pasted again. */
    private data class ClippedPath(
        val points: List<GeoPoint>, val breaks: Set<Int>, val endMode: EndMode, val name: String?
    )

    private var clipboard: ClippedPath? = null

    /** What the next map tap or search places: the drawn path itself, or the clipboard. */
    private enum class Placing { MOVE, PASTE }

    private var placing: Placing? = null
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

        activeCircle = Polygon().apply {
            fillPaint.color = Color.argb(35, 229, 57, 53)
            outlinePaint.color = WALK_COLOR
            outlinePaint.strokeWidth = 5f
            isEnabled = false
        }
        activeSlot = Polyline().apply { isEnabled = false }
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
            icon = walkerArrow(WALK_COLOR, 26)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isFlat = true
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
        b.map.overlays.addAll(
            listOf(activeCircle, activeSlot, circle, polyline, centerMarker, walkerMarker, events)
        )
        reloadLandmarks()
    }

    private fun dot(color: Int, sizeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
        setSize(dp(sizeDp), dp(sizeDp))
    }

    /** Red dot with a white arrowhead pointing up (north); rotated to the walking heading. */
    private fun walkerArrow(color: Int, sizeDp: Int): BitmapDrawable {
        val size = dp(sizeDp)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        c.drawCircle(r, r, r, paint)
        paint.color = color
        c.drawCircle(r, r, r - dp(2), paint)
        paint.color = Color.WHITE
        val arrow = Path().apply {
            moveTo(r, size * 0.18f)
            lineTo(size * 0.74f, size * 0.74f)
            lineTo(r, size * 0.6f)
            lineTo(size * 0.26f, size * 0.74f)
            close()
        }
        c.drawPath(arrow, paint)
        return BitmapDrawable(resources, bmp)
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
        if (mode == Mode.ROUTE && placing != null) {
            // a tapped landmark is a handy anchor, put the path's center on it
            placePath(landmarkAt(p)?.let { GeoPoint(it.lat, it.lon) } ?: p)
            return
        }
        landmarkAt(p)?.let {
            showLandmark(it)
            return
        }
        when (mode) {
            Mode.CIRCLE -> selectCenter(p)
            Mode.ROUTE -> {
                waypoints.add(p)
                refreshAll()
            }
            Mode.LANDMARK -> addLandmark(p, null)
        }
    }

    private fun selectCenter(p: GeoPoint) {
        center = p
        centerMarker.position = p
        refreshAll()
    }

    private fun refreshAll() {
        if (placing == Placing.MOVE && waypoints.isEmpty()) placing = null
        updatePathButtons()
        redrawCircle()
        redrawRoute()
        updateHeader()
        updateStatus()
    }

    private fun redrawCircle() {
        val c = center
        // the running circle is drawn by renderWalk, skip the identical draft on top of it
        val show = mode == Mode.CIRCLE && c != null && (activeWalk as? ActiveWalk.Circle)?.center != c
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
        R.id.btnEndRandom -> EndMode.RANDOM
        else -> EndMode.ONCE
    }

    // ---------- controls ----------

    private fun radiusM() = b.sliderRadius.value.toDouble()
    private fun speedKph() = b.sliderSpeed.value.toDouble()
    private fun speedRandom() = b.switchRandomSpeed.isChecked
    private fun gpsDrift() = b.switchGpsDrift.isChecked

    private fun setupControls() {
        b.modeGroup.check(R.id.btnModeCircle)
        b.endGroup.check(R.id.btnEndOnce)

        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) setMode(
                when (id) {
                    R.id.btnModeRoute -> Mode.ROUTE
                    R.id.btnModeLandmark -> Mode.LANDMARK
                    else -> Mode.CIRCLE
                }
            )
        }
        b.endGroup.addOnButtonCheckedListener { _, _, checked ->
            if (checked) {
                redrawRoute()
                updateStatus()
            }
        }
        b.sliderRadius.addOnChangeListener { _, _, _ -> onParamsChanged() }
        b.sliderSpeed.addOnChangeListener { _, _, _ -> onParamsChanged() }
        b.switchRandomSpeed.setOnCheckedChangeListener { _, _ -> onParamsChanged() }
        b.switchGpsDrift.setOnCheckedChangeListener { _, _ -> onParamsChanged() }

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
        b.btnMovePath.setOnClickListener { togglePlacing(Placing.MOVE) }
        b.btnCutPath.setOnClickListener { cutPath() }
        b.btnPastePath.setOnClickListener { togglePlacing(Placing.PASTE) }
        b.btnRotatePath.setOnClickListener { showRotate() }
        b.btnLandmarks.setOnClickListener { showLandmarks() }

        setMode(Mode.CIRCLE)
        onParamsChanged()
    }

    private fun setMode(m: Mode) {
        mode = m
        placing = null
        b.circleGroup.visibility = if (m == Mode.CIRCLE) View.VISIBLE else View.GONE
        b.routeGroup.visibility = if (m == Mode.ROUTE) View.VISIBLE else View.GONE
        b.landmarkGroup.visibility = if (m == Mode.LANDMARK) View.VISIBLE else View.GONE
        refreshAll()
    }

    private fun onParamsChanged() {
        val r = radiusM()
        val v = speedKph()
        b.tvRadius.text = String.format(Locale.US, "Radius: %.0f m", r)
        renderSpeed()
        redrawCircle()
        updateStatus()
        if (running) {
            val update = Intent(this, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_UPDATE)
                .putExtra(MockLocationService.EXTRA_SPEED_KPH, v)
                .putExtra(MockLocationService.EXTRA_SPEED_RANDOM, speedRandom())
                .putExtra(MockLocationService.EXTRA_GPS_DRIFT, gpsDrift())
            // radius edits the running circle only while the draft is still that circle
            val w = activeWalk
            if (w is ActiveWalk.Circle && center == w.center) {
                update.putExtra(MockLocationService.EXTRA_RADIUS_M, r)
            }
            startService(update)
        }
    }

    /** Chosen speed, plus the live one while a random-speed walk is running. */
    private fun renderSpeed() {
        var text = String.format(Locale.US, "Speed: %.1f km/h", speedKph())
        if (speedRandom()) text += " ±5"
        if (running && speedRandom()) {
            text += String.format(Locale.US, " (now %.1f)", MockState.currentSpeedKph.value)
        }
        b.tvSpeed.text = text
    }

    private fun updateHeader() {
        b.tvCenter.text = when (mode) {
            Mode.CIRCLE -> center?.let {
                String.format(Locale.US, "Center: %.6f, %.6f", it.latitude, it.longitude)
            } ?: "Tap the map to choose a location"
            Mode.ROUTE -> "Route: ${waypoints.size} waypoints"
            Mode.LANDMARK -> "Landmarks: ${landmarks.size}"
        }
    }

    private fun updateStatus() {
        val v = speedKph()
        val walking = when (val w = activeWalk) {
            is ActiveWalk.Route ->
                if (w.path.endMode == EndMode.RANDOM) "Random walk inside the red area"
                else if (MockState.arrived.value) "Arrived at route end. Location is held until you stop."
                else String.format(Locale.US, "Walking the red route, %.0f m", w.path.totalM)
            is ActiveWalk.Circle -> String.format(Locale.US, "Walking the red circle, radius %.0f m", w.radiusM)
            null -> null
        }
        val draft = when (mode) {
            Mode.CIRCLE -> {
                val circ = 2 * PI * radiusM()
                String.format(Locale.US, "Circumference %.0f m, one lap %s", circ, mmss(circ / (v / 3.6)))
            }
            Mode.ROUTE ->
                if (placing == Placing.MOVE) {
                    "Tap the map or search a place to move the route's center there"
                } else if (placing == Placing.PASTE) {
                    "Tap the map or search a place to paste the route centered there"
                } else if (endMode() == EndMode.RANDOM) {
                    if (waypoints.size < 3) "Tap the map to outline an area with at least 3 waypoints"
                    else String.format(
                        Locale.US, "Area %.0f m², random walk, new direction about every 5 s",
                        AreaWalker.areaM2(waypoints)
                    )
                } else if (waypoints.size < 2) {
                    if (activeWalk != null) "Tap the map to draw another route"
                    else "Tap the map to add at least 2 waypoints"
                } else {
                    val len = currentPath().totalM
                    String.format(Locale.US, "Length %.0f m, one pass %s", len, mmss(len / (v / 3.6)))
                }
            Mode.LANDMARK -> "Tap the map to add a landmark, tap a landmark for options"
        }
        b.tvStatus.text = if (walking == null) draft else "$walking\n$draft"
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
            .putExtra(MockLocationService.EXTRA_SPEED_RANDOM, speedRandom())
            .putExtra(MockLocationService.EXTRA_GPS_DRIFT, gpsDrift())

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
            Mode.LANDMARK -> {
                toast("Switch to Circle or Route to start walking")
                return
            }
            Mode.ROUTE -> {
                if (waypoints.size < 2) {
                    toast("Add at least 2 waypoints")
                    return
                }
                if (endMode() == EndMode.RANDOM && waypoints.size < 3) {
                    toast("A random-walk area needs at least 3 waypoints")
                    return
                }
                handOffDraft = true
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
                launch { MockState.currentSpeedKph.collect { renderSpeed() } }
                launch { MockState.walk.collect { renderWalk(it) } }
                launch { MockState.arrived.collect { updateStatus() } }
                launch {
                    MockState.error.collect { msg ->
                        if (msg != null) {
                            handOffDraft = false
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
        if (!isRunning) {
            walkerMarker.isEnabled = false
            b.map.invalidate()
        }
        renderSpeed()
        updateStatus()
    }

    /** Draws the running walk in red; it stays until the service stops, whatever the editor does. */
    private fun renderWalk(w: ActiveWalk?) {
        activeWalk = w
        activeLines.forEach { b.map.overlays.remove(it) }
        activeLines.clear()
        activeCircle.isEnabled = w is ActiveWalk.Circle
        when (w) {
            is ActiveWalk.Circle ->
                activeCircle.setPoints(GeoMath.circlePoints(w.center.latitude, w.center.longitude, w.radiusM))
            is ActiveWalk.Route -> {
                val lineAt = b.map.overlays.indexOf(activeSlot) + 1
                w.path.strokes.forEach { stroke ->
                    val line = Polyline().apply {
                        outlinePaint.color = Color.argb(190, 229, 57, 53)
                        outlinePaint.strokeWidth = 12f
                        setPoints(stroke)
                    }
                    b.map.overlays.add(lineAt + activeLines.size, line)
                    activeLines.add(line)
                }
                if (handOffDraft) {
                    // the started route now lives in the service, free the editor for the next one
                    handOffDraft = false
                    waypoints.clear()
                    waypointBreaks.clear()
                    suggestedRouteName = null
                }
            }
            null -> {}
        }
        refreshAll()
    }

    private fun renderWalker(p: GeoPoint?) {
        if (p == null) {
            walkerMarker.isEnabled = false
        } else {
            walkerMarker.position = p
            // osmdroid rotates markers counter-clockwise, headings are clockwise from north
            walkerMarker.rotation = -MockState.headingDeg.value
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
        val gp = GeoPoint(p.lat, p.lon)
        selectCenter(gp) // first, so the new radius does not resize a circle being walked
        b.sliderRadius.value = p.radiusM.toFloat().coerceIn(1f, 100f)
        b.map.controller.setZoom(18.0)
        b.map.controller.animateTo(gp)
    }

    // ---------- landmarks ----------

    private fun reloadLandmarks() {
        landmarks = store.landmarks()
        landmarkMarkers.forEach { b.map.overlays.remove(it) }
        landmarkMarkers.clear()
        val insertAt = b.map.overlays.indexOf(centerMarker) // above lines, below waypoints and walker
        landmarks.forEach { l ->
            val (icon, anchorY) = landmarkIcon(l.name, l.favorite)
            val m = Marker(b.map).apply {
                position = GeoPoint(l.lat, l.lon)
                this.icon = icon
                setAnchor(Marker.ANCHOR_CENTER, anchorY)
                setInfoWindow(null)
            }
            b.map.overlays.add(insertAt + landmarkMarkers.size, m)
            landmarkMarkers.add(m)
        }
        b.map.invalidate()
        updateHeader()
    }

    /**
     * Star badge with the name underneath, purple or orange for favorites (drawn larger);
     * returns the icon and the badge center as anchor.
     */
    private fun landmarkIcon(name: String, favorite: Boolean): Pair<BitmapDrawable, Float> {
        val badge = if (favorite) FAVORITE_COLOR else LANDMARK_COLOR
        val d = resources.displayMetrics.density
        val r = (if (favorite) 14 else 12) * d
        val label = if (name.length > 20) name.take(19) + "…" else name
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val halo = 3 * d
        val w = max(2 * r + 4 * d, text.measureText(label) + 2 * halo)
        val cy = r + 2 * d
        val labelTop = 2 * cy + 2 * d
        val h = labelTop + text.descent() - text.ascent() + halo
        val bmp = Bitmap.createBitmap(w.toInt() + 1, h.toInt() + 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r + 2 * d, fill)
        fill.color = badge
        c.drawCircle(cx, cy, r, fill)
        val star = Paint(text).apply { color = Color.WHITE; textSize = 15 * d }
        c.drawText("★", cx, cy - (star.ascent() + star.descent()) / 2, star)
        val baseline = labelTop - text.ascent()
        text.style = Paint.Style.STROKE
        text.strokeWidth = halo
        text.color = Color.WHITE
        c.drawText(label, cx, baseline, text)
        text.style = Paint.Style.FILL
        text.color = badge
        c.drawText(label, cx, baseline, text)
        return BitmapDrawable(resources, bmp) to cy / bmp.height
    }

    /** The landmark whose badge or label is under the tap, nearest first. */
    private fun landmarkAt(p: GeoPoint): SavedLandmark? {
        val proj = b.map.projection
        val tap = proj.toPixels(p, null)
        return landmarks
            .map { l -> l to proj.toPixels(GeoPoint(l.lat, l.lon), null) }
            .filter { (_, pt) -> abs(pt.x - tap.x) <= dp(28) && tap.y - pt.y in -dp(20)..dp(40) }
            .minByOrNull { (_, pt) -> hypot((pt.x - tap.x).toDouble(), (pt.y - tap.y).toDouble()) }
            ?.first
    }

    private fun addLandmark(p: GeoPoint, suggested: String?) {
        promptName("New landmark", suggested ?: "Landmark ${landmarks.size + 1}") { name ->
            val favorite = landmarks.any { it.name == name && it.favorite } // re-adding keeps the star
            store.upsertLandmark(SavedLandmark(name, p.latitude, p.longitude, favorite))
            reloadLandmarks()
            toast("Added \"$name\"")
        }
    }

    private fun setFavorite(names: Set<String>, favorite: Boolean) {
        store.saveLandmarks(store.landmarks().map { if (it.name in names) it.copy(favorite = favorite) else it })
        reloadLandmarks()
    }

    private fun deleteLandmarks(names: Set<String>) {
        store.saveLandmarks(store.landmarks().filterNot { it.name in names })
        reloadLandmarks()
    }

    private fun showLandmark(l: SavedLandmark) {
        val gp = GeoPoint(l.lat, l.lon)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when (mode) {
            Mode.CIRCLE -> actions.add("Set as center" to { selectCenter(gp) })
            Mode.ROUTE -> actions.add("Add waypoint" to {
                waypoints.add(gp)
                refreshAll()
            })
            Mode.LANDMARK -> {}
        }
        actions.add(
            if (l.favorite) "Remove from favorites" to { setFavorite(setOf(l.name), false) }
            else "Add to favorites" to { setFavorite(setOf(l.name), true) }
        )
        actions.add("Delete" to { deleteLandmarks(setOf(l.name)) })
        MaterialAlertDialogBuilder(this)
            .setTitle((if (l.favorite) "★ " else "") + l.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** All or favorite landmarks: tap a name to jump there, star to toggle favorite, check to delete. */
    private fun showLandmarks() {
        var favoritesOnly = false
        val selected = mutableSetOf<String>()
        val shown = mutableListOf<SavedLandmark>()
        var dlg: AlertDialog? = null

        val empty = TextView(this).apply { setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val list = ListView(this)
        val adapter = object : ArrayAdapter<SavedLandmark>(this, R.layout.item_landmark, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_landmark, parent, false)
                val l = shown[position]
                val rows = this
                v.findViewById<TextView>(R.id.tvName).text = l.name
                v.findViewById<TextView>(R.id.tvSub).text =
                    String.format(Locale.US, "%.5f, %.5f", l.lat, l.lon)
                v.findViewById<CheckBox>(R.id.cbSelect).apply {
                    setOnCheckedChangeListener(null) // recycled row, drop the old listener first
                    isChecked = l.name in selected
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(l.name) else selected.remove(l.name)
                        updateDeleteButton(dlg, selected.size)
                    }
                }
                v.findViewById<ImageButton>(R.id.btnFavorite).apply {
                    setImageResource(
                        if (l.favorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
                    )
                    setOnClickListener {
                        setFavorite(setOf(l.name), !l.favorite)
                        refill(shown, favoritesOnly, selected, rows, empty)
                        updateDeleteButton(dlg, selected.size)
                    }
                }
                v.findViewById<View>(R.id.textArea).setOnClickListener {
                    dlg?.dismiss()
                    b.map.controller.setZoom(18.0)
                    b.map.controller.animateTo(GeoPoint(l.lat, l.lon))
                }
                return v
            }
        }
        list.adapter = adapter

        val filter = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(dp(20), 0, dp(20), 0)
            val all = RadioButton(context).apply { id = View.generateViewId(); text = "All" }
            val fav = RadioButton(context).apply { id = View.generateViewId(); text = "★ Favorites" }
            addView(all)
            addView(fav)
            check(all.id)
            setOnCheckedChangeListener { _, id ->
                favoritesOnly = id == fav.id
                refill(shown, favoritesOnly, selected, adapter, empty)
                updateDeleteButton(dlg, selected.size)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(filter)
            addView(empty)
            addView(list)
        }
        refill(shown, favoritesOnly, selected, adapter, empty)

        dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Landmarks")
            .setView(content)
            .setPositiveButton("Delete selected", null) // wired below so the dialog stays open
            .setNegativeButton("Close", null)
            .create()
        dlg.setOnShowListener {
            updateDeleteButton(dlg, selected.size)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val names = selected.toSet()
                MaterialAlertDialogBuilder(this)
                    .setMessage("Delete ${names.size} landmark${if (names.size == 1) "" else "s"}?")
                    .setPositiveButton("Delete") { _, _ ->
                        deleteLandmarks(names)
                        refill(shown, favoritesOnly, selected, adapter, empty)
                        updateDeleteButton(dlg, selected.size)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        dlg.show()
    }

    /** Reloads the rows (favorites first) and drops selections that are no longer listed. */
    private fun refill(
        shown: MutableList<SavedLandmark>,
        favoritesOnly: Boolean,
        selected: MutableSet<String>,
        adapter: ArrayAdapter<SavedLandmark>,
        empty: TextView
    ) {
        shown.clear()
        shown.addAll(landmarks.filter { !favoritesOnly || it.favorite }.sortedByDescending { it.favorite })
        selected.retainAll(shown.map { it.name }.toSet())
        adapter.notifyDataSetChanged()
        empty.text = if (favoritesOnly) "No favorites yet. Tap ☆ on a landmark to add it."
        else "No landmarks yet. Tap the map in Landmark mode to add one."
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateDeleteButton(dlg: AlertDialog?, count: Int) {
        dlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isEnabled = count > 0
            text = if (count > 0) "Delete selected ($count)" else "Delete selected"
        }
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
        EndMode.RANDOM -> "random walk"
    }

    private fun loadRoute(r: SavedRoute) {
        b.modeGroup.check(R.id.btnModeRoute)
        checkEndMode(r.endMode)
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

    // ---------- move, cut / paste, rotate ----------

    private fun updatePathButtons() {
        val hasPath = waypoints.isNotEmpty()
        b.btnMovePath.isEnabled = hasPath
        b.btnCutPath.isEnabled = hasPath
        b.btnRotatePath.isEnabled = waypoints.size >= 2
        b.btnPastePath.isEnabled = clipboard != null
        b.btnMovePath.text = if (placing == Placing.MOVE) "Cancel" else "Move"
        b.btnPastePath.text = if (placing == Placing.PASTE) "Cancel" else "Paste"
    }

    /** Waits for a tap or search to place the path; pressing the same button again cancels. */
    private fun togglePlacing(what: Placing) {
        placing = if (placing == what) null else what
        refreshAll()
    }

    private fun cutPath() {
        if (waypoints.isEmpty()) return
        clipboard = ClippedPath(waypoints.toList(), waypointBreaks.toSet(), endMode(), suggestedRouteName)
        waypoints.clear()
        waypointBreaks.clear()
        suggestedRouteName = null
        placing = null
        refreshAll()
        toast("Route cut. Tap Paste, then tap the map or search where it goes")
    }

    private fun placePath(target: GeoPoint) {
        when (placing) {
            Placing.MOVE -> {
                val moved = PathTransform.moveTo(waypoints, target)
                waypoints.clear()
                waypoints.addAll(moved)
            }
            Placing.PASTE -> {
                val c = clipboard ?: return
                // pasting replaces the draft; the clipboard is kept so it can be pasted again
                checkEndMode(c.endMode)
                waypoints.clear()
                waypoints.addAll(PathTransform.moveTo(c.points, target))
                waypointBreaks.clear()
                waypointBreaks.addAll(c.breaks)
                suggestedRouteName = c.name
            }
            null -> return
        }
        placing = null
        refreshAll()
        zoomToPoints(waypoints)
    }

    private fun checkEndMode(m: EndMode) = b.endGroup.check(
        when (m) {
            EndMode.ONCE -> R.id.btnEndOnce
            EndMode.PING_PONG -> R.id.btnEndPingPong
            EndMode.LOOP -> R.id.btnEndLoop
            EndMode.RANDOM -> R.id.btnEndRandom
        }
    )

    /** Turns the drawn path around its center with a live preview; Cancel puts it back. */
    private fun showRotate() {
        if (waypoints.size < 2) return
        val original = waypoints.toList()
        val pivot = PathTransform.center(original)
        fun preview(deg: Double) {
            waypoints.clear()
            waypoints.addAll(PathTransform.rotate(original, deg, pivot))
            redrawRoute()
        }

        val slider = Slider(this).apply {
            valueFrom = -180f
            valueTo = 180f
            stepSize = 1f
            value = 0f
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setText("0")
        }
        var syncing = false
        slider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            syncing = true
            et.setText(v.roundToInt().toString())
            et.setSelection(et.text.length)
            syncing = false
            preview(v.toDouble())
        }
        et.doAfterTextChanged { text ->
            if (syncing) return@doAfterTextChanged
            val deg = text.toString().toDoubleOrNull() ?: return@doAfterTextChanged
            // any angle works, the slider shows its equivalent within -180..180
            val wrapped = ((deg % 360.0) + 540.0) % 360.0 - 180.0
            slider.value = wrapped.roundToInt().toFloat().coerceIn(-180f, 180f)
            preview(deg)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(context).apply { text = "Degrees, clockwise (negative turns counter-clockwise)" })
            addView(et)
            addView(slider)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rotate route")
            .setView(content)
            .setPositiveButton("OK") { _, _ -> refreshAll() }
            .setNegativeButton("Cancel") { _, _ ->
                waypoints.clear()
                waypoints.addAll(original)
                refreshAll()
            }
            .setOnCancelListener {
                waypoints.clear()
                waypoints.addAll(original)
                refreshAll()
            }
            .show()
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
                if (hit == null) toast("No result") else goTo(GeoPoint(hit.first, hit.second), q)
            }.onFailure { toast("Search failed") }
        }
    }

    private fun goTo(p: GeoPoint, label: String? = null) {
        b.map.controller.animateTo(p)
        when (mode) {
            Mode.CIRCLE -> selectCenter(p)
            Mode.LANDMARK -> addLandmark(p, label)
            Mode.ROUTE -> if (placing != null) placePath(p)
        }
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
