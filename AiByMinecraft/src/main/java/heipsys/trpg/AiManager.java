package heipsys.trpg;

import com.google.gson.*;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * TRPG용 AI 매니저. 4종 AI 인스턴스(GM/Entity/NPC/Assistant)를 관리하며
 * 각 인스턴스별 컨텍스트를 독립적으로 유지한다.
 *
 * 정보 접근 제한:
 *   GM_AI      — 플레이어 스탯/특성/직업 등 전체 정보 접근 가능
 *   ENTITY_AI  — 플레이어 행동 로그만 수신 (스탯/특성 차단)
 *   NPC_AI     — 플레이어 행동 로그만 수신 (스탯/특성 차단)
 *   ASSISTANT  — 단순 처리용 (Haiku 등급)
 */
public class AiManager {

    public enum AiType { GM_AI, ENTITY_AI, NPC_AI, ASSISTANT }

    private final String apiKey;
    private final String apiType;  // claude / openai / gemini
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newHttpClient();

    // 컨텍스트: GM과 Entity/NPC는 별도 히스토리 유지
    private final List<JsonObject>              gmContext     = new ArrayList<>();
    private final List<JsonObject>              entityContext = new ArrayList<>();
    private final Map<String, List<JsonObject>> npcContexts   = new HashMap<>();

    private static final int GM_MAX_TOKENS   = 2048;  // 실제 응답은 200-600 수준
    private static final int ASST_MAX_TOKENS = 1024;

    public AiManager(String apiKey, String apiType) {
        this.apiKey  = apiKey.trim();
        this.apiType = apiType;
    }

    // ======================================================
    //  모델 선택
    // ======================================================

    private String sonnetModel() {
        return switch (apiType) {
            case "claude" -> "claude-sonnet-4-6";
            case "openai" -> "gpt-4o";
            default       -> "gemini-2.0-flash";
        };
    }

    private String haikuModel() {
        return switch (apiType) {
            case "claude" -> "claude-haiku-4-5-20251001";
            case "openai" -> "gpt-4o-mini";
            default       -> "gemini-2.0-flash-lite";
        };
    }

    // ======================================================
    //  GM AI  (Sonnet, 플레이어 전체 정보 접근)
    // ======================================================

