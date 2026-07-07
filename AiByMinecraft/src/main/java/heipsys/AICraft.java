package heipsys;

import heipsys.trpg.AiManager;
import heipsys.trpg.TRPGGameManager;
import heipsys.trpg.cmd.CMDJoin;
import heipsys.trpg.cmd.CMDReload;
import heipsys.trpg.cmd.CMDTrpg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class AICraft extends JavaPlugin {

    static AICraft instance;

    public TRPGGameManager trpgManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        buildGame(null);

        // 채팅 리스너는 plugin.trpgManager를 동적으로 참조하므로 리로드해도 갱신된다
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        if (getCommand("r") != null) getCommand("r").setExecutor(new CMDReload(this));

        // 누적 비용 주기적 자동 저장(서버 비정상 종료 대비). 5분마다, 변경이 있을 때만 기록.
        // onEnable에서 1회만 등록 — trpgManager 필드는 리로드 시 갱신되므로 항상 현재 매니저를 가리킨다.
        long usageSavePeriod = 20L * 60L * 5L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (trpgManager != null) trpgManager.saveUsagePeriodic();
        }, usageSavePeriod, usageSavePeriod);

        getServer().getScheduler().scheduleSyncDelayedTask(instance, () -> {
            Bukkit.broadcastMessage("§f===========================");
            Bukkit.broadcastMessage("§e[AIByMinecraft] TRPG 준비 완료");
            Bukkit.broadcastMessage("§7/trpg start — 세션 시작 (OP)");
            Bukkit.broadcastMessage("§7/join        — 세션 참여");
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
            sender.sendMessage("§a[AIByMinecraft] 리로드 완료. (API: " + apiType.toUpperCase() + ")");
        }
        getLogger().info("설정 리로드 완료. API 타입: " + apiType.toUpperCase());
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

        AiManager trpgAi = new AiManager(apiKey, apiType);
        trpgAi.initUsagePersistence(new java.io.File(getDataFolder(), "usage.json")); // 전체 누적 비용 로드(영구)
        // AI 모델 설정 (config 'models' 섹션). 비워두면 자동 — claude는 API에서 각 등급 최신 모델을 탐지한다.
        trpgAi.setAutoLatest(getConfig().getBoolean("models.auto-latest", true));
        // 등급별 기본 모델 (비우면 자동/기본). 하위호환: 기존 gm-model-high(고품질) 키도 계속 읽는다.
        String highKey = getConfig().getString("models.high", "");
        if (highKey == null || highKey.isBlank()) highKey = getConfig().getString("gm-model-high", "");
        trpgAi.setHighModelOverride(highKey);
        trpgAi.setMediumModelOverride(getConfig().getString("models.medium", ""));
        trpgAi.setLowModelOverride(getConfig().getString("models.low", ""));
        // 역할별 세부 모델 (GM/괴담/NPC/보조/시나리오). 비우면 등급 기본을 따른다.
        trpgAi.setRoleModels(
            getConfig().getString("models.gm", ""),
            getConfig().getString("models.entity", ""),
            getConfig().getString("models.npc", ""),
            getConfig().getString("models.assistant", ""),
            getConfig().getString("models.gdam", ""));
        // ★생성/응답 속도(effort)★ — Opus 4.8 등 적응형 thinking 깊이(low<medium<high<xhigh<max). 비우면 모델 기본.
        //   낮출수록 thinking 토큰↓ → 시나리오 생성·GM 응답이 빨라진다(품질 트레이드오프). effort-gdam=시나리오 생성.
        trpgAi.setEfforts(
            getConfig().getString("models.effort-gdam", ""),
            getConfig().getString("models.effort-gm", ""),
            getConfig().getString("models.effort-npc", ""),
            getConfig().getString("models.effort-assistant", ""));
        trpgAi.warmUpModels(); // 백그라운드로 최신 모델 탐지 — 메인 스레드 비차단
        final int estBaseline = 4; // 시작 로그엔 접속 인원이 없으니 대표 인원(4인) 기준으로 추정 표시
        getLogger().info("[AI] provider=" + trpgAi.providerLabel()
            + " | 시간당 예상비용(추정) 저=" + trpgAi.hourlyCostLabel(AiManager.Quality.LOW, estBaseline)
            + " 중=" + trpgAi.hourlyCostLabel(AiManager.Quality.MEDIUM, estBaseline)
            + " 고=" + trpgAi.hourlyCostLabel(AiManager.Quality.HIGH, estBaseline));
        trpgManager = new TRPGGameManager(this, trpgAi);

        if (getCommand("trpg") != null) {
            CMDTrpg cmdTrpg = new CMDTrpg(trpgManager);
            getCommand("trpg").setExecutor(cmdTrpg);
            getCommand("trpg").setTabCompleter(cmdTrpg);
        }
        if (getCommand("join") != null) getCommand("join").setExecutor(new CMDJoin(trpgManager));

        return apiType;
    }

    public static AICraft getInstance() { return instance; }
}
