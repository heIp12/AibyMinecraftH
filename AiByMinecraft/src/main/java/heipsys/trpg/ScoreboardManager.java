package heipsys.trpg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import heipsys.trpg.model.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

/**
 * 사이드바 스코어보드: 이름/체력/정신력/스테이지/현재 위치만 간략 표시 (STEP 3-3).
 * 상세 스탯·특성은 '캐릭터 정보'(네더의 별) GUI에서 확인한다.
 */
public class ScoreboardManager {

    private final org.bukkit.scoreboard.ScoreboardManager sbm;
    private final GameStateManager state;

    public ScoreboardManager(GameStateManager state) {
        this.sbm   = Bukkit.getScoreboardManager();
        this.state = state;
    }

    /** 플레이어 스코어보드 갱신 */
    public void update(Player player, PlayerData pd, int roomNumber) { update(player, pd, roomNumber, null); }

    /** 스코어보드 갱신. mapLegend(방 이름 범례)가 있으면(지도 든 상태) 기존 정보 대신 그 범례를 표시한다. */
    public void update(Player player, PlayerData pd, int roomNumber, java.util.List<String> mapLegend) {
        Scoreboard sb = sbm.getNewScoreboard();

        // ★지도 든 상태★: 기존 정보 대신 '현재 보는 약도'의 방 이름 범례([n] 이름)를 보여준다.
        if (mapLegend != null && !mapLegend.isEmpty()) {
            Objective mo = sb.registerNewObjective("trpg", Criteria.DUMMY, "§e§l[ 현장 약도 ]");
            mo.setDisplaySlot(DisplaySlot.SIDEBAR);
            int ml = 14;
            set(mo, "§f현위치: §a" + resolveLocationLabel(pd), ml--);
            set(mo, divider(0),                               ml--);
            int cap = 13;
            if (mapLegend.size() <= cap) {
                for (String s : mapLegend) set(mo, s, ml--);
            } else {
                for (int i = 0; i < cap - 1; i++) set(mo, mapLegend.get(i), ml--);
                set(mo, "§8…외 " + (mapLegend.size() - (cap - 1)) + "곳 (지도 참조)", ml);
            }
            player.setScoreboard(sb);
            return;
        }

        Objective obj = sb.registerNewObjective("trpg", Criteria.DUMMY, "§e§l[ TRPG ]");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // 사이드바는 핵심 정보만 간략하게. 상세 정보(스탯·특성)는 '캐릭터 정보'(네더의 별) GUI에서.
        int line = 11;
        // 등장인물(캐릭터) 이름 우선 표시. 배역 미배정(생성 전)일 때만 계정 이름으로 폴백.
        set(obj, "§f" + (pd.charName.isEmpty() ? pd.name : pd.charName), line--);
        set(obj, divider(0),                             line--);
        set(obj, hpBar(pd),                               line--);
        set(obj, sanBar(pd),                              line--);
        set(obj, divider(1),                             line--);
        set(obj, "§f스테이지: §e" + roomNumber,           line--);
        set(obj, "§f위치: §a" + resolveLocationLabel(pd), line--);
        if (state.isClockActive()) {
            String t = state.isTimeKnown(pd) ? "§e" + state.getCurrentTimeString() : "§8불명";
            set(obj, "§f시간: " + t,                      line--);
        }
        set(obj, divider(2),                             line--);
        set(obj, "§7상세: §b네더의 별 우클릭",            line);

        player.setScoreboard(sb);
    }

    /** 스코어보드 제거 (세션 종료 시) */
    public void clear(Player player) {
        player.setScoreboard(sbm.getMainScoreboard());
    }

    // ──────────────────────────────────────────────────────────────
    //  위치 라벨 계산
    // ──────────────────────────────────────────────────────────────

    /** 위치 라벨: "존이름" 또는 "존이름§7[세부위치]" */
    private String resolveLocationLabel(PlayerData pd) {
        String zoneName = resolveZoneName(pd.zone);
        if (pd.spot != null && !pd.spot.isEmpty()) {
            return zoneName + "§7[" + pd.spot + "]";
        }
        return zoneName;
    }

    /** zone_id → .gdam zones[].name (사람이 읽을 이름). 없으면 zone_id, 미설정이면 "?" */
    private String resolveZoneName(String zoneId) {
        if (zoneId == null || zoneId.isEmpty()) return "?";
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("zones")) return zoneId;
        for (JsonElement el : gdam.getAsJsonArray("zones")) {
            JsonObject z = el.getAsJsonObject();
            String id = z.has("zone_id") ? z.get("zone_id").getAsString() : "";
            if (zoneId.equals(id)) {
                String name = z.has("name") ? z.get("name").getAsString() : "";
                return name.isEmpty() ? zoneId : name;
            }
        }
        return zoneId;
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

    /** 구분선. 동일 문자열은 스코어보드에서 한 엔트리로 합쳐지므로 보이지 않는 색코드로 구분한다. */
    private String divider(int n) {
        return "§8─────────────────" + "§r".repeat(n);
    }

    private void set(Objective obj, String label, int score) {
        Score s = obj.getScore(label);
        s.setScore(score);
    }

    private String hpBar(PlayerData pd) {
        return "§c체력  §f" + DialogManager.hpDisplay(pd.hp) + " " + bar(pd.hp[0], pd.hp[1], "§c", "§8");
    }

    private String sanBar(PlayerData pd) {
        return "§b정신 §f" + DialogManager.hpDisplay(pd.san) + " " + bar(pd.san[0], pd.san[1], "§b", "§8");
    }

    private String bar(int cur, int max, String fill, String empty) {
        if (max <= 0) return "";
        int bars  = 6;
        int filled = (int) Math.round((double) cur / max * bars);
        return fill + "█".repeat(filled) + empty + "░".repeat(bars - filled);
    }
}
