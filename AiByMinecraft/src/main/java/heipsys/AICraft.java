package heipsys;

import heipsys.trpg.AiManager;
import heipsys.trpg.TRPGGameManager;
import heipsys.trpg.cmd.CMDJoin;
import heipsys.trpg.cmd.CMDTrpg;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class AICraft extends JavaPlugin {

    static AICraft instance;

    // 기존 배틀 시스템
    public GameManager       gameManager;
    public AIBattleManager   aiBattleManager;

    // TRPG 시스템
    public TRPGGameManager   trpgManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        String apiKey  = getConfig().getString("api-key",  "");
        String apiType = getConfig().getString("api-type", "");

        if (apiKey.isEmpty()) {
            Bukkit.broadcastMessage("§c[경고] config.yml에 API 키가 설정되지 않았습니다!");
        }

        // ──────────────────────────────────────────────────────────
        //  API 타입 자동 감지
        // ──────────────────────────────────────────────────────────
        if (apiType.isEmpty()) {
            if (apiKey.startsWith("sk-ant-")) apiType = "claude";
            else if (apiKey.startsWith("AIza")) apiType = "gemini";
            else apiType = "openai";
            getLogger().info("API 타입 자동 감지: " + apiType.toUpperCase());
        }

        // ──────────────────────────────────────────────────────────
        //  기존 배틀 시스템 초기화
        // ──────────────────────────────────────────────────────────
        aiBattleManager = new AIBattleManager(apiKey, apiType, getConfig());
        aiBattleManager.validateApiKeyAsync().thenAccept(valid -> {
            if (valid) {
                Bukkit.broadcastMessage("§a✓ API 키 인증 성공!");
                int logicLevel = getConfig().getInt("logic-level", 2);
                String modeDesc = switch (logicLevel) {
                    case 0 -> "현실주의";
                    case 1 -> "벨런스";
                    case 2 -> "기본";
                    case 3 -> "판타지";
                    case 4 -> "신화";
                    case 5 -> "카오스";
                    default -> "종말";
                };
                Bukkit.broadcastMessage("§7[배틀] 로직 레벨: §f" + modeDesc);
            } else {
                Bukkit.broadcastMessage("§c[오류] API 키가 유효하지 않습니다!");
            }
        });

        gameManager = new GameManager(this, aiBattleManager);

        // ──────────────────────────────────────────────────────────
        //  TRPG 시스템 초기화
        // ──────────────────────────────────────────────────────────
        AiManager trpgAi = new AiManager(apiKey, apiType);
        trpgManager = new TRPGGameManager(this, trpgAi);

        // ──────────────────────────────────────────────────────────
        //  커맨드 등록
        // ──────────────────────────────────────────────────────────
        if (getCommand("r")    != null) getCommand("r").setExecutor(new CMDReload());
        if (getCommand("start") != null) getCommand("start").setExecutor(new CMDStart());
        if (getCommand("trpg") != null) getCommand("trpg").setExecutor(new CMDTrpg(trpgManager));
        if (getCommand("join") != null) getCommand("join").setExecutor(new CMDJoin(trpgManager));

        // ──────────────────────────────────────────────────────────
        //  이벤트 리스너 등록
        // ──────────────────────────────────────────────────────────
        getServer().getPluginManager().registerEvents(
            new ChatListener(this, gameManager, trpgManager), this);
        getServer().getPluginManager().registerEvents(
            new BattleRuleListener(gameManager), this);

        // ──────────────────────────────────────────────────────────
        //  시작 메시지
        // ──────────────────────────────────────────────────────────
        getServer().getScheduler().scheduleSyncDelayedTask(instance, () -> {
            Bukkit.broadcastMessage("§f===========================");
            Bukkit.broadcastMessage("§e[AIByMinecraft] TRPG 모드 준비 완료");
            Bukkit.broadcastMessage("§7/trpg start — TRPG 시작");
            Bukkit.broadcastMessage("§7/start <시간> — 배틀 게임 시작");
            Bukkit.broadcastMessage("§f===========================");
        });
    }

    @Override
    public void onDisable() {
        // 진행 중인 세션 정리
        if (trpgManager != null && trpgManager.isActive()) {
            trpgManager.stopSession(null);
        }
    }

    public static AICraft getInstance() { return instance; }
}
