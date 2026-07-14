package heipsys.trpg.cmd;

import heipsys.AICraft;

import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /gdam — ★세션을 끊지 않고★ AI 설정을 실시간 반영한다. (OP 전용)
 *   /gdam reload  : config.yml을 다시 읽어 api-key/타입/모델을 갈아끼운다(게임 진행 유지).
 *   /gdam key     : 다음 키로 수동 전환(여러 키를 번갈아 쓸 때).
 *   /gdam status  : 현재 provider·키 개수·활성 키 표시.
 * 여러 명이 config의 api-key에 ';'로 키를 나눠 넣으면, 한도 소진 시 자동 순환한다(하루 한도 합산).
 */
public class CMDGdam implements CommandExecutor {

    private final AICraft plugin;

    public CMDGdam(AICraft plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && !player.isOp()) {
            sender.sendMessage("§c권한이 없습니다.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "reload";
        switch (sub) {
            case "reload", "r" -> {
                sender.sendMessage("§7[AI] 설정을 다시 읽는 중… (세션은 유지됩니다)");
                plugin.hotReloadAiConfig(sender);
            }
            case "key", "next" -> {
                if (plugin.trpgAi == null) { sender.sendMessage("§cAI가 아직 초기화되지 않았습니다."); return true; }
                if (plugin.trpgAi.advanceKey())
                    sender.sendMessage("§a[AI] 다음 키로 전환했습니다 (키 "
                        + (plugin.trpgAi.activeKeyIndex() + 1) + "/" + plugin.trpgAi.keyCount() + ").");
                else
                    sender.sendMessage("§7키가 1개뿐이라 전환할 수 없습니다. config의 api-key에 ';'로 여러 키를 넣으세요.");
            }
            case "status", "info" -> {
                if (plugin.trpgAi == null) { sender.sendMessage("§cAI가 아직 초기화되지 않았습니다."); return true; }
                sender.sendMessage("§b[AI] provider=" + plugin.trpgAi.providerLabel()
                    + " §7| 키 §f" + plugin.trpgAi.keyCount() + "§7개, 현재 §f"
                    + (plugin.trpgAi.activeKeyIndex() + 1) + "§7번"
                    + (plugin.trpgAi.keyCount() > 1 ? " (한도 소진 시 자동 순환)" : ""));
            }
            default -> sender.sendMessage("§7사용법: §f/gdam <reload|key|status>");
        }
        return true;
    }
}
