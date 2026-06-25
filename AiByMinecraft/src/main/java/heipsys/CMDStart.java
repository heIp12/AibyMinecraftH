package heipsys;

import heipsys.AICraft;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class CMDStart implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§c권한이 없습니다.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§c사용법: /start <분:초> [true/false] [아이템개수]");
            return true;
        }

        int totalSeconds = 0;
        try {
            if (args[0].contains(":")) {
                String[] parts = args[0].split(":");
                int minutes = Integer.parseInt(parts[0]);
                int seconds = Integer.parseInt(parts[1]);
                totalSeconds = (minutes * 60) + seconds;
            } else {
                totalSeconds = Integer.parseInt(args[0]) * 60;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§c시간 형식이 올바르지 않습니다. (예: 10:0 또는 20)");
            return true;
        }

        // 기본값 설정
        boolean giveRandomItems = false;
        int randomItemCount = 0;

        // args[1]: 무작위 아이템 지급 여부 확인
        if (args.length >= 2) {
            giveRandomItems = Boolean.parseBoolean(args[1]);
            
            // args[2]: 아이템 개수 지정 (없으면 기본값 3)
            if (giveRandomItems) {
                if (args.length >= 3) {
                    try {
                        randomItemCount = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        randomItemCount = 3;
                    }
                } else {
                    randomItemCount = 3;
                }
            }
        }

        AICraft.getInstance().gameManager.startGame(totalSeconds, giveRandomItems, randomItemCount);
        return true;
    }
}