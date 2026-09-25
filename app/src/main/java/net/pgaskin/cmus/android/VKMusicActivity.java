package net.pgaskin.cmus.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VKMusicActivity extends Activity {
    private static final String TAG = "VKMusic";
    private static final String PREFS = "vk_music";
    private static final String KEY_TOKEN = "access_token";

    private CmusService service;
    private CmusIpc ipc;
    private boolean bound;

    private String token;
    private TextView statusText;
    private FrameLayout content;
    private WebView webView;

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
        root.setPadding(24, 24, 24, 24);

        statusText = new TextView(this);
        statusText.setText("Инициализация...");
        root.addView(statusText);

        Button logoutBtn = new Button(this);
        logoutBtn.setText("Выйти / сменить аккаунт");
        logoutBtn.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_TOKEN).apply();
            token = null;
            recreate();
        });
        root.addView(logoutBtn);

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
        if (webView != null) { webView.destroy(); webView = null; }
        if (bound) { unbindService(connection); bound = false; }
        executor.shutdown();
    }

    private void showLogin() {
        content.removeAllViews();
        statusText.setText("Залогинься в VK. Токен перехватится автоматически.");

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String url = request.getUrl().toString();
                    tryCapture(url);
                } catch (Exception e) {
                    Log.e(TAG, "intercept failed", e);
                }
                return super.shouldInterceptRequest(view, request);
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                try {
                    tryCapture(url);
                } catch (Exception e) {
                    Log.e(TAG, "intercept failed", e);
                }
                return super.shouldInterceptRequest(view, url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                tryJavascriptFallback(view);
            }
        });
        webView.loadUrl("https://vk.com/audio");
        content.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private synchronized void tryCapture(String url) {
        if (token != null) return;
        if (url == null) return;
        if (url.indexOf("access_token=vk1.") < 0) return;
        try {
            Uri u = Uri.parse(url);
            String t = u.getQueryParameter("access_token");
            if (t == null) return;
            t = t.trim();
            if (!t.startsWith("vk1.") || t.length() < 60) return;
            Log.i(TAG, "token captured from network, length=" + t.length());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TOKEN, t).apply();
            token = t;
            runOnUiThread(() -> {
                showTrackList();
                loadTracks();
            });
        } catch (Exception e) {
            Log.e(TAG, "tryCapture failed", e);
        }
    }

    private void tryJavascriptFallback(WebView view) {
        if (token != null) return;
        String js = "(function(){try{var s='';try{s+=JSON.stringify(Object.keys(window.vk||{}));}catch(e){s+='vk:'+e.message;}" +
                "var wt=window.vk&&window.vk.webToken;" +
                "if(wt){if(typeof wt==='string')return 'WT_STR:'+wt.slice(0,40);" +
                "if(wt.access_token)return 'WT:'+wt.access_token.slice(0,40);}" +
                "return 'KEYS:'+s;}catch(e){return 'ERR:'+e.message;}})()";
        view.evaluateJavascript(js, value -> {
            if (value != null && token == null) {
                statusText.setText("JS debug: " + value);
            }
        });
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
                if (r.error != null) {
                    statusText.setText("Ошибка: " + r.error);
                    return;
                }
                if (r.data == null) {
                    statusText.setText("Пустой ответ");
                    return;
                }
                tracks.clear();
                tracks.addAll(r.data);
                adapter.clear();
                for (VKApi.Audio a : tracks) adapter.add(a.displayName());
                statusText.setText("Треков: " + tracks.size());
            });
        });
    }

    private void playTrack(VKApi.Audio audio) {
        if (ipc == null) {
            Toast.makeText(this, "cmus не подключён", Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText("Играю: " + audio.displayName());
        executor.execute(() -> {
            String url = audio.url;
            if (url == null || url.isEmpty()) {
                VKApi.ApiResult<String> r = VKApi.getAudioUrl(token, audio.ownerId, audio.id);
                if (r.error != null) {
                    runOnUiThread(() -> statusText.setText("Ошибка URL: " + r.error));
                    return;
                }
                url = r.data;
            }
            final String finalUrl = url;
            runOnUiThread(() -> {
                ipc.send("add " + finalUrl);
                ipc.send("view queue");
                ipc.send("player-play");
                Toast.makeText(this, "Добавлено в cmus", Toast.LENGTH_SHORT).show();
            });
        });
    }
}
