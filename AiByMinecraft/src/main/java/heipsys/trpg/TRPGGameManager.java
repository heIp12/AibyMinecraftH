package heipsys.trpg;

import com.google.gson.JsonObject;
import heipsys.AICraft;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * TRPG 전체 게임 흐름 조율 (메인 오케스트레이터).
 *
 * 방 진행 구조:
 *   입장 → 캐릭터 생성(주사위) → 배역 배정 → 일상 파트 → 괴담 파트 → 클리어/실패
 *
 * ChatListener에서 호출하는 주요 진입점:
 *   handleChat(player, message) — 플레이어 채팅을 현재 단계에 맞게 라우팅
 *   handleCommand(player, subCmd) — /trpg 내부 커맨드 (_confirm, _reroll, _trait 등)
 */
public class TRPGGameManager {

    // ──────────────────────────────────────────────────────────────
    //  GM AI 시스템 프롬프트 (문서 STEP 2-3 기준)
    // ──────────────────────────────────────────────────────────────
    private static final String GM_SYSTEM_BASE = """
너는 하드코어 생존 괴담 TRPG의 '게임 마스터(GM)'야.
플레이어들이 Minecraft 서버에서 채팅으로 행동을 입력하면
그에 맞게 스토리를 진행한다.

## 핵심 원칙

### 괴담 사전 확정 원칙 ★ 최우선
게임 시작 전 .gdam 파일에 확정된 모든 요소는 절대 변경 불가.
플레이어 행동에 맞춰 사후 결정하거나 변경하지 않는다.
플레이어가 논리적으로 타당한 창의적 행동을 하면 인정하되
사전 확정된 규칙 범위 안에서만 판정한다.

### 게임 시작 시 출력 제한 ★
능력치 해설 후 즉시 일상 프롤로그로 전환.
아래 항목은 절대 언급하지 않는다:
- 타임라인/시간 경과에 따른 상황 악화
- 회차 시스템/재도전
- 엔딩 종류
- 아이템/판정 시스템 설명
- 게임 메커니즘 일체

### 타임라인 관리
- 내부적으로만 유지, 직접 고지 금지
- 환경 변화(소음/냄새/온도/색 변화)로만 암시
- 휴식/행동 지연 시 타임라인 진행
- 플레이어가 휴식하는 동안 다른 플레이어는 행동 가능

### 정보 요청 처리
[즉시 제공 — 타임라인 진행 없음]
현재 위치에서 간단히 확인 가능한 것

[단시간 소요 — 소량 타임라인 진행]
방 안 탐색, 서랍 열기, 짧은 이동

[장시간 소요 — 타임라인 유의미하게 진행]
건물 전체 수색, 외부 조사, 장거리 이동

### 판정 시스템
스탯 대비 결과가 불확실한 행동에만 d20 판정.
쉬운 행동 = 자동 성공. 어려운 행동 = 자동 실패.
캐릭터 나이/직업/특성은 판정 보정에 반영.

### 행운 발동
LUK 1~3: 발동 거의 없음
LUK 4~6: 가끔, 작은 우연
LUK 7~9: 종종, 의미 있는 행운
LUK 10+: 자주, 극적인 행운
발동 시: [행운!] 또는 [큰 행운!] 별도 라인에 표기 (불운은 서술로만)

### 꼭두각시 상태
정신력 0 + 괴담 직접 피해 → 꼭두각시 (즉시 게임오버 아님)
서술로만 구현: 플레이어 행동/말을 보고 GM이 적절히 조정.
각성: 강한 충격/오랜 시간/특수 아이템
재발 시: 영구 게임오버

### 아이템 시스템
아이템 지급 시 반드시 아래 태그 출력:
<ITEM_GRANT>
{"item_id":"","player":"ALL 또는 플레이어명","chapter_bound":true}
</ITEM_GRANT>

### 상태 변화 출력
반드시 아래 태그로 출력:
<STATE_UPDATE>
{"player":"","hp_change":0,"san_change":0,"timeline_change":0,"status_change":null,"new_clue":null,"item_grant":null,"item_remove":null}
</STATE_UPDATE>

### 클리어 판정
플레이어가 entity.solution(정석 해결법), entity.exploit_path(역이용), entity.escape(도주 성공)를 달성했을 때
반드시 아래 태그를 출력한다 (배드 엔딩이면 출력하지 않음):
<CLEAR>
{"grade":"A","reason":""}
</CLEAR>
grade 기준:
S: 전원 생존, 타임라인 2단계 이하, 완벽 해결
A: 전원 생존, 정석/역이용 해결
B: 생존자 과반, 어떤 방식이든 해결
C: 생존자 소수, 생존법 달성
D: 1명 생존으로 도주 성공

### GM 내부 비공개 항목 (절대 공개 금지)
- 괴담의 정체 및 스케일
- 타임라인 세부 내용
- 해결법 및 생존법
- 역이용 경로
- 단서 배치 위치
- 스탯 산출 과정
- 일상 파트 턴 수

### 서술 방식
- 2인칭 ("당신은...")
- 중요 판정 결과는 명확히 서술
""";

