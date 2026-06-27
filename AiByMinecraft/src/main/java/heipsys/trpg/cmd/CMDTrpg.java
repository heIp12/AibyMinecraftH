package heipsys.trpg.cmd;

import heipsys.trpg.TRPGGameManager;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /trpg 커맨드 핸들러.
 * 서브커맨드: start / stop / retry / load / list / status / help
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
            case "start"  -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                trpg.startSession(player);
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
            case "load"   -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                if (args.length < 2) { player.sendMessage("§c사용법: /trpg load <씨드>"); return true; }
                trpg.loadSession(player, args[1]);
            }
            case "read"   -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                if (args.length < 2) { player.sendMessage("§c사용법: /trpg read <씨드>"); return true; }
                readGdam(player, args[1]);
            }
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
            case "status" -> sendStatus(player);
            case "me"     -> trpg.openCharacterInfo(player);
            case "log"    -> trpg.openRecordLog(player);
            case "info"   -> trpg.openRecordInfo(player);
            case "keyinfo", "중요" -> trpg.openImportantInfo(player); // 전화번호·능력으로 밝힌 사실
            case "map"    -> trpg.openMap(player);
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
            default -> {
                player.sendMessage("§c알 수 없는 서브커맨드. §f/trpg help §c참조.");
            }
        }
        return true;
    }

    private void sendStatus(Player player) {
        var state = trpg.getState();
        if (!state.isSessionActive()) {
            player.sendMessage("§7현재 진행 중인 TRPG 세션이 없습니다.");
            return;
        }
        player.sendMessage("§e[TRPG 상태]");
        player.sendMessage("§7스테이지: §f" + state.getRoomNumber()
            + "  타임라인: §f" + (state.isDailyPhase() ? "일상(" + state.getDailyTurnsLeft() + "턴)" : state.getTimelineStage() + "단계")
            + "  오염: §f" + state.getCorruption().level);
        player.sendMessage("§7참여 인원: §f" + state.getTotalCount() + "명 (생존 " + state.getAliveCount() + "명)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = List.of("start", "stop", "retry", "next", "load", "read", "replay", "replaylist", "list", "status", "me", "log", "info", "map", "trait", "ending", "givetrait", "help");
            String partial = args[0].toLowerCase();
            return subs.stream()
                .filter(s -> s.startsWith(partial))
                .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("load") || sub.equals("read")) {
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
        player.sendMessage("§f/trpg load <씨드> §7— 저장된 세션 불러오기 (OP)");
        player.sendMessage("§f/trpg read <씨드> §7— .gdam 복호화 후 .json으로 내보내기 (OP)");
        player.sendMessage("§f/trpg replay <코드> §7— 기록된 시작(직업·특성·능력치)으로 그 스테이지만 재현 (OP)");
        player.sendMessage("§f/trpg replaylist §7— 재현 기록 목록");
        player.sendMessage("§f/trpg list §7— 저장된 세션 목록");
        player.sendMessage("§f/trpg stop  §7— 세션 종료 (OP)");
        player.sendMessage("§f/trpg retry §7— 재도전 (OP)");
        player.sendMessage("§f/trpg next  §7— 다음 스테이지로 이동 (OP) — 클리어 후 새 시나리오 시작");
        player.sendMessage("§f/trpg status §7— 현재 상태 확인");
        player.sendMessage("§f/trpg me §7— 내 캐릭터 정보·특성 보기 (핫바 아이템 우클릭도 가능)");
        player.sendMessage("§f/trpg log §7— 전체 대화 기록 열람 (다이얼로그, '기록' 아이템 우클릭도 가능)");
        player.sendMessage("§f/trpg info §7— 수집 정보 열람 (다이얼로그, '기록' 아이템 우클릭도 가능)");
        player.sendMessage("§f/trpg map §7— 가 본 곳으로 현장 약도 그리기 (지도 아이템)");
        player.sendMessage("§f/trpg trait §7— 특성 선택창이 닫힌 경우 다시 열기 (클리어 보상 선택 중에만 유효)");
        player.sendMessage("§f/trpg ending §7— 엔딩 해설 다이얼로그 다시 열기");
        player.sendMessage("§f/trpg givetrait <플레이어> <ID> §7— 시스템 특성 부여 (OP)");
        player.sendMessage("§f/join §7— 진행 중인 세션 참여");
    }
}
