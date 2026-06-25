package heipsys.trpg.cmd;

import heipsys.trpg.TRPGGameManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /trpg 커맨드 핸들러.
 * 서브커맨드: start / stop / retry / status / help
 * 내부 커맨드 (_confirm, _reroll, _trait 등)는 TRPGGameManager.handleInternalCommand()로 위임.
 */
public class CMDTrpg implements CommandExecutor {

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
            case "load"   -> {
                if (!player.isOp()) { player.sendMessage("§c권한이 없습니다."); return true; }
                if (args.length < 2) { player.sendMessage("§c사용법: /trpg load <씨드>"); return true; }
                trpg.loadSession(player, args[1]);
            }
            case "list"   -> listSessions(player);
            case "status" -> sendStatus(player);
            case "help"   -> sendHelp(player);
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
        player.sendMessage("§7방: §f" + state.getRoomNumber()
            + "  타임라인: §f" + (state.isDailyPhase() ? "일상(" + state.getDailyTurnsLeft() + "턴)" : state.getTimelineStage() + "단계")
            + "  오염: §f" + state.getCorruption().level);
        player.sendMessage("§7참여 인원: §f" + state.getTotalCount() + "명 (생존 " + state.getAliveCount() + "명)");
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
        player.sendMessage("§f/trpg list §7— 저장된 세션 목록");
        player.sendMessage("§f/trpg stop  §7— 세션 종료 (OP)");
        player.sendMessage("§f/trpg retry §7— 재도전 (OP)");
        player.sendMessage("§f/trpg status §7— 현재 상태 확인");
        player.sendMessage("§f/join §7— 진행 중인 세션 참여");
    }
}
