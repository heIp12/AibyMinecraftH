package heipsys.trpg;

import com.google.gson.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

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
    // 멀티플레이에서 여러 플레이어가 동시에 행동하면 callGmAi 등이 동시 실행되므로
    // 각 컨텍스트는 전용 락으로 직렬화하여 동시 변경(자료구조 손상)을 막는다.
    private final List<JsonObject>              gmContext     = new ArrayList<>();
    private final List<JsonObject>              entityContext = new ArrayList<>();
    private final Map<String, List<JsonObject>> npcContexts   = new ConcurrentHashMap<>();
    private final Map<String, Object>           npcCallLocks  = new ConcurrentHashMap<>(); // NPC별 호출 직렬화 락
    private final Object gmLock     = new Object();   // gmContext 자료구조 보호 (빠른 변경 전용)
    private final Object entityLock = new Object();   // entityContext 자료구조 보호 (빠른 변경 전용)
    // 네트워크 호출 직렬화용 락 — 컨텍스트 락과 분리해, send()(블로킹 I/O) 중에 컨텍스트 락을
    // 잡지 않도록 한다. 메인 스레드(injectGmSystem/clearAll 등)는 이 락을 절대 잡지 않으므로
    // GM 호출이 네트워크에서 멈춰도 메인 스레드가 블로킹되지 않는다(서버 프리즈 방지).
    private final Object gmCallLock     = new Object();
    private final Object entityCallLock = new Object();

    private static final int GM_MAX_TOKENS   = 2048;  // 실제 응답은 200-600 수준
    private static final int ASST_MAX_TOKENS = 1024;
    private static final int GDAM_MAX_TOKENS = 12000; // .gdam 청크 JSON 생성용 (8192는 코어 청크가 잘려 파싱 실패 → 상향)

    public AiManager(String apiKey, String apiType) {
        this.apiKey  = apiKey.trim();
        this.apiType = apiType;
    }

    // ======================================================
    //  모델 선택
    // ======================================================

    /** GM AI 품질 등급. 저품질=Haiku / 중품질=Sonnet / 고품질=Opus. */
    public enum Quality { LOW, MEDIUM, HIGH }

    /** 게임 시작 시 선택되는 GM AI 품질 (기본: 중품질). */
    private volatile Quality gmQuality = Quality.MEDIUM;

    // 등급별 모델 오버라이드 (config; 비우면 자동 탐지 → 하드코딩 폴백)
    private String highModelOverride = null, mediumModelOverride = null, lowModelOverride = null;
    // 역할별 모델 오버라이드 (config; 비우면 등급 기본)
    private String gmOverride = null, entityOverride = null, npcOverride = null,
                   assistantOverride = null, gdamOverride = null;
    // 최신 모델 자동 탐지 (claude/openai/gemini 모두 지원). 고·중품질만 자동 탐지(저품질은 비용 최소로 고정).
    private boolean autoLatest = true;
    private volatile boolean modelsDiscovered = false;
    private volatile String autoHigh = null, autoMedium = null, autoLow = null;

    // ── 실사용 토큰·비용 누적(서버 가동 중 영구) + 세션/스테이지 시작 스냅샷(델타용) ──
    private final LongAdder   accCalls   = new LongAdder();
    private final LongAdder   accInTok   = new LongAdder();   // 입력 토큰(캐시 읽기·쓰기 포함)
    private final LongAdder   accOutTok  = new LongAdder();   // 출력 토큰
    private final DoubleAdder accCostUsd = new DoubleAdder(); // 누적 비용(USD)
    private volatile UsageStat sessionStart = new UsageStat(0, 0, 0, 0.0);
    private volatile UsageStat stageStart   = new UsageStat(0, 0, 0, 0.0);
    // 영구 누적 기준점(파일에서 로드 = 이전 가동까지의 전체 누적). 전체누적 = persistedBase + 이번 가동(accumulators).
    private volatile UsageStat persistedBase = new UsageStat(0, 0, 0, 0.0);
    private volatile java.io.File usageFile  = null;
    private final Object usageSaveLock = new Object();
    private volatile long lastSavedCalls = -1L; // 마지막 저장 시점의 호출 수(변경 없으면 재저장 생략)

    /** AI 사용량 스냅샷(호출 수·입력/출력 토큰·USD 비용). */
    public record UsageStat(long calls, long inTok, long outTok, double costUsd) {
        public UsageStat minus(UsageStat o) {
            return new UsageStat(calls - o.calls, inTok - o.inTok, outTok - o.outTok, costUsd - o.costUsd);
        }
        public UsageStat plus(UsageStat o) {
            return new UsageStat(calls + o.calls, inTok + o.inTok, outTok + o.outTok, costUsd + o.costUsd);
        }
    }

    public void setGmQuality(Quality q)    { if (q != null) this.gmQuality = q; }
    public Quality getGmQuality()          { return gmQuality; }
    public boolean isGmHighQuality()       { return gmQuality == Quality.HIGH; }
    private static String norm(String m)   { return (m != null && !m.isBlank()) ? m.trim() : null; }
    public void setHighModelOverride(String m)   { this.highModelOverride   = norm(m); }
    public void setMediumModelOverride(String m) { this.mediumModelOverride = norm(m); }
    public void setLowModelOverride(String m)    { this.lowModelOverride    = norm(m); }
    public void setAutoLatest(boolean b)         { this.autoLatest = b; }
    public String providerLabel() {
        return switch (apiType) { case "claude" -> "Claude"; case "openai" -> "OpenAI"; default -> "Gemini"; };
    }
    /** 역할별 모델 지정(비우면 등급 기본). config 'models' 섹션에서 호출. */
    public void setRoleModels(String gm, String entity, String npc, String assistant, String gdam) {
        this.gmOverride = norm(gm); this.entityOverride = norm(entity); this.npcOverride = norm(npc);
        this.assistantOverride = norm(assistant); this.gdamOverride = norm(gdam);
    }

    // ── provider별 등급 기본 모델 (네트워크 없음) ──
    private String defHigh()   { return switch (apiType) { case "claude" -> "claude-opus-4-8";          case "openai" -> "gpt-5.5";      default -> "gemini-2.5-pro"; }; }
    private String defMedium() { return switch (apiType) { case "claude" -> "claude-sonnet-4-6";        case "openai" -> "gpt-5.4";      default -> "gemini-2.5-flash"; }; }
    private String defLow()    { return switch (apiType) { case "claude" -> "claude-haiku-4-5-20251001"; case "openai" -> "gpt-5.4-nano"; default -> "gemini-2.5-flash-lite"; }; }

    /** 백그라운드 워밍업 — 시작 시 호출하면 최신 모델 탐지가 메인 스레드를 막지 않는다. */
    public void warmUpModels() { CompletableFuture.runAsync(this::ensureModelsDiscovered); }

    /** autoLatest일 때 provider API에서 고·중품질 최신 모델을 1회 조회. 미지원/실패 시 하드코딩 폴백. */
    private void ensureModelsDiscovered() {
        if (modelsDiscovered || !autoLatest || apiKey.isEmpty()) return;
        synchronized (this) {
            if (modelsDiscovered) return;
            modelsDiscovered = true; // 1회만 시도(실패해도 재시도 안 함 — 게임 지연 방지)
            try {
                List<String> ids = fetchModelIds();
                if (ids.isEmpty()) return;
                switch (apiType) {
                    case "claude" -> { // anthropic 목록은 최신순 → 첫 매치가 최신
                        autoHigh   = firstMatch(ids, "opus", null);
                        autoMedium = firstMatch(ids, "sonnet", null);
                        // 저품질=가용 Haiku 중 가장 저렴(3.5) 우선, 없으면 가용 Haiku 아무거나(예: 4.5)
                        autoLow    = firstMatch(ids, "3-5-haiku", null);
                        if (autoLow == null) autoLow = firstMatch(ids, "haiku", null);
                    }
                    case "openai" -> { // 최신 버전 우선(목록 순서 비보장 → 버전 번호로 최신 선별). gpt-5 계열 > gpt-4 계열.
                        autoHigh = latestVer(ids, new String[]{"gpt-5"}, OAI_NON_FLAGSHIP);   // 최신 gpt-5 표준(mini·nano·pro 제외)
                        if (autoHigh == null) autoHigh = latestVer(ids, new String[]{"gpt-4"}, OAI_NON_FLAGSHIP);
                        autoMedium = latestVer(ids, new String[]{"gpt-5", "mini"}, OAI_SPECIAL); // 최신 gpt-5*-mini
                        if (autoMedium == null) autoMedium = autoHigh;
                        autoLow = latestVer(ids, new String[]{"nano"}, OAI_SPECIAL);          // 최신 *-nano(최저가)
                        if (autoLow == null) autoLow = latestVer(ids, new String[]{"mini"}, OAI_SPECIAL);
                    }
                    default -> { // gemini — 버전 최신 우선(gemini-2.5 < 3 < 3.1 < 3.5 …), 특수형 제외
                        autoHigh   = latestVer(ids, new String[]{"pro"}, GEMINI_NONCHAT);
                        autoMedium = latestVer(ids, new String[]{"flash"}, GEMINI_FLASH_EXCL); // flash(라이트 제외)
                        autoLow    = latestVer(ids, new String[]{"flash-lite"}, GEMINI_NONCHAT);
                        if (autoLow == null) autoLow = latestVer(ids, new String[]{"flash"}, GEMINI_FLASH_EXCL);
                    }
                }
            } catch (Exception ignored) { /* 실패 → 하드코딩 폴백 */ }
        }
    }

    /** provider별 모델 목록 조회 → id 리스트. (claude/openai: data[].id, gemini: models[].name) */
    private List<String> fetchModelIds() throws Exception {
        HttpRequest req;
        switch (apiType) {
            case "claude" -> req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models?limit=100"))
                    .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(15)).GET().build();
            case "openai" -> req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(15)).GET().build();
            default -> req = HttpRequest.newBuilder(URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey + "&pageSize=200"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
        }
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        List<String> ids = new ArrayList<>();
        if (resp.statusCode() != 200) return ids;
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        String arrKey = root.has("data") ? "data" : (root.has("models") ? "models" : null);
        if (arrKey == null || !root.get(arrKey).isJsonArray()) return ids;
        for (JsonElement el : root.getAsJsonArray(arrKey)) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String id = o.has("id") ? o.get("id").getAsString() : (o.has("name") ? o.get("name").getAsString() : null);
            if (id == null) continue;
            if (id.startsWith("models/")) id = id.substring("models/".length()); // gemini "models/xxx"
            ids.add(id);
        }
        return ids;
    }

    /** ids에서 kw를 포함하고 excl(있으면)을 포함하지 않는 첫 항목. */
    private static String firstMatch(List<String> ids, String kw, String excl) {
        for (String id : ids) {
            String l = id.toLowerCase();
            if (l.contains(kw) && (excl == null || !l.contains(excl))) return id;
        }
        return null;
    }

    // OpenAI 모델 선별용 제외 목록 — 표준 대화형이 아닌 특수/소형 변형들.
    //  NON_FLAGSHIP: 고품질(플래그십) 자동 선택에서 소형(mini·nano)·고가(pro)·특수형 제외.
    private static final String[] OAI_NON_FLAGSHIP = {
        "mini","nano","pro","codex","audio","realtime","search","image","tts","transcribe","embedding","instruct","moderation"};
    //  SPECIAL: 소형/중형 선택 시에도 대화형이 아닌 특수 변형은 제외(mini·nano는 허용).
    private static final String[] OAI_SPECIAL = {
        "pro","codex","audio","realtime","search","image","tts","transcribe","embedding","instruct","moderation"};

    // Gemini 모델 선별용 제외 목록 — 대화형이 아닌 특수 모델(이미지·임베딩·음성·오픈웨이트 등).
    private static final String[] GEMINI_NONCHAT = {
        "vision","embedding","imagen","veo","aqa","image","tts","audio","live","learnlm","gemma"};
    private static final String[] GEMINI_FLASH_EXCL = {
        "vision","embedding","imagen","veo","aqa","image","tts","audio","live","learnlm","gemma","lite"};

    /** require 키워드를 ★모두★ 포함하고 exclude를 ★하나도★ 포함하지 않는 id 중 버전 번호가 가장 높은 것.
     *  OpenAI 모델 목록은 최신순 정렬이 보장되지 않으므로, 버전으로 '최신'을 직접 고른다(gpt-5.4 < gpt-5.5 < gpt-6 …). */
    private static String latestVer(List<String> ids, String[] require, String[] exclude) {
        String best = null; double bestV = -1;
        for (String id : ids) {
            String l = id.toLowerCase();
            boolean ok = true;
            for (String r : require) if (!l.contains(r)) { ok = false; break; }
            if (ok && exclude != null) for (String e : exclude) if (l.contains(e)) { ok = false; break; }
            if (!ok) continue;
            double v = parseVer(l);
            if (v > bestV) { bestV = v; best = id; }
        }
        return best;
    }

    /** id에서 첫 버전 숫자를 비교용 근사값으로 추출(gpt-5.4→5.04, gpt-5→5.0, gpt-4.1→4.01). 없으면 0. */
    private static double parseVer(String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:[._](\\d+))?").matcher(id);
        if (!m.find()) return 0;
        double major = Double.parseDouble(m.group(1));
        double minor = (m.group(2) != null) ? Double.parseDouble(m.group(2)) : 0;
        return major + minor / 100.0;
    }

    private String sonnetModel() { // 중품질
        if (mediumModelOverride != null) return mediumModelOverride;
        ensureModelsDiscovered();
        return autoMedium != null ? autoMedium : defMedium();
    }

    private String haikuModel() { // 저품질 — 가용한 가장 저렴한 모델(탐지값 우선). config models.low로 직접 지정 가능.
        if (lowModelOverride != null) return lowModelOverride;
        ensureModelsDiscovered();
        return autoLow != null ? autoLow : defLow();
    }

    /** 고품질 모델 (config 우선 → 자동 최신 → provider 기본값) */
    private String highModel() {
        if (highModelOverride != null) return highModelOverride;
        ensureModelsDiscovered();
        return autoHigh != null ? autoHigh : defHigh();
    }

    // ── 비용 추정 (시간당) ──
    /** 모델 가격 (USD per 1M 토큰) [입력, 출력]. 모르는 모델은 보수적 추정. */
    private static double[] modelPriceUsd(String model) {
        String m = model == null ? "" : model.toLowerCase();
        // Claude
        if (m.contains("opus"))   return new double[]{5, 25};
        if (m.contains("sonnet")) return new double[]{3, 15};
        if (m.contains("haiku"))  return m.contains("3-haiku")   ? new double[]{0.25, 1.25}
                                       : m.contains("3-5-haiku") ? new double[]{0.8, 4}
                                       : new double[]{1, 5};
        // OpenAI (2026 기준; gpt-5 계열 우선, 소형/특수 변형 먼저 판별)
        if (m.contains("4o-mini") || m.contains("4.1-nano")) return new double[]{0.15, 0.6}; // 레거시 초저가
        if (m.contains("gpt-5") && m.contains("pro")) return new double[]{30, 180};          // gpt-5.x-pro
        if (m.contains("nano"))    return new double[]{0.20, 1.25};   // gpt-5.x-nano
        if (m.contains("mini"))    return new double[]{0.75, 4.5};    // gpt-5.x-mini
        if (m.contains("gpt-5.5") || m.contains("gpt-5-5")) return new double[]{5, 30};      // 플래그십
        if (m.contains("gpt-5"))   return new double[]{2.5, 15};      // gpt-5 / 5.1 / 5.4 표준급
        if (m.contains("o4") || m.contains("o3") || m.contains("o1")) return new double[]{2.2, 8.8}; // o-시리즈(추론)
        if (m.contains("gpt-4.1")) return new double[]{2, 8};
        if (m.contains("gpt-4o"))  return new double[]{2.5, 10};
        if (m.startsWith("gpt"))   return new double[]{2.5, 15};
        // Gemini (2026 기준; 버전으로 세대 구분 — 날짜 접미사 오판 방지 위해 parseVer 사용)
        if (m.contains("gemini") || m.contains("flash") || m.contains("pro")) {
            boolean gen3 = parseVer(m) >= 3;
            if (m.contains("flash-lite")) return gen3 ? new double[]{0.25, 1.5} : new double[]{0.10, 0.40};
            if (m.contains("flash"))      return gen3 ? new double[]{1.5, 9}    : new double[]{0.30, 2.50};
            if (m.contains("pro"))        return gen3 ? new double[]{2, 12}     : new double[]{1.25, 10};
        }
        return new double[]{1, 5}; // 알 수 없음 — 보수적 기본
    }

    /** 디스커버리(네트워크) 없이 등급의 대표 모델 — 비용 추정용. */
    private String nominalModel(Quality q) {
        return switch (q) {
            case HIGH   -> highModelOverride   != null ? highModelOverride   : (autoHigh   != null ? autoHigh   : defHigh());
            case LOW    -> lowModelOverride    != null ? lowModelOverride    : defLow();
            default     -> mediumModelOverride != null ? mediumModelOverride : (autoMedium != null ? autoMedium : defMedium());
        };
    }

    /** 시간당 예상 비용(USD) — 거친 추정(턴/시간·토큰 가정). */
    private double estimateHourlyUsd(Quality q) {
        double[] gmP  = modelPriceUsd(nominalModel(q));
        double[] auxP = modelPriceUsd(nominalModel(Quality.LOW)); // 괴담/NPC/보조 = 저품질 모델
        final int TURNS = 50;                  // 시간당 GM 턴(3~4인 가정)
        final int GM_IN = 8000, GM_OUT = 800;  // 턴당 GM 토큰(컨텍스트 성장·시나리오 생성 포함 평균)
        final int AUX_CALLS = 2, AUX_IN = 2500, AUX_OUT = 400; // 괴담/NPC 등 보조 호출
        double gm  = TURNS * (GM_IN * gmP[0] + GM_OUT * gmP[1]) / 1_000_000.0;
        double aux = TURNS * AUX_CALLS * (AUX_IN * auxP[0] + AUX_OUT * auxP[1]) / 1_000_000.0;
        return gm + aux;
    }

    /** 품질별 시간당 예상 비용 라벨(원화 추정). 선택 화면·시작 로그용. */
    public String hourlyCostLabel(Quality q) {
        long krw = Math.round(estimateHourlyUsd(q) * 1400.0); // 환율 ~₩1,400/$
        return "약 ₩" + String.format("%,d", krw) + "/시간(추정)";
    }

    // ── 실사용량 조회·마킹 (실제 토큰 사용 기반, /trpg status용) ──
    /** 이번 서버 가동 중 누적 사용량(메모리). */
    public UsageStat lifetimeUsage() {
        return new UsageStat(accCalls.sum(), accInTok.sum(), accOutTok.sum(), accCostUsd.sum());
    }
    /** 전체 누적 사용량(영구) = 이전 가동까지 저장분 + 이번 가동. */
    public UsageStat allTimeUsage() { return persistedBase.plus(lifetimeUsage()); }
    /** 세션(=/trpg start) 시작 시점 표시 — 이후 세션·스테이지 사용량을 0부터 잰다. */
    public void markSessionStart() { sessionStart = lifetimeUsage(); stageStart = sessionStart; saveUsage(); }
    /** 스테이지 시작 시점 표시 — 이후 스테이지 사용량을 0부터 잰다. */
    public void markStageStart()   { stageStart = lifetimeUsage(); saveUsage(); }
    /** /trpg start 이후 사용량. */
    public UsageStat sessionUsage() { return lifetimeUsage().minus(sessionStart); }
    /** 현재 스테이지 사용량. */
    public UsageStat stageUsage()   { return lifetimeUsage().minus(stageStart); }

    // ── 영구 사용량 파일(서버 가동 간 누적 유지) ──
    /** 영구 사용량 파일을 지정하고 즉시 로드한다. 서버 기동 시 1회 호출. */
    public void initUsagePersistence(java.io.File file) { this.usageFile = file; loadPersistedUsage(file); }
    /** 현재 전체 누적을 파일에 비동기 저장(게임 진행 중 체크포인트용). */
    public void saveUsage() {
        final java.io.File f = usageFile;
        if (f != null) CompletableFuture.runAsync(() -> savePersistedUsage(f));
    }
    /** 현재 전체 누적을 파일에 동기 저장(플러그인 종료·리로드 직전용). */
    public void saveUsageSync() { savePersistedUsage(usageFile); }

    private void loadPersistedUsage(java.io.File file) {
        try {
            if (file == null || !file.exists()) return;
            String s = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            JsonObject o = gson.fromJson(s, JsonObject.class);
            if (o == null) return;
            persistedBase = new UsageStat(usageLong(o, "calls"), usageLong(o, "inTok"),
                usageLong(o, "outTok"), o.has("costUsd") ? o.get("costUsd").getAsDouble() : 0.0);
        } catch (Exception ignored) { /* 손상·부재 시 0부터 */ }
    }
    private void savePersistedUsage(java.io.File file) {
        if (file == null) return;
        synchronized (usageSaveLock) {
            try {
                UsageStat all = allTimeUsage();
                if (all.calls() == lastSavedCalls) return; // 직전 저장 이후 새 호출 없음 → 불필요한 쓰기 생략
                JsonObject o = new JsonObject();
                o.addProperty("calls", all.calls());
                o.addProperty("inTok", all.inTok());
                o.addProperty("outTok", all.outTok());
                o.addProperty("costUsd", all.costUsd());
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
                java.nio.file.Files.write(file.toPath(),
                    gson.toJson(o).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                lastSavedCalls = all.calls();
            } catch (Exception ignored) { /* 저장 실패는 게임 진행에 영향 없음 */ }
        }
    }

    /** 사용량 → 원화 환산 누적 비용(₩만, 호출·토큰·달러·괄호 없이 깔끔하게). */
    public String usageLabel(UsageStat u) {
        return "₩" + String.format("%,d", Math.round(u.costUsd() * 1400.0));
    }

    /** 응답의 usage(토큰 사용량)를 provider별로 읽어 영구 누적에 더한다(캐시 단가 반영). */
    private void accumulateUsage(JsonObject json, String model) {
        try {
            double[] price = modelPriceUsd(model); // [입력,출력] per 1M
            long in = 0, out = 0, cacheRead = 0, cacheWrite = 0;
            switch (apiType) {
                case "claude" -> {
                    JsonObject u = json.has("usage") ? json.getAsJsonObject("usage") : null;
                    if (u == null) return;
                    in         = usageLong(u, "input_tokens");
                    out        = usageLong(u, "output_tokens");
                    cacheRead  = usageLong(u, "cache_read_input_tokens");
                    cacheWrite = usageLong(u, "cache_creation_input_tokens");
                }
                case "gemini" -> {
                    JsonObject u = json.has("usageMetadata") ? json.getAsJsonObject("usageMetadata") : null;
                    if (u == null) return;
                    in  = usageLong(u, "promptTokenCount");
                    out = usageLong(u, "candidatesTokenCount");
                }
                default -> { // openai
                    JsonObject u = json.has("usage") ? json.getAsJsonObject("usage") : null;
                    if (u == null) return;
                    in  = usageLong(u, "prompt_tokens");
                    out = usageLong(u, "completion_tokens");
                    if (u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject()) {
                        cacheRead = usageLong(u.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
                        in = Math.max(0, in - cacheRead); // 비캐시 입력만 정가 적용
                    }
                }
            }
            // 캐시 읽기 0.1×, 캐시 생성 1.25×(claude), 그 외 정가
            double cost = (in * price[0]
                         + cacheRead * price[0] * 0.1
                         + cacheWrite * price[0] * 1.25
                         + out * price[1]) / 1_000_000.0;
            accCalls.increment();
            accInTok.add(in + cacheRead + cacheWrite);
            accOutTok.add(out);
            accCostUsd.add(cost);
        } catch (Exception ignored) { /* 사용량 집계 실패는 게임 진행에 영향 주지 않음 */ }
    }

    private static long usageLong(JsonObject o, String k) {
        try { return (o != null && o.has(k) && o.get(k).isJsonPrimitive()) ? o.get(k).getAsLong() : 0L; }
        catch (Exception e) { return 0L; }
    }

    /** GM AI 호출 모델 — 역할 오버라이드 우선, 없으면 품질 등급(저=Haiku/중=Sonnet/고=Opus). */
    private String gmModel() {
        if (gmOverride != null) return gmOverride;
        return switch (gmQuality) {
            case HIGH   -> highModel();
            case LOW    -> haikuModel();
            default     -> sonnetModel();   // MEDIUM
        };
    }

    /** .gdam 생성 모델 — 역할 오버라이드 우선, 없으면 최소 Sonnet 보장(고품질만 Opus). */
    private String gdamModel() {
        if (gdamOverride != null) return gdamOverride;
        return gmQuality == Quality.HIGH ? highModel() : sonnetModel();
    }

    /** 괴담(엔티티) AI 모델 — 역할 오버라이드 우선, 없으면 저품질(Haiku). */
    private String entityModel()    { return entityOverride    != null ? entityOverride    : haikuModel(); }
    /** NPC AI 모델 — 역할 오버라이드 우선, 없으면 저품질(Haiku). */
    private String npcModel()       { return npcOverride       != null ? npcOverride       : haikuModel(); }
    /** 보조(특성·처리) AI 모델 — 역할 오버라이드 우선, 없으면 저품질(Haiku). */
    private String assistantModel() { return assistantOverride != null ? assistantOverride : haikuModel(); }

    // ======================================================
    //  GM AI  (Sonnet, 플레이어 전체 정보 접근)
    // ======================================================

    public CompletableFuture<String> callGmAi(String systemPrompt, String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            // 한 번에 하나의 GM 호출만 처리(직렬화)하되, 네트워크 send()는 컨텍스트 락 밖에서 수행한다.
            // → in-flight GM 호출이 네트워크에서 멈춰도 메인 스레드의 injectGmSystem/clearAll이 막히지 않는다.
            synchronized (gmCallLock) {
                List<JsonObject> snapshot;
                synchronized (gmLock) {                       // 빠른 변경만 (락 보유 시간 최소화)
                    gmContext.add(msg("user", userMessage));
                    snapshot = new ArrayList<>(gmContext);     // 네트워크엔 스냅샷 전달(전송 중 동시 변경 안전)
                }
                try {
                    String result = send(gmModel(), systemPrompt, snapshot, GM_MAX_TOKENS); // 락 미보유 — 블로킹 I/O
                    // 히스토리에는 태그 제거 버전 저장 → 다음 턴에 STATE_UPDATE JSON 재전송 방지
                    synchronized (gmLock) { gmContext.add(msg("assistant", stripTags(result))); }
                    return result;
                } catch (Exception e) {
                    return "§c[GM AI 오류] " + e.getMessage();
                }
            }
        });
    }

    /** 컨텍스트 없이 GM AI 1회성 호출 (캐릭터 생성, .gdam 검증 등) */
    public CompletableFuture<String> callGmAiOnce(String systemPrompt, String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JsonObject> single = List.of(msg("user", userMessage));
                return send(gmModel(), systemPrompt, single, GM_MAX_TOKENS);
            } catch (Exception e) {
                return "§c[GM AI 오류] " + e.getMessage();
            }
        });
    }

    /** 대용량 1회성 호출 (.gdam 전체 JSON 생성 등 — 토큰 한도 높음) */
    public CompletableFuture<String> callGmAiLarge(String systemPrompt, String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JsonObject> single = List.of(msg("user", userMessage));
                return send(gdamModel(), systemPrompt, single, GDAM_MAX_TOKENS);
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
            // GM과 동일 패턴: 네트워크 send()는 entityLock 밖에서 — 메인 스레드 clearAll/clearEntity 블로킹 방지.
            synchronized (entityCallLock) {
                List<JsonObject> snapshot;
                synchronized (entityLock) {
                    entityContext.add(msg("user", "플레이어 행동 로그:\n" + actionLog));
                    snapshot = new ArrayList<>(entityContext);
                }
                try {
                    String result = send(entityModel(), systemPrompt, snapshot, ASST_MAX_TOKENS);
                    synchronized (entityLock) { entityContext.add(msg("assistant", result)); }
                    return result;
                } catch (Exception e) {
                    return "§c[Entity AI 오류] " + e.getMessage();
                }
            }
        });
    }

    // ======================================================
    //  NPC AI  (Haiku, 행동 로그만 — 단순 반응에 Sonnet 불필요)
    // ======================================================

    public CompletableFuture<String> callNpcAi(String npcId, String systemPrompt, String actionLog) {
        return CompletableFuture.supplyAsync(() -> {
            List<JsonObject> ctx = npcContexts.computeIfAbsent(npcId,
                k -> Collections.synchronizedList(new ArrayList<>()));
            Object callLock = npcCallLocks.computeIfAbsent(npcId, k -> new Object());
            // 같은 NPC 호출은 직렬화하되, 네트워크 send()는 ctx 락 밖에서 — 메인 스레드 snapshotNpcMemories 블로킹 방지.
            synchronized (callLock) {
                List<JsonObject> snapshot;
                synchronized (ctx) {
                    ctx.add(msg("user", "플레이어 행동 로그:\n" + actionLog));
                    snapshot = new ArrayList<>(ctx);
                }
                try {
                    String result = send(npcModel(), systemPrompt, snapshot, ASST_MAX_TOKENS);
                    synchronized (ctx) { ctx.add(msg("assistant", result)); }
                    return result;
                } catch (Exception e) {
                    return "§c[NPC AI 오류] " + e.getMessage();
                }
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
                return send(assistantModel(),
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
        synchronized (gmLock) {
            gmContext.add(msg("user", "[시스템 주입] " + content));
        }
    }

    public void clearAll() {
        synchronized (gmLock)     { gmContext.clear(); }
        synchronized (entityLock) { entityContext.clear(); }
        npcContexts.clear(); // ConcurrentHashMap — 자체 thread-safe
        npcCallLocks.clear();
    }

    public void clearEntity() { synchronized (entityLock) { entityContext.clear(); } }
    public void clearNpc(String npcId) { npcContexts.remove(npcId); npcCallLocks.remove(npcId); }

    public int getGmContextSize() { synchronized (gmLock) { return gmContext.size(); } }

    /**
     * 재도전 직전, NPC별 최근 assistant 발화를 스냅샷으로 반환.
     * maxPerNpc: 각 NPC에서 가져올 최대 메시지 수.
     */
    public Map<String, List<String>> snapshotNpcMemories(int maxPerNpc) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        npcContexts.forEach((id, ctx) -> {
            synchronized (ctx) {
                List<String> assistantMsgs = new ArrayList<>();
                for (JsonObject m : ctx) {
                    if ("assistant".equals(m.get("role").getAsString()))
                        assistantMsgs.add(m.get("content").getAsString());
                }
                if (assistantMsgs.isEmpty()) return;
                int from = Math.max(0, assistantMsgs.size() - maxPerNpc);
                snapshot.put(id, new ArrayList<>(assistantMsgs.subList(from, assistantMsgs.size())));
            }
        });
        return snapshot;
    }

    /**
     * clearAll() 이후 NPC 컨텍스트에 이전 회차 기억을 주입.
     * memoryNote: 자연어로 합성된 기억 요약.
     */
    public void preSeedNpcContext(String npcId, String memoryNote) {
        List<JsonObject> ctx = npcContexts.computeIfAbsent(npcId,
            k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (ctx) {
            ctx.add(msg("user", "[이전 회차 기억]\n" + memoryNote));
            ctx.add(msg("assistant", "(기억 확인)"));
        }
    }

    /**
     * GM 컨텍스트 압축. 오래된 앞부분을 summary 한 줄로 교체.
     * 최근 10개 메시지는 원본 유지.
     */
    public void compressGmContext(String summary) {
        synchronized (gmLock) {
            if (gmContext.size() <= 20) return;
            List<JsonObject> recent = new ArrayList<>(gmContext.subList(gmContext.size() - 10, gmContext.size()));
            gmContext.clear();
            gmContext.add(msg("user", "[이전 컨텍스트 요약]\n" + summary));
            gmContext.addAll(recent);
        }
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

    /** &lt;ITEM_USE&gt; — 기계 효과 아이템 사용 시 상태 갱신 (아이템 Phase II) */
    public JsonObject parseItemUse(String response) {
        return parseTag(response, "<ITEM_USE>", "</ITEM_USE>");
    }

    /** 태그를 제거한 순수 서술 텍스트 반환 */
    public String stripTags(String response) {
        return response
            .replaceAll("<THOUGHT>[\\s\\S]*?</THOUGHT>", "")
            .replaceAll("<STATE_UPDATE>[\\s\\S]*?</STATE_UPDATE>", "")
            .replaceAll("<ITEM_GRANT>[\\s\\S]*?</ITEM_GRANT>", "")
            .replaceAll("<ITEM_USE>[\\s\\S]*?</ITEM_USE>", "")
            .replaceAll("<CLEAR>[\\s\\S]*?</CLEAR>", "")
            .replaceAll("<WITNESS[^>]*>[\\s\\S]*?</WITNESS>", "")
            .replaceAll("<SPAWN[^/]*/?>", "")
            .replaceAll("<COMM [^/]*/?>", "")
            .replaceAll("<COMM_CLOSE [^/]*/?>", "")
            .replaceAll("<CONTACT_REVEAL [^/]*/?>", "")
            .replaceAll("<CONTACT_CHANGE [^/]*/?>", "")
            .replaceAll("<IMPERSONATE [^/]*/?>", "")
            .replaceAll("<IMPERSONATE_END [^/]*/?>", "")
            .replaceAll("<ZONE_UPDATE [^/]*/?>", "")
            .replaceAll("<MAP_GRANT [^/]*/?>", "")
            .replaceAll("<TIME_SKIP [^/]*/?>", "")
            .replaceAll("<EVENT_BLOCK [^/]*/?>", "")
            .replaceAll("<EVENT_TRIGGER [^/]*/?>", "")
            .replaceAll("<TIME_VISIBLE [^/]*/?>", "")
            .trim();
    }

    public JsonObject parseClearTag(String response) {
        return parseTag(response, "<CLEAR>", "</CLEAR>");
    }

    /** <THOUGHT>...</THOUGHT> 내용 추출. 없으면 null. */
    public String parseThoughtTag(String response) {
        int s = response.indexOf("<THOUGHT>");
        int e = response.indexOf("</THOUGHT>");
        if (s == -1 || e == -1 || s >= e) return null;
        return response.substring(s + "<THOUGHT>".length(), e).trim();
    }

    /** <THOUGHT>...</THOUGHT> 태그를 제거한 텍스트 반환 */
    public String stripThought(String response) {
        return response.replaceAll("<THOUGHT>[\\s\\S]*?</THOUGHT>", "").trim();
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

    /** <MAP_GRANT player="name"/> 태그들에서 플레이어명 목록 추출 (지도 전체 입수) */
    public java.util.List<String> parseMapGrantTags(String response) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("<MAP_GRANT\\s+player=\"([^\"]+)\"\\s*/?>").matcher(response);
        while (m.find()) out.add(m.group(1));
        return out;
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
        return send(model, system, messages, maxTokens, 0);
    }

    private String send(String model, String system, List<JsonObject> messages, int maxTokens, int attempt)
            throws Exception {

        String body;
        // 출력이 길수록 더 오래 걸린다 → maxTokens에 비례해 타임아웃을 늘린다(.gdam 단일 생성 12000토큰은 120초로 부족).
        // 일반 GM/보조 호출(≤2048)은 120초, 대용량 생성은 최대 300초까지.
        long timeoutSec = Math.max(120, Math.min(300, 60 + maxTokens / 50));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .timeout(Duration.ofSeconds(timeoutSec)) // 응답 무한 대기 방지 (직렬화된 GM 락이 영구 점유되는 것 차단)
            .header("Content-Type", "application/json");

        switch (apiType) {
            case "claude" -> {
                builder.uri(URI.create("https://api.anthropic.com/v1/messages"))
                       .header("x-api-key", apiKey)
                       .header("anthropic-version", "2023-06-01")
                       .header("anthropic-beta", "prompt-caching-2024-07-31");

                JsonObject req = new JsonObject();
                req.addProperty("model", model);
                req.addProperty("max_tokens", maxTokens);
                if (system != null && !system.isBlank()) {
                    // system을 cache_control 포함 배열로 전송 → 캐시 히트 시 입력 토큰 ~90% 절약
                    JsonObject sysBlock = new JsonObject();
                    sysBlock.addProperty("type", "text");
                    sysBlock.addProperty("text", system);
                    JsonObject cacheCtrl = new JsonObject();
                    cacheCtrl.addProperty("type", "ephemeral");
                    sysBlock.add("cache_control", cacheCtrl);
                    JsonArray sysArr = new JsonArray();
                    sysArr.add(sysBlock);
                    req.add("system", sysArr);
                }

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
            if (attempt >= 3) throw new RuntimeException("API 429: 재시도 횟수 초과 (3회)");
            Thread.sleep(7000L * (attempt + 1));
            return send(model, system, messages, maxTokens, attempt + 1);
        }
        // 모델 ID가 이 키에서 안 먹히면(404 not_found) 탐지된 '가용' 모델로 1회 폴백 — 잘못된 모델로 게임이 죽지 않게.
        if (response.statusCode() == 404 && attempt < 2 && response.body().toLowerCase().contains("not_found")) {
            ensureModelsDiscovered();
            String fb = autoMedium != null ? autoMedium : (autoLow != null ? autoLow : autoHigh);
            if (fb != null && !fb.equals(model)) {
                return send(fb, system, messages, maxTokens, attempt + 1);
            }
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("API " + response.statusCode() + ": " + response.body().substring(0, Math.min(200, response.body().length())));
        }

        try {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            accumulateUsage(json, model); // 실사용 토큰·비용 누적(/trpg status 표시용)
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

    // ======================================================
    //  통신 태그 파싱
    // ======================================================

    /** <COMM from="A" to="B" method="radio"/> 파싱 */
    public JsonObject parseCommTag(String response) {
        final String PREFIX = "<COMM ";
        int idx = response.indexOf(PREFIX);
        if (idx == -1) return null;
        // PREFIX = "<COMM " 이므로 "<COMM_CLOSE"와 이미 구별됨 (공백 vs 밑줄)
        int end = response.indexOf("/>", idx);
        if (end == -1) return null;
        String attrs = response.substring(idx + PREFIX.length(), end).trim();
        JsonObject obj = new JsonObject();
        extractAttr(attrs, "from").ifPresent(v -> obj.addProperty("from", v));
        extractAttr(attrs, "to").ifPresent(v -> obj.addProperty("to", v));
        extractAttr(attrs, "method").ifPresent(v -> obj.addProperty("method", v));
        return obj.size() > 0 ? obj : null;
    }

    /** <COMM_CLOSE from="A" to="B"/> 파싱 */
    public JsonObject parseCommCloseTag(String response) {
        final String PREFIX = "<COMM_CLOSE ";
        int idx = response.indexOf(PREFIX);
        if (idx == -1) return null;
        int end = response.indexOf("/>", idx);
        if (end == -1) return null;
        String attrs = response.substring(idx + PREFIX.length(), end).trim();
        JsonObject obj = new JsonObject();
        extractAttr(attrs, "from").ifPresent(v -> obj.addProperty("from", v));
        extractAttr(attrs, "to").ifPresent(v -> obj.addProperty("to", v));
        return obj.size() > 0 ? obj : null;
    }

    /** <CONTACT_REVEAL to="A" target="B"/> 모두 파싱 → [{to, target}, ...] */
    public java.util.List<String[]> parseContactRevealTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<CONTACT_REVEAL ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs = response.substring(idx + PREFIX.length(), end);
            String to     = extractAttr(attrs, "to").orElse(null);
            String target = extractAttr(attrs, "target").orElse(null);
            if (to != null && target != null) out.add(new String[]{to, target});
            from = end + 2;
        }
        return out;
    }

    /** <CONTACT_CHANGE player="X"/> 모두 파싱 → [X, ...] */
    public java.util.List<String> parseContactChangeTags(String response) {
        return parseSelfClosingAttr(response, "<CONTACT_CHANGE ", "player");
    }

    /** <IMPERSONATE player="X"/> 모두 파싱 → [X, ...] */
    public java.util.List<String> parseImpersonateTags(String response) {
        return parseSelfClosingAttr(response, "<IMPERSONATE ", "player");
    }

    /** <IMPERSONATE_END player="X"/> 모두 파싱 → [X, ...] */
    public java.util.List<String> parseImpersonateEndTags(String response) {
        return parseSelfClosingAttr(response, "<IMPERSONATE_END ", "player");
    }

    /** <ZONE_UPDATE player="X" zone="Y" spot="Z" forced="true"/> 파싱 → [{player, zone, spot, forced}, ...]
     *  forced=강제 이동(납치·공격에 날아감·붕괴 등; 잠긴 게이트도 무시). 없으면 "". */
    public java.util.List<String[]> parseZoneUpdateTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<ZONE_UPDATE ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs  = response.substring(idx + PREFIX.length(), end);
            String player = extractAttr(attrs, "player").orElse(null);
            String zone   = extractAttr(attrs, "zone").orElse(null);
            String spot   = extractAttr(attrs, "spot").orElse("");
            String forced = extractAttr(attrs, "forced").orElse("");
            String bypass = extractAttr(attrs, "bypass").orElse("");
            if (player != null && zone != null) out.add(new String[]{player, zone, spot, forced, bypass});
            from = end + 2;
        }
        return out;
    }

    /** <TIME_SKIP minutes="N"/> 모두 합산 → 총 건너뛸 분 (없으면 0) */
    public int parseTimeSkip(String response) {
        int total = 0;
        for (String v : parseSelfClosingAttr(response, "<TIME_SKIP ", "minutes")) {
            try { total += Integer.parseInt(v.trim()); } catch (NumberFormatException ignore) {}
        }
        return total;
    }

    /** <EVENT_BLOCK id="X"/> 모두 파싱 → [id, ...] */
    public java.util.List<String> parseEventBlockTags(String response) {
        return parseSelfClosingAttr(response, "<EVENT_BLOCK ", "id");
    }

    /** <EVENT_TRIGGER id="X"/> 모두 파싱 → [id, ...] (분기로 특정 사건 즉시 발화) */
    public java.util.List<String> parseEventTriggerTags(String response) {
        return parseSelfClosingAttr(response, "<EVENT_TRIGGER ", "id");
    }

    /** <TIME_VISIBLE player="X" known="true/false"/> 모두 파싱 → [{player, known}, ...] */
    public java.util.List<String[]> parseTimeVisibleTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<TIME_VISIBLE ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs  = response.substring(idx + PREFIX.length(), end);
            String player = extractAttr(attrs, "player").orElse(null);
            String known  = extractAttr(attrs, "known").orElse("true");
            if (player != null) out.add(new String[]{player, known});
            from = end + 2;
        }
        return out;
    }

    /** 자기완결 태그(prefix ... />)에서 단일 속성값을 모두 수집 */
    private java.util.List<String> parseSelfClosingAttr(String response, String prefix, String attr) {
        java.util.List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = response.indexOf(prefix, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs = response.substring(idx + prefix.length(), end);
            extractAttr(attrs, attr).ifPresent(out::add);
            from = end + 2;
        }
        return out;
    }

    private java.util.Optional<String> extractAttr(String attrs, String name) {
        String search = name + "=\"";
        int idx = attrs.indexOf(search);
        if (idx == -1) return java.util.Optional.empty();
        int start = idx + search.length();
        int end = attrs.indexOf("\"", start);
        if (end == -1) return java.util.Optional.empty();
        return java.util.Optional.of(attrs.substring(start, end));
    }

    public String getApiType() { return apiType; }
}
