package heipsys.trpg.cmd;

import heipsys.trpg.TRPGGameManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class CMDJoin implements CommandExecutor {

    private final TRPGGameManager trpg;

    public CMDJoin(TRPGGameManager trpg) {
        this.trpg = trpg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용 가능합니다.");
            return true;
        }
        trpg.joinSession(player);
        return true;
    }
}
