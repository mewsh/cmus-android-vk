package net.pgaskin.cmus.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VKMusicActivity extends Activity {
    private static final String TAG = "VKMusic";
    private static final String PREFS = "vk_music";
    private static final String KEY_TOKEN = "access_token";
    private static final Pattern TOK_RE = Pattern.compile("vk1\\.a\\.[A-Za-z0-9_\\-\\.]{60,}");
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private CmusService service;
    private CmusIpc ipc;
    private boolean bound;

    private String token;
    private TextView statusText;
    private FrameLayout content;
    private WebView webView;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private int pollCount = 0;
    private static final int POLL_INTERVAL_MS = 1500;
    private static final int POLL_MAX = 80;

    private final List<VKApi.Audio> tracks = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CmusService.LocalBinder) binder).getService();
            ipc = service.getIpc();
            bound = true;
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false; ipc = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setText("Инициализация...");
        root.addView(statusText);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button logoutBtn = new Button(this);
        logoutBtn.setText("Сбросить");
        logoutBtn.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_TOKEN).apply();
            token = null;
            recreate();
        });
        btns.addView(logoutBtn);

        Button clearBtn = new Button(this);
        clearBtn.setText("Очистить кэш");
        clearBtn.setOnClickListener(v -> {
            executor.execute(() -> {
                File dir = new File(getCacheDir(), "vk");
                File[] files = dir.listFiles();
                int n = 0;
                if (files != null) for (File f : files) if (f.delete()) n++;
                final int nn = n;
                runOnUiThread(() -> Toast.makeText(this, "Удалено: " + nn, Toast.LENGTH_SHORT).show());
            });
        });
        btns.addView(clearBtn);

        root.addView(btns);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        startForegroundService(new Intent(this, CmusService.class));
        bindService(new Intent(this, CmusService.class), connection, Context.BIND_AUTO_CREATE);

        token = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, null);
        if (token != null && !token.isEmpty()) {
            showTrackList();
            loadTracks();
        } else {
            showLogin();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacksAndMessages(null);
        if (webView != null) { webView.destroy(); webView = null; }
        if (bound) { unbindService(connection); bound = false; }
        executor.shutdown();
    }

    private void showLogin() {
        content.removeAllViews();
        statusText.setText("Залогинься. Жду токен...");

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUserAgentString(UA);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String url = request.getUrl().toString();
                    if (url != null && url.contains("vk1.a.")) captureFromString(url, "net");
                    Map<String, String> h = request.getRequestHeaders();
                    if (h != null) for (String k : h.keySet()) {
                        String v = h.get(k);
                        if (v != null && v.contains("vk1.a.")) captureFromString(v, "hdr:" + k);
                    }
                } catch (Exception e) { Log.e(TAG, "intercept", e); }
                return super.shouldInterceptRequest(view, request);
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null && url.contains("vk1.a.")) captureFromString(url, "net-old");
                return super.shouldInterceptRequest(view, url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                startPolling();
            }
        });
        webView.loadUrl("https://vk.com/audio");
        content.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void startPolling() {
        pollHandler.removeCallbacksAndMessages(null);
        pollCount = 0;
        pollHandler.post(pollOnce);
    }

    private final Runnable pollOnce = new Runnable() {
        @Override public void run() {
            if (token != null || webView == null) return;
            if (pollCount >= POLL_MAX) {
                statusText.setText("Токен не найден. Тапни play на треке.");
                return;
            }
            pollCount++;
            String js = "(function(){try{" +
                    "function find(o,d){if(d>6||!o||typeof o!=='object')return '';" +
                    "var ks;try{ks=Object.keys(o);}catch(e){return '';}" +
                    "for(var i=0;i<ks.length;i++){var v;try{v=o[ks[i]];}catch(e){continue;}" +
                    "if(typeof v==='string'&&v.indexOf('vk1.a.')===0&&v.length>60)return v;" +
                    "if(typeof v==='object'&&v!==null){var r=find(v,d+1);if(r)return r;}}return '';}" +
                    "if(window.vk){var a=find(window.vk,0);if(a)return a;}" +
                    "try{for(var i=0;i<localStorage.length;i++){" +
                    "var v=localStorage.getItem(localStorage.key(i))||'';" +
                    "var m=v.match(/vk1\\.a\\.[A-Za-z0-9_\\-\\.]{60,}/);if(m)return m[0];}}catch(e){}" +
                    "return '';}catch(e){return '';}})()";
            webView.evaluateJavascript(js, value -> {
                if (token != null) return;
                if (value == null) { pollHandler.postDelayed(pollOnce, POLL_INTERVAL_MS); return; }
                String t = value.replace("\"", "").trim();
                if (t.startsWith("vk1.a.") && t.length() > 60) { captureFromString(t, "poll#" + pollCount); return; }
                statusText.setText("Ищу токен... (" + pollCount + "/" + POLL_MAX + ")");
                pollHandler.postDelayed(pollOnce, POLL_INTERVAL_MS);
            });
        }
    };

    private synchronized void captureFromString(String s, String src) {
        if (token != null || s == null) return;
        Matcher m = TOK_RE.matcher(s);
        if (!m.find()) return;
        String t = m.group();
        Log.i(TAG, "captured from " + src + " len=" + t.length());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TOKEN, t).apply();
        token = t;
        pollHandler.removeCallbacksAndMessages(null);
        runOnUiThread(() -> { showTrackList(); loadTracks(); });
    }

    private void showTrackList() {
        content.removeAllViews();
        if (webView != null) { webView.destroy(); webView = null; }

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, view, pos, id) -> playTrack(tracks.get(pos)));
        content.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void loadTracks() {
        statusText.setText("Загрузка треков...");
        executor.execute(() -> {
            VKApi.ApiResult<List<VKApi.Audio>> r = VKApi.getAudio(token, 100, 0);
            runOnUiThread(() -> {
                if (r.error != null) { statusText.setText("Ошибка: " + r.error); return; }
                if (r.data == null) { statusText.setText("Пустой ответ"); return; }
                tracks.clear();
                tracks.addAll(r.data);
                if (adapter != null) {
                    adapter.clear();
                    for (VKApi.Audio a : tracks) adapter.add(a.displayName());
                }
                statusText.setText("Треков: " + tracks.size() + " (тапни для игры)");
            });
        });
    }

    private void playTrack(VKApi.Audio audio) {
        if (ipc == null) {
            Toast.makeText(this, "cmus не подключён", Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText("Готовлю: " + audio.displayName());
        executor.execute(() -> {
            try {
                String url = audio.url;
                if (url == null || url.isEmpty()) {
                    VKApi.ApiResult<String> r = VKApi.getAudioUrl(token, audio.ownerId, audio.id);
                    if (r.error != null) {
                        uiSetStatus("Ошибка URL: " + r.error);
                        return;
                    }
                    url = r.data;
                }
                if (url == null || url.isEmpty()) { uiSetStatus("URL пустой"); return; }

                File cacheDir = new File(getCacheDir(), "vk");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                final File out = new File(cacheDir, "vk_" + audio.ownerId + "_" + audio.id + ".mp3");

                if (!out.exists() || out.length() == 0) {
                    uiSetStatus("Скачиваю: " + audio.displayName());
                    download(url, out);
                }

                if (out.length() == 0) { uiSetStatus("Файл пустой после скачивания"); return; }

                final String path = out.getAbsolutePath();
                runOnUiThread(() -> {
                    ipc.send("view 1");
                    ipc.send("add " + path);
                    ipc.send("view queue");
                    ipc.send("player-play");
                    statusText.setText("Играю: " + audio.displayName());
                });
            } catch (Exception e) {
                Log.e(TAG, "playTrack failed", e);
                uiSetStatus("Ошибка: " + e.getMessage());
            }
        });
    }

    private void download(String urlStr, File out) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://vk.com/");
        c.setRequestProperty("Accept", "audio/*,*/*;q=0.1");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);

        int code = c.getResponseCode();
        String ctype = c.getContentType();
        String finalUrl = c.getURL().toString();
        Log.i(TAG, "download code=" + code + " ctype=" + ctype + " -> " + finalUrl);
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " (" + ctype + ")");

        try (InputStream in = c.getInputStream();
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }

        long len = out.length();
        if (len < 4096) {
            byte[] head = new byte[(int) Math.min(len, 300)];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(out)) {
                int rd = fis.read(head);
                if (rd < 0) rd = 0;
            }
            String preview = new String(head, "UTF-8").replaceAll("\\s+", " ");
            throw new Exception("Мало (" + len + "b, ctype=" + ctype + "): " + preview);
        }
    }

    private void uiSetStatus(String s) {
        runOnUiThread(() -> statusText.setText(s));
    }
}
