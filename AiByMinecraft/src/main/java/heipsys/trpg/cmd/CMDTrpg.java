package heipsys.trpg.cmd;

import heipsys.trpg.TRPGGameManager;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * /trpg 커맨드 핸들러.
 * 서브커맨드: start / stop / retry / next / reserve / load / list / status / help
 * 내부 커맨드 (_confirm, _reroll, _trait 등)는 TRPGGameManager.handleInternalCommand()로 위임.
 */
public class CMDTrpg implements CommandExecutor, TabCompleter {

    private final TRPGGameManager trpg;

    public CMDTrpg(TRPGGameManager trpg) {
        this.trpg = trpg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용 가능합니다.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        // 내부 커맨드 (다이얼로그 버튼 클릭)
        if (args[0].startsWith("_")) {
            trpg.handleInternalCommand(player, args);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start", "s"  -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                // /trpg start setting  (또는 /trpg s s) → 시작 옵션 설정
                if (args.length > 1 && (args[1].equalsIgnoreCase("setting") || args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("s"))) {
                    trpg.handleStartSetting(player, Arrays.copyOfRange(args, 2, args.length));
                } else {
                    trpg.startSession(player);
                }
            }
            case "setting", "set" -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                trpg.handleStartSetting(player, Arrays.copyOfRange(args, 1, args.length));
            }
            case "stop"   -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                trpg.stopSession(player);
            }
            case "retry"  -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                trpg.retrySession(player);
            }
            case "next"   -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                trpg.nextSession(player);
            }
            case "resume" -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                trpg.resumeSession(player);
            }
            case "load"   -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                if (args.length < 2) { player.sendMessage("§c사용법: /trpg load <씨드>"); return true; }
                trpg.loadSession(player, args[1]);
            }
            case "reserve", "예약" -> { // ★#228★ 다음 스테이지에 특정 괴담 예약(1회성) — /trpg next에서 소비
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                if (args.length < 2) { player.sendMessage("§c사용법: /trpg reserve <씨드>  §7(해제: off)"); return true; }
                trpg.reserveNextScenario(player, args[1]);
            }
            // ★/trpg read 비활성(요청)★ — 인게임 명령어에서만 내려 노출·실행되지 않게 한다. 기능(readGdam·exportGdamJson)은 그대로 보존.
            //   ★재연결: 아래 case 주석을 해제하면 즉시 복구된다(탭완성 subs·seed 완성·도움말 3곳도 함께 주석 해제).★
            // case "read"   -> {
            //     if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
            //     if (args.length < 2) { player.sendMessage("§c사용법: /trpg read <씨드>"); return true; }
            //     readGdam(player, args[1]);
            // }
            case "replay" -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                if (args.length < 2) { player.sendMessage("§c사용법: /trpg replay <재현코드>"); return true; }
                trpg.replaySession(player, args[1]);
            }
            case "replaylist" -> {
                List<String> reps = trpg.listReplays();
                if (reps.isEmpty()) { player.sendMessage("§7저장된 재현 기록이 없습니다."); return true; }
                player.sendMessage("§e[재현 기록]");
                reps.forEach(s -> player.sendMessage("§f  " + s + " §8— /trpg replay " + s));
            }
            case "list"   -> listSessions(player);
            case "status" -> trpg.openStatusDialog(player);
            case "me"     -> trpg.openCharacterInfo(player);
            case "log"    -> trpg.openRecordLog(player);
            case "info"   -> trpg.openRecordInfo(player);
            case "keyinfo", "중요" -> trpg.openImportantInfo(player); // 전화번호·능력으로 밝힌 사실
            case "추천", "recommend", "hint" -> trpg.showRecommendations(player); // 정답 모르는 동료의 행동 제안(스포일러 없음)
            case "map"    -> trpg.openMap(player);
            case "이동", "move" -> trpg.openMoveSelector(player); // 아는 곳으로 이동 선언(먼 곳도 경유해 감) — #190
            case "mirror", "소지품", "peek" -> trpg.openSpectatorMirror(player); // 관전자: 보고 있는 인물의 소지품 미러(관전 중 월드 우클릭이 안 먹히는 것의 대체 진입 — 채팅 버튼/자기 인벤 클릭에서 호출)

            case "trait"  -> trpg.reopenTraitDialog(player);
            case "ending" -> trpg.reopenEndingDialog(player);
            case "help"   -> sendHelp(player);
            case "givetrait" -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                if (args.length < 3) { player.sendMessage("§c사용법: /trpg givetrait <플레이어> <특성ID>"); return true; }
                Player tgt = Bukkit.getPlayer(args[1]);
                if (tgt == null) { player.sendMessage("§c플레이어 '" + args[1] + "'을(를) 찾을 수 없습니다."); return true; }
                trpg.giveSystemTrait(player, tgt, args[2]);
            }
            case "jobrefresh" -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                trpg.forceJobRefresh(player); // 직업 풀 강제 재생성(캐시·재시작 불필요)
            }
            default -> {
                player.sendMessage("§c알 수 없는 서브커맨드. §f/trpg help §c참조.");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = List.of("start", "setting", "stop", "retry", "next", "resume", "load", "replay", "replaylist", "list", "status", "me", "log", "info", "추천", "map", "이동", "trait", "ending", "givetrait", "jobrefresh", "help"); // read 비활성(기능 보존): 재연결 시 "read" 추가
            String partial = args[0].toLowerCase();
            return subs.stream()
                .filter(s -> s.startsWith(partial))
                .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String partial2 = args[1].toLowerCase();
            if (sub.equals("setting") || sub.equals("set")) {
                return Stream.of("pregen", "stage", "type", "turnmode", "groupturn", "fanout").filter(s -> s.startsWith(partial2)).collect(Collectors.toList());
            }
            if (sub.equals("start") || sub.equals("s")) {
                return Stream.of("setting").filter(s -> s.startsWith(partial2)).collect(Collectors.toList());
            }
            if (sub.equals("load")) { // read 비활성(기능 보존): 재연결 시 " || sub.equals(\"read\")" 복원
                String partial = args[1].toLowerCase();
                return trpg.listSavedSeeds().stream()
                    .filter(s -> s.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
            }
            if (sub.equals("replay")) {
                String partial = args[1].toLowerCase();
                return trpg.listReplays().stream()
                    .filter(s -> s.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    /** ★보존됨(명령 비활성)★ — /trpg read 명령은 내렸지만 이 기능은 남겨 둔다. 위 switch의 case "read" 주석만 해제하면 재연결된다.
     *  .gdam을 복호화해 평문 .json으로 내보낸다(내용 검수·편집용). */
    @SuppressWarnings("unused")
    private void readGdam(Player player, String seed) {
        String path = trpg.exportGdamJson(seed);
        if (path == null) {
            player.sendMessage("§c씨드 '" + seed + "'를 찾을 수 없거나 복호화에 실패했습니다.");
            return;
        }
        player.sendMessage("§a복호화 완료! 아래 파일을 서버에서 확인하세요:");
        player.sendMessage("§e" + path);
        player.sendMessage("§7(확인 후 파일을 수동으로 삭제해 주세요.)");
    }

    private void listSessions(Player player) {
        List<String> seeds = trpg.listSavedSeeds();
        if (seeds.isEmpty()) {
            player.sendMessage("§7저장된 세션이 없습니다.");
            return;
        }
        player.sendMessage("§e[저장된 세션]");
        seeds.forEach(s -> player.sendMessage("§f  " + s + " §8— /trpg load " + s));
    }

    private void sendHelp(Player player) {
        player.sendMessage("§e[TRPG 커맨드]");
        player.sendMessage("§f/trpg start §7— 새 세션 시작 (OP)");
        player.sendMessage("§f/trpg setting §7— 시작 옵션(자동 사전생성·시작 스테이지) 설정 (OP)");
        player.sendMessage("§f/trpg load <씨드> §7— 저장된 세션 불러오기 (OP)");
        // player.sendMessage("§f/trpg read <씨드> §7— .gdam 복호화 후 .json으로 내보내기 (OP)"); // read 비활성(기능 보존): 재연결 시 주석 해제
        player.sendMessage("§f/trpg replay <코드> §7— 기록된 시작(직업·특성·능력치)으로 그 스테이지만 재현 (OP)");
        player.sendMessage("§f/trpg replaylist §7— 재현 기록 목록");
        player.sendMessage("§f/trpg list §7— 저장된 세션 목록");
        player.sendMessage("§f/trpg stop  §7— 세션 종료 (OP)");
        player.sendMessage("§f/trpg retry §7— 재도전 (OP)");
        player.sendMessage("§f/trpg next  §7— 다음 스테이지로 이동 (OP) — 클리어 후 새 시나리오 시작");
        player.sendMessage("§f/trpg reserve <씨드> §7— 다음 스테이지에 특정 괴담 예약(1회성 · 해제 off) (OP)");
        player.sendMessage("§f/trpg resume §7— 예기치 못하게 끊긴 게임을 자동 저장에서 이어하기 (OP)");
        player.sendMessage("§f/trpg jobrefresh §7— 직업 풀을 AI로 강제 재생성(캐시·재시작 불필요) (OP)");
        player.sendMessage("§f/trpg status §7— 현재 상태 확인");
        player.sendMessage("§f/trpg me §7— 내 캐릭터 정보·특성 보기 (핫바 아이템 우클릭도 가능)");
        player.sendMessage("§f/trpg log §7— 전체 대화 기록 열람 (다이얼로그, '기록' 아이템 우클릭도 가능)");
        player.sendMessage("§f/trpg info §7— 수집 정보 열람 (다이얼로그, '기록' 아이템 우클릭도 가능)");
        player.sendMessage("§f/trpg map §7— 가 본 곳으로 현장 약도 그리기 (지도 아이템)");
        player.sendMessage("§f/trpg 이동 §7— 아는 곳으로 이동 선언(먼 곳도 경유해 감, 이동마다 한 턴 소모, '멈춰'로 중단)");
        player.sendMessage("§f/trpg trait §7— 특성 선택창이 닫힌 경우 다시 열기 (클리어 보상 선택 중에만 유효)");
        player.sendMessage("§f/trpg ending §7— 엔딩 해설 다이얼로그 다시 열기");
        player.sendMessage("§f/trpg givetrait <플레이어> <ID> §7— 시스템 특성 부여 (OP)");
        player.sendMessage("§f/join §7— 진행 중인 세션 참여");
    }
}
