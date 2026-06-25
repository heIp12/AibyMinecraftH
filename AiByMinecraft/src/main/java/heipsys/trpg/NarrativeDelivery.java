package heipsys.trpg;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM 서술 타이프라이터 출력.
 * 문단(빈 줄 구분) 단위로 묶어 출력하고, 문단 사이에 PARAGRAPH_DELAY 대기.
 * 스니킹(Shift)으로 다음 문단을 즉시 출력.
 */
public class NarrativeDelivery {

    // 문단과 문단 사이 대기 시간 (~2.5초)
    private static final long PARAGRAPH_DELAY_TICKS = 50L;

    private final Plugin plugin;
    // 각 플레이어의 문단 큐 (문단 내 줄들은 \n으로 구분된 단일 문자열)
    private final Map<UUID, ArrayDeque<String>> queues  = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>            taskIds = new ConcurrentHashMap<>();

    public NarrativeDelivery(Plugin plugin) {
        this.plugin = plugin;
    }

    /** GM 서술 텍스트를 문단 단위로 큐에 넣고 순차 출력 시작 */
    public void deliver(Player player, String raw) {
        if (raw == null || raw.isBlank()) return;
        UUID uuid = player.getUniqueId();
        ArrayDeque<String> q = queues.computeIfAbsent(uuid, k -> new ArrayDeque<>());

        String formatted = format(raw);
        // 빈 줄(2개 이상 연속 개행)을 문단 구분으로 사용
        String[] paragraphs = formatted.split("\n{2,}");
        for (String para : paragraphs) {
            para = para.trim();
            if (!para.isBlank()) q.add(para);
        }
        if (!taskIds.containsKey(uuid)) scheduleNext(player);
    }

    /**
     * GM 서술 텍스트를 마인크래프트 색코드로 변환한다.
     * - 마크다운 헤더/강조 기호 제거 또는 색 강조로 치환
     * - 인물 대사("...")는 청록색(§b)으로 구분, 서술은 흰색(§f)
     */
    public static String format(String raw) {
        if (raw == null) return "";
        String s = raw;
        // 스마트 따옴표 → 일반 따옴표
        s = s.replace('“', '"').replace('”', '"');
        // 마크다운 헤더 기호 제거
        s = s.replaceAll("(?m)^\\s*#{1,6}\\s*", "");
        // 굵게/기울임/코드 마크다운 → 노란색 강조 후 흰색 복귀
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "§e$1§f");
        s = s.replaceAll("\\*(.+?)\\*",       "§e$1§f");
        s = s.replaceAll("`(.+?)`",           "§e$1§f");
        // 줄머리 목록 기호 제거
        s = s.replaceAll("(?m)^\\s*[-•]\\s+", "");
        // 인물 대사("...") → 청록색으로 구분
        s = s.replaceAll("\"([^\"]+)\"", "§b\"$1\"§f");
        return s;
    }

    /** Shift 감지 시 다음 문단을 즉시 출력하고 그 이후 문단 예약 */
    public void onSneak(Player player) {
        UUID uuid = player.getUniqueId();
        Integer tid = taskIds.remove(uuid);
        if (tid == null) return;
        plugin.getServer().getScheduler().cancelTask(tid);

        ArrayDeque<String> q = queues.get(uuid);
        if (q == null || q.isEmpty()) { queues.remove(uuid); return; }

        String para = q.poll();
        if (player.isOnline()) sendParagraph(player, para);
        if (!q.isEmpty()) scheduleNext(player);
        else queues.remove(uuid);
    }

    public boolean hasPending(Player player) {
        return queues.containsKey(player.getUniqueId());
    }

    public void clear(Player player) {
        UUID uuid = player.getUniqueId();
        Integer tid = taskIds.remove(uuid);
        if (tid != null) plugin.getServer().getScheduler().cancelTask(tid);
        queues.remove(uuid);
    }

    public void clearAll() {
        taskIds.values().forEach(plugin.getServer().getScheduler()::cancelTask);
        taskIds.clear();
        queues.clear();
    }

    /** 문단 내 줄들을 즉시 순서대로 전송 */
    private void sendParagraph(Player player, String para) {
        for (String line : para.split("\n")) {
            if (!line.isBlank()) player.sendMessage("§f" + line);
        }
    }

    /** 다음 문단을 PARAGRAPH_DELAY 후 출력 예약 */
    private void scheduleNext(Player player) {
        UUID uuid = player.getUniqueId();
        int tid = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            taskIds.remove(uuid);
            if (!player.isOnline()) { queues.remove(uuid); return; }
            ArrayDeque<String> q = queues.get(uuid);
            if (q == null || q.isEmpty()) { queues.remove(uuid); return; }
            String para = q.poll();
            sendParagraph(player, para);
            if (!q.isEmpty()) scheduleNext(player);
            else queues.remove(uuid);
        }, PARAGRAPH_DELAY_TICKS).getTaskId();
        taskIds.put(uuid, tid);
    }
}
