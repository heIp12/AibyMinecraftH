package heipsys.trpg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import heipsys.trpg.model.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * TRPG 세션 인메모리 상태 관리.
 * 오염도는 세션 재시작 시에도 유지되며, 파일은 항상 원본을 로드한다.
 */
public class GameStateManager {

    // ──────────────────────────────────────────────────────────────
    //  오염 데이터 (내부 클래스)
    // ──────────────────────────────────────────────────────────────

    public static class CorruptionData {
        public int          level    = 0;
        public int          attempts = 0;
        public List<String> entityMemory = new ArrayList<>();
        /** 괴담이 학습한 플레이어별 말투·행동 (이름 → 관찰 기록) */
        public Map<String, List<String>> playerProfiles = new HashMap<>();

        public void onRetry(int maxLevel) {
            attempts++;
            level = calcLevel(maxLevel);
        }

        /** 재도전 2회당 오염 1단계, 상한은 시나리오 규모별 최고 단계(CODE-17)에 맞춘다(기본 4). */
        private int calcLevel(int maxLevel) {
            if (attempts == 0) return 0;
            return Math.min(maxLevel, (attempts + 1) / 2);
        }

        /** 다음 스테이지 이동 시 부분 리셋 — entity 메모리·오염도만, 플레이어 프로파일 유지 */
        public void resetForNewStage() {
            level = 0;
            attempts = 0;
            entityMemory.clear();
        }

