package heipsys;

import heipsys.trpg.AiManager;
import heipsys.trpg.TRPGGameManager;
import heipsys.trpg.cmd.CMDGdam;
import heipsys.trpg.cmd.CMDReload;
import heipsys.trpg.cmd.CMDTrpg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class AICraft extends JavaPlugin {

    static AICraft instance;

    public TRPGGameManager trpgManager;
    /** 현재 AiManager — /gdam reload가 세션을 끊지 않고 이 인스턴스의 키/모델만 갈아끼운다. */
    public AiManager trpgAi;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        buildGame(null);

        // 채팅 리스너는 plugin.trpgManager를 동적으로 참조하므로 리로드해도 갱신된다
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        if (getCommand("r") != null) getCommand("r").setExecutor(new CMDReload(this));
        if (getCommand("gdam") != null) getCommand("gdam").setExecutor(new CMDGdam(this)); // 세션 유지 AI 설정 리로드/키 순환

        // 누적 비용 주기적 자동 저장(서버 비정상 종료 대비). 5분마다, 변경이 있을 때만 기록.
        // onEnable에서 1회만 등록 — trpgManager 필드는 리로드 시 갱신되므로 항상 현재 매니저를 가리킨다.
        long usageSavePeriod = 20L * 60L * 5L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (trpgManager != null) trpgManager.saveUsagePeriodic();
        }, usageSavePeriod, usageSavePeriod);

        getServer().getScheduler().scheduleSyncDelayedTask(instance, () -> {
            Bukkit.broadcastMessage("§f===========================");
            Bukkit.broadcastMessage("§6§l 괴담크래프트 §f준비 완료");
            Bukkit.broadcastMessage("§7/trpg start — 세션 시작 (OP)");
            Bukkit.broadcastMessage("§7/r           — 설정 리로드 (OP)");
            Bukkit.broadcastMessage("§f===========================");
        });
    }

    @Override
    public void onDisable() {
        if (trpgManager != null) {
            if (trpgManager.isActive()) trpgManager.stopSession(null);
            trpgManager.saveUsageOnDisable(); // 전체 누적 비용 최종 동기 저장
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  설정 리로드 (/r)
    // ──────────────────────────────────────────────────────────────

    /**
     * config.yml을 다시 읽고 AI/게임 매니저를 재구성한다.
     * 진행 중인 세션이 있으면 먼저 종료한다.
     */
    public void reloadPlugin(CommandSender sender) {
        if (trpgManager != null && trpgManager.isActive()) {
            trpgManager.stopSession(null);
            if (sender != null) sender.sendMessage("§7진행 중이던 세션을 종료했습니다.");
        }
        // 새 AiManager가 최신 누적을 이어받도록, 리로드 전에 동기 저장한다(비동기 저장 경쟁 방지).
        if (trpgManager != null) trpgManager.saveUsageOnDisable();

        reloadConfig();
        String apiType = buildGame(sender);

        if (sender != null) {
            sender.sendMessage("§a[괴담크래프트] 리로드 완료. (API: " + apiType.toUpperCase() + ")");
        }
        getLogger().info("설정 리로드 완료. API 타입: " + apiType.toUpperCase());
    }

    /** config 'models' 섹션(등급·역할·effort 오버라이드)을 AiManager에 적용한다. 최초 구성·핫 리로드 공용. */
    private void applyAiSettings(AiManager ai) {
        ai.setAutoLatest(getConfig().getBoolean("models.auto-latest", true));
        String highKey = getConfig().getString("models.high", "");
        if (highKey == null || highKey.isBlank()) highKey = getConfig().getString("gm-model-high", ""); // 하위호환
        ai.setHighModelOverride(highKey);
        ai.setMediumModelOverride(getConfig().getString("models.medium", ""));
        ai.setLowModelOverride(getConfig().getString("models.low", ""));
        ai.setRoleModels(
            getConfig().getString("models.gm", ""),
            getConfig().getString("models.entity", ""),
            getConfig().getString("models.npc", ""),
            getConfig().getString("models.assistant", ""),
            getConfig().getString("models.gdam", ""));
        ai.setEfforts(
            getConfig().getString("models.effort-gdam", ""),
            getConfig().getString("models.effort-gm", ""),
            getConfig().getString("models.effort-npc", ""),
            getConfig().getString("models.effort-assistant", ""));
    }

    /**
     * ★세션 유지 AI 설정 리로드 (/gdam reload)★ — config.yml을 다시 읽어 ★현재 AiManager의 api-key/타입/모델만★
     * 갈아끼운다. 진행 중인 세션·GM/NPC 컨텍스트·누적 비용을 끊지 않는다(전체 리로드 reloadPlugin과 다름).
     * 여러 명이 api-key에 ';'로 키를 나눠 넣고, 한도 소진 시 자동 순환하거나 이 명령으로 새 키를 즉시 반영할 수 있다.
     */
    public void hotReloadAiConfig(CommandSender sender) {
        reloadConfig();
        if (trpgAi == null) { buildGame(sender); return; } // 아직 초기화 전이면 최초 구성
        String apiKey  = getConfig().getString("api-key",  "");
        String apiType = getConfig().getString("api-type", "");
        if (apiType.isEmpty()) {
            if (apiKey.startsWith("sk-ant-")) apiType = "claude";
            else if (apiKey.startsWith("AIza")) apiType = "gemini";
            else apiType = "openai";
        }
        trpgAi.reconfigure(apiKey, apiType); // 세션 유지, 키/타입 교체 + 모델 재탐지 예약
        applyAiSettings(trpgAi);             // 모델/effort 오버라이드 재적용
        trpgAi.warmUpModels();               // 새 키로 최신 모델 백그라운드 재탐지
        String msg = "§a[AI] 설정 리로드 완료 — provider=" + trpgAi.providerLabel()
            + ", 키 " + trpgAi.keyCount() + "개"
            + (trpgAi.keyCount() > 1 ? " §7(한도 소진 시 자동 순환)" : "");
        if (sender != null) sender.sendMessage(msg);
        getLogger().info("[AI] 핫 리로드 — provider=" + trpgAi.providerLabel() + ", 키 " + trpgAi.keyCount() + "개");
    }

    // ──────────────────────────────────────────────────────────────
    //  AI/게임 매니저 구성 (최초 기동 + 리로드 공용)
    // ──────────────────────────────────────────────────────────────

    /** @return 결정된 API 타입 */
    private String buildGame(CommandSender sender) {
        String apiKey  = getConfig().getString("api-key",  "");
        String apiType = getConfig().getString("api-type", "");

        if (apiKey.isEmpty()) {
            String warn = "§c[경고] config.yml에 API 키가 설정되지 않았습니다!";
            if (sender != null) sender.sendMessage(warn);
            else Bukkit.broadcastMessage(warn);
        }

        if (apiType.isEmpty()) {
            if (apiKey.startsWith("sk-ant-")) apiType = "claude";
            else if (apiKey.startsWith("AIza")) apiType = "gemini";
            else apiType = "openai";
            getLogger().info("API 타입 자동 감지: " + apiType.toUpperCase());
        }

        trpgAi = new AiManager(apiKey, apiType);
        trpgAi.initUsagePersistence(new java.io.File(getDataFolder(), "usage.json")); // 전체 누적 비용 로드(영구)
        applyAiSettings(trpgAi); // 모델/effort 오버라이드 적용(config 'models' 섹션) — 리로드와 공용
        trpgAi.warmUpModels(); // 백그라운드로 최신 모델 탐지 — 메인 스레드 비차단
        final int estBaseline = 4; // 시작 로그엔 접속 인원이 없으니 대표 인원(4인) 기준으로 추정 표시
        getLogger().info("[AI] provider=" + trpgAi.providerLabel()
            + " | 시간당 예상비용(추정) 저=" + trpgAi.hourlyCostLabel(AiManager.Quality.LOW, estBaseline)
            + " 중=" + trpgAi.hourlyCostLabel(AiManager.Quality.MEDIUM, estBaseline)
            + " 고=" + trpgAi.hourlyCostLabel(AiManager.Quality.HIGH, estBaseline));
        trpgManager = new TRPGGameManager(this, this.trpgAi);

        if (getCommand("trpg") != null) {
            CMDTrpg cmdTrpg = new CMDTrpg(trpgManager);
            getCommand("trpg").setExecutor(cmdTrpg);
            getCommand("trpg").setTabCompleter(cmdTrpg);
        }

        return apiType;
    }

    public static AICraft getInstance() { return instance; }
}
