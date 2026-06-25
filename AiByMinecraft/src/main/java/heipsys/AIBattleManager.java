package heipsys;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AIBattleManager {
    private final String apiKey;
    private final String apiType;
    private final FileConfiguration config;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AIBattleManager(String apiKey, String configApiType, FileConfiguration config) {
        this.apiKey = apiKey.trim();
        this.config = config;
        
        String cType = (configApiType != null) ? configApiType.toLowerCase().trim() : "";
        String detectedType = "unknown";
        
        // 1. 키 형태를 바탕으로 실제 모델 감지
        if (this.apiKey.startsWith("sk-ant-")) {
            detectedType = "claude";
        } else if (this.apiKey.startsWith("AIza")) {
            detectedType = "gemini";
        } else if (this.apiKey.startsWith("sk-")) {
            detectedType = "openai";
        }
        
        // 2. config.yml의 설정값과 실제 키의 호환성 검증
        boolean isCompatible = false;
        if (cType.equals("claude") && detectedType.equals("claude")) isCompatible = true;
        else if (cType.equals("gemini") && detectedType.equals("gemini")) isCompatible = true;
        else if (cType.equals("openai") && detectedType.equals("openai")) isCompatible = true;
        
        // 3. 조건부 자동 할당 로직
        if (cType.isEmpty()) {
            this.apiType = !detectedType.equals("unknown") ? detectedType : "openai";
            System.out.println("[AI-Battle] api-type 설정이 비어있어, 키 형태에 따라 " + this.apiType.toUpperCase() + "(으)로 자동 할당되었습니다.");
        } else if (isCompatible) {
            this.apiType = cType;
            System.out.println("[AI-Battle] 지정된 AI 통신 모드: " + this.apiType.toUpperCase());
        } else {
            if (!detectedType.equals("unknown")) {
                System.out.println("§c[AI-Battle 경고] config.yml의 api-type(" + cType + ")과 입력된 키의 형태가 호환되지 않습니다!");
                this.apiType = detectedType;
                System.out.println("§e[AI-Battle 복구] 입력된 API 키에 맞춰 " + this.apiType.toUpperCase() + "(으)로 강제 자동 할당합니다.");
            } else {
                this.apiType = cType;
                System.out.println("[AI-Battle] 키 형태를 감지할 수 없어 설정된 " + this.apiType.toUpperCase() + "(으)로 연결을 시도합니다.");
            }
        }
    }

    private String getApiUrl() {
        return switch (apiType) {
            case "gemini" -> "https://generativelanguage.googleapis.com/v1/models/gemini-3.5-flash:generateContent?key=" + apiKey;
            case "claude" -> "https://api.anthropic.com/v1/messages";
            default -> "https://api.openai.com/v1/chat/completions";
        };
    }

    private JsonObject sendRequest(List<JsonObject> messages) throws Exception {
        String url = getApiUrl();
        String requestBody;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json");

        if (apiType.equals("gemini")) {
            JsonObject body = new JsonObject();
            JsonArray contents = new JsonArray();

            for (JsonObject msg : messages) {
                String role = msg.get("role").getAsString();
                String contentText = msg.get("content").getAsString();

                JsonObject content = new JsonObject();
                content.addProperty("role", role.equals("system") ? "user" : (role.equals("assistant") ? "model" : "user"));
                
                JsonArray parts = new JsonArray();
                JsonObject part = new JsonObject();
                
                part.addProperty("text", role.equals("system") ? "[시스템 지침] " + contentText : contentText);
                parts.add(part);
                content.add("parts", parts);
                contents.add(content);
            }
            body.add("contents", contents);
            requestBody = body.toString();

        } else if (apiType.equals("claude")) {
            requestBuilder.header("x-api-key", apiKey);
            requestBuilder.header("anthropic-version", "2023-06-01");

            JsonObject body = new JsonObject();
            body.addProperty("model", "claude-sonnet-4-6"); // 🌟 최신 모델 적용 완료
            body.addProperty("max_tokens", 4096);
            
            JsonArray claudeMessages = new JsonArray();
            StringBuilder systemPrompt = new StringBuilder();

            for (JsonObject msg : messages) {
                String role = msg.get("role").getAsString();
                String contentText = msg.get("content").getAsString();

                if (role.equals("system")) {
                    systemPrompt.append(contentText).append("\n");
                } else {
                    JsonObject claudeMsg = new JsonObject();
                    claudeMsg.addProperty("role", role.equals("assistant") ? "assistant" : "user");
                    claudeMsg.addProperty("content", contentText);
                    claudeMessages.add(claudeMsg);
                }
            }
            
            if (systemPrompt.length() > 0) {
                body.addProperty("system", systemPrompt.toString().trim());
            }
            body.add("messages", claudeMessages);
            requestBody = body.toString();

        } else {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
            
            JsonObject body = new JsonObject();
            body.addProperty("model", "gpt-5.2");
            body.add("messages", gson.toJsonTree(messages));
            body.add("response_format", gson.fromJson("{\"type\":\"json_object\"}", JsonObject.class));
            requestBody = body.toString();
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String rawBody = response.body();
        int statusCode = response.statusCode();

        if (statusCode == 429) {
            System.err.println("[API 경고] 토큰 제한 초과! 7초 대기 후 재시도합니다.");
            Thread.sleep(7000); 
            return sendRequest(messages);
        }

        if (statusCode != 200) {
            System.err.println("[API 통신 에러] Status: " + statusCode + " Body: " + rawBody);
            if (statusCode == 401 || statusCode == 403) throw new RuntimeException("API 키(Key) 미인증 혹은 잘못됨");
            throw new RuntimeException("서버 오류 (" + statusCode + ")");
        }

        JsonObject json = gson.fromJson(rawBody, JsonObject.class);
        if (json == null) throw new RuntimeException("API 응답이 비어있습니다.");

        String rawText = "";
        
        if (apiType.equals("gemini")) {
            if (json.has("candidates") && json.getAsJsonArray("candidates").size() > 0) {
                rawText = json.getAsJsonArray("candidates").get(0).getAsJsonObject()
                        .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                        .get("text").getAsString();
            } else {
                throw new RuntimeException("Gemini가 유효한 응답을 거부함 (Safety 등)");
            }
        } else if (apiType.equals("claude")) {
            if (json.has("content") && json.getAsJsonArray("content").size() > 0) {
                rawText = json.getAsJsonArray("content").get(0).getAsJsonObject()
                        .get("text").getAsString();
            } else {
                throw new RuntimeException("Claude가 유효한 응답을 거부함");
            }
        } else {
            if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
                rawText = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
            } else {
                throw new RuntimeException("OpenAI가 유효한 응답을 거부함");
            }
        }

        String cleanedText = rawText.replaceAll("```json", "").replaceAll("```", "").trim();
        
        int startIndex = cleanedText.indexOf('{');
        int endIndex = cleanedText.lastIndexOf('}');
        
        if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
            cleanedText = cleanedText.substring(startIndex, endIndex + 1);
        } else {
            startIndex = cleanedText.indexOf('[');
            endIndex = cleanedText.lastIndexOf(']');
            if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
                cleanedText = cleanedText.substring(startIndex, endIndex + 1);
            }
        }

        JsonElement element;
        try {
            element = gson.fromJson(cleanedText, JsonElement.class);
        } catch (Exception e) {
            throw new RuntimeException("AI가 JSON 형식을 지키지 않음: " + cleanedText);
        }

        JsonObject finalResult;

        if (element.isJsonObject()) {
            finalResult = element.getAsJsonObject();
        } else if (element.isJsonArray() && element.getAsJsonArray().size() > 0) {
            finalResult = element.getAsJsonArray().get(0).getAsJsonObject();
        } else {
            throw new RuntimeException("올바른 JSON 객체가 아닙니다.");
        }

        if (!finalResult.has("narrative") && !finalResult.has("intro_narrative") && !finalResult.has("title") && !finalResult.has("story")) {
            System.err.println("[키 누락 경고] AI가 예측된 텍스트 키를 누락했습니다. 원본: " + finalResult.toString());
            
            StringBuilder fallbackText = new StringBuilder("§7(마스터가 혼란스러워하며 알 수 없는 말을 중얼거립니다.)\n");
            for (String key : finalResult.keySet()) {
                JsonElement val = finalResult.get(key);
                if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                    fallbackText.append(val.getAsString()).append(" ");
                } else if (val.isJsonArray() || val.isJsonObject()) {
                    fallbackText.append("[데이터: ").append(key).append("] ");
                }
            }
            finalResult.addProperty("narrative", fallbackText.toString().trim());
            
            if(!finalResult.has("p1_result")) {
                JsonObject fall = new JsonObject();
                fall.add("add_status", new JsonArray());
                fall.add("remove_status", new JsonArray());
                finalResult.add("p1_result", fall);
            }
            if(!finalResult.has("p2_result")) {
                JsonObject fall = new JsonObject();
                fall.add("add_status", new JsonArray());
                fall.add("remove_status", new JsonArray());
                finalResult.add("p2_result", fall);
            }
        }

        if (finalResult.has("story") && !finalResult.has("title")) {
            finalResult.addProperty("title", "실전된 영웅의 기록");
        }
        return finalResult;
    }
    
    public CompletableFuture<Boolean> validateApiKeyAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request;
                if (apiType.equals("gemini")) {
                    request = HttpRequest.newBuilder()
                            .uri(URI.create("https://generativelanguage.googleapis.com/v1/models?key=" + apiKey))
                            .GET()
                            .build();
                } else if (apiType.equals("claude")) {
                    // 🌟 [버그 수정] 키 유효성 검사 테스트 바디에도 최신 모델(claude-sonnet-4-6) 적용 완료!
                    String testBody = "{\"model\": \"claude-sonnet-4-6\", \"max_tokens\": 1, \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}";
                    request = HttpRequest.newBuilder()
                            .uri(URI.create("https://api.anthropic.com/v1/messages"))
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", "2023-06-01")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(testBody))
                            .build();
                } else {
                    request = HttpRequest.newBuilder()
                            .uri(URI.create("https://api.openai.com/v1/models"))
                            .header("Authorization", "Bearer " + apiKey)
                            .GET()
                            .build();
                }
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        });
    }
    
    public CompletableFuture<JsonObject> evaluateClashAsync(
            List<JsonObject> history,
            String p1Name, String p1Legacy, String p1Status, String p1Action, String p1Effects,
            String p2Name, String p2Legacy, String p2Status, String p2Action, String p2Effects,
            String biomeName, int logicLevel, boolean rpMode) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String logicDescription = switch (logicLevel) {
	                case 0 -> "현실주의 모드";
	                case 1 -> "벨런스 모드";
	                case 2 -> "기본 모드";
	                case 3 -> "판타지 모드";
	                case 4 -> "신화 모드";
	                case 5 -> "카오스 모드";
	                default -> "종말 모드";
                };

                String dynamicModeRules = config.getString("prompts.modes.level_" + logicLevel, "모드 규칙을 찾을 수 없습니다.");
                String rpModeText = rpMode ? config.getString("prompts.rp_mode_addon", "") : "";
                
                String systemPromptTemplate = config.getString("prompts.evaluate_base", "기본 판정 프롬프트를 찾을 수 없습니다.");
                String systemPrompt = systemPromptTemplate
                        .replace("{LOGIC_DESCRIPTION}", logicDescription)
                        .replace("{DYNAMIC_MODE_RULES}", dynamicModeRules)
                        .replace("{RP_MODE_TEXT}", rpModeText);

                String finalP1Action = p1Effects.isEmpty() ? p1Action : "[현재 내 상태: " + p1Effects + "] 시도: " + p1Action;
                String finalP2Action = p2Effects.isEmpty() ? p2Action : "[현재 내 상태: " + p2Effects + "] 시도: " + p2Action;

                String userPrompt = String.format(
                        "참가자 A (%s) 이전 서사: %s\n상태:\n%s\n행동: %s\n\n참가자 B (%s) 이전 서사: %s\n상태:\n%s\n행동: %s\n현재 전장(바이옴): %s",
                        p1Name, p1Legacy, p1Status, finalP1Action,
                        p2Name, p2Legacy, p2Status, finalP2Action, biomeName
                    );

                JsonObject messageSystem = new JsonObject();
                messageSystem.addProperty("role", "system");
                messageSystem.addProperty("content", systemPrompt);

                JsonObject messageUser = new JsonObject();
                messageUser.addProperty("role", "user");
                messageUser.addProperty("content", userPrompt);

                List<JsonObject> messages = new ArrayList<>();
                messages.add(messageSystem);
                if (history != null) {
                    messages.addAll(history);
                }
                messages.add(messageUser);

                return sendRequest(messages);

            } catch (Exception e) {
                JsonObject errorObj = new JsonObject();
                errorObj.addProperty("narrative", "§c[마스터 통신 장애] " + e.getMessage());
                errorObj.add("p1_result", new JsonObject());
                errorObj.add("p2_result", new JsonObject());
                return errorObj;
            }
        });
    }

    public CompletableFuture<JsonObject> generateBattleIntroAsync(
            String p1Name, String p1Background, 
            String p2Name, String p2Background, 
            String biomeName, int logicLevel) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String logicDescription = switch (logicLevel) {
                    case 0 -> "현실주의 모드";
                    case 1 -> "벨런스 모드";
                    case 2 -> "기본 모드";
                    case 3 -> "판타지 모드";
                    case 4 -> "신화 모드";
                    case 5 -> "카오스 모드";
                    default -> "종말 모드";
                };

                String systemPromptTemplate = config.getString("prompts.intro_generation", "인트로 프롬프트를 찾을 수 없습니다.");
                String systemPrompt = systemPromptTemplate.replace("{LOGIC_DESCRIPTION}", logicDescription);

                String userPrompt = String.format(
                        "참가자 A(%s) 서사/배경: %s\n참가자 B(%s) 서사/배경: %s\n전장(바이옴): %s",
                        p1Name, p1Background, p2Name, p2Background, biomeName
                );

                JsonObject messageSystem = new JsonObject();
                messageSystem.addProperty("role", "system");
                messageSystem.addProperty("content", systemPrompt);

                JsonObject messageUser = new JsonObject();
                messageUser.addProperty("role", "user");
                messageUser.addProperty("content", userPrompt);

                List<JsonObject> messages = new ArrayList<>();
                messages.add(messageSystem);
                messages.add(messageUser);

                return sendRequest(messages);

            } catch (Exception e) {
                JsonObject errorObj = new JsonObject();
                errorObj.addProperty("intro_narrative", "§c[마스터 통신 장애] 긴장감이 감도는 전장입니다... (" + e.getMessage() + ")");
                return errorObj;
            }
        });
    }

    public CompletableFuture<JsonObject> generateEpicStoryAsync(List<JsonObject> history, String p1Name, String p2Name, String winnerName, List<String> matchLogs) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String logsText = String.join("\n", matchLogs);
                String systemPromptTemplate = config.getString("prompts.story_generation", "스토리 프롬프트를 찾을 수 없습니다.");
                String systemPrompt = systemPromptTemplate
                        .replace("{P1_NAME}", p1Name)
                        .replace("{P2_NAME}", p2Name)
                        .replace("{WINNER_NAME}", winnerName);

                JsonObject messageSystem = new JsonObject();
                messageSystem.addProperty("role", "system");
                messageSystem.addProperty("content", systemPrompt);

                JsonObject messageUser = new JsonObject();
                messageUser.addProperty("role", "user");
                messageUser.addProperty("content", "전투 기록 로그:\n" + logsText);

                List<JsonObject> messages = new ArrayList<>();
                messages.add(messageSystem);
                if (history != null) {
                    messages.addAll(history);
                }
                messages.add(messageUser);

                return sendRequest(messages);
            } catch (Exception e) {
                JsonObject errorObj = new JsonObject();
                errorObj.addProperty("title", "실전된 전투의 기록");
                errorObj.addProperty("story", "오랜 시간이 지나 이 전투의 기록은 소실되었습니다... (" + e.getMessage() + ")");
                return errorObj;
            }
        });
    }
}