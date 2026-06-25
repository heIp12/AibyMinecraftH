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
    private final List<EventLogEntry>            eventLog   = new ArrayList<>();

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
        dailyTurnsLeft--;
        if (dailyTurnsLeft <= 0) {
            dailyPhase    = false;
            timelineStage = 1;
            return true;
        }
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
        int start = Math.max(0, eventLog.size() - n);
        return new ArrayList<>(eventLog.subList(start, eventLog.size()));
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
        sb.append("TURN ").append(currentTurn).append("\n");
        sb.append("TIMELINE: ").append(dailyPhase ? "일상 파트 (").append(dailyTurnsLeft).append("턴 남음)" : timelineStage + "단계").append("\n");
        sb.append("CORRUPTION: ").append(corruption.level).append("\n");
        sb.append("PLAYERS:\n");
        players.values().forEach(p -> sb.append(p.toTurnLine()).append("\n"));

        List<EventLogEntry> recent = getRecentLog(5);
        if (!recent.isEmpty()) {
            sb.append("EVENTS: ");
            recent.forEach(e -> sb.append(e.toLogString()).append(" | "));
            sb.append("\n");
        }

        sb.append("ACTION: [").append(actor.getName()).append("] ").append(action);
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
