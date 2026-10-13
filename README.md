# Mock Walker

Android app that mocks GPS location while "walking" on an OpenStreetMap map (osmdroid).

## Build
1. Open this folder in Android Studio (Koala or newer), let Gradle sync
2. Run on a physical device or emulator (Android 8.0+)

## One-time device setup
1. Settings > About phone > tap Build number 7 times to enable Developer options
2. Settings > System > Developer options > Select mock location app > Mock Walker
3. Launch the app and grant location and notification permissions

## Circle mode
1. Tap the map, search a place, or type `lat,lon` to set the center
2. Radius 1 to 100 m, speed 1 to 30 km/h
3. Start walking. Radius and speed can change live
4. Save place stores center and radius. Saved places lists, loads and deletes them

## Route mode
1. Switch to Route and tap the map to add waypoints (green = start)
2. Choose end behavior: Once (hold at the end), Back & forth, Loop, or Random. Random treats
   the waypoints (at least 3) as the outline of an area and wanders inside it: a new random
   direction every 5 seconds, turning gradually, and bouncing back in at the edge
3. Undo removes the last waypoint, Clear removes all
4. Save stores the route. Routes lists, loads and deletes them
5. Speed can change live while walking
6. Move: tap Move, then tap the map (or a landmark, or search a place / `lat,lon`) to put the
   route's center there. Tap Cancel to give up
7. Cut / Paste: Cut lifts the route off the editor; Paste then places it centered on the next
   tap or search. The clipboard is kept, so the same route can be pasted again elsewhere
8. Rotate turns the route around its center: drag the slider or type exact degrees
   (clockwise, negative for counter-clockwise) with a live preview; Cancel restores it
9. Moved and rotated routes keep their size and shape in meters

## Speed and heading
- Random speed ±5 km/h (on by default) lets the pace drift up to 5 km/h above or below the
  chosen speed (never below 1 km/h). A new target pace is picked every 4 to 12 seconds and
  approached gradually; the live speed is shown next to the speed setting while walking
- GPS drift ±2 m on routes (on by default) imitates real GPS noise: the position wanders
  sideways, perpendicular to the direction of travel, picking a new offset every 2 to 6 seconds
  and sliding to it at up to 0.4 m/s. It never strays more than 2 m from the route line
- The reported bearing follows the direction of travel: on a route it looks a few meters ahead
  so it turns into corners smoothly, on a circle it is the tangent. The red walker arrow on the
  map points the same way

## Landmark mode
1. Switch to Landmark and tap the map (or search a place) to add a named landmark
2. Landmarks are always shown on the map as a purple star with their name, in every mode;
   favorites are drawn larger in orange
3. Tap a landmark for options: Set as center (Circle), Add waypoint (Route),
   Add to / Remove from favorites, or Delete
4. Landmarks lists them (favorites first) with an All / Favorites filter. Tap a name to jump
   to it, tap the star to toggle favorite, check several and tap Delete selected to remove them

## Editing while walking
The walk in progress is drawn in red and is independent of the editor. When a route starts,
it is handed to the walker and the editor is cleared, so you can keep adding landmarks,
drawing, saving or loading other routes and places. Only speed (and random speed) changes the running walk
(and radius, while the circle being edited is still the one being walked). The red walk
disappears when you stop; save a route first if you want to walk it again.

## How it works
`MockLocationService` is a foreground service. Every second it advances by
speed x elapsed time (circle: angle += speed / radius x dt, route: distance along the
polyline) and calls `LocationManager.setTestProviderLocation` for GPS and network providers.
Places, landmarks and routes are stored as JSON in SharedPreferences.
