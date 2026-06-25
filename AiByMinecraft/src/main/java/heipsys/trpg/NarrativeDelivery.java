package heipsys.trpg;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM 서술 타이프라이터 출력.
 * 문단(빈 줄 구분) 단위로 묶어 출력하고 문단 사이에 PARAGRAPH_DELAY 대기.
 * 각 줄은 MC 채팅 자동 인덴트 방지를 위해 38자(한글 기준)로 강제 분할.
 * 스니킹(Shift)으로 다음 문단 즉시 출력.
 */
public class NarrativeDelivery {

    // 문단과 문단 사이 대기 시간 (~2.5초)
    private static final long PARAGRAPH_DELAY_TICKS = 50L;
    // 마인크래프트 채팅 자동 줄바꿈 한계 (한글 기준 38자)
    private static final int MAX_CHAT_CHARS = 38;

    private final Plugin plugin;
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
        s = s.replace('“', '"').replace('”', '"');
        s = s.replaceAll("(?m)^\\s*#{1,6}\\s*", "");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "§e$1§f");
        s = s.replaceAll("\\*(.+?)\\*",       "§e$1§f");
        s = s.replaceAll("`(.+?)`",           "§e$1§f");
        s = s.replaceAll("(?m)^\\s*[-•]\\s+", "");
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

    /**
     * 문단 내 각 줄을 MAX_CHAT_CHARS 이하로 분할해 전송.
     * 마지막에 §8의 구분선으로 문단 경계 표시.
     */
    private void sendParagraph(Player player, String para) {
        for (String rawLine : para.split("\n")) {
            if (rawLine.isBlank()) continue;
            for (String segment : hardWrap(rawLine)) {
                player.sendMessage("§f" + segment);
            }
        }
        // 문단 구분선 (다음 문단과 시각적으로 분리)
        player.sendMessage("§8·");
    }

    /**
     * 색코드(§X)를 무시하고 실제 표시 문자 수 기준으로 줄을 분할한다.
     * 한글 기준 MAX_CHAT_CHARS 자 이하로 나눠 MC 자동 인덴트를 방지.
     */
    private static List<String> hardWrap(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int visualLen = 0;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length()) {
                current.append(c).append(line.charAt(i + 1));
                i += 2;
                continue;
            }
            current.append(c);
            visualLen++;
            if (visualLen >= MAX_CHAT_CHARS) {
                result.add(current.toString());
                current = new StringBuilder();
                visualLen = 0;
            }
            i++;
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result.isEmpty() ? Collections.singletonList(line) : result;
    }

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