        /** 세션 완전 종료 시 전체 리셋 */
        public void reset() {
            level = 0;
            attempts = 0;
            entityMemory.clear();
            playerProfiles.clear();
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  상태 필드
    // ──────────────────────────────────────────────────────────────

    private boolean sessionActive       = false;
    private int     roomNumber          = 1;
    private int     timelineStage       = 0; // 0 = 일상 파트
    private int     turnsSinceAdvance   = 0; // 마지막 타임라인 진행 이후 경과 턴 수
    private int     dailyTurnsLeft    = 5;
    private int     currentTurn       = 0;
    private boolean dailyPhase        = true;
    private String  currentSeed       = "";

    // --- v2 절대 시계 (start_time 미지정 시 비활성: clockMinutes < 0) ---
    private int     clockStart         = -1;    // 시작 시각(분)
    private int     clockMinutes       = -1;    // 현재 시각(분, 시작부터 누적 — 자정 넘기면 1440 초과)
    private int     clockEnd           = -1;    // 종료 시각(분, 시작 기준 누적; start 이하이면 +1440)
    private int     minutesPerTurn     = 15;    // 공포 파트 1턴당 진행 분
    private int     turnMode           = 1;     // ★#151★ 0=고정(턴당 고정 분) / 1=가변(행동 DUR로 시계 진행, ★기본값★) / 2=비동기 busy. 세션 시작 시 래치, 세이브 포함.
    private boolean timeVisibleDefault = true;  // 이 방에서 기본적으로 시간 인지 가능 여부
    private boolean endEventFired      = false; // 종료 사건/제한 시각 도달 여부
    private final Set<String>       firedEvents       = new HashSet<>();
    private final Set<String>       blockedEvents     = new HashSet<>();
    private final Set<String>       sealedZones       = new HashSet<>(); // ★런타임 봉쇄(#180)★ — 괴담·사건이 막은 구역(자발 진입 차단, 강제이동은 통과).
    private final Set<String>       blockedMedia      = new HashSet<>(); // ★매체별 차단(#180)★ — 괴담·사건이 막은 통신 수단(voice/text/signal/electronic, all=전부).
    private final List<String>      justFiredEvents   = new ArrayList<>();
    private boolean                 combatEventFired  = false; // ★A3/A4★ combat:true 사건 발화 감지(소비성 — 스냅샷 미포함)
    private String                  lastFiredEventLabel = ""; // 가장 최근 발화한 핵심 사건 이름(상태창 '최근' 패널용, 소비 안 됨)
    private final Map<UUID,Boolean> timeKnownOverride = new HashMap<>();

    // ──────────────────────────────────────────────────────────────
    //  ★괴담 세력 게이지 (위협도·분노도)★ — 판 안 누적 상태. 세이브 포함.
    //  threat(위협도): 느린 래칫 — 구조적 먹이(금기위반·함정·전파·사망·정체)로 상승, 파훼 진척으로만 배수.
    //    90+ = 정석 클리어 잠금(탈출·생존으로 선회).
    //  anger(분노도): 빠른 휘발 — 직접 도발(공격·조롱·성역침범)로 상승, 턴당 감쇠. 90+ = 규칙 무시 표적 살해(붕괴 결합).
    //  ★플레이어 비노출(GM 전용·뷰어 감사용)★.
    // ──────────────────────────────────────────────────────────────
    private int    threat      = 0;   // 0~100
    private int    anger       = 0;   // 0~100
    private String angerTarget = "";  // 분노 표적(캐릭터/계정명) — 규칙파괴 살해 대상

    private JsonObject gdamData = null;

    private final CorruptionData                 corruption = new CorruptionData();
    private final Map<UUID, PlayerData>          players    = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<String>                   activeNpcs = new ArrayList<>();
    private final List<String>                   discoveredClues = new ArrayList<>();
    private final List<String>                   foundItems = new ArrayList<>();
    /** 엔딩 공개용: 플레이 중 실제로 알아낸 정식 사실 키 (예: "identity", "weakness:<항목>", "solution", "name") */
    private final java.util.Set<String> discoveredFacts = new java.util.HashSet<>();
    /** 아이템 Phase II: 열쇠·도구 등으로 해제된 구역 zone_id 집합 (재도전·다음 방에서 초기화) */
    private final java.util.Set<String> unlockedZones = new java.util.HashSet<>();
    private final List<EventLogEntry>            eventLog   = Collections.synchronizedList(new ArrayList<>());
    // 캠페인(전 스테이지) 로그 — eventLog는 스테이지마다 비워지므로, 게임 종료 '전 스테이지 총평'용으로 따로 누적(최근 300개 캡). 새 게임 시작 시만 비운다.
    private final List<EventLogEntry>            campaignLog = Collections.synchronizedList(new ArrayList<>());
    /** 등장(spawn) 여부 판별 — TRPGGameManager의 spawnedPlayers를 단일 출처로 주입. 미설정이면 전원 등장으로 간주. */
    private java.util.function.Predicate<UUID> spawnedCheck = u -> true;
    public void setSpawnedCheck(java.util.function.Predicate<UUID> c) { if (c != null) spawnedCheck = c; }

    // ──────────────────────────────────────────────────────────────
    //  세션 라이프사이클
    // ──────────────────────────────────────────────────────────────

    public void startSession(int room, String seed, JsonObject gdam) {
        sessionActive  = true;
        roomNumber     = room;
        currentSeed    = seed;
        gdamData          = gdam;
        timelineStage     = 0;
        turnsSinceAdvance = 0;
        currentTurn       = 0;
        dailyPhase        = true;
        players.clear();
        activeNpcs.clear();
        discoveredClues.clear();
        foundItems.clear();
        discoveredFacts.clear();
        unlockedZones.clear();
        eventLog.clear();
        campaignLog.clear(); // 새 게임 시작 — 캠페인(전 스테이지) 로그도 초기화
        threat = 0; anger = 0; angerTarget = ""; // 괴담 세력 게이지 초기화
        loadTimelineConfig(gdam);
    }

    public void endSession(boolean resetCorruption) {
        sessionActive = false;
        if (resetCorruption) corruption.reset();
    }

    /** 다음 스테이지로 이동: 스테이지 번호/씨드/gdam 업데이트. 플레이어 데이터는 호출자가 clearRoleData()로 처리. */
    public void advanceToNextRoom(int nextRoom, String seed, JsonObject gdam) {
        roomNumber     = nextRoom;
        currentSeed    = seed;
        gdamData          = gdam;
        timelineStage     = 0;
        turnsSinceAdvance = 0;
        currentTurn       = 0;
        dailyPhase        = true;
        discoveredClues.clear();
        foundItems.clear();
        discoveredFacts.clear();
        unlockedZones.clear();
        archiveStageLog();   // 스테이지 전환 — 이번 스테이지 로그를 캠페인 로그에 보관 후 비움
        eventLog.clear();
        threat = 0; anger = 0; angerTarget = ""; // 새 스테이지 — 세력 게이지 초기화
        loadTimelineConfig(gdam);
    }

    /** 재도전: 오염도 상승, 플레이어 상태 리셋, 파일은 다시 로드 */
    public void onRetry() {
        corruption.onRetry(getMaxCorruptionLevel());
        timelineStage     = 0;
        turnsSinceAdvance = 0;
        currentTurn       = 0;
        dailyPhase        = true;
        discoveredClues.clear();
        foundItems.clear();
        discoveredFacts.clear();
        unlockedZones.clear();
        archiveStageLog();   // 재도전 — 이번 시도 로그도 캠페인 로그에 보관
        eventLog.clear();
        // 괴담이 지난 시도를 기억해 더 사납게 시작 — 위협도 시작 바닥을 오염도에서 시드(분노는 초기화).
        threat = retryThreatFloor(); anger = 0; angerTarget = "";
        loadTimelineConfig(gdamData);
        // 재도전 시에도 영구(비배역) 특성의 스탯 보정을 복원한다 — clearRoleData(다음 스테이지)와 동일한 불변식.
        // (resetToBase만 하면 클리어 보상 특성의 str/hp 등이 재도전마다 사라진다.)
        getAllPlayers().forEach(pd -> { pd.resetToBase(); pd.reapplyTraitStats(); });
    }

    // ──────────────────────────────────────────────────────────────
    //  세이브 / 로드 (전체 상태 스냅샷 — 자동 저장·이어하기용)
    // ──────────────────────────────────────────────────────────────
    private static final com.google.gson.Gson SNAP_GSON = new com.google.gson.Gson();

    /** 현재 게임 상태 전체를 JSON으로 직렬화(시나리오·진행도·오염도·플레이어 전부). */
    public JsonObject snapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("sessionActive", sessionActive);
        o.addProperty("roomNumber", roomNumber);
        o.addProperty("timelineStage", timelineStage);
        o.addProperty("turnsSinceAdvance", turnsSinceAdvance);
        o.addProperty("dailyTurnsLeft", dailyTurnsLeft);
        o.addProperty("currentTurn", currentTurn);
        o.addProperty("dailyPhase", dailyPhase);
        o.addProperty("currentSeed", currentSeed);
        o.addProperty("clockStart", clockStart);
        o.addProperty("clockMinutes", clockMinutes);
        o.addProperty("clockEnd", clockEnd);
        o.addProperty("minutesPerTurn", minutesPerTurn);
        o.addProperty("turnMode", turnMode);
        o.addProperty("timeVisibleDefault", timeVisibleDefault);
        o.addProperty("endEventFired", endEventFired);
        o.addProperty("lastFiredEventLabel", lastFiredEventLabel);
        o.addProperty("threat", threat);
        o.addProperty("anger", anger);
        o.addProperty("angerTarget", angerTarget);
        if (gdamData != null) o.add("gdam", gdamData);
        o.add("firedEvents", SNAP_GSON.toJsonTree(firedEvents));
        o.add("blockedEvents", SNAP_GSON.toJsonTree(blockedEvents));
        o.add("discoveredClues", SNAP_GSON.toJsonTree(discoveredClues));
        o.add("foundItems", SNAP_GSON.toJsonTree(foundItems));
        o.add("discoveredFacts", SNAP_GSON.toJsonTree(discoveredFacts));
        o.add("unlockedZones", SNAP_GSON.toJsonTree(unlockedZones));
        o.add("sealedZones", SNAP_GSON.toJsonTree(sealedZones));    // ★#180 §6-4★ 런타임 구역 봉쇄 영속(이어하기 유지)
        o.add("blockedMedia", SNAP_GSON.toJsonTree(blockedMedia));  // ★#180 §6-4★ 매체별 통신 차단 영속(이어하기 유지)
        o.add("activeNpcs", SNAP_GSON.toJsonTree(activeNpcs));
        o.add("corruption", SNAP_GSON.toJsonTree(corruption));
        JsonObject ps = new JsonObject();
        for (PlayerData pd : getAllPlayers())
            ps.add(pd.uuid.toString(), SNAP_GSON.toJsonTree(pd));
        o.add("players", ps);
        synchronized (eventLog) { o.add("eventLog", SNAP_GSON.toJsonTree(eventLog)); } // 종료 평가·최근 장면 맥락
        synchronized (campaignLog) { o.add("campaignLog", SNAP_GSON.toJsonTree(campaignLog)); } // 전 스테이지 총평 로그(이어하기 시 보존)
        JsonObject tko = new JsonObject();
        timeKnownOverride.forEach((u, b) -> tko.addProperty(u.toString(), b));
        o.add("timeKnownOverride", tko); // 플레이어별 시간 인지 토글(GM TIME_VISIBLE)
        return o;
    }

