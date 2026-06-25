package heipsys;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class AICraft extends JavaPlugin {
    //rp 모드 만들기
	//상태등은 따로 코드내에 저장해 ai가 추가하거나 지우는 기능만 사용하게 학기(건망증 치료)
	
    static AICraft instance;
    public GameManager gameManager;
    public AIBattleManager aiManager;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // 시스템 객체 초기화
        saveDefaultConfig();

        try {
            saveDefaultConfig();
        } catch (Exception e) {
            getLogger().warning("config.yml을 찾을 수 없거나 에러가 발생하여 기본 설정으로 생성합니다.");
            getConfig().options().copyDefaults(true);
            saveConfig();
        }

        String apiKey = getConfig().getString("api-key");
        String apiType = getConfig().getString("api-type");
        
        
        if (apiKey == null || apiKey.isEmpty()) {
        	Bukkit.broadcastMessage("========================================");
        	Bukkit.broadcastMessage("[경고] config.yml에 API 키가 설정되지 않았습니다!");
        	Bukkit.broadcastMessage("========================================");
        }

        AIBattleManager aiManager = new AIBattleManager(apiKey, apiType, getConfig());
        
        // API 키 유효성 비동기 검사
        getLogger().info("OpenAI API 키 유효성 검사를 시작합니다...");
        aiManager.validateApiKeyAsync().thenAccept(isValid -> {
            if (isValid) {
            	 Bukkit.broadcastMessage("✓ API 키가 성공적으로 인증되었습니다!");

                int logicLevel = getConfig().getInt("logic-level", 2);
                boolean rpMode = getConfig().getBoolean("rp-mode", false);

                String logicDescription = switch (logicLevel) {
                case 0 -> "현실주의 모드: 모든 행동은 마인크래프트 물리 법칙과 논리적 인과관계에 부합해야 합니다.";
                case 1 -> "벨런스 모드: 서사적 합당성이 최우선이며 벨런스를 우선시합니다.";
                case 2 -> "기본 모드: 기본 판정 규칙을 준수하며 적절한 서사를 수용합니다.";
                case 3 -> "판타지 모드: 다소 과장된 행동을 허용하며 영웅적인 서사를 선호합니다.";
                case 4 -> "신화 모드: 비현실적인 기적과 상상력을 적극적으로 반영합니다.";
                case 5 -> "카오스 모드: 유쾌한 혼돈을 허용하며 규칙보다 상상력과 재미가 무조건 우선합니다. AI가 직접혹은 NPC를 조종하여 상황에 개입할수도 있습니다";
                default -> "종말 모드: ai의 상황에서 살아남으세요";
                };
            	Bukkit.broadcastMessage("[AI] " + logicDescription);
                
            } else {
            	 Bukkit.broadcastMessage("🚨 [오류] API 키가 유효하지 않거나 만료되었습니다! config.yml을 확인해주세요.");
            }
        });
        
        gameManager = new GameManager(this, aiManager);
        
        // 명령어 등록
        if (getCommand("r") != null) {
            getCommand("r").setExecutor(new CMDReload());
        }
        if (getCommand("start") != null) {
            getCommand("start").setExecutor(new CMDStart());
        }
        
        // 리스너 등록
        getServer().getPluginManager().registerEvents(new ChatListener(this,gameManager), this);
        getServer().getPluginManager().registerEvents(new BattleRuleListener(gameManager), this);
        getServer().getScheduler().scheduleSyncDelayedTask(instance, ()->{
	        Bukkit.broadcastMessage("§f===========================");
	        Bukkit.broadcastMessage("§c제작: §7gemini");
	        Bukkit.broadcastMessage("§c검수: §7chatgpt");
	        Bukkit.broadcastMessage("§c아이디어: §aheIp12 §f<heIpgames>");
	        Bukkit.broadcastMessage("§f===========================");
        });
    }
    
    @Override
    public void onDisable() {
        
    }

    public static AICraft getInstance() {
        return instance;
    }
}