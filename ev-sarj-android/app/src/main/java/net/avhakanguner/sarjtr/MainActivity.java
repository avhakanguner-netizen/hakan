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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 73;
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
        settings.setUserAgentString(settings.getUserAgentString() + " SarjTR/1.1");

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("file".equalsIgnoreCase(scheme)) return false;
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
                Intent intent = new Intent(Intent.ACTION_VIEW, geo);
                try {
                    startActivity(intent);
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

    private void geocode(String query) {
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            URL url = new URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=tr&q=" + encoded);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "SarjTR/1.1 Android EV charging map (github.com/avhakanguner-netizen/hakan)");
            connection.setRequestProperty("Accept-Language", "tr");
            connection.setRequestProperty("Referer", "https://github.com/avhakanguner-netizen/hakan");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("Adres servisi HTTP " + code);
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            reader.close();
            JSONArray arr = new JSONArray(body.toString());
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
            inject("window.onGeocodeError && window.onGeocodeError(" + JSONObject.quote("Adres bulunamadı: " + e.getMessage()) + ");");
        } finally {
            if (connection != null) connection.disconnect();
        }
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
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        inject("window.setUserLocation && window.setUserLocation(" + lat + "," + lon + ");");
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