    /** snapshot()으로 저장한 상태를 복원(이어하기). */
    public void restore(JsonObject o) {
        if (o == null) return;
        sessionActive     = snapB(o, "sessionActive", true);
        roomNumber        = snapI(o, "roomNumber", 1);
        timelineStage     = snapI(o, "timelineStage", 0);
        turnsSinceAdvance = snapI(o, "turnsSinceAdvance", 0);
        dailyTurnsLeft    = snapI(o, "dailyTurnsLeft", 5);
        currentTurn       = snapI(o, "currentTurn", 0);
        dailyPhase        = snapB(o, "dailyPhase", true);
        currentSeed       = snapS(o, "currentSeed", "");
        clockStart        = snapI(o, "clockStart", -1);
        clockMinutes      = snapI(o, "clockMinutes", -1);
        clockEnd          = snapI(o, "clockEnd", -1);
        minutesPerTurn    = snapI(o, "minutesPerTurn", 15);
        turnMode          = snapI(o, "turnMode", 1); // 구형 세이브(필드 없음)도 가변 시간 기본값으로
        timeVisibleDefault = snapB(o, "timeVisibleDefault", true);
        endEventFired     = snapB(o, "endEventFired", false);
        lastFiredEventLabel = snapS(o, "lastFiredEventLabel", "");
        threat            = snapI(o, "threat", 0);
        anger             = snapI(o, "anger", 0);
        angerTarget       = snapS(o, "angerTarget", "");
        if (o.has("gdam") && o.get("gdam").isJsonObject()) gdamData = o.getAsJsonObject("gdam");
        snapStrInto(firedEvents, o, "firedEvents");
        snapStrInto(blockedEvents, o, "blockedEvents");
        snapStrInto(discoveredClues, o, "discoveredClues");
        snapStrInto(foundItems, o, "foundItems");
        snapStrInto(discoveredFacts, o, "discoveredFacts");
        snapStrInto(unlockedZones, o, "unlockedZones");
        snapStrInto(sealedZones, o, "sealedZones");   // ★#180 §6-4★ 런타임 봉쇄 복원
        snapStrInto(blockedMedia, o, "blockedMedia"); // ★#180 §6-4★ 매체 차단 복원
        snapStrInto(activeNpcs, o, "activeNpcs");
        if (o.has("corruption")) {
            CorruptionData c = SNAP_GSON.fromJson(o.get("corruption"), CorruptionData.class);
            if (c != null) {
                corruption.level = c.level; corruption.attempts = c.attempts;
                if (c.entityMemory != null)   corruption.entityMemory = c.entityMemory;
                if (c.playerProfiles != null) corruption.playerProfiles = c.playerProfiles;
            }
        }
        players.clear();
        if (o.has("players") && o.get("players").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("players").entrySet()) {
                try {
                    PlayerData pd = SNAP_GSON.fromJson(e.getValue(), PlayerData.class);
                    if (pd != null && pd.uuid != null) players.put(pd.uuid, pd);
                } catch (Exception ignore) {}
            }
        }
        synchronized (eventLog) {
            eventLog.clear();
            if (o.has("eventLog") && o.get("eventLog").isJsonArray()) {
                EventLogEntry[] arr = SNAP_GSON.fromJson(o.get("eventLog"), EventLogEntry[].class);
                if (arr != null) for (EventLogEntry el : arr) if (el != null) eventLog.add(el);
            }
        }
        synchronized (campaignLog) {
            campaignLog.clear();
            if (o.has("campaignLog") && o.get("campaignLog").isJsonArray()) {
                EventLogEntry[] arr = SNAP_GSON.fromJson(o.get("campaignLog"), EventLogEntry[].class);
                if (arr != null) for (EventLogEntry el : arr) if (el != null) campaignLog.add(el);
            }
        }
        justFiredEvents.clear(); // 소비성 버퍼(스냅샷에 없음) — 재사용 인스턴스에 이전 사건이 잔류하지 않도록 정리
        timeKnownOverride.clear();
        if (o.has("timeKnownOverride") && o.get("timeKnownOverride").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("timeKnownOverride").entrySet()) {
                try { timeKnownOverride.put(UUID.fromString(e.getKey()), e.getValue().getAsBoolean()); } catch (Exception ignore) {}
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  괴담 세력 게이지 접근/조정 (위협도·분노도)
    // ──────────────────────────────────────────────────────────────
    public int    getThreat()      { return threat; }
    public int    getAnger()       { return anger; }
    public String getAngerTarget() { return angerTarget; }
    /** 위협도 가감(0~100 클램프). 반환=적용 후 값. */
    public int adjustThreat(int delta) { threat = Math.max(0, Math.min(100, threat + delta)); return threat; }
    /** 분노도 가감(0~100 클램프). target이 비어있지 않으면 표적 갱신, 0으로 떨어지면 표적 해제. */
    public int adjustAnger(int delta, String target) {
        anger = Math.max(0, Math.min(100, anger + delta));
        if (target != null && !target.isBlank()) angerTarget = target;
        if (anger <= 0) angerTarget = "";
        return anger;
    }
    public void setThreat(int v) { threat = Math.max(0, Math.min(100, v)); }
    public void setAnger(int v)  { anger  = Math.max(0, Math.min(100, v)); if (anger <= 0) angerTarget = ""; }
    /** 턴당 분노 자연 감쇠(직접 도발이 없을 때 호출). 반환=적용 후 값. */
    public int decayAnger(int amount) { anger = Math.max(0, anger - Math.max(0, amount)); if (anger == 0) angerTarget = ""; return anger; }
    /** 재도전 시 위협도 시작 바닥 — 괴담이 지난 시도를 기억해 더 사납게 시작(오염도 비례, 상한 40). */
    private int retryThreatFloor() { return Math.min(40, corruption.level * 8); }

    private static boolean snapB(JsonObject o, String k, boolean d) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : d; }
    private static int     snapI(JsonObject o, String k, int d)     { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : d; }
    private static String  snapS(JsonObject o, String k, String d)  { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d; }
    private static void snapStrInto(Collection<String> col, JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return;
        col.clear();
        for (JsonElement el : o.getAsJsonArray(k)) if (!el.isJsonNull()) col.add(el.getAsString());
    }

    // ──────────────────────────────────────────────────────────────
    //  플레이어
    // ──────────────────────────────────────────────────────────────

    public void addPlayer(PlayerData pd)            { players.put(pd.uuid, pd); }
    public PlayerData getPlayer(UUID uuid)          { return players.get(uuid); }
    public PlayerData getPlayer(Player p)           { return players.get(p.getUniqueId()); }
    public boolean    hasPlayer(UUID uuid)          { return players.containsKey(uuid); }
    // 방어 복사본 반환 — AI 완료 스레드가 순회하는 동안 메인 스레드가 put/clear 해도 CME가 나지 않게(순회는 락 없이 스냅샷으로).
    public Collection<PlayerData> getAllPlayers()   { synchronized (players) { return new ArrayList<>(players.values()); } }

    public int getAliveCount() {
        // 동물 형태(revive_as_animal)는 정상 행동·해결이 불가하므로 생존자로 세지 않는다
        // (동물만 남으면 사실상 패배 → 배드엔딩·워치독이 정상 작동하도록).
        return (int) getAllPlayers().stream().filter(p -> !p.isDead && !"animal".equals(p.status)).count();
    }
    public int getTotalCount() { return players.size(); }

    // ──────────────────────────────────────────────────────────────
    //  타임라인
    // ──────────────────────────────────────────────────────────────

    public void advanceTimeline(int stages) {
        if (dailyPhase) return;
        if (stages > 0) turnsSinceAdvance = 0;
        timelineStage = Math.max(0, Math.min(getMaxStage(), timelineStage + stages));
    }

