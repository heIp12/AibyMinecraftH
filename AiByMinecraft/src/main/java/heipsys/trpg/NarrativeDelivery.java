package heipsys.trpg;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM 서술 타이프라이터 출력.
 * 한 줄씩 일정 간격으로 출력하고, 줄 사이에 빈 줄로 여백을 둔다.
 * 각 줄은 MC 채팅 자동 인덴트 방지를 위해 38자(한글 기준)로 강제 분할.
 * 스니킹(Shift)으로 다음 줄 즉시 출력.
 */
public class NarrativeDelivery {

    // 한 줄과 다음 줄 사이 대기 시간 (3초)
    private static final long LINE_DELAY_TICKS = 60L;
    // 마인크래프트 채팅 자동 줄바꿈 한계 (한글 기준 38자)
    private static final int MAX_CHAT_CHARS = 38;

    private final Plugin plugin;
    private final Map<UUID, ArrayDeque<String>> queues  = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>            taskIds = new ConcurrentHashMap<>();

    public NarrativeDelivery(Plugin plugin) {
        this.plugin = plugin;
    }

    /** GM 서술 텍스트를 줄 단위로 큐에 넣고 순차 출력 시작 */
    public void deliver(Player player, String raw) {
        if (raw == null || raw.isBlank()) return;
        UUID uuid = player.getUniqueId();
        ArrayDeque<String> q = queues.computeIfAbsent(uuid, k -> new ArrayDeque<>());

        for (String line : format(raw).split("\n")) {
            if (!line.isBlank()) q.add(line.trim());
        }
        if (!taskIds.containsKey(uuid)) scheduleNext(player);
    }

    // 색상 구분: 기본 서술=흰색, 화자 태그[...]=주황, 연출/시스템<...>=노랑, 대사="..."=청록
    private static final String BASE_COLOR = "§f"; // 기본 서술색 (흰색)

    /**
     * GM 서술 텍스트를 마인크래프트 색코드로 변환한다(가독성 색상 구분).
     * - 기본 서술: 흰색(§f)
     * - 화자 태그 [이름]: 주황색(§6)   예) [김민지] 저 민지에요!
     * - 연출·시스템 효과 &lt;...&gt;: 노란색(§e)   예) &lt;시야가 암전됨&gt;
     * - 인물 대사("..."): 청록색(§b)
     */
    public static String format(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replace('“', '"').replace('”', '"');
        s = s.replaceAll("(?m)^\\s*#{1,6}\\s*", "");
        // 연출·시스템 효과 <...> → 노란색 (괄호 포함)
        s = s.replaceAll("<([^<>\n]+)>", "§e<$1>" + BASE_COLOR);
        // 화자 태그 [이름] → 주황색 (괄호 포함)
        s = s.replaceAll("\\[([^\\[\\]\n]+)\\]", "§6[$1]" + BASE_COLOR);
        // 마크다운 강조 → 노란 강조 후 기본색 복귀
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "§e$1" + BASE_COLOR);
        s = s.replaceAll("\\*(.+?)\\*",       "§e$1" + BASE_COLOR);
        s = s.replaceAll("`(.+?)`",           "§e$1" + BASE_COLOR);
        s = s.replaceAll("(?m)^\\s*[-•]\\s+", "");
        // 인물 대사("...") → 청록색
        s = s.replaceAll("\"([^\"]+)\"", "§b\"$1\"" + BASE_COLOR);
        return s;
    }

    /** Shift 감지 시 다음 줄을 즉시 출력하고 그 이후 줄 예약 */
    public void onSneak(Player player) {
        UUID uuid = player.getUniqueId();
        Integer tid = taskIds.remove(uuid);
        if (tid == null) return;
        plugin.getServer().getScheduler().cancelTask(tid);

        ArrayDeque<String> q = queues.get(uuid);
        if (q == null || q.isEmpty()) { queues.remove(uuid); return; }

        String line = q.poll();
        if (player.isOnline()) sendLine(player, line);
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
     * 한 줄을 MAX_CHAT_CHARS 이하로 분할해 전송하고, 뒤에 빈 줄로 여백을 둔다.
     */
    private void sendLine(Player player, String line) {
        for (String segment : hardWrap(line)) {
            player.sendMessage(BASE_COLOR + segment);
        }
        player.sendMessage(""); // 줄 사이 여백
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
            String line = q.poll();
            sendLine(player, line);
            if (!q.isEmpty()) scheduleNext(player);
            else queues.remove(uuid);
        }, LINE_DELAY_TICKS).getTaskId();
        taskIds.put(uuid, tid);
    }
}
