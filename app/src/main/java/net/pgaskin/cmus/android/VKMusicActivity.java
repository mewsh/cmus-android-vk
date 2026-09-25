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

    private String token;
    private String userId;
    private String pendingSid;

    private EditText loginField;
    private EditText passwordField;
    private EditText codeField;
    private Button validateButton;
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
        statusText.setText("\u0412\u0432\u0435\u0434\u0438\u0442\u0435 \u043b\u043e\u0433\u0438\u043d \u0438 \u043f\u0430\u0440\u043e\u043b\u044c VK");
        root.addView(statusText);

        loginField = new EditText(this);
        loginField.setHint("\u041b\u043e\u0433\u0438\u043d (\u0442\u0435\u043b\u0435\u0444\u043e\u043d/email)");
        root.addView(loginField);

        passwordField = new EditText(this);
        passwordField.setHint("\u041f\u0430\u0440\u043e\u043b\u044c");
        passwordField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passwordField);

        Button loginButton = new Button(this);
        loginButton.setText("\u0412\u043e\u0439\u0442\u0438");
        loginButton.setOnClickListener(v -> doLogin());
        root.addView(loginButton);

        codeField = new EditText(this);
        codeField.setHint("\u041a\u043e\u0434 2FA (\u0435\u0441\u043b\u0438 \u0442\u0440\u0435\u0431\u0443\u0435\u0442\u0441\u044f)");
        codeField.setVisibility(View.GONE);
        root.addView(codeField);

        validateButton = new Button(this);
        validateButton.setText("\u041f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u0442\u044c \u043a\u043e\u0434");
        validateButton.setVisibility(View.GONE);
        validateButton.setOnClickListener(v -> doValidate());
        root.addView(validateButton);

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
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        executor.shutdown();
    }

    private void doLogin() {
        final String login = loginField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        if (login.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "\u0417\u0430\u043f\u043e\u043b\u043d\u0438\u0442\u0435 \u043b\u043e\u0433\u0438\u043d \u0438 \u043f\u0430\u0440\u043e\u043b\u044c", Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText("\u0412\u0445\u043e\u0434...");
        executor.execute(() -> {
            VKAuth.Result r = VKAuth.login(login, password);
            runOnUiThread(() -> handleAuth(r, login, password));
        });
    }

    private void doValidate() {
        final String code = codeField.getText().toString().trim();
        final String login = loginField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        if (code.isEmpty()) {
            Toast.makeText(this, "\u0412\u0432\u0435\u0434\u0438\u0442\u0435 \u043a\u043e\u0434", Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText("\u041f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 \u043a\u043e\u0434\u0430...");
        executor.execute(() -> {
            VKAuth.Result r = VKAuth.validate(pendingSid, code, login, password);
            runOnUiThread(() -> handleAuth(r, login, password));
        });
    }

    private void handleAuth(VKAuth.Result r, String login, String password) {
        if (r.needValidation) {
            pendingSid = r.validationSid;
            statusText.setText("\u0422\u0440\u0435\u0431\u0443\u0435\u0442\u0441\u044f \u043a\u043e\u0434. \u041e\u0442\u043f\u0440\u0430\u0432\u043b\u0435\u043d \u043d\u0430 " + r.phoneMask);
            codeField.setVisibility(View.VISIBLE);
            validateButton.setVisibility(View.VISIBLE);
            return;
        }
        if (r.error != null) {
            statusText.setText("\u041e\u0448\u0438\u0431\u043a\u0430: " + r.error);
            return;
        }
        token = r.token;
        userId = r.userId;
        statusText.setText("\u0412\u043e\u0448\u043b\u0438 \u043a\u0430\u043a " + userId);
        loadTracks();
    }

    private void loadTracks() {
        statusText.setText("\u0417\u0430\u0433\u0440\u0443\u0437\u043a\u0430 \u0442\u0440\u0435\u043a\u043e\u0432...");
        executor.execute(() -> {
            VKApi.ApiResult<List<VKApi.Audio>> r = VKApi.getAudio(token, userId, 100, 0);
            runOnUiThread(() -> {
                if (r.error != null) {
                    statusText.setText("\u041e\u0448\u0438\u0431\u043a\u0430 \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0438: " + r.error);
                    return;
                }
                tracks.clear();
                tracks.addAll(r.data);
                adapter.clear();
                for (VKApi.Audio a : tracks) adapter.add(a.displayName());
                statusText.setText("\u0422\u0440\u0435\u043a\u043e\u0432: " + tracks.size());
            });
        });
    }

    private void playTrack(VKApi.Audio audio) {
        if (ipc == null) {
            Toast.makeText(this, "cmus \u043d\u0435 \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0451\u043d", Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText("\u0418\u0433\u0440\u0430\u044e: " + audio.displayName());
        executor.execute(() -> {
            String url = audio.url;
            if (url == null || url.isEmpty()) {
                VKApi.ApiResult<String> r = VKApi.getAudioUrl(token, audio.ownerId, audio.id);
                if (r.error != null) {
                    runOnUiThread(() -> statusText.setText("\u041e\u0448\u0438\u0431\u043a\u0430 URL: " + r.error));
                    return;
                }
                url = r.data;
            }
            final String finalUrl = url;
            runOnUiThread(() -> {
                ipc.send("add " + finalUrl);
                ipc.send("view queue");
                ipc.send("player-play");
                Toast.makeText(this, "\u0414\u043e\u0431\u0430\u0432\u043b\u0435\u043d\u043e \u0432 cmus", Toast.LENGTH_SHORT).show();
            });
        });
    }
}
