package net.pgaskin.cmus.android;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class VKApi {
    private static final String TAG = "VKApi";
    private static final String API_VERSION = "5.131";
    private static final String UA = "KateMobileAndroid/56 lite-487 (Android 12; SDK 31; arm64-v8a; Xiaomi Redmi Note 8; ru)";

    public static class Audio {
        public int id;
        public int ownerId;
        public String artist;
        public String title;
        public int duration;
        public String url;

        public String displayName() {
            return artist + " \u2014 " + title;
        }
    }

    public static class ApiResult<T> {
        public T data;
        public String error;
    }

    public static ApiResult<List<Audio>> getAudio(String token, String userId, int count, int offset) {
        ApiResult<List<Audio>> result = new ApiResult<>();
        try {
            String urlStr = "https://api.vk.com/method/audio.get"
                    + "?owner_id=" + userId
                    + "&count=" + count
                    + "&offset=" + offset
                    + "&access_token=" + URLEncoder.encode(token, "UTF-8")
                    + "&v=" + API_VERSION;

            JSONObject json = getJson(urlStr);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").getString("error_msg");
                return result;
            }

            JSONArray items = json.getJSONObject("response").getJSONArray("items");
            List<Audio> audios = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                Audio audio = new Audio();
                audio.id = item.getInt("id");
                audio.ownerId = item.getInt("owner_id");
                audio.artist = item.optString("artist", "Unknown");
                audio.title = item.optString("title", "Unknown");
                audio.duration = item.optInt("duration", 0);
                audio.url = item.optString("url", "");
                audios.add(audio);
            }
            result.data = audios;
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getAudio failed", e);
            result.error = e.getMessage();
            return result;
        }
    }

    public static ApiResult<String> getAudioUrl(String token, int ownerId, int audioId) {
        ApiResult<String> result = new ApiResult<>();
        try {
            String urlStr = "https://api.vk.com/method/audio.getById"
                    + "?audios=" + ownerId + "_" + audioId
                    + "&access_token=" + URLEncoder.encode(token, "UTF-8")
                    + "&v=" + API_VERSION;

            JSONObject json = getJson(urlStr);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").getString("error_msg");
                return result;
            }

            JSONArray response = json.getJSONArray("response");
            if (response.length() > 0) {
                result.data = response.getJSONObject(0).optString("url", "");
            } else {
                result.error = "\u041f\u0443\u0441\u0442\u043e\u0439 \u043e\u0442\u0432\u0435\u0442";
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getAudioUrl failed", e);
            result.error = e.getMessage();
            return result;
        }
    }

    private static JSONObject getJson(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", UA);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return new JSONObject(sb.toString());
    }
}
