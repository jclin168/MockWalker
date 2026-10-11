package com.example.mockwalker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.util.GeoPoint
import kotlin.math.PI

/** Shared state observed by the UI. */
object MockState {
    val running = MutableStateFlow(false)
    val position = MutableStateFlow<GeoPoint?>(null)
    val arrived = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
}

/**
 * Pushes a moving position to the system as a mock GPS/network location.
 *  - circle mode: angle += (speed_mps / radius_m) * dt
 *  - route mode : traveled += speed_mps * dt, then position = point on the polyline at that distance
 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.example.mockwalker.START"
        const val ACTION_UPDATE = "com.example.mockwalker.UPDATE"
        const val ACTION_STOP = "com.example.mockwalker.STOP"
        const val EXTRA_MODE = "mode" // "circle" or "route"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_RADIUS_M = "radius_m"
        const val EXTRA_SPEED_KPH = "speed_kph"
        const val EXTRA_PATH_LATS = "path_lats"
        const val EXTRA_PATH_LONS = "path_lons"
        const val EXTRA_END_MODE = "end_mode"
        const val EXTRA_PATH_BREAKS = "path_breaks"

        private const val CHANNEL_ID = "mock_walk"
        private const val NOTIF_ID = 1
        private const val UPDATE_INTERVAL_MS = 1000L
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var routeMode = false
    private var route: RoutePath? = null
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var radiusM = 30.0
    private var speedKph = 5.0
    private var angleRad = 0.0
    private var traveledM = 0.0
    private var lastTickMs = 0L
    private var active = false
    private val activeProviders = mutableListOf<String>()

    // Google Play services fused location (what most apps read) blends real Wi-Fi/cell
    // positions with the GPS test provider unless it is put in mock mode as well
    private var gmsFused: FusedLocationProviderClient? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            tick()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val channel = NotificationChannel(CHANNEL_ID, "Mock walking", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // must go foreground quickly after startForegroundService
                try {
                    ServiceCompat.startForeground(
                        this, NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } catch (e: Exception) {
                    MockState.error.value = "Cannot start foreground service: ${e.message}"
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!readParams(intent, includeSetup = true)) {
                    MockState.error.value = "A route needs at least 2 waypoints."
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!active) start()
                updateNotification()
            }
            ACTION_UPDATE -> if (active) {
                readParams(intent, includeSetup = false)
                updateNotification()
            } else {
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun readParams(intent: Intent, includeSetup: Boolean): Boolean {
        if (includeSetup) {
            routeMode = intent.getStringExtra(EXTRA_MODE) == "route"
            if (routeMode) {
                val lats = intent.getDoubleArrayExtra(EXTRA_PATH_LATS)
                val lons = intent.getDoubleArrayExtra(EXTRA_PATH_LONS)
                if (lats == null || lons == null || lats.size < 2 || lats.size != lons.size) return false
                val end = EndMode.values().getOrElse(intent.getIntExtra(EXTRA_END_MODE, 0)) { EndMode.ONCE }
                val breaks = intent.getIntArrayExtra(EXTRA_PATH_BREAKS)?.toSet() ?: emptySet()
                route = RoutePath(lats.indices.map { GeoPoint(lats[it], lons[it]) }, end, breaks)
            } else {
                route = null
                centerLat = intent.getDoubleExtra(EXTRA_LAT, centerLat)
                centerLon = intent.getDoubleExtra(EXTRA_LON, centerLon)
            }
        }
        radiusM = intent.getDoubleExtra(EXTRA_RADIUS_M, radiusM).coerceAtLeast(1.0)
        speedKph = intent.getDoubleExtra(EXTRA_SPEED_KPH, speedKph).coerceAtLeast(0.0)
        return true
    }

    private fun start() {
        if (!setupProviders()) {
            stopSelf()
            return
        }
        active = true
        angleRad = 0.0 // circle starts at the north point
        traveledM = 0.0 // route starts at the first waypoint
        lastTickMs = SystemClock.elapsedRealtime()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mockwalker:walk").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
        MockState.arrived.value = false
        MockState.running.value = true
        handler.post(ticker)
    }

    private fun setupProviders(): Boolean {
        activeProviders.clear()
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        // Android 12+ has a platform fused provider that would otherwise mix in the real position
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) providers.add(LocationManager.FUSED_PROVIDER)
        for (p in providers) {
            try {
                try {
                    lm.removeTestProvider(p)
                } catch (_: Exception) {
                }
                lm.addTestProvider(
                    p, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(p, true)
                activeProviders.add(p)
            } catch (e: SecurityException) {
                MockState.error.value =
                    "Select Mock Walker as the mock location app in Developer options, then try again."
                return false
            } catch (e: Exception) {
                // provider not supported on this device, skip it
            }
        }
        if (activeProviders.isEmpty()) {
            MockState.error.value = "No mock provider could be created on this device."
            return false
        }
        setupGmsFused()
        return true
    }

    private fun setupGmsFused() {
        gmsFused = null
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (gms != ConnectionResult.SUCCESS) return // no Play services, platform providers are enough
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            client.setMockMode(true)
                .addOnSuccessListener {
                    // stopped before mock mode took effect, undo it so real location is not frozen
                    if (active) gmsFused = client else client.setMockMode(false)
                }
                .addOnFailureListener {
                    MockState.error.value =
                        "Google location could not enter mock mode, other apps may still see the real position."
                }
        } catch (_: SecurityException) {
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dtSec = (now - lastTickMs) / 1000.0
        lastTickMs = now
        val speedMps = speedKph / 3.6

        var lat: Double
        var lon: Double
        var heading: Float
        var reportedSpeed = speedMps.toFloat()

        val r = route
        if (routeMode && r != null) {
            traveledM += speedMps * dtSec
            val s = r.sampleAt(traveledM)
            lat = s.lat
            lon = s.lon
            heading = s.headingDeg.toFloat()
            if (s.finished) {
                reportedSpeed = 0f
                if (!MockState.arrived.value) MockState.arrived.value = true
            }
        } else {
            angleRad = (angleRad + speedMps / radiusM * dtSec) % (2 * PI)
            val (la, lo) = GeoMath.destination(centerLat, centerLon, angleRad, radiusM)
            lat = la
            lon = lo
            // moving clockwise, heading is tangent to the circle
            heading = ((Math.toDegrees(angleRad) + 90.0) % 360.0).toFloat()
        }

        fun makeLocation(provider: String) = Location(provider).apply {
            latitude = lat
            longitude = lon
            altitude = 0.0
            accuracy = 3f
            speed = reportedSpeed
            bearing = heading
            bearingAccuracyDegrees = 5f
            speedAccuracyMetersPerSecond = 0.5f
            verticalAccuracyMeters = 3f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        for (p in activeProviders) {
            try {
                lm.setTestProviderLocation(p, makeLocation(p))
            } catch (e: SecurityException) {
                MockState.error.value = "Mock location permission was revoked."
                stopSelf()
                return
            } catch (_: Exception) {
            }
        }
        try {
            gmsFused?.setMockLocation(makeLocation("fused"))
        } catch (_: SecurityException) {
        }
        MockState.position.value = GeoPoint(lat, lon)
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (routeMode) {
            String.format("%.1f km/h, route %.0f m", speedKph, route?.totalM ?: 0.0)
        } else {
            String.format("%.1f km/h, radius %.0f m", speedKph, radiusM)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Mock walking active")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        for (p in activeProviders) {
            try {
                lm.removeTestProvider(p)
            } catch (_: Exception) {
            }
        }
        activeProviders.clear()
        try {
            // hand Google fused location back to real sensors
            gmsFused?.setMockMode(false)
        } catch (_: SecurityException) {
        }
        gmsFused = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        MockState.running.value = false
        MockState.arrived.value = false
        MockState.position.value = null
        super.onDestroy()
    }
}
