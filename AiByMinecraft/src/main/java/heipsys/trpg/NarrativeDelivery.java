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
    // 한 라인(MC 채팅 한 줄) 최대 표시 길이 — 한글 기준 약 20자(40자 = 2줄).
    // 이를 넘으면 다음 라인으로 래핑하며, MAX_LINES_PER_BLOCK과 곱해 한 덩이(2줄=40자)를 만든다.
    private static final int MAX_CHAT_CHARS = 20;

    private final Plugin plugin;
    private final Map<UUID, ArrayDeque<String>> queues  = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>            taskIds = new ConcurrentHashMap<>();

    public NarrativeDelivery(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 한 번에 출력할 최대 줄 수 (이를 넘으면 다음 블록/틱으로 이어 표시) */
    private static final int MAX_LINES_PER_BLOCK = 2;

    /** GM 서술 텍스트를 줄 단위로 큐에 넣고 순차 출력 시작 */
    public void deliver(Player player, String raw) {
        if (raw == null || raw.isBlank()) return;
        UUID uuid = player.getUniqueId();
        ArrayDeque<String> q = queues.computeIfAbsent(uuid, k -> new ArrayDeque<>());

        // 문단(\n) → 문장 단위로 쪼갠 뒤, 한 덩이가 2줄(한글 MAX_CHAT_CHARS자×2)을 넘지 않게
        // ★문장 경계에서만★ 묶는다. 각 덩이는 MAX_CHAT_CHARS자 단위로 하드랩(MC 자동 인덴트 방지)해
        // 블록으로 큐에 넣는다 → 문장 중간이 끊기지 않고, 2줄을 넘기면 다음 라인으로 이어진다.
        // 단어 단위 줄바꿈은 단어를 보존하느라 줄 끝에 여백이 남으므로, 2줄 한도(40자)에서 살짝 줄여
        // 한 덩이가 2줄을 넘겨 3줄째로 흘러가지 않게 한다(타자기 한 블록 = 2줄 유지).
        final int maxChunk = MAX_CHAT_CHARS * MAX_LINES_PER_BLOCK - 4;
        for (String para : format(raw).split("\n")) {
            if (para.isBlank()) continue;
            StringBuilder chunk = new StringBuilder();
            int vis = 0;
            for (String sent : splitSentences(para.trim())) {
                int sv = visualLength(sent);
                // 이 문장을 더하면 2줄 초과 → 현재 덩이를 먼저 내보낸다
                if (vis > 0 && vis + 1 + sv > maxChunk) {
                    enqueueChunk(q, chunk.toString());
                    chunk.setLength(0); vis = 0;
                }
                if (vis > 0) { chunk.append(' '); vis++; }
                chunk.append(sent);
                vis += sv;
                // 한 문장 자체가 2줄을 넘으면 단독으로 내보낸다(하드랩으로만 분할)
                if (vis > maxChunk) {
                    enqueueChunk(q, chunk.toString());
                    chunk.setLength(0); vis = 0;
                }
            }
            if (chunk.length() > 0) enqueueChunk(q, chunk.toString());
        }

        if (!taskIds.containsKey(uuid)) scheduleNext(player);
    }

    /**
     * 한 덩이(문장 묶음)를 MAX_CHAT_CHARS자 단위로 하드랩해 ★하나의 블록★으로 큐에 넣는다.
     * 한 덩이는 항상 '완결된 문장(들)'이므로 통째로 한 블록에 담는다 → 빈 줄(블록 사이 여백)은
     * 문장과 문장 사이에만 들어가고, 한 문장이 3줄로 늘어나도 도중에 빈 줄이 끼지 않는다
     * ("구분이 안" / (빈 줄) / "된다." 처럼 끊겨 보이던 문제 해결).
     */
    private void enqueueChunk(ArrayDeque<String> q, String chunk) {
        StringBuilder block = new StringBuilder();
        for (String seg : hardWrap(chunk.trim())) {
            if (block.length() > 0) block.append('\n');
            block.append(seg);
        }
        if (block.length() > 0) q.add(block.toString());
    }

    /** 색코드(§X)를 제외한 실제 표시 문자 수. */
    private static int visualLength(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '§' && i + 1 < s.length()) { i++; continue; }
            n++;
        }
        return n;
    }

    /**
     * 문단을 문장 단위로 분해한다. 종결 부호(. ? ! …) 뒤의 닫는 따옴표·괄호·색코드를 흡수하고,
     * 그 뒤가 공백이거나 문단 끝일 때만 문장 경계로 본다(소수점·약어 등 오분할 방지).
     */
    private static List<String> splitSentences(String s) {
        List<String> out = new ArrayList<>();
        int start = 0, i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.' || c == '?' || c == '!' || c == '…') {
                int j = i + 1;
                while (j < s.length()) {
                    char d = s.charAt(j);
                    if (d == '"' || d == '\'' || d == '”' || d == '’' || d == ')' || d == ']') { j++; continue; }
                    if (d == '§' && j + 1 < s.length()) { j += 2; continue; }
                    break;
                }
                if (j >= s.length() || s.charAt(j) == ' ') {
                    String seg = s.substring(start, j).trim();
                    if (!seg.isEmpty()) out.add(seg);
                    while (j < s.length() && s.charAt(j) == ' ') j++;
                    start = j; i = j; continue;
                }
            }
            i++;
        }
        if (start < s.length()) {
            String seg = s.substring(start).trim();
            if (!seg.isEmpty()) out.add(seg);
        }
        return out.isEmpty() ? Collections.singletonList(s.trim()) : out;
    }

    /**
     * 서술 텍스트를 문단(\n)→문장 단위의 줄 목록으로 분해한다.
     * 다이얼로그처럼 가운데 정렬되는 곳에서 한 문장씩 줄을 나눠 '벽글'을 막는 용도(외부 호출용).
     */
    public static List<String> toSentenceLines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String para : raw.split("\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            for (String sent : splitSentences(p)) {
                String t = sent.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
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
        s = s.replace('“', '"').replace('”', '"'); // 굽은 따옴표 → 곧은 따옴표(아래 대사 색칠이 곡선 따옴표도 잡도록)
        s = s.replaceAll("(?m)^\\s*#{1,6}\\s*", ""); // 마크다운 헤더(#, ##…) 제거
        // 독백·내면의 소리 <-..-> → 회색 (연출 규칙보다 먼저 적용)
        s = s.replaceAll("<-([^<>\n]+)->", "§7<-$1->" + BASE_COLOR);
        // 연출·시스템 효과 <...> → 노란색 (독백 <-..-> 와 구분: - 로 시작하지 않는 것만 매칭)
        s = s.replaceAll("<(?!-)([^<>\n]+)>", "§e<$1>" + BASE_COLOR);
        // 화자 태그 [이름] → 주황색 (괄호 포함)
        s = s.replaceAll("\\[([^\\[\\]\n]+)\\]", "§6[$1]" + BASE_COLOR);
        // 마크다운 강조: **굵게**=노랑 강조, *주석/지문*=회색(연출 보조 설명)
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "§e$1" + BASE_COLOR);
        s = s.replaceAll("\\*(.+?)\\*",       "§7$1" + BASE_COLOR); // *주석*은 회색
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
     * 대기 중인 모든 줄을 즉시(타자기 딜레이 없이) 한꺼번에 내보내고 큐를 비운다.
     * 결말·결과 표시 직전에 호출해, 천천히 흐르던 서술이 결과와 겹쳐 보이는 것을 막는다(텍스트는 보존).
     */
    public void flushAll() {
        for (UUID uuid : new ArrayList<>(queues.keySet())) {
            Integer tid = taskIds.remove(uuid);
            if (tid != null) plugin.getServer().getScheduler().cancelTask(tid);
            ArrayDeque<String> q = queues.remove(uuid);
            if (q == null) continue;
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) while (!q.isEmpty()) sendLine(p, q.poll());
        }
    }

    /**
     * 한 블록(이미 래핑된 최대 2줄)을 줄 단위로 전송하고, 뒤에 빈 줄로 여백을 둔다.
     * (블록은 deliver에서 38자 단위로 미리 분할되어 있으므로 재래핑하지 않는다)
     */
    private void sendLine(Player player, String block) {
        for (String segment : block.split("\n")) {
            player.sendMessage(BASE_COLOR + segment);
        }
        player.sendMessage(""); // 블록 사이 여백
    }

    /**
     * 색코드(§X)를 무시하고 실제 표시 문자 수 기준으로 줄을 분할한다(한글 기준 MAX_CHAT_CHARS 자 이하).
     * - ★단어(공백) 경계★에서만 끊어 단어·문장부호가 잘리지 않게 한다(부득이 한 단어가 한 줄보다 길면 글자 단위 분할).
     * - 줄을 넘길 때 직전 줄의 ★마지막 색코드★를 다음 줄 머리에 이어 붙여, 줄바꿈 시 서식(색)이 끊기지 않게 한다.
     */
    private static List<String> hardWrap(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int vis = 0;
        String carry = ""; // 줄을 넘길 때 다음 줄 머리에 이어붙일 직전 활성 색코드

        for (String word : line.split(" ", -1)) {
            if (word.isEmpty()) continue; // 연속·말단 공백 무시(단어 사이는 아래에서 한 칸씩 복원)
            int wv = visualLength(word);

            // 이 단어를 더하면 한 줄 한도를 넘는다 → 현재 줄을 끊고 단어를 통째로 다음 줄로(단어 보존)
            if (vis > 0 && vis + 1 + wv > MAX_CHAT_CHARS) {
                carry = lastColorCode(cur.toString(), carry);
                result.add(cur.toString());
                cur = new StringBuilder(carry);
                vis = 0;
            }

            if (wv > MAX_CHAT_CHARS) {
                // 한 단어가 한 줄보다 길다 → 부득이 글자 단위로 분할(§코드 보존·색 이어붙임)
                if (vis > 0) { carry = lastColorCode(cur.toString(), carry); result.add(cur.toString()); cur = new StringBuilder(carry); vis = 0; }
                for (int k = 0; k < word.length(); ) {
                    char c = word.charAt(k);
                    if (c == '§' && k + 1 < word.length()) { cur.append(c).append(word.charAt(k + 1)); k += 2; continue; }
                    if (vis >= MAX_CHAT_CHARS) { carry = lastColorCode(cur.toString(), carry); result.add(cur.toString()); cur = new StringBuilder(carry); vis = 0; }
                    cur.append(c); vis++; k++;
                }
            } else {
                if (vis > 0) { cur.append(' '); vis++; }
                cur.append(word);
                vis += wv;
            }
        }
        if (visualLength(cur.toString()) > 0) result.add(cur.toString());
        return result.isEmpty() ? Collections.singletonList(line) : result;
    }

    /** s에 들어 있는 마지막 색코드(§X)를 반환한다(줄바꿈 시 색 유지용). 없으면 fallback을 그대로 돌려준다. */
    private static String lastColorCode(String s, String fallback) {
        String code = fallback;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == '§') { code = "§" + s.charAt(i + 1); i++; }
        }
        return code;
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