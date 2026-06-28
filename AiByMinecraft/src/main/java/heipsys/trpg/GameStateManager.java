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

        public void onRetry() {
            attempts++;
            level = calcLevel();
        }

        private int calcLevel() {
            if (attempts == 0) return 0;
            if (attempts <= 2) return 1;
            if (attempts <= 4) return 2;
            if (attempts <= 6) return 3;
            return 4;
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
    private boolean timeVisibleDefault = true;  // 이 방에서 기본적으로 시간 인지 가능 여부
    private boolean endEventFired      = false; // 종료 사건/제한 시각 도달 여부
    private final Set<String>       firedEvents       = new HashSet<>();
    private final Set<String>       blockedEvents     = new HashSet<>();
    private final List<String>      justFiredEvents   = new ArrayList<>();
    private final Map<UUID,Boolean> timeKnownOverride = new HashMap<>();

    private JsonObject gdamData = null;

    private final CorruptionData                 corruption = new CorruptionData();
    private final Map<UUID, PlayerData>          players    = new LinkedHashMap<>();
    private final List<String>                   activeNpcs = new ArrayList<>();
    private final List<String>                   discoveredClues = new ArrayList<>();
    private final List<String>                   foundItems = new ArrayList<>();
    /** 엔딩 공개용: 플레이 중 실제로 알아낸 정식 사실 키 (예: "identity", "weakness:<항목>", "solution", "name") */
    private final java.util.Set<String> discoveredFacts = new java.util.HashSet<>();
    /** 아이템 Phase II: 열쇠·도구 등으로 해제된 구역 zone_id 집합 (재도전·다음 방에서 초기화) */
    private final java.util.Set<String> unlockedZones = new java.util.HashSet<>();
    private final List<EventLogEntry>            eventLog   = Collections.synchronizedList(new ArrayList<>());
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
        eventLog.clear();
        loadTimelineConfig(gdam);
    }

    /** 재도전: 오염도 상승, 플레이어 상태 리셋, 파일은 다시 로드 */
    public void onRetry() {
        corruption.onRetry();
        timelineStage     = 0;
        turnsSinceAdvance = 0;
        currentTurn       = 0;
        dailyPhase        = true;
        discoveredClues.clear();
        foundItems.clear();
        discoveredFacts.clear();
        unlockedZones.clear();
        eventLog.clear();
        loadTimelineConfig(gdamData);
        players.values().forEach(PlayerData::resetToBase);
    }

    // ──────────────────────────────────────────────────────────────
    //  플레이어
    // ──────────────────────────────────────────────────────────────

    public void addPlayer(PlayerData pd)            { players.put(pd.uuid, pd); }
    public PlayerData getPlayer(UUID uuid)          { return players.get(uuid); }
    public PlayerData getPlayer(Player p)           { return players.get(p.getUniqueId()); }
    public boolean    hasPlayer(UUID uuid)          { return players.containsKey(uuid); }
    public Collection<PlayerData> getAllPlayers()   { return players.values(); }

    public int getAliveCount() {
        return (int) players.values().stream().filter(p -> !p.isDead).count();
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
        justFiredEvents.clear();
        timeKnownOverride.clear();
        endEventFired      = false;
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
            if (ev.has("is_end") && ev.get("is_end").getAsBoolean()) {
                endEventFired = true;
                timelineStage = getMaxStage(); // CODE-17: 종료 사건 → 최고 단계(가변)
            }
        }
        if (clockEnd >= 0 && clockMinutes >= clockEnd && !endEventFired) {
            endEventFired = true;
            timelineStage = getMaxStage();
            justFiredEvents.add("제한 시각 도달 — 상황이 종국으로 치닫는다");
        }
    }

    public boolean isClockActive()   { return clockMinutes >= 0; }
    public boolean isEndEventFired() { return endEventFired; }

    /** 현재 인게임 시각. 같은 날이면 "HH:MM", 여러 날에 걸치면 "N일차 HH:MM". 시계 없으면 "". */
    public String getCurrentTimeString() {
        if (clockMinutes < 0) return "";
        int m = ((clockMinutes % 1440) + 1440) % 1440;
        String hhmm = String.format("%02d:%02d", m / 60, m % 60);
        int dayIdx = (clockStart >= 0 ? clockMinutes - clockStart : clockMinutes) / 1440;
        return dayIdx > 0 ? (dayIdx + 1) + "일차 " + hhmm : hhmm;
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
        players.values().stream()
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
            if (ev.has("is_end") && ev.get("is_end").getAsBoolean()) { endEventFired = true; timelineStage = getMaxStage(); }
            return;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  턴
    // ──────────────────────────────────────────────────────────────

    public int nextTurn() {
        currentTurn++;
        tickClock();
        return currentTurn;
    }
    public int getCurrentTurn()  { return currentTurn; }

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
        for (PlayerData p : players.values()) {
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
            otherZone.forEach(p -> others.add(p.toShortLine()));
            sb.append("다른 위치 동료: ").append(others).append("\n");
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
        return players.values().stream()
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
            if (!"action".equals(e.type)) continue;
            PlayerData pd = playerOf(e.player);
            if (pd == null || !zoneFilter.equals(pd.zone)) continue;
            lines.add("[" + resolveDisplayName(e.player) + "] " + e.content);
        }
        int from = Math.max(0, lines.size() - limit);
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

    // ──────────────────────────────────────────────────────────────
    //  접근자
    // ──────────────────────────────────────────────────────────────

    public boolean     isSessionActive()    { return sessionActive; }
    public int         getRoomNumber()      { return roomNumber; }
    public int         getTimelineStage()   { return timelineStage; }
    public int         getMinutesPerTurn()  { return minutesPerTurn; }
    public int         getDailyTurnsLeft()  { return dailyTurnsLeft; }
    public boolean     isDailyPhase()       { return dailyPhase; }
    public String      getCurrentSeed()     { return currentSeed; }
    public JsonObject  getGdamData()        { return gdamData; }
    public CorruptionData getCorruption()   { return corruption; }
}
