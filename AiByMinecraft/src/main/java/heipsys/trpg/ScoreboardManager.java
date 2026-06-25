package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

/**
 * 사이드바 스코어보드: 체력/정신력/스탯/특성/스테이지 정보 표시 (STEP 3-3).
 * 타임라인 단계는 숫자 대신 '??' 표시.
 */
public class ScoreboardManager {

    private final org.bukkit.scoreboard.ScoreboardManager sbm;

    public ScoreboardManager() {
        this.sbm = Bukkit.getScoreboardManager();
    }

    /** 플레이어 스코어보드 갱신 */
    public void update(Player player, PlayerData pd, int roomNumber) {
        Scoreboard sb = sbm.getNewScoreboard();

        Objective obj = sb.registerNewObjective("trpg", Criteria.DUMMY, "§e§l[ TRPG ]");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        int line = 20;
        set(obj, "§f" + pd.name,                         line--);
        set(obj, "§7" + pd.age + "세 / " + pd.job,       line--);
        set(obj, "§8─────────────────",                  line--);
        set(obj, hpBar(pd),                               line--);
        set(obj, sanBar(pd),                              line--);
        set(obj, "§8─────────────────",                  line--);
        // 기본 스탯 + 배역 보정치(증감) 표기
        set(obj, "§9근력 §f" + pd.str + mod(pd.str, pd.baseStr)
               + " §a매력 §f" + pd.cha + mod(pd.cha, pd.baseCha), line--);
        set(obj, "§6행운 §f" + pd.luk + mod(pd.luk, pd.baseLuk)
               + " §d영감 §f" + pd.spr + mod(pd.spr, pd.baseSpr), line--);

        // 기본 특성 (영구)
        boolean baseHeader = false;
        for (TraitData t : pd.traits) {
            if (t.roleSpecific) continue;
            if (line <= 3) break;
            if (!baseHeader) {
                set(obj, "§8─────────────────", line--);
                set(obj, "§e[특성]",            line--);
                baseHeader = true;
            }
            set(obj, "§7▸ " + t.name + " §8(" + t.grade + ")", line--);
        }

        // 배역 특성 (이번 스테이지 한정)
        boolean roleHeader = false;
        for (TraitData t : pd.traits) {
            if (!t.roleSpecific) continue;
            if (line <= 3) break;
            if (!roleHeader) {
                set(obj, "§8─────────────────", line--);
                set(obj, "§6[배역 특성]",        line--);
                roleHeader = true;
            }
            set(obj, "§7▸ " + t.name + " §8(" + t.grade + ")", line--);
        }

        set(obj, "§8─────────────────",              line--);
        set(obj, "§f스테이지: " + roomNumber + "  §7타임라인: ??", line);

        player.setScoreboard(sb);
    }

    /** 스코어보드 제거 (세션 종료 시) */
    public void clear(Player player) {
        player.setScoreboard(sbm.getMainScoreboard());
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

    private void set(Objective obj, String label, int score) {
        Score s = obj.getScore(label);
        s.setScore(score);
    }

    /** 기본값 대비 배역 보정 증감 표기 (없으면 빈 문자열) */
    private String mod(int current, int base) {
        int d = current - base;
        if (d == 0) return "";
        return d > 0 ? "§a+" + d : "§c" + d; // 음수는 부호 포함
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
