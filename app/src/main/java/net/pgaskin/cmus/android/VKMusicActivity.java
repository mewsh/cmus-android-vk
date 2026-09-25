package net.pgaskin.cmus.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
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
import android.widget.ScrollView;
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

    private String token;
    private String userId;

    private EditText urlField;
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
            Log.d(TAG, "service connected, ipc=" + ipc);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            ipc = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        statusText = new TextView(this);
        statusText.setText(
                "1. Нажмите «Открыть vkhost.github.io»\n" +
                "2. Выберите Kate Mobile и нажмите «Разрешить»\n" +
                "3. Скопируйте адрес из строки браузера\n" +
                "4. Вставьте его в поле ниже и нажмите «Извлечь токен»");
        root.addView(statusText);

        Button openSite = new Button(this);
        openSite.setText("Открыть vkhost.github.io");
        openSite.setOnClickListener(v -> {
            Intent browser = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://vkhost.github.io/"));
            startActivity(browser);
        });
        root.addView(openSite);

        urlField = new EditText(this);
        urlField.setHint("Вставьте сюда URL с токеном");
        urlField.setSingleLine(false);
        urlField.setMinLines(2);
        root.addView(urlField);

        Button parseBtn = new Button(this);
        parseBtn.setText("Извлечь токен");
        parseBtn.setOnClickListener(v -> parseToken());
        root.addView(parseBtn);

        ListView listView = new ListView(this);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                playTrack(tracks.get(position)));

        setContentView(root);

        startForegroundService(new Intent(this, CmusService.class));
        bindService(new Intent(this, CmusService.class), connection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        executor.shutdown();
    }

    private void parseToken() {
        String input = urlField.getText().toString().trim();
        if (input.isEmpty()) {
            statusText.setText("Вставьте URL из адресной строки браузера");
            return;
        }

        // Токен лежит во фрагменте URL после #: #access_token=...&user_id=...
        String accessToken = null;
        String uid = null;
        int hashIdx = input.indexOf('#');
        String fragment = hashIdx >= 0 ? input.substring(hashIdx + 1) : input;

        for (String pair : fragment.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if ("access_token".equals(key)) {
                accessToken = value;
            } else if ("user_id".equals(key)) {
                uid = value;
            }
        }

        if (accessToken == null || accessToken.isEmpty()) {
            statusText.setText("Токен не найден в URL. Убедитесь, что скопировали всю строку.");
            return;
        }

        token = accessToken;
        userId = uid != null ? uid : "0";
        statusText.setText("Токен получен. Загрузка треков...");
        loadTracks();
    }

    private void loadTracks() {
        executor.execute(() -> {
            VKApi.ApiResult<List<VKApi.Audio>> r =
                    VKApi.getAudio(token, userId, 100, 0);
            runOnUiThread(() -> {
                if (r.error != null) {
                    statusText.setText("Ошибка загрузки: " + r.error);
                    return;
                }
                tracks.clear();
                tracks.addAll(r.data);
                adapter.clear();
                for (VKApi.Audio a : tracks) {
                    adapter.add(a.displayName());
                }
                statusText.setText("Треков: " + tracks.size() +
                        "\nНажмите на трек, чтобы отправить в cmus");
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
            String url = audio.url;
            if (url == null || url.isEmpty()) {
                VKApi.ApiResult<String> r =
                        VKApi.getAudioUrl(token, audio.ownerId, audio.id);
                if (r.error != null) {
                    runOnUiThread(() ->
                            statusText.setText("Ошибка URL: " + r.error));
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
