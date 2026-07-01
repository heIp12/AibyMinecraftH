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

    /** 새 세션 시작 시 호출 — 씨드 기준 실행 횟수를 매겨 새 로그 파일 생성. */
    public void startNewLog(String seed, int room) {
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
            appendEvent("세션", "", "세션 시작 (스테이지 " + room + ")", meta);
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
