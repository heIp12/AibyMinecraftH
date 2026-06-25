package heipsys.trpg;

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

        /** 세션 완전 종료 시 리셋 */
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

    private boolean sessionActive     = false;
    private int     roomNumber        = 1;
    private int     timelineStage     = 0; // 0 = 일상 파트
    private int     dailyTurnsLeft    = 5;
    private int     currentTurn       = 0;
    private boolean dailyPhase        = true;
    private String  currentSeed       = "";

    private JsonObject gdamData = null;

    private final CorruptionData                 corruption = new CorruptionData();
    private final Map<UUID, PlayerData>          players    = new LinkedHashMap<>();
    private final List<String>                   activeNpcs = new ArrayList<>();
    private final List<String>                   discoveredClues = new ArrayList<>();
    private final List<String>                   foundItems = new ArrayList<>();
    private final List<EventLogEntry>            eventLog   = Collections.synchronizedList(new ArrayList<>());

    // ──────────────────────────────────────────────────────────────
    //  세션 라이프사이클
    // ──────────────────────────────────────────────────────────────

    public void startSession(int room, String seed, JsonObject gdam) {
        sessionActive  = true;
        roomNumber     = room;
        currentSeed    = seed;
        gdamData       = gdam;
        timelineStage  = 0;
        currentTurn    = 0;
        dailyPhase     = true;
        players.clear();
        activeNpcs.clear();
        discoveredClues.clear();
        foundItems.clear();
        eventLog.clear();

        // 일상 턴 수는 .gdam에서 가져옴 (없으면 5)
        if (gdam != null && gdam.has("timeline")) {
            JsonObject tl = gdam.getAsJsonObject("timeline");
            dailyTurnsLeft = tl.has("daily_turns") ? tl.get("daily_turns").getAsInt() : 5;
        } else {
            dailyTurnsLeft = 5;
        }
    }

    public void endSession(boolean resetCorruption) {
        sessionActive = false;
        if (resetCorruption) corruption.reset();
    }

    /** 다음 스테이지로 이동: 스테이지 번호/씨드/gdam 업데이트. 플레이어 데이터는 호출자가 clearRoleData()로 처리. */
    public void advanceToNextRoom(int nextRoom, String seed, JsonObject gdam) {
        roomNumber     = nextRoom;
        currentSeed    = seed;
        gdamData       = gdam;
        timelineStage  = 0;
        currentTurn    = 0;
        dailyPhase     = true;
        discoveredClues.clear();
        foundItems.clear();
        eventLog.clear();
        if (gdam != null && gdam.has("timeline")) {
            JsonObject tl = gdam.getAsJsonObject("timeline");
            dailyTurnsLeft = tl.has("daily_turns") ? tl.get("daily_turns").getAsInt() : 5;
        } else {
            dailyTurnsLeft = 5;
        }
    }

    /** 재도전: 오염도 상승, 플레이어 상태 리셋, 파일은 다시 로드 */
    public void onRetry() {
        corruption.onRetry();
        timelineStage = 0;
        currentTurn   = 0;
        dailyPhase    = true;
        discoveredClues.clear();
        foundItems.clear();
        eventLog.clear();
        if (gdamData != null && gdamData.has("timeline")) {
            JsonObject tl = gdamData.getAsJsonObject("timeline");
            dailyTurnsLeft = tl.has("daily_turns") ? tl.get("daily_turns").getAsInt() : 5;
        } else {
            dailyTurnsLeft = 5;
        }
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
        timelineStage = Math.min(4, timelineStage + stages);
    }

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
    //  턴
    // ──────────────────────────────────────────────────────────────

    public int nextTurn()        { return ++currentTurn; }
    public int getCurrentTurn()  { return currentTurn; }

    // ──────────────────────────────────────────────────────────────
    //  이벤트 로그
    // ──────────────────────────────────────────────────────────────

    public void log(String type, String player, String content) {
        eventLog.add(new EventLogEntry(currentTurn, type, player, content));
    }

    public List<EventLogEntry> getLog()               { return eventLog; }
    public int                 getLogSize()            { return eventLog.size(); }

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

    // ──────────────────────────────────────────────────────────────
    //  GM AI 입력 포맷 빌더
    // ──────────────────────────────────────────────────────────────

    public String buildTurnInput(Player actor, String action) {
        StringBuilder sb = new StringBuilder();
        // 헤더: 필수 메타만 압축
        sb.append("T").append(currentTurn).append(" ");
        sb.append(dailyPhase ? "일상(" + dailyTurnsLeft + ")" : "공포" + timelineStage);
        if (corruption.level > 0) sb.append(" 오염").append(corruption.level);
        sb.append("\n");

        // 행동자: 풀 스탯
        PlayerData actorData = players.get(actor.getUniqueId());
        if (actorData != null) {
            sb.append("행동자: ").append(actorData.toTurnLine()).append("\n");
        }

        // 다른 플레이어: HP/SAN/상태만 한 줄로
        StringJoiner others = new StringJoiner("  ");
        players.values().stream()
            .filter(p -> !p.uuid.equals(actor.getUniqueId()))
            .forEach(p -> others.add(p.toShortLine()));
        String othersStr = others.toString();
        if (!othersStr.isEmpty()) sb.append("동료: ").append(othersStr).append("\n");

        // 최근 이벤트 (3개로 축소)
        List<EventLogEntry> recent = getRecentLog(3);
        if (!recent.isEmpty()) {
            sb.append("최근:");
            recent.forEach(e -> sb.append(" [").append(e.player).append("] ").append(e.content));
            sb.append("\n");
        }

        sb.append("행동: [").append(actor.getName()).append("] ").append(action);
        return sb.toString();
    }

    /** Entity/NPC AI용 — 행동 로그만, 스탯/특성 없음 */
    public String buildEntityLog(int limit) {
        StringBuilder sb = new StringBuilder();
        getRecentLog(limit).stream()
            .filter(e -> "action".equals(e.type))
            .forEach(e -> sb.append("[").append(e.player).append("] ").append(e.content).append("\n"));
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  접근자
    // ──────────────────────────────────────────────────────────────

    public boolean     isSessionActive()    { return sessionActive; }
    public int         getRoomNumber()      { return roomNumber; }
    public int         getTimelineStage()   { return timelineStage; }
    public int         getDailyTurnsLeft()  { return dailyTurnsLeft; }
    public boolean     isDailyPhase()       { return dailyPhase; }
    public String      getCurrentSeed()     { return currentSeed; }
    public JsonObject  getGdamData()        { return gdamData; }
    public CorruptionData getCorruption()   { return corruption; }
}
