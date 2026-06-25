package heipsys;

import heipsys.trpg.AiManager;
import heipsys.trpg.TRPGGameManager;
import heipsys.trpg.cmd.CMDJoin;
import heipsys.trpg.cmd.CMDTrpg;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class AICraft extends JavaPlugin {

    static AICraft instance;

    public TRPGGameManager trpgManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        String apiKey  = getConfig().getString("api-key",  "");
        String apiType = getConfig().getString("api-type", "");

        if (apiKey.isEmpty()) {
            Bukkit.broadcastMessage("§c[경고] config.yml에 API 키가 설정되지 않았습니다!");
        }

        if (apiType.isEmpty()) {
            if (apiKey.startsWith("sk-ant-")) apiType = "claude";
            else if (apiKey.startsWith("AIza")) apiType = "gemini";
            else apiType = "openai";
            getLogger().info("API 타입 자동 감지: " + apiType.toUpperCase());
        }

        AiManager trpgAi = new AiManager(apiKey, apiType);
        trpgManager = new TRPGGameManager(this, trpgAi);

        if (getCommand("trpg") != null) getCommand("trpg").setExecutor(new CMDTrpg(trpgManager));
        if (getCommand("join") != null) getCommand("join").setExecutor(new CMDJoin(trpgManager));

        getServer().getPluginManager().registerEvents(new ChatListener(this, trpgManager), this);

        getServer().getScheduler().scheduleSyncDelayedTask(instance, () -> {
            Bukkit.broadcastMessage("§f===========================");
            Bukkit.broadcastMessage("§e[AIByMinecraft] TRPG 준비 완료");
            Bukkit.broadcastMessage("§7/trpg start — 세션 시작 (OP)");
            Bukkit.broadcastMessage("§7/join        — 세션 참여");
            Bukkit.broadcastMessage("§f===========================");
        });
    }

    @Override
    public void onDisable() {
        if (trpgManager != null && trpgManager.isActive()) {
            trpgManager.stopSession(null);
        }
    }

    public static AICraft getInstance() { return instance; }
}