    // ──────────────────────────────────────────────────────────────
    //  세션 단계
    // ──────────────────────────────────────────────────────────────

    private enum Phase { IDLE, CHAR_CREATION, ROLE_ASSIGNMENT, DAILY, HORROR, CLEAR, GAMEOVER }

    private Phase currentPhase = Phase.IDLE;

    // ──────────────────────────────────────────────────────────────
    //  매니저 참조
    // ──────────────────────────────────────────────────────────────

    private final AICraft             plugin;
    private final AiManager           ai;
    private final GdamGenerator       gdamGen;
    private final GameStateManager    state;
    private final CharacterGenerator  charGen;
    private final TraitManager        traitMan;
    private final ScoreboardManager   scoreMan;
    private final RoleManager         roleMan;
    private final TurnManager         turnMan;
    private final ItemManager         itemMan;
    private final DialogManager       dialogMan;
    private final TraitButtonManager  traitBtn;
    private final CorruptionManager   corruptMan;
    private final ContextCompressor   compressor;

    /** 캐릭터 생성 완료 대기 중인 플레이어 UUID 집합 */
    private final Set<UUID> pendingCreation    = ConcurrentHashMap.newKeySet();
    /** 특성 선택 대기 중인 플레이어 */
    private final Set<UUID> pendingTraitSelect = ConcurrentHashMap.newKeySet();
    private String gmSystemPrompt = GM_SYSTEM_BASE;

    public TRPGGameManager(AICraft plugin, AiManager ai) {
        this.plugin     = plugin;
        this.ai         = ai;
        this.gdamGen    = new GdamGenerator(plugin, ai);
        this.state      = new GameStateManager();
        this.charGen    = new CharacterGenerator(ai);
        this.traitMan   = new TraitManager(ai);
        this.scoreMan   = new ScoreboardManager();
        this.roleMan    = new RoleManager(state);
        this.turnMan    = new TurnManager(state, ai);
        this.itemMan    = new ItemManager(plugin, state);
        this.dialogMan  = new DialogManager();
        this.traitBtn   = new TraitButtonManager();
        this.corruptMan = new CorruptionManager(state);
        this.compressor = new ContextCompressor(ai, state);

        turnMan.setResponseHandler(this::onGmResponse);
    }

    public boolean isActive() { return currentPhase != Phase.IDLE; }

    // ══════════════════════════════════════════════════════════════
    //  세션 시작 (/trpg start)
    // ══════════════════════════════════════════════════════════════

