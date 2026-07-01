package heipsys.trpg;

import org.bukkit.plugin.Plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 세션별 플레이 로그 기록기.
 * 플레이어 입력·GM 출력·시스템 이벤트를 모두 평문 텍스트로 누적한다.
 * 파일명: &lt;씨드&gt;#&lt;실행횟수&gt;.txt (예: VHQW-BMBZ#1.txt)
 * 같은 씨드를 다시 실행하면 #2, #3 … 으로 자동 증가.
 * 디버깅(문제 재현·원인 추적)용 — 색코드는 제거하고 기록한다.
 */
public class GameLogger {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Plugin plugin;
    private final File   logDir;
    private final Object lock = new Object();
    private File currentFile;
    private File eventsFile;                // 구조화 이벤트(JSONL) 사이드카 — HTML 뷰어용
    private long seq = 0;                   // 이벤트 일련번호(시점 재구성·정렬용)
    private boolean writeWarned = false;    // IOException 경고는 1회만 (콘솔 도배 방지)
    private boolean evtWarned   = false;

    public GameLogger(Plugin plugin) {
        this.plugin = plugin;
        this.logDir = new File(plugin.getDataFolder(), "logs");
        ensureDir();
        exportViewer();                     // logs/viewer.html 을 항상 최신으로 비치
    }

    /** 로그 디렉터리 보장. 실패 시 콘솔에 절대경로와 함께 경고. */
    private boolean ensureDir() {
        if (logDir.exists()) return true;
        logDir.mkdirs();
        if (!logDir.exists()) {
            plugin.getLogger().warning("[gamelog] 로그 폴더를 만들지 못했습니다: "
                + logDir.getAbsolutePath() + " (서버 폴더 쓰기 권한을 확인하세요)");
            return false;
        }
        return true;
    }

