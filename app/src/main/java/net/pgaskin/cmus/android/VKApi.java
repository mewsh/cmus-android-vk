package net.pgaskin.cmus.android;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class VKApi {
    private static final String TAG = "VKApi";

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

    /**
     * Получает список аудиозаписей пользователя.
     */
    public static ApiResult<List<Audio>> getAudio(VKWebClient client, int count, int offset) {
        ApiResult<List<Audio>> result = new ApiResult<>();
        try {
            // Формируем URL для вызова внутреннего метода VK.
            // Обратите внимание: этот метод может отличаться от публичного API.
            // Мы используем формат, который применяется в веб-версии.
            String url = "https://vk.com/audio?act=get_audio&al=1&owner_id=0"
                    + "&offset=" + offset
                    + "&count=" + count;

            JSONObject json = client.get(url);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").getString("error_msg");
                return result;
            }

            // Структура ответа может отличаться от публичного API.
            // Здесь мы предполагаем, что ответ содержит массив "response" или "audios".
            JSONArray items;
            if (json.has("response")) {
                items = json.getJSONArray("response");
            } else if (json.has("audios")) {
                items = json.getJSONArray("audios");
            } else {
                result.error = "Неизвестный формат ответа";
                return result;
            }

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

    /**
     * Получает прямую ссылку на трек, если её не было в списке.
     */
    public static ApiResult<String> getAudioUrl(VKWebClient client, int ownerId, int audioId) {
        ApiResult<String> result = new ApiResult<>();
        try {
            String url = "https://vk.com/audio?act=get_audio&al=1&owner_id=0"
                    + "&audio_id=" + ownerId + "_" + audioId;

            JSONObject json = client.get(url);
            if (json.has("error")) {
                result.error = json.getJSONObject("error").getString("error_msg");
                return result;
            }

            // Пытаемся извлечь URL из разных возможных мест
            String audioUrl = null;
            if (json.has("url")) {
                audioUrl = json.getString("url");
            } else if (json.has("response")) {
                JSONObject resp = json.getJSONObject("response");
                if (resp.has("url")) {
                    audioUrl = resp.getString("url");
                }
            }

            if (audioUrl == null || audioUrl.isEmpty()) {
                result.error = "URL не найден в ответе";
                return result;
            }
            result.data = audioUrl;
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getAudioUrl failed", e);
            result.error = e.getMessage();
            return result;
        }
    }
}