    public void startSession(Player initiator) {
        if (currentPhase != Phase.IDLE) {
            initiator.sendMessage("§c이미 TRPG 세션이 진행 중입니다.");
            return;
        }

        int room = state.isSessionActive() ? state.getRoomNumber() + 1 : 1;
        broadcast("§e§l═══ TRPG 세션 시작 (방 " + room + ") ═══");
        broadcast("§7.gdam 파일을 생성 중입니다...");

        currentPhase = Phase.CHAR_CREATION;

        gdamGen.generate(room).thenAccept(gdam -> {
            if (gdam.has("error")) {
                broadcast("§c[오류] 괴담 생성 실패: " + gdam.get("error").getAsString());
                currentPhase = Phase.IDLE;
                return;
            }

            String seed = gdam.get("seed").getAsString();
            state.startSession(room, seed, gdam);

            // GM AI에 .gdam 데이터 주입
            gmSystemPrompt = buildGmPrompt(gdam);
            ai.clearAll();

            broadcast("§a.gdam 생성 완료. 씨드: §e" + seed);
            broadcast("§7캐릭터를 생성합니다. 잠시 기다려주세요...");

            // 서바이벌 모드 플레이어 전원 캐릭터 생성
            List<Player> survivors = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                .collect(Collectors.toList());

            if (survivors.isEmpty()) {
                broadcast("§c서바이벌 모드 플레이어가 없습니다.");
                currentPhase = Phase.IDLE;
                return;
            }

            survivors.forEach(p -> {
                pendingCreation.add(p.getUniqueId());
                charGen.generate(p)
                    .thenAccept(pd -> {
                        state.addPlayer(pd);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!p.isOnline()) {
                                // 생성 도중 퇴장한 경우 → 대기 해제
                                pendingCreation.remove(p.getUniqueId());
                                checkAllConfirmed();
                                return;
                            }
                            p.sendMessage("§e§l─── 캐릭터 생성 ───");
                            p.sendMessage(charGen.buildSheetMessage(pd, room, state.getCorruption().attempts + 1));
                            dialogMan.showDiceConfirm(p, pd);
                        });
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("캐릭터 생성 실패 (" + p.getName() + "): " + ex.getMessage());
                        pendingCreation.remove(p.getUniqueId());
                        plugin.getServer().getScheduler().runTask(plugin, this::checkAllConfirmed);
                        return null;
                    });
            });
        });
    }

    // ══════════════════════════════════════════════════════════════
    //  세션 종료 (/trpg stop)
    // ══════════════════════════════════════════════════════════════

    public void stopSession(Player admin) {
        if (admin != null) {
            broadcast("§c[GM] " + admin.getName() + "이(가) 세션을 종료했습니다.");
        } else {
            broadcast("§c[GM] 서버 종료로 세션이 정리됩니다.");
        }
        endSession(true);
    }

    private void endSession(boolean resetCorruption) {
        turnMan.cancelAll();
        Bukkit.getOnlinePlayers().forEach(p -> {
            scoreMan.clear(p);
            dialogMan.clearDialog(p);
        });
        itemMan.reclaimChapterItems(new ArrayList<>(Bukkit.getOnlinePlayers()));
        state.endSession(resetCorruption);
        ai.clearAll();
        pendingCreation.clear();
        pendingTraitSelect.clear();
        currentPhase = Phase.IDLE;
    }

    // ══════════════════════════════════════════════════════════════
    //  재도전 (/trpg retry)
    // ══════════════════════════════════════════════════════════════

    public void retrySession(Player admin) {
        if (!state.isSessionActive()) {
            admin.sendMessage("§c활성 세션이 없습니다.");
            return;
        }
        broadcast("§e[TRPG] 재도전합니다. 오염도 상승!");
        state.onRetry();
        broadcast("§c오염 단계: §f" + corruptMan.getLevel() + " (" + corruptMan.getAttempts() + "회차)");
        ai.clearAll();
        gmSystemPrompt = buildGmPrompt(state.getGdamData());
        currentPhase = Phase.DAILY;
        broadcast("§7일상 파트부터 다시 시작합니다.");
        startDailyPhase();
    }

    // ══════════════════════════════════════════════════════════════
    //  채팅 라우팅 (ChatListener → 여기)
    // ══════════════════════════════════════════════════════════════

    public void handleChat(Player player, String message) {
        switch (currentPhase) {
            case CHAR_CREATION -> handleCharCreationChat(player, message);
            case DAILY, HORROR -> handleGameChat(player, message);
            default -> {}
        }
    }

    /** 내부 커맨드 처리 (/trpg _confirm, _reroll, _trait N 등) */
    public void handleInternalCommand(Player player, String[] args) {
        if (args.length == 0) return;
        switch (args[0].toLowerCase()) {
            case "_confirm"    -> confirmStats(player);
            case "_reroll"     -> rerollStats(player);
            case "_trait"      -> handleTraitSelect(player, args.length > 1 ? args[1] : "");
            case "_trait_remove" -> handleTraitRemove(player, args.length > 1 ? args[1] : "");
            case "_use_trait"  -> handleTraitUse(player, args.length > 1 ? args[1] : "");
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  캐릭터 생성 단계
    // ──────────────────────────────────────────────────────────────

    private void handleCharCreationChat(Player player, String message) {
        // 다이얼로그 활성 상태면 다이얼로그 우선
        if (dialogMan.hasActiveDialog(player)) {
            DialogManager.DialogType dtype = dialogMan.getDialogType(player);
            if (dtype == DialogManager.DialogType.TRAIT_SELECTION) {
                handleTraitSelect(player, message.trim());
            } else if (dtype == DialogManager.DialogType.TRAIT_REMOVE) {
                handleTraitRemove(player, message.trim());
            } else {
                // DICE_CONFIRM 다이얼로그 - 확정/재굴림 입력
                String lower = message.trim().toLowerCase();
                if (lower.equals("확정"))   { confirmStats(player); return; }
                if (lower.equals("재굴림")) { rerollStats(player);  return; }
            }
            return;
        }
        String lower = message.trim().toLowerCase();
        if (lower.equals("확정"))   { confirmStats(player); return; }
        if (lower.equals("재굴림")) { rerollStats(player);  return; }
    }

    private void confirmStats(Player player) {
        if (!dialogMan.hasActiveDialog(player) ||
            dialogMan.getDialogType(player) != DialogManager.DialogType.DICE_CONFIRM) return;

        dialogMan.clearDialog(player);
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;

        pd.statsConfirmed = true;
        player.sendMessage("§a스탯이 확정되었습니다!");
        scoreMan.update(player, pd, state.getRoomNumber());
        pendingCreation.remove(player.getUniqueId());
        checkAllConfirmed();
    }

    private void rerollStats(Player player) {
        if (!dialogMan.hasActiveDialog(player) ||
            dialogMan.getDialogType(player) != DialogManager.DialogType.DICE_CONFIRM) return;

        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.diceRollsRemaining <= 0) {
            player.sendMessage("§c재굴림 횟수를 모두 소진했습니다.");
            return;
        }

        dialogMan.clearDialog(player);
        pd.diceRollsRemaining--;
        player.sendMessage("§7재굴림 중...");

        charGen.generate(player).thenAccept(newPd -> {
            newPd.diceRollsRemaining = pd.diceRollsRemaining;
            state.addPlayer(newPd); // 덮어쓰기
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(charGen.buildSheetMessage(newPd, state.getRoomNumber(), state.getCorruption().attempts + 1));
                dialogMan.showDiceConfirm(player, newPd);
            });
        });
    }

    private void checkAllConfirmed() {
        if (!pendingCreation.isEmpty()) return;
        // 모든 플레이어 스탯 확정 → 배역 배정
        broadcast("§a모든 캐릭터 확정 완료. 배역을 배정합니다...");
        currentPhase = Phase.ROLE_ASSIGNMENT;
        assignRolesAndStart();
    }

    // ──────────────────────────────────────────────────────────────
    //  배역 배정
    // ──────────────────────────────────────────────────────────────

    private void assignRolesAndStart() {
        List<Player> players = state.getAllPlayers().stream()
            .map(pd -> Bukkit.getPlayer(pd.uuid))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (players.isEmpty()) {
            broadcast("§c[GM] 접속 중인 플레이어가 없어 배역 배정을 취소합니다.");
            currentPhase = Phase.IDLE;
            return;
        }

        Map<UUID, RoleManager.RoleAssignment> assignments = roleMan.assignRoles(players);

        for (var entry : assignments.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;
            RoleManager.RoleAssignment asgn = entry.getValue();
            p.sendMessage("§e§l[배역 배정]");
            p.sendMessage(roleMan.getRoleBriefing(asgn.roleId(), corruptMan.getLevel()));
            // 배역별 초기 아이템이 있으면 .gdam에서 지급
            giveRoleStartItems(p, asgn.roleId());
        }

        currentPhase = Phase.DAILY;
        startDailyPhase();
    }

    private void giveRoleStartItems(Player player, String roleId) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles")) return;
        for (var el : gdam.getAsJsonArray("roles")) {
            JsonObject r = el.getAsJsonObject();
            if (r.get("role_id").getAsString().equals(roleId) && r.has("start_item")) {
                for (var item : r.getAsJsonArray("start_item")) {
                    JsonObject grant = new JsonObject();
                    grant.addProperty("item_id", item.getAsString());
                    grant.addProperty("player", player.getName());
                    grant.addProperty("chapter_bound", true);
                    itemMan.processGrant(grant, List.of(player));
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  일상 파트
    // ──────────────────────────────────────────────────────────────

    private void startDailyPhase() {
        broadcast("§e[GM] 일상 파트를 시작합니다.");
        // GM AI로 일상 프롤로그 시작
        String prompt = "이제 일상 파트를 시작해줘. .gdam의 daily_prologue를 참고해서 "
            + "각 배역에 맞게 자연스러운 일상 장면으로 시작해. "
            + "첫 턴부터 괴담을 노출하지 마. "
            + "플레이어 수: " + state.getTotalCount();

        ai.callGmAi(gmSystemPrompt, prompt).thenAccept(response -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                broadcastGm(response);
                updateAllScoreboards();
            });
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  게임 중 채팅 처리 (일상/괴담 파트 공통)
    // ──────────────────────────────────────────────────────────────

    private void handleGameChat(Player player, String message) {
        // 다이얼로그 활성 상태면 다이얼로그 우선
        if (dialogMan.hasActiveDialog(player)) {
            DialogManager.DialogType dtype = dialogMan.getDialogType(player);
            if (dtype == DialogManager.DialogType.TRAIT_SELECTION) {
                handleTraitSelect(player, message.trim());
            } else if (dtype == DialogManager.DialogType.TRAIT_REMOVE) {
                handleTraitRemove(player, message.trim());
            }
            return;
        }

        if (!state.hasPlayer(player.getUniqueId())) return; // 참여자가 아님

        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.isDead) return;

        // 꼭두각시 상태: 행동 앞에 상태 표기 → GM이 서술 조정
        String actionMessage = message;
        if ("puppet".equals(pd.status)) {
            player.sendMessage("§8(당신의 의지가 아닌 무언가에 이끌려 행동합니다...)");
            actionMessage = "[꼭두각시] " + message;
        }

        // 특성 버튼 관련 단어 처리는 TurnManager가 GM AI로 전달
        boolean accepted = turnMan.handleAction(player, actionMessage, gmSystemPrompt);
        if (!accepted) {
            player.sendMessage("§7(현재 행동 처리 중입니다. 잠시 기다려주세요.)");
            return;
        }

        player.sendMessage("§7[행동 전달 중...]");

        // 컨텍스트 압축 체크
        compressor.compressIfNeeded();
    }

    // ──────────────────────────────────────────────────────────────
    //  GM AI 응답 처리 (TurnManager 콜백)
    // ──────────────────────────────────────────────────────────────

    private void onGmResponse(TurnManager.GmResponse response) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String raw = response.rawText();
            Player player = response.player();

            // 1. 클리어 판정 (다른 처리 전에 먼저 체크)
            if (currentPhase == Phase.HORROR) {
                JsonObject clearTag = ai.parseClearTag(raw);
                if (clearTag != null) {
                    String grade = clearTag.has("grade") ? clearTag.get("grade").getAsString() : "C";
                    String narrative = ai.stripTags(raw);
                    if (!narrative.isBlank()) broadcastGm(narrative);
                    onClearEnding(grade);
                    return;
                }
            }

            // 2. STATE_UPDATE 파싱 및 적용
            JsonObject stateUpdate = ai.parseStateUpdate(raw);
            if (stateUpdate != null) applyStateUpdate(stateUpdate);

            // 3. ITEM_GRANT 파싱 및 처리
            JsonObject itemGrant = ai.parseItemGrant(raw);
            if (itemGrant != null) {
                itemMan.processGrant(itemGrant, new ArrayList<>(Bukkit.getOnlinePlayers()));
            }

            // 4. 서술 텍스트 출력 (태그 제거)
            String narrative = ai.stripTags(raw);
            if (!narrative.isBlank()) broadcastGm(narrative);

            // 5. 일상 파트 턴 소비
            if (state.isDailyPhase()) {
                boolean phaseChanged = state.consumeDailyTurn();
                if (phaseChanged) {
                    onHorrorPhaseStart();
                } else if (state.getDailyTurnsLeft() == 1) {
                    broadcast("§7§o(어딘가 분위기가 이상해지기 시작한다...)");
                }
            }

            // 6. 스코어보드 갱신
            updateAllScoreboards();

            // 7. 타임라인 4단계 체크
            if (state.getTimelineStage() >= 4) { onBadEnding(); return; }

            // 8. 사망자 체크
            checkDeaths();

            // 9. 능동 특성 버튼 표시
            traitBtn.sendTraitButtons(player, state.getPlayer(player));

            // 10. Entity AI (괴담 파트, 2턴마다)
            if (currentPhase == Phase.HORROR && state.getCurrentTurn() % 2 == 1) {
                String entityLog = state.buildEntityLog(5);
                ai.callEntityAi(buildEntitySystemPrompt(), entityLog).thenAccept(entityResp -> {
                    if (entityResp == null || entityResp.startsWith("§c")) return;
                    String trimmed = entityResp.trim();
                    if (trimmed.isEmpty()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        Bukkit.broadcastMessage("§8§o[" + getEntityName() + "] " + trimmed);
                        if (corruptMan.getLevel() >= 2) corruptMan.addEntityMemory(trimmed);
                    });
                });
            }
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  STATE_UPDATE 적용
    // ──────────────────────────────────────────────────────────────

    private void applyStateUpdate(JsonObject update) {
        String playerName = update.has("player") ? update.get("player").getAsString() : null;
        if (playerName == null) return;

        state.getAllPlayers().stream()
            .filter(pd -> pd.name.equals(playerName))
            .findFirst()
            .ifPresent(pd -> {
                if (update.has("hp_change")) {
                    int delta = update.get("hp_change").getAsInt();
                    pd.hp[0] = Math.max(0, Math.min(pd.hp[1], pd.hp[0] + delta));
                    if (pd.hp[0] <= 0) pd.isDead = true;
                }
                if (update.has("san_change")) {
                    int delta = update.get("san_change").getAsInt();
                    pd.san[0] = Math.max(0, Math.min(pd.san[1], pd.san[0] + delta));
                    if (pd.san[0] <= 0 && pd.hp[0] <= 0) pd.isDead = true;
                }
                if (update.has("timeline_change")) {
                    state.advanceTimeline(update.get("timeline_change").getAsInt());
                }
                if (update.has("status_change") && !update.get("status_change").isJsonNull()) {
                    String newStatus = update.get("status_change").getAsString();
                    Player target = Bukkit.getPlayer(pd.uuid);
                    if ("puppet".equals(newStatus) && "puppet".equals(pd.status)) {
                        // 꼭두각시 재발 → 영구 탈락 (본인에게만 알림)
                        pd.isDead = true;
                        if (target != null) target.sendMessage("§4당신은 완전히 잠식되어 영원히 돌아올 수 없게 되었습니다...");
                    } else {
                        if ("puppet".equals(newStatus) && !"puppet".equals(pd.status)) {
                            if (target != null) target.sendMessage("§5당신의 의지가 서서히 녹아내리는 것이 느껴진다...");
                        } else if ("normal".equals(newStatus) && "puppet".equals(pd.status)) {
                            if (target != null) target.sendMessage("§a정신이 들었다. 잠시 동안 자신으로 돌아온 것 같다.");
                        }
                        pd.status = newStatus;
                    }
                }
                if (update.has("new_clue") && !update.get("new_clue").isJsonNull()) {
                    String clue = update.get("new_clue").getAsString();
                    state.discoverClue(clue);
                    state.log("clue", pd.name, "단서 발견: " + clue);
                }
            });
    }

    // ──────────────────────────────────────────────────────────────
    //  괴담 파트 시작
    // ──────────────────────────────────────────────────────────────

    private void onHorrorPhaseStart() {
        currentPhase = Phase.HORROR;
        broadcast("§c§l─── 괴담이 시작됩니다 ───");
        compressor.compressDailyPhase().thenRun(() -> {
            // GM AI에 괴담 파트 전환 알림
            ai.callGmAi(gmSystemPrompt, "일상 파트가 종료되었다. 이제 괴담 파트를 시작해줘. "
                + "타임라인이 시작됨을 환경 변화로만 암시하고, 직접 언급하지 마.")
              .thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin, () -> broadcastGm(r)));
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  배드 엔딩 / 클리어
    // ──────────────────────────────────────────────────────────────

    private void onBadEnding() {
        if (currentPhase == Phase.GAMEOVER) return;
        currentPhase = Phase.GAMEOVER;
        broadcast("§4§l[배드 엔딩] 타임라인이 수습 불가 단계에 도달했습니다.");
        ai.callGmAi(gmSystemPrompt, "타임라인이 4단계에 도달했다. 배드 엔딩을 서술해줘.")
          .thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin, () -> {
              broadcastGm(r);
              broadcast("§c재도전하려면 §f/trpg retry §c를 입력하세요.");
          }));
    }

    private void checkDeaths() {
        if (state.getAliveCount() == 0) onBadEnding();
    }

    public void joinSession(Player player) {
        if (!state.isSessionActive()) {
            player.sendMessage("§c활성 TRPG 세션이 없습니다.");
            return;
        }
        PlayerData pd = state.getPlayer(player);
        if (pd != null) {
            // 재접속: 스코어보드 복원 및 현재 상태 출력
            scoreMan.update(player, pd, state.getRoomNumber());
            player.sendMessage("§a세션에 재접속했습니다!");
            player.sendMessage(charGen.buildSheetMessage(pd, state.getRoomNumber(), state.getCorruption().attempts + 1));
            traitBtn.sendTraitButtons(player, pd);
        } else {
            player.sendMessage("§c이 세션의 참가자가 아닙니다. 게임은 시작 전에 참여해야 합니다.");
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 버튼 / 선택 처리
    // ──────────────────────────────────────────────────────────────

    private void handleTraitSelect(Player player, String indexStr) {
        if (dialogMan.getDialogType(player) != DialogManager.DialogType.TRAIT_SELECTION) return;

        List<TraitData> choices = dialogMan.getTraitChoices(player);
        try {
            int idx = Integer.parseInt(indexStr);
            if (idx == 4) { // 기존 특성 제거 선택
                dialogMan.clearDialog(player);
                PlayerData pd = state.getPlayer(player);
                if (pd != null) dialogMan.showTraitRemove(player, pd);
                return;
            }
            if (idx < 1 || idx > choices.size()) { player.sendMessage("§c잘못된 번호입니다."); return; }

            TraitData selected = choices.get(idx - 1);
            dialogMan.clearDialog(player);
            pendingTraitSelect.remove(player.getUniqueId());

            PlayerData pd = state.getPlayer(player);
            if (pd != null) {
                traitMan.addTrait(pd, selected);
                player.sendMessage("§a특성 '§f" + selected.name + "§a'을(를) 획득했습니다!");
                scoreMan.update(player, pd, state.getRoomNumber());
            }
        } catch (NumberFormatException e) {
            player.sendMessage("§c번호를 입력해주세요.");
        }
    }

    private void handleTraitRemove(Player player, String indexStr) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        try {
            int idx = Integer.parseInt(indexStr);
            if (idx < 0 || idx >= pd.traits.size()) { player.sendMessage("§c잘못된 번호."); return; }
            TraitData removed = pd.traits.get(idx);
            traitMan.removeTrait(pd, removed.id);
            dialogMan.clearDialog(player);
            pendingTraitSelect.remove(player.getUniqueId());
            player.sendMessage("§c특성 '§f" + removed.name + "§c'을(를) 제거했습니다.");
            scoreMan.update(player, pd, state.getRoomNumber());
        } catch (NumberFormatException e) {
            player.sendMessage("§c번호를 입력해주세요.");
        }
    }

    private void handleTraitUse(Player player, String traitId) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        String msg = traitBtn.buildTraitUseMessage(pd, traitId);
        if (msg == null) { player.sendMessage("§c특성을 찾을 수 없습니다."); return; }
        // GM AI에 특성 발동 메시지 전달
        turnMan.handleAction(player, msg, gmSystemPrompt);
    }

    // ──────────────────────────────────────────────────────────────
    //  클리어 엔딩
    // ──────────────────────────────────────────────────────────────

    private void onClearEnding(String grade) {
        if (currentPhase == Phase.CLEAR || currentPhase == Phase.GAMEOVER) return;
        currentPhase = Phase.CLEAR;

        String finalGrade = corruptMan.getRewardGrade(grade);
        broadcast("§6§l═══════════════════════════════");
        broadcast("§6§l  클리어! 등급: " + grade
            + (corruptMan.getLevel() > 0 ? " (오염 보정 → " + finalGrade + ")" : ""));
        broadcast("§6§l═══════════════════════════════");

        String gdamTheme = getEntityName();

        state.getAllPlayers().stream()
            .filter(pd -> !pd.isDead)
            .forEach(pd -> {
                traitMan.generateClearTraits(finalGrade, pd, gdamTheme)
                    .thenAccept(choices -> {
                        if (choices.isEmpty()) return;
                        Player p = Bukkit.getPlayer(pd.uuid);
                        if (p == null || !p.isOnline()) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            p.sendMessage("§6§l[클리어 보상] 특성을 선택하세요!");
                            boolean canRemove = pd.traits.size() >= 3;
                            dialogMan.showTraitSelection(p, choices, canRemove);
                            pendingTraitSelect.add(p.getUniqueId());
                        });
                    });
            });

        broadcast("§6특성을 선택한 뒤 §f/trpg stop §6으로 세션을 종료하세요.");
    }

    // ──────────────────────────────────────────────────────────────
    //  Entity AI 헬퍼
    // ──────────────────────────────────────────────────────────────

    private String buildEntitySystemPrompt() {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("entity")) {
            return "너는 괴담의 독립적 의지야. 1-2문장으로 짧게 행동/의도만 서술해. 한국어로.";
        }
        JsonObject entity = gdam.getAsJsonObject("entity");
        StringBuilder sb = new StringBuilder();
        sb.append("너는 '").append(entity.has("name") ? entity.get("name").getAsString() : "괴담").append("'이야.\n");
        sb.append("1-2문장으로만 응답. 자신의 행동/의도/감각만 서술. 한국어.\n");
        sb.append("플레이어 스탯·특성·해결법을 절대 직접 언급 금지.\n");
        if (entity.has("ai_context")) {
            JsonObject ctx = entity.getAsJsonObject("ai_context");
            if (ctx.has("personality"))
                sb.append("성격: ").append(ctx.get("personality").getAsString()).append("\n");
            if (ctx.has("initial_pattern"))
                sb.append("행동 패턴: ").append(ctx.get("initial_pattern").getAsString()).append("\n");
        }
        if (entity.has("rules") && entity.get("rules").isJsonArray()) {
            sb.append("규칙: ").append(entity.get("rules").toString()).append("\n");
        }
        return sb.toString();
    }

    private String getEntityName() {
        JsonObject gdam = state.getGdamData();
        if (gdam != null && gdam.has("entity")) {
            JsonObject e = gdam.getAsJsonObject("entity");
            if (e.has("name")) return e.get("name").getAsString();
        }
        return "???";
    }

    // ──────────────────────────────────────────────────────────────
    //  공유 유틸
    // ──────────────────────────────────────────────────────────────

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(msg);
    }

    private void broadcastGm(String msg) {
        // 2인칭 서술이므로 서버 전체에 출력
        Arrays.stream(msg.split("\n")).forEach(line ->
            Bukkit.broadcastMessage("§f" + line));
    }

    private void updateAllScoreboards() {
        state.getAllPlayers().forEach(pd -> {
            Player p = Bukkit.getPlayer(pd.uuid);
            if (p != null) scoreMan.update(p, pd, state.getRoomNumber());
        });
    }

    private String buildGmPrompt(JsonObject gdam) {
        StringBuilder sb = new StringBuilder(GM_SYSTEM_BASE);
        sb.append("\n## .gdam 사전 확정 데이터\n");
        sb.append("씨드: ").append(gdam.has("seed") ? gdam.get("seed").getAsString() : "?").append("\n");
        if (gdam.has("entity")) {
            sb.append("괴담 존재: ").append(gdam.getAsJsonObject("entity").get("name").getAsString()).append("\n");
        }
        // 오염 컨텍스트 추가
        sb.append(corruptMan.buildCorruptionContext(gdam));
        return sb.toString();
    }

    // 상태 조회
    public GameStateManager getState()          { return state; }
    public boolean hasPlayer(Player p)          { return state.hasPlayer(p.getUniqueId()); }
    public DialogManager getDialogManager()     { return dialogMan; }
    public TraitManager getTraitManager()       { return traitMan; }
}