    /** 현재 기록 중인 로그 파일의 절대경로(없으면 null) — 진단·명령어용. */
    public String currentLogPath() {
        synchronized (lock) {
            return currentFile == null ? null : currentFile.getAbsolutePath();
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  세션 라이프사이클
    // ──────────────────────────────────────────────────────────────

    /** 새 세션 시작 시 호출 — 씨드 기준 실행 횟수를 매겨 새 로그 파일 생성. title=괴담(시나리오) 이름(뷰어 파일목록 표시용). */
    public void startNewLog(String seed, int room, String title) {
        synchronized (lock) {
            ensureDir();                       // 폴더가 사라졌어도 재생성 시도
            writeWarned = false;               // 새 세션마다 경고 1회 재허용
            String safe  = sanitize(seed);
            int    count = nextRunCount(safe);
            currentFile  = new File(logDir, safe + "#" + count + ".txt");
            eventsFile   = new File(logDir, safe + "#" + count + ".events.jsonl");
            evtWarned    = false;
            rawWrite("========================================");
            rawWrite("세션 시작  |  씨드: " + seed + "  |  스테이지: " + room
                     + "  |  실행 #" + count);
            rawWrite("========================================");
            // 이벤트 사이드카 헤더(메타) — 뷰어가 씨드·스테이지를 표시
            JsonObject meta = new JsonObject();
            meta.addProperty("kind", "session");
            meta.addProperty("seed", seed == null ? "" : seed);
            meta.addProperty("stage", room);
            meta.addProperty("run", count);
            boolean hasTitle = title != null && !title.isEmpty() && !"???".equals(title);
            if (hasTitle) meta.addProperty("title", title);   // 뷰어가 파일목록·헤더에 표시할 괴담 이름
            appendEvent("세션", "", "세션 시작 (스테이지 " + room + ")" + (hasTitle ? " — 괴담: " + title : ""), meta);
            // 파일 생성 여부를 콘솔에 절대경로로 알려, "logs 파일이 안 보인다"를 즉시 진단 가능하게 한다.
            if (currentFile.exists())
                plugin.getLogger().info("[gamelog] 플레이 로그 기록 시작 → " + currentFile.getAbsolutePath());
            else
                plugin.getLogger().warning("[gamelog] 로그 파일을 생성하지 못했습니다 → "
                    + currentFile.getAbsolutePath());
        }
    }

    /** 같은 파일 안에서 구분선과 함께 회차·단계 전환을 표시. */
    public void section(String title) {
        synchronized (lock) {
            if (currentFile == null) return;
            rawWrite("");
            rawWrite("---------- " + title + " ----------");
            JsonObject ex = new JsonObject();
            ex.addProperty("kind", "section");
            appendEvent("구분", "", title, ex);
        }
    }

    /** 세션 종료 표시. 파일 핸들은 닫지 않고 다음 startNewLog까지 유지. */
    public void endLog(String reason) {
        synchronized (lock) {
            if (currentFile == null) return;
            rawWrite("");
            rawWrite("========== 세션 종료: " + reason + " ==========");
            JsonObject ex = new JsonObject();
            ex.addProperty("kind", "session");
            appendEvent("세션", "", "세션 종료: " + reason, ex);
            currentFile = null;
            eventsFile  = null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  기록 메서드
    // ──────────────────────────────────────────────────────────────

    /** 플레이어 채팅 입력 (행동/대사). */
    public void logPlayerInput(String playerName, String message) {
        write("입력", playerName, message);
    }

    /** GM이 특정 플레이어에게 전달한 서술/출력. */
    public void logGmOutput(String targetName, String content) {
        write("GM→" + targetName, "", content);
    }

    /** 시스템 이벤트(등장·배역배정·아이템·엔딩 등). */
    public void logEvent(String content) {
        write("이벤트", "", content);
    }

    /** GM에게 전달된 원본 입력(턴 컨텍스트) — 진단용 상세 로그. */
    public void logGmInput(String playerName, String turnInput) {
        write("GM입력", playerName, turnInput);
    }

    /** 임의 카테고리 기록. */
    public void write(String category, String who, String content) {
        record(category, who, content, null);
    }

    /** 능력(특성) 발동 이벤트 — .txt + 구조화 이벤트(kind=ability)로 기록.
     *  로그 뷰어의 '능력만 보기' 필터·시점 재구성에 쓰인다.
     *  @param caster 시전자 표시명, ability 능력 이름, target 대상(없으면 ""), detail 효과키/세부, text 결과/설명 */
    public void logAbility(String caster, String ability, String target, String detail, String text) {
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", "ability");
        if (caster  != null && !caster.isEmpty())  extra.addProperty("actor", caster);
        if (ability != null && !ability.isEmpty()) extra.addProperty("ability", ability);
        if (detail  != null && !detail.isEmpty())  extra.addProperty("detail", detail);
        if (target  != null && !target.isEmpty()) {
            JsonArray to = new JsonArray(); to.add(target); extra.add("to", to);
        }
        String cat  = "능력" + (ability != null && !ability.isEmpty() ? "(" + ability + ")" : "");
        String body = (target != null && !target.isEmpty() ? "→" + target + " " : "")
                    + (text == null ? "" : text);
        record(cat, caster, body, extra);
    }

    /** 능력 결과/설명(능력으로 드러난 정보·서술) — 발동(logAbility)과 구분되는 '결과' 이벤트로 남긴다. */
    public void logAbilityResult(String caster, String ability, String text) {
        if (text == null || text.isBlank()) return;
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", "ability");
        extra.addProperty("phase", "result");
        if (caster  != null && !caster.isEmpty())  extra.addProperty("actor", caster);
        if (ability != null && !ability.isEmpty()) extra.addProperty("ability", ability);
        String cat = "능력" + (ability != null && !ability.isEmpty() ? "(" + ability + ")" : "");
        record(cat, caster, text, extra);
    }

    /** 체력·정신력 변화 + 상태 전이(기절·완전잠식·조종·사망·회복·대가). 로그 뷰어 '상태' 필터·타임라인 진단용.
     *  @param cause 원인/전이 라벨(예: "사망","기절","완전 잠식(관전)","조종(홀림)","행운 구제","회복","능력 대가"). 없으면 "". */
    public void logVital(String actor, int hpDelta, int hpAfter, int hpMax,
                         int sanDelta, int sanAfter, int sanMax, String cause) {
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", "vital");
        if (actor != null && !actor.isEmpty()) extra.addProperty("actor", actor);
        extra.addProperty("hpDelta", hpDelta);   extra.addProperty("hpAfter", hpAfter);   extra.addProperty("hpMax", hpMax);
        extra.addProperty("sanDelta", sanDelta); extra.addProperty("sanAfter", sanAfter); extra.addProperty("sanMax", sanMax);
        if (cause != null && !cause.isEmpty()) extra.addProperty("cause", cause);
        StringBuilder b = new StringBuilder();
        if (hpDelta != 0) b.append("체력 ").append(hpDelta > 0 ? "+" : "").append(hpDelta)
                           .append("(→").append(hpAfter).append("/").append(hpMax).append(") ");
        if (sanDelta != 0) b.append("정신력 ").append(sanDelta > 0 ? "+" : "").append(sanDelta)
                            .append("(→").append(sanAfter).append("/").append(sanMax).append(") ");
        if (cause != null && !cause.isEmpty()) b.append("· ").append(cause);
        if (b.length() == 0) return;
        record("상태", actor, b.toString().trim(), extra);
    }

    /** 개인 전용 시스템 메시지(배역 배정·등장 등) — 로그 뷰어에서 ★그 플레이어 시점에만★ 보이게(NPC·타인 시점 제외). */
    public void logPrivate(String player, String content) {
        if (player == null || player.isEmpty()) { logEvent(content); return; }
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", "private");
        JsonArray to = new JsonArray(); to.add(player); extra.add("to", to);
        record("개인", player, content, extra);
    }

    /**
     * 통신·연락 이벤트(전화·근처발화·방송) — ★발신자(actor)와 수신자(to)를 함께 기록★해
     * 뷰어에서 통화내역·수신자 시점 표시가 가능하게 한다.
     *  @param kind "call"(전화) / "nearby"(근처 발화) / "broadcast"(방송)
     *  @param actor 발신자 표시명, to 수신자 표시명 목록(계정명 금지 — gmDisplayName 등), text 발화 내용
     */
    public void logComm(String kind, String actor, java.util.List<String> to, String text) {
        logComm(kind, actor, to, text, null);
    }

    /**
     * 통신 이벤트 + ★구체 매체 이름(via)★ — 개입 판정은 class(kind)로 하되, 표시 이름은 시대·기기에 따라 다양하게.
     *  @param via 매체의 구체 표시 이름(전화·무전·통신구·서찰·전서구·필담 등). null/빈값이면 기본 라벨만.
     */
    public void logComm(String kind, String actor, java.util.List<String> to, String text, String via) {
        String k = (kind == null || kind.isEmpty()) ? "call" : kind;
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", k);
        if (via != null && !via.isBlank()) extra.addProperty("via", via.trim());
        if (to != null && !to.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String t : to) if (t != null && !t.isEmpty()) arr.add(t);
            if (arr.size() > 0) extra.add("to", arr);
        }
        record("통신", actor, "[" + mediumLabel(k, via) + "] " + (text == null ? "" : text), extra);
    }

    /** 통신 수단 라벨 — 통화·근처·방송·외침·편지·귓속말·수신호 등 가변 수단을 지원(모달리티: 음성/문서/신호). */
    private static String commLabel(String k) {
        if (k == null) return "통화";
        switch (k) {
            case "nearby":    return "근처";
            case "broadcast": return "방송";
            case "shout":     return "외침";
            case "letter":    return "편지";
            case "whisper":   return "귓속말";
            case "signal":    return "수신호"; // 시각·신호형(손짓·봉화·깃발) — 소리도 글도 아닌 제3의 매체
            case "psychic":   return "정신감응"; // 정신·사이킥형(텔레파시·뇌파·신경망) — 물리 감각 밖의 매체
            case "call": default: return "통화";
        }
    }

    /** .txt 라벨 — class(통화/편지…)에 구체 매체명을 ·로 덧붙인다: [편지·전서구]. 뷰어가 class로 인식하고 매체명도 표시. */
    private static String mediumLabel(String k, String via) {
        String base = commLabel(k);
        return (via != null && !via.isBlank() && !via.trim().equals(base)) ? base + "·" + via.trim() : base;
    }

    /**
     * ★변형(변절)된 통신★ — 괴담·NPC가 전달 중 메시지를 바꿨을 때 원본과 변형본을 함께 기록.
     * 뷰어가 "원본 / 변형됨"으로 대조해 보여준다.
     *  @param kind 통신 수단(call/nearby/broadcast/shout/letter/whisper), orig 원문, altered 변형된 문장, cause 변형 주체·이유
     */
    public void logCommTampered(String kind, String actor, java.util.List<String> to,
                                String orig, String altered, String cause) {
        logCommTampered(kind, actor, to, orig, altered, cause, null);
    }

    /** 변형된 통신 + ★구체 매체 이름(via)★. */
    public void logCommTampered(String kind, String actor, java.util.List<String> to,
                                String orig, String altered, String cause, String via) {
        String k = (kind == null || kind.isEmpty()) ? "call" : kind;
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", k);
        if (via != null && !via.isBlank()) extra.addProperty("via", via.trim());
        if (to != null && !to.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String t : to) if (t != null && !t.isEmpty()) arr.add(t);
            if (arr.size() > 0) extra.add("to", arr);
        }
        extra.addProperty("orig", orig == null ? "" : strip(orig).trim());  // 뷰어: 원본 줄
        if (cause != null && !cause.isEmpty()) extra.addProperty("cause", cause);
        record("통신", actor, "[" + mediumLabel(k, via) + "] " + (altered == null ? "" : altered), extra);
    }

    /**
     * ★아이템·단서 획득★ — 뷰어의 아이템/단서 뱃지 + 재생 진행연동 상태패널(그 시점에 아는 정보)에 쓰인다.
     *  @param kind "item"(아이템) 또는 "clue"(단서/힌트), actor 획득자 표시명, name 아이템·단서 내용, note 부가(예: "시작 소지","능력") — 없으면 ""
     */
    public void logItem(String kind, String actor, String name, String note) {
        if (name == null || name.isBlank()) return;
        String k = "clue".equals(kind) ? "clue" : "item";
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", k);
        if (actor != null && !actor.isEmpty()) extra.addProperty("actor", actor);
        extra.addProperty("item", name);
        if (note != null && !note.isEmpty()) extra.addProperty("cause", note);
        String cat  = "clue".equals(k) ? "단서" : "아이템";
        String body = name + (note != null && !note.isEmpty() ? " (" + note + ")" : "");
        record(cat, actor, body, extra);
    }

    /** 로그 뷰어용 계정명↔캐릭터명 별칭 기록 — 같은 인물의 입력·서술 시점을 하나로 통합하게 한다. */
    public void logAlias(String account, String charName) {
        if (account == null || charName == null || account.isEmpty() || charName.isEmpty()) return;
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", "alias");
        extra.addProperty("account", account);
        extra.addProperty("char", charName);
        record("배역", account, "= " + charName, extra);
    }

    /** .txt 기록 + JSONL 이벤트를 한 번에. extra가 있으면 이벤트에 구조화 필드를 합친다. */
    private void record(String category, String who, String content, JsonObject extra) {
        synchronized (lock) {
            if (currentFile == null) return;
            String clean = content == null ? "" : strip(content).trim();
            if (!clean.isEmpty()) {
                String head = "[" + LocalTime.now().format(TIME_FMT) + "] [" + category + "]"
                              + (who == null || who.isEmpty() ? "" : " " + who);
                String[] lines = clean.split("\n");
                if (lines.length == 1) {
                    rawWrite(head + " " + lines[0]);
                } else {
                    rawWrite(head);
                    for (String ln : lines) {
                        if (!ln.isBlank()) rawWrite("    " + ln.trim());
                    }
                }
            } else if (extra == null) {
                return; // 본문도 구조화 정보도 없으면 기록할 게 없다
            }
            appendEvent(category, who, clean, extra);
        }
    }

    /** JSONL 이벤트 한 줄을 사이드카 파일에 추가(lock 보유 상태에서 호출). */
    private void appendEvent(String category, String who, String text, JsonObject extra) {
        if (eventsFile == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t",   LocalTime.now().format(TIME_FMT));
        o.addProperty("seq", ++seq);
        o.addProperty("cat", category == null ? "" : category);
        if (who != null && !who.isEmpty()) o.addProperty("who", who);
        o.addProperty("text", text == null ? "" : text);
        if (extra != null) for (var e : extra.entrySet()) o.add(e.getKey(), e.getValue());
        try (PrintWriter pw = new PrintWriter(new FileWriter(eventsFile, true))) {
            pw.println(o.toString());
        } catch (IOException ex) {
            if (!evtWarned) {
                evtWarned = true;
                plugin.getLogger().warning("[gamelog] 이벤트 로그 쓰기 실패 → "
                    + eventsFile.getAbsolutePath() + " : " + ex.getMessage());
            }
        }
    }

    /** 번들된 로그 뷰어 HTML을 logs/viewer.html 로 복사(매 시작 시 최신화). 리소스가 없으면 조용히 건너뜀. */
    private void exportViewer() {
        if (!logDir.exists() && !ensureDir()) return;
        try (InputStream in = plugin.getResource("log-viewer.html")) {
            if (in == null) return;
            Files.copy(in, new File(logDir, "viewer.html").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { /* 뷰어 비치는 보조 기능 — 실패해도 로그 기록은 계속 */ }
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

    /** 씨드 기준 다음 실행 번호 산출 (기존 파일 중 최대값 +1). */
    private int nextRunCount(String safeSeed) {
        int max = 0;
        File[] files = logDir.listFiles((d, name) ->
            name.startsWith(safeSeed + "#") && name.endsWith(".txt"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                String num  = name.substring(safeSeed.length() + 1, name.length() - 4);
                try { max = Math.max(max, Integer.parseInt(num)); }
                catch (NumberFormatException ignored) {}
            }
        }
        return max + 1;
    }

    private void rawWrite(String line) {
        if (currentFile == null) return;
        try (PrintWriter pw = new PrintWriter(new FileWriter(currentFile, true))) {
            pw.println(line);
        } catch (IOException ex) {
            // 이전엔 조용히 삼켜 "로그가 안 생긴다"의 원인을 알 수 없었다 → 세션당 1회 경고.
            if (!writeWarned) {
                writeWarned = true;
                plugin.getLogger().warning("[gamelog] 로그 쓰기 실패 → "
                    + currentFile.getAbsolutePath() + " : " + ex.getMessage());
            }
        }
    }

    /** 파일명에 쓸 수 없는 문자 제거(씨드의 # 등). */
    private static String sanitize(String seed) {
        if (seed == null || seed.isBlank()) return "session";
        String s = seed.replaceAll("[^A-Za-z0-9가-힣\\-_]", "");
        return s.isEmpty() ? "session" : s;
    }

    /** 마인크래프트 색코드(§X) 제거. */
    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }
}
