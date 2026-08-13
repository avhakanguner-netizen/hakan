package net.avhakanguner.sarjtr;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 73;
    private static final String USER_AGENT = "SarjTR/1.2 Android EV charging map (github.com/avhakanguner-netizen/hakan)";
    private static final Pattern KW_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*kW", Pattern.CASE_INSENSITIVE);

    private WebView webView;
    private LocationManager locationManager;
    private LocationListener listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " SarjTR/1.2");

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if ("file".equalsIgnoreCase(uri.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Bağlantı açılamadı", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void requestStations(String ocmKey) {
            final String key = ocmKey == null ? "" : ocmKey.trim();
            new Thread(() -> loadStations(key)).start();
        }

        @JavascriptInterface
        public void requestRoute(double fromLat, double fromLon, double toLat, double toLon) {
            new Thread(() -> loadRoute(fromLat, fromLon, toLat, toLon)).start();
        }

        @JavascriptInterface
        public void requestLocation() {
            runOnUiThread(() -> ensureLocationPermissionAndLocate());
        }

        @JavascriptInterface
        public void geocodeDestination(String query) {
            if (query == null || query.trim().isEmpty()) {
                inject("window.onGeocodeError && window.onGeocodeError('Hedef boş');");
                return;
            }
            final String requested = query.trim();
            new Thread(() -> geocode(requested)).start();
        }

        @JavascriptInterface
        public void openNavigation(double lat, double lon, String title) {
            runOnUiThread(() -> {
                Uri geo = Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(" + Uri.encode(title) + ")");
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, geo));
                } catch (Exception e) {
                    Uri web = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + lat + "," + lon);
                    startActivity(new Intent(Intent.ACTION_VIEW, web));
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            });
        }
    }

    private void loadStations(String ocmKey) {
        try {
            JSONArray combined = loadOsmStations();
            if (!ocmKey.isEmpty()) {
                try {
                    JSONArray ocm = loadOcmStations(ocmKey);
                    for (int i = 0; i < ocm.length(); i++) combined.put(ocm.getJSONObject(i));
                } catch (Exception e) {
                    inject("window.onSupplementalDataWarning && window.onSupplementalDataWarning(" +
                            JSONObject.quote("Open Charge Map ek verisi alınamadı: " + safeMessage(e)) + ");");
                }
            }
            inject("window.onStationsData && window.onStationsData(" + JSONObject.quote(combined.toString()) + ");");
        } catch (Exception e) {
            inject("window.onStationsError && window.onStationsError(" +
                    JSONObject.quote("Şarj istasyonları alınamadı: " + safeMessage(e)) + ");");
        }
    }

    private JSONArray loadOsmStations() throws Exception {
        String query = "[out:json][timeout:90];area[\"ISO3166-1\"=\"TR\"][admin_level=2]->.a;(nwr[\"amenity\"=\"charging_station\"](area.a););out center tags;";
        String form = "data=" + URLEncoder.encode(query, "UTF-8");
        String[] endpoints = new String[]{
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter"
        };
        Exception last = null;
        for (String endpoint : endpoints) {
            try {
                String body = httpPostForm(endpoint, form, 100_000);
                JSONObject root = new JSONObject(body);
                JSONArray elements = root.optJSONArray("elements");
                JSONArray result = new JSONArray();
                if (elements == null) return result;
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject station = normalizeOsm(elements.optJSONObject(i));
                    if (station != null) result.put(station);
                }
                if (result.length() > 0) return result;
                last = new Exception("OpenStreetMap boş sonuç döndürdü");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("OpenStreetMap servisine ulaşılamadı");
    }

    private JSONObject normalizeOsm(JSONObject element) {
        try {
            if (element == null) return null;
            JSONObject tags = element.optJSONObject("tags");
            if (tags == null) tags = new JSONObject();
            double lat;
            double lon;
            if (element.has("lat") && element.has("lon")) {
                lat = element.getDouble("lat");
                lon = element.getDouble("lon");
            } else {
                JSONObject center = element.optJSONObject("center");
                if (center == null) return null;
                lat = center.getDouble("lat");
                lon = center.getDouble("lon");
            }

            String operator = first(tags, "operator", "brand");
            String name = first(tags, "name", "brand", "operator");
            if (name.isEmpty()) name = "Şarj İstasyonu";
            String address = joinNonEmpty(
                    tags.optString("addr:street", ""),
                    tags.optString("addr:housenumber", ""),
                    tags.optString("addr:district", ""),
                    tags.optString("addr:city", "")
            );
            String price = tags.optString("charge", "").trim();
            String fee = tags.optString("fee", "").trim().toLowerCase(Locale.ROOT);
            if (price.isEmpty() && "no".equals(fee)) price = "Ücretsiz";
            else if (price.isEmpty() && "yes".equals(fee)) price = "Ücretli · tarife belirtilmemiş";

            boolean dc = false;
            boolean ac = false;
            double power = 0;
            Iterator<String> keys = tags.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = tags.optString(key, "");
                String lower = key.toLowerCase(Locale.ROOT);
                if (isTruthy(value)) {
                    if (lower.contains("socket:ccs") || lower.contains("socket:chademo") ||
                            lower.contains("socket:nacs") || lower.contains("socket:tesla_supercharger")) dc = true;
                    if (lower.contains("socket:type2") || lower.contains("socket:type1") ||
                            lower.contains("socket:schuko") || lower.contains("socket:cee")) ac = true;
                }
                if (lower.contains("output") || lower.contains("power")) {
                    Matcher matcher = KW_PATTERN.matcher(value);
                    while (matcher.find()) {
                        try {
                            double p = Double.parseDouble(matcher.group(1).replace(',', '.'));
                            if (p > power) power = p;
                        } catch (Exception ignored) {}
                    }
                }
            }

            JSONObject out = new JSONObject();
            out.put("id", element.optString("type", "n") + "-" + element.optLong("id"));
            out.put("source", "OSM");
            out.put("lat", lat);
            out.put("lon", lon);
            out.put("name", name);
            out.put("operator", operator);
            out.put("address", address);
            out.put("power", power > 0 ? power : JSONObject.NULL);
            out.put("dc", dc);
            out.put("ac", ac);
            out.put("capacity", parseIntOrNull(tags.optString("capacity", "")));
            out.put("hours", tags.optString("opening_hours", ""));
            out.put("price", price);
            out.put("status", first(tags, "operational_status", "status"));
            out.put("isOperational", JSONObject.NULL);
            out.put("live", false);
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONArray loadOcmStations(String key) throws Exception {
        String url = "https://api.openchargemap.io/v3/poi/?output=json&countrycode=TR&maxresults=5000&compact=true&verbose=false&key=" +
                URLEncoder.encode(key, "UTF-8");
        JSONArray raw = new JSONArray(httpGet(url, 45_000));
        JSONArray result = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject station = normalizeOcm(raw.optJSONObject(i));
            if (station != null) result.put(station);
        }
        return result;
    }

    private JSONObject normalizeOcm(JSONObject p) {
        try {
            if (p == null) return null;
            JSONObject a = p.optJSONObject("AddressInfo");
            if (a == null) return null;
            double lat = a.getDouble("Latitude");
            double lon = a.getDouble("Longitude");
            JSONObject op = p.optJSONObject("OperatorInfo");
            String operator = op == null ? "" : op.optString("Title", "");
            String name = a.optString("Title", "");
            if (name.isEmpty()) name = operator.isEmpty() ? "Şarj İstasyonu" : operator;

            JSONArray connections = p.optJSONArray("Connections");
            double power = 0;
            boolean dc = false;
            boolean ac = false;
            int sockets = 0;
            if (connections != null) {
                for (int i = 0; i < connections.length(); i++) {
                    JSONObject c = connections.optJSONObject(i);
                    if (c == null) continue;
                    power = Math.max(power, c.optDouble("PowerKW", 0));
                    sockets += Math.max(1, c.optInt("Quantity", 1));
                    JSONObject ct = c.optJSONObject("ConnectionType");
                    String title = ct == null ? "" : ct.optString("Title", "");
                    String t = title.toLowerCase(Locale.ROOT);
                    if (t.contains("ccs") || t.contains("chademo") || t.contains("nacs") || t.contains("tesla")) dc = true;
                    if (t.contains("type 2") || t.contains("type 1") || t.contains("cee") || t.contains("schuko")) ac = true;
                }
            }

            JSONObject st = p.optJSONObject("StatusType");
            Object operational = JSONObject.NULL;
            String status = "";
            if (st != null) {
                status = st.optString("Title", "");
                if (st.has("IsOperational") && !st.isNull("IsOperational")) operational = st.optBoolean("IsOperational");
            }

            JSONObject out = new JSONObject();
            out.put("id", String.valueOf(p.optLong("ID", iSafeId(p))));
            out.put("source", "OCM");
            out.put("lat", lat);
            out.put("lon", lon);
            out.put("name", name);
            out.put("operator", operator);
            out.put("address", joinComma(
                    a.optString("AddressLine1", ""),
                    a.optString("Town", ""),
                    a.optString("StateOrProvince", "")
            ));
            out.put("power", power > 0 ? power : JSONObject.NULL);
            out.put("dc", dc);
            out.put("ac", ac);
            int numberOfPoints = p.optInt("NumberOfPoints", 0);
            out.put("capacity", numberOfPoints > 0 ? numberOfPoints : (sockets > 0 ? sockets : JSONObject.NULL));
            out.put("hours", "");
            out.put("price", p.optString("UsageCost", ""));
            out.put("status", status);
            out.put("isOperational", operational);
            out.put("live", false);
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private long iSafeId(JSONObject p) {
        return Math.abs(p.toString().hashCode());
    }

    private void loadRoute(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            String url = "https://router.project-osrm.org/route/v1/driving/" + fromLon + "," + fromLat + ";" + toLon + "," + toLat +
                    "?overview=full&geometries=geojson&steps=false";
            String body = httpGet(url, 35_000);
            inject("window.onRouteData && window.onRouteData(" + JSONObject.quote(body) + ");");
        } catch (Exception e) {
            inject("window.onRouteError && window.onRouteError(" + JSONObject.quote("Rota alınamadı: " + safeMessage(e)) + ");");
        }
    }

    private void geocode(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            String body = httpGet("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=tr&q=" + encoded, 20_000);
            JSONArray arr = new JSONArray(body);
            if (arr.length() == 0) {
                inject("window.onGeocodeError && window.onGeocodeError('Hedef bulunamadı');");
                return;
            }
            JSONObject first = arr.getJSONObject(0);
            double lat = Double.parseDouble(first.getString("lat"));
            double lon = Double.parseDouble(first.getString("lon"));
            String name = first.optString("display_name", query);
            inject("window.onGeocodeResult && window.onGeocodeResult(" + lat + "," + lon + "," + JSONObject.quote(name) + ");");
        } catch (Exception e) {
            inject("window.onGeocodeError && window.onGeocodeError(" + JSONObject.quote("Adres bulunamadı: " + safeMessage(e)) + ");");
        }
    }

    private String httpGet(String urlString, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlString).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Language", "tr");
            int code = c.getResponseCode();
            String body = readBody(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + (body.isEmpty() ? "" : " · " + body.substring(0, Math.min(120, body.length()))));
            return body;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String httpPostForm(String urlString, String form, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlString).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int code = c.getResponseCode();
            String body = readBody(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + (body.isEmpty() ? "" : " · " + body.substring(0, Math.min(120, body.length()))));
            return body;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString();
        }
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static boolean isTruthy(String v) {
        if (v == null) return false;
        String x = v.trim().toLowerCase(Locale.ROOT);
        if (x.isEmpty() || "no".equals(x) || "false".equals(x) || "0".equals(x)) return false;
        return true;
    }

    private static Object parseIntOrNull(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : JSONObject.NULL;
        } catch (Exception e) {
            return JSONObject.NULL;
        }
    }

    private static String joinNonEmpty(String... values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(v.trim());
        }
        return b.toString();
    }

    private static String joinComma(String... values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(v.trim());
        }
        return b.toString();
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private void ensureLocationPermissionAndLocate() {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
            return;
        }
        locate();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            boolean granted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            if (granted) locate();
            else inject("window.onLocationError && window.onLocationError('Konum izni verilmedi');");
        }
    }

    @SuppressWarnings("MissingPermission")
    private void locate() {
        if (locationManager == null) return;
        Location best = null;
        String[] providers = new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            try {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) {}
        }
        if (best != null) sendLocation(best);

        if (listener != null) {
            try { locationManager.removeUpdates(listener); } catch (Exception ignored) {}
        }
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                sendLocation(location);
                try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
            }
            @Override public void onProviderDisabled(String provider) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };

        boolean requested = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 5, listener);
                requested = true;
            }
        } catch (Exception ignored) {}
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 5, listener);
                requested = true;
            }
        } catch (Exception ignored) {}
        if (!requested && best == null) inject("window.onLocationError && window.onLocationError('Konum servisi kapalı');");
    }

    private void sendLocation(Location location) {
        inject("window.setUserLocation && window.setUserLocation(" + location.getLatitude() + "," + location.getLongitude() + ");");
    }

    private void inject(String js) {
        if (webView == null) return;
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (locationManager != null && listener != null) {
            try { locationManager.removeUpdates(listener); } catch (Exception ignored) {}
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