    public CompletableFuture<String> callGmAi(String systemPrompt, String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                gmContext.add(msg("user", userMessage));
                String result = send(sonnetModel(), systemPrompt, gmContext, GM_MAX_TOKENS);
                // 히스토리에는 태그 제거 버전 저장 → 다음 턴에 STATE_UPDATE JSON 재전송 방지
                gmContext.add(msg("assistant", stripTags(result)));
                return result;
            } catch (Exception e) {
                return "§c[GM AI 오류] " + e.getMessage();
            }
        });
    }

    /** 컨텍스트 없이 GM AI 1회성 호출 (캐릭터 생성, .gdam 검증 등) */
    public CompletableFuture<String> callGmAiOnce(String systemPrompt, String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JsonObject> single = List.of(msg("user", userMessage));
                return send(sonnetModel(), systemPrompt, single, GM_MAX_TOKENS);
            } catch (Exception e) {
                return "§c[GM AI 오류] " + e.getMessage();
            }
        });
    }

    // ======================================================
    //  Entity AI  (Sonnet, 행동 로그만)
    // ======================================================

    public CompletableFuture<String> callEntityAi(String systemPrompt, String actionLog) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                entityContext.add(msg("user", "플레이어 행동 로그:\n" + actionLog));
                String result = send(haikuModel(), systemPrompt, entityContext, ASST_MAX_TOKENS);
                entityContext.add(msg("assistant", result));
                return result;
            } catch (Exception e) {
                return "§c[Entity AI 오류] " + e.getMessage();
            }
        });
    }

    // ======================================================
    //  NPC AI  (Haiku, 행동 로그만 — 단순 반응에 Sonnet 불필요)
    // ======================================================

    public CompletableFuture<String> callNpcAi(String npcId, String systemPrompt, String actionLog) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                npcContexts.putIfAbsent(npcId, new ArrayList<>());
                List<JsonObject> ctx = npcContexts.get(npcId);
                ctx.add(msg("user", "플레이어 행동 로그:\n" + actionLog));
                String result = send(haikuModel(), systemPrompt, ctx, ASST_MAX_TOKENS);
                ctx.add(msg("assistant", result));
                return result;
            } catch (Exception e) {
                return "§c[NPC AI 오류] " + e.getMessage();
            }
        });
    }

    // ======================================================
    //  Assistant  (Haiku, 단순 처리)
    // ======================================================

    public CompletableFuture<String> callAssistant(String task, String data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JsonObject> messages = List.of(msg("user", task + "\n\n" + data));
                return send(haikuModel(),
                    "너는 간단한 데이터 처리 도우미야. 요청받은 작업만 수행해.",
                    messages, ASST_MAX_TOKENS);
            } catch (Exception e) {
                return "§c[보조 AI 오류] " + e.getMessage();
            }
        });
    }

    // ======================================================
    //  컨텍스트 관리
    // ======================================================

    public void injectGmSystem(String content) {
        gmContext.add(0, msg("user", "[시스템 주입] " + content));
    }

    public void clearAll() {
        gmContext.clear();
        entityContext.clear();
        npcContexts.clear();
    }

    public void clearEntity() { entityContext.clear(); }
    public void clearNpc(String npcId) { npcContexts.remove(npcId); }

    public int getGmContextSize() { return gmContext.size(); }

    /**
     * GM 컨텍스트 압축. 오래된 앞부분을 summary 한 줄로 교체.
     * 최근 10개 메시지는 원본 유지.
     */
    public void compressGmContext(String summary) {
        if (gmContext.size() <= 20) return;
        List<JsonObject> recent = new ArrayList<>(gmContext.subList(gmContext.size() - 10, gmContext.size()));
        gmContext.clear();
        gmContext.add(msg("user", "[이전 컨텍스트 요약]\n" + summary));
        gmContext.addAll(recent);
    }

    // ======================================================
    //  태그 파싱
    // ======================================================

    public JsonObject parseStateUpdate(String response) {
        return parseTag(response, "<STATE_UPDATE>", "</STATE_UPDATE>");
    }

    public JsonObject parseItemGrant(String response) {
        return parseTag(response, "<ITEM_GRANT>", "</ITEM_GRANT>");
    }

    /** 태그를 제거한 순수 서술 텍스트 반환 */
    public String stripTags(String response) {
        return response
            .replaceAll("<STATE_UPDATE>[\\s\\S]*?</STATE_UPDATE>", "")
            .replaceAll("<ITEM_GRANT>[\\s\\S]*?</ITEM_GRANT>", "")
            .replaceAll("<CLEAR>[\\s\\S]*?</CLEAR>", "")
            .replaceAll("<WITNESS[^>]*>[\\s\\S]*?</WITNESS>", "")
            .replaceAll("<SPAWN[^/]*/?>", "")
            .trim();
    }

    public JsonObject parseClearTag(String response) {
        return parseTag(response, "<CLEAR>", "</CLEAR>");
    }

    /** <WITNESS player="name">text</WITNESS> 태그를 파싱 → {playerName: witnessText} */
    public Map<String, String> parseWitnessTags(String response) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        final String PREFIX = "<WITNESS player=\"";
        int from = 0;
        while (true) {
            int open = response.indexOf(PREFIX, from);
            if (open == -1) break;
            int nameEnd = response.indexOf("\">", open + PREFIX.length());
            if (nameEnd == -1) break;
            String name = response.substring(open + PREFIX.length(), nameEnd);
            int close = response.indexOf("</WITNESS>", nameEnd + 2);
            if (close == -1) break;
            result.put(name, response.substring(nameEnd + 2, close).trim());
            from = close + "</WITNESS>".length();
        }
        return result;
    }

    /** <SPAWN player="name"/> 태그에서 플레이어명 추출 */
    public String parseSpawnTag(String response) {
        final String PREFIX = "<SPAWN player=\"";
        int idx = response.indexOf(PREFIX);
        if (idx == -1) return null;
        int nameStart = idx + PREFIX.length();
        int nameEnd = response.indexOf("\"", nameStart);
        if (nameEnd == -1) return null;
        return response.substring(nameStart, nameEnd);
    }

    private JsonObject parseTag(String text, String open, String close) {
        try {
            int s = text.indexOf(open);
            int e = text.indexOf(close);
            if (s == -1 || e == -1 || s >= e) return null;
            String json = text.substring(s + open.length(), e).trim();
            return gson.fromJson(json, JsonObject.class);
        } catch (Exception ex) {
            return null;
        }
    }

    // ======================================================
    //  HTTP 코어 (provider 분기)
    // ======================================================

    private String send(String model, String system, List<JsonObject> messages, int maxTokens)
            throws Exception {

        String body;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .header("Content-Type", "application/json");

        switch (apiType) {
            case "claude" -> {
                builder.uri(URI.create("https://api.anthropic.com/v1/messages"))
                       .header("x-api-key", apiKey)
                       .header("anthropic-version", "2023-06-01");

                JsonObject req = new JsonObject();
                req.addProperty("model", model);
                req.addProperty("max_tokens", maxTokens);
                if (system != null && !system.isBlank())
                    req.addProperty("system", system);

                JsonArray arr = new JsonArray();
                for (JsonObject m : messages) {
                    if (!"system".equals(m.get("role").getAsString())) arr.add(m);
                }
                req.add("messages", arr);
                body = req.toString();
            }
            case "gemini" -> {
                builder.uri(URI.create(
                    "https://generativelanguage.googleapis.com/v1/models/"
                    + model + ":generateContent?key=" + apiKey));

                JsonObject req = new JsonObject();
                JsonArray contents = new JsonArray();

                if (system != null && !system.isBlank()) {
                    contents.add(geminiMsg("user", "[시스템 지침] " + system));
                }
                for (JsonObject m : messages) {
                    String role = "assistant".equals(m.get("role").getAsString()) ? "model" : "user";
                    contents.add(geminiMsg(role, m.get("content").getAsString()));
                }
                req.add("contents", contents);
                body = req.toString();
            }
            default -> { // openai
                builder.uri(URI.create("https://api.openai.com/v1/chat/completions"))
                       .header("Authorization", "Bearer " + apiKey);

                JsonObject req = new JsonObject();
                req.addProperty("model", model);
                JsonArray arr = new JsonArray();
                if (system != null && !system.isBlank()) arr.add(msg("system", system));
                arr.addAll(gson.toJsonTree(messages).getAsJsonArray());
                req.add("messages", arr);
                body = req.toString();
            }
        }

        HttpRequest request = builder
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            Thread.sleep(7000);
            return send(model, system, messages, maxTokens);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("API " + response.statusCode() + ": " + response.body().substring(0, Math.min(200, response.body().length())));
        }

        try {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            return switch (apiType) {
                case "claude" -> json.getAsJsonArray("content").get(0)
                                     .getAsJsonObject().get("text").getAsString();
                case "gemini" -> json.getAsJsonArray("candidates").get(0)
                                     .getAsJsonObject().getAsJsonObject("content")
                                     .getAsJsonArray("parts").get(0)
                                     .getAsJsonObject().get("text").getAsString();
                default       -> json.getAsJsonArray("choices").get(0)
                                     .getAsJsonObject().getAsJsonObject("message")
                                     .get("content").getAsString();
            };
        } catch (Exception e) {
            throw new RuntimeException("API 응답 파싱 실패: " + response.body().substring(0, Math.min(200, response.body().length())), e);
        }
    }

    private JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private JsonObject geminiMsg(String role, String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        msg.add("parts", parts);
        return msg;
    }

    public String getApiType() { return apiType; }
}