    /** CODE-17: seed timeline의 단계 수(연속 숫자 키 1..N). 규모별 가변. 없으면 기본 4. */
    public int getMaxStage() {
        if (gdamData != null && gdamData.has("timeline") && gdamData.get("timeline").isJsonObject()) {
            JsonObject tl = gdamData.getAsJsonObject("timeline");
            int n = 0;
            while (tl.has(String.valueOf(n + 1))) n++;
            if (n >= 1) return n;
        }
        return 4;
    }

    /** CODE-17: corruption_behavior의 최고 단계(키 0..N-1 중 최대값). 규모별 가변. 없으면 기본 4. */
    public int getMaxCorruptionLevel() {
        if (gdamData != null && gdamData.has("entity") && gdamData.get("entity").isJsonObject()) {
            JsonObject ent = gdamData.getAsJsonObject("entity");
            if (ent.has("ai_context") && ent.get("ai_context").isJsonObject()) {
                JsonObject ai = ent.getAsJsonObject("ai_context");
                if (ai.has("corruption_behavior") && ai.get("corruption_behavior").isJsonObject()) {
                    int max = -1;
                    for (String k : ai.getAsJsonObject("corruption_behavior").keySet()) {
                        try { max = Math.max(max, Integer.parseInt(k.trim())); } catch (Exception ignore) {}
                    }
                    if (max >= 1) return max;
                }
            }
        }
        return 4;
    }

    /** 타임라인이 진행되지 않은 턴을 누적한다. 3회 초과 시 자동 1단계 진행. */
    public boolean tickStagnation() {
        if (dailyPhase || timelineStage >= getMaxStage()) return false;
        turnsSinceAdvance++;
        if (turnsSinceAdvance >= 3) {
            timelineStage = Math.min(getMaxStage(), timelineStage + 1);
            turnsSinceAdvance = 0;
            return true; // 자동 진행 발생
        }
        return false;
    }

    /**
     * 절대 시계 진행도에 맞춰 추상 단계(1~4)를 최소 보장한다.
     * 시간이 흐르면 단계도 함께 흐르게 하여 '타임라인이 안 흐르는' 문제를 해소한다.
     * (0~25% → 1, 25~50% → 2, 50~75% → 3, 75~100% → 4)
     */
    private void syncStageToClock() {
        if (clockStart < 0 || clockEnd <= clockStart) return;
        double progress = (double) (clockMinutes - clockStart) / (clockEnd - clockStart);
        if (progress < 0) progress = 0;
        if (progress > 1) progress = 1;
        int max = getMaxStage();                                 // CODE-17: 단계 수 가변
        int target = 1 + (int) Math.floor(progress * max);
        if (target > max) target = max;
        if (target > timelineStage) {
            timelineStage     = target;
            turnsSinceAdvance = 0;
        }
    }

    /**
     * 워치독(무행동 가속)용: 플레이어 행동 없이 시간만 진행시킨다.
     * 시계가 있으면 1턴분 가속 + 도래 사건 발화, 없으면 추상 단계만 1 올린다.
     * @return 단계 또는 발화 사건이 변했으면 true
     */
    public boolean idleAdvance() {
        if (dailyPhase) return false;
        int beforeStage = timelineStage;
        int beforeFired = justFiredEvents.size();
        if (clockMinutes >= 0) {
            clockMinutes += minutesPerTurn;
            fireDueEvents();
            syncStageToClock();
        } else {
            timelineStage = Math.min(getMaxStage(), timelineStage + 1);
            if (timelineStage != beforeStage) turnsSinceAdvance = 0;
        }
        return timelineStage != beforeStage || justFiredEvents.size() != beforeFired;
    }

    public int getTurnsSinceAdvance() { return turnsSinceAdvance; }

    /** 일상 턴 소비. 0이 되면 괴담 파트 시작. true 반환 시 파트 전환 */
    public boolean consumeDailyTurn() {
        if (!dailyPhase) return false;
        if (dailyTurnsLeft <= 1) {
            dailyTurnsLeft = 0;
            dailyPhase     = false;
            timelineStage  = 1;
            return true;
        }
        dailyTurnsLeft--;
        return false;
    }

    public void forceStartHorrorPhase() {
        dailyPhase    = false;
        if (timelineStage == 0) timelineStage = 1;
    }

    // ──────────────────────────────────────────────────────────────
    //  v2 절대 시계 (타임라인 엔진)
    // ──────────────────────────────────────────────────────────────

    /** .gdam timeline 설정 로드 — 3개 라이프사이클에서 공통 사용 */
    private void loadTimelineConfig(JsonObject gdam) {
        firedEvents.clear();
        blockedEvents.clear();
        sealedZones.clear(); // 런타임 봉쇄(#180) — 새 시나리오/스테이지 초기화
        blockedMedia.clear(); // 매체별 차단(#180) — 새 시나리오/스테이지 초기화
        justFiredEvents.clear();
        lastFiredEventLabel = ""; // 새 시나리오/스테이지 — 최근 사건 초기화
        timeKnownOverride.clear();
        endEventFired      = false;
        combatEventFired   = false; // ★A3/A4★ 소비성 플래그도 새 시나리오/스테이지에서 초기화
        clockStart         = -1;
        clockMinutes       = -1;
        clockEnd           = -1;
        minutesPerTurn     = 15;
        timeVisibleDefault = true;
        dailyTurnsLeft     = 5;
        if (gdam == null || !gdam.has("timeline")) return;
        JsonObject tl = gdam.getAsJsonObject("timeline");
        if (tl.has("daily_turns"))      dailyTurnsLeft     = tl.get("daily_turns").getAsInt();
        if (tl.has("minutes_per_turn")) minutesPerTurn     = Math.max(1, tl.get("minutes_per_turn").getAsInt());
        if (tl.has("time_visible"))     timeVisibleDefault = tl.get("time_visible").getAsBoolean();
        if (tl.has("start_time")) {
            clockStart   = parseHhmm(tl.get("start_time").getAsString());
            clockMinutes = clockStart;
        }
        if (tl.has("end_time")) {
            int e = parseHhmm(tl.get("end_time").getAsString());
            if (e >= 0 && clockStart >= 0 && e <= clockStart) e += 24 * 60; // 자정 넘김
            clockEnd = e;
        }
    }

    /** "HH:MM" → 자정 기준 분(0~1439). 실패 시 -1. */
    private int parseHhmm(String s) {
        if (s == null) return -1;
        try {
            String[] p = s.trim().split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            return ((h % 24) * 60 + m + 1440) % 1440;
        } catch (Exception e) { return -1; }
    }

    /** 매 턴(nextTurn) 호출: 공포 파트에서 시계 진행 + 도래 사건 발화 */
    private void tickClock() {
        if (dailyPhase || clockMinutes < 0) return;
        clockMinutes += minutesPerTurn;
        fireDueEvents();
        syncStageToClock(); // 정상 턴에서도 시계 진행에 맞춰 추상 단계를 최소 보장(idleAdvance와 동일 정렬)
    }

