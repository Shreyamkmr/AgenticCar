<#
Exports one recorded trip from a copy of the app's database as a Google Earth KML file with layers:
speed-coloured route, engine-load 3D curtain, exaggerated elevation profile, timestamped replay points
with speed/RPM/load/coolant and markers. Standard KML only (no gx: extensions) so Google Earth web and
My Maps open it too. Reads the compressed signal blocks directly,
so the phone's MCP server does not need to be running.

Usage:
  powershell -ExecutionPolicy Bypass -File tools\export\trip_to_kml.ps1 -Database path\to\telemetry.db -Out trip.kml [-TripId <id>] [-Title "..."]
Copy the database off the phone first (binary-safe):
  adb exec-out run-as com.example.agenticcar cat databases/telemetry.db > telemetry.db   (also -wal and -shm)
#>
param(
    [Parameter(Mandatory = $true)][string]$Database,
    [Parameter(Mandatory = $true)][string]$Out,
    [long]$TripId = 0,
    [string]$Title = '',
    [string]$Sqlite = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\sqlite3.exe"
)
$ErrorActionPreference = 'Stop'
$inv = [Globalization.CultureInfo]::InvariantCulture
$deg = [char]0xB0  # kept out of the source so Windows PowerShell's ANSI script decoding cannot mangle it

if ($TripId -eq 0) { $TripId = [long](& $Sqlite $Database "SELECT id FROM trips ORDER BY id DESC LIMIT 1;") }
$tripRow = (& $Sqlite $Database "SELECT ended_at, updated_at, distance_km, max_speed_kmh FROM trips WHERE id = $TripId;") -split '\|'
if (-not $tripRow[0] -and -not $tripRow[1]) { throw "Trip $TripId not found" }

# Signal id -> resolution (must match PidCatalog).
$signals = [ordered]@{
    lat = @(0x3001, 0.000001); lon = @(0x3002, 0.000001); alt = @(0x3003, 0.1)
    speed = @(0x0D, 1.0); rpm = @(0x0C, 0.25); load = @(0x04, (100.0 / 255)); coolant = @(0x05, 1.0); throttle = @(0x11, (100.0 / 255))
}

function Decode-Block([string]$hex, [int]$startMs, [double]$res) {
    $bytes = [byte[]]::new($hex.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16) }
    $pos = 0; $tick = [long]0; $q = [long]0
    $points = [Collections.Generic.List[object]]::new()
    while ($pos -lt $bytes.Length) {
        $vals = foreach ($k in 0..1) {
            $shift = 0; $result = [long]0
            do { $b = [long]$bytes[$pos++]; $result = $result -bor (($b -band 0x7F) -shl $shift); $shift += 7 } while ($b -band 0x80)
            # zig-zag decode
            ($result -shr 1) -bxor (-($result -band 1))
        }
        $tick += $vals[0]; $q += $vals[1]
        $points.Add([pscustomobject]@{ t = $startMs + $tick * 10; v = $q * $res })
    }
    $points
}

$series = @{}
foreach ($name in $signals.Keys) {
    $id, $res = $signals[$name]
    $rows = & $Sqlite $Database "SELECT start_ms, hex(data) FROM signal_blocks WHERE trip_id = $TripId AND signal_id = $id ORDER BY start_ms;"
    $pts = [Collections.Generic.List[object]]::new()
    foreach ($row in $rows) { $start, $hex = $row -split '\|'; if ($hex) { (Decode-Block $hex ([int]$start) $res) | ForEach-Object { $pts.Add($_) } } }
    $series[$name] = $pts
}
if ($series.lat.Count -eq 0) { throw "Trip $TripId has no GPS data" }

