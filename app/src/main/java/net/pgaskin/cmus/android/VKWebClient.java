package net.pgaskin.cmus.android;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class VKWebClient {
    private static final String TAG = "VKWebClient";
    private final String cookieP;
    private final String cookieRemixsid;
    // User-Agent, который использует веб-версия VK
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public VKWebClient(String cookieP, String cookieRemixsid) {
        this.cookieP = cookieP;
        this.cookieRemixsid = cookieRemixsid;
    }

    /**
     * Выполняет GET-запрос к API VK с указанными cookies.
     * @param urlString URL запроса
     * @return JSONObject с ответом
     * @throws Exception при ошибке сети или парсинга
     */
    public JSONObject get(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Cookie", "p=" + cookieP + "; remixsid=" + cookieRemixsid);

        int responseCode = conn.getResponseCode();
        BufferedReader reader;
        if (responseCode >= 200 && responseCode < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();

        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception("HTTP " + responseCode + ": " + sb.toString());
        }

        return new JSONObject(sb.toString());
    }
}
