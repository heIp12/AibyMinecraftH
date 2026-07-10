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
    /** 이번 턴 GM에 붙일 시스템 주입 노트 — ★영구 히스토리(gmContext)엔 남기지 않고★ 다음 callGmAi 스냅샷에만 후행으로 붙인다.
     *  (예전엔 injectGmSystem이 gmContext에 직접 append → 스테이지 내내 누적·stale 노트가 쌓여 토큰↑·혼선. gmLock으로 보호.) */
    private final List<String>                  pendingSystemNotes = new ArrayList<>();
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

    // ★thinking 모델(Sonnet 5·Haiku 4.5 등)은 thinking 토큰이 max_tokens를 함께 소모★ → 2048/1024면 복잡한 턴에서
    //   thinking만으로 소진돼 text(대사·서술)가 비어 나오던 문제. 실제 출력은 여전히 200-600 수준이라(짧게 유지) 비용은
    //   거의 그대로지만, thinking이 들어갈 여유를 준다. (비-thinking 모델은 이 여유분을 쓰지 않아 동작 불변.)
    private static final int GM_MAX_TOKENS   = 6000;  // GM 턴(서술·대사): thinking + 실제 응답 200-600
    private static final int ASST_MAX_TOKENS = 4000;  // NPC 대사·능력 브리핑: thinking + 짧은 응답
    private static final int GDAM_MAX_TOKENS = 32000; // .gdam 청크 JSON 생성용. ★thinking 모델(Sonnet 5·Haiku 4.5 등)은 thinking 토큰이 max_tokens를 함께 소모★ → 12000이면 thinking만으로 소진돼 text 블록이 안 나오고 파싱 실패하던 문제. thinking+JSON이 모두 담기게 상향.

    public AiManager(String apiKey, String apiType) {
        this.apiKey  = apiKey.trim();
        this.apiType = apiType;
    }

    // ======================================================
    //  모델 선택
    // ======================================================

    /** GM AI 품질 등급. 저품질=Haiku / 중품질=Sonnet / 고품질=Opus / 효율=적응형(평시 Sonnet, 절정만 Opus). */
    public enum Quality { LOW, MEDIUM, HIGH, EFFICIENT }

    /** 게임 시작 시 선택되는 GM AI 품질 (기본: 중품질). */
    private volatile Quality gmQuality = Quality.MEDIUM;

    /** ★효율(적응형) 모드★: GM 모델을 현재 위협도로 자동 격상 — 평시=Sonnet(바닥), 절정(≥임계)=Opus. 게임이 위협도 공급자를 꽂아준다. */
    private volatile java.util.function.IntSupplier threatSupplier = () -> 0;
    private static final int EFFICIENT_PEAK_THREAT = 70; // 이 이상이면 절정(위험 밴드)으로 보고 Opus 격상 — 위협도는 전투·사망·사건으로 오르는 난이도 프록시
    public void setThreatSupplier(java.util.function.IntSupplier s) { if (s != null) threatSupplier = s; }

    // 등급별 모델 오버라이드 (config; 비우면 자동 탐지 → 하드코딩 폴백)
    private String highModelOverride = null, mediumModelOverride = null, lowModelOverride = null;
    // 역할별 모델 오버라이드 (config; 비우면 등급 기본)
    private String gmOverride = null, entityOverride = null, npcOverride = null,
                   assistantOverride = null, gdamOverride = null;
    // 최신 모델 자동 탐지 (claude/openai/gemini 모두 지원). 고·중품질만 자동 탐지(저품질은 비용 최소로 고정).
    private boolean autoLatest = true;
    // ★생성/응답 속도 조절(config: models.effort-*)★ — Opus 4.8 등 적응형 thinking 깊이(low<medium<high<xhigh<max).
    //   빈 값=모델 기본. 낮출수록 thinking 토큰↓ → 빠름(품질 트레이드오프). gdam=시나리오 생성 티어.
    private String gdamEffort = "", gmEffort = "", npcEffort = "", assistantEffort = "";
    private volatile boolean modelsDiscovered = false;
    private volatile String autoHigh = null, autoMedium = null, autoLow = null;
    /** 미니 티어(나노↑·중품질↓) 자동 탐지값 — NPC 등 '싸지만 나노보단 똑똑해야 하는' 역할용. */
    private volatile String autoMini = null;

    // ── 실사용 토큰·비용 누적(서버 가동 중 영구) + 세션/스테이지 시작 스냅샷(델타용) ──
    private final LongAdder   accCalls   = new LongAdder();
    private final LongAdder   accInTok   = new LongAdder();   // 입력 토큰(캐시 읽기·쓰기 포함)
    private final LongAdder   accOutTok  = new LongAdder();   // 출력 토큰
    private final DoubleAdder accCostUsd = new DoubleAdder(); // 누적 비용(USD)
    // ★#231 진단 계측★ — accInTok(총 입력)을 3갈래로 분해(이번 가동만, 영구저장·UsageStat 불변).
    //   40% 초과의 정체(캐시쓰기 churn=TTL만료·단발게임 / 출력 길이 / 미캐시 호출)를 실플레이 1회로 드러낸다.
    private final LongAdder   accCacheRead  = new LongAdder(); // 캐시 읽기 입력 토큰(0.1× 단가)
    private final LongAdder   accCacheWrite = new LongAdder(); // 캐시 생성 입력 토큰(1h TTL=2× 단가 = 읽기 0.1×의 20배)
    private final DoubleAdder accCostOut    = new DoubleAdder(); // 출력 토큰 비용(USD) — 입력/출력 비중 분해용
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
    /** config models.effort-* → 티어별 effort(빈 값=모델 기본 유지). 낮출수록 thinking↓·빠름. */
    public void setEfforts(String gdam, String gm, String npc, String assistant) {
        this.gdamEffort      = gdam      == null ? "" : gdam.trim();
        this.gmEffort        = gm        == null ? "" : gm.trim();
        this.npcEffort       = npc       == null ? "" : npc.trim();
        this.assistantEffort = assistant == null ? "" : assistant.trim();
    }
    /** 이 모델이 output_config.effort(적응형 thinking 깊이)를 지원하는가. Haiku 등 비적응형(4.5 이하) 티어는 미지원 —
     *  effort를 실어 보내면 400 "does not support the effort parameter". 미지원 모델엔 생략한다(send의 400 폴백은 안전망). */
    private static boolean modelSupportsEffort(String model) {
        if (model == null || model.isBlank()) return false;
        return !model.toLowerCase().contains("haiku"); // Haiku 계열만 제외 — Opus/Sonnet/Fable 등은 지원
    }
    public String providerLabel() {
        return switch (apiType) { case "claude" -> "Claude"; case "openai" -> "OpenAI"; default -> "Gemini"; };
    }
    /** 역할별 모델 지정(비우면 등급 기본). config 'models' 섹션에서 호출. */
    public void setRoleModels(String gm, String entity, String npc, String assistant, String gdam) {
        this.gmOverride = norm(gm); this.entityOverride = norm(entity); this.npcOverride = norm(npc);
        this.assistantOverride = norm(assistant); this.gdamOverride = norm(gdam);
    }

    // ── provider별 등급 기본 모델 (네트워크 없음) ──
    // 티어 지도(역할↔능력): HIGH=GM고품질·생성고품질(플래그십) · MEDIUM=GM기본·생성기본(강한 중형) · LOW=엔티티·보조(유능한 소형) · MINI=NPC(유능한 소형)
    //   ★provider 능력 정렬★: Claude Haiku 4.5는 유능한 소형이라 LOW/MINI에 적합하나, OpenAI nano는 그보다 아래 급이라 엔티티·보조에도 부실 → OpenAI LOW/MINI는 mini로 통일(nano 미사용).
    private String defHigh()   { return switch (apiType) { case "claude" -> "claude-opus-4-8";          case "openai" -> "gpt-5.5";      default -> "gemini-2.5-pro"; }; }
    private String defMedium() { return switch (apiType) { case "claude" -> "claude-sonnet-5";          case "openai" -> "gpt-5.4";      default -> "gemini-3.5-flash"; }; }
    private String defLow()    { return switch (apiType) { case "claude" -> "claude-haiku-4-5-20251001"; case "openai" -> "gpt-5.4-mini"; default -> "gemini-3.1-flash-lite"; }; }
    private String defMini()   { return switch (apiType) { case "claude" -> "claude-haiku-4-5-20251001"; case "openai" -> "gpt-5.4-mini"; default -> "gemini-3.1-flash-lite"; }; }
    // 티어별 대표 모델(2026): HIGH claude=Opus4.8/openai=gpt-5.5/gemini=2.5-pro(최심추론) · MEDIUM sonnet-5/gpt-5.4/3.5-flash(주력)
    //   · LOW·MINI haiku-4.5/gpt-5-mini/3.1-flash-lite(경량 floor·nano 미사용). GM은 gmModel()에서 LOW라도 mini 바닥 보장.

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
                        autoMini   = firstMatch(ids, "haiku", null); // 미니 = 최신 Haiku(4.5) — 저품질(3.5 우선)보다 한 단계 위
                    }
                    case "openai" -> { // 최신 버전 우선(목록 순서 비보장 → 버전 번호로 최신 선별). gpt-5 계열 > gpt-4 계열.
                        autoHigh = latestVer(ids, new String[]{"gpt-5"}, OAI_NON_FLAGSHIP);   // 최신 gpt-5 표준(mini·nano·pro 제외)
                        if (autoHigh == null) autoHigh = latestVer(ids, new String[]{"gpt-4"}, OAI_NON_FLAGSHIP);
                        autoMedium = latestVer(ids, new String[]{"gpt-5", "mini"}, OAI_SPECIAL); // 최신 gpt-5*-mini
                        if (autoMedium == null) autoMedium = autoHigh;
                        autoLow = latestVer(ids, new String[]{"mini"}, OAI_SPECIAL);          // ★nano는 엔티티·보조에도 부실 → 저티어 바닥도 mini★(nano 미사용)
                        if (autoLow == null) autoLow = autoMedium;
                        autoMini = latestVer(ids, new String[]{"mini"}, OAI_SPECIAL);         // 미니(NPC) = 최신 *-mini
                    }
                    default -> { // gemini — 버전 최신 우선(gemini-2.5 < 3 < 3.1 < 3.5 …), 특수형 제외
                        autoHigh   = latestVer(ids, new String[]{"pro"}, GEMINI_NONCHAT);
                        autoMedium = latestVer(ids, new String[]{"flash"}, GEMINI_FLASH_EXCL); // flash(라이트 제외)
                        autoLow    = latestVer(ids, new String[]{"flash-lite"}, GEMINI_NONCHAT);
                        if (autoLow == null) autoLow = latestVer(ids, new String[]{"flash"}, GEMINI_FLASH_EXCL);
                        autoMini   = latestVer(ids, new String[]{"flash"}, GEMINI_FLASH_EXCL); // 미니 = flash(라이트 제외)
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

    /** 미니 티어(나노↑·중품질↓) — GPT: 최신 *-mini / Claude: 최신 Haiku / Gemini: flash. */
    private String miniModel() {
        ensureModelsDiscovered();
        return autoMini != null ? autoMini : defMini();
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
        // Claude (2026: Fable 5 $10/$50 · Opus 4.8 $5/$25 · Sonnet 5 $3/$15 · Haiku 4.5 $1/$5)
        if (m.contains("fable") || m.contains("mythos")) return new double[]{10, 50}; // 최상위(플래그십 초과) — 누락 시 기본값으로 과소추정되던 것 보정
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

    /**
     * 시간당 예상 비용(USD) — ★실측 정합 추정★. ★인원수에 비례★(행동마다 GM 호출 → 사람이 많을수록 턴↑).
     * 예전 추정(턴당 입력 8000·캐싱/생성비 무시)은 실측의 1/3~1/4로 심하게 과소했다. 이제 세 축을 모두 반영:
     *  ① GM 시스템 프롬프트(GM_SYSTEM_BASE ~139KB ≈ ★3만 토큰★, 캐시 대상)를 매 턴 재투입 — 캐시 TTL이 1시간으로 늘었지만
     *     단발게임·턴 간격으로 ★절반가량 캐시 만료(HIT~0.5)★ 시 ★캐시쓰기 2×★로 재적재된다(핵심 비용, 실측 집계와 동단가).
     *  ② 매 턴 새 입력(누적 히스토리·상태·행동)은 캐시 안 됨(정가).
     *  ③ 시나리오 생성(.gdam): 스테이지 진입마다 대형 호출(1회 ~$0.4@Opus). 시간당 ~1.2회로 amortize.
     */
    private double estimateHourlyUsd(Quality q, int players) {
        // GM 턴 단가 — ★효율(적응형)★은 평시 Sonnet 바닥 + 절정(위협도≥임계)만 Opus → 절정 비중으로 혼합 추정.
        //   (실측은 매 호출 '실제로 굴린 모델'로 집계된다 — 이건 선택화면 예상치일 뿐, 위협도 곡선에 따라 실비가 Sonnet~Opus 사이를 오간다.)
        double[] gmP;
        if (q == Quality.EFFICIENT) {
            double[] baseP = modelPriceUsd(sonnetModel());
            double[] peakP = modelPriceUsd(highModel());
            final double PEAK_FRAC = 0.20; // 전투·클라이맥스(위협도 절정) 밴드 추정 비중 — 나머지 시간은 Sonnet 바닥
            gmP = new double[]{ baseP[0] * (1 - PEAK_FRAC) + peakP[0] * PEAK_FRAC,
                                baseP[1] * (1 - PEAK_FRAC) + peakP[1] * PEAK_FRAC };
        } else {
            gmP = modelPriceUsd(nominalModel(q));
        }
        double[] auxP = modelPriceUsd(nominalModel(Quality.LOW)); // 괴담/NPC/보조 = 저품질(mini) 모델
        // 시나리오 생성(.gdam) 단가 — gdamModel과 동일 규칙: HIGH만 Opus, 그 외(효율·중·저)는 Sonnet.
        double[] genP = modelPriceUsd(q == Quality.HIGH ? highModel() : sonnetModel());
        final int TURNS = 15 * Math.max(1, players);   // 시간당 GM 턴 — 1인 ~15
        // ① 시스템 프롬프트(캐시): HIT면 0.1× 읽기, MISS면 ★캐시쓰기 2×(1h TTL)★로 재적재 — 실측 집계(accumulateUsage)와 동일 단가로 정정.
        //   (예전 1.0× 근사는 캐시쓰기 과소계상 → 예측이 실측보다 ~40% 낮던 원인. 1h TTL이라도 단발게임·턴 간격으로 절반가량 만료.)
        final int SYS = 30000; final double HIT = 0.5;
        double sysInPerTurn = SYS * (HIT * 0.1 + (1 - HIT) * 2.0);
        // ② 매 턴 새 입력(정가) + 출력
        final int FRESH_IN = 9000, GM_OUT = 1000;
        double gm  = TURNS * ((sysInPerTurn + FRESH_IN) * gmP[0] + GM_OUT * gmP[1]) / 1_000_000.0;
        // 보조(NPC·괴담·정보추출) — 저품질, 턴당 ~2회
        final int AUX_CALLS = 2, AUX_IN = 3000, AUX_OUT = 500;
        double aux = TURNS * AUX_CALLS * (AUX_IN * auxP[0] + AUX_OUT * auxP[1]) / 1_000_000.0;
        // ③ 시나리오 생성 — 스테이지 페이스 ~1.2회/시간, gdam 모델(효율·중=Sonnet / 고=Opus)
        final double GENS_PER_HR = 1.2; final int GEN_IN = 20000, GEN_OUT = 11000;
        double gen = GENS_PER_HR * (GEN_IN * genP[0] + GEN_OUT * genP[1]) / 1_000_000.0;
        return gm + aux + gen;
    }

    /** 품질별 시간당 예상 비용 라벨(원화 추정, ★인원수 반영★). 선택 화면·시작 로그용. */
    public String hourlyCostLabel(Quality q, int players) {
        int p = Math.max(1, players);
        long krw = Math.round(estimateHourlyUsd(q, p) * 1400.0); // 환율 ~₩1,400/$
        return "약 ₩" + String.format("%,d", krw) + "/시간(" + p + "인 추정)";
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

    /** ★#231 진단★ 이번 가동 비용 구성 분해 — 순수입력/캐시읽기/캐시쓰기/출력 + 비용 비중 + 캐시 히트율.
     *  캐시히트 낮음 = 캐시쓰기(1h TTL=2×) churn(TTL만료·단발게임) / 출력 비중 높음 = 서술 과다. 실플레이 1회로 40% 초과 원인 규명. */
    public java.util.List<String> usageDiagLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (accCalls.sum() == 0) return out;
        long inTot = accInTok.sum(), cr = accCacheRead.sum(), cw = accCacheWrite.sum();
        long fresh = Math.max(0, inTot - cr - cw), ot = accOutTok.sum();
        double cTot = accCostUsd.sum(), cOut = accCostOut.sum();
        long crcw = cr + cw;
        int hitPct = crcw > 0 ? (int) Math.round(cr * 100.0 / crcw) : 0;
        int outPct = cTot > 0 ? (int) Math.round(cOut * 100.0 / cTot) : 0;
        out.add("§8 ");
        out.add("§6§lAI 비용 구성 §7(이번가동 · " + accCalls.sum() + "호출)");
        out.add("§7입력  §f순수 " + fmtTok(fresh) + " §8│ §a캐시읽기 " + fmtTok(cr) + "§8(0.1×) §8│ §c캐시쓰기 " + fmtTok(cw) + "§8(2×·1h TTL)");
        out.add("§7출력  §f" + fmtTok(ot) + " §8(5× 단가)  §8│ §7비중 §f입력 " + (100 - outPct) + "% §8/ §f출력 " + outPct + "%");
        out.add("§7캐시  §f히트 " + hitPct + "% §8(낮을수록 재생성 2× churn=TTL만료·단발게임)");
        return out;
    }
    private static String fmtTok(long t) {
        if (t >= 1_000_000) return String.format("%.1fM", t / 1_000_000.0);
        if (t >= 1_000)     return String.format("%.0fK", t / 1_000.0);
        return Long.toString(t);
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
            // 캐시 읽기 0.1×(TTL 무관), 캐시 생성(쓰기) 단가: 5분 TTL=1.25× / ★1시간 TTL=2×★.
            //   ★이 플러그인은 system·히스토리 프리픽스에 ttl="1h"를 쓴다(send() 1169·1190)★ → 모든 캐시 생성이 2×다.
            //   예전엔 1.25×로 계산해 실비를 ~20% 과소 집계(실측 $56 vs 표시 ~$44)했다 → 2×로 정정.
            //   (TTL을 5분으로 되돌리면 이 상수도 1.25로 되돌릴 것.)
            double cost = (in * price[0]
                         + cacheRead * price[0] * 0.1
                         + cacheWrite * price[0] * 2.0
                         + out * price[1]) / 1_000_000.0;
            accCalls.increment();
            accInTok.add(in + cacheRead + cacheWrite);
            accOutTok.add(out);
            accCostUsd.add(cost);
            accCacheRead.add(cacheRead);                        // #231 진단: 캐시 읽기(0.1×)
            accCacheWrite.add(cacheWrite);                      // #231 진단: 캐시 생성(1.25×)
            accCostOut.add(out * price[1] / 1_000_000.0);       // #231 진단: 출력 비용(입력/출력 비중용)
        } catch (Exception ignored) { /* 사용량 집계 실패는 게임 진행에 영향 주지 않음 */ }
    }

    private static long usageLong(JsonObject o, String k) {
        try { return (o != null && o.has(k) && o.get(k).isJsonPrimitive()) ? o.get(k).getAsLong() : 0L; }
        catch (Exception e) { return 0L; }
    }

    /** GM AI 호출 모델 — 역할 오버라이드 우선, 없으면 ★최소 mini 보장(nano 금지)★. 저=mini·중=Sonnet·고=Opus. */
    private String gmModel() {
        if (gmOverride != null) return gmOverride;
        // ★GM에 nano 금지★: GM은 '규칙 달린 런타임 오퍼레이터'(긴 시스템 프롬프트 유지·시나리오 JSON 교차검증·
        //   zone/item/clue/state 정합·태그 형식 준수·진행 가능 상태 보존)라 단순 대화 생성과 다르다. 최저(nano)에선
        //   제약 유지가 먼저 무너져 교착(전행동 봉쇄)·구역 정합 붕괴·태그 오형식으로 게임이 멈춘다(플레이 로그 실측).
        //   그래서 저/중/고 등급은 살리되 ★GM 바닥은 mini★ — 저=mini(플레이 가능선), 중=Sonnet(권장 기본), 고=Opus(복합·고난도).
        //   저품질 세션은 mini로 굴러가고(방호벽: 교착차단·태그누출·지오검증 프롬프트가 받쳐줌), 나노는 GM에 안 쓴다.
        return switch (gmQuality) {
            case HIGH      -> highModel();
            case LOW       -> miniModel();   // ★floor=mini★ (claude=Haiku 4.5 / openai=gpt-5-mini) — nano로 안 내려간다
            // ★효율(적응형)★: 평시엔 Sonnet(바닥·게임 제대로 굴러가는 선), 위협도 절정(전투·클라이맥스)에만 Opus로 격상 → 최대 절약.
            case EFFICIENT -> threatSupplier.getAsInt() >= EFFICIENT_PEAK_THREAT ? highModel() : sonnetModel();
            default        -> sonnetModel(); // MEDIUM
        };
    }

    /** .gdam 생성 모델 — 역할 오버라이드 우선, 없으면 최소 Sonnet 보장(고품질만 Opus). */
    private String gdamModel() {
        if (gdamOverride != null) return gdamOverride;
        return gmQuality == Quality.HIGH ? highModel() : sonnetModel();
    }

    /** 괴담(엔티티) AI 모델 — 역할 오버라이드 우선, 없으면 저품질(Haiku). */
    private String entityModel()    { return entityOverride    != null ? entityOverride    : haikuModel(); }
    /** NPC AI 모델 — 역할 오버라이드 우선, 없으면 ★미니 티어★(B테스트: 나노급이 절차 반복·스톤월링 등 대화 품질을 깎아 한 단계 승격). */
    private String npcModel()       { return npcOverride       != null ? npcOverride       : miniModel(); }
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
                    gmContext.add(msg("user", userMessage));   // 영구 히스토리엔 ★순수 행동만★
                    snapshot = new ArrayList<>(gmContext);     // 네트워크엔 스냅샷 전달(전송 중 동시 변경 안전)
                    if (!pendingSystemNotes.isEmpty()) {
                        // 이번 턴 시스템 노트를 ★전송 스냅샷에만★ 후행 메시지로 붙인다(gmContext엔 안 남김 → 누적·stale 방지).
                        //  캐시: 안정 프리픽스=gmContext 그대로 → 히스토리 캐싱 유지되고, 이 후행 노트만 매 턴 새로 전송된다.
                        //  각 줄 '[시스템 주입]' 접두 유지 — 누출 스크럽(stripTags의 [시스템 주입] 제거 규칙)이 GM 에코를 잡게 한다.
                        snapshot.add(msg("user", "[시스템 주입] " + String.join("\n[시스템 주입] ", pendingSystemNotes)));
                        pendingSystemNotes.clear();
                    }
                }
                try {
                    // cacheHistory=true: 마지막 메시지 프리픽스 캐싱 → 매 턴 커지는 히스토리를 다음 턴에 0.1× 읽기(핵심 절감).
                    String result = send(gmModel(), systemPrompt, snapshot, GM_MAX_TOKENS, true, gmEffort); // 락 미보유 — 블로킹 I/O
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
                return send(gmModel(), systemPrompt, single, GM_MAX_TOKENS, gmEffort);
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
                return send(gdamModel(), systemPrompt, single, GDAM_MAX_TOKENS, gdamEffort);
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
                    String result = send(entityModel(), systemPrompt, snapshot, ASST_MAX_TOKENS, true, npcEffort); // 히스토리 프리픽스 캐싱
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
        return callNpcAi(npcId, systemPrompt, actionLog, false);
    }
    /**
     * @param dialogue true=직접 대화(입력을 그대로 — userMsg가 이미 '누가·어떤 매체로 말한다' 머리말을 포함).
     *                 false=자율 관측(입력 앞에 '관측된 행동 로그:' 접두).
     * ★모드 분리 이유(#186 감사)★: 대화 입력에까지 '행동 로그' 접두가 붙고, 자율(3인칭 서술)과 대화(1인칭 대사)
     *   응답이 같은 npcContexts에 섞이면 약한 모델이 이력을 모방해 대화에도 3인칭·보고체가 새어 나온다.
     *   히스토리는 기억 유지를 위해 공유하되(분리 시 기억 손실), 입력 라벨만 모드에 맞춰 구분한다.
     */
    public CompletableFuture<String> callNpcAi(String npcId, String systemPrompt, String input, boolean dialogue) {
        return CompletableFuture.supplyAsync(() -> {
            List<JsonObject> ctx = npcContexts.computeIfAbsent(npcId,
                k -> Collections.synchronizedList(new ArrayList<>()));
            Object callLock = npcCallLocks.computeIfAbsent(npcId, k -> new Object());
            // 같은 NPC 호출은 직렬화하되, 네트워크 send()는 ctx 락 밖에서 — 메인 스레드 snapshotNpcMemories 블로킹 방지.
            synchronized (callLock) {
                List<JsonObject> snapshot;
                synchronized (ctx) {
                    ctx.add(msg("user", dialogue ? input : "관측된 행동 로그:\n" + input));
                    snapshot = new ArrayList<>(ctx);
                }
                try {
                    String result = send(npcModel(), systemPrompt, snapshot, ASST_MAX_TOKENS, true, npcEffort); // 히스토리 프리픽스 캐싱
                    // #1(컨텍스트 오염): 자율(3인칭) 응답을 raw로 저장하면 이후 ★대화★ 호출이 그 3인칭·보고체를 흉내낸다(약한 모델의 이력 모방).
                    //   → 자율 응답은 태그를 떼고 '[지난 자율 행동] 요약' 중립 로그로 저장한다(대화 1인칭 응답은 verbatim 유지해 대화 연속성 보존).
                    //   ★단 stripTags가 통째로 지우는 <NPC_CALL>(제가 먼저 건 연락)의 요지는 1인칭 기억으로 되살려 둔다★ —
                    //   자기 발화·계획을 잃어 다음 콜백에서 제 말과 어긋나던 회귀 보완(제 목소리라 스타일 오염 없음).
                    String stored;
                    if (dialogue) {
                        stored = result;
                    } else {
                        String memo = extractOwnCallMemo(result);
                        stored = "[지난 자율 행동] " + stripTags(result).trim() + (memo.isEmpty() ? "" : " " + memo);
                    }
                    synchronized (ctx) { ctx.add(msg("assistant", stored)); }
                    return result;
                } catch (Exception e) {
                    return "§c[NPC AI 오류] " + e.getMessage();
                }
            }
        });
    }

    /**
     * ★말투 2-pass(스타일 전이)★: 완성된 대사의 '내용·정보·의미·감정·길이·괄호 지문'은 그대로 두고 ★문장 종결 말투(어미)만★ styleSpec대로 변환한다.
     * 생성(pass1: callNpcAi)과 분리하는 이유 — 미니 모델은 '스타일 유지하며 생성'은 약해도(표준 어미로 회귀) '완성 문장 어미만 치환'은 안정적이다.
     * 실패·빈 응답이면 원본 대사를 그대로 돌려준다(전달을 절대 막지 않는다). 동기 호출 — 대화 전달 콜백(thenAccept)은 이미 비-메인 스레드다.
     */
    public String restyleDialogue(String dialogue, String styleSpec) {
        if (dialogue == null || dialogue.isBlank() || styleSpec == null || styleSpec.isBlank()) return dialogue;
        try {
            String sys = "너는 '대사 말투 변환기'다. 아래 [원본 대사]의 ★내용·정보·의미·감정·문장 수·괄호 지문(예: (문을 밀며))·줄바꿈·문장부호는 조금도 바꾸지 말고★, "
                + "오직 ★문장을 맺는 말투(어미)★만 [스타일]대로 고쳐라.\n"
                + "★대원칙: [스타일]에 ★명시된 어미만★ 그대로 써라 — 거기 없는 새 어미·변형을 ★지어내지 마라★(예: 스타일이 '~라구/~다구'면 '~냐구·~자구·~구' 같은 변형을 창작 금지). 지정 어미가 자연스럽게 안 붙는 문장은 ★원형을 그대로 지켜라★(억지 변형 금지). 새 말투를 창작하는 게 아니라 '있는 어미만 골라 얹는' 작업이다.\n"
                + "규칙: ①정보·설명·인사·감탄사를 새로 더하거나 빼지 마라(길이·문장 수 유지). 질문은 질문으로·대답은 대답으로 구조를 유지하라. ②반말/존댓말의 방향은 원본 그대로 두고 그 위에 지정 어미만 얹어라. "
                + "③괄호 지문과 태그처럼 보이는 부분은 손대지 마라. ④★어미가 문법상 안 맞는 문장은 억지로 비틀지 마라★ — 의문('왔어?')·감탄·짧은 외침('비켜!'·'뭐야?')·부름은 원래 형태를 지키고, 지정 어미는 그게 자연스럽게 붙는 평서문에만 얹어라. ★단어를 망가뜨리며(예: '왔냐'→'왔냐구') 모든 문장을 같은 소리로 도배하면 사람이 아니라 고장 난 기계처럼 들린다 — 절대 그러지 마라★. 억지로 다 비틀어 어색하면 핵심 평서문에만 적용하라. "
                + "⑤★감정 강도별 조절★: 평상시엔 말투를 또렷이 살리되 긴장·짜증이면 약간 완화하고, 공포·패닉·울음처럼 원본이 이미 흐트러지거나 짧게 끊긴 문장은 그 흐트러짐을 살려 최소한(핵심 어미 흔적만)으로 적용하라 — 고정 어미를 억지로 덧씌워 감정을 납작하게 만들지 마라(격한 순간엔 캐릭터 어미가 약해지는 게 자연스럽다). "
                + "★마무리 자가검수★: 내보내기 전에, 표준 평서형 어미(~요/~다/~어/~습니다)로 밋밋하게 끝난 ★평서문★이 남았으면 지정 어미로 살려 개성이 드러나게 하라 — 단 ④의 의문·감탄·외침·부름과 ⑤의 격한 감정 문장은 원형을 존중하라(★전 문장 도배가 목적이 아니라 '이 인물다움'이 드러나면 충분★). "
                + "출력은 ★변환된 대사 본문만★(따옴표·머리말·해설·목록 금지).\n"
                + "[스타일] " + styleSpec;
            List<JsonObject> m = List.of(msg("user", "[원본 대사]\n" + dialogue));
            String out = send(npcModel(), sys, m, ASST_MAX_TOKENS, npcEffort);
            out = out == null ? "" : out.trim();
            return (out.isEmpty() || out.startsWith("§c")) ? dialogue : out;
        } catch (Exception e) {
            return dialogue; // 변환 실패해도 원본 대사로 전달(전달 보장)
        }
    }

    // ======================================================
    //  Assistant  (Haiku, 단순 처리)
    // ======================================================

    public CompletableFuture<String> callAssistant(String task, String data) {
        return callAssistant(task, data, ASST_MAX_TOKENS);
    }

    /** 출력 토큰 상한을 지정하는 보조 호출 — 긴 목록(직업 풀 등)이 잘리지 않도록. */
    public CompletableFuture<String> callAssistant(String task, String data, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JsonObject> messages = List.of(msg("user", task + "\n\n" + data));
                return send(assistantModel(),
                    "너는 간단한 데이터 처리 도우미야. 요청받은 작업만 수행해.",
                    messages, maxTokens, assistantEffort);
            } catch (Exception e) {
                return "§c[보조 AI 오류] " + e.getMessage();
            }
        });
    }

    /**
     * 특성(능력) 생성용 1회성 호출 — ★GM 티어(gmModel)로 격상★. assistantModel(mini)은 이름을 단어 짜깁기하고
     * name·설명·효과가 따로 놀았다(교차검증 지적). 특성은 세션당 몇 번(캐릭터·클리어 보상·배역)만 생성되고
     * 플레이어가 게임 내내 보는 영구 텍스트라 GM 서술과 같은 품질이어야 한다. 호출 빈도가 낮아 비용 영향이
     * 작다(gmModel은 세션 품질을 따른다 — LOW 세션은 그대로 저품질).
     */
    public CompletableFuture<String> callTraitGen(String system, String data) {
        return callTraitGen(system, data, GM_MAX_TOKENS); // GM 티어(thinking 모델)에 thinking+JSON 여유
    }
    public CompletableFuture<String> callTraitGen(String system, String data, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JsonObject> messages = List.of(msg("user", system + "\n\n" + data));
                return send(gmModel(),
                    "너는 TRPG 특성(능력) 생성기다. 요청받은 JSON 형식으로만 정확히 응답한다.",
                    messages, maxTokens, gmEffort);
            } catch (Exception e) {
                return "§c[특성 AI 오류] " + e.getMessage();
            }
        });
    }

    /**
     * 충실도가 중요한 1회성 호출(친숙 모드 실존 괴담·환상체 정전 선정)용 — 최고 품질 모델 사용.
     * 저품질 모델은 실존 원전을 그럴듯하게 ★창작(환각)★하는 경향이 강해 정전 충실도가 깨진다.
     * 시나리오당 1회뿐이라 비용 영향이 작다.
     */
    public CompletableFuture<String> callAssistantHiFi(String task, String data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<JsonObject> messages = List.of(msg("user", task + "\n\n" + data));
                return send(highModel(),
                    "너는 정확한 자료 큐레이터다. ★검증된 실존 사실만★ 다루고, 불확실하면 가장 확실하고 유명한 것을 택하며, 그럴듯한 이름을 ★새로 지어내지 않는다★.",
                    messages, ASST_MAX_TOKENS, gdamEffort);
            } catch (Exception e) {
                return "§c[보조 AI 오류] " + e.getMessage();
            }
        });
    }

    // ======================================================
    //  컨텍스트 관리
    // ======================================================

    public void injectGmSystem(String content) {
        if (content == null || content.isBlank()) return;
        synchronized (gmLock) {
            // 같은 태그 노트는 새 것으로 교체(상반·중복 노트 누적 방지 — 예: '[통신 잡음]' 두 번이면 최신만).
            String tag = leadingTag(content);
            if (!tag.isEmpty()) pendingSystemNotes.removeIf(n -> n.startsWith(tag));
            pendingSystemNotes.add(content);
        }
    }
    /** content 앞머리의 "[...]" 태그를 추출(없으면 ""). */
    private static String leadingTag(String s) {
        String t = s.stripLeading();
        if (!t.startsWith("[")) return "";
        int end = t.indexOf(']');
        return end > 0 ? t.substring(0, end + 1) : "";
    }

    public void clearAll() {
        synchronized (gmLock)     { gmContext.clear(); pendingSystemNotes.clear(); }
        synchronized (entityLock) { entityContext.clear(); }
        npcContexts.clear(); // ConcurrentHashMap — 자체 thread-safe
        npcCallLocks.clear();
    }

    public void clearEntity() { synchronized (entityLock) { entityContext.clear(); } }
    public void clearNpc(String npcId) { npcContexts.remove(npcId); npcCallLocks.remove(npcId); }

    public int getGmContextSize() { synchronized (gmLock) { return gmContext.size(); } }

    /** GM 컨텍스트(대화 기록)를 mark 길이로 되돌린다(시간 회귀용). LLM은 무상태라 이 리스트가 곧 GM의 기억이다. */
    public void truncateGmContext(int mark) {
        synchronized (gmLock) {
            pendingSystemNotes.clear(); // 되돌린 시점 이후에 쌓인 미전송 노트는 폐기(과거 회귀 정합)
            if (mark < 0 || mark >= gmContext.size()) return;
            gmContext.subList(mark, gmContext.size()).clear();
        }
    }

    /** GM 컨텍스트(=GM의 기억)를 JSON 배열로 내보낸다(세이브용). */
    public com.google.gson.JsonArray exportGmContext() {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        synchronized (gmLock) { for (JsonObject m : gmContext) a.add(m); }
        return a;
    }

    /** 저장된 GM 컨텍스트를 복원한다(이어하기). */
    public void importGmContext(com.google.gson.JsonArray a) {
        synchronized (gmLock) {
            gmContext.clear(); pendingSystemNotes.clear(); // 복원 시 미전송 노트는 폐기(이어하기 정합)
            if (a != null) for (com.google.gson.JsonElement e : a) if (e.isJsonObject()) gmContext.add(e.getAsJsonObject());
        }
    }

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
        JsonObject o = parseTag(response, "<STATE_UPDATE>", "</STATE_UPDATE>");
        if (o != null) return o;
        // ★폴백1★: 일부 모델(제미나이 등)이 <STATE_UPDATE {json}> ★단일 태그★(닫는 태그 없이 여는 태그에 JSON 내장)로 낸다.
        //   이 형식은 위 parseTag(<STATE_UPDATE>…</STATE_UPDATE>)가 못 잡아 상태 적용도·서술 제거도 안 돼 태그가 그대로 누출됐다.
        //   여기서 내장 JSON을 뽑아 실제로 적용되게 하고, stripTags도 이 형식을 함께 제거한다.
        o = parseEmbeddedJsonTag(response, "STATE_UPDATE");
        if (o != null) return o;
        // ★폴백2★: 태그 없이 '벌거벗은' 상태 델타 JSON만 낸 경우(GPT 등) — 시그니처 키로 식별해 적용.
        return parseNakedStateJson(response);
    }

    /** 태그(&lt;STATE_UPDATE&gt;) 래퍼 없이 서술에 흘러나온 상태 델타 JSON을 시그니처 키(hp_change·san_change·
     *  timeline_change)로 식별해 추출. 일부 모델(GPT)이 &lt;STATE_UPDATE&gt; 래퍼 없이 {"player":..,"hp_change":..}만
     *  내보내 상태 적용도 안 되고 서술로 그대로 누출되던 것을 잡는다. stripTags도 같은 형식을 함께 지운다. */
    private JsonObject parseNakedStateJson(String text) {
        if (text == null) return null;
        if (text.indexOf("hp_change") < 0 && text.indexOf("san_change") < 0 && text.indexOf("timeline_change") < 0) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?s)\\{[^{}]*\"(?:hp_change|san_change|timeline_change)\"[^{}]*\\}")
                .matcher(text);
            if (m.find()) return gson.fromJson(m.group(), JsonObject.class);
        } catch (Exception ignore) {}
        return null;
    }

    /** &lt;TAG {json}&gt; 형태(닫는 태그 없이 여는 태그 안에 JSON 오브젝트 내장)에서 첫 JSON 오브젝트를 추출. 없으면 null. */
    private JsonObject parseEmbeddedJsonTag(String text, String tag) {
        if (text == null || text.indexOf(tag) < 0) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)<\\s*" + tag + "\\b[^{]*(\\{[\\s\\S]*?\\})\\s*>")
                .matcher(text);
            if (m.find()) return gson.fromJson(m.group(1).trim(), JsonObject.class);
        } catch (Exception ignore) {}
        return null;
    }

    public JsonObject parseItemGrant(String response) {
        return parseTag(response, "<ITEM_GRANT>", "</ITEM_GRANT>");
    }

    /** &lt;ITEM_USE&gt; — 기계 효과 아이템 사용 시 상태 갱신 (아이템 Phase II) */
    public JsonObject parseItemUse(String response) {
        return parseTag(response, "<ITEM_USE>", "</ITEM_USE>");
    }

    /** &lt;DICE&gt;{"roll":N,"dc":D,"outcome":"성공/부분성공/실패"}&lt;/DICE&gt; — d20 판정 결과(연출용). 없으면 null. */
    public JsonObject parseDiceTag(String response) {
        return parseTag(response, "<DICE>", "</DICE>");
    }

    /** &lt;BROADCAST&gt;{"from":"화자","content":"방송 내용"}&lt;/BROADCAST&gt; — GM이 '진짜 방송(PA 송출)'이라 판정했을 때만 낸다.
     *  시스템이 같은 건물 인원에게 [방송]으로 결정적 전달한다(키워드 추정 대신 GM 판단). 없으면 null. */
    public JsonObject parseBroadcastTag(String response) {
        return parseTag(response, "<BROADCAST>", "</BROADCAST>");
    }

    /** 태그를 제거한 순수 서술 텍스트 반환 */
    public String stripTags(String response) {
        return response
            // 사고(THOUGHT/THINKING) 블록 제거 — 여는·닫는 태그가 어긋나거나(<THOUGHT>…</THINKING>)
            // 잘려도(닫는 태그 누락) 본문에 누출되지 않게 한다. (재미나이 등 추론 블록 대응)
            .replaceAll("(?i)<(thought|thinking)>[\\s\\S]*?</(thought|thinking)>", "")
            .replaceAll("(?i)<(thought|thinking)>[\\s\\S]*$", "")
            .replaceAll("(?i)</?(thought|thinking)>", "")
            .replaceAll("<STATE_UPDATE>[\\s\\S]*?</STATE_UPDATE>", "")
            // ★단일/속성형 <STATE_UPDATE {json}> (닫는 태그 없이 여는 태그에 JSON 내장 — 제미나이 등)도 제거.★
            //   parseStateUpdate가 이 형식을 파싱해 상태는 적용하되, 서술·히스토리엔 태그가 남지 않게 여기서 지운다(누출 버그 수정).
            .replaceAll("(?i)<STATE_UPDATE\\b[^{]*\\{[\\s\\S]*?\\}\\s*>", "")   // <STATE_UPDATE {json}>
            .replaceAll("(?i)<STATE_UPDATE\\b[\\s\\S]*?</STATE_UPDATE>", "")    // 속성 붙은 쌍 <STATE_UPDATE ...>…</STATE_UPDATE>
            .replaceAll("(?i)<STATE_UPDATE\\b[\\s\\S]*$", "")                   // 여는 태그만 남고 미완성/잘림
            // ★대괄호·혼합·고아 STATE_UPDATE★: 약한 모델(GPT 등)이 [STATE_UPDATE]…</STATE_UPDATE>(대괄호 여+꺾쇠 닫)·
            //   [STATE_UPDATE]…[/STATE_UPDATE]·빈 [STATE_UPDATE]로 서술에 누출(실플레이 로그 seq19 실측). 위 꺾쇠 전용 규칙이 못 잡는다.
            //   여는([·<)~닫는(]·>) 쌍을 본문째 + 남은 고아 태그 제거. (내용 있는 델타 JSON은 parseNakedStateJson이 이미 상태에 적용.)
            .replaceAll("(?is)[\\[<]\\s*STATE_UPDATE\\b[^\\]>]*[\\]>][\\s\\S]*?[\\[<]\\s*/\\s*STATE_UPDATE\\s*[\\]>]", "")
            .replaceAll("(?is)[\\[<]\\s*/?\\s*STATE_UPDATE\\b[^\\]>]*[\\]>]", "")
            // ★태그 없는 '벌거벗은' 상태 델타 JSON 제거(GPT 등)★: <STATE_UPDATE> 래퍼 없이 {"player":..,"hp_change":..}만
            //   흘려 상태 적용도 안 되고 서술로 누출되던 것. 시그니처 키(hp_change·san_change·timeline_change)로 식별 —
            //   실제 서술엔 이 영문 스키마 키가 나올 수 없어 오탐 없음. parseNakedStateJson이 같은 형식을 파싱해 상태는 적용한다.
            .replaceAll("(?s)\\{[^{}]*\"(?:hp_change|san_change|timeline_change)\"[^{}]*\\}", "")
            .replaceAll("<ITEM_GRANT>[\\s\\S]*?</ITEM_GRANT>", "")
            .replaceAll("<ITEM_USE>[\\s\\S]*?</ITEM_USE>", "")
            .replaceAll("(?i)<DROP_NOTE[^>]*>[\\s\\S]*?</DROP_NOTE>", "") // 쪽지 두고가기 태그(속성·여러 줄 내용) 서술 누출 차단 — parseDropNoteTags가 raw에서 이미 소비
            .replaceAll("(?i)<DROP_NOTE[^>]*>[\\s\\S]*$", "")             // 닫는 태그 누락·잘림 대비
            .replaceAll("<DICE>[\\s\\S]*?</DICE>", "")
            .replaceAll("<BROADCAST>[\\s\\S]*?</BROADCAST>", "")
            .replaceAll("<CLEAR>[\\s\\S]*?</CLEAR>", "")
            // ★WITNESS 누출/오라우팅 방어★: 정형 <WITNESS player="X">…</WITNESS> 외에, 약한 모델이 [WITNESS …](대괄호)나
            //   </WITNESS> 없이 <WITNESS …>를 ★양쪽 북엔드★로 써서 시점 본문이 다른 플레이어에게 누출됐다(로그 실측).
            //   여는 태그([·<)~(닫는 </WITNESS> | 다음 WITNESS 여는태그) 사이를 본문째 제거하고, 남은 고아 태그도 정리.
            .replaceAll("(?is)[\\[<]\\s*WITNESS\\b[^\\]>]*[\\]>][\\s\\S]*?(?:</\\s*WITNESS\\s*>|(?=[\\[<]\\s*WITNESS\\b))", "")
            .replaceAll("(?is)[\\[<]\\s*/?\\s*WITNESS\\b[^\\]>]*[\\]>]", "")   // 남은 단독/고아 WITNESS 태그(대괄호 포함)
            .replaceAll("<NPC_CALL[^>]*>[\\s\\S]*?</NPC_CALL>", "")
            .replaceAll("(?i)<NPC_LEARN[^>]*>[\\s\\S]*?</NPC_LEARN[^>]*>", "") // 여는·닫는 태그 모두 유연: <NPC_LEARNING>…</NPC_LEARNING>(-ING 변형)도 제거 — 닫는 태그가 literal </NPC_LEARN>이라 -ING이 안 잡혀 서술로 누출되던 버그
            .replaceAll("(?i)<NPC_LEARN[^>]*>[\\s\\S]*$", "")                 // 닫는 태그 누락·잘림 대비(응답 말미 미완성 태그)
            .replaceAll("<TRUST[^>]*>[\\s\\S]*?</TRUST>", "")
            .replaceAll("<SPAWN[^/]*/?>", "")
            .replaceAll("<COMM [^/]*/?>", "")
            .replaceAll("<COMM_CLOSE [^/]*/?>", "")
            .replaceAll("<CONTACT_REVEAL [^/]*/?>", "")
            .replaceAll("<CONTACT_CHANGE [^/]*/?>", "")
            .replaceAll("<IMPERSONATE [^/]*/?>", "")
            .replaceAll("<IMPERSONATE_END [^/]*/?>", "")
            // ★ZONE_UPDATE 누출 방어★: 정규 형식은 <ZONE_UPDATE player=".." zone=".." spot=".."/> (자기닫힘·속성)뿐이나,
            //   일부 모델(GPT 등)이 <ZONE_UPDATE>{json}</ZONE_UPDATE> 쌍·<ZONE_UPDATE> 단독·</ZONE_UPDATE> 홀로 닫힘으로 낸다.
            //   기존 "<ZONE_UPDATE [^/]*/?>"는 ZONE_UPDATE 뒤 ★공백 필수★라 이 변형들을 못 지워 서술로 누출됐다.
            .replaceAll("(?is)<ZONE_UPDATE\\b[^>]*>[\\s\\S]*?</ZONE_UPDATE\\s*>", "") // 쌍(속성·JSON 본문 무관)
            .replaceAll("(?i)</?ZONE_UPDATE\\b[^>]*>", "")                            // 남은 단독 여는/닫는·자기닫힘 태그
            .replaceAll("<NPC_AT [^/]*/?>", "")
            .replaceAll("<BUSY [^/]*/?>", "")
            .replaceAll("<BLOCK_MOVE [^/]*/?>", "")
            .replaceAll("<TEMP_STAT [^/]*/?>", "")
            .replaceAll("<DUR [^/]*/?>", "")
            .replaceAll("</?NO_HOPE\\s*/?>", "")
            .replaceAll("<MAP_GRANT [^/]*/?>", "")
            .replaceAll("<TIME_SKIP [^/]*/?>", "")
            .replaceAll("<EVENT_BLOCK [^/]*/?>", "")
            .replaceAll("<EVENT_TRIGGER [^/]*/?>", "")
            .replaceAll("<ZONE_SEAL [^/]*/?>", "")
            .replaceAll("<ZONE_UNSEAL [^/]*/?>", "")
            .replaceAll("<COMM_BLOCK [^/]*/?>", "")
            .replaceAll("<COMM_UNBLOCK [^/]*/?>", "")
            .replaceAll("<TIME_VISIBLE [^/]*/?>", "")
            .replaceAll("(?i)</?THREAT\\b[^>]*>", "")   // 여는·자기닫힘·★고아 </THREAT>★ 모두(약한 모델이 </THREAT> 홀로 냄 — 로그 실측 누출)
            .replaceAll("(?i)</?ANGER\\b[^>]*>", "")
            .replaceAll("(?i)</?DANGER\\b[^>]*>", "")   // GPT 등이 <THREAT> 대신 내는 <DANGER delta.../> 변형 누출 차단
            .replaceAll("(?i)</?NPC_STATE\\b[^>]*>", "") // ★#266★ NPC 종결 상태 태그(제압·결박·봉인…) — GM 전용, 서술 누출 차단
            .replaceAll("<SUMMON[^>]*>", "")
            .replaceAll("<PACE [^/]*/?>", "")
            // ★[지난 자율 행동] 마커 누적 방지★: 미니 모델이 이전 턴의 이 내부 마커를 에코해 매턴 하나씩
            //   불어나던 버그(1→57, 오타 '자울'까지 전파). 어디에 있든 전부 제거 — 저장 시 정확히 1개만
            //   다시 붙인다(callNpcAi). 출력 누출(플레이어/GM 로그)도 함께 차단.
            .replaceAll("\\[지난\\s*자[율울]\\s*행동\\]\\s*", "")
            // ★GM 시스템 주입·GM 전용 문맥이 서술로 누출되는 것 차단('마음의 소리')★ — GM이 입력 지시문(판정 결과·
            //   소지품·같은 구역 동료·스탯 결·행운 보정·NPC 자율행동 등 GM 전용 컨텍스트)을 서술에 그대로 되뇌는 버그.
            //   이 메타 마커·지시문을 제거한다. 실제 대사 '[이름]…'·방송 '[방송]'은 이 라벨들과 겹치지 않아 안전.
            // (1) ★여러 줄에 걸치는 블록 먼저(DOTALL)★ — NPC 자율행동/막후 진행 주입은 NPC 원문(여러 줄)을 품고
            //     ★행동 요지다…채널에서 나온다).★ 지시문으로 끝난다. 마커부터 그 ※지시문 끝까지 통째로 지운다
            //     (줄 단위로 지우면 첫 줄만 없어지고 NPC 원문이 서술로 남는다). ※ 프리앰블도 함께 소거.
            .replaceAll("(?s)(?:\\[시스템 주입\\]\\s*)?\\[NPC 자율 행동[\\s\\S]*?※\\s*행동 요지[^\\n]*", "")
            .replaceAll("(?s)(?:\\[시스템 주입\\]\\s*)?\\[막후 진행[^\\]]*\\][^\\n]*", "")
            // (2) 한 줄짜리 주입·판정 마커 줄 통째(사망·회복·홀림 등 [시스템 주입] 접두 에코, 판정 결과 주입).
            .replaceAll("(?m)^\\s*\\[시스템 주입\\].*$", "")
            .replaceAll("\\[시스템 주입\\][^\\n]*", "")                 // 인라인 잔여
            .replaceAll("\\[판정 결과\\][^\\n]*", "")                   // 주사위 결과 주입(🎲 주사위:…/주사위 dN=… 어느 표기든)
            // (3) turnCtx 마커(계정 접두 없이 그대로 누출) — 대괄호로 경계 확정.
            .replaceAll("\\[소지품:[^\\]]*\\]", "")                     // GM 아이템 인지
            .replaceAll("\\[행운 보정[^\\]]*\\]", "")                   // 행운 보정 알림
            // ★행운 마커는 엔진 소유★: '[행운!]/[큰 행운!]'은 실제 발동 시에만 시스템이 표기한다(d7=showInlineDice 🍀,
            //   무판정 우연=serendipity 라인). GM이 자유 텍스트로 쓰면 저사양 모델이 판정 없이 남발한다(실플레이 5연발 실측).
            //   → GM 응답의 이 라벨과 [행운 조짐]/[행운 추가판정]/[불운 조짐] 지시 마커 에코를 전부 제거(우연·성과 서술 자체는 남음).
            .replaceAll("\\[\\s*(?:큰\\s*)?행운\\s*!\\s*\\]", "")
            .replaceAll("\\[\\s*행운\\s*(?:조짐|추가판정)[^\\]]*\\]", "")
            .replaceAll("\\[\\s*불운\\s*조짐[^\\]]*\\]", "")
            .replaceAll("\\[GM 필수:[^\\]]*\\]", "")                    // 아이템 사용 환기
            .replaceAll("\\[같은 구역 동료[^\\]\\n]*\\]?", "")          // 같은 구역 목격자 명단(닫힘/개행 어느 쪽이든)
            .replaceAll("\\[[^\\]\\n]*\\((?:근력|영감)\\s*\\d+\\)[^\\]]*\\]", "") // 스탯 결(신체 열세/둔한 직감 등)
            // (4) ※로 시작하는 GM 지시 문장 줄(블록에 안 붙고 홀로 남은 경우).
            .replaceAll("(?m)^\\s*※.*$", "")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    /**
     * 자율(비대화) 응답에서 ★이 NPC가 스스로 먼저 건 연락(&lt;NPC_CALL&gt;)★의 요지만 뽑아 1인칭 기억 문구로 만든다.
     * stripTags가 NPC_CALL을 통째로 지워, 제가 한 말·계획을 잊고 다음 콜백에서 제 말과 어긋나던 회귀를 보완한다.
     * (보존 대상은 ★제 목소리(대사)★뿐이라 3인칭·보고체 오염 없음. 없으면 빈 문자열.)
     */
    private String extractOwnCallMemo(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf("NPC_CALL") < 0) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?is)<NPC_CALL[^>]*\\bplayer\\s*=\\s*\"([^\"]*)\"[^>]*>([\\s\\S]*?)</NPC_CALL>")
            .matcher(raw);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find() && count < 3) {                         // 한 응답에 여러 연락이면 최대 3건까지만(기억 비대화 방지)
            String who  = m.group(1) == null ? "" : m.group(1).trim();
            String said = m.group(2) == null ? "" : stripTags(m.group(2)).replaceAll("\\s+", " ").trim();
            if (said.isEmpty()) continue;
            if (said.length() > 120) said = said.substring(0, 120) + "…"; // 요지만(장문 방지)
            if (sb.length() > 0) sb.append(" ");
            sb.append("(연락 기억 — ").append(who.isEmpty() ? "상대" : who).append("에게 \"").append(said).append("\"라고 먼저 전했다.)");
            count++;
        }
        return sb.toString();
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

    /** <THOUGHT>/<THINKING> 사고 블록 제거 (태그 어긋남·잘림 포함) */
    public String stripThought(String response) {
        return response
            .replaceAll("(?i)<(thought|thinking)>[\\s\\S]*?</(thought|thinking)>", "")
            .replaceAll("(?i)<(thought|thinking)>[\\s\\S]*$", "")
            .replaceAll("(?i)</?(thought|thinking)>", "")
            .trim();
    }

    /** <WITNESS player="name" [far="true"]>text</WITNESS> 파싱 → [{player, text, far("1"|"")}, …].
     *  ★속성 순서·개수 무관(정규식)★ — player 외에 far/loud/scale 같은 '멀리까지 퍼지는 큰 사건' 표식을 GM이 붙일 수 있다.
     *  far 판정은 ★엔진의 단어 추측이 아니라 GM의 명시적 표식★이다(원거리 전달 여부를 GM이 판단하도록). */
    public java.util.List<String[]> parseWitnessTags(String response) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (response == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?is)<WITNESS\\b([^>]*)>(.*?)</WITNESS>").matcher(response);
        while (m.find()) {
            String attrs = m.group(1) == null ? "" : m.group(1);
            String text  = m.group(2) == null ? "" : m.group(2).trim();
            java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("(?i)player\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
            if (!pm.find()) continue;                 // player 없는 WITNESS는 라우팅 불가 → 스킵
            String name = pm.group(1).trim();
            if (name.isEmpty()) continue;
            String a = attrs.toLowerCase().replace(" ", "");
            // GM이 '멀리까지 퍼지는 큰 사건'으로 표시한 경우만 far — 표식 이름은 관대하게(약한 모델 변형 흡수).
            boolean far = a.contains("far=\"true\"") || a.contains("far=\"1\"") || a.contains("far=\"yes\"")
                || a.contains("loud=\"true\"") || a.contains("carries=\"true\"") || a.contains("distant=\"true\"")
                || a.contains("scale=\"large\"") || a.contains("scale=\"big\"") || a.contains("scale=\"far\"") || a.contains("scale=\"huge\"");
            out.add(new String[]{ name, text, far ? "1" : "" });
        }
        return out;
    }

    /** <NPC_CALL player="name">말</NPC_CALL> 태그 파싱 → {playerName: 전할 말}. NPC가 먼저 연락하는 용도. */
    public Map<String, String> parseNpcCallTags(String response) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        final String PREFIX = "<NPC_CALL player=\"";
        int from = 0;
        while (true) {
            int open = response.indexOf(PREFIX, from);
            if (open == -1) break;
            int nameEnd = response.indexOf("\">", open + PREFIX.length());
            if (nameEnd == -1) break;
            String name = response.substring(open + PREFIX.length(), nameEnd);
            int close = response.indexOf("</NPC_CALL>", nameEnd + 2);
            if (close == -1) break;
            result.put(name, response.substring(nameEnd + 2, close).trim());
            from = close + "</NPC_CALL>".length();
        }
        return result;
    }

    /** <NPC_LEARN>새로 알게 된 것</NPC_LEARN> 태그 내용 목록 추출 — NPC가 플레이 중 수집한 정보. */
    public java.util.List<String> parseNpcLearnTags(String response) {
        java.util.List<String> out = new java.util.ArrayList<>();
        final String OPEN = "<NPC_LEARN>", CLOSE = "</NPC_LEARN>";
        int from = 0;
        while (true) {
            int o = response.indexOf(OPEN, from);
            if (o == -1) break;
            int c = response.indexOf(CLOSE, o + OPEN.length());
            if (c == -1) break;
            String v = response.substring(o + OPEN.length(), c).trim();
            if (!v.isEmpty()) out.add(v);
            from = c + CLOSE.length();
        }
        return out;
    }

    /** <TRUST>±N 이유</TRUST> 태그들의 앞머리 부호정수를 합산해 반환(동적 신뢰 델타, #189). 응답당 급변은 [-3,+3]로 상한. */
    public int parseTrustDelta(String response) {
        int sum = 0;
        final String OPEN = "<TRUST>", CLOSE = "</TRUST>";
        int from = 0;
        while (true) {
            int o = response.indexOf(OPEN, from);
            if (o == -1) break;
            int c = response.indexOf(CLOSE, o + OPEN.length());
            if (c == -1) break;
            String v = response.substring(o + OPEN.length(), c).trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([+-]?\\d+)").matcher(v);
            if (m.find()) { try { sum += Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {} }
            from = c + CLOSE.length();
        }
        return Math.max(-3, Math.min(3, sum));
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
        return send(model, system, messages, maxTokens, 0, false, null);
    }

    /** effort(빈 값=모델 기본) 지정 단일 호출 오버로드. */
    private String send(String model, String system, List<JsonObject> messages, int maxTokens, String effort)
            throws Exception {
        return send(model, system, messages, maxTokens, 0, false, effort);
    }

    /** cacheHistory=true면 마지막 메시지에 cache_control을 달아 히스토리 프리픽스를 캐시(멀티턴 GM 전용). */
    private String send(String model, String system, List<JsonObject> messages, int maxTokens, boolean cacheHistory)
            throws Exception {
        return send(model, system, messages, maxTokens, 0, cacheHistory, null);
    }

    /** effort + 히스토리 캐싱 지정 오버로드(멀티턴 GM·NPC). */
    private String send(String model, String system, List<JsonObject> messages, int maxTokens, boolean cacheHistory, String effort)
            throws Exception {
        return send(model, system, messages, maxTokens, 0, cacheHistory, effort);
    }

    private String send(String model, String system, List<JsonObject> messages, int maxTokens, int attempt, boolean cacheHistory, String effort)
            throws Exception {

        String body;
        // 출력이 길수록 더 오래 걸린다 → maxTokens에 비례해 타임아웃을 늘린다(.gdam 단일 생성 12000토큰은 120초로 부족).
        // ★Opus+thinking(adaptive)은 토큰당 훨씬 느려 300초 캡이면 대용량 생성이 통째로 타임아웃 → 재생성/폴백으로
        //   번지고, ★타임아웃난 요청은 Anthropic엔 과금됐는데 클라이언트는 usage를 못 받아 /trpg status에 미집계★된다
        //   (0.41달러 소모인데 ₩6만 표시된 원인). 캡 600초·토큰당 여유 상향으로 정상 완주율을 높인다(생성은 백그라운드).
        long timeoutSec = Math.max(150, Math.min(600, 90 + maxTokens / 20));
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
                // ★effort(적응형 thinking 깊이)★ — 지정 시 output_config.effort로 전달(빈 값이면 모델 기본).
                //   낮출수록 thinking 토큰↓ → 생성/응답 빨라짐. Opus 4.8 등은 budget_tokens 대신 effort만 허용.
                if (effort != null && !effort.isBlank() && modelSupportsEffort(model)) {
                    JsonObject oc = new JsonObject();
                    oc.addProperty("effort", effort.trim());
                    req.add("output_config", oc);
                }
                if (system != null && !system.isBlank()) {
                    // system을 cache_control 포함 배열로 전송 → 캐시 히트 시 입력 토큰 ~90% 절약
                    JsonObject sysBlock = new JsonObject();
                    sysBlock.addProperty("type", "text");
                    sysBlock.addProperty("text", system);
                    JsonObject cacheCtrl = new JsonObject();
                    cacheCtrl.addProperty("type", "ephemeral");
                    cacheCtrl.addProperty("ttl", "1h"); // 1시간 TTL(GA·헤더 불필요): TRPG는 턴 간격(수 분)이 기본 5분 캐시를 넘겨 매 턴 시나리오·규칙 프리픽스를 재처리하던 문제 → 세션 내내 유지.
                    sysBlock.add("cache_control", cacheCtrl);
                    JsonArray sysArr = new JsonArray();
                    sysArr.add(sysBlock);
                    req.add("system", sysArr);
                }

                JsonArray arr = new JsonArray();
                for (JsonObject m : messages) {
                    if (!"system".equals(m.get("role").getAsString())) arr.add(m);
                }
                // ★히스토리 프리픽스 캐싱★(멀티턴 GM 전용): 마지막 메시지에 cache_control을 달면 다음 턴에 이전
                //   히스토리를 0.1× 읽기로 재사용한다. 원본 메시지는 손대지 않고 arr의 마지막 슬롯만 블록형 복제로 교체.
                if (cacheHistory && arr.size() > 0) {
                    JsonObject src = arr.get(arr.size() - 1).getAsJsonObject();
                    if (src.has("content") && src.get("content").isJsonPrimitive()) {
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "text");
                        block.addProperty("text", src.get("content").getAsString());
                        JsonObject cc = new JsonObject();
                        cc.addProperty("type", "ephemeral");
                        cc.addProperty("ttl", "1h"); // 1시간 TTL: 느린 턴 간격에도 멀티턴 GM 히스토리 프리픽스 유지
                        block.add("cache_control", cc);
                        JsonArray contentArr = new JsonArray();
                        contentArr.add(block);
                        JsonObject marked = new JsonObject();
                        marked.addProperty("role", src.get("role").getAsString());
                        marked.add("content", contentArr);
                        arr.set(arr.size() - 1, marked);
                    }
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
            return send(model, system, messages, maxTokens, attempt + 1, cacheHistory, effort);
        }
        // 모델 ID가 이 키에서 안 먹히면(404 not_found) 탐지된 '가용' 모델로 1회 폴백 — 잘못된 모델로 게임이 죽지 않게.
        if (response.statusCode() == 404 && attempt < 2 && response.body().toLowerCase().contains("not_found")) {
            ensureModelsDiscovered();
            String fb = autoMedium != null ? autoMedium : (autoLow != null ? autoLow : autoHigh);
            if (fb != null && !fb.equals(model)) {
                return send(fb, system, messages, maxTokens, attempt + 1, cacheHistory, effort);
            }
        }
        // ★effort 미지원 모델(400)★: output_config.effort를 안 받는 모델에 effort가 실려 나가면
        //   "does not support the effort parameter" 400이 난다 → effort를 빼고 1회 재시도(게임이 죽지 않게).
        if (response.statusCode() == 400 && effort != null && !effort.isBlank()
                && response.body().toLowerCase().contains("effort")) {
            return send(model, system, messages, maxTokens, attempt + 1, cacheHistory, "");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("API " + response.statusCode() + ": " + response.body().substring(0, Math.min(200, response.body().length())));
        }

        try {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            accumulateUsage(json, model); // 실사용 토큰·비용 누적(/trpg status 표시용)
            return switch (apiType) {
                case "claude" -> claudeText(json);
                case "gemini" -> json.getAsJsonArray("candidates").get(0)
                                     .getAsJsonObject().getAsJsonObject("content")
                                     .getAsJsonArray("parts").get(0)
                                     .getAsJsonObject().get("text").getAsString();
                default       -> json.getAsJsonArray("choices").get(0)
                                     .getAsJsonObject().getAsJsonObject("message")
                                     .get("content").getAsString();
            };
        } catch (Exception e) {
            // ★파싱 실패 재시도★: 응답이 잘리거나(불완전 JSON) thinking 블록만 오고 text가 없을 때(Sonnet 5·Haiku 등
            //   thinking 모델에서 간헐 발생) 곧장 죽지 말고 429처럼 재시도한다 — 대개 다음 시도에서 온전한 응답을 받는다.
            if (attempt < 3) {
                Thread.sleep(3000L * (attempt + 1));
                return send(model, system, messages, maxTokens, attempt + 1, cacheHistory, effort);
            }
            throw new RuntimeException("API 응답 파싱 실패: " + response.body().substring(0, Math.min(200, response.body().length())), e);
        }
    }

    /** Claude 응답 content[]에서 실제 텍스트를 추출한다.
     *  ★Claude 5 계열(Sonnet 5 등)은 thinking 블록을 content[0]로 먼저 반환하므로, content[0]만 읽으면
     *  없는 text 필드를 참조해 파싱 실패한다. type=="text" 블록만 골라 이어 붙인다(thinking·기타 블록 무시). */
    private static String claudeText(JsonObject json) {
        com.google.gson.JsonArray content = json.getAsJsonArray("content");
        if (content == null) throw new RuntimeException("content 배열 없음");
        StringBuilder sb = new StringBuilder();
        for (com.google.gson.JsonElement el : content) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            String type = block.has("type") && !block.get("type").isJsonNull() ? block.get("type").getAsString() : "";
            if ("text".equals(type) && block.has("text") && !block.get("text").isJsonNull())
                sb.append(block.get("text").getAsString());
        }
        if (sb.length() == 0) throw new RuntimeException("text 블록 없음(thinking만 반환됐거나 max_tokens 소진)");
        return sb.toString();
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

    /** <DROP_NOTE by="X" to="Y">내용</DROP_NOTE> 파싱 → [by, to(없으면 ""), content]. 플레이어가 장소에 쪽지를 남긴다고 선언하면 GM이 실물 쪽지를 남기는 용도(그 구역에 오는 사람이 발견). */
    public java.util.List<String[]> parseDropNoteTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<DROP_NOTE ";
        int from = 0;
        while (true) {
            int open = response.indexOf(PREFIX, from);
            if (open == -1) break;
            int attrsEnd = response.indexOf(">", open + PREFIX.length());
            if (attrsEnd == -1) break;
            String attrs = response.substring(open + PREFIX.length(), attrsEnd);
            int close = response.indexOf("</DROP_NOTE>", attrsEnd + 1);
            if (close == -1) break;
            String content = response.substring(attrsEnd + 1, close).trim();
            String by = extractAttr(attrs, "by").orElse(null);
            String to = extractAttr(attrs, "to").orElse("");
            if (by != null && !by.isBlank() && !content.isEmpty()) out.add(new String[]{by, to == null ? "" : to, content});
            from = close + "</DROP_NOTE>".length();
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

    /** <BLOCK_MOVE player="X" reason="Y"/> 파싱 → [{player, reason}, ...] — 이동 소프트 차단(#190, 낙관적 이동 GM 거부권). */
    public java.util.List<String[]> parseBlockMoveTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<BLOCK_MOVE ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs  = response.substring(idx + PREFIX.length(), end);
            String player = extractAttr(attrs, "player").orElse(null);
            String reason = extractAttr(attrs, "reason").orElse("");
            if (player != null) out.add(new String[]{player, reason});
            from = end + 2;
        }
        return out;
    }

    /** <THREAT delta="±N" reason="…"/> 파싱 → [{delta, reason}, ...] — 위협도(괴담 세력) 가감. delta는 부호 정수. */
    public java.util.List<String[]> parseThreatTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<THREAT ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs  = response.substring(idx + PREFIX.length(), end);
            String delta  = extractAttr(attrs, "delta").orElse(null);
            String reason = extractAttr(attrs, "reason").orElse("");
            if (delta != null) out.add(new String[]{delta, reason});
            from = end + 2;
        }
        return out;
    }

    /** <TEMP_STAT player="X" stat="근력|매력|행운|영감|체력|정신력" amount="±N" turns="M"/> 파싱 → [{player, stat, amount, turns}, ...]
     *  — 약물·일시 효과로 몇 턴간 스탯을 올린다(음수면 일시 약화). 세션 종료 시 휘발. */
    public java.util.List<String[]> parseTempStatTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<TEMP_STAT ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs  = response.substring(idx + PREFIX.length(), end);
            String player = extractAttr(attrs, "player").orElse(null);
            String stat   = extractAttr(attrs, "stat").orElse("");
            String amount = extractAttr(attrs, "amount").orElse("");
            String turns  = extractAttr(attrs, "turns").orElse("");
            if (player != null && !stat.isBlank()) out.add(new String[]{player, stat, amount, turns});
            from = end + 2;
        }
        return out;
    }

    /** <ANGER delta="±N" target="이름" reason="…"/> 파싱 → [{delta, target, reason}, ...] — 분노도 가감·표적. */
    public java.util.List<String[]> parseAngerTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<ANGER ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs  = response.substring(idx + PREFIX.length(), end);
            String delta  = extractAttr(attrs, "delta").orElse(null);
            String target = extractAttr(attrs, "target").orElse("");
            String reason = extractAttr(attrs, "reason").orElse("");
            if (delta != null) out.add(new String[]{delta, target, reason});
            from = end + 2;
        }
        return out;
    }

    /** ★#266★ <NPC_STATE npc="이름" state="제압|구속|봉인|격퇴|사망|퇴장|해제" note="짧은 설명"/> 파싱 → [{npc, state, note}, ...]
     *  — NPC/괴담의 '종결 상태'를 durable 기록(압축 생존). state="해제"면 그 인물의 상태를 지운다(풀려남·부활·복귀). */
    public java.util.List<String[]> parseNpcStateTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<NPC_STATE ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs = response.substring(idx + PREFIX.length(), end);
            String npc   = extractAttr(attrs, "npc").orElse(null);
            String state = extractAttr(attrs, "state").orElse("");
            String note  = extractAttr(attrs, "note").orElse("");
            if (npc != null && !npc.isBlank()) out.add(new String[]{npc, state, note});
            from = end + 2;
        }
        return out;
    }

    /** <NPC_AT npc="X" zone="Y"/> 파싱 → [{npc, zone}, ...] — GM이 자율 NPC를 특정 구역(특히 플레이어 장면)에
     *  데려다 놓을 때. 위치 추적(npcZones)을 갱신해 그 NPC에게 @대화하면 '전화'가 아니라 '대면'으로 처리되게 한다(#B 또전화). */
    public java.util.List<String[]> parseNpcAtTags(String response) {
        java.util.List<String[]> out = new ArrayList<>();
        final String PREFIX = "<NPC_AT ";
        int from = 0;
        while (true) {
            int idx = response.indexOf(PREFIX, from);
            if (idx == -1) break;
            int end = response.indexOf("/>", idx);
            if (end == -1) break;
            String attrs = response.substring(idx + PREFIX.length(), end);
            String npc  = extractAttr(attrs, "npc").orElse(null);
            String zone = extractAttr(attrs, "zone").orElse(null);
            if (npc != null && zone != null) out.add(new String[]{npc, zone});
            from = end + 2;
        }
        return out;
    }

    /** <BUSY turns="N"/> — 자율 NPC가 '여러 턴 걸리는 다급한 일 중'이라 앞으로 N턴 계속 구동을 요청(#179 능동 비트 활성 창). 최대값 반환(없으면 0). */
    public int parseNpcBusyTurns(String response) {
        int max = 0;
        for (String v : parseSelfClosingAttr(response, "<BUSY ", "turns")) {
            try { max = Math.max(max, Integer.parseInt(v.trim())); } catch (NumberFormatException ignore) {}
        }
        return max;
    }

    /** <TIME_SKIP minutes="N"/> 모두 합산 → 총 건너뛸 분 (없으면 0) */
    public int parseTimeSkip(String response) {
        int total = 0;
        for (String v : parseSelfClosingAttr(response, "<TIME_SKIP ", "minutes")) {
            try { total += Integer.parseInt(v.trim()); } catch (NumberFormatException ignore) {}
        }
        return total;
    }

    /** <DUR minutes="N"/> 합산 → 이 행동의 소요 분(없으면 0, 0~1440 클램프 = 최대 하루).
     *  가변 턴 모드(turnMode≥1)에선 이 값만큼 시계가 흐른다(#151 Stage A). 하루를 넘겨 며칠·달·해 단위로 건너뛰는 도약은 DUR이 아니라 <TIME_SKIP>. */
    public int parseDur(String response) {
        int total = 0;
        for (String v : parseSelfClosingAttr(response, "<DUR ", "minutes")) {
            try { total += Integer.parseInt(v.trim()); } catch (NumberFormatException ignore) {}
        }
        return Math.max(0, Math.min(1440, total));
    }

    /** <NO_HOPE/> — GM이 '도주·해결·생존 가망 완전 소멸'을 선언(#2 자동 배드엔딩 신호). 있으면 true. */
    public boolean parseNoHope(String response) {
        return response != null && response.contains("<NO_HOPE");
    }

    /** <SUMMON reason="…"/> — 즉시 소집(#151 §2.2-5): 임박 사건·전투·피격에 비동기(busy) 인원을 전원 자유화.
     *  reason 목록(속성 없는 <SUMMON/> 형태도 1회 소집으로 인정). 없으면 빈 리스트. */
    public java.util.List<String> parseSummonTags(String response) {
        if (response == null || !response.contains("<SUMMON")) return java.util.Collections.emptyList();
        java.util.List<String> reasons = parseSelfClosingAttr(response, "<SUMMON ", "reason");
        return reasons.isEmpty() ? java.util.List.of("") : reasons; // <SUMMON/>(속성 없음)도 소집으로
    }

    /** <PACE mode="slow|normal|fast"/> — 완급(#151 §2.2-4). 마지막 지정 모드(소문자) 반환, 없으면 null.
     *  slow=중요 순간 시간이 천천히(같은 행동이 더 적은 분 소모 → 상대적으로 여러 행동). */
    public String parsePace(String response) {
        java.util.List<String> ms = parseSelfClosingAttr(response, "<PACE ", "mode");
        return ms.isEmpty() ? null : ms.get(ms.size() - 1).trim().toLowerCase();
    }

    /** <EVENT_BLOCK id="X"/> 모두 파싱 → [id, ...] */
    public java.util.List<String> parseEventBlockTags(String response) {
        return parseSelfClosingAttr(response, "<EVENT_BLOCK ", "id");
    }

    /** <EVENT_TRIGGER id="X"/> 모두 파싱 → [id, ...] (분기로 특정 사건 즉시 발화) */
    public java.util.List<String> parseEventTriggerTags(String response) {
        return parseSelfClosingAttr(response, "<EVENT_TRIGGER ", "id");
    }

    /** <ZONE_SEAL zone="X"/> — 런타임 구역 봉쇄(#180). zone_id 목록. */
    public java.util.List<String> parseZoneSealTags(String response) {
        return parseSelfClosingAttr(response, "<ZONE_SEAL ", "zone");
    }
    /** <ZONE_UNSEAL zone="X"/> — 봉쇄 해제(#180). */
    public java.util.List<String> parseZoneUnsealTags(String response) {
        return parseSelfClosingAttr(response, "<ZONE_UNSEAL ", "zone");
    }

    /** <COMM_BLOCK medium="X"/> — 통신 매체 차단(#180). medium: voice/text/signal/electronic/all. */
    public java.util.List<String> parseCommBlockTags(String response) {
        return parseSelfClosingAttr(response, "<COMM_BLOCK ", "medium");
    }
    /** <COMM_UNBLOCK medium="X"/> — 매체 차단 해제(#180). */
    public java.util.List<String> parseCommUnblockTags(String response) {
        return parseSelfClosingAttr(response, "<COMM_UNBLOCK ", "medium");
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
