package heipsys.trpg;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM 서술 타이프라이터 출력.
 * 줄마다 일정 간격으로 출력하고, 스니킹(Shift)으로 다음 줄 즉시 전달.
 */
public class NarrativeDelivery {

    private static final long LINE_DELAY_TICKS = 22L; // ~1.1초

    private final Plugin plugin;
    private final Map<UUID, ArrayDeque<String>> queues  = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>            taskIds = new ConcurrentHashMap<>();

    public NarrativeDelivery(Plugin plugin) {
        this.plugin = plugin;
    }

    /** GM 서술 텍스트를 줄별로 큐에 넣고 순차 출력 시작 */
    public void deliver(Player player, String raw) {
        if (raw == null || raw.isBlank()) return;
        UUID uuid = player.getUniqueId();
        ArrayDeque<String> q = queues.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        for (String line : raw.split("\n")) {
            if (!line.isBlank()) q.add(line);
        }
        if (!taskIds.containsKey(uuid)) scheduleNext(player);
    }

    /** Shift 감지 시 현재 대기 중인 줄을 즉시 출력하고 다음 줄 예약 */
    public void onSneak(Player player) {
        UUID uuid = player.getUniqueId();
        Integer tid = taskIds.remove(uuid);
        if (tid == null) return;
        plugin.getServer().getScheduler().cancelTask(tid);

        ArrayDeque<String> q = queues.get(uuid);
        if (q == null || q.isEmpty()) { queues.remove(uuid); return; }

        String line = q.poll();
        if (player.isOnline()) player.sendMessage("§f" + line);
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

    private void scheduleNext(Player player) {
        UUID uuid = player.getUniqueId();
        int tid = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            taskIds.remove(uuid);
            if (!player.isOnline()) { queues.remove(uuid); return; }
            ArrayDeque<String> q = queues.get(uuid);
            if (q == null || q.isEmpty()) { queues.remove(uuid); return; }
            String line = q.poll();
            player.sendMessage("§f" + line);
            if (!q.isEmpty()) scheduleNext(player);
            else queues.remove(uuid);
        }, LINE_DELAY_TICKS).getTaskId();
        taskIds.put(uuid, tid);
    }
}