    /** ★#151 §8.1★ afterMin(현재분) '이후' 아직 발화 안 한 가장 이른 main_event의 절대 분. 없으면 -1.
     *  비동기 busy 시계 점프가 다음 사건을 건너뛰지 않고 그 '직전'에서 멈춰 반응 턴을 주게 하는 데 쓴다. */
    public int nextDueEventMinute(int afterMin) {
        if (gdamData == null || !gdamData.has("timeline")) return -1;
        JsonObject tl = gdamData.getAsJsonObject("timeline");
        if (!tl.has("main_events") || !tl.get("main_events").isJsonArray()) return -1;
        int best = Integer.MAX_VALUE;
        for (JsonElement el : tl.getAsJsonArray("main_events")) {
            if (!el.isJsonObject()) continue;
            JsonObject ev = el.getAsJsonObject();
            String id = ev.has("id") ? ev.get("id").getAsString() : "";
            if (id.isEmpty() || firedEvents.contains(id) || blockedEvents.contains(id)) continue;
            if (!ev.has("time")) continue;
            int when = parseHhmm(ev.get("time").getAsString());
            if (when < 0) continue;
            if (clockStart >= 0 && when < clockStart) when += 1440; // 자정 넘김
            if (when > afterMin) best = Math.min(best, when);
        }
        // ★코드리뷰★ fireDueEvents는 clockEnd(제한 시각) 도달도 '종료 사건'으로 발화한다 — 점프 캡이 그 직전에도
        //   멈춰 '마지막 반응 턴'을 주도록 clockEnd도 후보에 포함(아니면 마감을 훌쩍 넘겨 점프).
        if (clockEnd >= 0 && !endEventFired && clockEnd > afterMin) best = Math.min(best, clockEnd);
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /** 현재 시각에 도달한 main_events를 1회씩 발화하여 justFiredEvents에 누적 */
    private void fireDueEvents() {
        if (gdamData == null || !gdamData.has("timeline")) return;
        JsonObject tl = gdamData.getAsJsonObject("timeline");
        if (!tl.has("main_events") || !tl.get("main_events").isJsonArray()) return;
        for (JsonElement el : tl.getAsJsonArray("main_events")) {
            if (!el.isJsonObject()) continue;
            JsonObject ev = el.getAsJsonObject();
            String id = ev.has("id") ? ev.get("id").getAsString() : "";
            if (id.isEmpty() || firedEvents.contains(id) || blockedEvents.contains(id)) continue;
            if (!ev.has("time")) continue;
            int when = parseHhmm(ev.get("time").getAsString());
            if (when < 0) continue;
            if (clockStart >= 0 && when < clockStart) when += 1440; // 자정 넘김
            if (when > clockMinutes) continue;                       // 아직 시각 미도달
            firedEvents.add(id);
            String label  = ev.has("label")  ? ev.get("label").getAsString()  : id;
            String effect = ev.has("effect") ? ev.get("effect").getAsString() : "";
            justFiredEvents.add(label + (effect.isEmpty() ? "" : " — " + effect));
            lastFiredEventLabel = label; // 상태창 '최근' 패널용(짧은 사건 이름)
            if (ev.has("is_end") && ev.get("is_end").getAsBoolean()) {
                endEventFired = true;
                timelineStage = getMaxStage(); // CODE-17: 종료 사건 → 최고 단계(가변)
            }
            if (ev.has("combat") && !ev.get("combat").isJsonNull() && ev.get("combat").getAsBoolean())
                combatEventFired = true; // ★A3/A4★ 전투 사건 발화 → 코드가 자동 소집·완급(소비성)
        }
        if (clockEnd >= 0 && clockMinutes >= clockEnd && !endEventFired) {
            endEventFired = true;
            timelineStage = getMaxStage();
            justFiredEvents.add("제한 시각 도달 — 상황이 종국으로 치닫는다");
            lastFiredEventLabel = "제한 시각 도달";
        }
    }

    public boolean isClockActive()   { return clockMinutes >= 0; }
    public boolean isEndEventFired() { return endEventFired; }
    /** ★A3/A4★ combat:true 사건이 방금 발화됐는지(읽으면 리셋) — 전투 자동 소집·완급용. */
    public boolean consumeCombatEventFired() { boolean b = combatEventFired; combatEventFired = false; return b; }

    /** 현재 인게임 시각. 첫날이면 "HH:MM", 여러 날(60일 미만)이면 "N일차 HH:MM",
     *  장기 도약(60일 이상)이면 "N년 M개월 D일차 HH:MM"로 압축 표시한다. 시계 없으면 "".
     *  ★환산은 1개월=30일·1년=12개월(=360일)로 내부 정합★ — 예전엔 1년=365·1개월=30일이 서로
     *  안 맞아 개월이 12로 넘쳐 "12개월 …"이 뜨던 표기 오류가 있었다(365%30 잔여가 최대 12개월). */
    public String getCurrentTimeString() {
        if (clockMinutes < 0) return "";
        int m = ((clockMinutes % 1440) + 1440) % 1440;
        String hhmm = String.format("%02d:%02d", m / 60, m % 60);
        int dayIdx = (clockStart >= 0 ? clockMinutes - clockStart : clockMinutes) / 1440; // 0=첫날
        if (dayIdx <= 0) return hhmm;
        if (dayIdx < 60)  return (dayIdx + 1) + "일차 " + hhmm;      // ~2개월 미만은 종전대로 "N일차"
        int years  = dayIdx / 360;                                    // 1년=12개월×30일=360일(개월 오버플로 방지)
        int months = (dayIdx % 360) / 30;                             // 0~11로 정확히 떨어짐
        int days   = (dayIdx % 360) % 30;
        StringBuilder sb = new StringBuilder();
        if (years  > 0) sb.append(years).append("년 ");
        if (months > 0) sb.append(months).append("개월 ");
        return sb.append(days + 1).append("일차 ").append(hhmm).toString();
    }

    /** 이 플레이어가 현재 시간을 알 수 있는가 (override > 방 기본값) */
    public boolean isTimeKnown(PlayerData pd) {
        if (clockMinutes < 0) return false;
        Boolean o = (pd == null) ? null : timeKnownOverride.get(pd.uuid);
        return o != null ? o : timeVisibleDefault;
    }

    /** GM TIME_VISIBLE: 특정 플레이어의 시간 인지 여부 토글 */
    public void setTimeKnown(String playerName, boolean known) {
        if (playerName == null) return;
        getAllPlayers().stream()
            .filter(p -> p.name.equals(playerName) || playerName.equals(p.charName))
            .findFirst()
            .ifPresent(p -> timeKnownOverride.put(p.uuid, known));
    }

    /** GM TIME_SKIP: 시간을 건너뛰고 그 사이 사건을 발화 */
    public void skipTime(int minutes) {
        if (clockMinutes < 0 || minutes <= 0) return;
        clockMinutes += minutes;
        fireDueEvents();
    }

    /** GM EVENT_BLOCK: 해당 사건을 취소(발화하지 않음) */
    public void blockEvent(String id) {
        if (id != null && !id.isBlank()) blockedEvents.add(id.trim());
    }

    // ── 런타임 봉쇄(#180): 괴담·사건이 구역/통로를 막음 ──────────────
    /** 구역 봉쇄 — 자발 진입 차단(강제이동은 통과). */
    public void sealZone(String zoneId)   { if (zoneId != null && !zoneId.isBlank()) sealedZones.add(zoneId.trim()); }
    /** 봉쇄 해제. */
    public void unsealZone(String zoneId) { if (zoneId != null) sealedZones.remove(zoneId.trim()); }
    public boolean isZoneSealed(String zoneId) { return zoneId != null && sealedZones.contains(zoneId.trim()); }
    public Set<String> getSealedZones() { return new HashSet<>(sealedZones); }

    /** 통신 매체 차단(#180) — voice/text/signal/electronic, "all"=전부. */
    public void blockMedium(String medium)   { if (medium != null && !medium.isBlank()) blockedMedia.add(medium.trim().toLowerCase()); }
    public void unblockMedium(String medium) { if (medium != null) blockedMedia.remove(medium.trim().toLowerCase()); }
    /** 그 매체가 지금 차단됐는가(개별 또는 all). */
    public boolean isMediumBlocked(String medium) {
        if (blockedMedia.isEmpty()) return false;
        return blockedMedia.contains("all") || (medium != null && blockedMedia.contains(medium.trim().toLowerCase()));
    }
    public Set<String> getBlockedMedia() { return new HashSet<>(blockedMedia); }

    /** GM EVENT_TRIGGER: 분기 등으로 특정 main_event를 즉시 발화한다(시각 미도달이어도). */
    public void triggerEvent(String id) {
        if (id == null || id.isBlank() || gdamData == null || !gdamData.has("timeline")) return;
        String tid = id.trim();
        if (firedEvents.contains(tid)) return;
        JsonObject tl = gdamData.getAsJsonObject("timeline");
        if (!tl.has("main_events") || !tl.get("main_events").isJsonArray()) return;
        for (JsonElement el : tl.getAsJsonArray("main_events")) {
            if (!el.isJsonObject()) continue;
            JsonObject ev = el.getAsJsonObject();
            String eid = ev.has("id") ? ev.get("id").getAsString() : "";
            if (!tid.equals(eid)) continue;
            firedEvents.add(tid);
            blockedEvents.remove(tid); // 차단됐어도 분기로 강제 발화 가능
            String label  = ev.has("label")  ? ev.get("label").getAsString()  : tid;
            String effect = ev.has("effect") ? ev.get("effect").getAsString() : "";
            justFiredEvents.add(label + (effect.isEmpty() ? "" : " — " + effect));
            lastFiredEventLabel = label; // 상태창 '최근' 패널용
            if (ev.has("is_end") && ev.get("is_end").getAsBoolean()) { endEventFired = true; timelineStage = getMaxStage(); }
            if (ev.has("combat") && !ev.get("combat").isJsonNull() && ev.get("combat").getAsBoolean()) combatEventFired = true; // ★A3/A4★
            return;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  턴
    // ──────────────────────────────────────────────────────────────

    public int nextTurn() {
        currentTurn++;
        if (turnMode == 0) tickClock(); // ★#151★ DUR/비동기 모드(≥1)에선 고정 진행 대신 advanceActionClock이 시계를 운전한다.
        return currentTurn;
    }
    public int getCurrentTurn()  { return currentTurn; }
    public int  getTurnMode()      { return turnMode; }
    public void setTurnMode(int m) { turnMode = (m < 0 ? 0 : (m > 2 ? 2 : m)); }

    /** ★#151 Stage A★ 행동 소요(DUR)만큼 시계를 진행 — DUR/비동기 모드에서 고정 tickClock 대신 호출.
     *  TIME_SKIP(skipTime)과 달리 syncStageToClock까지 수행(정상 진행과 동일 정렬). 일상/비활성 시계면 무효. */
    public void advanceActionClock(int minutes) {
        if (dailyPhase || clockMinutes < 0 || minutes <= 0) return;
        clockMinutes += minutes;
        fireDueEvents();
        syncStageToClock();
    }
    /** 현재 게임 내 분(절대). 시계 없으면 -1. busy 판정·점프용(#151 Stage B). */
    public int getClockMinutes() { return clockMinutes; }
    /** 일상(프롤로그) 단계 여부 — 이 동안 시계는 얼어 있다(advanceClockTo/ActionClock no-op).
     *  turnMode=2 busy 모델은 시계가 흘러야 풀리므로, 이 단계엔 busy 잠금을 적용하면 안 된다(#208). */
    public boolean isDailyPhase() { return dailyPhase; }
    /** ★#151 Stage B★ 시계를 절대 목표 분(absMin)까지 진행(뒤로는 안 감). 도래 사건 발화 + 단계 동기화. 비동기 busy 점프 전용. */
    public void advanceClockTo(int absMin) {
        if (dailyPhase || clockMinutes < 0 || absMin <= clockMinutes) return;
        clockMinutes = absMin;
        fireDueEvents();
        syncStageToClock();
    }
    /** 지금까지 발화(진행)된 타임라인/분기 사건 수 — 통합 진행도 계산용. */
    public int getFiredEventCount() { return firedEvents.size(); }
    /** 가장 최근 발화한 핵심 사건 이름(상태창 '최근' 패널용, 없으면 ""). */
    public String getLastFiredEventLabel() { return lastFiredEventLabel; }

    // ──────────────────────────────────────────────────────────────
    //  이벤트 로그
    // ──────────────────────────────────────────────────────────────

    public void log(String type, String player, String content) {
        synchronized (eventLog) {
            eventLog.add(new EventLogEntry(currentTurn, type, player, content));
        }
    }

    public List<EventLogEntry> getLog()               { return eventLog; }
    public int                 getLogSize()            { synchronized (eventLog) { return eventLog.size(); } }

    public List<EventLogEntry> getRecentLog(int n) {
        synchronized (eventLog) {
            int start = Math.max(0, eventLog.size() - n);
            return new ArrayList<>(eventLog.subList(start, eventLog.size()));
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  단서 / 아이템
    // ──────────────────────────────────────────────────────────────

    public void discoverClue(String id) { if (!discoveredClues.contains(id)) discoveredClues.add(id); }
    public void collectItem(String id)  { if (!foundItems.contains(id)) foundItems.add(id); }
    public List<String> getDiscoveredClues() { return discoveredClues; }

    // --- CODE-15: 발견 사실(엔딩 공개용) 추적 ---
    /** 정식 사실 키를 발견 처리 (null/blank 무시) */
    public void markFactDiscovered(String key) {
        if (key != null && !key.isBlank()) discoveredFacts.add(key);
    }
    /** 해당 사실이 발견되었는지 여부 */
    public boolean isFactDiscovered(String key) { return discoveredFacts.contains(key); }
    /** 발견된 정식 사실 집합 (읽기용) */
    public java.util.Set<String> getDiscoveredFacts() { return discoveredFacts; }

    // --- 아이템 Phase II: 열쇠·도구로 해제된 구역 추적 ---
    /** 구역을 해제 처리 (null/blank 무시) */
    public void markZoneUnlocked(String zone) {
        if (zone != null && !zone.isBlank()) unlockedZones.add(zone);
    }
    /** 해당 구역이 해제되었는지 여부 */
    public boolean isZoneUnlocked(String zone) { return unlockedZones.contains(zone); }
    /** 해제된 구역 집합 (읽기용) */
    public java.util.Set<String> getUnlockedZones() { return unlockedZones; }

    // ──────────────────────────────────────────────────────────────
    //  GM AI 입력 포맷 빌더
    // ──────────────────────────────────────────────────────────────

    /** zoneId → 표시용 구역명(gdam zones[].name). 없으면 zoneId 그대로. */
    public String zoneNameOf(String zoneId) {
        if (zoneId == null || zoneId.isEmpty()) return "?";
        if (gdamData != null && gdamData.has("zones") && gdamData.get("zones").isJsonArray()) {
            for (JsonElement el : gdamData.getAsJsonArray("zones")) {
                if (!el.isJsonObject()) continue;
                JsonObject z = el.getAsJsonObject();
                if (z.has("zone_id") && zoneId.equals(z.get("zone_id").getAsString()))
                    return z.has("name") ? z.get("name").getAsString() : zoneId;
            }
        }
        return zoneId;
    }

    /** 두 구역의 거리 등급: 0=같은 구역, 1=인접(연결됨), 2=멀리. gdam zones[].connections 기준. */
    private int zoneDistance(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 2;
        if (a.equals(b)) return 0;
        if (gdamData != null && gdamData.has("zones") && gdamData.get("zones").isJsonArray()) {
            for (JsonElement el : gdamData.getAsJsonArray("zones")) {
                if (!el.isJsonObject()) continue;
                JsonObject z = el.getAsJsonObject();
                if (z.has("zone_id") && a.equals(z.get("zone_id").getAsString())
                        && z.has("connections") && z.get("connections").isJsonArray()) {
                    for (JsonElement c : z.getAsJsonArray("connections"))
                        if (b.equals(c.getAsString())) return 1; // 직접 연결 = 인접
                }
            }
        }
        return 2; // 그 외 = 멀리
    }

    private static String distanceLabel(int d) {
        return d == 0 ? "같은 구역" : d == 1 ? "인접" : "멀리";
    }

    public String buildTurnInput(Player actor, String action) {
        StringBuilder sb = new StringBuilder();
        // 헤더: 필수 메타만 압축
        sb.append("T").append(currentTurn).append(" ");
        sb.append(dailyPhase ? "일상(" + dailyTurnsLeft + ")" : "공포" + timelineStage);
        if (corruption.level > 0) sb.append(" 오염").append(corruption.level);
        if (clockMinutes >= 0 && !dailyPhase) sb.append(" 시각 ").append(getCurrentTimeString());
        sb.append("\n");

        // 시계가 진행되며 도달한 큰 사건 — GM이 이번 서술에 반영 (1회 소비)
        if (!justFiredEvents.isEmpty()) {
            sb.append("지금 발생한 사건(반드시 서술에 반영):\n");
            for (String e : justFiredEvents) sb.append("  ▶ ").append(e).append("\n");
            justFiredEvents.clear();
        }

        // 행동자: 풀 스탯
        PlayerData actorData = players.get(actor.getUniqueId());
        String actorZone = (actorData != null) ? actorData.zone : "";
        if (actorData != null) {
            sb.append("행동자: ").append(actorData.toTurnLine()).append("\n");
        }

        // 동료를 같은 위치(zone)와 다른 위치로 분리한다.
        // 같은 위치 동료는 협력·상호작용이 가능하므로 직전 행동까지 함께 제공한다.
        // (사망자는 제외. 정체 차용된 플레이어는 toShortLine이 GM에게 표시하므로 포함)
        // ★아직 등장하지 않은(spawn 전) 배역은 '장면에 없는 것'으로 분리한다 — GM이 미등장 인물을 서술에 끌어들이지 않도록.
        List<PlayerData> sameZone  = new ArrayList<>();
        List<PlayerData> otherZone = new ArrayList<>();
        int notSpawnedCount = 0;
        for (PlayerData p : getAllPlayers()) {
            if (p.uuid.equals(actor.getUniqueId()) || p.isDead) continue;
            if (!spawnedCheck.test(p.uuid)) { notSpawnedCount++; continue; }
            if (!actorZone.isEmpty() && actorZone.equals(p.zone)) sameZone.add(p);
            else otherZone.add(p);
        }
        if (notSpawnedCount > 0) {
            sb.append("아직 등장하지 않은 배역 ").append(notSpawnedCount)
              .append("명 — ★이 인물들은 아직 이야기에 등장하지 않았다. 서술에 등장·언급시키지 마라(때가 되면 시스템이 등장시킨다).\n");
        }

        if (!sameZone.isEmpty()) {
            sb.append("같은 위치(협력·상호작용 가능):\n");
            for (PlayerData p : sameZone) {
                sb.append("  ").append(p.toShortLine());
                String last = lastActionOf(p.name);
                if (last != null) sb.append("  직전행동: ").append(last);
                sb.append("\n");
            }
        }
        if (!otherZone.isEmpty()) {
            StringJoiner others = new StringJoiner("  ");
            otherZone.forEach(p -> others.add(
                p.toShortLine() + "(" + zoneNameOf(p.zone) + "·" + distanceLabel(zoneDistance(actorZone, p.zone)) + ")"));
            sb.append("다른 위치 동료(거리): ").append(others).append("\n");
        }

        // 최근 이벤트 — ★현재 행동자가 지각할 수 있는(같은 위치 zone, 또는 본인) 일만 반영한다.
        // 다른 장면(다른 zone)의 플레이어 행동이 현재 플레이어 서술에 끼어드는 '장면 혼선'을 막는다.
        List<EventLogEntry> recentAll = getRecentLog(8);
        List<EventLogEntry> recent = new ArrayList<>();
        for (EventLogEntry e : recentAll) {
            if (isPerceivableEvent(e, actorZone, actor.getUniqueId())) recent.add(e);
        }
        if (recent.size() > 4) recent = recent.subList(recent.size() - 4, recent.size());
        if (!recent.isEmpty()) {
            sb.append("최근(같은 장면):");
            recent.forEach(e -> sb.append(" [").append(resolveDisplayName(e.player)).append("] ").append(e.content));
            sb.append("\n");
        }

        String actorDisplay = (actorData != null) ? actorData.gmDisplayName() : actor.getName();
        sb.append("행동: [").append(actorDisplay).append("] ").append(action);
        return sb.toString();
    }

    /** 로그 player 필드(계정명 또는 캐릭터명)로 PlayerData를 찾는다. 없으면 null(NPC·괴담·시스템 등). */
    private PlayerData playerOf(String who) {
        if (who == null) return null;
        return getAllPlayers().stream()
            .filter(p -> p.name.equals(who)
                || (p.charName != null && !p.charName.isEmpty() && p.charName.equals(who)))
            .findFirst().orElse(null);
    }

    /**
     * 이벤트를 현재 행동자가 지각할 수 있는가 — 본인 이벤트이거나 같은 위치(zone)의 플레이어 행동만 true.
     * 비플레이어(NPC·괴담·시스템) 이벤트와 다른 zone의 플레이어 이벤트는 장면 밖으로 간주해 제외한다.
     */
    private boolean isPerceivableEvent(EventLogEntry e, String actorZone, UUID actorUuid) {
        PlayerData pd = playerOf(e.player);
        if (pd == null) return false;
        if (pd.uuid.equals(actorUuid)) return true;
        if (actorZone == null || actorZone.isEmpty()) return false;
        return actorZone.equals(pd.zone);
    }

    /**
     * Minecraft 계정 이름 → GM·서술용 표시 이름.
     * 로그의 player 필드가 계정명이면 그 플레이어의 gmDisplayName(계정명 절대 미노출)으로 변환한다.
     * 매칭되는 플레이어가 없으면(이미 캐릭터명이거나 NPC명) 그대로 통과시킨다.
     */
    public String resolveDisplayName(String rawName) {
        if (rawName == null) return "?";
        PlayerData pd = playerOf(rawName);
        return pd != null ? pd.gmDisplayName() : rawName;
    }

    /** 특정 플레이어의 가장 최근 action 로그 1건 (협력 맥락 제공용). 없으면 null. */
    private String lastActionOf(String playerName) {
        synchronized (eventLog) {
            for (int i = eventLog.size() - 1; i >= 0; i--) {
                EventLogEntry e = eventLog.get(i);
                if ("action".equals(e.type) && playerName.equals(e.player)) {
                    String c = e.content;
                    return c.length() > 60 ? c.substring(0, 60) + "…" : c;
                }
            }
        }
        return null;
    }

    /** Entity/NPC AI용 — 행동 로그만, 스탯/특성 없음 */
    public String buildEntityLog(int limit) {
        StringBuilder sb = new StringBuilder();
        // 후일담 등 플레이어 노출 출력에 쓰이므로 계정 이름 대신 등장인물(캐릭터) 이름으로 변환한다.
        getRecentLog(limit).stream()
            .filter(e -> "action".equals(e.type))
            .forEach(e -> sb.append("[").append(resolveDisplayName(e.player)).append("] ").append(e.content).append("\n"));
        return sb.toString();
    }

    /**
     * Entity/NPC AI용 — ★특정 위치(zone)에서 일어난 플레이어 행동만 반영한다.
     * 다른 장면(다른 zone)의 플레이어 행동이 현재 장면 서술에 섞이는 '장면 혼선'을 막는다.
     * zoneFilter가 비어 있으면 위치 구분 없이 전체를 반환한다(하위호환).
     */
    public String buildEntityLog(int limit, String zoneFilter) {
        if (zoneFilter == null || zoneFilter.isEmpty()) return buildEntityLog(limit);
        List<String> lines = new ArrayList<>();
        for (EventLogEntry e : getRecentLog(Math.max(limit * 4, 12))) {
            boolean isAction = "action".equals(e.type);
            // 같은 구역에서 ★소리 내어 말한 것(@근처 발화)★도 NPC가 듣는다 — 행동만 보고 말은 못 듣던 공백 보완.
            boolean isSpeech = "comm".equals(e.type) && e.content != null && e.content.startsWith("[근처]");
            if (!isAction && !isSpeech) continue;
            PlayerData pd = playerOf(e.player);
            if (pd == null || !zoneFilter.equals(pd.zone)) continue;
            if (isSpeech) {
                String said = e.content.substring("[근처]".length()).trim();
                lines.add("[" + resolveDisplayName(e.player) + " 말함] " + said);
            } else {
                lines.add("[" + resolveDisplayName(e.player) + "] " + e.content);
            }
        }
        int from = Math.max(0, lines.size() - Math.max(0, limit)); // limit 음수 방어(subList 경계 위반 방지)
        StringBuilder sb = new StringBuilder();
        for (String l : lines.subList(from, lines.size())) sb.append(l).append("\n");
        return sb.toString();
    }

    /** 시나리오 평가용 — 전체 게임 로그 (action + damage + clue + system + comm)
     *  comm: 플레이어 간 직접 통신(@연락) — 정보 전달 기여 평가에 사용 */
    public String buildFullEvalLog() {
        StringBuilder sb = new StringBuilder();
        synchronized (eventLog) {
            for (EventLogEntry e : eventLog) {
                if ("action".equals(e.type) || "damage".equals(e.type)
                        || "clue".equals(e.type) || "system".equals(e.type)
                        || "comm".equals(e.type)) {
                    String tag = "comm".equals(e.type) ? "[통신] " : "";
                    sb.append(tag).append(e.toLogString(this::resolveDisplayName)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** 스테이지 전환·재도전 시: 이번 스테이지의 로그를 캠페인 로그에 보관(최근 300개 캡). eventLog는 호출부에서 비운다. */
    private void archiveStageLog() {
        synchronized (eventLog)    { campaignLog.addAll(eventLog); }
        synchronized (campaignLog) { while (campaignLog.size() > 300) campaignLog.remove(0); }
    }

    /** 게임 종료 '전 스테이지 총평'용 — 이전 스테이지(campaignLog) + 현재 스테이지(eventLog)를 합쳐 평가 로그 생성. */
    public String buildCampaignEvalLog() {
        List<EventLogEntry> all = new ArrayList<>();
        synchronized (campaignLog) { all.addAll(campaignLog); }
        synchronized (eventLog)    { all.addAll(eventLog); }
        StringBuilder sb = new StringBuilder();
        for (EventLogEntry e : all) {
            if ("action".equals(e.type) || "damage".equals(e.type)
                    || "clue".equals(e.type) || "system".equals(e.type)
                    || "comm".equals(e.type)) {
                String tag = "comm".equals(e.type) ? "[통신] " : "";
                sb.append(tag).append(e.toLogString(this::resolveDisplayName)).append("\n");
            }
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  접근자
    // ──────────────────────────────────────────────────────────────

    public boolean     isSessionActive()    { return sessionActive; }
    public int         getRoomNumber()      { return roomNumber; }
    public int         getTimelineStage()   { return timelineStage; }
    public int         getMinutesPerTurn()  { return minutesPerTurn; }
    /** 제한 시각까지 남은 인게임 분. 시계·종료시각 없으면 -1, 이미 지났으면 0. (무행동 가속이 마감을 넘지 못하게 캡할 때 사용) */
    public int         getMinutesUntilEnd()  { return (clockMinutes < 0 || clockEnd < 0) ? -1 : Math.max(0, clockEnd - clockMinutes); }
    public int         getDailyTurnsLeft()  { return dailyTurnsLeft; }
    public boolean     isDailyPhase()       { return dailyPhase; }
    public String      getCurrentSeed()     { return currentSeed; }
    public JsonObject  getGdamData()        { return gdamData; }
    public CorruptionData getCorruption()   { return corruption; }
}
