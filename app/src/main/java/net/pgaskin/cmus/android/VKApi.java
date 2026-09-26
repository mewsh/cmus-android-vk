package net.pgaskin.cmus.android;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class VKApi {
    private static final String TAG = "VKApi";
    private static final String API_VERSION = "5.131";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static class Playlist {
        public int id;
        public int ownerId;
        public String title;
        public int size;
        public String photo;
    }

    public static class Audio {
        public int id;
        public int ownerId;
        public String artist;
        public String title;
        public int duration;
        public String url;

        public String displayName() {
            return artist + " — " + title;
        }
    }

    public static class ApiResult<T> {
        public T data;
        public String error;
    }

    public static ApiResult<Integer> getUserId(String token) {
        ApiResult<Integer> result = new ApiResult<>();
        try {
            String url = "https://api.vk.com/method/users.get"
                    + "?access_token=" + URLEncoder.encode(token, "UTF-8")
                    + "&v=" + API_VERSION;
            JSONObject json = httpGet(url);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").optString("error_msg", "unknown");
                return result;
            }
            JSONArray arr = json.optJSONArray("response");
            if (arr == null || arr.length() == 0) {
                result.error = "Пустой users.get";
                return result;
            }
            result.data = arr.getJSONObject(0).optInt("id");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getUserId failed", e);
            result.error = e.getMessage();
            return result;
        }
    }

    public static ApiResult<List<Playlist>> getPlaylistsWithOwner(String token, int ownerId) {
        ApiResult<List<Playlist>> result = new ApiResult<>();
        try {
            String url = "https://api.vk.com/method/audio.getPlaylists"
                    + "?access_token=" + URLEncoder.encode(token, "UTF-8")
                    + "&v=" + API_VERSION
                    + "&owner_id=" + ownerId
                    + "&count=100";
            JSONObject json = httpGet(url);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").optString("error_msg", "unknown");
                return result;
            }
            Object respObj = json.opt("response");
            JSONArray items = null;
            if (respObj instanceof JSONObject) items = ((JSONObject) respObj).optJSONArray("items");
            else if (respObj instanceof JSONArray) items = (JSONArray) respObj;

            List<Playlist> out = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    Playlist p = new Playlist();
                    p.id = it.optInt("id");
                    p.ownerId = it.optInt("owner_id", ownerId);
                    p.title = it.optString("title", "?");
                    p.size = it.optInt("size", 0);
                    p.photo = it.optString("photo_300", it.optString("photo_600", ""));
                    out.add(p);
                }
            }
            result.data = out;
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getPlaylistsWithOwner failed", e);
            result.error = e.getMessage();
            return result;
        }
    }

    public static ApiResult<List<Playlist>> getPlaylists(String token) {
        ApiResult<List<Playlist>> result = new ApiResult<>();
        try {
            String url = "https://api.vk.com/method/audio.getPlaylists"
                    + "?access_token=" + URLEncoder.encode(token, "UTF-8")
                    + "&v=" + API_VERSION
                    + "&count=100";
            JSONObject json = httpGet(url);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").optString("error_msg", "unknown");
                return result;
            }
            if (!json.has("response")) {
                result.error = "Нет response: " + json.toString();
                return result;
            }
            Object respObj = json.get("response");
            JSONArray items = null;
            if (respObj instanceof JSONObject) items = ((JSONObject) respObj).optJSONArray("items");
            else if (respObj instanceof JSONArray) items = (JSONArray) respObj;

            List<Playlist> out = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    Playlist p = new Playlist();
                    p.id = it.optInt("id");
                    p.ownerId = it.optInt("owner_id");
                    p.title = it.optString("title", "?");
                    p.size = it.optInt("size", 0);
                    p.photo = it.optString("photo_300", it.optString("photo_600", ""));
                    out.add(p);
                }
            }
            result.data = out;
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getPlaylists failed", e);
            result.error = e.getMessage();
            return result;
        }
    }

    public static ApiResult<List<Audio>> getAudioFromPlaylist(String token, int playlistId, int ownerId, int count) {
        ApiResult<List<Audio>> result = new ApiResult<>();
        try {
            String url = "https://api.vk.com/method/audio.get"
                    + "?access_token=" + URLEncoder.encode(token, "UTF-8")
                    + "&v=" + API_VERSION
                    + "&count=" + count
                    + "&playlist_id=" + playlistId
                    + "&owner_id=" + ownerId;
            JSONObject json = httpGet(url);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").optString("error_msg", "unknown");
                return result;
            }
            if (!json.has("response")) {
                result.error = "Нет response";
                return result;
            }
            Object respObj = json.get("response");
            JSONArray items = null;
            if (respObj instanceof JSONObject) items = ((JSONObject) respObj).optJSONArray("items");
            else if (respObj instanceof JSONArray) items = (JSONArray) respObj;

            List<Audio> audios = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    Audio a = new Audio();
                    a.id = it.optInt("id");
                    a.ownerId = it.optInt("owner_id");
                    a.artist = it.optString("artist", "Unknown");
                    a.title = it.optString("title", "Unknown");
                    a.duration = it.optInt("duration", 0);
                    a.url = it.optString("url", "");
                    audios.add(a);
                }
            }
            result.data = audios;
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getAudioFromPlaylist failed", e);
            result.error = e.getMessage();
            return result;
        }
    }

    public static ApiResult<List<Audio>> getAudio(String token, int count, int offset) {
        ApiResult<List<Audio>> result = new ApiResult<>();
        try {
            String url = "https://api.vk.com/method/audio.get"
                    + "?access_token=" + URLEncoder.encode(token, "UTF-8")
                    + "&v=" + API_VERSION
                    + "&count=" + count
                    + "&offset=" + offset;
            JSONObject json = httpGet(url);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").optString("error_msg", "unknown");
                return result;
            }
            if (!json.has("response")) {
                result.error = "Нет response: " + json.toString();
                return result;
            }
            Object respObj = json.get("response");
            JSONArray items;
            if (respObj instanceof JSONObject) {
                items = ((JSONObject) respObj).optJSONArray("items");
            } else if (respObj instanceof JSONArray) {
                items = (JSONArray) respObj;
            } else {
                items = null;
            }
            List<Audio> audios = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    Audio a = new Audio();
                    a.id = it.optInt("id");
                    a.ownerId = it.optInt("owner_id");
                    a.artist = it.optString("artist", "Unknown");
                    a.title = it.optString("title", "Unknown");
                    a.duration = it.optInt("duration", 0);
                    a.url = it.optString("url", "");
                    audios.add(a);
                }
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
            String url = "https://api.vk.com/method/audio.getById"
                    + "?access_token=" + URLEncoder.encode(token, "UTF-8")
                    + "&v=" + API_VERSION
                    + "&audios=" + ownerId + "_" + audioId;
            JSONObject json = httpGet(url);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").optString("error_msg", "unknown");
                return result;
            }
            JSONArray arr = json.optJSONArray("response");
            if (arr == null || arr.length() == 0) {
                result.error = "Пустой ответ";
                return result;
            }
            String u = arr.getJSONObject(0).optString("url", "");
            if (u.isEmpty()) { result.error = "URL пустой"; return result; }
            result.data = u;
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getAudioUrl failed", e);
            result.error = e.getMessage();
            return result;
        }
    }

    private static JSONObject httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) throw new Exception("HTTP " + code + ": пустой ответ");
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return new JSONObject(sb.toString());
    }
}
