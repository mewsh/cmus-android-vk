package net.pgaskin.cmus.android;

import android.app.Activity;
import android.app.AlertDialog;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class VKMusicActivity extends Activity {
    private static final String TAG = "VKMusic";
    private static final String PREFS = "vk_music";
    private static final String KEY_TOKEN = "access_token";
    private static final Pattern TOK_RE = Pattern.compile("vk1\\.a\\.[A-Za-z0-9_\\-\\.]{60,}");
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://vk.com/";

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

        Button infoBtn = new Button(this);
        infoBtn.setText("Info");
        infoBtn.setOnClickListener(v -> showInfo());
        btns.addView(infoBtn);

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
                    if (r.error != null) { uiSetStatus("Ошибка URL: " + r.error); return; }
                    url = r.data;
                }
                if (url == null || url.isEmpty()) { uiSetStatus("URL пустой"); return; }

                File cacheDir = new File(getCacheDir(), "vk");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                final String base = "vk_" + audio.ownerId + "_" + audio.id;
                File mp3 = new File(cacheDir, base + ".mp3");
                File diag = new File(cacheDir, base + ".diag");

                File playFile;
                if (mp3.exists() && mp3.length() > 10000) {
                    playFile = mp3;
                } else {
                    if (mp3.exists()) mp3.delete();
                    if (diag.exists()) diag.delete();
                    uiSetStatus("Скачиваю: " + audio.displayName());
                    playFile = downloadAndBuildMp3(url, cacheDir, base, mp3, diag);
                }
                if (playFile == null || !playFile.exists() || playFile.length() < 4096) {
                    uiSetStatus("Файл не готов"); return;
                }
                final String path = playFile.getAbsolutePath();
                final String display = audio.displayName();
                final long sz = playFile.length() / 1024;
                runOnUiThread(() -> {
                    ipc.send("add -q " + path);
                    ipc.send("view queue");
                    ipc.send("player-play");
                    statusText.setText("Играю: " + display + " (" + sz + " КБ)");
                });
            } catch (Exception e) {
                Log.e(TAG, "playTrack failed", e);
                uiSetStatus("Ошибка: " + e.getMessage());
            }
        });
    }

    private File downloadAndBuildMp3(String urlStr, File cacheDir, String base, File outMp3, File diagFile) throws Exception {
        byte[] head = httpGetBytes(urlStr);
        String headStr = new String(head, "UTF-8");
        if (!headStr.startsWith("#EXTM3U")) {
            try (FileOutputStream fos = new FileOutputStream(outMp3)) {
                fos.write(head);
            }
            if (outMp3.length() < 4096) throw new Exception("Прямой mp3 слишком мал: " + outMp3.length());
            return outMp3;
        }

        String manifest = headStr;
        String baseUrl = urlStr;
        if (manifest.contains("#EXT-X-STREAM-INF")) {
            String media = null;
            String[] lines = manifest.split("\n");
            for (int i = 0; i < lines.length && media == null; i++) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    for (int j = i + 1; j < lines.length; j++) {
                        String l = lines[j].trim();
                        if (!l.isEmpty() && !l.startsWith("#")) { media = l; break; }
                    }
                }
            }
            if (media == null) throw new Exception("Master без media");
            String mediaUrl = new URL(new URL(baseUrl), media).toString();
            manifest = new String(httpGetBytes(mediaUrl), "UTF-8");
            baseUrl = mediaUrl;
        }

        Pattern keyRe = Pattern.compile("URI=\"([^\"]+)\"");
        Pattern ivRe = Pattern.compile("IV=0x([0-9A-Fa-f]+)");

        byte[] key = null;
        byte[] ivFixed = null;
        int mediaSeq = 0;
        List<String> segs = new ArrayList<>();

        for (String raw : manifest.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSeq = Integer.parseInt(line.substring(22).trim());
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Matcher km = keyRe.matcher(line);
                if (km.find()) {
                    key = httpGetBytes(km.group(1));
                    if (key.length != 16) throw new Exception("Ключ " + key.length);
                }
                Matcher im = ivRe.matcher(line);
                if (im.find()) ivFixed = hexToBytes(im.group(1));
            } else if (!line.startsWith("#")) {
                segs.add(line);
            }
        }
        if (segs.isEmpty()) throw new Exception("Сегментов нет");

        StringBuilder diagLog = new StringBuilder();
        diagLog.append("segments=").append(segs.size()).append(" encrypted=").append(key != null).append("\n");

        try (FileOutputStream fos = new FileOutputStream(outMp3)) {
            int seq = mediaSeq;
            long totalMp3 = 0;
            for (int i = 0; i < segs.size(); i++) {
                String segUrl = new URL(new URL(baseUrl), segs.get(i)).toString();
                byte[] data = httpGetBytes(segUrl);
                if (key != null) {
                    byte[] iv;
                    if (ivFixed != null) {
                        iv = ivFixed;
                    } else {
                        iv = new byte[16];
                        iv[12] = (byte) ((seq >> 24) & 0xff);
                        iv[13] = (byte) ((seq >> 16) & 0xff);
                        iv[14] = (byte) ((seq >> 8) & 0xff);
                        iv[15] = (byte) (seq & 0xff);
                    }
                    data = aes128CbcDecrypt(data, key, iv);
                }
                int added = extractMp3Frames(data, fos);
                totalMp3 += added;
                diagLog.append("seg").append(i).append(" in=").append(data.length)
                        .append(" mp3=").append(added).append("\n");
                seq++;
                final int cur = i + 1;
                if ((i & 1) == 0) uiSetStatus("HLS: " + cur + "/" + segs.size());
            }
            diagLog.append("total=").append(totalMp3).append("\n");
        }

        try (FileWriter fw = new FileWriter(diagFile)) {
            fw.write(diagLog.toString());
        } catch (Exception ignored) {}

        if (outMp3.length() < 10000) throw new Exception("MP3 мало: " + outMp3.length() + "\n" + diagLog);
        return outMp3;
    }

    /**
     * Сканирует сырые данные на MP3-кадры по синхрослову 0xFF Ex.
     * Для надёжности проверяет, что следующий кадр начинается ровно
     * через frame_size байт. Не зависит от TS-контейнера вообще.
     */
    private int extractMp3Frames(byte[] data, FileOutputStream out) throws Exception {
        int written = 0;
        int i = 0;
        int consecutiveFails = 0;
        while (i < data.length - 4) {
            int b0 = data[i] & 0xFF;
            int b1 = data[i + 1] & 0xFF;
            if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
                i++;
                consecutiveFails++;
                if (consecutiveFails > 100000 && written > 1000000) break;
                continue;
            }
            int versionBits = (b1 >> 3) & 0x03;
            int layerBits = (b1 >> 1) & 0x03;
            int b2 = data[i + 2] & 0xFF;
            int bitrateIdx = (b2 >> 4) & 0x0F;
            int srIdx = (b2 >> 2) & 0x03;
            int padding = (b2 >> 1) & 0x01;

            if (versionBits == 1 || layerBits == 0 || bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) {
                i++;
                consecutiveFails++;
                continue;
            }
            int frameSize = mp3FrameSize(versionBits, layerBits, bitrateIdx, srIdx, padding);
            if (frameSize <= 4 || i + frameSize > data.length) {
                i++;
                consecutiveFails++;
                continue;
            }
            int next = i + frameSize;
            if (next + 2 < data.length) {
                int nb0 = data[next] & 0xFF;
                int nb1 = data[next + 1] & 0xFF;
                if (nb0 != 0xFF || (nb1 & 0xE0) != 0xE0) {
                    i++;
                    consecutiveFails++;
                    continue;
                }
            }
            out.write(data, i, frameSize);
            written += frameSize;
            i += frameSize;
            consecutiveFails = 0;
        }
        return written;
    }

    private int mp3FrameSize(int version, int layer, int bitrateIdx, int srIdx, int padding) {
        int[][][] bitrateTables = {
            // MPEG 1
            {
                {0,32,64,96,128,160,192,224,256,288,320,352,384,416,448},
                {0,32,48,56,64,80,96,112,128,160,192,224,256,320,384},
                {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320}
            },
            // MPEG 2/2.5
            {
                {0,32,48,56,64,80,96,112,128,144,160,176,192,224,256},
                {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160},
                {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160}
            }
        };
        int[][][] sampleRateTables = {
            {44100, 48000, 32000},
            {22050, 24000, 16000},
            {11025, 12000, 8000}
        };
        int versionGroup = (version == 3) ? 0 : 1; // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
        int layerGroup;
        if (layer == 3) layerGroup = 0;      // Layer I
        else if (layer == 2) layerGroup = 1; // Layer II
        else layerGroup = 2;                 // Layer III

        int bitrate = bitrateTables[versionGroup][layerGroup][bitrateIdx];
        if (bitrate == 0) return -1;
        int sampleRate = sampleRateTables[versionGroup == 0 ? 0 : (version == 2 ? 1 : 2)][srIdx];
        if (sampleRate == 0) return -1;

        if (layerGroup == 0) {
            // Layer I
            return (12 * bitrate * 1000 / sampleRate + padding) * 4;
        } else {
            // Layer II/III
            int coef = (versionGroup == 0) ? 144 : 72;
            return coef * bitrate * 1000 / sampleRate + padding;
        }
    }

    private byte[] aes128CbcDecrypt(byte[] data, byte[] key, byte[] iv) throws Exception {
        int len = data.length - (data.length % 16);
        if (len <= 0) return new byte[0];
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data, 0, len);
    }

    private byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private byte[] httpGetBytes(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Accept", "*/*");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private void showInfo() {
        executor.execute(() -> {
            File dir = new File(getCacheDir(), "vk");
            File[] files = dir.listFiles();
            StringBuilder sb = new StringBuilder();
            sb.append("cache: ").append(dir.getAbsolutePath()).append("\n\n");
            if (files == null || files.length == 0) {
                sb.append("(empty)");
            } else {
                for (File f : files) {
                    sb.append(f.getName()).append("  ").append(f.length()).append(" b\n");
                }
                for (File f : files) {
                    if (f.getName().endsWith(".diag")) {
                        sb.append("\n-- ").append(f.getName()).append(" --\n");
                        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line).append("\n");
                        } catch (Exception ignored) {}
                    }
                }
            }
            String txt = sb.toString();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Info")
                    .setMessage(txt)
                    .setPositiveButton("OK", null)
                    .show());
        });
    }

    private void uiSetStatus(String s) {
        runOnUiThread(() -> statusText.setText(s));
    }
}
