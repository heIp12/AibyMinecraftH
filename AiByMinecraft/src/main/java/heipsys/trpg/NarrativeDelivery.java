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

    // 블록(세그먼트) 사이 대기 — 방금 보여준 블록의 글자 수에 비례해 2~5초(읽는 시간만큼).
    private static final long MIN_DELAY_TICKS   = 40L;  // 2초
    private static final long MAX_DELAY_TICKS   = 100L; // 5초
    private static final long FIRST_DELAY_TICKS = 12L;  // 첫 블록은 빠르게(0.6초)
    // 세그먼트(문장 묶음) 목표 글자 — 이 근처에서 ★문장 경계로만★ 끊는다(문장 도중 분할 금지).
    // 약간 남으면 다음 문장도 같이 묶어 한 줄을 꽉 채운다(최대 MAX_CHAT_CHARS=50자까지).
    private static final int SEGMENT_TARGET = 48;
    // 한 라인(MC 채팅 한 줄) 최대 표시 길이(한글 기준). 클라 채팅 폭에 맞춰 — 너무 크면 MC가 임의로 줄바꿈.
    private static final int MAX_CHAT_CHARS = 50;

    private final Plugin plugin;
    private final Map<UUID, ArrayDeque<Block>>  queues  = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>            taskIds = new ConcurrentHashMap<>();

    public NarrativeDelivery(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 출력 한 단위(세그먼트). trailingBlank=true면 출력 뒤에 빈 줄(여백)을 둔다(= 문단 마지막 세그먼트). */
    private record Block(String text, boolean trailingBlank) {}

    /** GM 서술 텍스트를 문단→문장 세그먼트로 쪼개 큐에 넣고 순차 출력 시작 */
    public void deliver(Player player, String raw) {
        if (raw == null || raw.isBlank()) return;
        UUID uuid = player.getUniqueId();
        ArrayDeque<Block> q = queues.computeIfAbsent(uuid, k -> new ArrayDeque<>());

        // 문단(\n) → 문장 경계로 세그먼트(≤SEGMENT_TARGET자)로 묶는다. ★문장 도중에는 절대 끊지 않는다.★
        // 각 세그먼트는 한 블록으로 출력하고(내부 빈 줄 없음), 빈 줄(여백)은 ★문단과 문단 사이에만★ 둔다.
        for (String para : format(raw).split("\n")) {
            if (para.isBlank()) continue;
            List<String> segments = new ArrayList<>();
            StringBuilder seg = new StringBuilder();
            int vis = 0;
            for (String sent : splitSentences(para.trim())) {
                int sv = visualLength(sent);
                // 이 문장을 더하면 목표 초과 → 현재 세그먼트를 끊는다(★문장 경계에서만★)
                if (vis > 0 && vis + 1 + sv > SEGMENT_TARGET) {
                    segments.add(seg.toString()); seg.setLength(0); vis = 0;
                }
                if (vis > 0) { seg.append(' '); vis++; }
                seg.append(sent); vis += sv; // 한 문장이 목표보다 길어도 통째로 둔다(도중 분할 금지)
            }
            if (seg.length() > 0) segments.add(seg.toString());

            // 세그먼트들을 블록으로 큐에 — 문단 ★마지막★ 세그먼트만 뒤에 빈 줄(여백)
            for (int i = 0; i < segments.size(); i++) {
                String block = wrapToBlock(segments.get(i));
                if (block.isEmpty()) continue;
                q.add(new Block(block, i == segments.size() - 1));
            }
        }

        if (!taskIds.containsKey(uuid)) scheduleNext(player, FIRST_DELAY_TICKS);
    }

    /**
     * 한 세그먼트(문장 묶음)를 MAX_CHAT_CHARS자 단위로 하드랩해 ★하나의 블록★(여러 줄, 내부 빈 줄 없음)
     * 문자열로 만든다. 줄 사이 여백(빈 줄)은 출력 단계(sendLine)에서 문단 마지막에만 붙인다.
     */
    private static String wrapToBlock(String text) {
        StringBuilder block = new StringBuilder();
        for (String seg : hardWrap(text.trim())) {
            if (block.length() > 0) block.append('\n');
            block.append(seg);
        }
        return block.toString();
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
        // 닫는 부분은 AI가 종종 -->/—>(엠대시) 등으로 흘리므로 대시류(-, –, —)가 1개 이상이면 모두 허용해 정규화한다.
        s = s.replaceAll("<-([^<>\n]+?)[-–—]+>", "§7<-$1->" + BASE_COLOR);
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

        ArrayDeque<Block> q = queues.get(uuid);
        if (q == null || q.isEmpty()) { queues.remove(uuid); return; }

        Block b = q.poll();
        if (player.isOnline()) sendLine(player, b);
        if (!q.isEmpty()) scheduleNext(player, delayFor(b));
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
            ArrayDeque<Block> q = queues.remove(uuid);
            if (q == null) continue;
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) while (!q.isEmpty()) sendLine(p, q.poll());
        }
    }

    /**
     * 한 블록을 줄 단위로 전송한다. 블록은 deliver에서 미리 하드랩되어 있으므로 재래핑하지 않는다.
     * trailingBlank일 때만(문단 마지막 세그먼트) 뒤에 빈 줄(여백)을 둔다 → 문단 도중엔 빈 줄이 끼지 않는다.
     */
    private void sendLine(Player player, Block block) {
        for (String segment : block.text().split("\n")) {
            player.sendMessage(BASE_COLOR + segment);
        }
        if (block.trailingBlank()) player.sendMessage(""); // 문단 사이에만 여백
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

    private void scheduleNext(Player player, long delayTicks) {
        UUID uuid = player.getUniqueId();
        int tid = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            taskIds.remove(uuid);
            if (!player.isOnline()) { queues.remove(uuid); return; }
            ArrayDeque<Block> q = queues.get(uuid);
            if (q == null || q.isEmpty()) { queues.remove(uuid); return; }
            Block b = q.poll();
            sendLine(player, b);
            if (!q.isEmpty()) scheduleNext(player, delayFor(b)); // 방금 블록 읽는 시간만큼 뒤 다음
            else queues.remove(uuid);
        }, delayTicks).getTaskId();
        taskIds.put(uuid, tid);
    }

    /** 방금 보여준 블록을 읽는 데 걸릴 시간 → 다음 블록까지의 지연(2~5초, 글자 수 비례). */
    private static long delayFor(Block block) {
        int len = visualLength(block.text().replace("\n", "")); // 색코드·줄바꿈 제외 실제 글자 수
        long span = MAX_DELAY_TICKS - MIN_DELAY_TICKS;
        long ticks = MIN_DELAY_TICKS + Math.round(Math.min(len, 40) / 40.0 * span);
        return Math.max(MIN_DELAY_TICKS, Math.min(MAX_DELAY_TICKS, ticks));
    }
}