# Drop upward RPM blips: >1000 rpm above the median of the last five kept samples and back below it + 400
# within 1.5 s. Real revs ramp up and down; these come from a second ECU answering functional requests
# (trips recorded before the app targeted the engine ECU with ATSH7E0).
$clean = [Collections.Generic.List[object]]::new(); $rpm = $series.rpm
for ($i = 0; $i -lt $rpm.Count; $i++) {
    $recent = @($clean | Select-Object -Last 5 | ForEach-Object v | Sort-Object)
    $ref = if ($recent.Count) { $recent[[int][math]::Floor($recent.Count / 2)] } else { $null }
    $spike = $false
    if ($ref -ne $null -and $rpm[$i].v -gt $ref + 1000) {
        for ($j = $i + 1; $j -lt $rpm.Count -and $rpm[$j].t - $rpm[$i].t -le 1500; $j++) {
            if ($rpm[$j].v -lt $ref + 400) { $spike = $true; break }
        }
    }
    if (-not $spike) { $clean.Add($rpm[$i]) } else { Write-Host ("Dropped corrupt RPM sample {0} at {1:N1} s" -f $rpm[$i].v, ($rpm[$i].t / 1000)) }
}
$series.rpm = $clean

function Value-At($pts, [double]$t, [double]$holdMs = 20000) {
    $lo = 0; $hi = $pts.Count - 1; $found = -1
    while ($lo -le $hi) { $mid = [int](($lo + $hi) / 2); if ($pts[$mid].t -le $t) { $found = $mid; $lo = $mid + 1 } else { $hi = $mid - 1 } }
    if ($found -lt 0 -or ($t - $pts[$found].t) -gt $holdMs) { return $null }
    $pts[$found].v
}

# One sample per GPS fix.
$samples = foreach ($p in $series.lat) {
    $lon = Value-At $series.lon $p.t 2000
    if ($lon -eq $null) { continue }
    [pscustomobject]@{
        t = $p.t; lat = $p.v; lon = $lon; alt = (Value-At $series.alt $p.t)
        speed = (Value-At $series.speed $p.t); rpm = (Value-At $series.rpm $p.t); load = (Value-At $series.load $p.t)
        coolant = (Value-At $series.coolant $p.t); throttle = (Value-At $series.throttle $p.t)
    }
}
Write-Host "Track samples: $($samples.Count)"

function F([double]$x, [int]$d = 6) { $x.ToString("F$d", $inv) }
function Coord($s, [double]$alt = 0) { "{0},{1},{2}" -f (F $s.lon), (F $s.lat), (F $alt 1) }
function Esc([string]$s) { [Security.SecurityElement]::Escape($s) }

# KML colours are aabbggrr. Green -> yellow -> red ramp.
function Ramp([double]$f, [string]$alpha = 'ff') {
    $f = [math]::Max(0, [math]::Min(1, $f))
    $r = [int]([math]::Min(1, 2 * $f) * 255); $g = [int]([math]::Min(1, 2 * (1 - $f)) * 255)
    '{0}{1:x2}{2:x2}{3:x2}' -f $alpha, 0, $g, $r
}

$start = [DateTimeOffset]::FromUnixTimeMilliseconds($TripId)
$local = $start.ToLocalTime()
if (-not $Title) { $Title = "Trip " + $local.ToString('ddd d MMM yyyy, HH:mm', $inv) }
$maxSpeed = [math]::Max(1, ($samples | Where-Object { $_.speed -ne $null } | Measure-Object speed -Maximum).Maximum)
$maxLoad = [math]::Max(1, ($samples | Where-Object { $_.load -ne $null } | Measure-Object load -Maximum).Maximum)
$alts = $samples | Where-Object { $_.alt -ne $null }
$minAlt = if ($alts) { ($alts | Measure-Object alt -Minimum).Minimum } else { 0 }
$maxAlt = if ($alts) { ($alts | Measure-Object alt -Maximum).Maximum } else { 0 }
$durationMin = ($samples[-1].t - $samples[0].t) / 60000
$centerLat = ($samples | Measure-Object lat -Average).Average; $centerLon = ($samples | Measure-Object lon -Average).Average

$sb = [Text.StringBuilder]::new()
function W([string]$s) { [void]$sb.AppendLine($s) }

