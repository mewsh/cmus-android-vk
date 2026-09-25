package net.pgaskin.cmus.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
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
                File ts  = new File(cacheDir, base + ".ts");
                File wav = new File(cacheDir, base + ".wav");

                File playFile;
                if (wav.exists() && wav.length() > 40000) {
                    playFile = wav;
                } else if (ts.exists() && ts.length() > 10000) {
                    uiSetStatus("Декодирую .ts -> .wav...");
                    tsToWav(ts, wav);
                    playFile = wav;
                } else if (mp3.exists() && mp3.length() > 10000) {
                    playFile = mp3;
                } else {
                    if (mp3.exists()) mp3.delete();
                    if (ts.exists()) ts.delete();
                    if (wav.exists()) wav.delete();
                    uiSetStatus("Скачиваю: " + audio.displayName());
                    File dl = downloadAndPrepare(url, cacheDir, base);
                    if (dl.getName().endsWith(".ts")) {
                        uiSetStatus("Декодирую .ts -> .wav...");
                        tsToWav(dl, wav);
                        playFile = wav;
                    } else {
                        playFile = dl;
                    }
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

    private File downloadAndPrepare(String urlStr, File cacheDir, String base) throws Exception {
        byte[] head = httpGetBytes(urlStr);
        String headStr = new String(head, "UTF-8");
        if (headStr.startsWith("#EXTM3U")) {
            File out = new File(cacheDir, base + ".ts");
            downloadHlsFromManifest(urlStr, headStr, out);
            return out;
        } else {
            File out = new File(cacheDir, base + ".mp3");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(head);
            }
            if (out.length() < 4096) {
                String preview = new String(head, 0, Math.min(head.length, 300), "UTF-8").replaceAll("\\s+", " ");
                throw new Exception("Мало (" + out.length() + "b): " + preview);
            }
            return out;
        }
    }

    private void downloadHlsFromManifest(String baseUrl, String manifest, File out) throws Exception {
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

        uiSetStatus("HLS: сегментов " + segs.size());

        try (FileOutputStream fos = new FileOutputStream(out)) {
            int seq = mediaSeq;
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
                fos.write(data);
                seq++;
                if ((i & 3) == 0) uiSetStatus("HLS: " + (i + 1) + "/" + segs.size());
            }
        }
        if (out.length() < 10000) throw new Exception("Склеено мало: " + out.length());
        Log.i(TAG, "HLS done: " + out.length() + " bytes");
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

    private void tsToWav(File tsFile, File wavFile) throws Exception {
        Log.i(TAG, "tsToWav: in=" + tsFile.length() + " bytes");
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(tsFile.getAbsolutePath());
        int audioTrack = -1;
        MediaFormat inFmt = null;
        StringBuilder tracksLog = new StringBuilder();
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            tracksLog.append("[").append(i).append(":").append(mime).append("]");
            if (mime != null && mime.startsWith("audio/") && audioTrack < 0) {
                audioTrack = i;
                inFmt = f;
            }
        }
        Log.i(TAG, "tracks=" + tracksLog);
        if (audioTrack < 0) { ex.release(); throw new Exception("audio трек не найден"); }
        ex.selectTrack(audioTrack);

        String mime = inFmt.getString(MediaFormat.KEY_MIME);
        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        dec.configure(inFmt, null, null, 0);
        dec.start();

        int sampleRate = 44100, channels = 2, pcmEncoding = 2;
        if (inFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        if (wavFile.exists()) wavFile.delete();
        FileOutputStream out = new FileOutputStream(wavFile);
        out.write(new byte[44]);

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, outputDone = false;
        long totalPcm = 0;
        int loops = 0, readSamples = 0, decodedFrames = 0;

        while (!outputDone && loops < 500000) {
            loops++;
            if (!inputDone) {
                int inIdx = dec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = dec.getInputBuffer(inIdx);
                    inBuf.clear();
                    int sz = ex.readSampleData(inBuf, 0);
                    if (sz < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        dec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                        readSamples++;
                        ex.advance();
                    }
                }
            }
            int outIdx = dec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                ByteBuffer outBuf = dec.getOutputBuffer(outIdx);
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    byte[] data = new byte[info.size];
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    outBuf.get(data);
                    out.write(data);
                    totalPcm += data.length;
                    decodedFrames++;
                }
                dec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = dec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                Log.i(TAG, "outFmt: rate=" + sampleRate + " ch=" + channels + " pcmEnc=" + pcmEncoding);
            }
            if ((loops & 0x3F) == 0) {
                final long tp = totalPcm;
                runOnUiThread(() -> statusText.setText("Декодирую... " + (tp / 1024) + " КБ"));
            }
        }
        try { dec.stop(); } catch (Exception ignored) {}
        dec.release();
        ex.release();
        out.close();

        int bitsPerSample = (pcmEncoding == 4) ? 32 : 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int fmtTag = (pcmEncoding == 4) ? 3 : 1;

        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wavFile, "rw");
        raf.seek(0);
        raf.write("RIFF".getBytes("US-ASCII"));
        writeIntLE(raf, (int) (36 + totalPcm));
        raf.write("WAVE".getBytes("US-ASCII"));
        raf.write("fmt ".getBytes("US-ASCII"));
        writeIntLE(raf, 16);
        writeShortLE(raf, (short) fmtTag);
        writeShortLE(raf, (short) channels);
        writeIntLE(raf, sampleRate);
        writeIntLE(raf, byteRate);
        writeShortLE(raf, (short) blockAlign);
        writeShortLE(raf, (short) bitsPerSample);
        raf.write("data".getBytes("US-ASCII"));
        writeIntLE(raf, (int) totalPcm);
        raf.close();

        long durMs = (byteRate > 0) ? (totalPcm * 1000L / byteRate) : 0;

        try {
            File diag = new File(wavFile.getParentFile(), wavFile.getName() + ".txt");
            java.io.FileWriter fw = new java.io.FileWriter(diag);
            fw.write("tsFile=" + tsFile.length() + "\n");
            fw.write("tracks=" + tracksLog + "\n");
            fw.write("rate=" + sampleRate + " ch=" + channels + " bits=" + bitsPerSample + " pcmEnc=" + pcmEncoding + "\n");
            fw.write("readSamples=" + readSamples + " decodedFrames=" + decodedFrames + "\n");
            fw.write("totalPcm=" + totalPcm + " durMs=" + durMs + "\n");
            fw.close();
        } catch (Exception ignored) {}

        Log.i(TAG, "wav done: readSamples=" + readSamples + " frames=" + decodedFrames + " pcm=" + totalPcm + " durMs=" + durMs);
        if (totalPcm < 10000) throw new Exception("PCM мало: " + totalPcm);
    }

    private void writeIntLE(java.io.RandomAccessFile r, int v) throws Exception {
        r.write(v & 0xff); r.write((v >> 8) & 0xff);
        r.write((v >> 16) & 0xff); r.write((v >> 24) & 0xff);
    }
    private void writeShortLE(java.io.RandomAccessFile r, short v) throws Exception {
        r.write(v & 0xff); r.write((v >> 8) & 0xff);
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
                    if (f.getName().endsWith(".wav.txt")) {
                        sb.append("\n-- ").append(f.getName()).append(" --\n");
                        try {
                            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line).append("\n");
                            br.close();
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
