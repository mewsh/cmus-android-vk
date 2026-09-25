package net.pgaskin.cmus.android;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class VKAuth {
    private static final String TAG = "VKAuth";
    private static final String CLIENT_ID = "2274003";
    private static final String CLIENT_SECRET = "hHbZxrka2uZ6jB1inYsH";
    private static final String API_VERSION = "5.131";
    private static final String UA = "KateMobileAndroid/56 lite-487 (Android 12; SDK 31; arm64-v8a; Xiaomi Redmi Note 8; ru)";

    public static class Result {
        public String token;
        public String userId;
        public String error;
        public boolean needValidation;
        public String validationSid;
        public String phoneMask;
    }

    public static Result login(String username, String password) {
        return request(username, password, null, null);
    }

    public static Result validate(String sid, String code, String username, String password) {
        return request(username, password, sid, code);
    }

    private static Result request(String username, String password, String sid, String code) {
        Result result = new Result();
        try {
            StringBuilder params = new StringBuilder();
            params.append("grant_type=password")
                  .append("&client_id=").append(CLIENT_ID)
                  .append("&client_secret=").append(CLIENT_SECRET)
                  .append("&username=").append(URLEncoder.encode(username, "UTF-8"))
                  .append("&password=").append(URLEncoder.encode(password, "UTF-8"))
                  .append("&scope=audio,offline")
                  .append("&v=").append(API_VERSION);
            if (sid != null && code != null) {
                params.append("&validation_sid=").append(sid)
                      .append("&code=").append(code);
            }

            URL url = new URL("https://oauth.vk.com/token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", UA);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.toString().getBytes(StandardCharsets.UTF_8));
            }

            int codeResp = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    codeResp >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());

            if (json.has("error")) {
                String err = json.getString("error");
                if ("need_validation".equals(err)) {
                    result.needValidation = true;
                    result.validationSid = json.getString("validation_sid");
                    result.phoneMask = json.getString("phone_mask");
                } else {
                    result.error = json.optString("error_description", err);
                }
                return result;
            }

            result.token = json.getString("access_token");
            result.userId = json.getString("user_id");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "request failed", e);
            result.error = e.getMessage();
            return result;
        }
    }
}
