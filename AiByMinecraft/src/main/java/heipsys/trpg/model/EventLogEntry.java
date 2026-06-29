package heipsys.trpg.model;

public class EventLogEntry {
    public int    turn;
    public String type;    // action / clue / timeline / damage / item / system
    public String player;  // null for system events
    public String content;

    public EventLogEntry(int turn, String type, String player, String content) {
        this.turn    = turn;
        this.type    = type;
        this.player  = player;
        this.content = content;
    }

    public String toLogString() {
        String prefix = (player != null && !player.isEmpty()) ? "[" + player + "] " : "";
        return "T" + turn + " " + type.toUpperCase() + ": " + prefix + content;
    }

    /**
     * player 필드를 표시 이름으로 변환해 렌더링(계정명 노출 차단용).
     * nameResolver: raw player(계정명) → 표시명(gmDisplayName). AI에 먹이는 로그는 이 오버로드를 써야 한다.
     */
    public String toLogString(java.util.function.UnaryOperator<String> nameResolver) {
        String who = (player != null && !player.isEmpty() && nameResolver != null)
            ? nameResolver.apply(player) : player;
        String prefix = (who != null && !who.isEmpty()) ? "[" + who + "] " : "";
        return "T" + turn + " " + type.toUpperCase() + ": " + prefix + content;
    }
}
