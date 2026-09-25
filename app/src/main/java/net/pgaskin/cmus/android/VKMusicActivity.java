package net.pgaskin.cmus.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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

    private CmusService service;
    private CmusIpc ipc;
    private boolean bound;

    private VKWebClient webClient;

    private EditText cookiePField;
    private EditText cookieRemixsidField;
    private TextView statusText;

    private final List<VKApi.Audio> tracks = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CmusService.LocalBinder) binder).getService();
            ipc = service.getIpc();
            bound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false; ipc = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        statusText = new TextView(this);
        statusText.setText("Введите cookies 'p' и 'remixsid' из браузера (DevTools -> Application -> Cookies -> vk.com)");
        root.addView(statusText);

        cookiePField = new EditText(this);
        cookiePField.setHint("Cookie 'p'");
        root.addView(cookiePField);

        cookieRemixsidField = new EditText(this);
        cookieRemixsidField.setHint("Cookie 'remixsid'");
        root.addView(cookieRemixsidField);

        Button connectBtn = new Button(this);
        connectBtn.setText("Подключиться и загрузить треки");
        connectBtn.setOnClickListener(v -> connectAndLoad());
        root.addView(connectBtn);

        ListView listView = new ListView(this);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> playTrack(tracks.get(position)));

        setContentView(root);

        startForegroundService(new Intent(this, CmusService.class));
        bindService(new Intent(this, CmusService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) { unbindService(connection); bound = false; }
        executor.shutdown();
    }

    private void connectAndLoad() {
        String p = cookiePField.getText().toString().trim();
        String remixsid = cookieRemixsidField.getText().toString().trim();
        if (p.isEmpty() || remixsid.isEmpty()) {
            statusText.setText("Заполните оба поля");
            return;
        }
        webClient = new VKWebClient(p, remixsid);
        statusText.setText("Загрузка треков...");
        loadTracks();
    }

    private void loadTracks() {
        executor.execute(() -> {
            VKApi.ApiResult<List<VKApi.Audio>> r = VKApi.getAudio(webClient, 100, 0);
            runOnUiThread(() -> {
                if (r.error != null) { statusText.setText("Ошибка: " + r.error); return; }
                tracks.clear(); tracks.addAll(r.data);
                adapter.clear();
                for (VKApi.Audio a : tracks) adapter.add(a.displayName());
                statusText.setText("Треков: " + tracks.size());
            });
        });
    }

    private void playTrack(VKApi.Audio audio) {
        if (ipc == null) { Toast.makeText(this, "cmus не подключён", Toast.LENGTH_SHORT).show(); return; }
        statusText.setText("Играю: " + audio.displayName());
        executor.execute(() -> {
            String url = audio.url;
            if (url == null || url.isEmpty()) {
                VKApi.ApiResult<String> r = VKApi.getAudioUrl(webClient, audio.ownerId, audio.id);
                if (r.error != null) { runOnUiThread(() -> statusText.setText("Ошибка URL: " + r.error)); return; }
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