W '<?xml version="1.0" encoding="UTF-8"?>'
W '<kml xmlns="http://www.opengis.net/kml/2.2">'
W '<Document>'
W "  <name>$(Esc $Title)</name>"
W "  <description><![CDATA[<b>Agentic Car</b> trip recorded via OBD-II + phone GPS.<br/>Duration $(F $durationMin 1) min, distance $(F ([double]$tripRow[2]) 2) km, top speed $(F $maxSpeed 0) km/h, altitude $(F $minAlt 0)-$(F $maxAlt 0) m.<br/>Layers: speed, engine-load curtain, elevation profile, replay points (click one for telemetry).]]></description>"
W "  <LookAt><longitude>$(F $centerLon)</longitude><latitude>$(F $centerLat)</latitude><altitude>0</altitude><heading>20</heading><tilt>55</tilt><range>2200</range><altitudeMode>relativeToGround</altitudeMode></LookAt>"

# --- Speed layer: consecutive fixes grouped into same-colour runs.
W '  <Folder><name>Speed (green slow, red fast)</name><open>1</open>'
$bucketCount = 6
$run = [Collections.Generic.List[object]]::new(); $runBucket = -1
$flush = {
    param($pts, $bucket)
    if ($pts.Count -lt 2) { return }
    $lo = [int]($maxSpeed * $bucket / $bucketCount); $hi = [int]($maxSpeed * ($bucket + 1) / $bucketCount)
    W "    <Placemark><name>$lo-$hi km/h</name><Style><LineStyle><color>$(Ramp (($bucket + 0.5) / $bucketCount))</color><width>7</width></LineStyle></Style>"
    W "      <LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>$(($pts | ForEach-Object { Coord $_ }) -join ' ')</coordinates></LineString></Placemark>"
}
foreach ($s in $samples) {
    $b = if ($s.speed -eq $null) { 0 } else { [math]::Min($bucketCount - 1, [int][math]::Floor($s.speed / $maxSpeed * $bucketCount)) }
    if ($b -ne $runBucket -and $run.Count) { $run.Add($s); & $flush $run $runBucket; $run = [Collections.Generic.List[object]]::new() }
    $run.Add($s); $runBucket = $b
}
& $flush $run $runBucket
W '  </Folder>'

# --- Engine load curtain: wall height and colour follow load.
W '  <Folder><name>Engine load (3D curtain, height = load)</name><visibility>1</visibility>'
for ($i = 1; $i -lt $samples.Count; $i++) {
    $a = $samples[$i - 1]; $b = $samples[$i]
    if ($a.load -eq $null -or $b.load -eq $null) { continue }
    $avg = ($a.load + $b.load) / 2
    W "    <Placemark><Style><LineStyle><color>$(Ramp ($avg / $maxLoad))</color><width>2</width></LineStyle><PolyStyle><color>$(Ramp ($avg / $maxLoad) 'b0')</color></PolyStyle></Style>"
    W "      <LineString><extrude>1</extrude><tessellate>1</tessellate><altitudeMode>relativeToGround</altitudeMode><coordinates>$(Coord $a ($a.load * 1.5)) $(Coord $b ($b.load * 1.5))</coordinates></LineString></Placemark>"
}
W '  </Folder>'

# --- Elevation profile: height above the trip's lowest point, exaggerated 5x.
if ($alts) {
    W '  <Folder><name>Elevation profile (height above lowest point x5)</name><visibility>0</visibility>'
    $coords = ($samples | Where-Object { $_.alt -ne $null } | ForEach-Object { Coord $_ (($_.alt - $minAlt) * 5 + 3) }) -join ' '
    W "    <Placemark><name>GPS altitude $(F $minAlt 0)-$(F $maxAlt 0) m</name><description>GPS altitude is good to about +/-5-15 m; early fixes may drift.</description><visibility>0</visibility><Style><LineStyle><color>ffff9900</color><width>3</width></LineStyle><PolyStyle><color>66ff9900</color></PolyStyle></Style>"
    W "      <LineString><extrude>1</extrude><tessellate>1</tessellate><altitudeMode>relativeToGround</altitudeMode><coordinates>$coords</coordinates></LineString></Placemark>"
    W '  </Folder>'
}

