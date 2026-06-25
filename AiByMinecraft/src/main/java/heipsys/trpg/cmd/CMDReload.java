package heipsys.trpg.cmd;

import heipsys.AICraft;
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
        sender.sendMessage("§e[AIByMinecraft] 설정을 리로드합니다...");
        plugin.reloadPlugin(sender);
        return true;
    }
}
