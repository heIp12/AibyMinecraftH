package heipsys;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import net.md_5.bungee.api.ChatColor;

public class CMDReload implements CommandExecutor {
	
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gamerule.reload")) {
            sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
            return true;
        }
        Bukkit.reload();
        sender.sendMessage(ChatColor.GREEN + "서버를 리로드했습니다!");
        return true;
    }

}
