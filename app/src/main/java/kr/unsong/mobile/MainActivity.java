package kr.unsong.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 화면(assets/www/index.html)을 띄우고, 화면이 쓰는 기기 기능을 연결한다. */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final int REQ_FILE = 1, REQ_LOCATION = 2, REQ_NOTIFY = 3;

    private WebView web;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private TextRecognizer recognizer;
    private long leftAt;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.loadUrl(HOME);

        ReminderReceiver.schedule(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("askedNotify", false)) {
            prefs.edit().putBoolean("askedNotify", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
        if (hasLocationPermission()) fetchLocation();
    }

    @Override
    protected void onStop() {
        super.onStop();
        leftAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (leftAt > 0) {
            js("window.__resume&&__resume(" + (SystemClock.elapsedRealtime() - leftAt) + ")");
            leftAt = 0;
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        if (recognizer != null) recognizer.close();
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back&&__back())===true", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    private void js(final String code) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    /* ---------- 화면 불러오기, 바깥 링크, 전화 ---------- */
    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            try {
                String mime = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css" : "text/html";
                return new WebResourceResponse(mime, "utf-8", getAssets().open("www" + path));
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                        new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent("tel".equals(u.getScheme()) ? Intent.ACTION_DIAL : Intent.ACTION_VIEW, u));
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
    }

    /* ---------- 캡처·백업 파일 고르기 ---------- */
    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            boolean image = false;
            String[] types = params.getAcceptTypes();
            if (types != null) for (String t : types) if (t != null && t.startsWith("image")) image = true;
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType(image ? "image/*" : "*/*");
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(pick, image ? "캡처 고르기" : "파일 고르기"), REQ_FILE);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_FILE || fileCallback == null) return;
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < uris.length; i++) uris[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(uris);
        fileCallback = null;
    }

    /* ---------- 위치(날씨용) ---------- */
    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void fetchLocation() {
        LocationManager lm = getSystemService(LocationManager.class);
        if (lm == null) return;
        try {
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) saveLocation(best);
            if (Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, getMainExecutor(), l -> {
                    if (l != null) saveLocation(l);
                });
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // 위치를 못 얻으면 기본 지역 날씨를 그대로 쓴다.
        }
    }

    private void saveLocation(Location l) {
        String now = String.format(Locale.US, "%.3f,%.3f", l.getLatitude(), l.getLongitude());
        if (now.equals(prefs.getString("loc", ""))) return;
        prefs.edit().putString("loc", now).apply();
        js("window.__locGranted&&__locGranted()");
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_LOCATION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) fetchLocation();
    }

    /* ---------- 화면에서 부르는 기능 (window.Native) ---------- */
    private class Bridge {
        private File store() {
            return new File(getFilesDir(), "data.json");
        }

        /** 앱 내부 파일에 보관한 기록. 브라우저 저장소가 비었을 때 복원에 쓴다. */
        @JavascriptInterface
        public String loadPersist() {
            File f = store();
            if (!f.exists()) return "";
            try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        @JavascriptInterface
        public void persist(final String json) {
            io.execute(() -> {
                File tmp = new File(getFilesDir(), "data.json.tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    return;
                }
                tmp.renameTo(store());
            });
        }

        /** 다운로드 폴더의 "운송모바일"에 파일을 저장한다. 성공하면 "ok". */
        @JavascriptInterface
        public String saveFile(String name, String base64) {
            try {
                byte[] raw = Base64.decode(base64, Base64.DEFAULT);
                String mime = name.endsWith(".xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : name.endsWith(".json") ? "application/json" : "application/octet-stream";
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/운송모바일");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "저장 위치를 만들지 못했습니다";
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return "파일을 열지 못했습니다";
                    out.write(raw);
                }
                if (!name.contains("자동백업")) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "다운로드 폴더의 \"운송모바일\"에 저장했습니다", Toast.LENGTH_LONG).show());
                }
                return "ok";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }

        /** 캡처 이미지의 글자를 줄 단위로 읽어 화면의 __ocrDone(id, 결과)로 돌려준다. */
        @JavascriptInterface
        public void ocr(final String id, final String base64) {
            io.execute(() -> {
                try {
                    byte[] raw = Base64.decode(base64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    if (bmp == null) {
                        ocrFail(id, "이미지를 열지 못했습니다");
                        return;
                    }
                    final int w = bmp.getWidth(), h = bmp.getHeight();
                    if (recognizer == null) {
                        recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
                    }
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener(text -> {
                                try {
                                    JSONArray lines = new JSONArray();
                                    for (Text.TextBlock block : text.getTextBlocks()) {
                                        for (Text.Line line : block.getLines()) {
                                            Rect r = line.getBoundingBox();
                                            if (r == null) continue;
                                            lines.put(new JSONObject().put("t", line.getText())
                                                    .put("x", r.left).put("y", r.top)
                                                    .put("w", r.width()).put("h", r.height()));
                                        }
                                    }
                                    JSONObject res = new JSONObject().put("w", w).put("h", h).put("lines", lines);
                                    js("__ocrDone(" + JSONObject.quote(id) + "," + res + ")");
                                } catch (Exception e) {
                                    ocrFail(id, String.valueOf(e.getMessage()));
                                }
                            })
                            .addOnFailureListener(e -> ocrFail(id, String.valueOf(e.getMessage())));
                } catch (Throwable t) {
                    ocrFail(id, String.valueOf(t.getMessage()));
                }
            });
        }

        private void ocrFail(String id, String message) {
            js("__ocrDone(" + JSONObject.quote(id) + ",{error:" + JSONObject.quote(message) + "})");
        }

        /** 저장해 둔 위치 "위도,경도". 없으면 빈 문자열. */
        @JavascriptInterface
        public String getLocation() {
            return prefs.getString("loc", "");
        }

        @JavascriptInterface
        public void requestLocation() {
            runOnUiThread(() -> {
                if (hasLocationPermission()) fetchLocation();
                else requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            });
        }

        /** 폰에 지문·PIN·패턴 같은 화면 잠금이 걸려 있는지. */
        @JavascriptInterface
        public boolean canAuth() {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            return km != null && km.isDeviceSecure();
        }

        /** 지문이나 화면 잠금으로 확인하고, 성공하면 화면의 __authDone(true)를 부른다. */
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void auth() {
            runOnUiThread(() -> {
                try {
                    BiometricPrompt.Builder b = new BiometricPrompt.Builder(MainActivity.this)
                            .setTitle("운송 모바일")
                            .setSubtitle("지문이나 화면 잠금으로 엽니다");
                    if (Build.VERSION.SDK_INT >= 30) {
                        b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                    } else {
                        b.setDeviceCredentialAllowed(true);
                    }
                    b.build().authenticate(new CancellationSignal(), getMainExecutor(),
                            new BiometricPrompt.AuthenticationCallback() {
                                @Override
                                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                    js("window.__authDone&&__authDone(true)");
                                }
                            });
                } catch (Exception e) {
                    // 확인 창을 띄울 수 없으면 비밀번호로 들어간다.
                }
            });
        }

        /** 알림 목록([{id,date,title,text}])을 저장하고 매일 아침 확인을 예약한다. */
        @JavascriptInterface
        public void setReminders(String json) {
            prefs.edit().putString("reminders", json).apply();
            ReminderReceiver.run(MainActivity.this);
        }
    }
}