# --- Replay: plain timestamped points (standard KML only, so Google Earth web and My Maps accept it; Earth Pro
# still shows a time slider). One point every few seconds; click a point for its telemetry.
W '  <Folder><name>Replay points (click for telemetry)</name>'
$lastT = [double]::NegativeInfinity
foreach ($s in $samples) {
    if ($s.t - $lastT -lt 5000 -and $s -ne $samples[-1]) { continue }
    $lastT = $s.t
    $when = [DateTimeOffset]::FromUnixTimeMilliseconds($TripId + $s.t)
    $rows = foreach ($f in @(@('Speed', 'speed', 'km/h', 0), @('RPM', 'rpm', '', 0), @('Engine load', 'load', '%', 0), @('Throttle', 'throttle', '%', 0), @('Coolant', 'coolant', "$deg`C", 0), @('GPS altitude', 'alt', 'm', 1))) {
        $v = $s.($f[1]); if ($v -ne $null) { "<tr><td>$($f[0])</td><td><b>$(F $v $f[3]) $($f[2])</b></td></tr>" }
    }
    W "    <Placemark><name>$($when.ToLocalTime().ToString('HH:mm:ss', $inv))</name><TimeStamp><when>$($when.ToString('yyyy-MM-ddTHH:mm:ssZ', $inv))</when></TimeStamp>"
    W "      <description><![CDATA[<table>$($rows -join '')</table>]]></description>"
    W "      <Style><IconStyle><color>$(if ($s.speed -ne $null) { Ramp ($s.speed / $maxSpeed) } else { 'ffffffff' })</color><scale>0.5</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon></IconStyle><LabelStyle><scale>0</scale></LabelStyle></Style>"
    W "      <Point><coordinates>$(Coord $s)</coordinates></Point></Placemark>"
}
W '  </Folder>'

# --- Markers.
function Marker([string]$name, $s, [string]$icon, [string]$desc) {
    W "    <Placemark><name>$(Esc $name)</name><description><![CDATA[$desc]]></description><Style><IconStyle><Icon><href>http://maps.google.com/mapfiles/kml/$icon</href></Icon></IconStyle></Style><Point><coordinates>$(Coord $s)</coordinates></Point></Placemark>"
}
function Info($s) { "t = $(F ($s.t / 1000) 0) s<br/>speed $($s.speed) km/h, RPM $($s.rpm), load $(if ($s.load -ne $null) { F $s.load 0 })%, coolant $($s.coolant) $deg`C" }
W '  <Folder><name>Markers</name>'
Marker 'Start' $samples[0] 'paddle/grn-circle.png' (Info $samples[0])
Marker 'Finish' $samples[-1] 'paddle/red-square.png' (Info $samples[-1])
$top = $samples | Where-Object { $_.speed -ne $null } | Sort-Object speed -Descending | Select-Object -First 1
Marker "Top speed $($top.speed) km/h" $top 'shapes/arrow.png' (Info $top)
$peak = $samples | Where-Object { $_.load -ne $null } | Sort-Object load -Descending | Select-Object -First 1
Marker ("Peak engine load {0}%" -f (F $peak.load 0)) $peak 'shapes/caution.png' (Info $peak)
$hiRpm = $samples | Where-Object { $_.rpm -ne $null } | Sort-Object rpm -Descending | Select-Object -First 1
Marker "Highest RPM $($hiRpm.rpm)" $hiRpm 'shapes/flag.png' (Info $hiRpm)
W '  </Folder>'

W '</Document>'
W '</kml>'

$dir = Split-Path -Parent $Out
if (-not $dir) { $dir = '.' }
New-Item -ItemType Directory -Force $dir | Out-Null
[IO.File]::WriteAllText((Join-Path (Resolve-Path $dir) (Split-Path -Leaf $Out)), $sb.ToString(), (New-Object Text.UTF8Encoding($false)))
Write-Host "Wrote $Out"
