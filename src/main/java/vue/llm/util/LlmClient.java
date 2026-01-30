package vue.llm.util;

import okhttp3.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public class LlmClient {

    private final OkHttpClient client;
    public final static String endpoint = "https://api.openai.com/v1/chat/completions";
    public final static String apiKey = "sk-xx";
    public final static String model = "gpt-4o";

    public LlmClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(120))
                .callTimeout(Duration.ofSeconds(180))
                .build();
    }

    public String complete(String systemPrompt, String userPrompt, Map<String, Object> params) throws Exception {
        String temperature = params != null && params.get("temperature") != null
                ? params.get("temperature").toString()
                : "0";

        String payloadFormatted = """
                {
                  "model": %s,
                  "messages": [
                    {"role":"system","content": %s},
                    {"role":"user","content": %s}
                  ],
                  "temperature": %s,
                  "response_format": {"type": "json_object"}
                }
                """;
        String payload = payloadFormatted.formatted(
                jsonString(model),
                jsonString(systemPrompt),
                jsonString(userPrompt),
                temperature
        );
        //System.out.println("[payload]: " + payload);
        Request req = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.getBytes(StandardCharsets.UTF_8), MediaType.parse("application/json")))
                .build();

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful())
                throw new RuntimeException("HTTP " + resp.code() + " - " + resp.message());

            return resp.body() != null ? resp.body().string() : "";
        }
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }


    public String ask(String systemPrompt, String userPrompt) throws Exception {
        return complete(systemPrompt, userPrompt, Map.of("temperature", 0));
    }
}
