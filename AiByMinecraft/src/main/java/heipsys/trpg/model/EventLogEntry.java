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
}
