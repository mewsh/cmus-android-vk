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
import java.io.FileOutputStream;
import java.io.FileWriter;
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
    private static final String KEY_FORCE = "force_rebuild";
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
        clearBtn.setText("Очистить");
        clearBtn.setOnClickListener(v -> clearCache());
        btns.addView(clearBtn);

        Button reprocessBtn = new Button(this);
        reprocessBtn.setText("Reprocess");
        reprocessBtn.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FORCE, true).apply();
            Toast.makeText(this, "Флаг пересборки, тапни трек", Toast.LENGTH_LONG).show();
            statusText.setText("Пересборка: тапни трек");
        });
        btns.addView(reprocessBtn);

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

    private void clearCache() {
        executor.execute(() -> {
            File dir = new File(getCacheDir(), "vk");
            File[] files = dir.listFiles();
            int n = 0;
            if (files != null) for (File f : files) if (f.delete()) n++;
            final int nn = n;
            runOnUiThread(() -> Toast.makeText(this, "Удалено: " + nn, Toast.LENGTH_SHORT).show());
        });
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
                    if (url != null && url.contains("vk1.a.")) captureFromString(url);
                    Map<String, String> h = request.getRequestHeaders();
                    if (h != null) for (String k : h.keySet()) {
                        String v = h.get(k);
                        if (v != null && v.contains("vk1.a.")) captureFromString(v);
                    }
                } catch (Exception e) { Log.e(TAG, "intercept", e); }
                return super.shouldInterceptRequest(view, request);
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null && url.contains("vk1.a.")) captureFromString(url);
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
                statusText.setText("Токен не найден");
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
                if (t.startsWith("vk1.a.") && t.length() > 60) { captureFromString(t); return; }
                statusText.setText("Ищу токен... (" + pollCount + "/" + POLL_MAX + ")");
                pollHandler.postDelayed(pollOnce, POLL_INTERVAL_MS);
            });
        }
    };

    private synchronized void captureFromString(String s) {
        if (token != null || s == null) return;
        Matcher m = TOK_RE.matcher(s);
        if (!m.find()) return;
        String t = m.group();
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
                statusText.setText("Треков: " + tracks.size());
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

                boolean force = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FORCE, false);
                String base = "vk_" + audio.ownerId + "_" + audio.id
                        + (force ? ("_" + System.currentTimeMillis()) : "");

                File wav = new File(cacheDir, base + ".wav");
                File diag = new File(cacheDir, base + ".diag");

                File playFile;
                if (!force && wav.exists() && wav.length() > 40000) {
                    playFile = wav;
                } else {
                    uiSetStatus("Скачиваю: " + audio.displayName());
                    playFile = downloadAndBuildWav(url, cacheDir, wav, diag);
                    if (force) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FORCE, false).apply();
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

    private File downloadAndBuildWav(String urlStr, File cacheDir, File outWav, File diagFile) throws Exception {
        byte[] head = httpGetBytes(urlStr);
        String headStr = new String(head, "UTF-8");
        if (!headStr.startsWith("#EXTM3U")) {
            throw new Exception("Не HLS: " + headStr.substring(0, Math.min(80, headStr.length())));
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

        StringBuilder diag = new StringBuilder();
        diag.append("segments=").append(segs.size())
                .append(" enc=").append(key != null)
                .append(" mediaSeq=").append(mediaSeq)
                .append(" ivFixed=").append(ivFixed != null ? hex16(ivFixed, 0, 16) : "null")
                .append("\n");

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int sampleRate = 44100, channels = 2, bits = 16;
        boolean fmtSet = false;

        for (int i = 0; i < segs.size(); i++) {
            String segUrl = new URL(new URL(baseUrl), segs.get(i)).toString();
            byte[] data = httpGetBytes(segUrl);
            diag.append("raw[").append(i).append("]=").append(hex16(data, 0, 8)).append("\n");

            if (key != null) {
                data = decryptSegment(data, key, ivFixed, mediaSeq, i, diag, i);
            }
            diag.append("dec[").append(i).append("]=").append(hex16(data, 0, 8)).append("\n");

            File tmp = new File(cacheDir, "seg_" + i + ".ts");
            try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(data); }

            long before = pcm.size();
            try {
                int[] fmtOut = new int[3];
                byte[] segPcm = decodeTsToPcm(tmp, fmtOut);
                if (segPcm != null && segPcm.length > 0) {
                    pcm.write(segPcm);
                    if (!fmtSet && fmtOut[0] > 0) {
                        sampleRate = fmtOut[0];
                        channels = fmtOut[1];
                        bits = fmtOut[2];
                        fmtSet = true;
                    }
                }
            } catch (Exception ex) {
                diag.append("  ERR[").append(i).append("]=").append(ex.getMessage()).append("\n");
            }
            tmp.delete();
            long added = pcm.size() - before;
            diag.append("seg").append(i).append(" in=").append(data.length)
                    .append(" pcm=").append(added).append("\n");

            final int cur = i + 1;
            if ((i & 1) == 0) uiSetStatus("HLS: " + cur + "/" + segs.size() + " (pcm " + (pcm.size()/1024) + " КБ)");
        }

        byte[] pcmBytes = pcm.toByteArray();
        diag.append("pcmTotal=").append(pcmBytes.length)
                .append(" rate=").append(sampleRate)
                .append(" ch=").append(channels)
                .append(" bits=").append(bits).append("\n");

        try (FileOutputStream fo = new FileOutputStream(outWav)) {
            writeWavHeader(fo, pcmBytes.length, sampleRate, channels, bits);
            fo.write(pcmBytes);
        }

        try (FileWriter fw = new FileWriter(diagFile)) { fw.write(diag.toString()); }
        catch (Exception ignored) {}

        if (outWav.length() < 40000) throw new Exception("WAV мало: " + outWav.length());
        return outWav;
    }

    private byte[] decryptSegment(byte[] data, byte[] key, byte[] ivFixed, int seqBase, int segIdx,
                                   StringBuilder diag, int idx) throws Exception {
        if (key == null || data == null || data.length < 16) return data;

        List<byte[]> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (ivFixed != null) { candidates.add(ivFixed); names.add("fixed"); }
        candidates.add(seqToIv(seqBase + segIdx)); names.add("base+i");
        candidates.add(seqToIv(segIdx)); names.add("i");
        candidates.add(seqToIv(seqBase)); names.add("base");
        candidates.add(new byte[16]); names.add("zero");

        byte[] firstBlock = new byte[16];
        System.arraycopy(data, 0, firstBlock, 0, 16);

        for (int j = 0; j < candidates.size(); j++) {
            byte[] iv = candidates.get(j);
            try {
                byte[] dec1 = aes128CbcDecrypt(firstBlock, key, iv);
                String h = hex16(dec1, 0, 4);
                boolean ok = isPlausibleHeader(dec1);
                diag.append("  iv").append(idx).append("[").append(names.get(j)).append("]=")
                        .append(h).append(ok ? " OK" : "").append("\n");
                if (ok) {
                    return aes128CbcDecrypt(data, key, iv);
                }
            } catch (Exception ex) {
                diag.append("  iv").append(idx).append("[").append(names.get(j)).append("] EX=")
                        .append(ex.getMessage()).append("\n");
            }
        }
        diag.append("  iv").append(idx).append(" NO_MATCH\n");
        return aes128CbcDecrypt(data, key, candidates.get(0));
    }

    private boolean isPlausibleHeader(byte[] b) {
        if (b == null || b.length < 4) return false;
        int u0 = b[0] & 0xFF;
        int u1 = b[1] & 0xFF;
        int u2 = b[2] & 0xFF;
        int u3 = b[3] & 0xFF;
        if (u0 == 0x47) return true;
        if (u0 == 0x49 && u1 == 0x44 && u2 == 0x33) return true;
        if (u0 == 0xFF && (u1 & 0xE0) == 0xE0) return true;
        if (u0 == 0x66 && u1 == 0x74 && u2 == 0x79 && u3 == 0x70) return true;
        return false;
    }

    private byte[] seqToIv(int seq) {
        byte[] iv = new byte[16];
        iv[12] = (byte)((seq >> 24) & 0xff);
        iv[13] = (byte)((seq >> 16) & 0xff);
        iv[14] = (byte)((seq >> 8) & 0xff);
        iv[15] = (byte)(seq & 0xff);
        return iv;
    }

    private byte[] decodeTsToPcm(File ts, int[] outFmt) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ts.getAbsolutePath());
        int audioTrack = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                fmt = f;
                break;
            }
        }
        if (audioTrack < 0) { ex.release(); throw new Exception("no audio"); }
        ex.selectTrack(audioTrack);

        String mime = fmt.getString(MediaFormat.KEY_MIME);
        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        dec.configure(fmt, null, null, 0);
        dec.start();

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, outputDone = false;
        int loops = 0;
        int gotRate = 0, gotCh = 0, gotBits = 16;

        while (!outputDone && loops < 200000) {
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
                        ex.advance();
                    }
                }
            }
            int outIdx = dec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                ByteBuffer outBuf = dec.getOutputBuffer(outIdx);
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    byte[] chunk = new byte[info.size];
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    outBuf.get(chunk);
                    pcm.write(chunk);
                }
                dec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = dec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) gotRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) gotCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    int enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    gotBits = (enc == 4) ? 32 : 16;
                }
            }
        }
        try { dec.stop(); } catch (Exception ignored) {}
        dec.release();
        ex.release();

        outFmt[0] = gotRate;
        outFmt[1] = gotCh;
        outFmt[2] = gotBits;
        return pcm.toByteArray();
    }

    private void writeWavHeader(FileOutputStream fo, int dataLen, int rate, int ch, int bits) throws Exception {
        int byteRate = rate * ch * bits / 8;
        int blockAlign = ch * bits / 8;
        int fmtTag = (bits == 32) ? 3 : 1;

        fo.write("RIFF".getBytes("US-ASCII"));
        writeIntLE(fo, 36 + dataLen);
        fo.write("WAVE".getBytes("US-ASCII"));
        fo.write("fmt ".getBytes("US-ASCII"));
        writeIntLE(fo, 16);
        writeShortLE(fo, (short) fmtTag);
        writeShortLE(fo, (short) ch);
        writeIntLE(fo, rate);
        writeIntLE(fo, byteRate);
        writeShortLE(fo, (short) blockAlign);
        writeShortLE(fo, (short) bits);
        fo.write("data".getBytes("US-ASCII"));
        writeIntLE(fo, dataLen);
    }

    private void writeIntLE(FileOutputStream fo, int v) throws Exception {
        fo.write(v & 0xff); fo.write((v >> 8) & 0xff);
        fo.write((v >> 16) & 0xff); fo.write((v >> 24) & 0xff);
    }
    private void writeShortLE(FileOutputStream fo, short v) throws Exception {
        fo.write(v & 0xff); fo.write((v >> 8) & 0xff);
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

    private String hex16(byte[] data, int off, int len) {
        if (data == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && off + i < data.length; i++) {
            sb.append(String.format("%02x", data[off + i]));
        }
        return sb.toString();
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
