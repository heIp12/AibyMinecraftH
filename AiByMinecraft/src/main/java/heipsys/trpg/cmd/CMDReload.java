package heipsys.trpg.cmd;

import heipsys.AICraft;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /r — config.yml 리로드.
 * API 키/타입 등 설정을 다시 읽고 AI·게임 매니저를 재구성한다.
 * 진행 중인 세션이 있으면 먼저 정리한다. (OP 전용)
 */
public class CMDReload implements CommandExecutor {

    private final AICraft plugin;

    public CMDReload(AICraft plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && !player.isOp()) {
            sender.sendMessage("§c권한이 없습니다.");
            return true;
        }
        // ★리로드 전에 진행 중이던 세션을 먼저 저장·정리한다★ — Bukkit.reload()는 급작스러워
        //   비동기 서술·정리가 끊겨 '방금 플레이'가 로그에 마감되지 않던 문제 방지(로그 endLog·자동세이브 보존).
        try {
            if (plugin.trpgManager != null && plugin.trpgManager.isActive()) {
                sender.sendMessage("§7진행 중이던 세션을 저장·정리한 뒤 리로드합니다…");
                plugin.trpgManager.stopSession(null); // endLog 마감 + 자동세이브(이어하기) 보존
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[reload] 세션 정리 중 오류(무시하고 리로드): " + t.getMessage());
        }
        Bukkit.reload(); // 서버 전체 리로드
        return true;
    }
}
