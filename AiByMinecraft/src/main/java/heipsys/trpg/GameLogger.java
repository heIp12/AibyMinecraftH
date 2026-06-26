package heipsys.trpg;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
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

    private final File   logDir;
    private final Object lock = new Object();
    private File currentFile;

    public GameLogger(Plugin plugin) {
        this.logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
    }

    // ──────────────────────────────────────────────────────────────
    //  세션 라이프사이클
    // ──────────────────────────────────────────────────────────────

    /** 새 세션 시작 시 호출 — 씨드 기준 실행 횟수를 매겨 새 로그 파일 생성. */
    public void startNewLog(String seed, int room) {
        synchronized (lock) {
            String safe  = sanitize(seed);
            int    count = nextRunCount(safe);
            currentFile  = new File(logDir, safe + "#" + count + ".txt");
            rawWrite("========================================");
            rawWrite("세션 시작  |  씨드: " + seed + "  |  스테이지: " + room
                     + "  |  실행 #" + count);
            rawWrite("========================================");
        }
    }

    /** 같은 파일 안에서 구분선과 함께 회차·단계 전환을 표시. */
    public void section(String title) {
        synchronized (lock) {
            if (currentFile == null) return;
            rawWrite("");
            rawWrite("---------- " + title + " ----------");
        }
    }

    /** 세션 종료 표시. 파일 핸들은 닫지 않고 다음 startNewLog까지 유지. */
    public void endLog(String reason) {
        synchronized (lock) {
            if (currentFile == null) return;
            rawWrite("");
            rawWrite("========== 세션 종료: " + reason + " ==========");
            currentFile = null;
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
        if (content == null) return;
        synchronized (lock) {
            if (currentFile == null) return;
            String clean = strip(content).trim();
            if (clean.isEmpty()) return;
            String head = "[" + LocalTime.now().format(TIME_FMT) + "] [" + category + "]"
                          + (who == null || who.isEmpty() ? "" : " " + who);
            // 여러 줄 내용은 들여쓰기해 가독성 유지
            String[] lines = clean.split("\n");
            if (lines.length == 1) {
                rawWrite(head + " " + lines[0]);
            } else {
                rawWrite(head);
                for (String ln : lines) {
                    if (!ln.isBlank()) rawWrite("    " + ln.trim());
                }
            }
        }
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
        } catch (IOException ignored) {}
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
