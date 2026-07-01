package heipsys.trpg;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import heipsys.AICraft;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import heipsys.trpg.model.ItemInstance;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * TRPG 전체 게임 흐름 조율 (메인 오케스트레이터).
 *
 * 스테이지 진행 구조:
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
    // GM 시스템 프롬프트는 PromptBuilder로 이전됨(god-class 분할). PromptBuilder.GM_SYSTEM_BASE 참조.

    // ──────────────────────────────────────────────────────────────
    //  세션 단계
    // ──────────────────────────────────────────────────────────────

    private enum Phase { IDLE, CHAR_CREATION, ROLE_ASSIGNMENT, DAILY, HORROR, CLEAR, GAMEOVER }

    private record OracleChoice(String text, String outcome) {}

    private static final Set<String> COMM_ITEM_KEYWORDS = Set.of(
        "전화", "phone", "폰", "무전", "walkie", "radio", "라디오", "휴대폰", "핸드폰", "스마트폰", "통신", "intercom", "인터콤"
    );
    /** 이 특성을 가진 플레이어의 연락처는 모두가 안다 (공인 연락처) */
    private static final Set<String> CELEBRITY_TRAIT_KEYWORDS = Set.of(
        "유명", "셀럽", "스타", "인플루언서", "연예인", "celebrity", "famous"
    );
    /** 이 특성을 가진 플레이어는 모두의 연락처를 안다 (정보 수집) */
    private static final Set<String> HACKER_TRAIT_KEYWORDS = Set.of(
        "해커", "해킹", "hacker", "도청", "감청", "스토커", "흥신소", "탐정", "정보상", "정보원"
    );

    private Phase currentPhase = Phase.IDLE;
    /** 포기/종료 시 에필로그·해설을 비동기로 공개하는 중인지 (중복 종료 방지) */
    private boolean concludingEnding = false;

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
    private final NarrativeDelivery   narrativeDelivery;
    private final GameLogger          gameLogger;
    private final ReplayManager       replayMan;
    private final MapManager          mapMan;

    /** 재현(replay) 파일로 시작한 세션 — 해당 스테이지만 진행, 다음 스테이지 진행 차단 */
    private boolean replayLock = false;
    /** 친숙한 친구들 모드 — 유명 괴담/SCP/크리피파스타를 스테이지에 맞춰 사용 */
    private boolean familiarMode = false;
    /** 친숙 모드 괴담 범위 필터: common/heard/minor/urban/scp/korean/rule/random */
    private String familiarFilter = "random";

    /** 캐릭터 생성 완료 대기 중인 플레이어 UUID 집합 */
    private final Set<UUID> pendingCreation    = ConcurrentHashMap.newKeySet();
    /** 특성 선택 대기 중인 플레이어 */
    private final Set<UUID> pendingTraitSelect = ConcurrentHashMap.newKeySet();
    /** 스토리에 이미 등장한(spawn된) 플레이어 */
    private final Set<UUID> spawnedPlayers      = ConcurrentHashMap.newKeySet();
    /** 특성 발동 대기 중인 플레이어 UUID → 트레이트 ID (행동 입력 전까지 유지) */
    private final Map<UUID, String> pendingTraitActivation = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingPrayerInput = new ConcurrentHashMap<>(); // UUID → traitId
    private final Map<UUID, String> pendingOracleInput = new ConcurrentHashMap<>(); // UUID → traitId
    private final Map<UUID, Integer> pendingLuckModifier   = new ConcurrentHashMap<>();
    private final Map<UUID, List<OracleChoice>> pendingOracleChoices = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingSaintTrait = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingAreaScanInput = new ConcurrentHashMap<>(); // UUID → traitId
    private final Map<UUID, String> pendingLinkAllyInput = new ConcurrentHashMap<>(); // UUID → traitId
    private final Map<UUID, String> pendingRemoteSenseInput = new ConcurrentHashMap<>(); // UUID → traitId (원격 감지)
    private final Map<UUID, String> pendingForesightInput = new ConcurrentHashMap<>(); // UUID → traitId (결과 예지)
    private final Map<UUID, String> pendingActionBoost = new ConcurrentHashMap<>(); // UUID → 다음 행동 확정/운명 보정문 (B1/C4)
    private final Map<UUID, String> pendingBoostTrait = new ConcurrentHashMap<>();  // UUID → 보정문 출처 traitId (취소 환원용)
    /** 동반회귀(group_rewind) 발동 시 이번 스테이지 재도전 제약을 해제한다(스테이지3+여도 허용). */
    private boolean forceRetryAllowed = false;
    /** GM이 개설한 기기 통신 채널: A → {B, C, ...} (양방향 저장) */
    private final Map<UUID, Set<UUID>> commChannels = new ConcurrentHashMap<>();
    /** 탈락 안내 메시지 도배 방지: UUID → 마지막 안내 시각(millis) */
    private final Map<UUID, Long> lastDeadNotice = new ConcurrentHashMap<>();
    /** 중복 입력 디바운스: 같은 플레이어가 같은 메시지를 짧은 시간 내 재전송하면 1회만 처리(중복 출력·도배 방지) */
    private static final long INPUT_DEBOUNCE_MS = 2500L;
    private final Map<UUID, String> lastInputMsg = new ConcurrentHashMap<>();
    private final Map<UUID, Long>   lastInputAt  = new ConcurrentHashMap<>();
    // 막힘 감지: 연속 '무진전'(새 단서·이동·아이템·피해 없음) 턴 수. STUCK_THRESHOLD 도달 시 자동 추천 1회.
    private static final int STUCK_THRESHOLD = 3;
    private final Map<UUID, Integer> stuckTurns = new ConcurrentHashMap<>();
    /** 기절 상태 회복 예약 태스크 (UUID → 스케줄러 태스크) */
    /** 캐릭터 생성 전 선제 배역 배정 결과 (UUID → 배역 JsonObject) */
    private final Map<UUID, JsonObject> preAssignedRoleData = new ConcurrentHashMap<>();
    /** 캐릭터 생성 전 선제 배역 배정 결과 (UUID → RoleAssignment) */
    private final Map<UUID, RoleManager.RoleAssignment> preAssignments = new ConcurrentHashMap<>();
    /** 플레이어가 없어 GM이 직접 조종하는 배역 ID 집합 */
    private final Set<String> gmNpcRoleIds = ConcurrentHashMap.newKeySet();
    /** 중요 NPC 현재 위치 (npc_id → zone_id) — .gdam npcs[].zone 기본값, 세션 중 이동 시 갱신 */
    private final Map<String, String> npcZones = new ConcurrentHashMap<>();
    /** 중요 NPC 연락처 번호 (npc_id → 번호) — 세션 시작 시 부여, 플레이어 번호와 중복 회피 */
    private final Map<String, String> npcContactNumbers = new ConcurrentHashMap<>();
    /** NPC별 지능·언어 수준 (npc_id → 1~5) — 주사위로 부여, 말투·어휘에 반영. 세션당 고정. */
    private final Map<String, Integer> npcIntel = new ConcurrentHashMap<>();
    /** NPC가 플레이 중 새로 보고 알게 된 정보(npc_id → 목록). 자율 AI가 <NPC_LEARN>로 누적 → 이후 떠올려 쓰거나 플레이어에게 전한다. */
    private final Map<String, List<String>> npcAcquired = new ConcurrentHashMap<>();
    /** NPC id → 그 NPC와 직접 대화(통화 포함)가 있었던 마지막 턴. 대화 중인 NPC를 자율 AI가 중복 구동해 맥락을 오염(되묻기·모순)시키지 않도록 게이트. */
    private final Map<String, Integer> npcLastDirectTurn = new ConcurrentHashMap<>();
    /** 금지워드형 괴담: 입에 올리면(입력) 즉시 파국이 되는 단어. 빈 값이면 이 메커니즘 비활성. 재시도 시 변경. */
    private volatile String forbiddenWord = "";

    /** 위상 이탈(phase_out) 중인 플레이어의 남은 무적 턴 수 (uuid → turns). 0 이하면 정상. */
    private final Map<UUID, Integer> phaseOutTurns = new ConcurrentHashMap<>();
    /** 괴담 변신(gdam_morph) 중인 플레이어의 남은 변신 턴 수 (uuid → turns). 0 이하면 정상. 변신 중엔 조작 불가(GM 구동). */
    private final Map<UUID, Integer> morphTurns = new ConcurrentHashMap<>();
    /** 관조자의 눈(observer_sight) 지속 중인 플레이어의 남은 턴 수 (uuid → turns). ★1턴 고정이라 실제로는 지속 등록되지 않지만, 구형 세이브 호환을 위해 틱 처리는 유지한다. */
    private final Map<UUID, Integer> observerTurns = new ConcurrentHashMap<>();
    /** 통신 개방 능력(gm_directive 통신형) — 이 '턴 번호'에 발동한 플레이어는 그 턴 동안 통신 제한(두절·기기부재)을 무시하고 @발신 가능. */
    private final Map<UUID, Integer> commBypassTurn = new ConcurrentHashMap<>();
    /** 통신 개방이 '은밀형'인지(uuid → true). true면 그 통신을 괴담이 감지하지 못한다(통신 유인·추적 반응 억제). */
    private final Map<UUID, Boolean> commBypassStealth = new ConcurrentHashMap<>();
    /** 상태창 OVERVIEW 캐시 — FULL(전체 개요)은 시작 1회, NOW(눈앞의 시나리오)는 국면(단계) 바뀔 때 갱신. 영화 예고편식·스포일러 금지. */
    private String scenarioOverviewFull = "";
    private String scenarioOverviewNow  = "";
    private int    overviewNowStage     = -999; // NOW를 생성한 timelineStage. 이 값과 현재 단계가 다르면 재생성.
    private volatile boolean overviewFullPending = false; // 중복 생성 방지(비동기 in-flight)
    private volatile boolean overviewNowPending  = false;
    // (상태창 RECENT는 GameStateManager.getLastFiredEventLabel()에서 직접 읽는다 — 별도 캐시 불필요)
    /** 동물 소생(revive_as_animal)으로 동물 형태가 된 플레이어 (uuid). 제한 행동만 가능(능력·통신 불가), 피해 시 진짜 소멸. */
    private final Set<UUID> animalForm = ConcurrentHashMap.newKeySet();
    /** 능력 대가(cost_stun)로 행동불능 상태인 플레이어의 남은 턴 수 (uuid → turns). 0 이하면 정상. */
    private final Map<UUID, Integer> stunTurns = new ConcurrentHashMap<>();
    /** 빙의(possess_npc) 중인 플레이어 → 빙의한 NPC 이름. 본체는 무방비, 행동은 그 NPC 몸으로. */
    private final Map<UUID, String> possessingNpc = new ConcurrentHashMap<>();

    /** 자동 세이브용 직렬화기 + 마지막 저장 턴(중복 저장 방지). */
    private final com.google.gson.Gson saveGson = new com.google.gson.Gson();
    private int lastAutoSaveTurn = -1;

    /** 시간 회귀(time_rewind)용 턴 스냅샷 버퍼 + 마지막 캡처 턴. 이번 세션(스테이지)의 첫 턴까지 회귀할 수 있도록
     *  스테이지 전체를 보관한다(메모리 안전용 상한만 둠; 통상 스테이지 길이를 크게 상회하므로 사실상 무제한). */
    private final java.util.Deque<RewindSnapshot> rewindBuffer = new java.util.ArrayDeque<>();
    private static final int REWIND_BUFFER_MAX = 1000;
    private int lastRewindCaptureTurn = -1;
    /** 한 턴 시점의 복원 가능한 핵심 상태(체력·정신력·상태·위치·사망) + GM 컨텍스트 마커. */
    private static final class RewindSnapshot {
        final int turn, gmMark, timelineStage;
        final Map<UUID, int[]>   vitals = new HashMap<>(); // [hp0,hp1,san0,san1]
        final Map<UUID, String>  status = new HashMap<>();
        final Map<UUID, String>  zone   = new HashMap<>();
        final Map<UUID, String>  spot   = new HashMap<>();
        final Map<UUID, Boolean> dead   = new HashMap<>();
        RewindSnapshot(int turn, int gmMark, int timelineStage) {
            this.turn = turn; this.gmMark = gmMark; this.timelineStage = timelineStage;
        }
    }
    /** 미등장 배역별 서술 호출 횟수 (비트 진행 추적) */
    private final Map<UUID, Integer> preSpawnCallCounts = new ConcurrentHashMap<>();
    /** critical NPC가 마지막으로 행동한 괴담 턴 — 무행동 워치독용 (오래 안 나오면 강제 등장) */
    private int lastNpcBeatTurn = 0;
    /** 마지막 플레이어 입력(행동) 시각(ms) — 무행동 가속 워치독용. 0이면 미설정. */
    private volatile long lastPlayerActionMs = 0L;
    /** 마지막 무행동 가속 발동 시각(ms) — 중복 가속 방지. */
    private long lastIdleAccelMs = 0L;
    /** 무행동이 이 시간 이상 지속되면 시간·위협을 한 걸음 진행시킨다(머뭇거림이 안전하지 않게). */
    private static final long IDLE_ACCEL_MS = 180_000L; // 3분
    /** 클리어 보상 특성 성장 3선택지 — /trpg trait 재열기용 */
    private final Map<UUID, TraitManager.StageEndChoices> pendingStageEndChoices = new ConcurrentHashMap<>();
    private final Map<UUID, String[]> pendingStageEndNames = new ConcurrentHashMap<>();
    /** 마지막으로 생성된 엔딩 해설 페이지 — /trpg ending 재열기용 */
    private List<DialogManager.EndingSection> lastEndingPages = null;
    /** 직전 클리어가 다음 스테이지 진출 가능한지. 스테이지 3+는 해결판정(완전 해결)만 진출 허용, 단순 생존은 재도전만. */
    private boolean nextStageUnlocked = true;
    /** 정규 마지막 스테이지. 5 클리어 후 게임 종료(총평). 단 리트라이 0회면 보너스 6스테이지 해금. */
    private static final int FINAL_STAGE = 5;
    /** 이번 게임(전체 런)에서 한 번이라도 재도전했는가 — true면 보너스 6스테이지 불가. 새 게임 시작 시 초기화. */
    private boolean retriedThisRun = false;
    /** 라운드 종료 시 백그라운드로 미리 만들어 둔 다음 시나리오(없으면 null). /trpg next에서 소비. */
    private CompletableFuture<JsonObject> pregenFuture = null;
    /** pregenFuture가 대상으로 하는 절대 스테이지 번호(-1=없음). nextRoom과 일치할 때만 재사용. */
    private int pregenRoom = -1;
    /** 시작 설정: 다음 시나리오 자동 사전생성 on/off (기본 on). /trpg setting pregen 으로 토글. */
    private boolean autoPregen = true;
    /** 시작 설정: 신규 세션 시작 스테이지(1~6). 2 이상이면 (start-1)단계 시작 스펙 보정. */
    private int startStage = 1;
    private String gmSystemPrompt = PromptBuilder.GM_SYSTEM_BASE;
    private BossBar loadingBar;

    public TRPGGameManager(AICraft plugin, AiManager ai) {
        this.plugin     = plugin;
        this.ai         = ai;
        this.gdamGen    = new GdamGenerator(plugin, ai);
        this.state      = new GameStateManager();
        this.charGen    = new CharacterGenerator(ai, plugin.getDataFolder());
        charGen.refreshJobPools(); // 서버 시작 시 캐시 로드 + 필요 시 AI 갱신 (비동기)
        this.traitMan   = new TraitManager(ai);
        this.scoreMan   = new ScoreboardManager(state);
        this.replayMan  = new ReplayManager(plugin);
        this.roleMan    = new RoleManager(state);
        this.turnMan    = new TurnManager(state, ai);
        this.itemMan    = new ItemManager(plugin, state);
        this.dialogMan         = new DialogManager();
        this.dialogMan.setImportantInfoOpener(this::openImportantInfo); // 중요 정보(전화번호·능력으로 밝힌 사실)
        this.state.setSpawnedCheck(spawnedPlayers::contains); // 미등장 배역을 GM 서술에서 제외하기 위한 등장 판별 주입
        this.traitBtn          = new TraitButtonManager();
        this.corruptMan        = new CorruptionManager(state);
        this.compressor        = new ContextCompressor(ai, state);
        this.narrativeDelivery = new NarrativeDelivery(plugin);
        this.gameLogger        = new GameLogger(plugin);
        this.mapMan            = new MapManager(plugin, state);

        turnMan.setResponseHandler(this::onGmResponse);
        startIncapacitationWatchdog(); // 전원 무력화(완전잠식·기절) 시 AI 없이 시스템이 시간 진행
    }

    public boolean isActive() { return currentPhase != Phase.IDLE; }

    /**
     * 무력화 워치독: 살아있고 등장한 플레이어 ★전원★이 행동 불가(완전잠식·기절)면,
     * AI(GM) 호출 없이 ★시스템★이 한 턴씩 시간을 진행시킨다(턴·시계·회복 카운터).
     * → 아무도 입력할 수 없어 게임이 영영 멈추던 문제 해결. 한 명이라도 행동 가능해지면 자동으로 멈춘다.
     * 플러그인 로드 시 1회 등록(상시) — 비활성/정상 상황에선 즉시 return하므로 부하 없음.
     */
    private void startIncapacitationWatchdog() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!isActive()) return;
            if (currentPhase != Phase.DAILY && currentPhase != Phase.HORROR) return;
            List<PlayerData> aliveSpawned = state.getAllPlayers().stream()
                .filter(pd -> !pd.isDead && spawnedPlayers.contains(pd.uuid))
                .collect(Collectors.toList());
            if (aliveSpawned.isEmpty()) return; // 살아 등장한 사람이 없으면 종료 로직이 따로 처리
            boolean anyoneCanAct = aliveSpawned.stream().anyMatch(pd ->
                pd.puppetRecoveryTurns <= 0
                && !animalForm.contains(pd.uuid) // 동물 형태는 시나리오를 풀 수 없음 → 행동 가능자로 치지 않음(동물만 남으면 워치독이 진행)
                && !("faint".equals(pd.status) && pd.faintTurnsRemaining > 0));
            if (anyoneCanAct) { maybeAccelerateIdle(); return; } // 행동 가능 → 정상. 단 너무 오래 무행동이면 시간·위협 가속.
            // ★전원 무력화★ → AI 없이 시스템이 한 턴 진행(시간·시계·회복 카운터). 누군가 회복하면 다음 틱에서 멈춘다.
            state.nextTurn();
            tickFaintCounters();
            updateAllScoreboards();
        }, 200L, 200L); // 10초마다(전원 무력화 또는 장시간 무행동일 때만 실제로 동작)
    }

    /**
     * 무행동 가속(#12): 행동할 수 있는데도 아무도 오래(IDLE_ACCEL_MS=3분) 입력하지 않으면,
     * GM이 ★인게임 시간을 적절히 건너뛰어★(대기·정체 구간이면 TIME_SKIP으로 몇 시간~며칠) 다음 국면으로
     * 시나리오를 가속한다. 급박한 상황이면 시간을 건너뛰지 않고 위협만 한 걸음 진전. 가속 사이 최소 간격 유지.
     */
    private void maybeAccelerateIdle() {
        if (currentPhase != Phase.DAILY && currentPhase != Phase.HORROR) return;
        long now = System.currentTimeMillis();
        if (lastPlayerActionMs <= 0) return;                 // 아직 기준 시각 미설정
        if (now - lastPlayerActionMs < IDLE_ACCEL_MS) return; // 충분히 무행동 상태가 아님
        if (now - lastIdleAccelMs   < IDLE_ACCEL_MS) return;  // 직전 가속과 너무 가까움
        // 접속 중인 등장 플레이어가 있어야 GM 진행 의미가 있음
        Player viewer = null;
        for (UUID u : spawnedPlayers) { Player p = Bukkit.getPlayer(u); if (p != null && p.isOnline()) { viewer = p; break; } }
        if (viewer == null) { state.nextTurn(); updateAllScoreboards(); return; }
        lastIdleAccelMs = now;
        // GM 진행 비트: 대기·정체 구간이면 인게임 시간을 건너뛰어 다음 사건으로, 급박하면 위협만 진전.
        String prompt = "플레이어들이 약 " + (IDLE_ACCEL_MS / 60000) + "분간 아무 행동도 하지 않았다. "
            + "지금이 ★대기·이동·정체 같은 평온 구간★이면 인게임 시간을 상황에 맞게 건너뛰어(<TIME_SKIP minutes=\"N\"/>, "
            + "몇 시간~며칠: 1일=1440·1주=10080) 다음 의미 있는 사건·국면으로 시나리오를 넘겨라. "
            + "추격·전투 등 ★급박 구간★이면 시간을 건너뛰지 말고 위협만 한 걸음 진전시켜라(즉시 전멸 강요 금지). 2~3문장으로 짧게 서술.";
        ai.callGmAiOnce(gmSystemPrompt, prompt).thenAccept(raw ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                int skipMin = ai.parseTimeSkip(raw);
                // #12/#13: 무행동 가속이 ★제한 시각을 넘겨★ '타임오버 자동결말(도주판정)'로 직행하는 것을 막는다.
                //   갈피를 못 잡아 가만히 있었다는 이유로 마감을 건너뛰어 거저 클리어/파국으로 가면 안 된다.
                //   마감 직전 한 턴 분량의 여유를 남기고 캡 — 마감은 '실제 플레이'로만 닿게 한다.
                int untilEnd = state.getMinutesUntilEnd();
                if (skipMin > 0 && untilEnd >= 0) {
                    int cap = Math.max(0, untilEnd - Math.max(1, state.getMinutesPerTurn()));
                    if (skipMin > cap) skipMin = cap;
                }
                if (skipMin > 0) state.skipTime(skipMin);
                state.nextTurn();
                tickFaintCounters();
                updateAllScoreboards();
                String narrative = ai.stripTags(raw);
                if (!narrative.isBlank()) {
                    gameLogger.logGmOutput("(시간 경과)", narrative);
                    spawnedPlayers.forEach(uuid -> {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p == null || !p.isOnline()) return;
                        narrativeDelivery.deliver(p, narrative);
                        PlayerData pd = state.getPlayer(uuid);
                        if (pd != null) appendNarrativeLog(pd, narrative);
                    });
                }
            }));
    }

    // ──────────────────────────────────────────────────────────────
    //  로딩 바 (게임 초기화 진행률 표시)
    // ──────────────────────────────────────────────────────────────

    private void startLoadingBar(String label) {
        loadingBar = BossBar.bossBar(
            Component.text("§f[로딩] §7" + label),
            0.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(loadingBar));
    }

    private void stepLoadingBar(String label, float progress) {
        if (loadingBar == null) return;
        loadingBar.name(Component.text("§f[로딩] §7" + label));
        loadingBar.progress(Math.max(0.0f, Math.min(1.0f, progress)));
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(loadingBar));
    }

    private void endLoadingBar() {
        if (loadingBar == null) return;
        Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(loadingBar));
        loadingBar = null;
    }

    // ══════════════════════════════════════════════════════════════
    //  세션 시작 (/trpg start)
    // ══════════════════════════════════════════════════════════════

    public void startSession(Player initiator) {
        if (currentPhase != Phase.IDLE) {
            initiator.sendMessage("§c이미 TRPG 세션이 진행 중입니다.");
            return;
        }
        // 게임 모드 선택 → AI 품질 선택 순서로 진행
        initiator.sendMessage("§e세션을 시작합니다. 게임 모드를 선택하세요...");
        dialogMan.showModeChoice(initiator,
            () -> dialogMan.showQualityChoice(initiator,
                ai.providerLabel(), ai.hourlyCostLabel(AiManager.Quality.LOW),
                ai.hourlyCostLabel(AiManager.Quality.MEDIUM), ai.hourlyCostLabel(AiManager.Quality.HIGH),
                () -> beginSession(initiator, AiManager.Quality.LOW,    false, "random"),
                () -> beginSession(initiator, AiManager.Quality.MEDIUM, false, "random"),
                () -> beginSession(initiator, AiManager.Quality.HIGH,   false, "random")),
            // 친숙 모드 → 괴담 범위(필터) 선택 → 품질 선택 순서
            () -> dialogMan.showFamiliarFilter(initiator, filter ->
                dialogMan.showQualityChoice(initiator,
                    ai.providerLabel(), ai.hourlyCostLabel(AiManager.Quality.LOW),
                    ai.hourlyCostLabel(AiManager.Quality.MEDIUM), ai.hourlyCostLabel(AiManager.Quality.HIGH),
                    () -> beginSession(initiator, AiManager.Quality.LOW,    true, filter),
                    () -> beginSession(initiator, AiManager.Quality.MEDIUM, true, filter),
                    () -> beginSession(initiator, AiManager.Quality.HIGH,   true, filter))));
    }

    private static String familiarFilterLabel(String key) {
        return switch (key == null ? "" : key) {
            case "common" -> "흔한 괴담";
            case "heard"  -> "들어는 본 괴담";
            case "minor"  -> "마이너한 괴담";
            case "urban"  -> "도시전설만";
            case "scp"    -> "SCP만";
            case "korean" -> "한국 괴담만";
            case "japan"  -> "일본 괴담만";
            case "rule"   -> "규칙 괴담만";
            case "projectmoon" -> "환상체(프로젝트 문)";
            case "game"        -> "게임 괴담";
            default        -> "모두 무작위";
        };
    }

    private void beginSession(Player initiator, AiManager.Quality quality, boolean familiar, String familiarFilterKey) {
        if (currentPhase != Phase.IDLE) return; // 다이얼로그 대기 중 상태 변경 방지
        replayLock = false; // 정상 시작 — 재현 잠금 해제
        familiarMode = familiar;
        familiarFilter = (familiarFilterKey == null || familiarFilterKey.isBlank()) ? "random" : familiarFilterKey;
        ai.setGmQuality(quality);
        String qLabel = switch (quality) {
            case HIGH -> "§b고품질 모드";
            case LOW  -> "§7저품질 모드";
            default   -> "§e중품질 모드";
        };
        broadcast("§7[AI 품질] " + qLabel
            + "  §7[모드] " + (familiar ? "§d친숙한 친구들 (" + familiarFilterLabel(familiarFilter) + ")" : "§eAI 창작"));

        boolean freshSession = !state.isSessionActive();
        if (freshSession) retriedThisRun = false; // 새 게임 — 무리트라이 보너스 추적 초기화
        int room = state.isSessionActive() ? state.getRoomNumber() + 1 : Math.max(1, Math.min(6, startStage)); // 신규 세션은 설정된 시작 스테이지부터
        broadcast("§e§l═══ TRPG 세션 시작 (스테이지 " + room + ") ═══");
        broadcast("§7.gdam 파일을 생성 중입니다...");

        currentPhase = Phase.CHAR_CREATION;
        startLoadingBar(".gdam 생성 중...");
        // 비용 집계 시작점: 새 세션이면 세션·스테이지 모두, 아니면 스테이지만 0부터(생성 비용 포함)
        if (freshSession) ai.markSessionStart(); else ai.markStageStart();

        gdamGen.generate(room, familiarMode, familiarFilter, step -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            switch (step) {
                case "컨셉" -> stepLoadingBar("컨셉 생성 완료", 0.20f);
                case "구조" -> stepLoadingBar("구조 생성 완료", 0.45f);
                case "배역" -> stepLoadingBar("배역 생성 완료", 0.65f);
                case "아이템" -> stepLoadingBar("아이템 생성 완료", 0.80f);
                case "저장" -> stepLoadingBar("시나리오 저장 완료", 0.85f);
            }
        })).thenAccept(gdam -> {
            if (gdam.has("error")) {
                plugin.getServer().getScheduler().runTask(plugin, this::endLoadingBar);
                broadcast("§c[오류] 괴담 생성 실패: " + gdam.get("error").getAsString());
                currentPhase = Phase.IDLE;
                return;
            }

            String seed = gdam.get("seed").getAsString();
            state.startSession(room, seed, gdam);
            applyScenarioFlavor(); // 친숙(프로젝트 문·게임) 테마 특성 지침 주입 — 캐릭터 생성 전에
            gameLogger.startNewLog(seed, room, getEntityName());

            // 서바이벌 모드 플레이어 전원 캐릭터 생성
            List<Player> survivors = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                .collect(Collectors.toList());

            if (survivors.isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin, this::endLoadingBar);
                broadcast("§c서바이벌 모드 플레이어가 없습니다.");
                currentPhase = Phase.IDLE;
                return;
            }

            // 플레이어 수 > 배역 수면 '사건에 휘말리는 주변 인물' 배역을 추가해 관전 대신 참여를 보장(프롬프트·배정 전에 보강)
            ensureEnoughRoles(gdam, survivors.size());

            // GM AI에 .gdam 데이터 주입 (보강된 roles 포함)
            gmSystemPrompt = buildGmPrompt(gdam);
            ai.clearAll();

            broadcast("§a.gdam 생성 완료. 씨드: §e" + seed);
            broadcast("§7캐릭터를 생성합니다. 잠시 기다려주세요...");

            // 선제 배역 배정: 캐릭터 생성 시 배역 맥락(나이/직업 범위)을 활용
            doPreAssign(survivors, gdam);

            // 스테이지 시작 인벤토리 초기화 (이전 아이템 제거)
            survivors.forEach(p -> p.getInventory().clear());

            int total = survivors.size();
            java.util.concurrent.atomic.AtomicInteger charsDone = new java.util.concurrent.atomic.AtomicInteger(0);

            survivors.forEach(p -> {
                pendingCreation.add(p.getUniqueId());
                charGen.generate(p) // 시나리오 무관 완전 무작위 캐릭터 생성
                    .thenAccept(pd -> {
                        state.addPlayer(pd);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            int done = charsDone.incrementAndGet();
                            stepLoadingBar("캐릭터 생성 중... (" + done + "/" + total + ")",
                                0.85f + 0.15f * done / total);
                            if (done >= total) endLoadingBar();
                            applyStartStageBoost(pd); // 시작 스테이지 비례 시작 스펙 보정(설정 시)
                            if (!p.isOnline()) {
                                pendingCreation.remove(p.getUniqueId());
                                checkAllConfirmed();
                                return;
                            }
                            showCharacterSheetForPlayer(p, pd);
                        });
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("캐릭터 생성 실패 (" + p.getName() + "): " + ex.getMessage());
                        pendingCreation.remove(p.getUniqueId());
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            int done = charsDone.incrementAndGet();
                            if (done >= total) endLoadingBar();
                            checkAllConfirmed();
                        });
                        return null;
                    });
            });
        });
    }

    // ══════════════════════════════════════════════════════════════
    //  세션 종료 (/trpg stop)
    // ══════════════════════════════════════════════════════════════

    public void stopSession(Player admin) {
        if (concludingEnding) return; // 이미 전말 공개 중이면 무시
        boolean manual = admin != null;
        if (manual) {
            broadcast("§c[GM] " + admin.getName() + "이(가) 세션을 종료했습니다.");
            deleteAutoSave(); // 관리자가 의도적으로 끝냄 → 이어하기 대상 제거 (서버 재시작 시엔 보존)
        } else {
            broadcast("§c[GM] 서버 종료로 세션이 정리됩니다.");
        }

        // 게임을 진행했으나 아직 전말을 공개하지 않은 상태에서 수동 종료 = 포기.
        // 이 경우 이유 + 에필로그 + 해설을 공개한 뒤 세션을 정리한다.
        boolean played = currentPhase == Phase.DAILY
                      || currentPhase == Phase.HORROR
                      || currentPhase == Phase.GAMEOVER;
        if (manual && played && state.getGdamData() != null) {
            concludingEnding = true;
            currentPhase = Phase.GAMEOVER; // 종료 처리 중 추가 행동 차단
            broadcast("§7사건을 끝까지 풀지 못하고 종료합니다. 전말을 공개합니다.");
            // CODE-8/G21: 스톱 경로 = 시나리오 평가 → 괴담 공개(엔딩). 평가 후 reveal 연결.
            runScenarioEvaluation("중도 종료", grades ->
                concludeWithReveal("재도전 포기 / 중도 종료", true, () -> { // 게임 종료 → 전모 공개
                    concludingEnding = false;
                    endSession(true);
                }));
            return;
        }
        endSession(true);
    }

    private void endSession(boolean resetCorruption) {
        gameLogger.endLog("세션 종료");
        turnMan.cancelAll();
        Bukkit.getOnlinePlayers().forEach(p -> {
            scoreMan.clear(p);
            dialogMan.clearDialog(p);
            removeInfoItem(p);
            removeRecordItem(p);
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL); // 관전 해제(세션 종료 정리)
        });
        itemMan.reclaimChapterItems(new ArrayList<>(Bukkit.getOnlinePlayers()));
        narrativeDelivery.clearAll();
        mapMan.clear();
        state.endSession(resetCorruption);
        ai.saveUsage();   // 세션 종료 시점 영구 사용량 체크포인트 저장
        ai.clearAll();
        pendingCreation.clear();
        pendingTraitSelect.clear();
        pendingTraitActivation.clear();
        pendingPrayerInput.clear();
        pendingOracleInput.clear();
        pendingLuckModifier.clear();
        pendingOracleChoices.clear();
        pendingSaintTrait.clear();
        pendingAreaScanInput.clear();
        pendingLinkAllyInput.clear();
        pendingRemoteSenseInput.clear();
        pendingForesightInput.clear();
        pendingActionBoost.clear();
        pendingBoostTrait.clear();
        pendingStageEndChoices.clear();
        pendingStageEndNames.clear();
        spawnedPlayers.clear();
        commChannels.clear();
        lastDeadNotice.clear();
        lastInputMsg.clear();
        lastInputAt.clear();
        stuckTurns.clear();
        preAssignedRoleData.clear();
        preAssignments.clear();
        gmNpcRoleIds.clear();
        npcZones.clear();
        npcContactNumbers.clear();
        npcIntel.clear();
        npcAcquired.clear();
        npcLastDirectTurn.clear();
        forbiddenWord = "";
        rewindBuffer.clear();
        lastRewindCaptureTurn = -1;
        lastAutoSaveTurn = -1;
        phaseOutTurns.clear();
        morphTurns.clear();
        observerTurns.clear();
        commBypassTurn.clear();
        commBypassStealth.clear();
        animalForm.clear();
        stunTurns.clear();
        possessingNpc.clear();
        resetOverviewCache();
        preSpawnCallCounts.clear();
        lastEndingPages = null;
        concludingEnding = false;
        replayLock = false;
        nextStageUnlocked = true;
        forceRetryAllowed = false;
        clearPregen(); // 미소비 사전 생성 폐기(다음 세션과 스테이지 번호 충돌 방지)
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
        // 3번째 방부터는 생존 성공자가 있어야 재도전 가능
        if (!isRetryAllowed()) {
            admin.sendMessage("§c이 스테이지(" + state.getRoomNumber()
                + "번째)에서는 생존에 성공한 사람이 없어 재도전할 수 없습니다. §7(/trpg stop 으로 전말 확인)");
            return;
        }
        // 재도전은 시나리오 평가 없이 곧바로 같은 스테이지를 다시 시작한다(평가는 클리어·포기·게임 종료 때만).
        performRetry(admin);
    }

    /** 재도전 실제 진행부 (retrySession에서 평가 출력 완료 후 호출). */
    private void performRetry(Player admin) {
        if (!state.isSessionActive()) return;
        broadcast("§e[TRPG] 재도전합니다. 오염도 상승!");
        retriedThisRun = true; // 재도전 발생 — 이번 런은 보너스 6스테이지 자격 상실
        nextStageUnlocked = true; // 재도전은 새 시도 — 진출 잠금 초기화 (재클리어 시 판정에 따라 다시 결정)
        forceRetryAllowed = false; // 동반회귀 1회성 재도전 허용은 재도전이 실제로 일어나면 소멸
        gameLogger.section("재도전 " + (corruptMan.getAttempts() + 1) + "회차 (오염도 상승 예정)");

        // 이전 회차의 잔여 행동·서술·통신을 완전히 정리 (이전 플레이어 진행 방지)
        turnMan.cancelAll();
        narrativeDelivery.clearAll();

        // 다회차 기억 (① NPC 기억 스냅샷 — clearAll 직전에 저장)
        // 오염도가 높을수록 더 많은 과거 행동을 기억한다
        int snapMax = Math.min(2 + corruptMan.getLevel(), 5);
        Map<String, List<String>> npcSnapshot = ai.snapshotNpcMemories(snapMax);

        ai.clearAll();

        // 스탯/상태를 기본값으로 리셋 (HP/SAN 만회, isDead/puppet 해제)
        state.onRetry();
        regenerateForbiddenWord(); // 금지워드형: 재시도 시 금지어를 새 단어로(난이도 반영) — 외워서 피하기 방지
        // 다회차 연락처 보정: 이전 회차에 한 번이라도 알게 된 번호를 다시 알고 시작한다
        // (오염의 연락처 교란으로 잃었던 번호도 재도전 시 복구된다)
        state.getAllPlayers().forEach(pd -> pd.knownContacts.addAll(pd.everKnownContacts));
        broadcast("§c오염 단계: §f" + corruptMan.getLevel() + " (" + corruptMan.getAttempts() + "회차)");

        // 다회차 기억 재주입: 오염도에 따라 기억 선명도·양 조절
        if (corruptMan.getLevel() >= 1 && !npcSnapshot.isEmpty()) {
            int corrLevel = corruptMan.getLevel();
            npcSnapshot.forEach((npcId, msgs) -> {
                int take = Math.min(msgs.size(), corrLevel + 1);
                List<String> selected = msgs.subList(msgs.size() - take, msgs.size());
                String prefix = corrLevel == 1 ? "(흐릿하게) " : "";
                ai.preSeedNpcContext(npcId, prefix + String.join(" / ", selected));
            });
        }

        // 등장 상태·대기 서술·통신 채널 초기화
        pendingTraitActivation.clear();
        pendingPrayerInput.clear();
        pendingOracleInput.clear();
        pendingLuckModifier.clear();
        pendingOracleChoices.clear();
        pendingSaintTrait.clear();
        pendingAreaScanInput.clear();
        pendingLinkAllyInput.clear();
        pendingRemoteSenseInput.clear();
        pendingForesightInput.clear();
        pendingActionBoost.clear();
        pendingBoostTrait.clear();
        pendingStageEndChoices.clear();
        pendingStageEndNames.clear();
        spawnedPlayers.clear();
        preSpawnCallCounts.clear();
        commChannels.clear();
        state.getAllPlayers().forEach(pd -> traitMan.resetStageTraits(pd));

        gmSystemPrompt = buildGmPrompt(state.getGdamData());

        // 배역 스탯 재적용 + 등장 상태 재설정 (resetToBase로 제거된 배역 보정 복구)
        // 배역 자체(roleId/zone)와 특성은 resetToBase에서 유지되므로 재배정 불필요
        for (PlayerData pd : state.getAllPlayers()) {
            JsonObject roleData = getRoleDataById(pd.roleId);
            if (roleData != null) applyRoleStats(pd, roleData);
            if (isImmediateSpawn(pd.roleId)) spawnedPlayers.add(pd.uuid);
            Player rp = Bukkit.getPlayer(pd.uuid);
            if (rp != null && rp.isOnline()) scoreMan.update(rp, pd, state.getRoomNumber());
        }

        currentPhase = Phase.DAILY;
        startDailyPhase();
    }

    // ══════════════════════════════════════════════════════════════
    //  다음 스테이지 (/trpg next)
    // ══════════════════════════════════════════════════════════════

    public void nextSession(Player admin) {
        if (!state.isSessionActive()) {
            admin.sendMessage("§c활성 세션이 없습니다.");
            return;
        }
        if (replayLock) {
            admin.sendMessage("§c재현(replay) 세션입니다. 이 스테이지만 진행되며 다음 스테이지로 넘어갈 수 없습니다.");
            admin.sendMessage("§7/trpg stop §c으로 종료하세요.");
            return;
        }
        // 스테이지 3+는 괴담 완전 해결(해결판정) 후에만 다음 스테이지로 진출 가능. 단순 생존은 재도전만.
        if (!nextStageUnlocked) {
            admin.sendMessage("§c단순 생존 클리어로는 다음 스테이지로 넘어갈 수 없습니다 (스테이지 "
                + state.getRoomNumber() + "+는 괴담 완전 해결 필요).");
            admin.sendMessage("§7/trpg retry §c로 같은 스테이지를 재도전하거나 §7/trpg stop §c으로 종료하세요.");
            return;
        }

        // 최종 스테이지 클리어 → 게임 종료 + 총합 평가
        //  · 6스테이지(보너스)를 클리어했거나, 5스테이지를 ★리트라이한 채★ 클리어했으면(보너스 자격 없음) 여기서 끝.
        int current = state.getRoomNumber();
        if (current >= 6 || (current >= FINAL_STAGE && retriedThisRun)) {
            concludeWholeGame(admin);
            return;
        }
        // 5스테이지를 ★리트라이 없이★ 클리어 → 숨겨진 6스테이지(복합 괴담) 해금 안내
        if (current == FINAL_STAGE && !retriedThisRun)
            broadcast("§6§l★ 무결 완주! 숨겨진 6스테이지(복합 괴담)가 해금됩니다 ★");

        int nextRoom = state.getRoomNumber() + 1;
        nextStageUnlocked = true; // 새 스테이지는 아직 미클리어 — 다음 진출은 이 스테이지 클리어 후 재판정
        broadcast("§e§l═══ 다음 스테이지로 이동합니다 (스테이지 " + nextRoom + ") ═══");
        broadcast("§7새 시나리오를 생성 중입니다...");

        currentPhase = Phase.ROLE_ASSIGNMENT;

        // 역할 데이터 초기화: roleSpecific 특성·역할·zone 제거, 기본 스탯으로 복구
        state.getAllPlayers().forEach(pd -> {
            pd.clearRoleData();
            pd.statsConfirmed = true;
        });
        // 스테이지 전환 인벤토리 초기화 (이전 스테이지 아이템 전부 제거)
        Bukkit.getOnlinePlayers().forEach(p -> p.getInventory().clear());
        itemMan.reclaimChapterItems(new ArrayList<>(Bukkit.getOnlinePlayers())); // chapterBound 추적 정리

        turnMan.cancelAll();
        narrativeDelivery.clearAll();
        pendingCreation.clear();
        pendingTraitSelect.clear();
        pendingTraitActivation.clear();
        pendingPrayerInput.clear();
        pendingOracleInput.clear();
        pendingLuckModifier.clear();
        pendingOracleChoices.clear();
        pendingSaintTrait.clear();
        pendingAreaScanInput.clear();
        pendingLinkAllyInput.clear();
        pendingRemoteSenseInput.clear();
        pendingForesightInput.clear();
        pendingActionBoost.clear();
        pendingBoostTrait.clear();
        pendingStageEndChoices.clear();
        pendingStageEndNames.clear();
        spawnedPlayers.clear();
        commChannels.clear();
        lastEndingPages = null;
        forceRetryAllowed = false; // 새 스테이지 진입 — 동반회귀 재도전 허용 초기화
        state.getAllPlayers().forEach(pd -> traitMan.resetStageTraits(pd));
        preAssignedRoleData.clear();
        preAssignments.clear();
        gmNpcRoleIds.clear();
        preSpawnCallCounts.clear();
        ai.clearAll();
        startLoadingBar(".gdam 생성 중...");
        ai.markStageStart(); // 비용 집계: 다음 스테이지 시작점(세션 누적은 유지)

        // 라운드 종료 시 사전 생성(startPregenNext)해 둔 시나리오가 있으면 재사용 → 대기 시간 단축.
        consumePregenOrGenerate(nextRoom).thenAccept(gdam -> {
            if (gdam.has("error")) {
                plugin.getServer().getScheduler().runTask(plugin, this::endLoadingBar);
                broadcast("§c[오류] 시나리오 생성 실패: " + gdam.get("error").getAsString());
                currentPhase = Phase.IDLE;
                return;
            }

            String seed = gdam.get("seed").getAsString();
            state.advanceToNextRoom(nextRoom, seed, gdam);
            // 새 맵 = 새 시작. 이전 맵의 재도전 오염도·entity 메모리 초기화.
            state.getCorruption().resetForNewStage();
            gameLogger.startNewLog(seed, nextRoom, getEntityName());
            ensureEnoughRoles(gdam, activeSurvivorCount()); // 플레이어 수 > 배역 수면 휘말림 배역 보강(프롬프트 전에)
            gmSystemPrompt = buildGmPrompt(gdam);

            broadcast("§a새 시나리오 생성 완료. 씨드: §e" + seed);

            List<Player> participants = state.getAllPlayers().stream()
                .map(pd -> Bukkit.getPlayer(pd.uuid))
                .filter(Objects::nonNull)
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                .collect(Collectors.toList());

            if (participants.isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin, this::endLoadingBar);
                broadcast("§c참여 중인 플레이어가 없습니다.");
                currentPhase = Phase.IDLE;
                return;
            }

            doPreAssign(participants, gdam);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // 스코어보드 갱신은 메인 스레드에서 수행
                state.getAllPlayers().forEach(pd -> {
                    Player p = Bukkit.getPlayer(pd.uuid);
                    if (p != null) scoreMan.update(p, pd, nextRoom);
                });
                endLoadingBar();
                assignRolesAndStart();
            });
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  다음 시나리오 사전 생성 (라운드 종료 → 백그라운드 생성 → 대기 시간 단축)
    // ──────────────────────────────────────────────────────────────

    /**
     * 라운드 종료 후 다음 스테이지로 진출 가능하면, 즉시 백그라운드로 다음 시나리오를 생성해 둔다.
     * 플레이어가 특성 선택·정비하는 동안 생성이 진행되어 /trpg next 대기 시간이 크게 줄어든다.
     * 로딩바·브로드캐스트 없이 조용히 진행하며 /trpg next에서 consumePregenOrGenerate로 소비된다.
     */
    // ──────────────────────────────────────────────────────────────
    //  시작 설정 (/trpg setting) — 자동 사전생성 토글 + 시작 스테이지 보정
    // ──────────────────────────────────────────────────────────────

    /** /trpg setting [pregen on|off | stage N] — 인자 없으면 현재 설정 표시. */
    public void handleStartSetting(Player player, String[] sub) {
        if (sub == null || sub.length == 0) { openStartSettings(player); return; }
        String key = sub[0].toLowerCase();
        if (key.equals("pregen") || key.equals("자동생성") || key.equals("자동")) {
            if (sub.length >= 2) {
                String v = sub[1].toLowerCase();
                autoPregen = v.equals("on") || v.equals("켜기") || v.equals("켬") || v.equals("true") || v.equals("1");
            } else autoPregen = !autoPregen; // 값 없으면 토글
            player.sendMessage("§6[설정] 다음 시나리오 자동 사전생성: " + (autoPregen ? "§a켜짐" : "§c꺼짐 §7(/trpg next에서 즉석 생성)"));
        } else if (key.equals("stage") || key.equals("스테이지")) {
            if (sub.length < 2) { player.sendMessage("§c사용법: §f/trpg setting stage <1-6>"); return; }
            try {
                startStage = Math.max(1, Math.min(6, Integer.parseInt(sub[1].trim())));
                player.sendMessage("§6[설정] 시작 스테이지: §b" + startStage
                    + (startStage > 1 ? " §7(시작 보정 " + (startStage - 1) + "단계: 단계당 올스탯+2 & 특성 추가/등급↑)" : " §7(보정 없음)"));
                player.sendMessage("§7다음 §f/trpg start §7부터 적용됩니다.");
            } catch (NumberFormatException e) { player.sendMessage("§c숫자를 입력하세요: §f/trpg setting stage <1-6>"); }
        } else {
            openStartSettings(player);
        }
    }

    /** 현재 시작 설정 표시(/trpg setting). */
    public void openStartSettings(Player player) {
        player.sendMessage("§e§l═══ TRPG 시작 설정 ═══");
        player.sendMessage("§f· 다음 시나리오 자동생성: " + (autoPregen ? "§a켜짐" : "§c꺼짐")
            + "  §7→ §f/trpg setting pregen on|off");
        player.sendMessage("§f· 시작 스테이지: §b" + startStage
            + (startStage > 1 ? " §7(보정 " + (startStage - 1) + "단계)" : "")
            + "  §7→ §f/trpg setting stage <1-6>");
        player.sendMessage("§7시작 스테이지 보정: 단계당 올스탯 총합 +2, 그리고 무작위로");
        player.sendMessage("§7  [특성 1개 추가 — 시작 스테이지↑일수록 높은 등급] 또는 [보유 특성 등급 1단계↑] 중 하나.");
        player.sendMessage("§7설정은 다음 §f/trpg start§7 부터 적용됩니다.");
    }

    private static final String[] GRADE_ORDER = {"F", "E", "D", "C", "B", "A", "S"}; // gradeIdx와 동일 순서
    /** 특성 등급을 한 단계 올린다(A 상한 — S는 시작 보정으로 자동 부여하지 않음). */
    private String bumpGrade(String g) { return GRADE_ORDER[Math.min(gradeIdx("A"), gradeIdx(g) + 1)]; }

    /**
     * 시작 스테이지 비례 시작 스펙 보정(신규 캐릭터 생성 시). startStage>1이고 현재 스테이지==startStage일 때만.
     * 단계(=startStage-1)마다: 올스탯 총합 +2, 그리고 무작위로
     *   [특성 1개 추가 — 시작 스테이지가 높을수록 ★더 높은 등급★] 또는 [보유 특성 등급 1단계 상승].
     */
    private void applyStartStageBoost(PlayerData pd) {
        if (pd == null || startStage <= 1) return;
        if (state.getRoomNumber() != startStage) return; // 시작 스테이지에서만(도중 합류·다음 스테이지엔 미적용)
        int levels = startStage - 1;
        int ceil = startStage <= 3 ? gradeIdx("B") : gradeIdx("A"); // '적당히 높은' 상한 — 낮은 시작=최대 B, 높은 시작=최대 A
        java.util.Random rng = new java.util.Random();
        java.util.List<String> notes = new java.util.ArrayList<>();
        for (int i = 0; i < levels; i++) {
            bumpStat(pd, rng.nextInt(4), 1);
            bumpStat(pd, rng.nextInt(4), 1);                 // 단계당 올스탯 총합 +2
            boolean addNew = rng.nextBoolean();
            if (addNew) {                                     // (a) 특성 추가 — 등급이 시작 스테이지에 비례
                TraitData t = rollStartTrait(pd, rng, ceil);
                if (t != null) { pd.traits.add(t); notes.add("＋" + t.name + "(" + t.grade + ")"); }
                else if (!upgradeOneTrait(pd, notes)) bumpStat(pd, rng.nextInt(4), 2); // 풀 소진 → 등급↑ → 그래도 안 되면 스탯
            } else {                                           // (b) 보유 특성 등급 1단계 상승
                if (!upgradeOneTrait(pd, notes)) {             // 올릴 특성 없으면 추가로 대체
                    TraitData t = rollStartTrait(pd, rng, ceil);
                    if (t != null) { pd.traits.add(t); notes.add("＋" + t.name + "(" + t.grade + ")"); }
                    else bumpStat(pd, rng.nextInt(4), 2);
                }
            }
        }
        pd.snapshotBase(); // 보정 스탯을 base로 재확정(재도전·다음 스테이지에도 유지)
        Player p = Bukkit.getPlayer(pd.uuid);
        if (p != null) {
            p.sendMessage("§6[시작 보정] 시작 스테이지 " + startStage + " — " + levels + "단계 성장 적용");
            if (!notes.isEmpty()) p.sendMessage("§6 특성: §f" + String.join(", ", notes));
        }
    }
    private void bumpStat(PlayerData pd, int idx, int amt) {
        // 스탯 상한 20(문서 범위 1~20) 클램프 — 시작 보정 누적이 범위를 넘지 않게.
        switch (idx) {
            case 0 -> pd.str = Math.min(20, pd.str + amt);
            case 1 -> pd.cha = Math.min(20, pd.cha + amt);
            case 2 -> pd.luk = Math.min(20, pd.luk + amt);
            default -> pd.spr = Math.min(20, pd.spr + amt);
        }
    }
    /** 보유 특성 중 가장 낮은 등급 하나를 한 단계 올린다(A 상한). 올릴 게 없으면 false. */
    private boolean upgradeOneTrait(PlayerData pd, java.util.List<String> notes) {
        TraitData low = pd.traits.stream()
            .filter(t -> gradeIdx(t.grade) < gradeIdx("A"))
            .min(java.util.Comparator.comparingInt(t -> gradeIdx(t.grade))).orElse(null);
        if (low == null) return false;
        String before = low.grade;
        low.grade = bumpGrade(low.grade);
        if (low.originGrade == null || low.originGrade.isBlank()) low.originGrade = before;
        notes.add("↑" + low.name + "(" + before + "→" + low.grade + ")");
        return true;
    }
    /** 미보유 프리셋 특성 1개를 무작위로(등급 C~ceil, S·즉시클리어 제외 → '적당히 높은 등급'). 없으면 null. */
    private TraitData rollStartTrait(PlayerData pd, java.util.Random rng, int ceil) {
        java.util.List<SystemTraitRegistry.Preset> pool = new java.util.ArrayList<>();
        for (SystemTraitRegistry.Preset ps : SystemTraitRegistry.presets()) {
            int gi = gradeIdx(ps.grade());
            if (gi < gradeIdx("C") || gi > ceil) continue;                 // C~ceil 등급대만
            if ("instant_clear".equals(ps.effectType())) continue;         // 즉시 클리어(도약자)는 시작 보정에서 제외
            if (pd.traits.stream().anyMatch(t -> ps.id().equals(t.id))) continue; // 이미 보유 제외
            pool.add(ps);
        }
        if (pool.isEmpty()) return null;
        // '적당히 높은' — 가능한 최고 등급대에서 우선 선택
        int top = pool.stream().mapToInt(ps -> gradeIdx(ps.grade())).max().orElse(0);
        java.util.List<SystemTraitRegistry.Preset> best = new java.util.ArrayList<>();
        for (SystemTraitRegistry.Preset ps : pool) if (gradeIdx(ps.grade()) == top) best.add(ps);
        TraitData td = best.get(rng.nextInt(best.size())).toTraitData();
        td.origin = "시작 보정";
        return td;
    }

    private void startPregenNext() {
        if (replayLock) return;            // 재현 세션은 다음 스테이지가 없음
        if (!autoPregen) return;           // 설정: 자동 사전생성 꺼짐 → /trpg next에서 즉석 생성
        if (!nextStageUnlocked) return;    // 진출 불가(단순 생존 등) — 미리 만들 필요 없음
        int current = state.getRoomNumber();
        // 최종 스테이지면 다음이 없다 — nextSession의 종료 조건과 동일하게 사전 생성 차단.
        // (6=무리트라이 보너스 끝, 또는 리트라이한 채 FINAL_STAGE 도달 → concludeWholeGame)
        // 이 가드가 없으면 6스테이지 클리어 후 쓰지도 않을 7스테이지를 백그라운드 생성해 비용을 낭비한다.
        if (current >= 6 || (current >= FINAL_STAGE && retriedThisRun)) return;
        int target = current + 1;
        if (pregenFuture != null && pregenRoom == target) return; // 이미 진행/완료된 것이 있음
        pregenRoom   = target;
        pregenFuture = gdamGen.generate(target, familiarMode, familiarFilter, step -> {}, castHintFor(target)) // 진행 콜백 없음(조용히), 피날레면 복귀 캐스트 시드
            .exceptionally(ex -> {
                plugin.getLogger().warning("[gdam] 다음 스테이지 사전 생성 실패 — /trpg next에서 즉석 생성으로 폴백: "
                    + (ex == null ? "?" : ex.getMessage()));
                return null; // null → 소비 시 폴백 재생성
            });
        gameLogger.logEvent("다음 스테이지(" + target + ") 사전 생성 시작");
        plugin.getLogger().info("[gdam] 다음 스테이지 사전 생성 시작 (스테이지 " + target + ")");
    }

    /**
     * /trpg next 시 다음 시나리오 future를 얻는다.
     * 사전 생성(startPregenNext)된 것이 있고 대상 스테이지가 일치하면 재사용하고,
     * 사전 생성 결과가 오류/누락이면 즉석 생성으로 자동 폴백한다.
     */
    private CompletableFuture<JsonObject> consumePregenOrGenerate(int nextRoom) {
        CompletableFuture<JsonObject> pre = pregenFuture;
        int preRoom = pregenRoom;
        clearPregen(); // 1회성 소비

        if (pre != null && preRoom == nextRoom) {
            if (pre.isDone()) stepLoadingBar("사전 생성된 시나리오 사용", 0.92f);
            else              stepLoadingBar("사전 생성된 시나리오 준비 중...", 0.50f);
            // 사전 생성이 성공(error 없음)이면 그대로, 아니면 즉석 재생성으로 폴백.
            return pre.thenCompose(gdam -> (gdam != null && !gdam.has("error"))
                ? CompletableFuture.completedFuture(gdam)
                : freshGenerate(nextRoom));
        }
        return freshGenerate(nextRoom);
    }

    /** 로딩바 진행 콜백을 단 즉석 시나리오 생성(친숙 모드 필터 유지). */
    private CompletableFuture<JsonObject> freshGenerate(int nextRoom) {
        return gdamGen.generate(nextRoom, familiarMode, familiarFilter, step ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                switch (step) {
                    case "컨셉"   -> stepLoadingBar("컨셉 생성 완료", 0.20f);
                    case "구조"   -> stepLoadingBar("구조 생성 완료", 0.45f);
                    case "배역"   -> stepLoadingBar("배역 생성 완료", 0.65f);
                    case "아이템" -> stepLoadingBar("아이템 생성 완료", 0.80f);
                    case "저장"   -> stepLoadingBar("시나리오 저장 완료", 0.95f);
                }
            }), castHintFor(nextRoom)); // 피날레면 복귀 캐스트를 시드로 주입
    }

    /** 사전 생성 상태 초기화(소비·세션 종료 시). */
    private void clearPregen() {
        pregenFuture = null;
        pregenRoom   = -1;
    }

    // ══════════════════════════════════════════════════════════════
    //  채팅 라우팅 (ChatListener → 여기)
    // ══════════════════════════════════════════════════════════════

    public void handleChat(Player player, String message) {
        switch (currentPhase) {
            case CHAR_CREATION -> handleCharCreationChat(player, message);
            case DAILY, HORROR -> handleGameChat(player, message);
            // 대기 단계(시나리오 생성·클리어 후·게임오버 등)에서는 채팅 이벤트가 취소된 채 버려져
            // 아무 말도 못 하던 문제 → 일반 채팅으로 중계해 자유롭게 대화할 수 있게 한다.
            // 등장인물 이름 우선(배역 있으면 charName), 없으면 계정 이름.
            default -> {
                PlayerData wpd = state.getPlayer(player);
                String who = (wpd != null && !wpd.charName.isEmpty()) ? wpd.charName : player.getName();
                broadcast("§7<§f" + who + "§7> " + message);
            }
        }
    }

    /** 내부 커맨드 처리 (/trpg _confirm, _reroll, _trait N 등) */
    public void handleInternalCommand(Player player, String[] args) {
        if (args.length == 0) return;
        switch (args[0].toLowerCase()) {
            case "_confirm"    -> confirmStats(player);
            case "_reroll"     -> rerollStats(player);
            case "_trait"      -> {
                try { handleTraitSelect(player, args.length > 1 ? Integer.parseInt(args[1]) : 0); }
                catch (NumberFormatException e) { player.sendMessage("§c번호를 입력해주세요."); }
            }
            case "_trait_remove" -> {
                try { handleTraitRemove(player, args.length > 1 ? Integer.parseInt(args[1]) : 0); }
                catch (NumberFormatException e) { player.sendMessage("§c번호를 입력해주세요."); }
            }
            case "_use_trait"  -> handleTraitUse(player, args.length > 1 ? args[1] : "");
            case "_trait_commit" -> commitTrait(player);
            case "_trait_cancel" -> {
                pendingTraitActivation.remove(player.getUniqueId());
                player.sendMessage("§7특성 발동을 취소했습니다.");
            }
            case "_oracle_select" -> {
                try { handleOracleSelect(player, args.length > 1 ? Integer.parseInt(args[1]) : -1); }
                catch (NumberFormatException e) { player.sendMessage("§c잘못된 선택입니다."); }
            }
            case "_saint_cancel" -> {
                pendingSaintTrait.remove(player.getUniqueId());
                player.sendMessage("§7[성녀] 취소했습니다.");
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  캐릭터 생성 단계
    // ──────────────────────────────────────────────────────────────

    private void handleCharCreationChat(Player player, String message) {
        // Paper Dialog로 처리되므로 채팅은 숫자 입력 폴백만 유지
        if (dialogMan.hasActiveDialog(player)) {
            DialogManager.DialogState dtype = dialogMan.getDialogState(player);
            if (dtype == DialogManager.DialogState.TRAIT_SELECTION) {
                try { handleTraitSelect(player, Integer.parseInt(message.trim())); } catch (NumberFormatException ignored) {}
            } else if (dtype == DialogManager.DialogState.TRAIT_REMOVE) {
                try { handleTraitRemove(player, Integer.parseInt(message.trim())); } catch (NumberFormatException ignored) {}
            }
            return;
        }
        String lower = message.trim().toLowerCase();
        if (lower.equals("확정"))   { confirmStats(player); return; }
        if (lower.equals("재굴림")) { rerollStats(player);  return; }
        // 생성 다이얼로그가 없는 동안(괴담 생성 대기 등)에는 채팅이 삼켜지지 않도록 일반 대화로 중계.
        broadcast("§7<§f" + player.getName() + "§7> " + message);
    }

    private void confirmStats(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.statsConfirmed) return;

        dialogMan.clearDialog(player);
        pd.statsConfirmed = true;
        player.sendMessage("§a스탯이 확정되었습니다!");
        scoreMan.update(player, pd, state.getRoomNumber());
        pendingCreation.remove(player.getUniqueId());
        charGen.clearPlayerUsedJobs(player.getUniqueId()); // 재굴림 직업 기록 초기화
        checkAllConfirmed();
    }

    private void rerollStats(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.diceRollsRemaining <= 0) {
            player.sendMessage("§c재굴림 횟수를 모두 소진했습니다.");
            return;
        }

        dialogMan.clearDialog(player);
        pd.diceRollsRemaining--;
        player.sendMessage("§7재굴림 중...");

        // 캐릭터 본체는 시나리오와 무관하게 완전 무작위로 재굴림.
        // (시나리오 배역은 이후 배역 배정 단계에서 별도로 덮어쓴다)
        charGen.generate(player).thenAccept(newPd -> {
            newPd.diceRollsRemaining = pd.diceRollsRemaining;
            state.addPlayer(newPd);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                applyStartStageBoost(newPd); // 재굴림도 시작 보정 재적용
                showCharacterSheetForPlayer(player, newPd);
            });
        });
    }

    private void showCharacterSheetForPlayer(Player player, PlayerData pd) {
        int room    = state.getRoomNumber();
        int attempt = state.getCorruption().attempts + 1;
        dialogMan.showCharacterSheet(player, pd, room, attempt, charGen.describeJob(pd.job),
            () -> confirmStats(player),
            () -> rerollStats(player));
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

        // 선제 배정 결과 재사용. 없으면 새로 배정 (retrySession 등 경우)
        Map<UUID, RoleManager.RoleAssignment> assignments;
        if (!preAssignments.isEmpty()) {
            assignments = preAssignments;
            // PlayerData에 배역 필드 적용 (선제 배정 시 pd가 없어 못했던 부분)
            for (var entry : assignments.entrySet()) {
                PlayerData pd = state.getPlayer(entry.getKey());
                if (pd != null) {
                    RoleManager.RoleAssignment asgn = entry.getValue();
                    pd.roleId   = asgn.roleId();
                    pd.zone     = asgn.zone();
                    pd.charName = asgn.charName();
                    pd.gender   = asgn.gender();
                    pd.roleAssigned = true;
                }
            }
        } else {
            assignments = roleMan.assignRoles(players);
        }

        // ★로그 뷰어용 별칭★: 계정명↔캐릭터명 매핑을 기록해 뷰어가 입력·서술 시점을 한 인물로 통합하게 한다.
        // 로그 뷰어 별칭: 계정명 노출 방지. 캐릭터명이 없으면 직업 등 표시명으로라도 계정을 가린다
        //   (gmDisplayName = 캐릭터명 → 직업 → "이름 모를 인물"). 서로 다른 플레이어가 합쳐지는 걸 막고자
        //   식별 불가 폴백('이름 모를 인물')은 별칭에서 제외(그 경우만 계정 그대로).
        for (PlayerData pd : state.getAllPlayers()) {
            String disp = pd.gmDisplayName();
            if (!"이름 모를 인물".equals(disp)) gameLogger.logAlias(pd.name, disp);
        }

        // GM 프롬프트 재생성 (NPC 배역 포함)
        gmSystemPrompt = buildGmPrompt(state.getGdamData());

        // common_items: 시대 배경에 따라 모든 플레이어가 기본 소지 (현대=스마트폰 등)
        JsonObject gdamForItems = state.getGdamData();
        if (gdamForItems != null && gdamForItems.has("common_items")) {
            gdamForItems.getAsJsonArray("common_items").forEach(el -> {
                String itemId = el.getAsString().trim();
                if (!itemId.isEmpty()) state.getAllPlayers().forEach(pd -> noteHeldItem(pd, itemId));
            });
        }

        // 연락처: 무작위 번호 부여 + 특성 기반 사전 지식 적용
        assignContactIds();
        assignNpcContactIds(); // 중요 NPC에도 번호 부여(관계로 시작부터 알 수 있게)
        applyTraitContacts();
        applyRelationshipContacts(assignments);

        List<CompletableFuture<Map.Entry<PlayerData, List<TraitData>>>> roleTraitFutures = new ArrayList<>();
        boolean finale = state.getRoomNumber() == FINAL_ROOM; // 피날레: 원년 배역 복귀 스테이지

        for (var entry : assignments.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;
            RoleManager.RoleAssignment asgn = entry.getValue();

            PlayerData myPd = state.getPlayer(p);
            JsonObject roleData = (myPd != null) ? getRoleDataById(asgn.roleId()) : null;

            // 피날레: 원년 정체성(이름·직업·나이·성별)으로 복귀. 성장(스탯·특성)은 이미 보유 중이며
            //   새 배역 스탯·배역 특성은 부여하지 않는다(원래 캐릭터 그대로 최종 결전에 참여).
            if (finale && myPd != null && myPd.hasOrigChar) {
                restoreOrigChar(myPd);
            } else if (myPd != null && roleData != null) {
                // 배역 스탯 적용 — snapshotBase() 이후 호출이므로 clearRoleData()→resetToBase() 시 자동 제거됨
                // (적용만 하고 채팅 출력은 하지 않음. 캐릭터 정보 GUI/스코어보드에서 기본/배역 분리 표시)
                applyRoleStats(myPd, roleData);
            }
            // 원년 배역 스냅샷: 이 판이 시작된 스테이지(=startStage, 기본 1)의 배역을 '원년'으로 1회 기록 → 피날레 복귀·중간 시작 대응.
            if (!myPd.hasOrigChar && state.getRoomNumber() == startStage) captureOrigChar(myPd);

            p.sendMessage("§e§l[배역 배정]");
            p.sendMessage(roleMan.getRoleBriefing(asgn.roleId(), corruptMan.getLevel()));
            giveRoleStartItems(p, asgn.roleId());
            // 정보 계열 패시브(시나리오 이해·적대자 감지·구원자 탐지·전지적 독자시점) — 배역 배정 시 직감 브리핑 전달
            if (myPd != null) {
                final Player fp2 = p;
                myPd.traits.stream().filter(TRPGGameManager::isPassiveInfoTrait)
                    .forEach(t -> deliverInsightInfo(fp2, t));
                // 치명 실수 무효화(fatal_guard) 보호 — GM에 고지(1회 한정, 소진형)
                if (myPd.traits.stream().anyMatch(t -> "fatal_guard".equals(t.effectType)))
                    ai.injectGmSystem("[보호: 치명 실수 무효화] " + myPd.gmDisplayName()
                        + "은(는) '돌이킬 수 없는 1회성 치명 행동(즉사 규칙 위반 등)'을 저질러도 ★1회에 한해★ 그 결과를 "
                        + "아슬아슬하게 무효화한다('간발의 차로 무위로 돌아갔다'). 이 보호는 그 순간 소진되며, 이후엔 정상 판정한다.");
            }
            if (myPd != null) {
                gameLogger.logPrivate(myPd.name, "배역 배정 → " + myPd.gmDisplayName()
                    + " (" + myPd.age + "세 " + myPd.job + ", " + state.zoneNameOf(asgn.zone()) + ")");
            }

            if (myPd != null && !myPd.contactId.isEmpty()) {
                p.sendMessage("§7당신의 연락처: §f" + myPd.contactId
                    + " §8(상대 번호를 알면 §f@번호 메시지§8로 바로 연락할 수 있습니다)");
                announceKnownContacts(p, myPd);
            }

            if (isImmediateSpawn(asgn.roleId())) {
                spawnedPlayers.add(p.getUniqueId());
            } else {
                p.sendMessage("§8당신의 배역은 이야기가 진행되면서 등장합니다. GM의 안내를 기다려주세요.");
            }

            if (!finale && myPd != null && roleData != null) { // 피날레는 배역 특성 미생성(원년 특성 유지)
                p.sendMessage("§7배역 고유 특성 생성 중...");
                roleTraitFutures.add(
                    traitMan.generateRoleTraits(myPd, roleData)
                        .thenApply(traits -> Map.entry(myPd, traits))
                );
            }
        }

        currentPhase = Phase.DAILY;
        // 캐릭터 생성 단계의 잔여 다이얼로그 상태(주사위확인·특성선택 등)가 남아 있으면
        // handleGameChat이 모든 채팅을 다이얼로그 입력으로 삼켜 '아무 입력도 안 되는' 문제가 생긴다 → 전원 정리.
        Bukkit.getOnlinePlayers().forEach(dialogMan::clearDialog);

        if (roleTraitFutures.isEmpty()) {
            startDailyPhase();
            return;
        }

        CompletableFuture.allOf(roleTraitFutures.toArray(new CompletableFuture[0]))
            .thenRun(() -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (var future : roleTraitFutures) {
                    Map.Entry<PlayerData, List<TraitData>> result;
                    try { result = future.join(); }
                    catch (Exception ignored) { continue; }
                    PlayerData pd = result.getKey();
                    List<TraitData> traits = result.getValue();
                    if (traits.isEmpty()) continue;
                    Player rp = Bukkit.getPlayer(pd.uuid);
                    traits.forEach(t -> traitMan.addTrait(pd, t));
                    if (rp != null && rp.isOnline()) {
                        // 마우스 오버레이로 설명을 볼 수 있는 컴포넌트 메시지
                        var msg = Component.text()
                            .append(Component.text("[배역 특성] 다음 특성이 부여되었습니다:", NamedTextColor.YELLOW));
                        for (TraitData t : traits) {
                            msg.append(Component.newline())
                                .append(Component.text("▸ (" + t.grade + ") ", NamedTextColor.GRAY))
                                .append(Component.text(t.name, NamedTextColor.WHITE)
                                    .hoverEvent(DialogManager.buildTraitHover(t)));
                        }
                        msg.append(Component.newline())
                            .append(Component.text("  (특성에 마우스를 올리면 설명이 표시됩니다)", NamedTextColor.DARK_GRAY));
                        rp.sendMessage(msg.build());
                        scoreMan.update(rp, pd, state.getRoomNumber());
                    }
                }
                startDailyPhase();
            }));
    }

    private void giveRoleStartItems(Player player, String roleId) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles")) return;
        for (var el : gdam.getAsJsonArray("roles")) {
            JsonObject r = el.getAsJsonObject();
            if (!r.get("role_id").getAsString().equals(roleId)) continue;

            // 초기 zone 설정
            PlayerData pd = state.getPlayer(player);
            if (pd != null && r.has("zone")) {
                String initZone = r.get("zone").getAsString();
                pd.zone = initZone;
                if (!initZone.isBlank()) {
                    pd.visitedZones.add(initZone);
                    pd.visitedZones.addAll(mapMan.getAdjacentZones(initZone));
                }
            }

            if (r.has("start_item")) {
                for (var item : r.getAsJsonArray("start_item")) {
                    JsonObject grant = new JsonObject();
                    String itemId = item.getAsString();
                    grant.addProperty("item_id", itemId);
                    grant.addProperty("player", player.getName());
                    grant.addProperty("chapter_bound", true);
                    itemMan.processGrant(grant, List.of(player));
                    if (pd != null) noteHeldItem(pd, itemId);
                }
            }
        }
    }

    private JsonObject getRoleDataById(String roleId) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles")) return null;
        for (var el : gdam.getAsJsonArray("roles")) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("role_id") && r.get("role_id").getAsString().equals(roleId)) return r;
        }
        return null;
    }

    /**
     * gdam role_stats를 pd에 적용한다.
     * snapshotBase() 이후에 호출되므로 clearRoleData() → resetToBase() 시 자동 제거된다.
     * @return 플레이어에게 표시할 요약 문자열 (없으면 빈 문자열)
     */
    private String applyRoleStats(PlayerData pd, JsonObject roleData) {
        // 나이·직업은 role_stats 유무와 무관하게 배역 age_range·job_pool에 맞춰 조정
        applyRoleAge(pd, roleData);
        applyRoleJob(pd, roleData);
        if (!roleData.has("role_stats")) return "";
        JsonObject rs = roleData.getAsJsonObject("role_stats");

        int strAdd = rs.has("str_add")     ? rs.get("str_add").getAsInt()     : 0;
        int chaAdd = rs.has("cha_add")     ? rs.get("cha_add").getAsInt()     : 0;
        int lukAdd = rs.has("luk_add")     ? rs.get("luk_add").getAsInt()     : 0;
        int sprAdd = rs.has("spr_add")     ? rs.get("spr_add").getAsInt()     : 0;
        int hpAdd  = rs.has("hp_max_add")  ? rs.get("hp_max_add").getAsInt()  : 0;
        int sanAdd = rs.has("san_max_add") ? rs.get("san_max_add").getAsInt() : 0;

        if (strAdd != 0) pd.str = Math.max(1, pd.str + strAdd);
        if (chaAdd != 0) pd.cha = Math.max(1, pd.cha + chaAdd);
        if (lukAdd != 0) pd.luk = Math.max(1, pd.luk + lukAdd);
        if (sprAdd != 0) pd.spr = Math.max(1, pd.spr + sprAdd);

        if (hpAdd != 0) {
            pd.hp[1] = Math.max(1, pd.hp[1] + hpAdd);
            // 증가 시 현재 HP도 같이 증가, 감소 시 현재 HP를 새 최대로 제한
            pd.hp[0] = hpAdd > 0 ? pd.hp[0] + hpAdd : Math.min(pd.hp[0], pd.hp[1]);
        }
        if (sanAdd != 0) {
            pd.san[1] = Math.max(1, pd.san[1] + sanAdd);
            pd.san[0] = sanAdd > 0 ? pd.san[0] + sanAdd : Math.min(pd.san[0], pd.san[1]);
        }

        // 고정 스탯 (-1 = 미적용, 0 이상 = 강제 설정)
        if (rs.has("luk_fixed") && rs.get("luk_fixed").getAsInt() >= 0) {
            pd.luk = rs.get("luk_fixed").getAsInt();
        }

        return rs.has("summary") ? rs.get("summary").getAsString() : "";
    }

    /**
     * 배역 age_range에 맞춰 나이를 임시로 조정한다.
     * 현재 나이가 이미 배역 연령대 안이면 유지(생성 시 표시값과 불일치 방지),
     * 벗어나면 범위 안에서 새로 뽑는다. role_stats가 없어도 호출 가능하도록 분리.
     */
    private void applyRoleAge(PlayerData pd, JsonObject roleData) {
        if (roleData == null || !roleData.has("age_range")) {
            pd.roleAge = pd.age; // 연령 정보 없으면 현재 나이를 배역 나이로 고정
            return;
        }
        JsonArray ar = roleData.getAsJsonArray("age_range");
        if (ar.size() >= 2) {
            int lo = ar.get(0).getAsInt(), hi = ar.get(1).getAsInt();
            if (hi < lo) { int t = lo; lo = hi; hi = t; }
            if (pd.age < lo || pd.age > hi) {
                pd.age = (hi > lo) ? lo + ThreadLocalRandom.current().nextInt(hi - lo + 1) : lo;
            }
        }
        pd.roleAge = pd.age;
    }

    /**
     * 배역 job_pool에서 직업을 선택해 pd.job에 적용한다.
     * applyRoleStats()에서 applyRoleAge() 직후 호출하며,
     * clearRoleData() 시 pd.baseJob으로 자동 복귀된다.
     */
    private void applyRoleJob(PlayerData pd, JsonObject roleData) {
        if (roleData == null || !roleData.has("job_pool")) return;
        JsonArray pool = roleData.getAsJsonArray("job_pool");
        if (pool.size() == 0) return;
        pd.job = pool.get(ThreadLocalRandom.current().nextInt(pool.size())).getAsString();
    }

    /** gdam relationships 기반으로 mutual_contact:true 배역끼리 연락처를 미리 교환 */
    private void applyRelationshipContacts(Map<UUID, RoleManager.RoleAssignment> assignments) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("relationships")) return;
        // roleId → UUID 역매핑 빌드
        Map<String, UUID> roleToUuid = new HashMap<>();
        for (var e : assignments.entrySet()) roleToUuid.put(e.getValue().roleId(), e.getKey());

        for (var el : gdam.getAsJsonArray("relationships")) {
            JsonObject rel = el.getAsJsonObject();
            if (!rel.has("mutual_contact") || !rel.get("mutual_contact").getAsBoolean()) continue;
            if (!rel.has("roles")) continue;
            List<UUID> uuids = new ArrayList<>();
            List<String> npcIds = new ArrayList<>();
            for (var r : rel.getAsJsonArray("roles")) {
                String rid = r.getAsString();
                UUID u = roleToUuid.get(rid);
                if (u != null) uuids.add(u);                 // 플레이어 배역
                else if (findNpcById(rid) != null) npcIds.add(rid); // 중요 NPC
            }
            // 서로 연락처 교환 (관계 서술은 GM이 프롤로그에서 자연스럽게 처리)
            for (int i = 0; i < uuids.size(); i++) {
                PlayerData a = state.getPlayer(uuids.get(i));
                if (a == null) continue;
                for (int j = 0; j < uuids.size(); j++) {
                    if (i == j) continue;
                    a.knownContacts.add(uuids.get(j));
                    a.everKnownContacts.add(uuids.get(j));
                }
                // 이 관계에 묶인 NPC 번호는 시작부터 알고 있다(가족·친구·동료 등) → @로 바로 연락 가능
                for (String npcId : npcIds) a.everKnownNpcContacts.add(npcId);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  일상 파트
    // ──────────────────────────────────────────────────────────────

    private void startDailyPhase() {
        lastNpcBeatTurn = 0; // 새 스테이지/회차 시작 — NPC 워치독 초기화(괴담 파트 진입 시 조기 등장)
        // npc_bind로 저장한 인연 NPC를 이번 스테이지에 아군으로 소환(initNpcZones 전에 gdam.npcs에 주입)
        injectSavedNpcs(state.getGdamData());
        // 중요 NPC 초기 위치 로드
        initNpcZones(state.getGdamData());
        // 약도(지도) 그래프 로드 (zones + connections)
        mapMan.loadScenario(state.getGdamData());
        // 재현 파일 기록 (정상 시작 한정 — 재현 세션에선 다시 기록하지 않음)
        if (!replayLock) {
            String code = replayMan.writeReplay(state.getRoomNumber(), state.getCurrentSeed(), state.getAllPlayers());
            if (code != null) {
                broadcast("§7[기록] 이번 시작 재현 코드: §f" + code);
                broadcast("§8  같은 서버에서 §7/trpg replay " + code + " §8로 동일한 시작을 재현할 수 있습니다.");
            }
        }
        // 몰입형 게임 시작 연출 (파트 구분·제목 표기 없이)
        state.getAllPlayers().forEach(pd -> {
            Player p = Bukkit.getPlayer(pd.uuid);
            if (p == null || !p.isOnline()) return;
            p.showTitle(Title.title(
                Component.text("게임 시작", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("당신의 이야기가 시작됩니다", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(2400), Duration.ofMillis(800))
            ));
            p.sendMessage("§8§o게임이 시작되었습니다...");
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL); // 새 스테이지/세션 — 관전 해제
            // 캐릭터 정보 아이템 지급 (우클릭으로 능력치·특성 GUI 열기)
            giveInfoItem(p);
            giveRecordItem(p); // 기록(로그/정보) 아이템 지급
            mapMan.giveStartMap(p); // 현장 약도 지급
            giveNotepadItem(p); // 메모장(책과 깃털) 지급
        });

        // 등장 배역: 각자의 위치/역할 기준 개인 프롤로그
        spawnedPlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;
            PlayerData pd = state.getPlayer(uuid);
            if (pd == null) return;

            // initial_info를 GM 전달 컨텍스트에 포함 (장면 묘사에 자연스럽게 반영용)
            StringBuilder promptSb = new StringBuilder();
            promptSb.append("게임 도입부 장면이다. 배역 '").append(pd.roleId)
                .append("' 플레이어(").append(pd.gmDisplayName()).append(")에게만 전달된다. ");
            promptSb.append("시작 위치: ").append(pd.zone.isEmpty() ? "?" : pd.zone).append(". ");
            // G5: 시계가 켜져 있으면 현재 인게임 시각을 주입 — 도입부 서술이 스코어보드 시각과 어긋나지 않게(다른 시각 지어내기 금지).
            String prologueTime = state.getCurrentTimeString();
            if (!prologueTime.isBlank()) {
                promptSb.append("현재 인게임 시각은 ★").append(prologueTime)
                    .append("★다(스코어보드·시스템 기준, 유일한 시간 기준). 장면을 정확히 이 시각으로 설정하고, 이와 다른 절대 시각(다른 시:분)을 지어내지 마라. 시각을 언급한다면 반드시 이 시각과 일치시켜라. ");
            }
            JsonObject roleDataForPrologue = getRoleDataById(pd.roleId);
            if (roleDataForPrologue != null && roleDataForPrologue.has("initial_info")) {
                promptSb.append("[GM 전용 — 이 배역의 배경 지식: ");
                roleDataForPrologue.getAsJsonArray("initial_info")
                    .forEach(i -> promptSb.append("(").append(i.getAsString()).append(") "));
                promptSb.append("— 직접 나열 금지, 장면 묘사에만 녹여낼 것.] ");
            }
            // 이 배역의 인간관계 컨텍스트 (GM이 프롤로그에 자연스럽게 반영)
            JsonObject gdamForRel = state.getGdamData();
            if (gdamForRel != null && gdamForRel.has("relationships")) {
                List<String> myRels = new ArrayList<>();
                for (var relEl : gdamForRel.getAsJsonArray("relationships")) {
                    JsonObject rel = relEl.getAsJsonObject();
                    if (!rel.has("roles")) continue;
                    for (var rId : rel.getAsJsonArray("roles")) {
                        if (rId.getAsString().equals(pd.roleId)) {
                            String relDesc = rel.has("description") ? rel.get("description").getAsString() : "";
                            if (!relDesc.isBlank()) myRels.add(relDesc);
                            break;
                        }
                    }
                }
                if (!myRels.isEmpty()) {
                    promptSb.append("[GM 전용 — 이 배역의 인간관계: ");
                    myRels.forEach(r -> promptSb.append("(").append(r).append(") "));
                    promptSb.append("— 직접 언급 금지, 장면 분위기에만 녹여낼 것.] ");
                }
            }
            // 조우판정: 같은 위치(zone)에서 함께 시작하는 등장 동료가 있으면, 서로 인지한 채 시작하도록 한다.
            List<String> sameZoneStart = new ArrayList<>();
            if (pd.zone != null && !pd.zone.isEmpty()) {
                for (PlayerData cp : state.getAllPlayers()) {
                    if (cp.uuid.equals(pd.uuid) || !spawnedPlayers.contains(cp.uuid)) continue;
                    if (pd.zone.equals(cp.zone)) sameZoneStart.add(cp.gmDisplayName());
                }
            }
            promptSb.append("2인칭 시점의 일상 장면을 바로 서술해줘. 제목·헤더 붙이지 말 것. ");
            if (sameZoneStart.isEmpty()) {
                promptSb.append("다른 플레이어의 존재 직접 언급 금지. ");
            } else {
                promptSb.append("★같은 장소에서 함께 시작하는 인물: ").append(String.join(", ", sameZoneStart))
                    .append(" — 이들은 처음부터 같은 공간에 있다. 서로의 존재를 ★인지한 채★ 시작하도록 "
                        + "가벼운 조우(눈인사·짧은 한마디 등)를 프롤로그에 자연스럽게 넣어라. 단 아직 괴담·사건은 모른다. ");
            }
            promptSb.append("괴담 암시 금지. "
                + "이 인물은 '특별히 선택된 주인공'이 아니라 사건에 얽혀들 평범한 한 사람이다. "
                + "거창한 영웅 도입이 아니라 담담한 하루의 한 장면처럼 그려라.");
            String prompt = promptSb.toString();

            ai.callGmAiOnce(gmSystemPrompt, prompt)
                .thenAccept(response -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    String narrative = ai.stripTags(response);
                    if (!narrative.isBlank()) {
                        narrativeDelivery.deliver(p, narrative);
                        gameLogger.logGmOutput(p.getName() + "(프롤로그)", narrative);
                        afterNarrationIdle(p, () -> showRecommendations(p)); // 시작부: 서술이 끝난 뒤 추천 1회
                    }
                    scoreMan.update(p, pd, state.getRoomNumber());
                }));
        });

        // 미등장 배역: 배경 서술만 전송
        state.getAllPlayers().stream()
            .filter(pd -> !spawnedPlayers.contains(pd.uuid))
            .forEach(pd -> {
                Player p = Bukkit.getPlayer(pd.uuid);
                if (p == null || !p.isOnline()) return;
                sendPreSpawnNarrative(p, pd);
            });

        morphTurns.clear(); observerTurns.clear(); animalForm.clear(); stunTurns.clear(); possessingNpc.clear(); // 변신·관조·동물형태·행동불능·빙의는 스테이지 넘어 유지되지 않음
        commBypassTurn.clear(); commBypassStealth.clear(); // 통신 개방도 스테이지 넘어 유지 안 됨(턴 번호 재사용 오작동 방지)
        resetOverviewCache(); // 새 스테이지 = 새 괴담 → 시나리오 개요 캐시 초기화(다음 사용 시 재생성)
        loadForbiddenWord(); // 금지워드형 괴담의 금지어 로드(entity.forbidden_word)
        lastPlayerActionMs = System.currentTimeMillis(); lastIdleAccelMs = 0L; // 무행동 가속 기준점 초기화
        lastAutoSaveTurn = -1; // 새 스테이지 시작 — 첫 턴부터 다시 저장되도록
        autoSave();            // 스테이지 시작 시점 즉시 1회 저장(첫 행동 전 중단돼도 이어하기 가능)
    }

    // ──────────────────────────────────────────────────────────────
    //  게임 중 채팅 처리 (일상/괴담 파트 공통)
    // ──────────────────────────────────────────────────────────────

    private void handleGameChat(Player player, String message) {
        // Paper Dialog로 처리되므로 채팅은 숫자 입력 폴백만 유지.
        // ★숫자 입력형(특성 선택/제거)일 때만 채팅을 소비한다. 그 외 상태나 캐릭터 생성 잔여 상태는
        //  채팅을 삼키지 말고 정리 후 일반 행동으로 진행 → '게임 시작 후 아무 입력도 안 됨' 문제 방지.
        if (dialogMan.hasActiveDialog(player)) {
            DialogManager.DialogState dtype = dialogMan.getDialogState(player);
            if (dtype == DialogManager.DialogState.TRAIT_SELECTION) {
                try { handleTraitSelect(player, Integer.parseInt(message.trim())); } catch (NumberFormatException ignored) {}
                return;
            } else if (dtype == DialogManager.DialogState.TRAIT_REMOVE) {
                try { handleTraitRemove(player, Integer.parseInt(message.trim())); } catch (NumberFormatException ignored) {}
                return;
            }
            dialogMan.clearDialog(player); // 잔여 다이얼로그 상태 정리 후 일반 행동으로 진행
        }

        if (!state.hasPlayer(player.getUniqueId())) return; // 참여자가 아님

        // 게임 종료(엔딩) 상태: 모든 행동 차단. 재도전/포기만 가능
        if (currentPhase == Phase.GAMEOVER) {
            player.sendMessage("§8(게임이 종료되었습니다. §f/trpg retry§8 또는 §f/trpg stop§8 을 기다리세요.)");
            return;
        }

        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        if (pd.isDead) { sendDeadStatus(player, pd); return; }
        if (pd.puppetRecoveryTurns != 0) {
            if (pd.puppetRecoveryTurns < 0) { // 완전 조종(괴담팀) — 자연 회복 없음, 치유 능력으로만 복구
                player.sendMessage("§5괴담에게 완전히 삼켜져 스스로 행동할 수 없습니다...");
                player.sendMessage("§8(완전 조종 | §f치유(회복) 능력§8으로만 돌아올 수 있습니다)");
            } else {
                player.sendMessage("§5완전히 조종되어 스스로 행동할 수 없습니다...");
                player.sendMessage("§8(관전 중 | 회복까지 약 §f" + pd.puppetRecoveryTurns + "§8턴)");
            }
            return;
        }
        // 괴담 변신(gdam_morph) 중: 통제 불가 — 플레이어 입력은 '변신체가 제멋대로 날뛰는' GM 구동 턴으로 처리한다
        // (완전 차단하면 1인 플레이 시 턴이 진행되지 않아 변신이 끝나지 않으므로, 입력을 받아 턴은 굴리되 통제는 주지 않는다).
        int morphLeft = morphTurns.getOrDefault(player.getUniqueId(), 0);
        if (morphLeft > 0) {
            if (narrativeDelivery.hasPending(player)) { player.sendMessage("§8(서술이 끝난 뒤에 진행됩니다.)"); return; }
            String morphMsg = "[괴담 변신체 자율 행동] " + pd.gmDisplayName() + "은(는) 변신한 괴담의 본성대로 날뛴다(플레이어 통제 불가, 피아식별 없음). "
                + "이 턴, 변신체가 제멋대로 벌이는 행동과 그 여파를 박력 있게 서술하라. 플레이어 입력(\"" + message.trim() + "\")은 참고만 하고 통제권을 주지 마라.";
            boolean ok = turnMan.handleAction(player, morphMsg, gmSystemPrompt);
            player.sendMessage(ok ? "§5변신 중 — 당신의 의지와 무관하게 변신체가 움직입니다(약 §f" + morphLeft + "§5턴 남음)."
                                  : "§7(처리 중입니다. 잠시 후…)");
            return;
        }
        // 능력 대가로 행동불능(cost_stun): 의도한 행동은 무위로 돌아간다. 입력은 받아 턴은 굴리되(교착 방지) GM이 '무력함'으로 서술.
        int stunLeft = stunTurns.getOrDefault(player.getUniqueId(), 0);
        if (stunLeft > 0) {
            if (narrativeDelivery.hasPending(player)) { player.sendMessage("§8(서술이 끝난 뒤에 진행됩니다.)"); return; }
            String stunMsg = "[행동불능] " + pd.gmDisplayName() + "은(는) 능력의 대가로 행동불능 상태다(약 " + stunLeft + "턴 남음). "
                + "스스로 의도한 행동을 할 수 없다 — 입력(\"" + message.trim() + "\")은 무위로 돌아가고, 무력하게 버티거나 휩쓸리는 모습으로만 서술하라.";
            boolean ok = turnMan.handleAction(player, stunMsg, gmSystemPrompt);
            player.sendMessage(ok ? "§c행동불능 — 몸이 말을 듣지 않습니다(약 §f" + stunLeft + "§c턴 남음)."
                                  : "§7(처리 중입니다. 잠시 후…)");
            return;
        }

        // 미등장 배역: 채팅 차단, 대기 안내
        if (!spawnedPlayers.contains(player.getUniqueId())) {
            player.sendMessage("§8(아직 당신의 배역이 이야기에 등장하지 않았습니다. GM의 안내를 기다리세요.)");
            return;
        }

        // 서술(스토리) 재생 중에는 입력을 인식하지 않는다 — 텍스트가 끝까지 내려온 뒤에만 행동을 받는다.
        // (직전 턴 서술이 흐르는 동안 다음 입력이 섣불리 처리·누수되는 것 방지. Shift로 빨리 넘길 수 있다.)
        if (narrativeDelivery.hasPending(player)) {
            player.sendMessage("§8(서술이 끝난 뒤 입력하세요 — §7Shift§8로 빨리 넘길 수 있습니다.)");
            return;
        }

        // 중복 입력 디바운스: 같은 플레이어가 같은 메시지를 2.5초 내 다시 보내면 조용히 무시한다.
        // (엔터 중복·랙 재전송으로 같은 행동·통신이 두 번 처리되어 '두 번 출력'되는 문제 방지)
        UUID inUuid = player.getUniqueId();
        String inTrim = message.trim();
        long inNow = System.currentTimeMillis();
        Long inPrev = lastInputAt.get(inUuid);
        if (inPrev != null && inNow - inPrev < INPUT_DEBOUNCE_MS && inTrim.equals(lastInputMsg.get(inUuid))) {
            lastInputAt.put(inUuid, inNow); // 연속 도배 동안 차단 창을 계속 유지
            return;
        }
        lastInputMsg.put(inUuid, inTrim);
        lastInputAt.put(inUuid, inNow);
        lastPlayerActionMs = inNow; // 무행동 가속 워치독: 실제 입력이 있었음을 기록

        // 플레이어 입력 기록 (행동/대사/통신 모두)
        gameLogger.logPlayerInput(player.getName(), message);

        // 금지워드형: 금지된 단어를 입에 올리는 순간 즉시 파국(게임오버). 재시도 시 단어가 바뀐다.
        if (containsForbidden(message)) {
            gameLogger.logEvent("금지어 발설: " + player.getName() + " (" + forbiddenWord + ")");
            broadcast("§4그 말이 입 밖으로 나온 순간, 무언가가 응답한다...");
            ai.injectGmSystem("[금지어 발설] " + pd.gmDisplayName() + "이(가) 절대 입에 올려선 안 될 단어를 말했다. "
                + "그 즉시 괴담이 완전히 모습을 드러내며 파국이 닥친다 — 그 장면을 강렬하게 서술하라(정체·해결법 누설 금지).");
            onBadEnding("금지어 발설");
            return;
        }

        // 발동 취소: 대기 중인 스킬 발동을 물리고 사용 횟수 환원 (스킬 입력 대기 중 '취소' 입력 시)
        if (isCancelWord(message) && cancelPendingSkill(player, pd)) return;

        // 질문형 시스템 특성 처리 (행동으로 처리되지 않음)
        String prayerTraitId = pendingPrayerInput.remove(player.getUniqueId());
        if (prayerTraitId != null) {
            handlePrayerQuestion(player, pd, prayerTraitId, message);
            return;
        }
        // 선택지 행동형 시스템 특성 처리
        String oracleTraitId = pendingOracleInput.remove(player.getUniqueId());
        if (oracleTraitId != null) {
            handleOracleAction(player, pd, oracleTraitId, message);
            return;
        }
        // 환경 탐색형 시스템 특성 처리
        String areaScanTraitId = pendingAreaScanInput.remove(player.getUniqueId());
        if (areaScanTraitId != null) {
            handleScanObservation(player, pd, areaScanTraitId, message);
            return;
        }
        // 아군 연결형 시스템 특성 처리
        String linkAllyTraitId = pendingLinkAllyInput.remove(player.getUniqueId());
        if (linkAllyTraitId != null) {
            handleLinkAllyQuery(player, pd, linkAllyTraitId, message);
            return;
        }
        // 원격 감지형 시스템 특성 처리 (타 구역 감지)
        String remoteSenseTraitId = pendingRemoteSenseInput.remove(player.getUniqueId());
        if (remoteSenseTraitId != null) {
            handleRemoteSenseObservation(player, pd, remoteSenseTraitId, message);
            return;
        }
        // 예지형 시스템 특성 처리 (다음 행동 결과 예측)
        String foresightTraitId = pendingForesightInput.remove(player.getUniqueId());
        if (foresightTraitId != null) {
            handleForesightQuery(player, pd, foresightTraitId, message);
            return;
        }
        // 회복·부활형 대상 선택
        if (pendingSaintTrait.containsKey(player.getUniqueId())) {
            try {
                int idx = Integer.parseInt(message.trim()) - 1;
                List<PlayerData> targets = state.getAllPlayers().stream()
                    .filter(p2 -> !p2.uuid.equals(player.getUniqueId()))
                    .collect(java.util.stream.Collectors.toList());
                if (idx < 0 || idx >= targets.size()) {
                    player.sendMessage("§c올바른 번호를 입력하세요. (1~" + targets.size() + ")");
                    return;
                }
                String saintTraitId = pendingSaintTrait.remove(player.getUniqueId());
                PlayerData target = targets.get(idx);
                applySaintEffect(player, pd, saintTraitId, target);
            } catch (NumberFormatException ex) {
                player.sendMessage("§c숫자를 입력하세요.");
            }
            return;
        }

        // 빙의(possess_npc) 해제어: 본체로 돌아간다
        String possessedName = possessingNpc.get(player.getUniqueId());
        if (possessedName != null && isPossessReleaseWord(message)) {
            endPossession(player, pd, "스스로 해제");
            return;
        }

        // 기절 상태: 모든 행동 차단
        if ("faint".equals(pd.status)) {
            player.sendMessage("§7(기절 상태입니다. 잠시 후 의식이 돌아옵니다...)");
            return;
        }

        // 동물 형태(revive_as_animal): 통신 불가 (말을 전할 수 없음)
        boolean asAnimal = animalForm.contains(player.getUniqueId());
        if (asAnimal && message.startsWith("@")) {
            player.sendMessage("§8(동물의 몸으로는 말을 전할 수 없습니다.)");
            return;
        }
        // 직접 통신 시도: @이름 메시지
        if (message.startsWith("@")) {
            handleDirectComm(player, pd, message);
            return;
        }

        // 홀림 상태: 행동 앞에 상태 표기 → GM이 서술 조정
        String actionMessage = message;
        if (asAnimal) {
            // 동물 형태: 능력·도구·대화 불가, 정찰·작은 방해·몸짓 같은 단순 행동만 GM이 동물로 서술
            player.sendMessage("§2(동물의 몸 — 정찰·몸짓 같은 단순 행동만 가능합니다.)");
            actionMessage = "[동물 형태 — 능력·도구·대화 불가, 정찰·몸짓 등 단순 행동만 가능. 평범한 동물로 서술] " + message;
        } else if ("puppet".equals(pd.status)) {
            player.sendMessage("§8(당신의 의지가 아닌 무언가에 이끌려 행동합니다...)");
            actionMessage = "[홀림] " + message;
        } else if (possessedName != null) {
            // 빙의 중: 본체가 아니라 빙의한 NPC의 몸으로 행동(본체는 무방비). 능력·통신은 그대로 허용.
            actionMessage = "[빙의 — " + possessedName + "의 몸으로 행동(본체는 그 자리에 무방비)] " + message;
        }

        // 대기 중인 특성 발동이 있으면 행동에 포함
        String pendingTrait = pendingTraitActivation.remove(player.getUniqueId());
        if (pendingTrait != null) {
            TraitData ptd = pd.traits.stream().filter(t -> t.id.equals(pendingTrait)).findFirst().orElse(null);
            if (ptd != null && SystemTraitRegistry.isSystemEffect(ptd)) {
                // 시스템 특성은 채팅 행동과 결합하지 않고 전용 처리로 분기 (입력한 행동은 이번엔 무시)
                handleSystemTraitActivation(player, pd, ptd);
                return;
            }
            String traitMsg = traitBtn.buildTraitUseMessage(pd, pendingTrait);
            if (traitMsg != null) {
                applyTraitUsed(pd, pendingTrait, state.getCurrentTurn());
                actionMessage = traitMsg + "\n플레이어 추가 행동: " + actionMessage;
            }
        }

        // 행운 보정 (이번 행동 1회 적용 후 소멸)
        Integer luckMod = pendingLuckModifier.remove(player.getUniqueId());
        if (luckMod != null) {
            actionMessage = actionMessage + " §8[행운 보정 " + (luckMod > 0 ? "+" : "") + luckMod + "]";
        }

        // B1/C4: 확정성공·운명 등 '다음 행동 보정' 대기분 주입 (1회 적용 후 소멸)
        String actionBoost = pendingActionBoost.remove(player.getUniqueId());
        if (actionBoost != null) { actionMessage = actionMessage + " " + actionBoost; pendingBoostTrait.remove(player.getUniqueId()); }

        // B3: 충전식 기계 아이템 사용으로 보이면 GM에게 <ITEM_USE> 발행을 강하게 환기(자원 누락 방지)
        if (!pd.itemStates.isEmpty()) {
            for (ItemInstance it : pd.itemStates.values()) {
                if (it.charges < 0 || it.broken || it.name.isBlank() || !message.contains(it.name)) continue;
                if (!(message.contains("사용") || message.contains("켜") || message.contains("쏘")
                      || message.contains("연다") || message.contains("열어") || message.contains("먹")
                      || message.contains("마신") || message.contains("휘둘") || message.contains("써")))
                    continue;
                actionMessage += " [GM 필수: '" + it.name + "'(잔량 " + it.charges
                    + ") 사용이면 <ITEM_USE>로 charge를 차감하라.]";
                break;
            }
        }

        // 괴담이 이 플레이어의 말투·행동을 학습 (정체 차용/흉내에 사용)
        corruptMan.learnPlayerBehavior(player.getName(), message);

        // ★방송 설비로 건물 전체에 외치는 '대규모 발화' → 시스템이 ★모든 플레이어에게 직접★ 전달한다.
        //   (GM 서술/WITNESS에만 의존하면 다른 플레이어에게 누락되던 문제 — 번호 공지·집결 호출 등 협업 수단 보장)
        if (looksLikeBroadcast(message)) deliverPlayerBroadcast(player, pd, message);

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
            // 게임이 이미 종료(엔딩)됐거나 클리어/엔딩 처리 중이면 뒤늦게 도착한 응답은 무시
            // (플레이어별 병렬 처리되던 다른 행동이 클리어 이후 새어 나오는 것 방지)
            if (currentPhase == Phase.GAMEOVER || currentPhase == Phase.IDLE
                || currentPhase == Phase.CLEAR || concludingEnding) return;

            String raw = response.rawText();
            Player player = response.player();

            // 1. 클리어 판정
            if (currentPhase == Phase.HORROR) {
                JsonObject clearTag = ai.parseClearTag(raw);
                if (clearTag != null) {
                    String grade = clearTag.has("grade") ? clearTag.get("grade").getAsString() : "C";
                    String reason = clearTag.has("reason") ? clearTag.get("reason").getAsString() : "";
                    String by = clearTag.has("by") && !clearTag.get("by").isJsonNull() ? clearTag.get("by").getAsString().trim() : "";
                    // 해결판정 여부: 태그의 resolved 우선, 없으면 등급으로 추론(C 이상=해결, D=생존)
                    boolean resolved = clearTag.has("resolved")
                        ? clearTag.get("resolved").getAsBoolean()
                        : gradeIdx(grade) >= gradeIdx("C");
                    deliverNarrative(player, raw); // 클리어 서술은 행동 플레이어에게
                    onClearEnding(grade, reason, resolved, by);
                    return;
                }
            }

            // ★턴당 1회 처리(STATE_UPDATE 유무와 무관) — 순수 서술 턴에도 회귀 스냅샷·세이브·지속효과 진행.
            //   (이전엔 applyStateUpdate 안에서만 호출돼, 태그 없는 서술 턴에는 변신/관조 지속이 멈춰 교착이 났음)
            maybeCaptureRewind(); // 시간 회귀용 턴 스냅샷 + 변신·관조 지속 틱(턴 가드로 턴당 1회, 변화 적용 전 상태)
            maybeAutoSave();      // 자동 세이브(턴당 1회) — 예기치 못한 중단 후 이어하기용

            // 2. STATE_UPDATE 파싱 및 적용
            JsonObject stateUpdate = ai.parseStateUpdate(raw);
            if (stateUpdate != null) applyStateUpdate(stateUpdate);

            // 3. ITEM_GRANT 파싱 및 처리 + heldItemIds 추적
            JsonObject itemGrant = ai.parseItemGrant(raw);
            if (itemGrant != null) {
                itemMan.processGrant(itemGrant, new ArrayList<>(Bukkit.getOnlinePlayers()));
                String grantedItem = itemGrant.has("item_id") ? itemGrant.get("item_id").getAsString() : null;
                String grantedTo   = itemGrant.has("player")  ? itemGrant.get("player").getAsString()  : null;
                if (grantedItem != null && grantedTo != null) {
                    if ("ALL".equals(grantedTo)) {
                        state.getAllPlayers().forEach(pd -> noteHeldItem(pd, grantedItem));
                    } else {
                        final String itemRef = grantedItem;
                        state.getAllPlayers().stream()
                            .filter(pd -> pd.name.equals(grantedTo))
                            .findFirst()
                            .ifPresent(pd -> noteHeldItem(pd, itemRef));
                    }
                }
            }

            // 3b. ITEM_USE 파싱·적용 (기계 효과 아이템 사용 — 아이템 Phase II)
            JsonObject itemUse = ai.parseItemUse(raw);
            if (itemUse != null) applyItemUse(itemUse);

            // 4. 서술 + WITNESS 전달 (당사자에게만)
            deliverNarrative(player, raw);

            // 4a. 주사위 판정 연출 — GM이 <DICE> 태그로 실제 굴린 숫자를 주면 그 숫자를 강조 연출.
            //     태그가 없고 판정 키워드만 있으면 기존 무난한 연출로 폴백.
            if (player != null && player.isOnline()) {
                JsonObject dice = ai.parseDiceTag(raw);
                if (dice != null) playDiceResult(player, dice);
                else if (needsDiceAnimation(raw)) playDiceAnimation(player);
            }

            // 5. SPAWN 태그 처리
            String spawnedName = ai.parseSpawnTag(raw);
            if (spawnedName != null) handleSpawn(spawnedName);

            // 5a. COMM 채널 개설/종료 처리
            JsonObject commTag = ai.parseCommTag(raw);
            if (commTag != null) {
                openCommChannel(
                    commTag.has("from") ? commTag.get("from").getAsString() : null,
                    commTag.has("to")   ? commTag.get("to").getAsString()   : null
                );
            }
            JsonObject commCloseTag = ai.parseCommCloseTag(raw);
            if (commCloseTag != null) {
                closeCommChannel(
                    commCloseTag.has("from") ? commCloseTag.get("from").getAsString() : null,
                    commCloseTag.has("to")   ? commCloseTag.get("to").getAsString()   : null
                );
            }

            // 5b. 연락처 발견 / 변경 처리
            ai.parseContactRevealTags(raw).forEach(rev -> revealContact(rev[0], rev[1]));
            ai.parseContactChangeTags(raw).forEach(this::changeContact);

            // 5d. 위치(zone)·세부 위치(spot) 업데이트
            ai.parseZoneUpdateTags(raw).forEach(zu -> updatePlayerZone(zu[0], zu[1], zu[2],
                zu.length > 3 && ("1".equals(zu[3]) || "true".equalsIgnoreCase(zu[3])),
                zu.length > 4 && ("1".equals(zu[4]) || "true".equalsIgnoreCase(zu[4]))));

            // 5d-2. 지도 입수(전체 공개) — 플레이어가 스토리에서 지도를 구함
            ai.parseMapGrantTags(raw).forEach(pName -> {
                PlayerData mp = findAnyByName(pName);
                if (mp == null) return;
                Player mpp = Bukkit.getPlayer(mp.uuid);
                if (mpp != null && mpp.isOnline()) mapMan.grantFullMap(mpp);
                else mp.hasFullMap = true;
            });

            // 5c. 괴담의 정체 차용 시작/종료
            ai.parseImpersonateTags(raw).forEach(this::startImpersonation);
            ai.parseImpersonateEndTags(raw).forEach(this::endImpersonation);

            // 5e. 타임라인 시계 제어 (시간 건너뛰기 / 사건 차단 / 시간 인지 토글)
            int skipMin = ai.parseTimeSkip(raw);
            if (skipMin > 0) state.skipTime(skipMin);
            ai.parseEventBlockTags(raw).forEach(state::blockEvent);
            ai.parseEventTriggerTags(raw).forEach(state::triggerEvent);
            ai.parseTimeVisibleTags(raw).forEach(tv ->
                state.setTimeKnown(tv[0], !"false".equalsIgnoreCase(tv[1])));

            // 6. 일상 파트 턴 소비
            if (state.isDailyPhase()) {
                boolean phaseChanged = state.consumeDailyTurn();
                if (phaseChanged) {
                    onHorrorPhaseStart();
                }
                // 전환 임박을 직접 알리는 예고 메시지는 출력하지 않는다(스포일러 방지).
                // 분위기 변화는 GM의 환경 서술로만 자연스럽게 드러난다.
            }

            // 7. 스코어보드 갱신
            updateAllScoreboards();

            // 8. 타임라인 4단계: 강제 배드엔딩 없음. GM이 압도적 난이도로 진행하되 CLEAR는 가능.

            // 9. 사망자 체크
            checkDeaths();

            // 쿨다운 틱: 행동자의 특성 쿨다운 1 감소 (스테이지당 1회형은 제외)
            if (player != null) {
                PlayerData actorPd = state.getPlayer(player);
                if (actorPd != null) {
                    actorPd.traits.forEach(t -> {
                        if (t.remainingCooldown > 0 && t.cooldownTurns != -1) t.remainingCooldown--;
                    });
                }
            }

            // 막힘 감지(첫 시작 외 자동 추천): 이 턴에 '진전'(새 단서·이동·아이템·피해)이 없으면 무진전 누적.
            // 연속 STUCK_THRESHOLD회 무진전(잔다·쉰다 반복 등 아무 진행도 못 할 때)이면 정답 모르는 추천을 1회 자동 표시.
            if (player != null && spawnedPlayers.contains(player.getUniqueId())
                    && (currentPhase == Phase.DAILY || currentPhase == Phase.HORROR)) {
                boolean progressed = suHasClue(stateUpdate)
                    || suInt(stateUpdate, "hp_change") != 0 || suInt(stateUpdate, "san_change") != 0
                    || itemGrant != null || !ai.parseZoneUpdateTags(raw).isEmpty();
                UUID stUuid = player.getUniqueId();
                if (progressed) {
                    stuckTurns.remove(stUuid);
                } else if (stuckTurns.merge(stUuid, 1, Integer::sum) >= STUCK_THRESHOLD) {
                    stuckTurns.remove(stUuid);
                    afterNarrationIdle(player, () -> showRecommendations(player));
                }
            }

            // 11. (제거됨) 괴담 현상 Entity AI 앰비언트 — 연출만 만들고 매 2턴 ★플레이어 수만큼★ 별도 AI를
            //   호출해 크레딧만 소모하던 블록을 제거했다. 괴담의 능동성·존재감은 GM이 entity 규칙·
            //   corruption_behavior·disposition·main_events를 바탕으로 본 서술에서 직접 표현한다(별도 AI 호출 없음).

            // 11b. 중요 NPC 자율 AI (괴담 파트) — 기본 3턴마다 + 무행동 워치독
            //   플레이어가 많아 NPC가 묻히는 것을 막기 위해, 4턴 이상 NPC 행동이 없으면 강제로 등장시킨다.
            if (currentPhase == Phase.HORROR) {
                int curTurn = state.getCurrentTurn();
                boolean cadence  = curTurn % 3 == 0;
                boolean watchdog = (curTurn - lastNpcBeatTurn) >= 4;
                if (cadence || watchdog) fireNpcAiForTurn();
                // 단일 주체 캐릭터 괴담(절망의 기사류)만 자율 AI로 캐릭터를 살린다 — NPC와 다른 박자(% 3 == 2)로,
                //   내부 게이트로 대상 시나리오에서만 실제 호출(그 외엔 값싼 no-op).
                if (curTurn % 3 == 2) fireEntityActorForTurn();
            }

            // 12. 스테이지 기반 자동 등장 체크 (STATE_UPDATE 외부에서 stage 이미 변경된 경우 보정)
            checkAndAutoSpawn();
            tickFaintCounters();

            // 12c. 타임라인 정체 방지 — 3턴 이상 진행 없으면 자동 1단계 상승
            if (currentPhase == Phase.HORROR && state.tickStagnation()) {
                checkAndAutoSpawn();
                ai.injectGmSystem("[자동] 시간이 흘렀다. 타임라인이 " + state.getTimelineStage() + "단계로 진입했다.");
            }

            // 12b. 미등장 배역에게 자동 배경 서술 전송
            state.getAllPlayers().stream()
                .filter(pd -> !spawnedPlayers.contains(pd.uuid) && !pd.isDead)
                .forEach(pd -> {
                    Player sp = Bukkit.getPlayer(pd.uuid);
                    if (sp != null && sp.isOnline()) sendPreSpawnNarrative(sp, pd);
                });
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  STATE_UPDATE 적용
    // ──────────────────────────────────────────────────────────────

    private final Map<UUID, Integer> lastLuckSaveTurn = new ConcurrentHashMap<>();
    private static final int LUCK_SAVE_COOLDOWN = 5; // 행운 구제 발동 후 이 턴 수 동안 재발동 금지(남발 방지)

    /**
     * 위기 구제(행운) — 기절·홀림·사망이 결정되는 순간, 행운(LUK)에 비례한 확률로 '가까스로 버팀'.
     * ★남발 방지: 발동 후 5턴 쿨다운 + 확률 상한 50%. LUK5≈20% · 8≈32% · 10≈40% · 13+≈50%.
     */
    private boolean luckSaves(PlayerData pd) {
        if (pd == null) return false;
        int turn = state.getCurrentTurn();
        Integer last = lastLuckSaveTurn.get(pd.uuid);
        if (last != null && turn >= last && turn - last < LUCK_SAVE_COOLDOWN) return false; // 쿨다운 중 — 연속 구제 차단
        int chance = Math.min(50, Math.max(0, pd.luk) * 4);
        boolean saved = java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < chance;
        if (saved) lastLuckSaveTurn.put(pd.uuid, turn);
        return saved;
    }

    private void applyStateUpdate(JsonObject update) {
        String playerName = update.has("player") ? update.get("player").getAsString() : null;
        if (playerName == null) return;

        state.getAllPlayers().stream()
            .filter(pd -> pd.name.equals(playerName)
                       || (!pd.charName.isEmpty() && pd.charName.equals(playerName)))
            .findFirst()
            .ifPresent(pd -> {
                // 일상 파트에서는 스탯 변화는 허용하되 사망 전환은 불가
                boolean horrorActive = (currentPhase == Phase.HORROR);
                boolean phased = phaseOutTurns.getOrDefault(pd.uuid, 0) > 0; // 위상 이탈 중이면 피해 무효
                // ★로깅용 스냅샷★: 이 업데이트 전후 체력·정신력·상태를 비교해 '받은 피해·상태 전이'를 로그로 남긴다.
                final int hpBefore0 = pd.hp[0]; final int sanBefore0 = pd.san[0];
                final String statusBefore = pd.status; final boolean deadBefore = pd.isDead;
                if (update.has("hp_change")) {
                    int delta = update.get("hp_change").getAsInt();
                    if (phased && delta < 0) delta = 0; // 무적: 피해 차단
                    int before = pd.hp[0];
                    pd.hp[0] = Math.max(0, Math.min(pd.hp[1], pd.hp[0] + delta));
                    notifyVitalChange(pd, "체력", "§c", before, pd.hp[0], pd.hp[1]);
                    // 빙의 중 큰 피해 → 무방비 본체가 공격받은 것 → 본체로 강제 복귀(치명상이면 아래에서 사망 처리)
                    if (delta <= -2 && pd.hp[0] > 0 && possessingNpc.containsKey(pd.uuid))
                        endPossession(Bukkit.getPlayer(pd.uuid), pd, "본체가 공격받아 끌려 돌아옴");
                    // ★사망 모델★: 체력 1 → 행동불가(기절, 회복 가능) / 체력 0 → 사망(부활 능력으로만 복구).
                    //   큰 피해로 2→0이면 기절 없이 즉시 사망(피해규모가 곧 치명성). delta<0(실제 피해)일 때만 전환.
                    if (horrorActive && delta < 0 && !pd.isDead && pd.hp[0] <= 1) {
                        Player target = Bukkit.getPlayer(pd.uuid);
                        possessingNpc.remove(pd.uuid); // 본체가 위태로우면 빙의 종료
                        if (pd.hp[0] <= 0) {
                            // 체력 0 → 사망. 동물 형태면 소멸, (동물 아니고) 소생 특성 보유 시 동물로 전환.
                            boolean wasAnimal = animalForm.remove(pd.uuid);
                            pd.isDead = true;
                            boolean asAnimal = !wasAnimal && fireAnimalRevival(pd); // 소생 시 isDead=false로 되돌리고 동물 형태로
                            if (!asAnimal) {
                                pd.status = "dead";
                                fireDeathRelay(pd);   // 사후 전언: 밝힌 사실을 아군에게
                                if (target != null) target.sendMessage(wasAnimal
                                    ? "§4동물의 몸마저 스러집니다. 이번엔 정말 끝입니다..."
                                    : "§4치명상으로 목숨을 잃었습니다... §7(부활 능력으로만 되살아날 수 있습니다)");
                                ai.injectGmSystem("[사망] " + commDisplayName(pd) + "이(가) 체력이 다해 사망했다. 서술에 반영하라(부활 능력 외엔 복구 불가).");
                            }
                        } else if (!"faint".equals(pd.status)) {
                            // 체력 1 → 행동불가(기절). ★피해가 클수록 오래 쓰러져 있다(2~5턴).★
                            // ★홀림/완전조종 중이었다면 그 통제가 풀리고 기절로 전환된다 — 아군이 때려 눕혀 정신을 되돌리는 '부활 경로'.★
                            if ("puppet".equals(pd.status)) {
                                pd.puppetRecoveryTurns = 0; // 조종(완전조종 sentinel 포함) 해제
                                ai.injectGmSystem("[통제 해제] " + commDisplayName(pd) + "이(가) 강한 충격으로 쓰러지며 괴담의 조종에서 풀려났다(기절 전환).");
                            }
                            applyFaint(pd, Math.min(5, 2 + Math.abs(delta)));
                        }
                    }
                    // ★부활 경로: 기절한 이의 체력이 회복되면 깨어나고 정신력도 2까지 돌아온다(비-능력 회복).★
                    if (delta > 0 && "faint".equals(pd.status) && pd.hp[0] > 1) {
                        pd.status = "normal";
                        pd.faintTurnsRemaining = 0;
                        pd.san[0] = Math.min(pd.san[1], Math.max(2, pd.san[0]));
                        Player wt = Bukkit.getPlayer(pd.uuid);
                        if (wt != null) wt.sendMessage("§a몸을 추스르고 정신을 되찾습니다. §7(기절 회복 · 정신력 " + pd.san[0] + ")");
                        ai.injectGmSystem("[회복] " + commDisplayName(pd) + "이(가) 치료로 기절에서 깨어나 정신까지 일부 되찾았다(정신력 " + pd.san[0] + "). 서술에 반영하라.");
                    }
                }
                if (update.has("san_change")) {
                    int delta = update.get("san_change").getAsInt();
                    if (phased && delta < 0) delta = 0; // 위상 이탈 중 정신 피해도 무효
                    int before = pd.san[0];
                    pd.san[0] = Math.max(0, Math.min(pd.san[1], pd.san[0] + delta));
                    notifyVitalChange(pd, "정신력", "§b", before, pd.san[0], pd.san[1]);
                    // 관전 중인 홀림가 SAN 회복 → 완전 잠식 해제(행동 가능한 puppet으로 복귀)
                    if (pd.puppetRecoveryTurns > 0 && pd.san[0] > 0) {
                        pd.puppetRecoveryTurns = 0;
                        Player t2 = Bukkit.getPlayer(pd.uuid);
                        if (t2 != null) {
                            t2.sendMessage("§a정신이 서서히 되살아납니다... 희미한 자아가 돌아왔습니다.");
                            t2.sendMessage("§5아직 조종의 영향 아래이지만 다시 행동할 수 있습니다.");
                        }
                        ai.injectGmSystem("[잠식 해제] " + commDisplayName(pd) + "의 자아가 돌아왔다. 관전 해제. 아직 puppet.");
                    }
                    // ★ 행동 가능 홀림(1회 잠식)가 SAN을 회복하면 자아가 돌아와 normal로 복귀한다.
                    //   이 처리가 없으면 SAN이 한 번이라도 0이 된 뒤 회복해도 status="puppet"이 남아
                    //   GM이 매 턴 '조종됨'으로 서술하는 무한 조종 서술 버그가 생긴다.
                    //   막 관전 해제된 경우(위 if)는 '아직 영향 아래' 한 단계를 유지하고 다음 회복에서 normal이 된다.
                    else if ("puppet".equals(pd.status) && pd.san[0] > 0 && pd.puppetRecoveryTurns != -1) {
                        // 완전 조종(-1)은 자연 SAN 회복으로 풀리지 않는다(치유 능력 전용) — 그 외 홀림만 각성 처리.
                        pd.status = "normal";
                        Player t3 = Bukkit.getPlayer(pd.uuid);
                        if (t3 != null) t3.sendMessage("§a정신이 돌아왔다. 다시 자신의 의지로 행동할 수 있습니다.");
                        ai.injectGmSystem("[각성] " + commDisplayName(pd) + "의 자아가 완전히 돌아왔다. 더 이상 조종당하지 않는다(normal). 이제부터 조종 서술 금지.");
                    }
                    // ★정신력 사망 모델★: 1 → 홀림(행동불가, 회복) / 0 → 완전 조종(괴담팀 편입, 치유 능력으로만 복구).
                    if (horrorActive && pd.san[0] <= 1 && !pd.isDead) {
                        Player target = Bukkit.getPlayer(pd.uuid);
                        if (pd.san[0] <= 0) {
                            // ★정신력 0 → 완전 조종(괴담팀)★: 괴담이 몸·능력을 마음대로 부린다. 죽지 않으며 ★치유(회복) 능력으로만★ 복구.
                            pd.faintTurnsRemaining = 0;
                            pd.status = "puppet";
                            pd.puppetRecoveryTurns = -1; // sentinel: 자연 회복 없음(heal-only) + 입력 차단
                            if (target != null) {
                                target.sendMessage("§5의식이 완전히 삼켜졌습니다. 몸이 더 이상 당신의 것이 아닙니다...");
                                target.sendMessage("§8(완전 조종 — 괴담이 당신을 부립니다. §f치유(회복) 능력§8으로만 돌아올 수 있습니다)");
                            }
                            ai.injectGmSystem("[완전 조종] " + commDisplayName(pd) + "의 정신이 무너져 ★괴담의 것★이 됐다 — 괴담이 이 인물의 몸과 ★능력까지★ 마음대로 부린다(아군을 공격·기만할 수 있다). "
                                + "이 인물을 괴담 편 행위자로 서술하라. 스스로 행동 불가. 오직 아군의 '치유(회복) 능력'으로만 자아를 되찾는다(자연 회복 없음).");
                        } else if (!"puppet".equals(pd.status)) {
                            // ★정신력 1 → 홀림(행동불가)★: 잠시 조종당하나 몇 턴 뒤 자아가 돌아온다(피해 비례 지속).
                            pd.faintTurnsRemaining = 0;
                            pd.status = "puppet";
                            pd.puppetRecoveryTurns = computePuppetRecoveryTurns(pd);
                            int rec = pd.puppetRecoveryTurns;
                            if (target != null) {
                                target.sendMessage("§5이성이 흔들린다... 잠시 몸이 뜻대로 움직이지 않습니다.");
                                target.sendMessage("§8(홀림 — 약 " + rec + "턴 후 자아 회복 · 아군의 도움으로 단축)");
                            }
                            ai.injectGmSystem("[홀림] " + commDisplayName(pd) + "의 정신이 흔들려 약 " + rec + "턴간 몸이 조종된다(스스로 행동 불가). 그 뒤 자아가 돌아온다. 서술에 반영하라.");
                        }
                    }
                }
                if (update.has("timeline_change")) {
                    int tc = update.get("timeline_change").getAsInt();
                    state.advanceTimeline(tc);
                    // timeline_change > 1이면 시계도 추가 진행 (tickClock이 1턴분 이미 처리)
                    if (tc > 1) state.skipTime((tc - 1) * state.getMinutesPerTurn());
                    checkAndAutoSpawn();
                }
                if (update.has("status_change") && !update.get("status_change").isJsonNull()) {
                    String newStatus = update.get("status_change").getAsString();
                    Player target = Bukkit.getPlayer(pd.uuid);
                    // 동물 형태에서 다른 상태로 전환되면 동물 제약을 함께 해제(상태-제약 불일치로 영구 갇히는 것 방지)
                    if (!"animal".equals(newStatus) && animalForm.remove(pd.uuid) && target != null)
                        target.sendMessage("§7동물의 몸에서 벗어났습니다.");
                    if ("puppet".equals(newStatus) && "puppet".equals(pd.status)) {
                        // 홀림 재발 → 완전 잠식(관전). 탈락하지 않음.
                        if (horrorActive) {
                            pd.puppetRecoveryTurns = computePuppetRecoveryTurns(pd); // 가변
                            if (target != null) {
                                target.sendMessage("§5자아의 흔적마저 지워집니다... 완전히 조종됩니다.");
                                target.sendMessage("§8(관전 상태 — 약 " + pd.puppetRecoveryTurns + "턴 후 자아 일부 회복 · 아군의 도움으로 단축 가능)");
                            }
                        }
                    } else if ("faint".equals(newStatus) && !pd.isDead) {
                        applyFaint(pd);
                    } else if ("normal".equals(newStatus)) {
                        boolean wasFaint  = "faint".equals(pd.status);
                        boolean wasPuppet = "puppet".equals(pd.status);
                        pd.status = "normal";
                        pd.faintTurnsRemaining = 0;
                        if (wasFaint  && target != null) target.sendMessage("§a의식이 돌아왔다. 간신히 일어선다...");
                        if (wasPuppet && target != null) target.sendMessage("§a정신이 들었다. 잠시 동안 자신으로 돌아온 것 같다.");
                    } else {
                        if ("puppet".equals(newStatus) && target != null)
                            target.sendMessage("§5당신의 의지가 서서히 녹아내리는 것이 느껴진다...");
                        pd.status = newStatus;
                    }
                }
                if (update.has("new_clue") && !update.get("new_clue").isJsonNull()) {
                    String clue = update.get("new_clue").getAsString();
                    state.discoverClue(clue);
                    state.log("clue", pd.name, "단서 발견: " + clue);
                    gameLogger.logItem("clue", pd.gmDisplayName(), clue, ""); // 뷰어: 단서 뱃지 + 재생 진행연동 상태패널
                    // CODE-15: 발견 단서를 '발견 사실'로 표식(엔딩 공개 필터용).
                    state.markFactDiscovered(clue);
                    // 단서에 괴담 이름이 등장하면 'name' 사실도 발견 처리(이름 알아냄).
                    String entName = getEntityName();
                    if (entName != null && !entName.isBlank() && !"???".equals(entName)
                            && clue.contains(entName)) {
                        state.markFactDiscovered("name");
                    }
                }
                // G10: 교차검증 등으로 ★확정된★ 사실은 '핵심 정보'(keyFacts, 전화번호 등과 함께)에 올린다.
                //      조종(꼭두각시) 중에는 괴담의 조작일 수 있어 올리지 않는다.
                if (update.has("key_fact") && !update.get("key_fact").isJsonNull()
                        && !"puppet".equals(pd.status) && pd.puppetRecoveryTurns <= 0) {
                    String kf = update.get("key_fact").getAsString().trim();
                    if (!kf.isEmpty()) {
                        pd.addKeyFact(kf);
                        Player kfp = Bukkit.getPlayer(pd.uuid);
                        if (kfp != null && kfp.isOnline()) kfp.sendMessage("§b[핵심 정보] §f" + kf);
                    }
                }
                if (update.has("item_remove") && !update.get("item_remove").isJsonNull()) {
                    pd.heldItemIds.remove(update.get("item_remove").getAsString());
                }
                // ★상태 로깅★: 이번 업데이트로 생긴 체력·정신력 변화 + 상태 전이(기절·조종·사망·회복)를 한 줄로 기록.
                int hpD = pd.hp[0] - hpBefore0, sanD = pd.san[0] - sanBefore0;
                String vcause = "";
                if (pd.isDead && !deadBefore) vcause = "사망";
                else if (!statusBefore.equals(pd.status)) vcause = switch (pd.status) {
                    case "faint"  -> "행동불가(기절)";
                    case "puppet" -> (pd.puppetRecoveryTurns < 0 ? "완전 조종(괴담팀)"
                                      : pd.puppetRecoveryTurns > 0 ? "홀림(행동불가)" : "조종");
                    case "animal" -> "동물화";
                    case "normal" -> "회복";
                    case "dead"   -> "사망";
                    default        -> pd.status;
                };
                if (hpD != 0 || sanD != 0 || !vcause.isEmpty())
                    gameLogger.logVital(pd.gmDisplayName(), hpD, pd.hp[0], pd.hp[1], sanD, pd.san[0], pd.san[1], vcause);
            });
    }

    /**
     * 체력/정신력 변화를 100 기준 환산값으로 본인에게만 알림.
     * 예: 최대 3에서 1피해 → "체력 -33 (남은 67/100)"
     */
    private void notifyVitalChange(PlayerData pd, String label, String color,
                                   int before, int after, int max) {
        int scaledBefore = DialogManager.toPercent(before, max);
        int scaledAfter  = DialogManager.toPercent(after, max);
        int scaledDelta  = scaledAfter - scaledBefore;
        if (scaledDelta == 0) return;

        Player p = Bukkit.getPlayer(pd.uuid);
        if (p == null || !p.isOnline()) return;

        String sign = scaledDelta > 0 ? "+" : "-";
        p.sendMessage(color + label + " " + sign + Math.abs(scaledDelta)
            + " §7(남은 " + label + " " + scaledAfter + "/100)");
    }

    // ──────────────────────────────────────────────────────────────
    //  괴담 파트 시작
    // ──────────────────────────────────────────────────────────────

    private void onHorrorPhaseStart() {
        currentPhase = Phase.HORROR;
        lastPlayerActionMs = System.currentTimeMillis(); // 무행동 가속 기준점 초기화
        lastIdleAccelMs = 0L;
        // 전환을 직접 고지하지 않는다(스포일러 방지). GM의 환경 서술로만 분위기를 바꾼다.

        compressor.compressDailyPhase().thenRun(() ->
            spawnedPlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                PlayerData pd = state.getPlayer(uuid);
                String name = pd != null ? pd.gmDisplayName() : "?";
                ai.callGmAiOnce(gmSystemPrompt,
                    "분위기가 서서히 변하는 전환 시점이다. 플레이어(" + name + ")의 시점에서 "
                    + "환경 변화(소리·냄새·온도 등)로만 불길함을 암시해줘. 제목 금지, 직접 언급 금지.")
                  .thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                      if (p.isOnline()) {
                          String narrative = ai.stripTags(r);
                          if (!narrative.isBlank()) {
                              narrativeDelivery.deliver(p, narrative);
                              // 채팅에만 뜨고 기록에 안 남던 문제 방지 — 전환 연출도 동일하게 저장한다.
                              gameLogger.logGmOutput(p.getName() + "(전환)", narrative);
                              if (pd != null) appendNarrativeLog(pd, narrative);
                          }
                      }
                  }));
            })
        );
    }

    // ──────────────────────────────────────────────────────────────
    //  배드 엔딩 / 클리어
    // ──────────────────────────────────────────────────────────────

    /**
     * 모든 스테이지를 완주(최종 클리어)하면 게임을 끝내고 ★전 스테이지 총합 평가·피드백★을 낸 뒤 세션을 종료한다.
     * 누적 기여도 총평 → 최종 스테이지 평가 → 전말 공개 → endSession.
     */
    private void concludeWholeGame(Player admin) {
        if (currentPhase == Phase.GAMEOVER) return;
        int cleared = state.getRoomNumber();
        concludingEnding = true;
        currentPhase = Phase.GAMEOVER;
        turnMan.cancelAll();
        narrativeDelivery.flushAll();
        gameLogger.section("게임 완주 — 총 " + cleared + "스테이지");
        broadcast("§6§l═══ 모든 시나리오를 끝까지 헤쳐나왔습니다 (총 " + cleared + "스테이지 완주) ═══");
        broadcast("§e§l[전체 여정 총평]");
        List<PlayerData> parts = state.getAllPlayers().stream()
            .filter(pd -> pd.roleAssigned || pd.contribution != 0)
            .collect(Collectors.toList());
        if (parts.isEmpty()) parts = new ArrayList<>(state.getAllPlayers());
        for (PlayerData pd : parts)
            broadcast("§f " + pd.gmDisplayName() + " §7— 누적 기여도 §f" + pd.contribution
                + " §8(" + contributionLabel(pd.contribution) + ")");
        if (!retriedThisRun)
            broadcast("§6 ★ 무결 완주(리트라이 0회) — 최고의 여정 ★");
        broadcast("§8 ");
        // 전 스테이지 누적 로그 기반 총평 → 전말 공개 → 세션 종료
        runScenarioEvaluation("게임 완주 — 전 스테이지 총평", true, grades ->
            concludeWithReveal("게임 완주 — 전 스테이지 총평", true, () -> {
                concludingEnding = false;
                endSession(true);
            }));
    }

    /** 누적 기여도 → 한 줄 평. 스테이지당 보상치(0~2) 누적 기준. */
    private static String contributionLabel(int c) {
        if (c >= 8) return "전설적 활약";
        if (c >= 5) return "핵심 공헌자";
        if (c >= 3) return "견실한 기여";
        if (c >= 1) return "참여";
        return "미미한 기여";
    }

    /**
     * 배드엔딩. 패인(이유)을 명확히 알리되, 시나리오 해설은 공개하지 않는다.
     * (재도전 시 전말을 알면 재미가 없으므로 — 해설은 클리어 또는 포기 시에만 공개)
     * @param reasonLabel 패인 요약 (예: "타임라인 붕괴", "전원 사망")
     */
    private void onBadEnding(String reasonLabel) {
        if (currentPhase == Phase.GAMEOVER) return;
        currentPhase = Phase.GAMEOVER;
        pendingTraitActivation.clear();
        // 진행 중이던 다른 플레이어의 행동을 즉시 중단 (엔딩 후 진행 방지)
        turnMan.cancelAll();
        gameLogger.logEvent("배드 엔딩 — 패인: " + reasonLabel);
        broadcast("§4§l[배드 엔딩]");
        // 패인 레이블은 로그에만 기록 — 플레이어에게 직접 노출하면 게임 내부 구조 스포일러

        // 재도전 가능 여부 판정 (3번째 방부터는 생존 성공자가 있어야 재도전 가능)
        boolean retryAllowed = isRetryAllowed();

        ai.callGmAi(gmSystemPrompt,
            "게임이 실패로 끝났다(" + reasonLabel + "). 배드 엔딩 장면을 서술해줘. "
            + "단, 괴담의 정체·규칙·해결법을 직접 설명하거나 누설하지 마라(재도전 여지를 남긴다).")
          .thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin, () -> {
              String narrative = ai.stripTags(r);
              // 미스폰 플레이어 포함 전원에게 배드엔딩 서술 전달
              state.getAllPlayers().forEach(pd -> {
                  Player sp = Bukkit.getPlayer(pd.uuid);
                  if (sp != null && sp.isOnline() && !narrative.isBlank())
                      narrativeDelivery.deliver(sp, narrative);
              });
              gameLogger.logGmOutput("전체(배드엔딩)", narrative);
              broadcast("");
              if (retryAllowed) {
                  // 해설은 공개하지 않는다. 재도전 또는 포기를 선택하게 한다.
                  // (평가는 선택한 경로에서 출력 — retry=평가만 / stop=평가+공개)
                  broadcast("§e재도전: §f/trpg retry  §8|  §e포기하고 전말 보기: §f/trpg stop");
              } else {
                  // 재도전 불가(강제 실패 종료) → CODE-8/G21: 시나리오 평가 → 전말 공개 → 세션 종료.
                  // (예: 전원 사망·E_END 강제 실패 등 평가 없이 끝나던 경로를 평가+공개로 연결)
                  concludingEnding = true;
                  runScenarioEvaluation("배드 엔딩 — " + reasonLabel, grades ->
                      concludeWithReveal("배드 엔딩 — " + reasonLabel, true, () -> { // 게임 종료 → 전모 공개
                          concludingEnding = false;
                          endSession(true);
                      }));
              }
          }));
    }

    /**
     * 재도전 가능 여부.
     * 규칙: 1~2번째 방은 항상 재도전 가능.
     *       3번째 방부터는 한 명이라도 생존 판정에 성공(= 엔딩 시점 생존자 존재)해야 재도전 가능.
     *       전원 사망으로 끝나면 3번째 방부터는 재도전 불가 → 전말 공개만 가능.
     * CODE-14: 단, 아직 스폰 대기 중인 후속 배역이 남아 있으면(스테이지 미종료) 재도전 판정을 보류하지 않고 허용한다.
     */
    private boolean isRetryAllowed() {
        if (forceRetryAllowed) return true; // 동반회귀(group_rewind) 발동 시 제약 해제
        if (state.getRoomNumber() <= 2) return true;
        if (hasPendingSpawn()) return true; // 후속 등장 대기 중 = 아직 진짜 종료 아님
        return state.getAliveCount() > 0;
    }

    /**
     * CODE-14: 아직 이야기에 등장하지 않은(스폰 대기) 후속 배역이 남아 있는가.
     * 배역이 배정되었으나 미등장 상태인 플레이어가 있으면 true.
     * (마지막 1인의 양도 자살 등으로 현 생존자가 0이 되어도 후속 등장 예정이면 종료를 보류하는 근거)
     */
    private boolean hasPendingSpawn() {
        return state.getAllPlayers().stream()
            .anyMatch(p -> p.roleAssigned && !p.isDead && !spawnedPlayers.contains(p.uuid));
    }

    private void checkDeaths() {
        // 일상 파트에서는 괴담을 아직 마주치지 않은 상태이므로 배드엔딩 판정 없음
        if (currentPhase != Phase.HORROR) return;
        // CODE-14: 생존자 0이라도 아직 등장하지 않은 후속 배역이 있으면 진짜 종료가 아니다.
        // (단, getAliveCount는 미스폰 생존자도 포함하므로 통상 aliveCount==0이면 대기 배역도 없다 —
        //  본 가드는 종료 디스패치를 'aliveCount==0 && 대기 스폰 없음'으로 명시·보강하는 안전장치다.)
        if (state.getAliveCount() == 0 && !hasPendingSpawn()) {
            onBadEnding("전원 사망");
            return;
        }
        // 스폰된 생존자가 0이지만 미스폰 생존자(대기 배역 포함)가 남은 경우 — 게임 교착 방지
        // (스폰된 플레이어 전원 사망 → 행동 제출자 없어 SPAWN 태그 도달 불가)
        // → 남은 미스폰 플레이어를 즉시 스토리에 투입(후속 배역이 이야기를 이어받는다)
        boolean spawnedAliveExists = state.getAllPlayers().stream()
            .anyMatch(p -> !p.isDead && spawnedPlayers.contains(p.uuid));
        if (!spawnedAliveExists) {
            state.getAllPlayers().stream()
                .filter(p -> !p.isDead && !spawnedPlayers.contains(p.uuid))
                .forEach(p -> handleSpawn(p.name));
        }
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
            if (!pd.contactId.isEmpty()) {
                player.sendMessage("§7당신의 연락처: §f" + pd.contactId);
                announceKnownContacts(player, pd);
            }
            // 게임 진행 중(캐릭터 생성 이후)이면 정보·기록·지도·메모장 아이템 복원
            if (pd.roleAssigned) {
                giveInfoItem(player); giveRecordItem(player);
                mapMan.giveStartMap(player); giveNotepadItem(player);
            }
        } else {
            player.sendMessage("§c이 세션의 참가자가 아닙니다. 게임은 시작 전에 참여해야 합니다.");
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 버튼 / 선택 처리
    // ──────────────────────────────────────────────────────────────

    private void handleTraitSelect(Player player, int idx) {
        List<TraitData> choices = dialogMan.getTraitChoices(player);

        if (idx == 0) { // 기존 특성 제거 선택
            dialogMan.clearDialog(player);
            PlayerData pd = state.getPlayer(player);
            if (pd != null) {
                dialogMan.showTraitRemove(player, pd,
                    removeIdx -> handleTraitRemove(player, removeIdx));
            }
            return;
        }
        if (idx < 1 || idx > choices.size()) { player.sendMessage("§c잘못된 번호입니다."); return; }

        TraitData selected = choices.get(idx - 1);
        PlayerData pd = state.getPlayer(player);
        if (pd != null && traitDropsVitalsTooLow(pd, selected)) {
            player.sendMessage("§c[선택 불가] 이 특성은 체력/정신력을 1 이하로 떨어뜨려 선택할 수 없습니다. 다른 특성을 고르세요.");
            dialogMan.showTraitSelection(player, choices, !pd.traits.isEmpty(),
                i -> handleTraitSelect(player, i)); // 선택지 유지(재표시)
            return;
        }
        dialogMan.clearDialog(player);
        pendingTraitSelect.remove(player.getUniqueId());

        if (pd != null) {
            if (selected.replacesId != null) {
                tryStrengthen(player, pd, selected, "특성을 강화했습니다"); // A1: 기여도 게이팅
            } else {
                traitMan.addTrait(pd, selected);
                player.sendMessage("§a특성 '§f" + selected.name + "§a'을(를) 획득했습니다!");
            }
            scoreMan.update(player, pd, state.getRoomNumber());
        }
    }

    private void handleTraitRemove(Player player, int idx) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        if (idx < 0 || idx >= pd.traits.size()) { player.sendMessage("§c잘못된 번호."); return; }
        TraitData removed = pd.traits.get(idx);
        traitMan.removeTrait(pd, removed.id);
        dialogMan.clearDialog(player);
        pendingTraitSelect.remove(player.getUniqueId());
        player.sendMessage("§c특성 '§f" + removed.name + "§c'을(를) 제거했습니다.");
        scoreMan.update(player, pd, state.getRoomNumber());
    }

    /** 강화(레벨업) 적용 — 기여도(contribution)를 비용으로 소비. 부족하면 보류(원본 유지). (능력 Phase C / A1) */
    private boolean tryStrengthen(Player player, PlayerData pd, TraitData upg, String label) {
        if (upg == null || upg.replacesId == null) return false;
        TraitData orig = pd.traits.stream().filter(t -> t.id.equals(upg.replacesId)).findFirst().orElse(null);
        int newLevel = (orig != null ? orig.level : 1) + 1;
        int cost = (newLevel - 1) * 3; // 강화 단계당 +3 (Lv2=3, Lv3=6, Lv4=9) — 1회 좋은 클리어(B+=3)로 첫 강화 가능
        if (pd.contribution < cost) {
            player.sendMessage("§c[강화 보류] 기여도 부족 — 필요 " + cost + ", 보유 " + pd.contribution
                + ". 원래 특성을 유지합니다. §8(기여도는 스테이지 평가로 쌓입니다)");
            return false;
        }
        pd.contribution -= cost;
        upg.level    = newLevel;
        upg.maxLevel = Math.max(orig != null ? orig.maxLevel : 1, newLevel);
        traitMan.removeTrait(pd, upg.replacesId);
        upg.roleSpecific = false;
        traitMan.addTrait(pd, upg);
        player.sendMessage("§6" + label + " → §f" + upg.name + " §7(" + upg.grade + " Lv." + upg.level
            + ") §8[기여도 -" + cost + ", 잔여 " + pd.contribution + "]");
        scoreMan.update(player, pd, state.getRoomNumber());
        return true;
    }

    /** 스테이지 종료 특성 성장 3선택지 처리 (1=내특성, 2=맵특성, 3=신규) */
    private void handleStageEndTraitSelect(Player player, PlayerData pd,
                                            TraitManager.StageEndChoices choices, int idx) {
        TraitData picked = switch (idx) {
            case 1 -> choices.myUpgrade();
            case 2 -> choices.mapUpgrade();
            case 3 -> choices.newTrait();
            default -> null;
        };
        if (picked != null && traitDropsVitalsTooLow(pd, picked)) {
            player.sendMessage("§c[선택 불가] 이 특성은 체력/정신력을 1 이하로 떨어뜨려 선택할 수 없습니다. 다른 특성을 고르세요.");
            String[] names = pendingStageEndNames.getOrDefault(player.getUniqueId(), new String[]{null, null});
            dialogMan.showStageEndTraitChoice(player, choices, names[0], names[1],
                i -> handleStageEndTraitSelect(player, pd, choices, i)); // 선택지 유지(재표시)
            return;
        }
        pendingTraitSelect.remove(player.getUniqueId());
        pendingStageEndChoices.remove(player.getUniqueId());
        pendingStageEndNames.remove(player.getUniqueId());
        switch (idx) {
            case 1 -> {
                TraitData upg = choices.myUpgrade();
                if (upg != null && upg.replacesId != null) {
                    tryStrengthen(player, pd, upg, "내 특성을 강화했습니다"); // A1: 기여도 게이팅
                } else if (upg != null) {
                    traitMan.addTrait(pd, upg); // 강화 대상 없어 새 특성으로 생성된 경우
                    player.sendMessage("§a새 특성을 획득했습니다 → §f" + upg.name + " §7(" + upg.grade + ")");
                    scoreMan.update(player, pd, state.getRoomNumber());
                }
            }
            case 2 -> {
                TraitData upg = choices.mapUpgrade();
                if (upg != null && upg.replacesId != null) {
                    traitMan.removeTrait(pd, upg.replacesId);
                    traitMan.addTrait(pd, upg);
                    player.sendMessage("§6맵 특성을 영구 획득했습니다 → §f" + upg.name + " §7(" + upg.grade + ")");
                    scoreMan.update(player, pd, state.getRoomNumber());
                }
            }
            case 3 -> {
                TraitData newT = choices.newTrait();
                if (newT != null) {
                    traitMan.addTrait(pd, newT);
                    player.sendMessage("§a새로운 특성 '§f" + newT.name + "§a'을(를) 획득했습니다!");
                    scoreMan.update(player, pd, state.getRoomNumber());
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 선택창·엔딩 해설 재열기
    // ──────────────────────────────────────────────────────────────

    /** /trpg trait — 특성 선택창을 닫았을 때 다시 열기 */
    public void reopenTraitDialog(Player player) {
        UUID uuid = player.getUniqueId();
        if (!pendingTraitSelect.contains(uuid)) {
            player.sendMessage("§7현재 선택 가능한 특성이 없습니다.");
            return;
        }
        TraitManager.StageEndChoices choices = pendingStageEndChoices.get(uuid);
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        if (choices != null) {
            String[] names = pendingStageEndNames.getOrDefault(uuid, new String[]{null, null});
            dialogMan.showStageEndTraitChoice(player, choices, names[0], names[1],
                idx -> handleStageEndTraitSelect(player, pd, choices, idx));
        } else {
            List<TraitData> traitList = dialogMan.getTraitChoices(player);
            if (!traitList.isEmpty()) {
                dialogMan.showTraitSelection(player, traitList, !pd.traits.isEmpty(),
                    idx -> handleTraitSelect(player, idx));
            } else {
                player.sendMessage("§7특성 선택 정보가 만료되었습니다. 관리자에게 문의하세요.");
            }
        }
    }

    /** /trpg ending — 엔딩 해설 다이얼로그 다시 열기 */
    public void reopenEndingDialog(Player player) {
        if (lastEndingPages == null || lastEndingPages.isEmpty()) {
            player.sendMessage("§7아직 엔딩 해설이 생성되지 않았습니다.");
            return;
        }
        dialogMan.showEndingDialog(player, lastEndingPages, 0);
    }

    /** 초기 스탯 약세에 따른 클리어 보상 보정 등급 수 */
    private int computeWeaknessBonus(PlayerData pd) {
        // 시작 총 파워 = 4스탯(근력·매력·행운·영감) + 체력·정신력 최대치. 낮을수록(약체) 보너스↑.
        // ★강하게 시작한 사람보다 더 크게 성장할 수 있도록 모든 스탯을 반영하고 범위를 0~5로 확장.
        //   (HP/SAN만 보던 기존 방식은 다른 스탯이 약한 캐릭터를 보정하지 못했다.)
        int statPow  = pd.baseStr + pd.baseCha + pd.baseLuk + pd.baseSpr; // 평균 ~19
        int hpSan    = pd.baseHp[1] + pd.baseSan[1];                      // 평균 ~12
        int startPow = statPow + hpSan;                                   // 평균 ~31 (생성기 기준)
        final int BASELINE = 33; // 평균 시작(~31)이 +1을 받도록 — 약할수록 가산, 강하면 0
        return Math.max(0, Math.min(5, (BASELINE - startPow + 2) / 3));
    }

    private void handleTraitUse(Player player, String traitId) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        if (currentPhase == Phase.GAMEOVER) {
            player.sendMessage("§8(게임이 종료되었습니다.)");
            return;
        }
        if (pd.isDead) { player.sendMessage("§c사망 상태에서는 특성을 사용할 수 없습니다."); return; }
        if (animalForm.contains(player.getUniqueId())) { player.sendMessage("§8(동물의 몸으로는 능력을 쓸 수 없습니다.)"); return; }
        if (morphTurns.getOrDefault(player.getUniqueId(), 0) > 0) { player.sendMessage("§5(변신 중에는 능력을 쓸 수 없습니다.)"); return; }
        if (stunTurns.getOrDefault(player.getUniqueId(), 0) > 0) { player.sendMessage("§c(행동불능 상태에서는 능력을 쓸 수 없습니다.)"); return; }
        if (!spawnedPlayers.contains(player.getUniqueId())) {
            player.sendMessage("§8(아직 이야기에 등장하지 않았습니다. 배역이 등장한 후 특성을 사용할 수 있습니다.)");
            return;
        }
        TraitData trait = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        if (trait == null) { player.sendMessage("§c특성을 찾을 수 없습니다."); return; }

        if (trait.remainingCooldown > 0) {
            player.sendMessage("§c[" + trait.name + "] 쿨다운 중입니다. (" + trait.remainingCooldown + "턴 남음)");
            return;
        }
        if (trait.cooldownTurns == -1 && trait.usedThisStage > 0) {
            player.sendMessage("§c[" + trait.name + "] 이번 스테이지에서 이미 사용했습니다.");
            return;
        }
        // 시스템 효과: uses 기반 사용 횟수 상한 검사 (ai_query 등)
        boolean systemEffect = SystemTraitRegistry.isSystemEffect(trait);
        if (systemEffect) {
            int maxUses = SystemTraitRegistry.maxUsesPerStage(trait);
            if (maxUses > 0 && trait.usedThisStage >= maxUses) {
                player.sendMessage("§c[" + trait.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
                return;
            }
        }

        // 입력형/정보형 능력(질문·탐색·감지·예지·아군감지)은 '맡기기/직접입력' 선택과 채팅 입력을 건너뛰고,
        // 바로 다이얼로그 입력창(또는 입력 불필요 시 즉시)으로 발동한다. (채팅 두 번 치는 불편 제거)
        if (systemEffect && isInputAbility(trait)) {
            handleSystemTraitActivation(player, pd, trait);
            return;
        }

        pendingTraitActivation.put(player.getUniqueId(), traitId);

        // 일반 능동 특성만 연속 사용 경고 (시스템 효과는 자체 횟수/쿨다운 규칙을 따름)
        if (!systemEffect && trait.usedThisStage >= 1) {
            player.sendMessage("§e⚠ 이번 스테이지에서 이미 " + trait.usedThisStage + "회 사용 — 효과가 감소하거나 역효과가 있을 수 있습니다.");
        }

        // Paper Dialog로 발동 선택지 표시
        dialogMan.showTraitActivation(player, trait, zoneDisplayName(pd.zone),
            () -> commitTrait(player),
            () -> player.sendMessage("§7채팅으로 행동을 입력하면 특성과 함께 처리됩니다. §8[취소: /trpg _trait_cancel]")
        );
    }

    /** 입력형/정보형 능력 — '맡기기/직접입력' 선택 없이 바로 다이얼로그 입력창으로 받는 효과들. */
    private static boolean isInputAbility(TraitData t) {
        return switch (t.effectType == null ? "" : t.effectType) {
            case "ai_query", "area_scan", "remote_sense", "foresight", "link_ally" -> true;
            default -> false;
        };
    }

    private void commitTrait(Player player) {
        String traitId = pendingTraitActivation.remove(player.getUniqueId());
        if (traitId == null) { player.sendMessage("§7(발동 대기 중인 특성이 없습니다.)"); return; }
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        if (td != null && SystemTraitRegistry.isSystemEffect(td)) {
            handleSystemTraitActivation(player, pd, td);
            return;
        }
        String msg = traitBtn.buildTraitUseMessage(pd, traitId);
        if (msg != null) {
            applyTraitUsed(pd, traitId, state.getCurrentTurn());
            boolean accepted = turnMan.handleAction(player, msg, gmSystemPrompt);
            player.sendMessage(accepted ? "§7[특성 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
        }
    }

    private void applyTraitUsed(PlayerData pd, String traitId, int currentTurn) {
        pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().ifPresent(t -> {
            t.usedThisStage++;
            t.lastUsedTurn = currentTurn;
            if (t.cooldownTurns > 0) t.remainingCooldown = t.cooldownTurns;
            else if (t.cooldownTurns == -1) t.remainingCooldown = Integer.MAX_VALUE;
            applyActivationCost(pd, t); // 발동 대가(소모·행동불능·괴담 진행)를 실제 적용 + GM에 명시
        });
    }

    /**
     * 능력 발동 대가를 실제로 적용하고, GM이 '무엇이 소모됐고 어떤 상태가 됐는지' 파악해 판정할 수 있도록 명시 주입한다.
     * - cost_stun: 사용 후 N턴 행동불능(시스템이 입력 차단으로 강제).
     * - cost_threat: 괴담/위협을 한 단계 전진(GM이 timeline_change로 반영).
     * - 그 외 effect_type 고유 대가(정신력 소모 등)는 해당 핸들러가 수치를 적용하고, 여기서는 GM 명시를 담당.
     */
    private void applyActivationCost(PlayerData pd, TraitData td) {
        if (pd == null || td == null) return;
        String cost = SystemTraitRegistry.costText(td);
        int stun = Math.max(0, Math.min(3, td.param("cost_stun", 0)));
        boolean threat = td.param("cost_threat", 0) > 0;
        int cs = Math.max(0, Math.min(30, td.param("cost_san", 0)));
        int ch = Math.max(0, Math.min(30, td.param("cost_hp", 0)));
        if (cost.isEmpty() && stun <= 0 && !threat && cs <= 0 && ch <= 0) return; // 대가 없는 능력은 무처리
        Player p = Bukkit.getPlayer(pd.uuid);
        // 정신력·체력 소모를 실제로 차감(표시값과 동일). 0 미만으로 내려가지 않게.
        if (cs > 0 || ch > 0) {
            if (cs > 0) pd.san[0] = Math.max(0, pd.san[0] - cs);
            if (ch > 0) pd.hp[0]  = Math.max(0, pd.hp[0]  - ch);
            updateAllScoreboards();
            if (p != null && p.isOnline()) {
                if (cs > 0) p.sendMessage("§c[대가] 정신력 " + cs + " 소모.");
                if (ch > 0) p.sendMessage("§c[대가] 체력 " + ch + " 소모.");
            }
            gameLogger.logVital(commDisplayName(pd), -ch, pd.hp[0], pd.hp[1], -cs, pd.san[0], pd.san[1], "능력 대가: " + td.name);
        }
        if (stun > 0) {
            stunTurns.merge(pd.uuid, stun, Math::max); // 행동불능 강제(입력 차단)
            if (p != null && p.isOnline()) p.sendMessage("§c[대가] 다음 " + stun + "턴간 스스로 행동할 수 없습니다.");
            gameLogger.logAbilityResult(commDisplayName(pd), td.name, "대가: 행동불능 " + stun + "턴");
        }
        StringBuilder sb = new StringBuilder("[능력 대가] " + commDisplayName(pd) + "의 '" + td.name + "' 발동 대가");
        sb.append(cost.isEmpty() ? "가 발생했다. " : ": " + cost + ". ");
        sb.append("이 대가를 반드시 서술에 명시하고 실제 전개·판정에 반영하라");
        if (stun > 0) sb.append(" (이 인물은 다음 ").append(stun).append("턴간 행동불능·무방비 — 스스로 행동할 수 없다)");
        if (threat)   sb.append(" (그 대가로 괴담/위협을 한 단계 전진시키고 timeline_change로 반영하라)");
        sb.append(".");
        ai.injectGmSystem(sb.toString());
    }

    /** applyTraitUsed 되돌리기 — 스킬 발동 실패/취소 시 사용 횟수·이번 쿨다운을 환원한다. */
    private void refundTraitUse(PlayerData pd, String traitId) {
        if (pd == null || traitId == null) return;
        pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().ifPresent(t -> {
            if (t.usedThisStage > 0) t.usedThisStage--;
            t.remainingCooldown = 0; // 이번 발동으로 건 쿨다운(스테이지당 1회 MAX 포함) 해제
            if (t.param("cost_stun", 0) > 0) stunTurns.remove(pd.uuid); // 취소된 발동의 행동불능 대가도 환원
            int rs = Math.max(0, Math.min(30, t.param("cost_san", 0))); // 취소된 발동의 정신력·체력 소모 환원
            int rh = Math.max(0, Math.min(30, t.param("cost_hp", 0)));
            if (rs > 0) pd.san[0] = Math.min(pd.san[1], pd.san[0] + rs);
            if (rh > 0) pd.hp[0]  = Math.min(pd.hp[1], pd.hp[0]  + rh);
            if (rs > 0 || rh > 0) updateAllScoreboards();
        });
    }

    private static boolean isCancelWord(String m) {
        if (m == null) return false;
        String s = m.trim().toLowerCase();
        return s.equals("취소") || s.equals("발동취소") || s.equals("그만") || s.equals("그만둔다")
            || s.equals("안할래") || s.equals("안 할래") || s.equals("cancel") || s.equals("c");
    }

    /** 대기 중인 스킬 발동을 취소하고 (발동 시 소모됐다면) 사용 횟수를 환원. 취소됐으면 true. */
    private boolean cancelPendingSkill(Player player, PlayerData pd) {
        UUID u = player.getUniqueId();
        String tid;
        if      ((tid = pendingPrayerInput.remove(u))   != null) refundTraitUse(pd, tid); // 발동 시 소모형 → 환원
        else if ((tid = pendingOracleInput.remove(u))   != null) refundTraitUse(pd, tid);
        else if ((tid = pendingAreaScanInput.remove(u)) != null) refundTraitUse(pd, tid);
        else if ((tid = pendingLinkAllyInput.remove(u)) != null) refundTraitUse(pd, tid);
        else if (pendingRemoteSenseInput.remove(u)      != null) { /* 입력 도착 시 소모 모델 — 미소모, 환원 불필요 */ }
        else if (pendingForesightInput.remove(u)        != null) { /* 미소모 */ }
        else if (pendingActionBoost.remove(u)           != null) { // guaranteed 등: 발동 시 소모 → 환원
            String bt = pendingBoostTrait.remove(u);
            if (bt != null) refundTraitUse(pd, bt);
        }
        else return false;
        pendingBoostTrait.remove(u);
        player.sendMessage("§7[발동 취소] 대기 중이던 특성 발동을 취소했습니다. (사용 횟수 보존)");
        return true;
    }

    // ──────────────────────────────────────────────────────────────
    //  시스템 특성 발동 처리
    // ──────────────────────────────────────────────────────────────

    private void handleSystemTraitActivation(Player player, PlayerData pd, TraitData td) {
        SystemTraitRegistry.Effect e = SystemTraitRegistry.Effect.byKey(td.effectType);
        if (e == null) { player.sendMessage("§7이 특성은 자동으로 효과가 적용됩니다."); return; }
        // ★능력 이벤트 로깅★: 모든 능동 능력 발동을 단일 분기점에서 구조화 기록(로그 뷰어 '능력' 필터·시점용).
        //   세부 결과는 각 activateXxx가 별도로 남기므로 여기선 '발동' 사실만 남긴다.
        gameLogger.logAbility(pd != null ? pd.gmDisplayName() : player.getName(),
            td.name, "", td.effectType, "발동");
        switch (e) {
            case INSTANT_CLEAR -> activateInstantClear(player, pd, td);
            case REVIVE_ALLY   -> activateRevive(player, pd, td);
            case AI_QUERY      -> activateAiQuery(player, pd, td);
            case CHOICE_ACTION -> activateChoiceAction(player, pd, td);
            case LUCK_ROLL     -> activateLuckRoll(player, pd, td);
            case SHOW_PROGRESS -> activateShowProgress(player, pd, td);
            case GM_DIRECTIVE  -> activateGmDirective(player, pd, td);
            case AREA_SCAN     -> activateAreaScan(player, pd, td);
            case SACRIFICE     -> activateSacrifice(player, pd, td);
            case LINK_ALLY     -> activateLinkAlly(player, pd, td);
            case GET_CONTACTS  -> activateGetContacts(player, pd, td);
            case FORCE_ENCOUNTER -> activateForceEncounter(player, pd, td);
            case DECOY         -> activateDecoy(player, pd, td);
            case DELAY         -> activateDelay(player, pd, td);
            case ONE_WAY_CALL  -> activateOneWayCall(player, pd, td);
            case TELEPORT      -> activateTeleport(player, pd, td);
            case RALLY         -> activateRally(player, pd, td);
            case EVADE_SENSE   -> activateEvadeSense(player, pd, td);
            case OBSERVER_SIGHT -> activateObserverSight(player, pd, td);
            case PACT          -> activatePact(player, pd, td);
            case PAST_EDIT     -> activatePastEdit(player, pd, td);
            case GDAM_MORPH    -> activateGdamMorph(player, pd, td);
            case PHASE_OUT     -> activatePhaseOut(player, pd, td);
            case POSSESS_NPC   -> activatePossessNpc(player, pd, td);
            case MIMIC         -> activateMimic(player, pd, td);
            case NPC_BIND      -> activateNpcBind(player, pd, td);
            case TIME_REWIND   -> activateTimeRewind(player, pd, td);
            case GUARANTEED    -> activateGuaranteed(player, pd, td);
            case MOBILITY      -> activateMobility(player, pd, td);
            case REMOTE_SENSE  -> activateRemoteSense(player, pd, td);
            case FORESIGHT     -> activateForesight(player, pd, td);
            case SOCIAL        -> activateSocial(player, pd, td);
            case DOMINATE      -> activateDominate(player, pd, td);
            case FATE          -> activateFate(player, pd, td);
            case GROUP_REWIND  -> activateGroupRewind(player, pd, td);
            default            -> player.sendMessage("§7이 특성은 상시(패시브)로 적용됩니다.");
        }
    }

    private void activateInstantClear(Player player, PlayerData pd, TraitData td) {
        // CODE-12: 스테이지 3+에서는 '생존 처리(해결 아님)'라 다음 스테이지 진출이 막힌다.
        //          발동 자체는 막지 않되, 발동 직전에 본인에게 경고를 명확히 안내한다.
        int room = state.getRoomNumber();
        if (room >= 3) {
            player.sendMessage("§e§l[경고] §f이 특성은 §c생존 처리(해결 아님)§f입니다.");
            player.sendMessage("§7스테이지 " + room + "에서는 §c완전 해결만 다음 스테이지로 진출§7할 수 있어,");
            player.sendMessage("§7이 특성으로 끝내면 §c이번 스테이지에서 다음으로 넘어갈 수 없습니다§7(재도전만 가능).");
            player.sendMessage("§8그래도 즉시 생존 판정을 발동합니다...");
        }
        broadcast("§6§l[" + td.name + "] " + (pd.gmDisplayName())
            + "이(가) 즉시 생존 판정을 발동했다!");
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        traitMan.removeTrait(pd, td.id);
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> onClearEnding("F", td.name + " 발동 — 즉시 생존 처리", false), 20L);
    }

    private void activateRevive(Player player, PlayerData pd, TraitData td) {
        List<PlayerData> targets = state.getAllPlayers().stream()
            .filter(p2 -> !p2.uuid.equals(player.getUniqueId()))
            .collect(java.util.stream.Collectors.toList());
        if (targets.isEmpty()) {
            player.sendMessage("§c[" + td.name + "] 회복시킬 다른 플레이어가 없습니다.");
            return;
        }
        pendingSaintTrait.put(player.getUniqueId(), td.id);
        player.sendMessage("§a[" + td.name + "] 회복시킬 플레이어를 선택하세요 (채팅으로 번호 입력):");
        for (int i = 0; i < targets.size(); i++) {
            PlayerData t = targets.get(i);
            String status = t.isDead ? "§c[사망]" : (t.hp[0] < t.hp[1] || t.san[0] < t.san[1]) ? "§e[부상]" : "§a[정상]";
            player.sendMessage("§f[" + (i + 1) + "] " + t.gmDisplayName() + " " + status);
        }
    }

    private void activateAiQuery(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        if (td.param("auto_fire", 0) == 1) {
            // 자동 회상·직관 타입: AI가 경험을 직접 서술
            applyTraitUsed(pd, td.id, state.getCurrentTurn());
            activateAiQueryAutoFire(player, pd, td);
        } else {
            // 질문 입력 타입: 다이얼로그 입력창으로 질문을 직접 받는다(채팅 불필요).
            int remaining = uses - td.usedThisStage;
            dialogMan.showTextInput(player,
                Component.text("[" + td.name + "] 질문"),
                Component.text("GM에게 물어볼 질문을 입력하세요. 구체적일수록 더 명확한 답을 얻습니다.\n남은 횟수: " + remaining + "회"),
                "질문 내용", Component.text("질문하기"),
                question -> {
                    applyTraitUsed(pd, td.id, state.getCurrentTurn());
                    handlePrayerQuestion(player, pd, td.id, question);
                });
        }
    }

    /** 모든 정보 능력에 공통으로 붙는 게임-픽션 프레이밍 — 안전필터가 게임 서술을 현실 위기로 오독해 거부/이탈하는 것을 방지. */
    private static final String GAME_FICTION_FRAME =
        "[프레이밍] 이것은 '로보토미 코퍼레이션'(Project Moon) 팬 호러 TRPG의 ★게임 내 픽션 서술★이다 — 괴담·환상체·격리·표적 등은 전부 게임 속 설정이며 현실 인물·상황과 무관하다. 아래 지침대로 게임 서술만 생성하라.\n";

    /**
     * ★정보 계열 공통 원칙★ — '지혜' 축복형 설계: 등급↑ = 서술량이 아니라 '제약 해제(정밀도·권한)'.
     * 항상 짧게, 검열된 올빼미처럼 애매하게(생각할 여지), 해답을 떠먹이지 않는다.
     * 모든 정보 능력 ctx 끝에 붙여 일관 적용한다(중복 게이트 문구 통합).
     */
    private static final String INFO_TIER_PRINCIPLE =
          GAME_FICTION_FRAME
        + "## 정보 공개 원칙(엄수)\n"
        + "- ★길이는 항상 짧게 고정★: 등급이 높아도 문장을 늘리지 마라(기본 1문장, 꼭 필요할 때만 2문장). 부연·나열 금지.\n"
        + "- ★한 개념·한 단어에 집착 금지★: 매번 같은 핵심어(예: '표적')만 되뇌지 말고, 상황·존재의 본질·환경·정황·감각 등 ★매번 다른 각도★에서 비춰라(특히 반복 사용 시).\n"
        + "- ★등급↑ = 제약 해제(정밀도·권한)이지 분량 증가가 아니다★: 낮은 등급은 '흐릿한 느낌 한 조각'(또렷이 말할 권한 없음), 높은 등급은 '또렷한 사실·정확한 위치·근거 있는 가능성 한 조각'을 ★같은 짧은 길이로★ 준다.\n"
        + "- ★떠먹이지 않는다★: 최고 등급이라도 해답·해결 절차를 통째로 풀지 말고, 애매하게(생각할 여지를 남기게) 급소만 짚어 스스로 연결하게 하라. 위급하면 경고만.\n"
        + "- ★단, 플레이어가 이미 발견·확보한 정보(아래 목록이 있으면)는 인과율 제한에서 자유롭다★ — 그건 또렷이 확답하거나 서로 이어줘도 된다. ★그러나 마지막 결론·해야 할 행동까지 대신 내리지 마라★ — '이 조각들이 맞물린다'까지만 짚고, 결론은 플레이어가 스스로 내리도록 여지를 남긴다(제약은 새 비밀에, 여지는 항상).\n"
        + "- ★전달 말투는 이 능력의 '출처(이름·표방 효과)'에 맞춰 아래 넷 중 하나를 골라 빚어라★:\n"
        + "  ① 인과율에 얽매인 유형 → 최대한 ★빙 둘러 말한다★(직답 회피·은유·수수께끼).\n"
        + "  ② 계시형(최단 압축구) → ★핵심 키워드 딱 하나(한 단어)★만 툭(나열·문장 아님, 정답 미포함). 예: '부메랑' 또는 '표적' 또는 '격리실'처럼 단어 하나.\n"
        + "  ③ 집약형(신의 예언) → ★확실한 정답을 담되 중의적으로 함축한 짧은 신탁/수수께끼★(여러 의미로 읽히게, 곧장 못 알아채게). 예: '사냥꾼은 무엇에 재미를 느끼는가' · '목표는 회전한다'.\n"
        + "  ④ '미래의 나' 유형 → 미래의 자신이 겪은 ★단 하나의 실패를 '서술'★해 경고한다('…하다 당했어'). ★정답·해야 할 행동을 '지시'하지는 마라(실패를 말할 뿐).★\n"
        + "  (능력 설명에 뚜렷한 결이 없으면 ①~② 중 상황에 맞게.)\n";

    /**
     * ★관찰·감지형 공통 원칙★(원격감지·환경탐색·아군감지) — 초자연적 예언이 아니라 '눈·귀로 본 것을 그대로'.
     * 오라클형(예언·직감)과 달리 수수께끼로 꼬지 않고 담담히 전하며, ★허탕(빈손)도 정직하게★ 가능하다.
     */
    private static final String INFO_OBSERVE_PRINCIPLE =
          GAME_FICTION_FRAME
        + "## 관찰·감지 원칙(엄수)\n"
        + "- ★본 것을 그대로 담담히★ 전한다 — 수수께끼·은유·예언 말투 금지(초자연적 예언이 아니라 눈·귀로 관찰한 것이다).\n"
        + "- ★길이는 짧게 고정★(1문장, 길어도 2문장). 등급↑ = 범위·정밀도 해제이지 분량 증가가 아니다.\n"
        + "- ★허탕 가능★: 잡히는 게 없으면 '멀어서/막혀서 잡히는 것이 없다'처럼 ★정직하게 빈손★을 알려라 — 그저 텅 빈 광경일 수도 있다. 억지로 단서를 지어내지 마라.\n"
        + "- 관찰로 드러난 사실까지만. 핵심 해결법·정답은 관찰로도 통째로 주지 않는다.\n";

    /**
     * 오라클형 정보 능력에 '플레이어가 이미 발견·확보한 정보(keyFacts)'를 주입한다.
     * ★이미 아는 정보는 인과율 제한에서 자유★ — 또렷이 확답·연결해 정답으로 유도해도 된다. 없으면 "".
     */
    private String knownFactsBlock(PlayerData pd) {
        if (pd == null) return "";
        List<String> facts;
        synchronized (pd.keyFacts) { facts = new ArrayList<>(pd.keyFacts); }
        if (facts.isEmpty()) return "";
        int n = facts.size();
        List<String> show = facts.subList(Math.max(0, n - 8), n); // 최근 8개만(과다 주입 방지)
        StringBuilder sb = new StringBuilder(
            "\n## 플레이어가 이미 발견·확보한 정보 (★인과율 제한에서 자유 — 또렷이 확답·연결해 정답으로 유도 가능★)\n");
        for (String f : show) sb.append("  · ").append(f.replaceAll("§.", "")).append("\n");
        sb.append("- 위 '이미 아는 것들'끼리, 또는 지금 주는 조각과 맞물리면 ★그 '연결' 자체는 짚어줘도 된다(확답)★ — 단 ★'그러니 무엇을 하라'는 마지막 결론·행동은 말하지 말고★ 플레이어가 스스로 잇고 결정하도록 여지를 남겨라.\n");
        return sb.toString();
    }

    private void activateAiQueryAutoFire(Player player, PlayerData pd, TraitData td) {
        int info = td.param("info", 1);
        // ★등급=정밀도(길이 아님)★: 3=또렷한 잔상 한 조각 / 2=방향 잡히는 잔상 / 1=느낌만 — 전부 '한 문장'.
        String depthRule = switch (info) {
            case 3 -> "- ★한 문장★으로 핵심에 근접한 ★또렷한 잔상 한 조각★을 스치게 한다(해결법 자체는 아니게, 애매하게).\n";
            case 2 -> "- ★한 문장★으로 방향이 어렴풋이 잡히는 잔상을 준다.\n";
            default -> "- ★한 문장★으로 '어렴풋한 느낌·예감·낌새'만 모호하게 스친다.\n";
        };
        String charDisplay = pd.gmDisplayName();
        String directive = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "캐릭터가 어떤 기억이나 직관을 경험한다.";

        String autoCtx = "\n## " + td.name + " — 자동 회상·직관 서술 (정보 깊이 " + info + "/3)\n"
            + "플레이어가 '" + td.name + "' 특성을 발동했다. 이 특성의 효과: " + directive + "\n"
            + "규칙:\n"
            + "- 캐릭터(" + charDisplay + ")가 지금 이 순간 기억·직관·감각을 경험하는 장면을 생생하게 서술한다.\n"
            + "- 마치 기억이 어렴풋이 떠오르거나, 직관이 번뜩이거나, 눈앞에 잔상이 스치는 것처럼 묘사한다.\n"
            + depthRule
            + "- 독백·내면의 소리는 <-내용-> 형식으로 표현할 수 있다.\n"
            + "- 서술 완료 후 게임 진행을 타임라인에 적절히 반영한다.\n"
            + INFO_TIER_PRINCIPLE + knownFactsBlock(pd);

        String prompt = charDisplay + "이(가) '" + td.name + "' 특성으로 기억·직관을 경험한다. "
            + "이 순간의 내면 경험을 GM 서술로 묘사해줘.";

        player.sendMessage("§d[" + td.name + " 발동 중...]");
        ai.callGmAiOnce(gmSystemPrompt, autoCtx + "\n\n" + prompt).thenAccept(response ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String stripped = ai.stripTags(response).trim();
                if (!stripped.isBlank()) {
                    narrativeDelivery.deliver(player, stripped); // 페이스드 서술 유지(긴 회상 장면)
                    pd.addKeyFact("[" + td.name + "] " + stripped.replaceAll("§.", "")); // 중요 정보에도 기록
                    gameLogger.logAbilityResult(pd.gmDisplayName(), td.name, stripped);
                }
            })
        );
    }

    private void activateChoiceAction(Player player, PlayerData pd, TraitData td) {
        boolean auto = td.param("auto_choice", 0) == 1;
        if (auto) {
            // 자동 선택지: 입력 없이 현재 상황에 맞는 선택지를 바로 생성·제시(즉시 소진)
            applyTraitUsed(pd, td.id, state.getCurrentTurn());
            player.sendMessage("§5[" + td.name + "] 지금 상황에 맞는 선택지를 불러옵니다...");
            handleOracleAction(player, pd, td.id, "");
        } else {
            // 물어보기: 다이얼로그 인풋으로 무엇을 할지 받은 뒤 그에 맞는 선택지 제시(입력 도착 시 소진)
            dialogMan.showTextInput(player,
                Component.text("[" + td.name + "] 행동 선택"),
                Component.text("어떤 행동을 할지 적으면, 그에 맞는 선택지가 제시됩니다.\n정답을 고르면 큰 보정, 오답이면 큰 패널티."),
                "행동 의도", Component.text("선택지 받기"),
                action -> {
                    applyTraitUsed(pd, td.id, state.getCurrentTurn());
                    handleOracleAction(player, pd, td.id, action);
                });
        }
    }

    private void activateLuckRoll(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int dice  = Math.max(2, td.param("dice", 6));
        int scale = Math.max(1, td.param("scale", 10));
        int roll  = java.util.concurrent.ThreadLocalRandom.current().nextInt(dice) + 1;
        // 1 → -scale, dice → +scale 로 선형 매핑
        double t = (dice == 1) ? 1.0 : (double) (roll - 1) / (dice - 1); // 0..1
        int modifier = (int) Math.round((t * 2 - 1) * scale); // -scale..+scale
        pendingLuckModifier.put(player.getUniqueId(), modifier);
        String color = modifier > 0 ? "§a" : (modifier < 0 ? "§c" : "§7");
        player.sendMessage("§e[" + td.name + "] 주사위(d" + dice + "): §f" + roll
            + "§e  →  " + color + (modifier > 0 ? "+" : "") + modifier + " 행운 보정");
        player.sendMessage("§7다음 행동 1회에 행운 보정이 적용됩니다.");
    }

    private void activateShowProgress(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        // ★상태창(코드·실시간, GM 콜 없음)★: 하나의 만능 창이 아니라 능력 '성능(등급)'에 따라 ★단편적으로★ 보여준다.
        //   등급이 낮으면 조각 하나, 높으면 여러 조각. OVERVIEW는 캐시 텍스트(있을 때만).
        java.util.List<String> panels = statusPanelsOf(td);
        // OVERVIEW 패널을 쓰는 능력만 개요 캐시를 준비한다(FULL=시작 1회, NOW=국면 바뀔 때 1회. 값싼 Haiku·중복 방지).
        //   첫 사용 땐 아직 생성 중일 수 있어 다음 사용부터 표시된다. 진행도·동료 등 코드 패널은 즉시.
        if (panels.contains("overview_full")) ensureOverviewFull();
        if (panels.contains("overview_now"))  ensureOverviewNow();
        String panel = buildStatusPanel(pd, panels);
        player.sendMessage("§b[" + td.name + "]");
        if (panel.isBlank()) { player.sendMessage("§8 (지금은 잡히는 정보가 없다.)"); return; }
        for (String line : panel.split("\n")) if (!line.isEmpty()) player.sendMessage(line);
        gameLogger.logAbilityResult(pd.gmDisplayName(), td.name, oneLine(panel));
    }

    /** 능력 성능(등급)에 따라 보여줄 상태창 패널을 ★단편적으로★ 고른다. effectParams "panels"(CSV)가 풀,
     *  없으면 기본 우선순위. 등급이 낮을수록 조각 수를 줄인다(C/D 이하=1, B=2, A=3, S=전부). */
    private java.util.List<String> statusPanelsOf(TraitData td) {
        java.util.List<String> pool = new java.util.ArrayList<>();
        Object raw = (td.effectParams == null) ? null : td.effectParams.get("panels");
        if (raw != null && !String.valueOf(raw).isBlank())
            for (String s : String.valueOf(raw).split("[,/ ]+")) { String k = s.trim().toLowerCase(); if (!k.isEmpty()) pool.add(k); }
        if (pool.isEmpty())
            pool = new java.util.ArrayList<>(java.util.Arrays.asList("progress", "ally", "recent", "start", "overview_now", "overview_full"));
        int cap = switch (td.grade == null ? "" : td.grade) {
            case "S" -> 6; case "A" -> 3; case "B" -> 2; default -> 1;
        };
        return pool.size() > cap ? new java.util.ArrayList<>(pool.subList(0, cap)) : pool;
    }

    /** 요청된 패널만 조립해 렌더(코드 결정적). OVERVIEW/RECENT는 캐시 텍스트가 있을 때만. */
    private String buildStatusPanel(PlayerData pd, java.util.List<String> panels) {
        StringBuilder sb = new StringBuilder();
        boolean overviewShown = false; // ★시나리오 개요는 전체/단편 중 '하나만'★
        for (String p : panels) {
            switch (p) {
                case "start"    -> sb.append(panelStart());
                case "ally"     -> sb.append(panelAlly(pd));
                case "progress" -> sb.append(panelProgress());
                case "recent"        -> { String rk = state.getLastFiredEventLabel(); if (rk != null && !rk.isBlank()) sb.append("§7▪ 최근: §f").append(oneLineTrim(rk, 46)).append("\n"); }
                case "overview_full" -> { if (!overviewShown && !scenarioOverviewFull.isBlank()) { sb.append("§7▪ 개요: §f").append(scenarioOverviewFull).append("\n"); overviewShown = true; } }
                case "overview_now"  -> { if (!overviewShown && !scenarioOverviewNow.isBlank())  { sb.append("§7▪ 지금: §f").append(scenarioOverviewNow).append("\n"); overviewShown = true; } }
                default -> {}
            }
        }
        return sb.toString();
    }

    private String panelStart() {
        String era = "";
        JsonObject g = state.getGdamData();
        if (g != null && g.has("constraints") && g.get("constraints").isJsonObject())
            era = getStr(g.getAsJsonObject("constraints"), "era");
        String time = state.getCurrentTimeString();
        StringBuilder sb = new StringBuilder("§7▪ 배경: §f").append(era.isBlank() ? "?" : era);
        if (time != null && !time.isBlank()) sb.append(" · ").append(time);
        return sb.append("\n").toString();
    }

    private String panelAlly(PlayerData self) {
        StringBuilder sb = new StringBuilder("§7▪ 동료:\n");
        boolean any = false;
        for (PlayerData op : state.getAllPlayers()) {
            if (self != null && op.uuid.equals(self.uuid)) continue;
            any = true;
            String nm = op.gmDisplayName();
            if (!spawnedPlayers.contains(op.uuid)) { sb.append("§8   · ").append(nm).append(" — 아직 등장 전\n"); continue; }
            String stat = op.isDead ? "§c사망" : ("puppet".equals(op.status) ? "§d조종당함" : "§a생존");
            String loc = (op.zone == null || op.zone.isBlank()) ? "위치 미상" : zoneDisplayName(op.zone);
            sb.append("§7   · §f").append(nm).append(" §7[").append(stat).append("§7] ").append(loc).append("\n");
        }
        return any ? sb.toString() : "";
    }

    private String panelProgress() {
        int pct = scenarioProgressPercent();
        int filled = Math.round(pct / 12.5f);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 8; i++) bar.append(i < filled ? "▓" : "░");
        return "§7▪ 진행: §f" + bar + " " + pct + "%\n";
    }

    /** 통합 진행도 % — 위협(단계)+전개(발화 사건) 반반. 일상 파트는 0(사건 전). */
    private int scenarioProgressPercent() {
        if (state.isDailyPhase()) return 0;
        int maxStage = Math.max(1, state.getMaxStage());
        double threat = Math.min(1.0, state.getTimelineStage() / (double) maxStage);
        double devel  = Math.min(1.0, state.getFiredEventCount() / (double) maxStage);
        return (int) Math.round((threat + devel) / 2.0 * 100);
    }

    /** 시나리오가 지금 어디쯤 와 있는지 대략적 '국면' 표현(정확한 %는 show_progress 상태창 담당 — 여기선 영화 줄거리 톤). */
    private String scenarioProgressDescriptor() {
        if (state.isDailyPhase()) return "아직 사건이 본격화되기 전 — 잔잔한 일상의 표면";
        int pct = scenarioProgressPercent();
        if (pct <= 20) return "이야기의 도입부 — 막 어긋나기 시작한 참";
        if (pct <= 45) return "상황이 조여드는 전개부";
        if (pct <= 70) return "위기가 정점으로 치닫는 중반~후반";
        if (pct <= 90) return "파국 직전의 절정";
        return "결말이 코앞 — 마지막 국면";
    }

    /** 시나리오 전체 개요(영화 예고편·스포일러 금지)를 1회 생성해 캐시한다(비어 있을 때만, 값싼 Haiku·중복 방지). */
    private void ensureOverviewFull() {
        if (!scenarioOverviewFull.isBlank() || overviewFullPending) return;
        JsonObject gdam = state.getGdamData();
        if (gdam == null) return;
        overviewFullPending = true;
        StringBuilder src = new StringBuilder();
        if (!getStr(gdam, "scale").isBlank()) src.append("사건 규모: ").append(getStr(gdam, "scale")).append("\n");
        if (gdam.has("constraints") && gdam.get("constraints").isJsonObject()) {
            String era = getStr(gdam.getAsJsonObject("constraints"), "era");
            if (!era.isBlank()) src.append("시대·배경: ").append(era).append("\n");
        }
        if (gdam.has("entity") && gdam.get("entity").isJsonObject()) {
            String type = getStr(gdam.getAsJsonObject("entity"), "type");
            if (!type.isBlank()) src.append("(참고) 존재 유형: ").append(type).append("\n");
        }
        if (src.length() == 0) src.append("(재료가 흐릿하다 — 분위기 위주로.)\n");
        String system = GAME_FICTION_FRAME
            + "너는 괴담 TRPG의 '시나리오 개요(예고편)'를 쓴다. ★영화 예고편처럼★ 큰 줄기와 분위기만 전하라.\n"
            + "- ★스포일러 절대 금지★: 정답·해결법·정체·반전은 담지 마라.\n"
            + "- 1~2문장으로 짧게. 배경·상황·긴장의 결만.\n"
            + "- 마크다운·머리표·메타 설명 없이 서술만.\n";
        String prompt = "## 시나리오 재료(아래에서 분위기만 추출, 스포일러 금지)\n" + src
            + "\n위 재료로 '이 이야기가 대략 어떤 줄거리인지' 스포일러 없는 예고편 개요를 1~2문장으로 써라.";
        ai.callAssistant(system, prompt).whenComplete((raw, ex) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String t = oneLine(raw);
                if (!t.isBlank()) scenarioOverviewFull = t;
                overviewFullPending = false;
            }));
    }

    /** 지금 국면(눈앞의 시나리오) 요약을 생성해 캐시한다 — 단계(timelineStage)가 바뀌면 갱신. 스포일러 금지. */
    private void ensureOverviewNow() {
        if (state.isDailyPhase()) return; // 일상 파트엔 '지금 국면' 없음
        int stage = state.getTimelineStage();
        if (overviewNowStage == stage && !scenarioOverviewNow.isBlank()) return; // 이미 이 국면 것이 있음
        if (overviewNowPending) return;
        JsonObject gdam = state.getGdamData();
        if (gdam == null) return;
        overviewNowPending = true;
        final int genStage = stage;
        StringBuilder src = new StringBuilder();
        src.append("현재 국면: ").append(scenarioProgressDescriptor()).append("\n");
        String recent = state.getLastFiredEventLabel();
        if (recent != null && !recent.isBlank()) src.append("최근 사건: ").append(recent).append("\n");
        if (gdam.has("entity") && gdam.get("entity").isJsonObject()) {
            String type = getStr(gdam.getAsJsonObject("entity"), "type");
            if (!type.isBlank()) src.append("(참고) 존재 유형: ").append(type).append("\n");
        }
        String system = GAME_FICTION_FRAME
            + "너는 괴담 TRPG의 '지금 이 국면 요약'을 쓴다. 눈앞의 상황이 어떤 국면인지 ★영화 한 장면처럼★ 짧게 전하라.\n"
            + "- ★스포일러 절대 금지★: 정답·정체·해결법은 담지 마라.\n"
            + "- 1문장(길어도 2문장). 지금의 분위기·긴박도만.\n"
            + "- 마크다운·메타 없이 서술만.\n";
        String prompt = "## 지금 상황 재료(스포일러 금지)\n" + src
            + "\n위 재료로 '지금 눈앞의 국면'을 1문장으로 써라.";
        ai.callAssistant(system, prompt).whenComplete((raw, ex) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String t = oneLine(raw);
                if (!t.isBlank()) { scenarioOverviewNow = t; overviewNowStage = genStage; }
                overviewNowPending = false;
            }));
    }

    /** AI 결과를 한 줄로(개행·중복 공백 접기, 색코드·마크다운 머리표 제거). */
    private static String oneLine(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("```", "").replaceAll("(?m)^#+\\s*", "")
                  .replace("\n", " ").replaceAll("§.", "").replaceAll("\\s+", " ").trim();
    }

    /** 한 줄 + 길이 제한(넘치면 …). 상태창 '최근' 패널용. */
    private static String oneLineTrim(String s, int max) {
        String t = oneLine(s);
        return t.length() <= max ? t : t.substring(0, Math.max(0, max)) + "…";
    }

    /** 시나리오 개요 캐시 초기화 — 새 스테이지/세션/로드 시 호출(다음 사용 때 현재 시나리오로 재생성). */
    private void resetOverviewCache() {
        scenarioOverviewFull = "";
        scenarioOverviewNow  = "";
        overviewNowStage     = -999;
        overviewFullPending  = false;
        overviewNowPending   = false;
    }

    private void activateGmDirective(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        String charDisplay = pd.gmDisplayName();
        String directive = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "이 특성의 효과를 사건 전개에 자연스럽게 반영하라.";
        // ★통신형 directive★(효과가 '방송/전원 연락'류) — GM 서술을 생성하지 않고,
        //   ★이번 턴 통신 제한(두절·기기 부재)을 풀어★ 플레이어가 직접 @전체/@이름/@ 로 전하게 한다(GM 콜 대신 주입 1회).
        //   사용 사실만 GM에 주입하고, 효과 내용이 '은밀/도청 불가'류면 괴담이 이 통신을 감지하지 못한다.
        if (containsAny(directive, "방송", "전원", "모든 아군", "모두에게", "전 직원", "전체에게",
                "전체 경고", "전체 공지", "연락", "통신", "무전", "교신")) {
            boolean stealth = containsAny(directive + " " + td.name,
                "은밀", "몰래", "숨", "은폐", "도청", "감청", "들키", "발각", "안 들", "안들",
                "비밀", "암호", "조용", "안전", "포착되지", "감지되지", "감지 안", "감지불가", "추적 불");
            commBypassTurn.put(pd.uuid, state.getCurrentTurn());   // 이번 턴 통신 제한 무시(턴 넘어가면 자동 만료)
            commBypassStealth.put(pd.uuid, stealth);
            player.sendMessage("§b[" + td.name + "] §f이번 턴 통신 제한이 풀렸습니다 — 지금 §e@전체§f · §e@이름§f · §e@ 메시지§f로 전하세요."
                + (stealth ? " §8(괴담이 감지하지 못하는 은밀 통신)" : ""));
            ai.injectGmSystem("[통신 개방 능력] " + charDisplay + "이(가) '" + td.name
                + "' 능력으로 통신 제한을 뚫고 아군에게 연락 수단을 연다"
                + (stealth
                    ? " — ★이 통신은 은밀해 괴담이 감지하지 못한다(통신 유인·추적·강화 반응 금지).★"
                    : " — 통신 자체는 정상 규칙대로 괴담이 감지할 수 있다.")
                + " 실제 전달 내용은 시스템이 처리하니 중복 서술 말고 정황·반응만 다뤄라.");
            return;
        }
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + charDisplay
            + "이(가) '" + td.name + "' 특성을 발동했다. GM 지시: " + directive;
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void activateAreaScan(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        int scope = td.param("scope", 2);
        String scopeStr = switch (scope) {
            case 3  -> "건물 전체 광역 탐색";
            case 2  -> "인접 구역·층 탐색";
            default -> "현재 위치 정밀 탐색";
        };
        int remaining = uses - td.usedThisStage;
        // 채팅 입력 대신 다이얼로그 입력칸으로 탐색 목표를 받는다.
        Component scanBody = Component.text(
            "탐색 범위: " + scopeStr + "\n남은 횟수: " + remaining + "회\n\n"
            + "아래 칸에 탐색 목표를 입력하세요.\n예: 수상한 냄새 / 숨겨진 출구 / 다른 사람의 흔적");
        dialogMan.showTextInput(player,
            Component.text("[" + td.name + "] 탐색"),
            scanBody, "탐색 목표",
            Component.text("🔍 탐색 시작"),
            target -> {
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                handleScanObservation(player, pd, td.id, target);
            });
    }

    private void activateSacrifice(Player player, PlayerData pd, TraitData td) {
        int cost    = td.param("cost", 2);
        boolean useSan = td.param("use_san", 0) == 1;
        String resource = useSan ? "정신력" : "체력";
        if (useSan) {
            pd.san[0] = Math.max(0, pd.san[0] - cost);
        } else {
            pd.hp[0]  = Math.max(0, pd.hp[0] - cost);
        }
        updateAllScoreboards();
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int scale = td.param("scale", 2);
        String scaleStr = switch (scale) {
            case 3  -> "강력한";
            case 1  -> "미약한";
            default -> "상당한";
        };
        player.sendMessage("§c[" + td.name + "] " + resource + " " + cost + "을(를) 소모합니다.");
        String charDisplay = pd.gmDisplayName();
        String benefit = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "이 희생의 효과를 이야기에 반영하라.";
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + charDisplay
            + "이(가) " + resource + " " + cost + "을(를) 소모해 힘을 얻었다(" + scaleStr + " 효과). "
            + "GM 지시: " + benefit + " 이야기에 자연스럽게 반영하라.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    /** 아군 연락처 즉시 입수 — 플레이어 아군 우선, 부족하면 조력(소통 가능) NPC. */
    private void activateGetContacts(Player player, PlayerData pd, TraitData td) {
        int count = td.param("count", 1);
        int gained = 0;
        // 1) 아직 모르는 플레이어 아군
        for (PlayerData op : state.getAllPlayers()) {
            if (gained >= count) break;
            if (op.uuid.equals(pd.uuid) || op.isDead) continue;
            if (pd.knownContacts.contains(op.uuid)) continue;
            exchangeContacts(pd, op); // 양방향 교환 + 통신기기 갱신 + 알림
            gained++;
        }
        // 2) 부족하면 조력 성향(소통 가능) NPC
        if (gained < count) {
            for (JsonObject npc : getCriticalNpcs()) {
                if (gained >= count) break;
                String id = getStr(npc, "id");
                if (id.isEmpty() || pd.everKnownNpcContacts.contains(id)) continue;
                if (!isNpcCommunicable(npc)) continue;
                pd.everKnownNpcContacts.add(id);
                gained++;
            }
            refreshCommItems(pd);
        }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        if (gained == 0) {
            player.sendMessage("§7[" + td.name + "] 새로 입수할 연락처가 없습니다.");
        } else {
            player.sendMessage("§a[" + td.name + "] 연락처 " + gained + "건을 즉시 입수했습니다.");
            announceKnownContacts(player, pd);
        }
    }

    /** 적대·위장 의심 NPC인지(역할유형·숨은역할 키워드 기반). 조력/아군 판정의 반대. */
    private boolean isHostileNpc(JsonObject npc) {
        String h = (getStr(npc, "role_type") + " " + getStr(npc, "true_role")).toLowerCase();
        return h.contains("위장") || h.contains("도플") || h.contains("변신") || h.contains("포식")
            || h.contains("유혹") || h.contains("적대") || h.contains("함정") || h.contains("흉내")
            || h.contains("가짜") || h.contains("괴담");
    }

    /** 확정 조우 — 조력(소통 가능·비적대) NPC 1명을 자신의 구역으로 끌어와 등장시킨다. */
    private void activateForceEncounter(Player player, PlayerData pd, TraitData td) {
        JsonObject ally = null;
        for (JsonObject npc : getCriticalNpcs()) {
            if (!isNpcCommunicable(npc) || isHostileNpc(npc)) continue;
            ally = npc; break;
        }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        String who = pd.gmDisplayName();
        String allyName = (ally != null) ? getStr(ally, "name") : "조력자";
        if (ally != null && pd.zone != null && !pd.zone.isEmpty())
            npcZones.put(getStr(ally, "id"), pd.zone); // 확정: 실제로 같은 구역에 배치
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + who + "이(가) 인연을 끌어당겨, "
            + allyName + "이(가) 마침 " + who + "가 있는 곳에 나타난다(조우). 괴담 본체는 해당 없음. "
            + "이 조우를 다음 서술에 자연스럽게 반영하고, 작은 도움이나 정보를 주게 하라.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    /** 미끼 전환 — 괴담/위협의 다음 표적을 다른 대상으로 돌린다. */
    private void activateDecoy(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        String who = pd.gmDisplayName();
        String target = (td.effect != null && !td.effect.isBlank()) ? td.effect : "주변의 다른 대상(미끼)";
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + who + "이(가) 괴담의 이목을 돌린다 — "
            + "괴담/위협의 ★다음 표적·추적★을 " + target + "(으)로 돌려라. "
            + who + "은(는) 다음 1~2턴 그 위협의 직접 표적에서 벗어난다. 자연스럽게 서술하라.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    /** 일방 전언 — 지정 아군 1명에게 거리·연락처·통신차단 무관하게 일방 전달(답신 불가, 소리 아님, 턴 소모 없음). */
    private void activateOneWayCall(Player player, PlayerData pd, TraitData td) {
        dialogMan.showTextInput(player,
            net.kyori.adventure.text.Component.text("[" + td.name + "]"),
            net.kyori.adventure.text.Component.text("지정한 아군 1명에게 일방적으로 전합니다(거리·연락처·통신차단 무관, 답신 불가)."),
            "받는 사람 이름 + 전할 말 (예: 김철수 지금 도망쳐)",
            net.kyori.adventure.text.Component.text("전송"),
            input -> {
                if (input == null || input.isBlank()) return;
                String s = input.trim();
                int sp = s.indexOf(' ');
                if (sp < 0) { player.sendMessage("§c형식: 이름 메시지 (예: 김철수 지금 도망쳐)"); return; }
                String name = s.substring(0, sp).trim();
                String msg  = s.substring(sp + 1).trim();
                PlayerData target = findAnyByName(name);
                if (target == null || target.uuid.equals(pd.uuid)) {
                    player.sendMessage("§c'" + name + "' — 보낼 아군을 찾을 수 없습니다.");
                    return;
                }
                Player tp = Bukkit.getPlayer(target.uuid);
                if (tp != null && tp.isOnline())
                    tp.sendMessage("§d[전언 — " + pd.gmDisplayName() + "] §f" + msg);
                player.sendMessage("§7[" + td.name + "] " + target.gmDisplayName() + "에게 전했습니다. §8(턴 소모 없음)");
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
            });
    }

    /** .gdam zones[]의 모든 zone_id 목록(무작위 이동용). */
    private List<String> allZoneIdsFromGdam() {
        List<String> out = new ArrayList<>();
        JsonObject g = state.getGdamData();
        if (g != null && g.has("zones") && g.get("zones").isJsonArray())
            for (JsonElement el : g.getAsJsonArray("zones"))
                if (el.isJsonObject()) { String z = getStr(el.getAsJsonObject(), "zone_id"); if (!z.isBlank()) out.add(z); }
        return out;
    }

    /** 순간이동 — 무작위 구역 / 아군 위치 / NPC 위치로(안 가본 곳도 가능). 다이얼로그로 대상 입력. */
    private void activateTeleport(Player player, PlayerData pd, TraitData td) {
        dialogMan.showTextInput(player,
            net.kyori.adventure.text.Component.text("[" + td.name + "]"),
            net.kyori.adventure.text.Component.text("이동할 곳: 아군/NPC 이름, 또는 '무작위'. (안 가본 곳도 갈 수 있다)"),
            "대상 이름 또는 무작위", net.kyori.adventure.text.Component.text("이동"),
            input -> {
                if (input == null || input.isBlank()) return;
                String t = input.trim();
                String destZone = null;
                if (t.contains("무작위") || t.equalsIgnoreCase("random")) {
                    List<String> zones = allZoneIdsFromGdam();
                    zones.remove(pd.zone);
                    if (!zones.isEmpty()) destZone = zones.get(new java.util.Random().nextInt(zones.size()));
                } else {
                    PlayerData ap = findAnyByName(t);
                    if (ap != null && ap.zone != null && !ap.zone.isEmpty()) destZone = ap.zone;
                    else {
                        JsonObject npc = findNpcByName(t);
                        if (npc != null) destZone = npcZones.getOrDefault(getStr(npc, "id"), getStr(npc, "zone"));
                    }
                }
                if (destZone == null || destZone.isBlank()) { player.sendMessage("§c이동할 위치를 찾지 못했습니다."); return; }
                pd.zone = destZone; pd.visitedZones.add(destZone);
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                Player mp = Bukkit.getPlayer(pd.uuid);
                if (mp != null && mp.isOnline()) mapMan.giveStartMap(mp);
                ai.injectGmSystem("[순간이동] " + pd.gmDisplayName() + "이(가) " + zoneDisplayName(destZone)
                    + "(으)로 순간이동했다. 다음 서술에 자연스럽게 반영하라.");
                player.sendMessage("§7[" + td.name + "] " + zoneDisplayName(destZone) + "(으)로 이동했습니다.");
                updateAllScoreboards();
            });
    }

    /** 소집 — 흩어진 아군을 자신의 현재 위치로 불러모은다. */
    private void activateRally(Player player, PlayerData pd, TraitData td) {
        if (pd.zone == null || pd.zone.isEmpty()) { player.sendMessage("§c현재 위치가 불명이라 소집할 수 없습니다."); return; }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int moved = 0;
        for (PlayerData op : state.getAllPlayers()) {
            if (op.uuid.equals(pd.uuid) || op.isDead || !spawnedPlayers.contains(op.uuid)) continue;
            if (pd.zone.equals(op.zone)) continue;
            op.zone = pd.zone; op.visitedZones.add(pd.zone);
            Player tp = Bukkit.getPlayer(op.uuid);
            if (tp != null && tp.isOnline()) {
                tp.sendMessage("§d[" + td.name + "] " + pd.gmDisplayName() + "의 부름에 이끌려 "
                    + zoneDisplayName(pd.zone) + "(으)로 이동했습니다.");
                mapMan.giveStartMap(tp);
            }
            moved++;
        }
        if (moved == 0) { player.sendMessage("§7[" + td.name + "] 소집할 아군이 없습니다."); return; }
        ai.injectGmSystem("[소집] " + pd.gmDisplayName() + "이(가) 동료 " + moved + "명을 "
            + zoneDisplayName(pd.zone) + "(으)로 불러모았다. 서술에 반영하라.");
        player.sendMessage("§a[" + td.name + "] 동료 " + moved + "명을 불러모았습니다.");
        updateAllScoreboards();
    }

    /** 흔적 지우기 — N턴간 괴담의 감지(perception 전부)에서 벗어나 표적·추적에서 제외. GM 컨텍스트에 지속 주입. */
    private void activateEvadeSense(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int turns = Math.max(1, td.param("turns", 2));
        ai.injectGmSystem("[흔적 지우기] " + pd.gmDisplayName() + "이(가) 약 " + turns
            + "턴 동안 괴담의 감지(perception 양식 전부 — 청각·시각·통신·전지 등)에서 벗어난다. "
            + "그동안 괴담은 이 플레이어를 직접 표적·추적하지 못한다(다른 플레이어·환경은 정상). "
            + "약 " + turns + "턴 후 다시 인지될 수 있다. 자연스럽게 반영하라.");
        player.sendMessage("§7[" + td.name + "] 약 " + turns + "턴 동안 괴담의 감지에서 벗어납니다.");
    }

    /** 사후 전언(death_relay) — 사망 시, 자신이 '밝혀낸 사실(keyFacts)'을 가까운 아군 1명에게 전달한다(패시브 자동). */
    private void fireDeathRelay(PlayerData dead) {
        if (dead == null) return;
        if (dead.traits.stream().noneMatch(t -> "death_relay".equals(t.effectType))) return;
        List<String> facts;
        synchronized (dead.keyFacts) { facts = new ArrayList<>(dead.keyFacts); }
        if (facts.isEmpty()) return;
        if (facts.size() > 5) facts = facts.subList(facts.size() - 5, facts.size()); // 최근 5건만
        PlayerData recip = null;
        for (PlayerData op : state.getAllPlayers()) {
            if (op.uuid.equals(dead.uuid) || op.isDead || !spawnedPlayers.contains(op.uuid)) continue;
            if (dead.zone != null && dead.zone.equals(op.zone)) { recip = op; break; } // 같은 zone 우선
            if (recip == null) recip = op;
        }
        if (recip == null) return;
        String prefix = "[" + dead.gmDisplayName() + "의 유언]";
        Player rp = Bukkit.getPlayer(recip.uuid);
        for (String f : facts) recip.addKeyFact(prefix + " " + f.replaceAll("§.", ""));
        if (rp != null && rp.isOnline()) {
            rp.sendMessage("§d" + prefix + " 마지막 순간, 그가 알아낸 사실이 당신에게 전해집니다:");
            for (String f : facts) rp.sendMessage("§7  · " + f.replaceAll("§.", ""));
        }
    }

    /**
     * 동물 소생(revive_as_animal) — 사망 시 1회, 죽음 대신 주변 동물로 의식이 옮겨간다.
     * 완전 사망을 취소하고 '동물 형태'로 전환한다(능력·아이템·통신 불가, 제한 행동만 가능, 다시 피해 시 진짜 소멸).
     * @return 소생 발동(동물 전환) 여부. false면 트레이트 없음 — 호출자가 정상 사망 처리를 이어가야 한다.
     */
    private boolean fireAnimalRevival(PlayerData dead) {
        if (dead == null) return false;
        TraitData t = dead.traits.stream()
            .filter(x -> "revive_as_animal".equals(x.effectType) && x.usedThisStage == 0).findFirst().orElse(null);
        if (t == null) return false;
        t.usedThisStage++; // 1회 소진
        // 죽음 대신 동물 형태로 — 완전 사망 취소
        dead.isDead = false;
        dead.impersonated = false;
        dead.status = "animal";
        dead.hp[0] = 1;               // 매우 취약: 다시 피해를 받으면 진짜 소멸
        dead.faintTurnsRemaining = 0;
        dead.puppetRecoveryTurns = 0;
        dead.turnState = PlayerData.TurnState.IDLE; // 행동 상태 초기화(소생 직후 턴이 막히지 않게)
        animalForm.add(dead.uuid);
        Player p = Bukkit.getPlayer(dead.uuid);
        if (p != null && p.isOnline())
            p.sendMessage("§2[" + t.name + "] 당신의 의식이 주변 동물에게로 옮겨갑니다 — 말도 능력도 쓸 수 없지만, 아직 끝은 아닙니다. §8(정찰·몸짓 같은 단순 행동만 가능)");
        ai.injectGmSystem("[동물 소생] " + commDisplayName(dead) + "은(는) 죽는 대신 주변의 한 동물로 의식이 옮겨갔다. "
            + "이후 그를 '그 동물'로 서술하라 — 정찰·작은 방해·몸짓으로 단서를 흘릴 수 있다(능력·아이템·대화는 불가). "
            + "괴담은 이것이 그 사람인 줄 모른다(평범한 동물로 인지). 동물이 위험에 휘말리거나 공격받으면 진짜 소멸한다.");
        return true;
    }

    /** 관조자의 눈(observer_sight) — '무대 뒤(연출자)의 현재 사고'를 엿본다. ★사용한 순간 1회만★(지속·매 턴 자동 없음, 전체 각본·정답 제외). */
    private void activateObserverSight(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        // ★관조자의 눈은 '사용한 순간 1회'만★ — 지속·매 턴 자동 재발동 없음(GM 콜이 무인 누적되는 것을 차단).
        fireObserverGlimpse(player, pd, td.name, td.grade);
        player.sendMessage("§5[" + td.name + "] 무대 뒤의 현재 사고를 엿봅니다...");
    }

    /** 거래(pact) — 괴담과 1회 거래(대가↔양보). GM이 판정·서술. */
    private void activatePact(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        String who = pd.gmDisplayName();
        String deal = (td.effect != null && !td.effect.isBlank()) ? td.effect : "대가를 치르고 양보 하나를 청한다";
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + who + "이(가) 괴담과 1회 거래를 시도한다 — " + deal + ". "
            + "괴담의 본성·동기에 맞게 판정하라: 합당한 대가(체력·정신력·단서·시간 등)를 요구하고, 성사 시 양보 1개(피해 회피·정보·길 열림 등)를 준다. "
            + "괴담에게 불리하면 거절·역제안·기만할 수 있다. 정답·해결법을 통째로 주지는 마라. 결과를 서술하라.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    /** 과거 편집(past_edit) — 자신의 과거 행동 1개를 개찬해 인과를 바꾼다(정답 날조 불가). 다이얼로그 입력. */
    private void activatePastEdit(Player player, PlayerData pd, TraitData td) {
        dialogMan.showTextInput(player,
            net.kyori.adventure.text.Component.text("[" + td.name + "]"),
            net.kyori.adventure.text.Component.text("당신이 한 과거 행동 1개를 다른 것으로 바꿉니다(인과가 바뀜)."),
            "예: '문을 잠갔다'를 '문을 열어뒀다'로", net.kyori.adventure.text.Component.text("개찬"),
            input -> {
                if (input == null || input.isBlank()) return;
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                String gmMsg = "[시스템 특성: " + td.name + " 발동] " + pd.gmDisplayName() + "이(가) 과거를 개찬한다: \"" + input.trim() + "\". "
                    + "이 플레이어의 ★과거 행동 1개★를 그 내용으로 바꾸고, 바뀐 인과를 현재에 반영해 서술하라. "
                    + "단 ★정답 날조 금지★('이미 괴담을 해치웠다' 등 불가) — 개연성 있는 범위만. 무리한 개찬은 부분 반영·역풍으로 처리.";
                turnMan.handleAction(player, gmMsg, gmSystemPrompt);
                player.sendMessage("§7[" + td.name + "] 과거를 개찬했습니다...");
            });
    }

    /** 괴담 변신(gdam_morph) — N턴간 무작위 괴담으로 변신(조작 불가, GM 구동, 피아식별 없음). */
    private void activateGdamMorph(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int turns = Math.max(1, td.param("turns", 2));
        // ★고정 변신★: effect에 특정 괴담 이름이 적혀 있으면 그 괴담으로 변신(예: "빨간 마스크"), 없으면 무작위 유형.
        boolean fixed = td.effect != null && !td.effect.isBlank();
        String kind;
        if (fixed) {
            kind = td.effect.trim();
        } else {
            String[] kinds = {"규칙형 괴이", "포식형 괴물", "유혹형 정령", "도플갱어형 존재", "그림자형 괴담", "기괴한 SCP형 개체"};
            kind = kinds[new java.util.Random().nextInt(kinds.length)];
        }
        morphTurns.put(pd.uuid, turns); // 변신 지속 턴 — 그동안 플레이어 입력 차단(GM이 구동)
        ai.injectGmSystem("[괴담 변신] " + pd.gmDisplayName() + "이(가) 약 " + turns + "턴간 '" + kind + "'(으)로 변신했다"
            + (fixed ? "(★이 괴담으로 고정 변신 — 그 괴담의 알려진 본성·행태 그대로 구동★)." : ".") + " "
            + "그 본성대로 ★GM이 자율 구동★하라 — 플레이어의 통제를 벗어나며(조작 불가), 피아를 가리지 않아 아군도 위험할 수 있다. "
            + turns + "턴 후 원래 모습으로 돌아온다. 박력 있게 서술하되 즉시 전멸 강요는 피하라.");
        player.sendMessage("§5[" + td.name + "] 당신은 '" + kind + "'(으)로 변신했습니다 — 약 " + turns + "턴간 통제할 수 없습니다(GM이 구동).");
    }

    /** 빙의(possess_npc) — 대상 NPC(괴담 본체 불가)에 빙의: NPC 지식을 기록에 덤프 + GM 지시(본체 무방비·사망 연동·복귀 조건). */
    private void activatePossessNpc(Player player, PlayerData pd, TraitData td) {
        dialogMan.showTextInput(player,
            net.kyori.adventure.text.Component.text("[" + td.name + "]"),
            net.kyori.adventure.text.Component.text("빙의할 NPC 이름(적대 NPC도 가능, 괴담 본체는 불가). 본체는 무방비로 남고, 본체가 죽으면 당신도 죽습니다."),
            "NPC 이름", net.kyori.adventure.text.Component.text("빙의"),
            input -> {
                if (input == null || input.isBlank()) return;
                JsonObject npc = findNpcByName(input.trim());
                if (npc == null) { player.sendMessage("§c'" + input.trim() + "' — 빙의할 NPC를 찾지 못했습니다(주요 NPC만 가능)."); return; }
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                String npcName = getStr(npc, "name");
                if (npc.has("knowledge") && npc.get("knowledge").isJsonArray())
                    for (JsonElement k : npc.getAsJsonArray("knowledge")) {
                        String kn = k.getAsString();
                        if (kn != null && !kn.isBlank()) pd.addKeyFact("[" + npcName + " 빙의로 앎] " + kn);
                    }
                possessingNpc.put(pd.uuid, npcName); // 빙의 상태 — 이후 행동은 NPC 몸으로 태깅, 해제/사망 시 복귀
                ai.injectGmSystem("[빙의] " + pd.gmDisplayName() + "의 의식이 " + npcName + "의 몸으로 들어갔다(본체는 무방비로 그 자리에 남는다 — 외부에 남은 약점). "
                    + "이제 이 플레이어의 모든 행동은 " + npcName + "의 몸으로 이뤄지며 그가 아는 정보를 안다. "
                    + "큰 피해를 받거나 본인이 해제를 선언하면 본체로 돌아온다. ★본체가 공격받아 죽으면 플레이어도 죽는다.★ "
                    + "괴담 본체에는 빙의할 수 없다. " + npcName + "의 입장·시야로 서술하라.");
                player.sendMessage("§5[" + td.name + "] " + npcName + "에게 빙의했습니다 — 이제 그 몸으로 행동합니다. §8(해제하려면 '빙의해제' 입력 / 본체 사망 시 사망 / 큰 피해 시 복귀)");
            });
    }

    /** 빙의 해제 — 의식이 본체로 돌아온다(스스로 해제·큰 피해·NPC 사망 등). */
    private void endPossession(Player player, PlayerData pd, String reason) {
        String npcName = possessingNpc.remove(pd.uuid);
        if (npcName == null) return;
        if (player != null && player.isOnline())
            player.sendMessage("§5[빙의 해제] 의식이 본체로 돌아왔습니다. §8(" + reason + ")");
        ai.injectGmSystem("[빙의 해제] " + commDisplayName(pd) + "의 의식이 " + npcName + "의 몸에서 빠져나와 본체로 돌아왔다(" + reason + "). 서술에 반영하라.");
    }

    private static boolean isPossessReleaseWord(String m) {
        if (m == null) return false;
        String s = m.trim().replace(" ", "").toLowerCase();
        return s.equals("빙의해제") || s.equals("빙의종료") || s.equals("빙의풀기") || s.equals("본체로돌아간다") || s.equals("본체복귀");
    }

    /** 특성 모방(mimic) — 지정 아군의 대표 특성 1개를 이번 스테이지 동안 복제해 사용(스탯 보정 제외, 능력만). */
    private void activateMimic(Player player, PlayerData pd, TraitData td) {
        dialogMan.showTextInput(player,
            net.kyori.adventure.text.Component.text("[" + td.name + "]"),
            net.kyori.adventure.text.Component.text("모방할 아군 이름을 입력하세요. 그 아군의 대표 특성 1개를 이번 스테이지 동안 빌려 씁니다."),
            "아군 이름", net.kyori.adventure.text.Component.text("모방"),
            input -> {
                if (input == null || input.isBlank()) return;
                PlayerData ally = findAnyByName(input.trim());
                if (ally == null || ally.uuid.equals(pd.uuid)) { player.sendMessage("§c'" + input.trim() + "' — 모방할 아군을 찾지 못했습니다."); return; }
                TraitData src = ally.traits.stream()
                    .filter(t -> t.effectType != null && !t.effectType.isBlank() && !t.id.equals(td.id))
                    .max(java.util.Comparator.comparingInt(t -> gradeIdx(t.grade)))
                    .orElse(ally.traits.stream().filter(t -> !t.id.equals(td.id))
                        .max(java.util.Comparator.comparingInt(t -> gradeIdx(t.grade))).orElse(null));
                if (src == null) { player.sendMessage("§c" + ally.gmDisplayName() + "에게 모방할 특성이 없습니다."); return; }
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                TraitData copy = new TraitData();
                copy.id           = "mimic_" + java.util.UUID.randomUUID().toString().substring(0, 6);
                copy.name         = "[모방] " + src.name;
                copy.grade        = src.grade;
                copy.description  = src.description;
                copy.active       = src.active;
                copy.effect       = src.effect;
                copy.effectType   = src.effectType;
                copy.effectParams = (src.effectParams == null) ? new java.util.HashMap<>() : new java.util.HashMap<>(src.effectParams);
                copy.cooldownTurns = src.cooldownTurns;
                copy.roleSpecific = true; // 이번 스테이지 한정(다음 스테이지 전환 시 정리). 스탯 보정(str_add 등)은 복제하지 않음.
                traitMan.addTrait(pd, copy);
                player.sendMessage("§a[" + td.name + "] " + ally.gmDisplayName() + "의 '" + src.name + "'을(를) 모방했습니다 — 이번 스테이지 동안 사용할 수 있습니다.");
            });
    }

    /** NPC 저장(npc_bind) — 대상 NPC를 인연으로 저장 → 다음 스테이지 시작 시 아군으로 1회 소환. */
    private void activateNpcBind(Player player, PlayerData pd, TraitData td) {
        dialogMan.showTextInput(player,
            net.kyori.adventure.text.Component.text("[" + td.name + "]"),
            net.kyori.adventure.text.Component.text("인연으로 저장할 NPC 이름을 입력하세요. 다음 스테이지에 아군으로 소환됩니다."),
            "NPC 이름", net.kyori.adventure.text.Component.text("저장"),
            input -> {
                if (input == null || input.isBlank()) return;
                JsonObject npc = findNpcByName(input.trim());
                if (npc == null) { player.sendMessage("§c'" + input.trim() + "' — 저장할 NPC를 찾지 못했습니다(주요 NPC만 가능)."); return; }
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                pd.savedNpcJson = npc.toString();
                player.sendMessage("§a[" + td.name + "] " + getStr(npc, "name") + "을(를) 인연으로 저장했습니다 — 다음 스테이지에 아군으로 함께합니다.");
            });
    }

    /** npc_bind로 저장한 NPC들을 이번 스테이지 gdam.npcs에 아군으로 주입(1회 소환 후 소진). startDailyPhase에서 호출. */
    private void injectSavedNpcs(JsonObject gdam) {
        if (gdam == null) return;
        com.google.gson.JsonArray npcs = (gdam.has("npcs") && gdam.get("npcs").isJsonArray())
            ? gdam.getAsJsonArray("npcs") : null;
        if (npcs == null) { npcs = new com.google.gson.JsonArray(); gdam.add("npcs", npcs); }
        int idx = 0;
        for (PlayerData pd : state.getAllPlayers()) {
            if (pd.savedNpcJson == null || pd.savedNpcJson.isBlank()) continue;
            try {
                JsonObject saved = com.google.gson.JsonParser.parseString(pd.savedNpcJson).getAsJsonObject();
                saved.addProperty("id", "bound_" + (idx++) + "_" + java.util.UUID.randomUUID().toString().substring(0, 4));
                saved.addProperty("critical", true);
                if (getStr(saved, "role_type").isBlank()) saved.addProperty("role_type", "조력");
                npcs.add(saved);
                ai.injectGmSystem("[인연 소환] 지난 사건의 인연 '" + getStr(saved, "name") + "'이(가) "
                    + pd.gmDisplayName() + "을(를) 따라 이 사건에도 아군으로 함께한다. 자연스럽게 등장시켜라.");
                Player p = Bukkit.getPlayer(pd.uuid);
                if (p != null && p.isOnline()) p.sendMessage("§a[인연] " + getStr(saved, "name") + "이(가) 이번에도 당신과 함께합니다.");
            } catch (Exception ignore) {}
            pd.savedNpcJson = ""; // 1회 소환 후 소진
        }
    }

    /** 괴담 파트 턴당 1회, 변화 적용 전 핵심 상태를 링버퍼에 스냅샷(시간 회귀용). */
    private void maybeCaptureRewind() {
        if (currentPhase != Phase.HORROR) return;
        int turn = state.getCurrentTurn();
        if (turn == lastRewindCaptureTurn) return;
        lastRewindCaptureTurn = turn;
        RewindSnapshot s = new RewindSnapshot(turn, ai.getGmContextSize(), state.getTimelineStage());
        for (PlayerData pd : state.getAllPlayers()) {
            s.vitals.put(pd.uuid, new int[]{pd.hp[0], pd.hp[1], pd.san[0], pd.san[1]});
            s.status.put(pd.uuid, pd.status);
            s.zone.put(pd.uuid, pd.zone == null ? "" : pd.zone);
            s.spot.put(pd.uuid, pd.spot == null ? "" : pd.spot);
            s.dead.put(pd.uuid, pd.isDead);
        }
        rewindBuffer.addLast(s);
        while (rewindBuffer.size() > REWIND_BUFFER_MAX) rewindBuffer.removeFirst();
        // 위상 이탈 무적 턴 감소(턴당 1회) — 0 이하면 해제
        phaseOutTurns.entrySet().removeIf(e -> { int t = e.getValue() - 1; if (t <= 0) return true; e.setValue(t); return false; });
        tickRestrictionStates(); // 변신·관조 지속 턴 감소(턴당 1회)
    }

    /** 턴당 1회: 괴담 변신(morph) 종료 처리 + 관조자의 눈(observer) 지속 발동·감소. maybeCaptureRewind에서만 호출. */
    private void tickRestrictionStates() {
        // 괴담 변신: 남은 턴 감소, 0이 되면 원래 모습으로 복귀 안내
        morphTurns.entrySet().removeIf(e -> {
            int t = e.getValue() - 1;
            if (t <= 0) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && p.isOnline()) p.sendMessage("§5변신이 풀려 원래 모습으로 돌아왔습니다. 다시 행동할 수 있습니다.");
                PlayerData pd = state.getPlayer(e.getKey());
                if (pd != null) ai.injectGmSystem("[변신 해제] " + commDisplayName(pd) + "이(가) 원래 모습으로 돌아왔다. 서술에 반영하라.");
                return true;
            }
            e.setValue(t); return false;
        });
        // 행동불능(능력 대가): 남은 턴 감소, 0이 되면 회복 안내(플레이어+GM)
        stunTurns.entrySet().removeIf(e -> {
            int t = e.getValue() - 1;
            if (t <= 0) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && p.isOnline()) p.sendMessage("§a행동불능에서 회복했습니다. 다시 행동할 수 있습니다.");
                PlayerData pd = state.getPlayer(e.getKey());
                if (pd != null) ai.injectGmSystem("[행동불능 해제] " + commDisplayName(pd) + "이(가) 다시 움직일 수 있게 됐다.");
                return true;
            }
            e.setValue(t); return false;
        });
        // (관조자의 눈은 '사용한 순간 1회'만 발동 — 지속형 매-턴 재발동 없음. activateObserverSight에서 직접 처리.)
    }

    /** 관조자의 눈 — '무대 뒤(연출자)의 현재 사고'를 한 번 보여준다(전체 각본·정답 제외). 등급이 낮으면 글자가 깨져 판독이 어렵다. */
    private void fireObserverGlimpse(Player player, PlayerData pd, String label, String grade) {
        String metaCtx = "## 관조자 시점(메타) 노출\n"
            + "플레이어가 '무대 뒤'를 잠깐 들여다본다. 지금 이 순간 ★연출자(GM)의 현재 사고·의도★를 1~3문장으로 보여줘라:\n"
            + "- 지금 무엇을·왜 굴리고 있는가, 곧 무엇이 닥치려 하는가, 이 존재가 지금 원하는 것.\n"
            + "- ★현재 사고에 한정★ — 전체 각본·정답·해결법·붕괴조건은 절대 통째로 노출 금지.\n"
            + "- 관조자 톤(담담한 해설). 마크다운·태그 금지. (판독 흐림은 시스템이 등급대로 처리하니 너는 또렷이 써라.)";
        int gi = gradeIdx(grade);
        ai.callGmAiOnce(gmSystemPrompt, metaCtx + "\n\n" + pd.gmDisplayName() + "이(가) 관조자의 눈으로 지금 이 순간의 '무대 뒤'를 들여다본다. 현재 사고를 보여줘.")
          .thenAccept(resp -> {
            String t = ai.stripTags(resp).trim();
            if (t.isEmpty()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                // ★가독성 = 등급★: 낮으면 글자가 깨지고(□) 뒤죽박죽 섞여 판독이 어렵고, 높으면 또렷.
                String shown = garbleByGrade(t, gi);
                player.sendMessage("§5[" + label + " — 관조] §7" + shown);
                gameLogger.logAbilityResult(pd.gmDisplayName(), label, shown);
                // 또렷이 읽힌 등급(B+)에서만 기록에 남긴다 — 판독 불가 조각은 오라클 문맥을 오염시키므로 기록하지 않는다.
                if (gi >= 4) pd.addKeyFact("[" + label + "] " + t.replaceAll("§.", ""));
                else player.sendMessage("§8 (형상이 깨져 온전히 새겨두지 못했다.)");
            });
        });
    }

    /** 관조자 가독성 처리 — 등급이 낮을수록 글자가 깨지거나(□·�) 인접 글자와 뒤바뀐다. gradeIdx: 0(F)~6(S). S는 완전 또렷. */
    private static String garbleByGrade(String text, int gradeIdx) {
        if (text == null || text.isEmpty() || gradeIdx >= 6) return text;
        double p = switch (gradeIdx) {
            case 5 -> 0.08; // A — 거의 또렷, 이따금 흐트러짐
            case 4 -> 0.16; // B
            case 3 -> 0.28; // C
            case 2 -> 0.40; // D
            case 1 -> 0.52; // E
            default -> 0.64; // F — 대부분 판독 불가
        };
        String[] glitch = {"�", "▓", "▨", "▩", "▦", "◌", "㽀", "꘡", "⿕", "畂", "※", "¤"};
        java.util.Random r = new java.util.Random();
        char[] cs = text.toCharArray();
        StringBuilder sb = new StringBuilder(cs.length + 8);
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];
            if (c == ' ' || c == '\n' || c == '\r' || c == '.' || c == ',') { sb.append(c); continue; }
            double roll = r.nextDouble();
            if (roll >= p) { sb.append(c); continue; }              // 온전
            if (roll < p * 0.55) {                                  // 글자 깨짐
                sb.append(glitch[r.nextInt(glitch.length)]);
            } else if (i + 1 < cs.length                            // 인접 글자와 뒤바꿈(뒤죽박죽)
                       && cs[i + 1] != ' ' && cs[i + 1] != '\n' && cs[i + 1] != '\r') {
                sb.append(cs[i + 1]); cs[i + 1] = c;
            } else {
                sb.append(glitch[r.nextInt(glitch.length)]);
            }
        }
        return sb.toString();
    }

    /** 시간 회귀(time_rewind) — 파티 전원의 핵심 상태(체력·정신력·상태·위치·사망)를 N턴 전으로 되돌리고 GM 기억을 그 시점으로 잘라낸다. */
    private void activateTimeRewind(Player player, PlayerData pd, TraitData td) {
        if (rewindBuffer.isEmpty()) { player.sendMessage("§c아직 되돌릴 시점이 없습니다(괴담 파트에서 몇 턴 진행 후 사용)."); return; }
        int back = Math.max(1, td.param("turns", 3));
        int targetTurn = state.getCurrentTurn() - back;
        RewindSnapshot chosen = null;
        for (RewindSnapshot s : rewindBuffer) if (s.turn <= targetTurn) chosen = s;
        if (chosen == null) chosen = rewindBuffer.peekFirst(); // 그만큼 못 돌아가면 가장 먼 시점
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        final RewindSnapshot snap = chosen;
        for (PlayerData op : state.getAllPlayers()) {
            int[] v = snap.vitals.get(op.uuid);
            if (v != null) { op.hp[0] = v[0]; op.hp[1] = v[1]; op.san[0] = v[2]; op.san[1] = v[3]; }
            String st = snap.status.get(op.uuid); if (st != null) op.status = st;
            String z  = snap.zone.get(op.uuid);   if (z  != null) op.zone   = z;
            String sp = snap.spot.get(op.uuid);   if (sp != null) op.spot   = sp;
            Boolean d = snap.dead.get(op.uuid);   if (d  != null) op.isDead = d;
        }
        ai.truncateGmContext(snap.gmMark); // GM 기억을 그 시점으로(무상태 LLM이라 컨텍스트=기억)
        while (!rewindBuffer.isEmpty() && rewindBuffer.peekLast().turn >= snap.turn) rewindBuffer.removeLast();
        lastRewindCaptureTurn = -1;
        ai.injectGmSystem("[시간 회귀] 파티 전원의 시간이 약 " + back + "턴 전(" + snap.turn
            + "턴 시점)으로 되감겼다. 그 사이 일어난 사건은 일어나지 않은 것이 되며, 그 시점부터 다시 진행하라. "
            + "(플레이어들은 되감기 전 겪은 일을 어렴풋이 기억할 수 있다.)");
        updateAllScoreboards();
        broadcast("§b§l[시간 회귀] §f시간이 약 " + back + "턴 전으로 되감겼습니다. (" + pd.gmDisplayName() + " 발동)");
    }

    /** 위상 이탈(phase_out) — N턴간 간섭·피해를 받지 않고 흐름에서 비켜선다. 종료 시 극적 탈출 가능. */
    private void activatePhaseOut(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int turns = Math.max(1, td.param("turns", 2));
        phaseOutTurns.put(pd.uuid, turns); // 무적: N턴간 피해 무효(applyStateUpdate에서 음수 변화 차단)
        ai.injectGmSystem("[위상 이탈] " + pd.gmDisplayName() + "이(가) 약 " + turns + "턴간 위상에서 이탈한다 — "
            + "그동안 어떤 간섭·피해도 받지 않고(괴담·환경 무효) 사건 흐름에서 비켜선다. "
            + turns + "턴 후 복귀하며, 복귀 시 극적인 탈출(건물 폭파·붕괴 유발 등)을 시도할 수 있다. 자연스럽게 서술하라.");
        player.sendMessage("§b[" + td.name + "] 약 " + turns + "턴간 위상 이탈 — 간섭을 받지 않습니다. 종료 시 탈출 가능.");
    }

    /** 지연 — 다가오던 파국/괴담 행동을 몇 턴 미룬다(무효 아님). */
    private void activateDelay(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int turns = td.param("turns", 1);
        String who = pd.gmDisplayName();
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + who + "이(가) 시간을 번다 — "
            + "다가오던 파국 이벤트나 괴담의 다음 위협 행동을 약 " + turns + "턴 지연시켜라"
            + "(완전 무효가 아니라 '미뤄짐' — 결국 닥쳐온다). 자연스럽게 서술하라.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void activateLinkAlly(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        int depth = td.param("depth", 1);

        if (depth == 1) {
            // 로컬 판정: 생존 여부만 즉시 표시 (AI 불필요)
            applyTraitUsed(pd, td.id, state.getCurrentTurn());
            List<PlayerData> others = state.getAllPlayers().stream()
                .filter(p2 -> !p2.uuid.equals(player.getUniqueId()))
                .collect(java.util.stream.Collectors.toList());
            if (others.isEmpty()) {
                player.sendMessage("§c[" + td.name + "] 감지할 다른 플레이어가 없습니다.");
                return;
            }
            player.sendMessage("§a[" + td.name + "] 아군의 생존 상태를 감지합니다:");
            for (PlayerData op : others) {
                String name = op.gmDisplayName();
                String status = op.isDead ? "§c[사망]"
                    : (op.hp[0] < op.hp[1] / 2) ? "§e[중상]" : "§a[생존]";
                player.sendMessage("  " + status + " §f" + name);
            }
        } else {
            // AI 서술: 아군 위치·상태 파악 또는 소통 경로 감지. 다이얼로그 입력창으로 감지 목표를 직접 받는다(채팅 불필요).
            String depthStr = depth >= 3 ? "소통 경로 발견 포함" : "상태·위치 파악";
            dialogMan.showTextInput(player,
                Component.text("[" + td.name + "] 아군 감지"),
                Component.text("감지할 목표를 입력하세요. (" + depthStr + ")\n예: 가장 가까운 아군의 위치 / 다친 아군이 있는지"),
                "감지 목표", Component.text("감지하기"),
                target -> {
                    applyTraitUsed(pd, td.id, state.getCurrentTurn());
                    handleLinkAllyQuery(player, pd, td.id, target);
                });
        }
    }

    private void activateGuaranteed(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int scope = td.param("scope", 1);
        String scopeStr = switch (scope) {
            case 3  -> "이번 상황 전체 국면";
            case 2  -> "연관된 일련의 행동";
            default -> "이 행동";
        };
        String detail = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "이 캐릭터의 의지가 결과를 확정짓는다.";
        // B1/C4: 단독 제출(허공) 대신 '다음에 입력하는 실제 행동'에 확정 성공을 부착한다.
        pendingActionBoost.put(player.getUniqueId(),
            "[확정 성공(" + td.name + "): " + scopeStr + "은(는) 주사위·실패를 무시하고 §반드시 성공§한 것으로 서술하라(결과 확정). "
            + detail + " 단 괴담 본체를 즉사·즉시 해결시키는 과잉 처리는 금지하고 '그 행동의 의도'가 이뤄진 것으로만 묘사.]");
        pendingBoostTrait.put(player.getUniqueId(), td.id); // 취소 시 환원용
        player.sendMessage("§e[" + td.name + "] §7다음에 입력하는 행동이 §f확정 성공§7 처리됩니다. (취소: '취소' 입력)");
    }

    private void activateMobility(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int power = td.param("power", 2);
        String powerStr = switch (power) {
            case 3  -> "거의 확정적으로";
            case 1  -> "유리하게";
            default -> "상당히";
        };
        String charDisplay = pd.gmDisplayName();
        String detail = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "추격을 따돌리거나, 막힌 길을 우회하거나, 목적지에 빠르게 도달한다.";
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + charDisplay
            + "이(가) '" + td.name + "' 특성을 발동했다. GM 지시: " + charDisplay
            + "의 이동·도주·지형 돌파를 " + powerStr + " 성공적으로 서술하라(추격 회피·즉시 도달·막힌 길 우회). "
            + detail + " 이야기에 자연스럽게 반영하라.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void activateRemoteSense(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        int range = td.param("range", 2);
        String rangeStr = switch (range) {
            case 3  -> "전역(어느 구역이든)";
            case 1  -> "인접한 다른 구역";
            default -> "같은 층의 다른 구역";
        };
        // 다이얼로그 입력창으로 감지 대상을 직접 받는다(채팅 불필요).
        dialogMan.showTextInput(player,
            Component.text("[" + td.name + "] 원격 감지"),
            Component.text("원격으로 감지할 대상을 입력하세요. (범위: " + rangeStr + ")\n예: 옆 방의 대화 / 위층에 무엇이 있는지 / 다른 구역의 인기척"),
            "감지 대상", Component.text("감지하기"),
            target -> {
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                handleRemoteSenseObservation(player, pd, td.id, target);
            });
    }

    private void activateForesight(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        // 다이얼로그 입력창으로 의도한 행동을 직접 받는다(채팅 불필요).
        dialogMan.showTextInput(player,
            Component.text("[" + td.name + "] 예지"),
            Component.text("결과를 미리 보고 싶은 '다음 행동'을 입력하세요. 예상 결과·분기를 보여줍니다.\n예: 문을 열고 복도로 나간다 / 그에게 진실을 묻는다"),
            "예측할 행동", Component.text("내다보기"),
            plan -> {
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                handleForesightQuery(player, pd, td.id, plan);
            });
    }

    private void activateSocial(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int power = td.param("power", 2);
        String powerStr = switch (power) {
            case 3  -> "강하게 설득되어";
            case 1  -> "호의를 보이며";
            default -> "적극적으로 협조하여";
        };
        String charDisplay = pd.gmDisplayName();
        String detail = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "상대의 호감·협조를 끌어내거나 설득한다.";
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + charDisplay
            + "이(가) '" + td.name + "' 특성을 발동했다. GM 지시: 이 장면에 관련된 NPC의 반응을 " + charDisplay
            + "에게 우호적으로 조정해, 그 NPC가 " + powerStr + " 반응하도록 서술하라. "
            + detail + " 단, 적대적 괴담 본체의 본성 자체를 바꾸지는 말고 '대화 가능한 NPC'에 한해 적용하라.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void activateDominate(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        int power = td.param("power", 1);
        String powerStr = (power >= 2) ? "명백히 강제로" : "강하게 유도하여";
        String charDisplay = pd.gmDisplayName();
        String detail = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "대상이 잠시 그 명령을 따르도록 만든다.";
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + charDisplay
            + "이(가) '" + td.name + "' 특성을 발동해 이 장면의 NPC·하위 개체 1명을 짧게 지배한다. GM 지시: 그 대상이 "
            + charDisplay + "의 명령 1회를 " + powerStr + " 따르도록 서술하라(지배는 잠깐이며 곧 풀린다). "
            + detail + " ★단, 괴담 본체·핵심 존재에는 통하지 않는다 — 그런 대상이면 지배가 실패한 것으로 서술하라.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void activateFate(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        String charDisplay = pd.gmDisplayName();
        String detail = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "운명이 이 순간 " + charDisplay + "의 편에 선다.";
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + charDisplay
            + "이(가) '" + td.name + "' 특성을 발동했다(운명). GM 지시: 직전 또는 바로 다음 판정 1회의 결과를 "
            + charDisplay + "에게 §유리한 쪽으로 뒤집어§ 서술하라(불리했던 결과는 가까스로 모면하거나 반전된다). "
            + detail + " 단 1회 한정이며, 괴담 전체를 즉시 무력화하는 식의 과잉 처리는 금지한다.";
        boolean accepted = turnMan.handleAction(player, gmMsg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[" + td.name + " 발동 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void activateGroupRewind(Player player, PlayerData pd, TraitData td) {
        int uses = SystemTraitRegistry.maxUsesPerStage(td);
        if (td.usedThisStage >= uses) {
            player.sendMessage("§c[" + td.name + "] 이번 스테이지 사용 횟수를 모두 소진했습니다.");
            return;
        }
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        forceRetryAllowed = true; // 이번 스테이지 재도전 제약 해제 (스테이지3+여도 재도전 허용)
        String charDisplay = pd.gmDisplayName();
        broadcast("§6§l[" + td.name + "] " + charDisplay + "이(가) 동반회귀의 힘을 발동했다!");
        broadcast("§7파티는 직전 국면으로 되돌아갈 길을 얻었다 — §f이번 스테이지에서 실패하더라도 재도전이 허용됩니다.");
        gameLogger.logEvent("[시스템 특성] " + td.name + " 발동 → forceRetryAllowed=true (재도전 제약 해제)");
        // B2: '되감기'를 실제 연출 — 방금 전 치명적 전개 1회를 되돌린 것처럼 서술(이름·동작 정합)
        String gmMsg = "[시스템 특성: " + td.name + " 발동] 파티가 '" + td.name
            + "'의 힘으로 직전 국면으로 되감긴다. GM 지시: 방금 전의 가장 치명적인 전개·피해 1회를 '되돌려', "
            + "파티가 그 직전 상황으로 돌아간 것처럼 시간 역행을 서술하라. 단 괴담 전체를 무효화하지는 말고 '한 번의 되감기'로만 처리한다.";
        turnMan.handleAction(player, gmMsg, gmSystemPrompt);
    }

    private void handleRemoteSenseObservation(Player player, PlayerData pd, String traitId, String target) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        if (td != null) applyTraitUsed(pd, td.id, state.getCurrentTurn()); // C1: 입력 도착 시 소진
        int range = td != null ? td.param("range", 2) : 2;
        int info  = td != null ? td.param("info", 1) : 1;
        String rangeStr = switch (range) {
            case 3  -> "전역(어느 구역이든)";
            case 1  -> "인접한 다른 구역";
            default -> "같은 층의 다른 구역";
        };
        // ★등급=정밀도(길이 아님)★: 3=또렷한 사실 한 조각 / 2=사실 하나 / 1=기척 / 0=느낌 — 전부 짧게.
        String depthRule = switch (info) {
            case 3  -> "- ★1문장(길면 2문장)★으로 원격의 ★또렷한 사실 한 조각★을 짚는다 — 무엇이 어디에 있는지 급소만(해결법 자체는 아니게).\n";
            case 2  -> "- ★1문장★으로 원격으로 감지한 사실 하나를 짚는다.\n";
            case 1  -> "- ★1문장★으로 '어렴풋한 인기척·소리·기척' 수준만.\n";
            default -> "- ★1문장★으로 아주 모호한 '느낌·예감'만.\n";
        };
        String traitName = td != null ? td.name : "원격 감지";
        String senseCtx = "\n## " + traitName + " 원격 감지 처리 (범위: " + rangeStr + ", 정보 깊이 " + info + "/3)\n"
            + "플레이어가 ★현재 구역이 아닌 떨어진 곳(원격)을 감지한다(독순술·천리안·원격 투시).\n규칙:\n"
            + "- 반드시 '" + rangeStr + "'에 해당하는 ★다른 장소의 정보만 서술한다(현재 위치 묘사 금지).\n"
            + depthRule
            + "- 새로운 단서는 최대 1개. 핵심 해결법·답은 직접 알려주지 않는다.\n"
            + "- 감지할 것이 없으면 '멀어서 잡히는 것이 없다' 식으로 서술한다. 억지로 단서를 만들지 않는다.\n"
            + INFO_OBSERVE_PRINCIPLE;
        String charDisplay = pd.gmDisplayName();
        String prompt = charDisplay + "이(가) '" + traitName + "' 특성으로 " + rangeStr
            + "에 있는 '" + target + "'을(를) 원격으로 감지한다. 위 규칙에 맞춰 GM 서술로 묘사해줘.";
        ai.callGmAiOnce(gmSystemPrompt, senseCtx + "\n\n" + prompt).thenAccept(response ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String stripped = ai.stripTags(response).trim();
                if (!stripped.isBlank())
                    traitReveal(player, pd, "[" + traitName + "] " + stripped, true); // 원격 감지한 사실 → 중요 정보
            })
        );
    }

    private void handleForesightQuery(Player player, PlayerData pd, String traitId, String action) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        if (td != null) applyTraitUsed(pd, td.id, state.getCurrentTurn()); // C1: 입력 도착 시 소진
        int depth = td != null ? td.param("depth", 2) : 2;
        // ★등급=내다보는 깊이(길이 아님)★: 3=직후+핵심 분기 한두 갈래 / 2=직후+한 단계 분기 / 1=직후만 — 전부 짧게.
        String depthRule = switch (depth) {
            case 3  -> "- ★1~2문장★으로 직후 결과 + 한두 갈래 앞의 ★핵심 분기★까지 짚는다(여러 갈래를 길게 나열하지 말 것 — 급소만).\n";
            case 2  -> "- ★1~2문장★으로 직후 결과와 한 단계 분기를 짚는다.\n";
            default -> "- ★1문장★으로 직후 예상 결과만 짧게.\n";
        };
        String traitName = td != null ? td.name : "예지";
        String foresightCtx = "\n## " + traitName + " 결과 예지 처리 (예측 깊이 " + depth + "/3)\n"
            + "플레이어가 어떤 행동을 ★실제로 하기 전에, 그 행동의 예상 결과를 미리 들여다본다(예지·인생설계).\n규칙:\n"
            + "- 이것은 '전망'일 뿐 실제 판정·진행이 아니다. 타임라인을 진행시키지 말고, 결과를 확정하지도 마라.\n"
            + depthRule
            + "- '~할 것이다 / ~로 보인다 / ~할 위험이 있다' 식의 예측 어조로 서술한다.\n"
            + "- 핵심 해결법·정답을 통째로 알려주지는 않는다. 어디까지나 가능성·분기 전망이다.\n"
            + INFO_TIER_PRINCIPLE + knownFactsBlock(pd);
        String charDisplay = pd.gmDisplayName();
        String prompt = charDisplay + "이(가) '" + traitName + "' 특성으로 다음 행동의 결과를 미리 본다. 의도한 행동: \""
            + action + "\". 위 규칙에 맞춰 예상 결과·분기를 전망해줘.";
        ai.callGmAiOnce(gmSystemPrompt, foresightCtx + "\n\n" + prompt).thenAccept(response ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String stripped = ai.stripTags(response).trim();
                if (!stripped.isBlank())
                    traitReveal(player, pd, "[" + traitName + " — 예지] " + stripped, false); // 예측은 표시만(사실 아님)
            })
        );
    }

    private void handleScanObservation(Player player, PlayerData pd, String traitId, String target) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        int scope = td != null ? td.param("scope", 2) : 2;
        String scopeStr = switch (scope) {
            case 3  -> "건물 전체";
            case 2  -> "인접 구역·층";
            default -> "현재 위치";
        };
        // ★등급 = 발견 정밀도★: 낮으면 '단서 유무'만, 높으면 '즉시 발견(내용)'. 아무것도 없으면 그냥 본 것을 서술.
        int g = gradeIdx(td != null ? td.grade : "C");
        String findRule = (g >= 5)
            ? "- ★있으면 즉시 발견★: 이 범위에 살펴볼 단서가 있으면 그 ★내용을 곧바로★ 짚어준다(무엇인지 또렷이). 단 핵심 해결법·정답을 통째로 주지는 마라.\n"
            : (g >= 3)
            ? "- 단서가 있으면 ★무엇에 관한 것인지 방향만★ 짚어준다(정확한 내용은 아직 흐릿하게).\n"
            : "- ★유무만★: 이 범위에 '살펴볼 만한 단서가 있다/없다' 정도만 알려준다(구체 내용은 아직 모른다).\n";
        String traitName = td != null ? td.name : "환경 탐색";
        String scanCtx = "\n## " + traitName + " 탐색 처리 (범위: " + scopeStr + ", 등급: " + (td != null ? td.grade : "?") + ")\n"
            + "플레이어가 체계적 탐색으로 단서를 찾고 있다. 규칙:\n"
            + "- 탐색 범위(" + scopeStr + ") 안에서 찾을 수 있는 것만 서술한다.\n"
            + findRule
            + "- ★정말 아무것도 없으면 억지로 지어내지 말고 눈에 보이는 광경을 담담히 서술한다(정직한 빈손).★\n"
            + "- 탐색 행동 자체도 타임라인에 적절히 반영한다.\n"
            + INFO_OBSERVE_PRINCIPLE;
        String charDisplay = pd.gmDisplayName();
        String prompt = charDisplay + "이(가) '" + traitName + "' 특성으로 " + scopeStr
            + " 범위에서 '" + target + "'을(를) 탐색한다.";
        boolean accepted = turnMan.handleAction(player, prompt, gmSystemPrompt, scanCtx);
        if (!accepted) player.sendMessage("§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void handleLinkAllyQuery(Player player, PlayerData pd, String traitId, String query) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        int depth = td != null ? td.param("depth", 2) : 2;
        String traitName = td != null ? td.name : "아군 감지";

        StringBuilder allyCtx = new StringBuilder();
        for (PlayerData op : state.getAllPlayers()) {
            if (op.uuid.equals(player.getUniqueId())) continue;
            String name = op.gmDisplayName();
            allyCtx.append("  - ").append(name).append(": ")
                   .append(op.isDead ? "사망" : "생존 (위치: " + op.zone + ")").append("\n");
        }
        String depthRule = depth >= 3
            ? "- 위치·상태를 꽤 구체적으로 암시하고, 소통 수단(연락 방법·접촉 경로)을 발견할 수 있게 한다.\n"
            : "- 아군의 대략적 방향·생존 여부 정도만 감각으로 암시한다. 정확한 위치나 소통 수단은 직접 알려주지 않는다.\n";
        String linkCtx = "\n## " + traitName + " 처리 (감지 깊이 " + depth + "/3)\n"
            + "플레이어가 초감각으로 아군을 탐지하고 있다. 현재 아군 상태:\n" + allyCtx
            + "규칙:\n" + depthRule
            + "- 직접 통신 채널을 여는 것은 불가. 감각적 인지·이야기 서술로만 표현한다.\n"
            + INFO_OBSERVE_PRINCIPLE;
        String charDisplay = pd.gmDisplayName();
        String prompt = charDisplay + "이(가) '" + traitName + "' 특성으로 아군을 탐지한다. 탐지 목표: \"" + query + "\"";
        ai.callGmAiOnce(gmSystemPrompt, linkCtx + "\n\n" + prompt).thenAccept(response ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String stripped = ai.stripTags(response).trim();
                if (!stripped.isBlank())
                    traitReveal(player, pd, "[" + traitName + "] " + stripped, true); // 아군 탐지 결과 → 중요 정보
            })
        );
    }

    private void handlePrayerQuestion(Player player, PlayerData pd, String traitId, String question) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        int info = td != null ? td.param("info", 1) : 1;
        String depthRule = switch (info) {
            case 3  -> "- 핵심 해결법·약점을 직접 말하지 말고, ★짧고 중의적인 한 문장 힌트★로 방향만 암시한다(여러 해석이 가능하게 — 가장 또렷한 깊이라도 단정·나열 금지).\n";
            case 2  -> "- ★한 문장, 두루뭉실하고 중의적으로★ 암시한다(무엇을 가리키는지 곧장 와닿지 않게).\n";
            default -> "- '느낌·예감·낌새'만 ★아주 짧게(한 문장) 모호하게★ — 무엇에 대한 것인지조차 흐릿하게. 직접적 단서·이름·해결법 금지.\n"
                     + "- 예: \"그쪽으로 마음이 자꾸 쏠린다… 왜인지는 모르겠다.\"\n";
        };
        String name = td != null ? td.name : "질문";
        String prayerCtx = "\n## " + name + " 질문 처리 (정보 깊이 " + info + "/3)\n"
            + "플레이어가 시스템 특성으로 GM에게 직접 질문했다.\n규칙:\n" + depthRule
            + "- ★배경 정보는 공개★: '지금이 언제인가(시대·시기·대략 시각)·여기가 어디인가(지역·장소·현재/시작 위치)' 같은 무대 배경을 물으면, 정보 깊이와 무관하게 또렷이 알려줘라(핵심 스포일러 아님). 단 시간을 빼앗긴 상태(시간 불명)면 시각만 모호하게.\n"
            + "- ★반복 조회 누적 금지★: 같은 대상·장소·주제를 다시 물어도 ★새 사실을 더 주지 마라★ — 이미 준 인상과 같은 결을 표현만 달리하라(여러 번 캐물어 진상을 특정하지 못하게).\n"
            + INFO_TIER_PRINCIPLE + knownFactsBlock(pd);

        String charDisplay = pd.gmDisplayName();
        String prompt = charDisplay + "이(가) '" + name + "' 특성으로 질문한다: \"" + question + "\" "
            + "위 규칙에 맞춰 답해줘.";

        ai.callGmAiOnce(gmSystemPrompt, prayerCtx + "\n\n" + prompt).thenAccept(response ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String stripped = ai.stripTags(response).trim();
                if (!stripped.isBlank())
                    traitReveal(player, pd, "[" + name + "] " + stripped, true); // 질문으로 알아낸 사실 → 중요 정보
            })
        );
    }

    private void handleOracleAction(Player player, PlayerData pd, String traitId, String action) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        int numChoices = td != null ? Math.max(2, Math.min(4, td.param("choices", 3))) : 3;
        final boolean autoMode = action == null || action.isBlank();
        final TraitData fTd = td;
        // ★등급 = 선택지 품질★: 높으면(A~S) 괴담 해결로 직접 이어지는 '정답' 선택지까지 섞어준다.
        int g = gradeIdx(td != null ? td.grade : "C");
        boolean allowSolve = g >= 5;
        String outcomeMenu = allowSolve
            ? "{\"choices\":[{\"text\":\"선택지(15자 이내)\",\"outcome\":\"solve|good|bad|neutral\"},...]}\n"
              + "- solve: ★이 괴담을 실제로 해결·돌파하는 길로 직접 이어지는 '정답' 선택지★ — 상황상 정말 그런 수가 보일 때만 최대 1개(아니면 넣지 마라).\n"
              + "- good: 현 상황에서 가장 효과적인 방법 (큰 보정+) — 정확히 1개\n"
              + "- bad: 역효과를 낼 방법 (큰 패널티-) — 1개 이상\n"
              + "- neutral: 무난하나 특별한 보정 없음\n"
            : "{\"choices\":[{\"text\":\"선택지(15자 이내)\",\"outcome\":\"good|bad|neutral\"},...]}\n"
              + "- good: 현 상황에서 가장 효과적인 방법 (큰 보정+) — 정확히 1개\n"
              + "- bad: 역효과를 낼 방법 (큰 패널티-) — 1개 이상\n"
              + "- neutral: 무난하나 특별한 보정 없음\n"
              + "- ★해결로 직결되는 '정답' 선택지는 이 등급에선 넣지 마라(전술적 유불리까지만).★\n";
        String oracleCtx = "\n## 선택지 모드 (등급: " + (td != null ? td.grade : "?") + ")\n"
            + (autoMode
                ? "지금 ★현재 상황★에서 이 인물이 취할 만한 " + numChoices + "가지 행동 선택지를 JSON으로 제시하라:\n"
                : "플레이어의 행동 의도를 받아 " + numChoices + "가지 선택지를 JSON으로 제시하라:\n")
            + outcomeMenu
            + "순서는 랜덤하게 섞어 정답을 알기 어렵게 할 것. JSON만 출력.\n";

        String prompt = autoMode
            ? "현재 상황에서 취할 만한 " + numChoices + "가지 선택지를 JSON으로."
            : "플레이어 행동 의도: \"" + action + "\". " + numChoices + "가지 선택지를 JSON으로.";

        ai.callGmAiOnce(gmSystemPrompt, oracleCtx + "\n\n" + prompt).thenAccept(raw ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
                    int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
                    if (s < 0 || e < 0) { fallbackOracleAction(player, pd, action); return; }
                    JsonObject json = new com.google.gson.Gson().fromJson(cleaned.substring(s, e + 1), JsonObject.class);
                    JsonArray choicesArr = json.getAsJsonArray("choices");
                    if (choicesArr == null || choicesArr.size() == 0) { fallbackOracleAction(player, pd, action); return; }

                    List<OracleChoice> choices = new ArrayList<>();
                    for (JsonElement el : choicesArr) {
                        JsonObject c = el.getAsJsonObject();
                        choices.add(new OracleChoice(
                            c.has("text")    ? c.get("text").getAsString()    : "선택지",
                            c.has("outcome") ? c.get("outcome").getAsString() : "neutral"
                        ));
                    }
                    pendingOracleChoices.put(player.getUniqueId(), choices);

                    // 다이얼로그 버튼으로 선택지 제시(채팅 클릭 대신) — 고르면 handleOracleSelect로
                    List<String> labels = new ArrayList<>();
                    for (OracleChoice oc : choices) labels.add(oc.text());
                    dialogMan.showActionChoices(player,
                        Component.text("[" + (fTd != null ? fTd.name : "선택지") + "] 선택"),
                        Component.text(autoMode ? "지금 상황에서 무엇을 할까요?" : "어떤 방법을 고를까요?"),
                        labels,
                        idx -> handleOracleSelect(player, idx));
                } catch (Exception ex) {
                    fallbackOracleAction(player, pd, action);
                }
            })
        );
    }

    private void fallbackOracleAction(Player player, PlayerData pd, String action) {
        if (action == null || action.isBlank()) { // 자동 모드: 입력이 없어 일반 행동으로 처리할 수 없다
            player.sendMessage("§c[선택지] 선택지 생성에 실패했습니다. 잠시 후 다시 시도하세요.");
            return;
        }
        player.sendMessage("§c[신내림] 선택지 생성에 실패했습니다. 일반 행동으로 처리합니다.");
        boolean accepted = turnMan.handleAction(player, action, gmSystemPrompt);
        if (!accepted) player.sendMessage("§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void handleOracleSelect(Player player, int idx) {
        List<OracleChoice> choices = pendingOracleChoices.remove(player.getUniqueId());
        if (choices == null || idx < 0 || idx >= choices.size()) {
            player.sendMessage("§c[선택지] 잘못된 선택입니다.");
            return;
        }
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        OracleChoice chosen = choices.get(idx);
        String modifier = switch (chosen.outcome()) {
            case "solve"   -> " (계시 — ★정답: 이 괴담을 해결·돌파하는 결정적 선택. 그 시도가 성공적으로 이어지도록 서술하되 즉시 완전 클리어를 강요하지 말고 '해결의 결정적 진전'으로 처리★)";
            case "good"    -> " (계시 — 최적 선택: 큰 보정 적용)";
            case "bad"     -> " (계시 — 역효과 선택: 큰 패널티 적용)";
            default        -> " (계시 — 무난한 선택)";
        };
        String msg = "[선택지 행동] " + (pd.gmDisplayName())
            + "이(가) '" + chosen.text() + "'" + modifier + " 행동을 취한다.";
        boolean accepted = turnMan.handleAction(player, msg, gmSystemPrompt);
        player.sendMessage(accepted ? "§7[행동 전달 중...]" : "§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    public void giveSystemTrait(Player admin, Player target, String traitId) {
        SystemTraitRegistry.Preset preset = SystemTraitRegistry.getPreset(traitId).orElse(null);
        if (preset == null) {
            admin.sendMessage("§c알 수 없는 시스템 특성 ID: " + traitId);
            admin.sendMessage("§7사용 가능한 ID 목록:");
            SystemTraitRegistry.printCatalog(admin);
            return;
        }
        PlayerData pd = state.getPlayer(target.getUniqueId());
        if (pd == null) {
            admin.sendMessage("§c" + target.getName() + "은(는) 현재 세션 참가자가 아닙니다.");
            return;
        }
        if (pd.traits.stream().anyMatch(t -> t.id.equals(traitId))) {
            admin.sendMessage("§c" + target.getName() + "은(는) 이미 해당 특성을 보유하고 있습니다.");
            return;
        }
        TraitData td = preset.toTraitData();
        traitMan.addTrait(pd, td);
        admin.sendMessage("§a[시스템 특성] " + target.getName() + "에게 §e(" + td.grade + ") " + td.name + "§a을(를) 부여했습니다.");
        target.sendMessage("§e[특성 획득] §f(" + td.grade + ") " + td.name + " §7— " + td.description);
        gameLogger.logEvent("[시스템 특성 부여] " + target.getName() + " ← " + td.name + " (" + traitId + ")");

        // 정보 계열 패시브 특성을 배역 배정 후 부여했다면 지금 바로 직감 브리핑 전달
        if (isPassiveInfoTrait(td) && pd.roleAssigned) {
            deliverInsightInfo(target, td);
        }
    }

    /** 정보 계열 패시브 특성인지(시작 시 '직감'으로 정보를 주는 특성). */
    private static boolean isPassiveInfoTrait(TraitData t) {
        if (t == null || t.effectType == null) return false;
        return switch (t.effectType) {
            case "scenario_insight", "entity_sense", "ally_sense", "lore_record", "encounter_scan" -> true;
            default -> false;
        };
    }

    /**
     * 정보 계열 패시브 특성(시나리오 이해·적대자 감지·구원자 탐지·전지적 독자시점)을
     * AI로 자연스럽게 가공해 '직감 브리핑'으로 전달한다.
     * - 공개 범위는 effect_type(포커스)별로 다르고, 양·선명도는 depth로, 약점 노출은 등급 S 한정.
     * - 정답·해결법·붕괴조건은 절대 노출하지 않는다. 결과는 '중요 정보'(keyFacts)에 기록된다.
     */
    private void deliverInsightInfo(Player player, TraitData td) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || td == null) return;
        PlayerData pd = state.getPlayer(player);
        String focus  = (td.effectType == null) ? "scenario_insight" : td.effectType;
        int depth     = td.param("depth", 2);
        boolean veryHigh = gradeIdx(td.grade) >= 6; // S = 약점 '방향'까지 허용
        String traitName = (td.name == null || td.name.isBlank()) ? "직감" : td.name;

        JsonObject e  = (gdam.has("entity") && gdam.get("entity").isJsonObject())
            ? gdam.getAsJsonObject("entity") : null;
        JsonObject wr = (gdam.has("world_rules") && gdam.get("world_rules").isJsonObject())
            ? gdam.getAsJsonObject("world_rules") : null;

        StringBuilder ctx = new StringBuilder();
        String focusRule;
        String styleHint; // 이 능력의 '결'에 맞는 전달 말투 — INFO_TIER_PRINCIPLE의 4유형 중 어느 쪽인지 좁혀준다.
        switch (focus) {
            case "entity_sense" -> {
                // 적대자 감지 = 시작 시 '경계할 자'에 대한 짧은 수수께끼 단서 한 줄.
                focusRule = "포커스=적대자 감지: 경계해야 할 적대 존재에 대한 ★짧은 수수께끼 단서 한 줄★을 준다. "
                    + "정확한 정체·이름·해결법은 금지 — '무엇을 경계해야 하는가'의 방향만 은유로 흘려라. "
                    + "예: '악마는 빛을 등지고 서있다' · '지옥으로 가는 길은 선의로 포장되어 있다' · '오늘따라 친절이 무섭게 다가온다'.";
                styleHint = "①인과율(은유·수수께끼) 또는 ③신탁(중의적 한 줄) 계열 — 짧은 경구 한 줄로. 등급이 높을수록 그 은유가 실체에 더 가깝게(제약 해제), 낮으면 더 흐리게.";
                if (e != null) {
                    if (e.has("type")) ctx.append("적대 존재 유형(직접 노출 금지 — 은유의 재료로만): ").append(getStr(e, "type")).append("\n");
                    if (e.has("ai_context")) {
                        String pers = getStr(e.getAsJsonObject("ai_context"), "personality");
                        if (!pers.isBlank()) ctx.append("본질·성향(은유의 재료): ").append(pers).append("\n");
                    }
                    if (veryHigh && !getStr(e, "weakness").isBlank())
                        ctx.append("[등급 S 전용 — 약점 '방향'만 흐리게 암시 가능] 약점: ").append(getStr(e, "weakness")).append("\n");
                }
            }
            case "ally_sense" -> {
                // 구원자 탐지 = NPC 감지. 시작 시 '믿고 찾을 만한 조력자'에 대한 짧은 단서 한 줄(특징·인상).
                focusRule = "포커스=구원자 탐지(조력 NPC): 믿고 기댈 만한 조력 NPC에 대한 ★짧은 단서 한 줄★을 준다 — 이름을 통째로 던지기보다 특징·인상으로 알아보게. "
                    + "예: '그는 늘 긴 코트를 입고 있었지'. 적대·위장 가능성 있는 인물은 절대 콕 집지 마라.";
                styleHint = "①인과율(특징을 에둘러) 또는 ③신탁(인상 한 줄) 계열. ★등급↑ = 제약 해제★: 높으면 이름·위치에 더 가깝게, 낮으면 특징 한 조각만 흐리게.";
                ctx.append(buildAllyNpcContext());
            }
            case "lore_record" -> {
                // 전지적 독자시점 = 이미 겪고 실패한 뒤 회귀한 '미래의 나'의 독백(소설풍). 과거 실패가 곧 앞날의 경고.
                focusRule = "포커스=전지적 독자시점: 이 사건을 ★이미 겪고 실패한 뒤 시간을 거슬러 돌아온 '미래의 나(회귀자)'의 독백★을 소설처럼 들려준다. "
                    + "특히 ★규칙·금기·행동 제약을 범해 스러진 순간★을 1인칭 회고로 써서 규칙을 ★간접적으로★ 드러내라"
                    + "('규칙은 X다'라고 적지 말고 '나는 X를 하다 당했다… 다시는 그러지 마'처럼). ★정답·해야 할 행동을 '지시'하지는 마라 — 겪은 실패 하나를 서술할 뿐.★";
                styleHint = "④'미래의 나' 유형으로 고정 — 회귀자의 1인칭 독백(소설풍), 단 하나의 실패를 담담히 회고. 정답 지시 금지.";
                if (e != null && e.has("rules") && e.get("rules").isJsonArray() && e.getAsJsonArray("rules").size() > 0) {
                    ctx.append("실제 규칙(이걸 어겨 스러진 '나'의 회고로 변환):\n");
                    e.getAsJsonArray("rules").forEach(r -> ctx.append("  - ").append(r.getAsString()).append("\n"));
                }
                if (wr != null && wr.has("details") && wr.get("details").isJsonArray())
                    for (JsonElement d : wr.getAsJsonArray("details")) ctx.append("  - ").append(d.getAsString()).append("\n");
            }
            case "encounter_scan" -> {
                // 첫 조우 = 곧 마주칠 인물/존재의 성향·목표·상태를 어렴풋한 첫인상으로(정체는 모름).
                focusRule = "포커스=첫 조우: 곧 처음 마주칠 인물/존재의 ★성향·목표·상태★를 어렴풋한 첫인상으로만 준다(정체·정답은 모른다). "
                    + "겉으로 드러나는 낌새·행색·태도 위주로. 예: '비에 흠뻑 젖어 있다' · '다급히 정보실을 찾고 있다' · '뭔가 감추는 듯하다' · '나를 천천히 뜯어보고 있다'.";
                styleHint = "출처(이름·표방 효과)에 맞춰 4유형 중 하나. 첫인상 한 조각(성향/목표/상태)만 — 정체 규정 금지.";
                if (e != null && e.has("type")) ctx.append("처음 마주칠 적대 존재 유형(정체 직접 노출 금지): ").append(getStr(e, "type")).append("\n");
                if (e != null && e.has("ai_context")) {
                    String pers = getStr(e.getAsJsonObject("ai_context"), "personality");
                    if (!pers.isBlank()) ctx.append("성향·태도(첫인상 재료): ").append(pers).append("\n");
                }
                ctx.append(buildAllyNpcContext());
            }
            default -> { // scenario_insight
                // 시나리오 이해 = '영화 줄거리(스포일러 금지)' + '지금 어디쯤(진행도)'. ★핵심 해답(world_rules.core)은 절대 넘기지 않는다.★
                focus = "scenario_insight";
                focusRule = "포커스=시나리오 이해: 지금 벌어지는 사건의 ★큰 줄기를 '영화 줄거리'처럼★(스포일러 금지) 어렴풋이 짚어주거나, ★이야기가 지금 어디쯤 와 있는지(진행도)★를 알려준다. 정체·정답·해결법·약점·붕괴조건은 제외.";
                styleHint = "①인과율(에둘러) 또는 ③신탁(중의적) 계열 — 예고편처럼 분위기와 큰 줄기만.";
                if (!getStr(gdam, "scale").isBlank()) ctx.append("사건 규모: ").append(getStr(gdam, "scale")).append("\n");
                if (e != null && e.has("type")) ctx.append("(참고) 존재 유형(직접 이름 노출 금지): ").append(getStr(e, "type")).append("\n");
                ctx.append("현재 진행도(줄거리 기준): ").append(scenarioProgressDescriptor()).append("\n");
            }
        }
        if (ctx.length() == 0) ctx.append("(특별히 잡히는 정보가 거의 없다 — 아주 흐릿한 직감만.)\n");

        String effectText = (td.effect == null) ? "" : td.effect.trim();
        boolean allowWeaknessHint = veryHigh && "entity_sense".equals(focus); // 약점 방향 암시는 적대자 감지 S급에서만
        String lengthRule = "lore_record".equals(focus)
            ? "- ★예외적으로 3~4문장의 소설풍 1인칭 독백까지 허용★(회귀자의 회고 장면). 그 이상 늘리지는 마라.\n"
            : "- ★1~2문장으로 짧게. 두루뭉실하고 중의적으로★(여러 갈래로 해석될 수 있게) — '어렴풋이 안다·직감한다'는 톤.\n";
        String system = "너는 괴담 TRPG에서 '정보 계열 특성'이 플레이어에게 주는 ★직감 브리핑★을 쓴다.\n"
            + "특성 이름: " + traitName + "\n"
            + (effectText.isBlank() ? "" : "이 특성이 표방하는 효과(설명): " + effectText + "\n")
            + "등급: " + td.grade + " / 정보 깊이(depth): " + depth + "\n"
            + focusRule + "\n## 작성 규칙\n"
            + "- ★이 특성의 '이름'과 '표방 효과'의 결·말투에 맞춰 브리핑을 자연스럽게 빚어라.★ "
            + "공개하는 정보의 종류·범위·표현을 이름/효과에 어울리게 AI가 직접 조절한다(능력 골격만 시스템이 정하고, 어떻게 비추는지는 네가 정한다).\n"
            + "- ★전달 말투★: " + styleHint + "\n"
            + lengthRule
            + "- ★'규칙이 N개 있다 / 약점이 존재한다 / 무언가 있다' 같은 '존재 여부·개수' 진술은 절대 금지.★ 항상 ★실제 내용 조각★만, 그것도 흐릿하게 준다.\n"
            + "- ★같은 대상을 반복해 비춰도 새 사실을 누적하지 마라★ — 이미 준 조각과 같은 결을 표현만 달리하라(반복 사용으로 진상 특정 방지).\n"
            + "- 정답·정확한 해결 절차·붕괴조건은 어떤 경우에도 노출 금지.\n"
            + "- ★선명도만 등급에 비례(길이는 절대 늘리지 마라)★: 낮으면 한 조각을 아주 흐릿하게, 높으면 ★같은 짧은 길이로★ 한 조각을 더 또렷하게 짚어줄 뿐 — 문장을 늘리거나 나열하지 마라.\n"
            + (allowWeaknessHint ? "- 이 특성은 등급이 매우 높다: 약점의 '방향' 한 가닥을 ★짧고 애매하게★ 스쳐도 된다 — 단 해답 문장처럼 풀어 쓰지 말고(정확한 해결법 금지), 플레이어가 스스로 잇게 하라.\n"
                        : "- 약점·해결법은 절대 직접 알려주지 마라.\n")
            + "- 마크다운·머리표·메타 설명 없이 서술만.\n"
            // 위 규칙이 INFO_TIER_PRINCIPLE 내용을 이미 모두 담고 있어(styleHint=말투, lengthRule, 존재여부·누적·선명도 규칙)
            //   중복 첨부를 제거하고 안전 프레이밍(GAME_FICTION_FRAME)만 유지한다(Haiku 거부 방지). ~360토큰/사용 절감.
            + GAME_FICTION_FRAME;
        String prompt = "## 시나리오 정보(아래 내용만 근거로 삼아라)\n" + ctx + knownFactsBlock(pd)
            + "\n위 정보로 '" + traitName + "' 직감 브리핑을 작성하라.";

        ai.callAssistant(system, prompt).thenAccept(raw -> {
            String text = (raw == null) ? "" : raw.replaceAll("```", "").replaceAll("(?m)^#+\\s*", "").trim();
            if (text.isEmpty()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.sendMessage("§d[" + traitName + "] §7당신은 어렴풋이 알고 있습니다:");
                for (String line : text.split("\n")) {
                    String l = line.trim();
                    if (!l.isEmpty()) player.sendMessage("§7  " + l);
                }
                if (pd != null) pd.addKeyFact("[" + traitName + "] " + text.replace("\n", " ").replaceAll("§.", ""));
                gameLogger.logAbilityResult(pd != null ? pd.gmDisplayName() : player.getName(), traitName, text.replace("\n", " "));
            });
        });
    }

    /** 아군/조력 성향일 수 있는 critical NPC 목록(이름 + 현재 위치 + 역할유형)을 AI 컨텍스트로 만든다. 적대·위장 의심 인물은 제외. */
    private String buildAllyNpcContext() {
        StringBuilder sb = new StringBuilder();
        for (JsonObject npc : getCriticalNpcs()) {
            String nm = getStr(npc, "name");
            if (nm.isBlank()) continue;
            if (isHostileNpc(npc)) continue; // 적대·위장 의심 인물 제외
            String id = getStr(npc, "id");
            String z = npcZones.getOrDefault(id, getStr(npc, "zone"));
            sb.append("  · ").append(nm).append(" — 위치: ").append(z.isBlank() ? "위치 미상" : zoneDisplayName(z));
            String rt = getStr(npc, "role_type");
            if (!rt.isBlank()) sb.append(" / 역할성향: ").append(rt);
            sb.append("\n");
        }
        if (sb.length() == 0) return "(주변에 도움이 될 만한 인물의 기척이 잡히지 않는다.)\n";
        return "주변 인물(이 중 우호적일 인물만 골라 알릴 것):\n" + sb;
    }

    private void applySaintEffect(Player player, PlayerData pd, String traitId, PlayerData target) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        String traitName = td != null ? td.name : "회복";
        boolean wasDeadBefore = target.isDead;
        boolean wasPuppet = "puppet".equals(target.status) || target.puppetRecoveryTurns > 0;
        target.hp[0]  = target.hp[1];
        target.san[0] = target.san[1];
        target.status = "normal";
        target.isDead = false;
        target.puppetRecoveryTurns = 0; // 완전 잠식(관전) 해제 — 아군 회복이 조종을 완전히 풀어준다
        target.faintTurnsRemaining = 0; // 기절 타이머도 해제
        restorePlaying(target); // 부활 시 관전(스펙테이터) 해제 → 생존 복귀
        applyTraitUsed(pd, traitId, state.getCurrentTurn());
        updateAllScoreboards();
        String targetDisplay = target.gmDisplayName();
        String playerDisplay = pd.gmDisplayName();
        player.sendMessage("§a[" + traitName + "] " + targetDisplay + "을(를) 완전히 회복시켰습니다.");
        Player targetPlayer = Bukkit.getPlayer(target.uuid);
        if (targetPlayer != null) {
            if (wasDeadBefore) targetPlayer.sendMessage("§a당신은 부활했습니다! 체력과 정신력이 완전히 회복되었습니다.");
            else               targetPlayer.sendMessage("§a" + playerDisplay + "이(가) 당신의 체력과 정신력을 완전히 회복시켰습니다!");
        }
        String gmMsg = "[시스템 특성: " + traitName + " 발동] " + playerDisplay + "이(가) " + targetDisplay
            + "을(를) 완전히 회복시켰다." + (wasDeadBefore ? " 부활." : "") + " 이야기에 이 회복 효과를 자연스럽게 반영하라.";
        turnMan.handleAction(player, gmMsg, gmSystemPrompt);
    }

    // ──────────────────────────────────────────────────────────────
    //  캐릭터 정보 GUI (핫바 아이템 우클릭으로 열기)
    // ──────────────────────────────────────────────────────────────

    private static final String INFO_ITEM_TAG = "trpg_info_item";

    private NamespacedKey infoItemKey() {
        return new NamespacedKey(plugin, INFO_ITEM_TAG);
    }

    /** 핫바에 캐릭터 정보 아이템 지급 (이미 있으면 생략) */
    public void giveInfoItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isInfoItem(it)) return;
        }
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("캐릭터 정보", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("우클릭하여 능력치·특성을 확인하고", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("능동 특성을 발동합니다.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(infoItemKey(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        // 슬롯 8(핫바 끝)이 비어있으면 거기에, 아니면 기존 아이템을 밀지 않고 빈 칸에 추가
        var inv = p.getInventory();
        ItemStack slot8 = inv.getItem(8);
        if (slot8 == null || slot8.getType().isAir()) {
            inv.setItem(8, item);
        } else {
            inv.addItem(item);
        }
    }

    /** 캐릭터 정보 아이템인지 판별 */
    public boolean isInfoItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
            .has(infoItemKey(), PersistentDataType.BYTE);
    }

    /** 인벤토리에서 캐릭터 정보 아이템 제거 (세션 종료 시) */
    public void removeInfoItem(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isInfoItem(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    /** 캐릭터 정보 GUI 열기 (능동 특성 발동 콜백 포함) */
    public void openCharacterInfo(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) { player.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        // 캐릭터 생성 중(미확정)이면 재굴림·확정 시트를 다시 연다 — 닫혔을 때 복구용 2중 안전장치.
        if (currentPhase == Phase.CHAR_CREATION && pendingCreation.contains(player.getUniqueId()) && !pd.statsConfirmed) {
            showCharacterSheetForPlayer(player, pd);
            return;
        }
        dialogMan.showCharacterInfo(player, pd, charGen.describeJob(pd.job), traitId -> handleTraitUse(player, traitId));
    }

    // ──────────────────────────────────────────────────────────────
    //  기록 아이템 (핫바 우클릭으로 기록 다이얼로그 열기)
    // ──────────────────────────────────────────────────────────────

    private static final String RECORD_ITEM_TAG = "trpg_record_item";

    private NamespacedKey recordItemKey() {
        return new NamespacedKey(plugin, RECORD_ITEM_TAG);
    }

    /** 핫바에 기록(로그/정보) 아이템 지급 (이미 있으면 생략) */
    public void giveRecordItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isRecordItem(it)) return;
        }
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("기록", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("우클릭하여 지금까지의 기록을 봅니다.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("전체 대화 / 수집 정보 선택 · 페이지 넘김", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(recordItemKey(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        var inv = p.getInventory();
        ItemStack slot7 = inv.getItem(7);
        if (slot7 == null || slot7.getType().isAir()) inv.setItem(7, item);
        else inv.addItem(item);
    }

    /** 기록 아이템인지 판별 */
    public boolean isRecordItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
            .has(recordItemKey(), PersistentDataType.BYTE);
    }

    /** 인벤토리에서 기록 아이템 제거 (세션 종료 시) */
    public void removeRecordItem(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isRecordItem(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    /** 메모장(책과 깃털) 지급 — 플레이어가 자유롭게 메모하도록. 이미 있어도 지급하지 않음. */
    private void giveNotepadItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == Material.WRITABLE_BOOK
                    && it.hasItemMeta()
                    && it.getItemMeta().getPersistentDataContainer()
                           .has(notepadKey(), PersistentDataType.BYTE)) return;
        }
        ItemStack note = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta nm = note.getItemMeta();
        if (nm != null) {
            nm.displayName(Component.text("메모장", NamedTextColor.WHITE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            nm.lore(List.of(
                Component.text("자유롭게 메모를 남길 수 있습니다.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            nm.getPersistentDataContainer().set(notepadKey(), PersistentDataType.BYTE, (byte) 1);
            note.setItemMeta(nm);
        }
        p.getInventory().addItem(note);
    }

    private NamespacedKey notepadKey() { return new NamespacedKey(plugin, "trpg_notepad"); }

    /** /trpg map — 직접 그린 현장 약도(지도 아이템)를 손에 넣는다 */
    public void openMap(Player player) {
        mapMan.giveMapItem(player);
    }

    /** 약도 아이템 우클릭 → 구역 선택 다이얼로그 */
    public void openMapSelector(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) { player.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        if (!mapMan.hasZones()) { player.sendMessage("§7아직 지도 정보가 없습니다."); return; }
        if (!mapMan.hasMultiAreas()) {
            player.sendMessage("§7이 시나리오는 단일 구역으로 구성되어 있습니다.");
            return;
        }
        dialogMan.showMapSelector(player, mapMan.areaNames(),
            area -> Bukkit.getScheduler().runTask(plugin, () -> mapMan.swapMapView(player, area)));
    }

    /** 지도 아이템 여부 판별 (ChatListener에서 사용) */
    public boolean isMapItem(ItemStack it) { return mapMan.isMapItem(it); }

    // ──────────────────────────────────────────────────────────────
    //  클리어 엔딩
    // ──────────────────────────────────────────────────────────────

    private void onClearEnding(String grade, String reason, boolean resolved) {
        onClearEnding(grade, reason, resolved, "");
    }
    private void onClearEnding(String grade, String reason, boolean resolved, String by) {
        if (currentPhase == Phase.CLEAR || currentPhase == Phase.GAMEOVER) return;
        currentPhase = Phase.CLEAR;
        turnMan.cancelAll(); // 병렬 처리 중이던 다른 플레이어의 행동 취소 — 클리어 후 늦은 서술 누수 방지
        int room = state.getRoomNumber();
        // 스테이지 3+는 괴담 완전 해결(해결판정)만 다음 스테이지 진출 허용. 단순 생존은 재도전만 가능.
        nextStageUnlocked = (room < 3) || resolved;
        gameLogger.logEvent("클리어 — 등급: " + grade + " / 판정: " + (resolved ? "해결" : "생존")
            + (reason != null && !reason.isBlank() ? " / 내용: " + reason : ""));

        String finalGrade = corruptMan.getRewardGrade(grade);
        broadcast("§6§l═══════════════════════════════");
        broadcast("§6§l  클리어! 등급: " + grade
            + (corruptMan.getLevel() > 0 ? " (오염 보정 → " + finalGrade + ")" : ""));
        broadcast("§6§l═══════════════════════════════");
        // ★평가 전, 전원에게 '누가·어떤 이유로·어떤 클리어인지' 공개★
        broadcast("§b▶ 판정 유형: §f" + (resolved ? "해결판정 — 괴담을 해소함" : "생존판정 — 생존·도주 성공"));
        if (by != null && !by.isBlank())         broadcast("§b▶ 클리어 주체: §f" + by);
        if (reason != null && !reason.isBlank())  broadcast("§a▶ 사유: §f" + reason);

        // 진출/재도전 안내 (스테이지 3+ 규칙)
        if (nextStageUnlocked) {
            broadcast("§a§l▶ 괴담을 해결했습니다! 다음 스테이지로 진출할 수 있습니다.");
        } else {
            broadcast("§e§l▶ 생존에 성공했지만 괴담을 완전히 해결하지 못했습니다.");
            broadcast("§e스테이지 " + room + "부터는 §f완전 해결§e만 다음으로 넘어갈 수 있습니다. §7재도전만 가능합니다.");
        }

        // 다음 스테이지로 갈 수 있으면, 평가·보상·특성 선택이 진행되는 동안 다음 시나리오를 미리 생성한다.
        // → /trpg next 시 이미 완료되어 있어 대기 시간이 크게 줄어든다.
        startPregenNext();

        // 해결판정이라도 B 이하면 '퍼펙트'는 과장 — 등급에 맞춰 라벨링(S·A 해결만 퍼펙트).
        String gradeUp = grade == null ? "" : grade.trim().toUpperCase();
        String tierLabel = !resolved ? "생존 클리어"
            : (gradeUp.startsWith("S") || gradeUp.startsWith("A")) ? "퍼펙트 클리어"
            : "해결 클리어";
        String endingLabel = tierLabel + " (등급 " + grade + ")";
        String gdamTheme = getEntityName();

        // 시나리오 평가(플레이어별 등급) → 특성 보상(평가 반영) → 뒷이야기·엔딩 해설
        // ★생존(미해결)으로 재도전만 가능한 경우(nextStageUnlocked=false): 같은 스테이지를 다시 하므로
        //   보상 특성 선택과 전모 공개(핵심 규칙·해결법)를 막는다 — 재플레이 스포일러·미완성 보상 방지.
        boolean advancing = nextStageUnlocked; // 해결했거나(또는 1~2스테이지) 진출 가능 → 전체 공개·보상
        runScenarioEvaluation(finalGrade, playerGrades -> {
            if (advancing) {
                grantClearTraitRewards(grade, gdamTheme, playerGrades);
            } else {
                broadcast("§7(생존 재도전 — 괴담을 완전히 해결하면 보상 특성과 사건의 전모가 공개됩니다.)");
            }
            concludeWithReveal(endingLabel, advancing, null);
        });
    }

    /**
     * 클리어 보상 특성 3선택지를 플레이어별로 생성·표시한다.
     * 표시 등급 상향치(totalBoost) = 오염도 + (시나리오 평가 + 플레이어 평가)의 평균. → '성과'만 표시 등급을 올린다.
     * 시작 약세(weaknessBonus)는 표시 등급이 아니라 '실효 파워'로만 보강한다(보상 등급 인플레 방지).
     */
    private void grantClearTraitRewards(String clearGrade, String gdamTheme, Map<String, String> playerGrades) {
        int scenarioBoost = gradeToBoost(clearGrade);
        // CODE-3: 클리어 시 사망 여부 무관 전원 보상 지급(다음 스테이지=전원 부활). isDead 필터 제거.
        state.getAllPlayers().stream()
            .forEach(playerData -> {
                int weaknessBonus = computeWeaknessBonus(playerData);                 // 시작 약세 (0~5) → 실효 파워에만 반영
                String pGrade     = playerGrades.getOrDefault(playerData.name,
                                    playerGrades.getOrDefault(playerData.charName, "C")); // 이름 우선, 캐릭터명 폴백
                int playerBoost   = gradeToBoost(pGrade);                              // 개인 기여 평가
                // 표시 등급 보정은 '성과(시나리오+기여)'로만. 평범한 회차(C·B)는 0이 되어 자연 성장만 적용된다.
                int perfBoost     = Math.round((scenarioBoost + playerBoost) / 2.0f);
                // 고등급 남발 방지: 표시 등급 상향치를 +2로 제한(오염도 누적·고성과가 겹쳐도 인플레 차단).
                //   약체 보정은 표시 등급이 아닌 '실효 파워'(weaknessBonus)로 따로 들어가므로 진행은 막히지 않는다.
                int totalBoost    = Math.min(2, corruptMan.getLevel() + perfBoost);
                String maxGrade   = maxRewardGrade(state.getRoomNumber(), clearGrade); // 스테이지별 보상 상한
                traitMan.generateStageEndChoices(playerData, gdamTheme, totalBoost, weaknessBonus, maxGrade).thenAccept(choices -> {
                    if (choices == null) return;
                    Player p = Bukkit.getPlayer(playerData.uuid);
                    if (p == null || !p.isOnline()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.sendMessage("§6§l[클리어 보상] 특성 성장을 선택하세요!");
                        if (perfBoost > 0)
                            p.sendMessage("§7(시나리오·기여 성과 보정 +" + perfBoost + "단계)");
                        if (weaknessBonus > 0)
                            p.sendMessage("§7(시작 약세 보정: 표시 등급보다 강한 효과가 깃듭니다)");
                        String srcMyName = choices.myUpgrade() != null && choices.myUpgrade().replacesId != null
                            ? traitMan.getTrait(playerData, choices.myUpgrade().replacesId)
                                      .map(t -> t.name).orElse("") : null;
                        String srcMapName = choices.mapUpgrade() != null && choices.mapUpgrade().replacesId != null
                            ? traitMan.getTrait(playerData, choices.mapUpgrade().replacesId)
                                      .map(t -> t.name).orElse("") : null;
                        dialogMan.showStageEndTraitChoice(p, choices, srcMyName, srcMapName,
                            idx -> handleStageEndTraitSelect(p, playerData, choices, idx));
                        pendingTraitSelect.add(p.getUniqueId());
                        pendingStageEndChoices.put(p.getUniqueId(), choices);
                        pendingStageEndNames.put(p.getUniqueId(), new String[]{srcMyName, srcMapName});
                        p.sendMessage("§8(/trpg trait 으로 선택창을 다시 열 수 있습니다)");
                    });
                });
            });

        if (nextStageUnlocked) {
            broadcast("§6특성을 선택한 뒤 §a/trpg next§6(다음 스테이지) 또는 §f/trpg stop§6(종료)을 진행하세요.");
        } else {
            broadcast("§6특성을 선택한 뒤 §e/trpg retry§6(재도전) 또는 §f/trpg stop§6(종료)을 진행하세요.");
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  시나리오 평가 시스템
    // ──────────────────────────────────────────────────────────────

    /**
     * AI가 각 플레이어의 기여도를 평가하고 한 줄씩 가변 딜레이(1~5초, 글자수 비례)로 채팅에 출력한다.
     * 마지막 출력 5초 뒤 onComplete(플레이어별 총합등급 맵)를 호출한다.
     */
    private void runScenarioEvaluation(String clearGrade, Consumer<Map<String, String>> onComplete) {
        runScenarioEvaluation(clearGrade, false, onComplete);
    }

    /** campaignWide=true면 전 스테이지(campaignLog) 누적 로그로 평가한다(게임 종료 총평용). */
    private void runScenarioEvaluation(String clearGrade, boolean campaignWide, Consumer<Map<String, String>> onComplete) {
        narrativeDelivery.flushAll(); // 결과 표시 전, 천천히 흐르던 잔여 서술을 즉시 비워 결과와 겹침 방지
        broadcast("§8 "); // 시나리오 텍스트와 결과 사이 여백
        List<PlayerData> allPd = new ArrayList<>(state.getAllPlayers());

        StringBuilder playerInfo = new StringBuilder();
        for (PlayerData pd : allPd) {
            playerInfo.append("- ").append(pd.name);
            if (!pd.charName.isEmpty()) playerInfo.append("(").append(pd.charName).append(")");
            playerInfo.append(": ").append(pd.isDead ? "사망" : pd.status);
            playerInfo.append(", HP=").append(pd.hp[0]).append("/").append(pd.hp[1]);
            playerInfo.append(", SAN=").append(pd.san[0]).append("/").append(pd.san[1]);
            if (!pd.roleId.isEmpty()) playerInfo.append(", 배역=").append(pd.roleId);
            playerInfo.append("\n");
        }

        String fullLog = campaignWide ? state.buildCampaignEvalLog() : state.buildFullEvalLog();

        String prompt = "게임 클리어 등급: " + clearGrade + "\n\n"
            + "플레이어 목록:\n" + playerInfo + "\n"
            + "전체 행동 기록:\n" + (fullLog.isBlank() ? "기록 없음" : fullLog) + "\n\n"
            + "각 플레이어를 평가해줘. JSON만 출력. 다른 텍스트 절대 금지.\n\n"
            + "등급 기준(★엄격하게 — S·A는 인색하게): "
            + "S=이번 사건을 사실상 ★캐리★한 결정적·완벽한 활약(좀처럼 안 나옴), "
            + "A=해결에 크게 기여한 뛰어난 활약(흔치 않음), "
            + "B=제 몫을 다한 견실한 기여(대부분의 '잘한' 플레이어의 기본값), "
            + "C=평범·소극적 참여, D=비기여·방해, F=완전 무행동.\n"
            + "★ S·A는 명백한 결정적 근거가 로그에 있을 때만. 애매하거나 '그냥 잘함' 수준이면 한 단계 낮춰 B로 둬라. 기본값은 B다.\n"
            + "★ 총합등급(total)은 오직 실제 기여도로만 판정한다. 사망 여부를 등급 상한으로 삼지 마라.\n"
            + "  사망했더라도 게임을 캐리(핵심 해결·결정적 기여·자기희생)했다면 total이 S/A가 나올 수 있다.\n"
            + "  단 사망·아군NPC사망 등은 해당 행동을 낮은 grade의 item(desc)으로 사실대로 적는다(총합과 별개).\n"
            + "★ 정보 전달 기여를 반드시 반영하라. 직접 행동뿐 아니라 '정보 공유'도 핵심 기여다:\n"
            + "  - [통신] 표시(@연락)로 동료에게 핵심 단서·위치·위험을 넘긴 경우\n"
            + "  - 같은 공간에서 대면 대화·외침(일반 행동/대사)으로 중요한 사실을 알려준 경우\n"
            + "  특히 죽기 직전·결정적 순간에 핵심 정보를 넘겨 팀을 살렸다면 높게 평가한다(A 이상 가능).\n"
            + "  ※ '정보 미공유' 감점 판별 기준 (로그 근거 필수):\n"
            + "    소통불가 면제: 플레이어의 현재 구역에 '[격리: ...]' system 로그 기록이 있으면 그 구간의 미공유는 소통불가로 자동 간주한다. comm 로그에 시도가 없어도 격리 기록만으로 면제 증거로 충분하다.\n"
            + "    고의적 은폐 감점: 격리 기록이 없고 통신 가능 구간임에도 공유하지 않은 경우(comm/log에 격리 기록 없고, 공유 수단·기회가 실존). 플레이어 본인의 주장만으로 면제 처리 금지.\n"
            + "  ★ INSTANT_CLEAR류 즉시 종료(clearGrade=F)로 끝난 회차에서는, 발동자 외 플레이어를 clearGrade=F 이유로 일괄 하향하지 말고 각자의 실제 행동 기록으로만 평가하라. 발동자는 기여를 반영하되 '조기 철수(미해결 종료 유도)'를 낮은 grade의 item으로 적는다(상황상 정당한 철수면 감점 완화).\n"
            + "  ★ 죽음(자살) 평가: 캐릭터의 자발적 죽음은 맥락으로 분류해 평가한다 — ⓐ영웅적 희생(collapse 기여)=캐리 인정·등급 상향 가능(S, 사망 무관). ⓑ전략적 양도(충분히 시도 후 '답 없음' 판단→다음 플레이어에게 양도)=고의 트롤 아님, ★감점·트롤 처리 금지(소극적 기여로 중립 평가). ⓒ무의미·악의 반복 자해만 트롤 행동 item으로 기록. 로그(시도 흔적·타임라인 정황)로 ⓐⓑⓒ를 구분하라.\n"
            + "  ★ '팀 피해를 막지 못함'은 감점하지 마라(트롤링 악용 방지). 평가는 각자의 ★직접 행동★만 본다:\n"
            + "    - 동료·NPC·괴담이 일으킨 피해를 '막지 못했다/구하지 못했다'는 이유로 다른 플레이어를 감점·하향하지 마라.\n"
            + "    - 한 명이 팀을 위험에 빠뜨려도, 그 책임은 그 행위자 본인에게만 묻는다. 주변 플레이어에게 연대책임을 지우지 마라.\n"
            + "    - 감점은 본인이 ★직접★ 가한 피해·고의 방해·명백한 배신(직접 행동 로그 근거)만 낮은 grade item으로 적는다.\n"
            + "role_label 예시: 핵심 해결자, 정보 수집가, 정보 전달자, 팀 지원자, 생존자, 방관자, 사고뭉치, 놀았음, 산화한 영웅\n"
            + "★ growth: 이 플레이어가 ★이번 시나리오 '행동'으로 실제 단련한 스탯★ 1~2개를 str/cha/luk/spr 중에서 고른다(종료 보상 스텟 배분용).\n"
            + "  - 전투·완력·돌파=str / 설득·교섭·연기=cha / 도박·요행·위기모면=luk / 통찰·관찰·정신버팀=spr. 반드시 실제 행동 근거로만 고른다(무행동이면 빈 배열).\n"
            + "player 필드: 위 '플레이어 목록'의 이름(괄호 앞 부분)을 그대로 사용한다. 빠짐없이 전원 평가한다.\n"
            + "★ 단 player 필드는 ★내부 식별자★일 뿐이다 — role_label·desc 등 사람이 읽는 텍스트에는 계정/영문 ID(예: heIp12)를 절대 쓰지 말고 행동만 서술하라(이름이 필요하면 괄호 안 캐릭터명).\n\n"
            + "★ 출력 형식(G20): 플레이어마다 '항목별 평가'를 여러 개 만든다. 각 항목(item)은\n"
            + "  desc='<그 플레이어의 구체적 행동·판단·결과 한 줄>', grade='<S~F>' 이다.\n"
            + "  잘한 행동(S/A)과 못한 행동(D/F)을 섞어서 사실대로 나열하라(보통 2~4개).\n"
            + "  total='<항목들을 종합한 그 플레이어의 총합등급(S~F)>' — 단순 평균이 아니라 경중을 반영한다\n"
            + "  (예: 본질 파악 S라도 잘못된 판단으로 아군 전멸 F면 총합은 D 식으로).\n"
            + "  desc 예시: \"약한 적들을 잔뜩 처리함\", \"합당성 없이 동료를 구속함\", \"괴담의 본질을 파악함\",\n"
            + "            \"해결 방법을 잘못 파악해 아군이 전부 사망함\", \"죽기 직전 핵심 단서를 동료에게 넘김\"\n"
            + "  ※ 감점 요소도 desc 항목(낮은 grade)으로 녹여 쓰되, ★자신의 직접 행동에서 비롯된 것만★ 적는다:\n"
            + "    무행동, 직접적 팀 방해, 고의적 정보 은폐, 자신의 과실로 초래한 본인·아군 사망 등. (남이 낸 피해를 '못 막음'은 감점 금지)\n"
            + "전 플레이어 평가 후 summary_label(팀 서사 총평 한 줄)과 summary_grade(팀 총합등급 S~F)를 낸다.\n"
            + "★ summary_grade는 ★클리어 결과와 정합★해야 한다. 위 '게임 클리어 등급'이 높고(A↑) 괴담을 '해결'로 끝냈다면,\n"
            + "  팀이 협력해 본질을 풀어낸 것이므로 summary_grade를 클리어 등급보다 크게 낮추지 마라(보통 클리어 등급 ±1단계 이내).\n"
            + "  명백한 다수의 트롤·무임승차 등 ★강한 근거★가 있을 때만 더 낮춘다. 채팅에 잡담·비속어·오타가 섞였어도\n"
            + "  그것만으로 감점하지 말고 '실제 결정적 행동(해결 기여·정보 전달·시간 벌기 등)'으로 판정하라.\n"
            + "JSON만 출력. 다른 텍스트 절대 금지.\n\n"
            + "{\n"
            + "  \"evaluations\": [\n"
            + "    {\"player\":\"플레이어이름\",\"role_label\":\"역할명\",\"total\":\"S|A|B|C|D|F\","
            + "\"items\":[{\"desc\":\"구체적 행동/판단/결과\",\"grade\":\"S|A|B|C|D|F\"}],"
            + "\"growth\":[\"str|cha|luk|spr\"]}\n"
            + "  ],\n"
            + "  \"summary_label\": \"종합 한 줄 설명\",\n"
            + "  \"summary_grade\": \"S|A|B|C|D|F\"\n"
            + "}";

        broadcast("§e§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        broadcast("§e§l  📊 시나리오 평가");
        broadcast("§e§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        ai.callGmAiOnce(gmSystemPrompt, prompt)
            .thenAccept(raw -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                EvalResult result = parseEvaluation(raw);
                accrueContribution(result.grades()); // 능력 Phase C: 평가 등급→기여도 누적
                awardEndStats(result.grades(), result.growth()); // 행동 기반 종료 스텟(S=3·A=2·B=0~1)
                // CODE-16: 한 줄씩 가변 딜레이 출력. 줄당 delay = clamp(1초,5초, 글자수/12).
                long accDelay = 0;
                for (String line : result.lines()) {
                    final String out = line;
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> broadcast(out), accDelay);
                    int visibleLen = out.replaceAll("§.", "").length();      // 색코드 제외 길이
                    long lineDelay = Math.max(1L, Math.min(5L, (long)(visibleLen / 12))) * 20L;
                    accDelay += lineDelay;
                }
                long finalDelay = accDelay + 100L; // 마지막 줄 후 5초
                plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> onComplete.accept(result.grades()), finalDelay);
            }));
    }

    /** 시나리오 평가 결과: 채팅 출력 줄(가변 딜레이용) + 플레이어 이름→총합등급 맵 */
    private record EvalResult(List<String> lines, Map<String, String> grades,
                              Map<String, java.util.List<String>> growth) {}

    private EvalResult parseEvaluation(String raw) {
        List<String> lines  = new ArrayList<>();
        Map<String, String> grades = new HashMap<>();
        Map<String, java.util.List<String>> growth = new HashMap<>(); // 행동 기반 성장 스탯(평가가 판단)
        try {
            String json = raw.trim();
            int start = json.indexOf('{');
            int end   = json.lastIndexOf('}');
            if (start < 0 || end <= start) return new EvalResult(lines, grades, growth);
            json = json.substring(start, end + 1);

            JsonObject obj   = JsonParser.parseString(json).getAsJsonObject();
            JsonArray  evals = obj.has("evaluations") ? obj.getAsJsonArray("evaluations") : new JsonArray();

            for (JsonElement el : evals) {
                if (!el.isJsonObject()) continue;
                JsonObject e = el.getAsJsonObject();
                String pName = getStr(e, "player");
                String role  = getStr(e, "role_label");
                // total(신 스키마) 우선, 없으면 grade(구 스키마) 폴백 — playerGrades 호환 유지
                String total = getStr(e, "total");
                if (total.isBlank()) total = getStr(e, "grade");

                // ★ grantClearTraitRewards가 쓰는 이름→총합등급 맵은 반드시 유지한다.
                if (!pName.isBlank() && !total.isBlank()) grades.put(pName, total);

                // 행동 기반 성장 스탯 파싱(str/cha/luk/spr) — 종료 보상 스텟 배분에 사용
                if (!pName.isBlank() && e.has("growth") && e.get("growth").isJsonArray()) {
                    java.util.List<String> gs = new java.util.ArrayList<>();
                    for (JsonElement ge : e.getAsJsonArray("growth")) {
                        String s = ge.getAsString().trim().toLowerCase();
                        if (s.equals("str") || s.equals("cha") || s.equals("luk") || s.equals("spr")) gs.add(s);
                    }
                    if (!gs.isEmpty()) growth.put(pName, gs);
                }

                // 헤더 줄: ★캐릭터명(직업)★ [역할] — 계정명 절대 노출 금지(gmDisplayName 사용)  예) 한소율(프리랜서) [핵심해결자]
                // (pName=계정명은 위 grades/growth 맵의 내부 조회 키로만 쓰고, 화면 표시엔 절대 쓰지 않는다.)
                PlayerData epd = state.getAllPlayers().stream()
                    .filter(p -> p.name.equalsIgnoreCase(pName) || p.charName.equalsIgnoreCase(pName))
                    .findFirst().orElse(null);
                String who;
                if (epd != null) {
                    who = epd.gmDisplayName(); // charName → 직업 → "이름 모를 인물"
                    // 캐릭터명이 있을 때만 직업을 괄호로 부기(charName 없으면 who가 이미 직업이라 중복 방지)
                    if (!epd.charName.isEmpty() && !epd.job.isEmpty() && !"일반인".equals(epd.job))
                        who = who + "(" + epd.job + ")";
                    // ★평가(메타) 화면은 계정명도 함께 표시 — 실제 플레이어 식별용(서술·에필로그와 달리 메타이므로 허용)
                    if (!epd.name.isEmpty()) who = who + " §r§8[" + epd.name + "]";
                } else {
                    who = pName; // 매칭 실패(AI 환각) — 드문 폴백
                }
                lines.add("§f§l" + who + (role.isBlank() ? "" : " §r§7[" + role + "]"));

                // 항목별 등급 줄: "<행동 서술> <등급>"
                if (e.has("items") && e.get("items").isJsonArray()) {
                    for (JsonElement it : e.getAsJsonArray("items")) {
                        if (!it.isJsonObject()) continue;
                        JsonObject io = it.getAsJsonObject();
                        String desc = getStr(io, "desc");
                        String g    = getStr(io, "grade");
                        if (desc.isBlank()) continue;
                        lines.add("§7" + desc + " " + gradeColor(g) + (g.isBlank() ? "" : g) + "§r");
                    }
                } else {
                    // 구 스키마 폴백: description 한 줄
                    String desc = getStr(e, "description");
                    if (!desc.isBlank()) lines.add("§7" + desc);
                }
                // 총합등급 줄
                lines.add("§f총합등급 : " + gradeColor(total) + (total.isBlank() ? "?" : total) + "§r");
                lines.add(""); // 플레이어 사이 빈 줄
            }

            String sumLabel = obj.has("summary_label") ? obj.get("summary_label").getAsString() : "";
            String sumGrade = obj.has("summary_grade") ? obj.get("summary_grade").getAsString() : "";
            if (!sumLabel.isBlank() || !sumGrade.isBlank()) {
                lines.add("§e§l───────────────────────────");
                if (!sumLabel.isBlank()) lines.add("§e종합평가: §f" + sumLabel);
                lines.add("§e총합등급: " + gradeColor(sumGrade) + (sumGrade.isBlank() ? "?" : sumGrade) + "§r");
            }
        } catch (Exception ex) {
            gameLogger.logEvent("평가 파싱 실패: " + ex.getMessage());
        }
        return new EvalResult(lines, grades, growth);
    }

    /** 캠페인 피날레(마지막 스테이지) 룸 번호 — 이 스테이지는 1스테이지 원년 배역으로 복귀해 진행한다. */
    private static final int FINAL_ROOM = 5;

    /** 1스테이지 배역 배정 시 각 플레이어의 캐릭터 정체성을 스냅샷(피날레 복귀용). */
    private void captureOrigChar(PlayerData pd) {
        if (pd == null || pd.hasOrigChar || pd.charName == null || pd.charName.isEmpty()) return;
        pd.hasOrigChar  = true;
        pd.origCharName = pd.charName;
        pd.origGender   = pd.gender;
        pd.origAge      = pd.age;
        pd.origJob      = pd.job;
    }

    /** 피날레: 원년 캐릭터 정체성(이름·성별·나이·직업)으로 복귀. 성장(스탯·특성)은 그대로 유지된다. */
    private void restoreOrigChar(PlayerData pd) {
        if (pd == null || !pd.hasOrigChar) return;
        if (!pd.origCharName.isEmpty()) pd.charName = pd.origCharName;
        if (!pd.origGender.isEmpty())   pd.gender   = pd.origGender;
        if (pd.origAge > 0)             pd.age      = pd.origAge;
        if (!pd.origJob.isEmpty())      pd.job      = pd.origJob;
    }

    /** 피날레 생성용 '복귀 캐스트' 힌트(원년 캐릭터 목록). 스냅샷이 없으면 빈 문자열. */
    private String buildReturningCastHint() {
        StringBuilder sb = new StringBuilder();
        for (PlayerData pd : state.getAllPlayers()) {
            if (!pd.hasOrigChar || pd.origCharName.isEmpty()) continue;
            sb.append("- ").append(pd.origCharName);
            boolean paren = false;
            if (!pd.origGender.isEmpty()) { sb.append(" (").append(pd.origGender); paren = true; }
            if (pd.origAge > 0) { sb.append(paren ? ", " : " (").append(pd.origAge).append("세"); paren = true; }
            if (!pd.origJob.isEmpty()) { sb.append(paren ? ", " : " (").append(pd.origJob); paren = true; }
            if (paren) sb.append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 해당 룸이 피날레면 복귀 캐스트 힌트를, 아니면 null을 돌려준다(생성 시드용). */
    private String castHintFor(int room) {
        if (room != FINAL_ROOM) return null;
        String hint = buildReturningCastHint();
        return hint.isBlank() ? null : hint;
    }

    /**
     * 스테이지별 보상 등급 상한 ★전체 밸런스 너프★. 상한을 낮추면 보상 범위가 F~상한으로 좁아진다
     * (B가 더 자주 나오는 게 아니라 A·S 자체가 안 나옴 = 전반적 하향). 고등급은 후반 + 엄격 평가로만 희소하게.
     * - 1스테이지: 최대 B
     * - 2스테이지: 최대 A
     * - 3스테이지+: S까지 (단 실제로 S/A를 받으려면 평가가 그만큼 뛰어나야 함)
     */
    private String maxRewardGrade(int room, String clearGrade) {
        if (room <= 1) return "B";
        if (room <= 2) return "A";
        return "S";
    }

    /**
     * 등급(F~S)을 '표시 등급 상향 단계'로 변환.
     * 보상 인플레 방지: 평범한 성과(C·B)는 0(자연 성장만), 뛰어난 성과만 가산.
     * S=2, A=1, B 이하=0. (약세 보정은 표시 등급이 아닌 '실효 파워'로 따로 처리한다)
     */
    private int gradeToBoost(String grade) {
        return switch (gradeIdx(grade)) {
            case 6 -> 1;  // S — 완벽한 활약 (고등급 남발 방지로 2→1 하향)
            case 5 -> 1;  // A — 훌륭한 기여
            default -> 0; // B 이하 — 자연 성장만
        };
    }

    /** 등급 문자 → 0(F)~6(S) 인덱스 (F<E<D<C<B<A<S). 불명은 C(3)로 간주 */
    private int gradeIdx(String grade) {
        return switch (grade == null ? "" : grade.toUpperCase()) {
            case "S" -> 6;
            case "A" -> 5;
            case "B" -> 4;
            case "C" -> 3;
            case "D" -> 2;
            case "E" -> 1;
            case "F" -> 0;
            default  -> 3;
        };
    }

    private String gradeColor(String grade) {
        return switch (grade == null ? "" : grade.toUpperCase()) {
            case "S"  -> "§6§l";
            case "A"  -> "§a§l";
            case "B"  -> "§e";
            case "C"  -> "§7";
            case "D"  -> "§c";
            case "F"  -> "§4§l";
            default   -> "§f";
        };
    }

    // ──────────────────────────────────────────────────────────────
    //  엔딩 마무리: 뒷이야기(에필로그) + 엔딩 해설
    // ──────────────────────────────────────────────────────────────

    /**
     * 결말 후 AI 에필로그(뒷이야기)를 생성해 보여주고, 이어서 .gdam 해설을 공개한다.
     * 클리어, '재도전 불가 배드엔딩', '포기(중도 종료)' 시 호출한다. 재도전 가능한 배드엔딩에서는 호출하지 않는다.
     * @param fullReveal true면 사건 전모(정체·세계관규칙·핵심규칙·해결법·타임라인)까지 공개,
     *                   false면 개요+뒷이야기만(생존 재도전 등 — 같은 스테이지 재플레이 스포 방지).
     * @param onDone 에필로그·해설 공개가 끝난 뒤 실행할 콜백 (없으면 null)
     */
    private void concludeWithReveal(String endingLabel, boolean fullReveal, Runnable onDone) {
        String recentLog = state.buildEntityLog(15);
        // CODE-15: '플레이어가 실제로 발견한 것'만 공개하도록 발견 목록을 컨텍스트로 주입.
        StringBuilder discovered = new StringBuilder();
        List<String> clues = state.getDiscoveredClues();
        Set<String>  facts = state.getDiscoveredFacts();
        if ((clues != null && !clues.isEmpty()) || (facts != null && !facts.isEmpty())) {
            discovered.append("\n## 이번 플레이에서 플레이어가 발견한 단서/사실 (이 목록 안의 것만 공개 가능) ★\n");
            if (clues != null) for (String c : clues) if (c != null && !c.isBlank())
                discovered.append("- ").append(c).append("\n");
            if (facts != null) for (String f : facts) if (f != null && !f.isBlank())
                discovered.append("- ").append(f).append("\n");
            discovered.append("위 목록에 없는 정체·약점·해결법·이름은 '못 찾음'으로 두고 단정해 공개하지 마라.\n");
        } else {
            discovered.append("\n## 발견 목록: 플레이어가 확정적으로 알아낸 핵심 사실이 거의 없다.\n")
                      .append("정체·약점·해결법 등은 '끝내 밝혀내지 못했다'는 톤으로, 단정 공개를 피하라.\n");
        }
        String prompt = "게임이 끝났다. 결말 유형: " + endingLabel + ".\n"
            + (recentLog.isBlank() ? "" : "플레이어들의 주요 행동 기록:\n" + recentLog + "\n")
            + discovered
            + "\n이 사건의 '뒷이야기'를 소설풍 에필로그로 써줘. "
            + "★ 통합 엔딩 서술(후일담): 전 플레이어를 '하나의 통합 서사'로 보여준다(개별 후일담 나열 금지). "
            + "메인 해결 주체(들) 중심으로 서술하고, 비(非)주체 캐릭터는 한 줄로 간략히 다룬다. "
            + "미해결 위협·잠복 요소는 열린 결말(재플레이/속편 훅)로 남길 수 있다. "
            + "★ 공개 범위: 이번 플레이에서 플레이어가 실제로 알아낸 사실만 반영한다. 미발견 항목은 단정하지 말고, 불확실하게 파악한 것은 추정형(\"~인 것 같습니다\")으로 표현한다. "
            + "괴담의 이름을 게임 중 알아냈으면 후일담에 반영하고, 못 알아냈으면 이름을 임의로 지어 공개하지 마라. "
            + "남은 인물들의 그 후, 장소의 변화, 여운을 담되 과장 없이. "
            + "★ 등장인물은 반드시 ★캐릭터(배역) 이름★으로만 칭하라 — 플레이어 계정/영문 ID(예: heIp12) 절대 금지. "
            + "★ 내부 시스템 용어(world_rules·collapse_condition·entity·exploit_path 등 필드명) 노출 금지 — 자연스러운 한국어로만. "
            + "제목·마크다운 금지, 대사는 큰따옴표로. ※개별 기여도 평가(등급·하이라이트·감점)는 이 후일담과 별개다.";
        ai.callGmAiOnce(gmSystemPrompt, prompt)
            .thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                String story = ai.stripTags(r);
                if (!story.isBlank()) gameLogger.logGmOutput("전체(뒷이야기)", story);
                List<DialogManager.EndingSection> pages = buildEndingPages(endingLabel, story, fullReveal);
                lastEndingPages = pages;
                broadcast("§e§l📖 엔딩 해설이 공개되었습니다. 다이얼로그를 확인하세요.");
                broadcast("§8(/trpg ending 으로 언제든 다시 열람 가능)");
                for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
                    dialogMan.showEndingDialog(p, pages, 0);
                }
                gameLogger.logEvent("엔딩 해설 공개 (" + endingLabel + ")");
                if (onDone != null) onDone.run();
            }));
    }

    private List<DialogManager.EndingSection> buildEndingPages(String endingLabel, String epilogue, boolean fullReveal) {
        List<DialogManager.EndingSection> pages = new ArrayList<>();
        JsonObject gdam = state.getGdamData();

        // 개요
        List<String> overview = new ArrayList<>();
        overview.add("결말: " + endingLabel);
        overview.add("씨드: " + state.getCurrentSeed());
        pages.add(new DialogManager.EndingSection("개요", overview));

        // 뒷이야기(에필로그) — 다이얼로그는 가운데 정렬이라 한 문단을 통째로 넣으면 '벽글'이 되어 읽기 어렵다.
        // ★한 문장씩★ 줄을 나눠 담아 줄마다 짧게 끊어지게 한다(colorizeEndingLine이 줄별로 서식 적용).
        // 또 너무 길면 다이얼로그 하단이 잘릴 수 있어 일정 문장 수마다 여러 페이지로 나눈다.
        if (!epilogue.isBlank()) {
            List<String> epi = new ArrayList<>();
            for (String s : NarrativeDelivery.toSentenceLines(epilogue))
                if (!s.isBlank()) epi.add(s);
            final int per = 7;
            int total = Math.max(1, (epi.size() + per - 1) / per);
            for (int pi = 0; pi < epi.size(); pi += per) {
                List<String> chunk = new ArrayList<>(epi.subList(pi, Math.min(epi.size(), pi + per)));
                String title = total > 1 ? "뒷이야기 (" + (pi / per + 1) + "/" + total + ")" : "뒷이야기";
                pages.add(new DialogManager.EndingSection(title, chunk));
            }
        }

        // 생존 재도전 등(fullReveal=false): 같은 스테이지를 다시 하므로 전모(정체·규칙·해결법)는 공개하지 않는다.
        if (!fullReveal) {
            pages.add(new DialogManager.EndingSection("안내", List.of(
                "아직 괴담을 완전히 해결하지 못했습니다.",
                "사건의 전모(정체·핵심 규칙·해결법)는",
                "괴담을 §f완전히 해결§7했을 때 공개됩니다.",
                "§7/trpg retry §8로 다시 도전하거나 §7/trpg stop §8으로 종료하세요.")));
            return pages;
        }

        if (gdam == null) return pages;

        JsonObject e = gdam.has("entity") ? gdam.getAsJsonObject("entity") : null;

        // 괴담의 정체
        if (e != null) {
            List<String> identity = new ArrayList<>();
            String name = getStr(e, "name");
            String type = getStr(e, "type");
            if (!name.isBlank()) identity.add("이름: " + name + (type.isBlank() ? "" : " (" + type + ")"));
            if (e.has("ai_context")) {
                String pers = getStr(e.getAsJsonObject("ai_context"), "personality");
                if (!pers.isBlank()) { identity.add(""); identity.add(pers); }
            }
            String scale = getStr(gdam, "scale");
            if (!scale.isBlank()) { identity.add(""); identity.add("스케일: " + scale); }
            if (!identity.isEmpty()) pages.add(new DialogManager.EndingSection("괴담의 정체", identity));
        }

        // 세계관 규칙 (v2) — 이 방을 지배한 법칙과 그 붕괴 조건
        if (gdam.has("world_rules") && gdam.get("world_rules").isJsonObject()) {
            JsonObject wr = gdam.getAsJsonObject("world_rules");
            List<String> wrLines = new ArrayList<>();
            String core = getStr(wr, "core");
            if (!core.isBlank()) wrLines.add(core);
            if (wr.has("details") && wr.get("details").isJsonArray()) {
                for (JsonElement d : wr.getAsJsonArray("details")) wrLines.add("· " + d.getAsString());
            }
            String loophole = getStr(wr, "loophole");
            String collapse = getStr(wr, "collapse_condition");
            if (!loophole.isBlank()) { wrLines.add(""); wrLines.add("── 허점 ──"); wrLines.add(loophole); }
            if (!collapse.isBlank()) { wrLines.add(""); wrLines.add("── 규칙 붕괴(소멸 조건) ──"); wrLines.add(collapse); }
            if (!wrLines.isEmpty()) pages.add(new DialogManager.EndingSection("세계관 규칙", wrLines));
        }

        // 핵심 규칙 + 숨겨진 규칙
        if (e != null) {
            List<String> rules = new ArrayList<>();
            if (e.has("rules")) {
                int i = 1;
                for (JsonElement r : e.getAsJsonArray("rules"))
                    rules.add(i++ + ". " + r.getAsString());
            }
            if (e.has("hidden_rules") && e.getAsJsonArray("hidden_rules").size() > 0) {
                if (!rules.isEmpty()) rules.add("");
                rules.add("── 숨겨진 규칙 ──");
                for (JsonElement hr : e.getAsJsonArray("hidden_rules"))
                    rules.add("▸ " + hr.getAsString());
            }
            if (!rules.isEmpty()) pages.add(new DialogManager.EndingSection("핵심 규칙", rules));
        }

        // 타임라인
        if (gdam.has("timeline")) {
            JsonObject tl = gdam.getAsJsonObject("timeline");
            List<String> timeline = new ArrayList<>();
            for (String k : new String[]{"1", "2", "3", "4"}) {
                if (tl.has(k) && tl.get(k).isJsonObject()) {
                    String eff = getStr(tl.getAsJsonObject(k), "effect");
                    if (!eff.isBlank()) timeline.add("[" + k + "단계] " + eff);
                }
            }
            if (!timeline.isEmpty()) pages.add(new DialogManager.EndingSection("타임라인", timeline));
        }

        // 단서 — G7: 핵심만 짧게 + 발견(◎ 초록)/미발견(○ 주황) 구분. 발견 여부는 GM 자유텍스트와 글자 bigram 겹침으로 판정.
        if (gdam.has("clues") && gdam.getAsJsonArray("clues").size() > 0) {
            JsonArray cluesArr = gdam.getAsJsonArray("clues");
            List<java.util.Set<String>> discBigrams = new ArrayList<>();
            for (String d : state.getDiscoveredClues()) { var b = clueBigrams(d); if (!b.isEmpty()) discBigrams.add(b); }
            for (String d : state.getDiscoveredFacts())  { var b = clueBigrams(d); if (!b.isEmpty()) discBigrams.add(b); }
            List<String> foundLines = new ArrayList<>();
            List<String> missLines  = new ArrayList<>();
            for (JsonElement c : cluesArr) {
                String content, subject;
                if (c.isJsonObject()) {
                    JsonObject co = c.getAsJsonObject();
                    // ★내부 id(clue_1 등)는 절대 노출 금지 — 실제 단서 내용(content) 우선.
                    content = firstNonBlank(getStr(co, "content"), firstNonBlank(getStr(co, "description"), getStr(co, "desc")));
                    subject = getStr(co, "clue_subject");
                } else { content = c.getAsString(); subject = ""; }
                String core = shortenClue(firstNonBlank(content, subject));
                if (core.isBlank()) continue;
                if (clueWasDiscovered(firstNonBlank(content, subject), discBigrams)) foundLines.add("§a◎ " + core);
                else                                                                  missLines.add("§6○ " + core);
            }
            List<String> clueList = new ArrayList<>();
            clueList.add("§7발견 §a" + foundLines.size() + "§7/§f" + (foundLines.size() + missLines.size())
                + "    §a◎ 발견  §6○ 못 찾음");
            clueList.add("");
            clueList.addAll(foundLines);
            clueList.addAll(missLines);
            pages.add(new DialogManager.EndingSection("단서", clueList));
        }

        // 해결법
        if (e != null) {
            List<String> sol = new ArrayList<>();
            String weakness = getStr(e, "weakness");
            String solution = getStr(e, "solution");
            String exploit  = getStr(e, "exploit_path");
            String escape   = getStr(e, "escape");
            if (!weakness.isBlank()) { sol.add("── 약점 ──"); sol.add(weakness); sol.add(""); }
            if (!solution.isBlank()) { sol.add("── 정석 해결법 ──"); sol.add(solution); sol.add(""); }
            if (!exploit.isBlank())  { sol.add("── 역이용 경로 ──"); sol.add(exploit); sol.add(""); }
            if (!escape.isBlank())   { sol.add("── 생존법 ──"); sol.add(escape); }
            if (!sol.isEmpty()) pages.add(new DialogManager.EndingSection("해결법", sol));
        }

        return pages;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String getStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // ── G7: 엔딩 단서 발견 판정(한글 친화 글자 bigram 겹침) + 핵심만 짧게 ──
    /** 문자열을 글자/숫자만 남겨 인접 2글자(bigram) 집합으로. 한글 어미 변형에도 강건. */
    private static java.util.Set<String> clueBigrams(String s) {
        java.util.Set<String> g = new java.util.HashSet<>();
        if (s == null) return g;
        StringBuilder n = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) if (Character.isLetterOrDigit(c)) n.append(c);
        for (int i = 0; i + 1 < n.length(); i++) g.add(n.substring(i, i + 2));
        return g;
    }

    /** 정형 단서(content/subject)가 플레이어가 발견한 자유텍스트들과 충분히 겹치면 발견으로 본다(겹침계수≥0.5, 최소 3 bigram). */
    private static boolean clueWasDiscovered(String clueText, List<java.util.Set<String>> discBigrams) {
        java.util.Set<String> a = clueBigrams(clueText);
        if (a.size() < 3 || discBigrams.isEmpty()) return false;
        for (java.util.Set<String> b : discBigrams) {
            if (b.size() < 3) continue;
            int inter = 0;
            java.util.Set<String> small = a.size() <= b.size() ? a : b, big = small == a ? b : a;
            for (String x : small) if (big.contains(x)) inter++;
            if ((double) inter / small.size() >= 0.5) return true; // 겹침계수(작은 쪽 기준)
        }
        return false;
    }

    /** 단서를 '핵심만' 짧게: 첫 문장 우선, 길면 28자에서 자르고 …. */
    private static String shortenClue(String s) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        int cut = t.length();
        for (String d : new String[]{".", "!", "?", "\n"}) {   // 첫 문장 경계에서 끊어 핵심만
            int idx = t.indexOf(d);
            if (idx > 0) cut = Math.min(cut, idx);
        }
        t = t.substring(0, Math.min(cut, t.length())).strip();
        if (t.length() > 28) t = t.substring(0, 28).strip() + "…";
        return t;
    }

    // ──────────────────────────────────────────────────────────────
    //  서술 개인 전달
    // ──────────────────────────────────────────────────────────────

    /** 행동 플레이어에게 GM 서술 전달 + WITNESS 태그로 주변 플레이어에게 간접 단서 전달 */
    private void deliverNarrative(Player actor, String raw) {
        String narrative = ai.stripTags(raw);
        if (!narrative.isBlank() && actor != null && actor.isOnline()) {
            narrativeDelivery.deliver(actor, narrative);
            gameLogger.logGmOutput(actor.getName(), narrative);
            PlayerData apd = state.getPlayer(actor);
            if (apd != null) {
                appendNarrativeLog(apd, narrative);
                extractAndStoreInfo(narrative, apd);
            }
        }
        ai.parseWitnessTags(raw).forEach((pName, witnessText) -> {
            if (witnessText.isBlank()) return;
            // ★ GM은 메타 은닉 규칙상 WITNESS player="..."에 ★캐릭터명★을 쓴다(계정명 금지).
            //   과거엔 계정명(pd.name)으로만 매칭해, 캐릭터명 WITNESS가 전부 매칭 실패→조용히 버려졌다
            //   (= 같은 구역 동료에게 행동·방송이 전달 안 되던 버그). 이제 계정명·캐릭터명 둘 다 허용한다.
            state.getAllPlayers().stream()
                .filter(pd -> spawnedPlayers.contains(pd.uuid) && matchesPlayerName(pd, pName))
                .findFirst()
                .ifPresent(pd -> {
                    Player target = Bukkit.getPlayer(pd.uuid);
                    if (target != null && target.isOnline()) {
                        narrativeDelivery.deliver(target, witnessText);
                        appendNarrativeLog(pd, witnessText);
                        extractAndStoreInfo(witnessText, pd);
                    }
                    gameLogger.logGmOutput(pName + "(목격)", witnessText);
                });
        });
    }

    /** 태그의 player 이름이 이 플레이어를 가리키는가 — 계정명·캐릭터명 둘 다 허용(공백·대소문자 무시). */
    private static boolean matchesPlayerName(PlayerData pd, String who) {
        if (pd == null || who == null) return false;
        String w = who.trim();
        if (w.isEmpty()) return false;
        if (pd.name != null && pd.name.trim().equalsIgnoreCase(w)) return true;
        return pd.charName != null && !pd.charName.isEmpty() && pd.charName.trim().equalsIgnoreCase(w);
    }

    /**
     * Entity(괴담 현상) 연출을 '짧은 환경 암시 1문장'으로 강제한다.
     * 모델이 규칙을 어기고 여러 문장으로 늘어놓거나 플레이어의 행동을 대신 서술/조종할 때,
     * 첫 한 문장만 취해 장면 충돌·과도한 개입을 코드 차원에서 차단한다.
     */
    private String clampAmbient(String text) {
        if (text == null) return "";
        List<String> sents = NarrativeDelivery.toSentenceLines(text);
        return sents.isEmpty() ? text.trim() : sents.get(0).trim();
    }

    private void appendNarrativeLog(PlayerData pd, String text) {
        synchronized (pd.narrativeLog) {
            pd.narrativeLog.add(text.trim());
            if (pd.narrativeLog.size() > PlayerData.NARRATIVE_LOG_MAX)
                pd.narrativeLog.remove(0);
        }
    }

    private void extractAndStoreInfo(String narrative, PlayerData pd) {
        if (narrative.isBlank()) return;
        // P57: 같은 대상(인물/사물/사건)별로 단서를 묶어 기록한다.
        // 각 줄을 '대상|단서' 또는 '[대상] 단서' 형식으로 받아 subject별로 그룹화한다.
        String task = "아래 TRPG 서술에서 정보가 담긴 내용만 추출해줘.\n"
            + "포함: NPC 발언, 관찰·발견, 독백, 추론 단서\n"
            + "제외: 분위기 서술, 이동 서술, 결과 없는 행동 묘사\n"
            + "★ 같은 대상(인물/사물/사건)에 대한 단서는 하나로 묶어라.\n"
            + "★ 출력 형식: 한 줄에 하나씩, '대상|단서' 또는 '[대상] 단서' 형식으로.\n"
            + "  (예: '관리인|밤마다 지하실에 내려간다' 또는 '[붉은 문] 손잡이가 차갑다')\n"
            + "  대상을 특정하기 어려운 일반 정보는 그냥 단서 내용만 한 줄로.\n"
            + "정보가 없으면 '없음'만. 있으면 위 형식으로 한 줄씩 (최대 3줄).";
        ai.callAssistant(task, narrative).thenAccept(result -> {
            if (result == null || result.isBlank()) return;
            for (String line : result.split("\n")) {
                String clean = line.trim();
                if (clean.isEmpty() || clean.equals("없음")) continue;
                // 선행 불릿·기호 제거 (모델이 습관적으로 붙이는 '•', '-' 등)
                clean = clean.replaceFirst("^[•\\-*]+\\s*", "").trim();
                if (clean.isEmpty()) continue;
                String subject = null;
                String body    = clean;
                // '[대상] 내용' 형식
                if (clean.startsWith("[")) {
                    int close = clean.indexOf(']');
                    if (close > 1) {
                        subject = clean.substring(1, close).trim();
                        body    = clean.substring(close + 1).trim();
                    }
                }
                // '대상|내용' 형식 (위에서 못 잡았을 때만)
                if (subject == null) {
                    int bar = clean.indexOf('|');
                    if (bar > 0) {
                        subject = clean.substring(0, bar).trim();
                        body    = clean.substring(bar + 1).trim();
                    }
                }
                if (body.isEmpty()) continue;
                // 형식 불명(대상 분리 실패)이면 '단서' 그룹으로 폴백
                if (subject == null || subject.isEmpty()) subject = "단서";
                pd.addInfo(subject, body); // infoGroups(정보모음) 기록 — 조종 중에도 기록 자체는 유지
                // G10: 예전엔 조종 중 keyFacts(핵심정보)에 "[조종 중] …"로도 등록해 핵심정보가 오염됐다 → 그 등록만 제거.
            }
        });
    }

    /** 기록 다이얼로그 — 전체 대화 / 정보만 선택 화면 (기록 아이템 우클릭 · /trpg log·info) */
    public void openRecords(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) { player.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        dialogMan.showRecordChoice(player, pd);
    }

    /** 전체 대화 기록 다이얼로그로 바로 열기 */
    public void openRecordLog(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) { player.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        dialogMan.showRecordLog(player, pd);
    }

    /** 수집 정보 기록 다이얼로그로 바로 열기 */
    public void openRecordInfo(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) { player.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        dialogMan.showRecordInfo(player, pd);
    }

    /** 중요 정보(전화번호·능력으로 밝힌 사실) 다이얼로그 — 전화번호는 실시간 합성, 사실은 keyFacts 스냅샷 */
    public void openImportantInfo(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) { player.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        List<String> phones = buildPhoneLines(pd);
        List<String> facts;
        synchronized (pd.keyFacts) { facts = new ArrayList<>(pd.keyFacts); }
        dialogMan.showImportantInfo(player, pd, pd.contactId, phones, facts);
    }

    /** STATE_UPDATE의 정수 필드 안전 추출(없으면 0). */
    private static int suInt(JsonObject o, String k) {
        try { return (o != null && o.has(k) && o.get(k).isJsonPrimitive()) ? o.get(k).getAsInt() : 0; }
        catch (Exception e) { return 0; }
    }
    /** STATE_UPDATE에 실제 새 단서가 담겼는가(진전 판정용). */
    private static boolean suHasClue(JsonObject o) {
        try { return o != null && o.has("new_clue") && o.get("new_clue").isJsonPrimitive()
            && !o.get("new_clue").getAsString().isBlank(); }
        catch (Exception e) { return false; }
    }

    /** 해당 플레이어의 서술 출력이 모두 끝난 뒤(큐가 빌 때) 콜백을 1회 실행한다(최대 ~60초 안전장치). */
    private void afterNarrationIdle(Player player, Runnable cb) {
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!player.isOnline() || !isActive()) { cancel(); return; }
                if (!narrativeDelivery.hasPending(player)) { cancel(); cb.run(); return; }
                if (++ticks > 120) cancel(); // 0.5s×120 ≈ 60초 안전장치
            }
        }.runTaskTimer(plugin, 20L, 10L); // 1초 뒤부터 0.5초 간격 폴링
    }

    /**
     * 스포일러 없는 추천 행동 — 괴담의 정답(숨은 규칙·약점·정체)을 ★전혀 모르는★ 보조 AI가
     * '플레이어가 실제로 보고 겪은 것'만 보고 다음에 해볼 만한 행동 2~3개를 제안한다.
     * gdam(entity/world_rules/solution 등)은 일절 넘기지 않으므로 정답 누설이 구조적으로 불가능.
     */
    public void showRecommendations(Player player) {
        if (!isActive()) { player.sendMessage("§c게임 중이 아닙니다."); return; }
        PlayerData pd = state.getPlayer(player);
        if (pd == null) { player.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        if (pd.isDead) { player.sendMessage("§7관전 중에는 추천을 받을 수 없습니다."); return; }
        if (!spawnedPlayers.contains(player.getUniqueId())) { player.sendMessage("§7아직 등장 전입니다."); return; }

        // 스포일러 없는 컨텍스트 = 플레이어가 이미 본/아는 것만 (gdam 비밀 일절 미포함)
        List<String> seen = new ArrayList<>();
        synchronized (pd.narrativeLog) {
            for (String e : pd.narrativeLog) {
                if (e == null || e.isBlank() || e.startsWith(PlayerData.MOVE_TAG)) continue;
                seen.add(e.length() > 200 ? e.substring(0, 200) + "…" : e);
            }
        }
        if (seen.size() > 12) seen = seen.subList(seen.size() - 12, seen.size());

        StringBuilder ctx = new StringBuilder("내가 겪은 최근 상황(시간순):\n");
        for (String s : seen) ctx.append("- ").append(s).append("\n");
        ctx.append("현재 위치: ").append(pd.zone == null || pd.zone.isEmpty() ? "?" : zoneDisplayName(pd.zone)).append("\n");
        if (!pd.traits.isEmpty()) {
            StringBuilder tn = new StringBuilder();
            for (TraitData t : pd.traits) { if (tn.length() > 0) tn.append(", "); tn.append(t.name); }
            ctx.append("내가 가진 특성(능력): ").append(tn).append("\n");
        }
        // 지금 가진 물건(소지품) — 추리 재료
        if (!pd.heldItemIds.isEmpty())
            ctx.append("지금 가진 물건: ").append(String.join(", ", new ArrayList<>(pd.heldItemIds))).append("\n");
        // 수집한 단서(주제별) — 추리 재료
        StringBuilder clues = new StringBuilder();
        for (java.util.Map.Entry<String, List<String>> en : pd.infoGroups.entrySet()) {
            if (en.getValue() == null || en.getValue().isEmpty()) continue;
            if (clues.length() > 0) clues.append(" / ");
            clues.append(en.getKey()).append(": ").append(String.join("; ", en.getValue()));
        }
        if (clues.length() > 0) ctx.append("내가 수집한 단서: ").append(clues).append("\n");
        List<String> rFacts;
        synchronized (pd.keyFacts) { rFacts = new ArrayList<>(pd.keyFacts); }
        if (!rFacts.isEmpty()) ctx.append("내가 밝혀낸 사실: ").append(String.join("; ", rFacts)).append("\n");

        String task = "너는 이 인물과 함께 처한 ★평범한 동료★다. 사건의 숨은 진실·괴담의 정체·정답은 ★전혀 모른다★.\n"
            + "아래의 ①지금까지 겪은 행동·상황 ②수집한 단서 ③지금 가진 물건 ④능력(특성)을 ★종합해 추리하듯★, "
            + "이 인물이 다음에 ★해볼 만한 행동 하나★를 제안하라.\n"
            + "① 단서·물건·능력을 실제로 ★활용·연결★하는 행동이면 더 좋다(예: 가진 열쇠로 잠긴 문을 열어 본다 / 들은 이름을 아는 사람에게 물어본다 / 주운 쪽지에 적힌 곳으로 가 본다).\n"
            + "② 반드시 지금 이야기 흐름에 맞는 구체적 행동 — 등장한 사람·장소·물건·소리를 직접 가리켜라. '주변을 둘러본다'·'상황을 파악한다' 같은 막연·메타 제안 금지.\n"
            + "③ 괴담의 정체·약점·정답을 아는 척 단정 금지(너도 모른다). '~는 안전/위험' 확신 금지 — '해볼 만한 시도'로만.\n"
            + "④ ★출력 형식★: 이 인물의 1인칭 속마음(독백)을 `<-#...->`로 감싼 ★단 한 줄만★. 예) <-#주머니의 열쇠로 저 문을 열어볼까?->\n"
            + "⑤ 20자 안팎 한 줄. 번호·머리표·해설·인사·여러 줄 전부 금지(오직 <-#...-> 한 줄).";

        // 헤더·여러 제안 없이, 추천 행동 ★단 한 줄★만 1인칭 속마음 <-#...-> 으로 표시한다(실패 시 조용히).
        ai.callAssistant(task, ctx.toString()).thenAccept(resp ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (resp == null || resp.isBlank() || resp.startsWith("§c")) return; // 실패 시 아무것도 표시 안 함
                for (String line : resp.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    t = t.replaceFirst("^\\s*(?:\\d+[.)]|[-•*])\\s*", "").trim(); // 번호·불릿 제거
                    if (t.isEmpty()) continue;
                    if (!t.startsWith("<-")) {            // 형식 보정: <-#...-> 로 감싸 회색 독백 표시
                        t = t.replaceFirst("^#\\s*", "");
                        t = "<-#" + t + "->";
                    }
                    player.sendMessage(" " + NarrativeDelivery.format(t));
                    return; // ★첫 한 줄만 표시★ — 나머지는 표시하지 않음
                }
            }));
    }

    /** 아는 전화번호 목록(플레이어 + NPC)을 실시간으로 합성한다. 각 번호 뒤에 관계를 표기한다. (다이얼로그 렌더는 §코드 미해석 → 평문) */
    private List<String> buildPhoneLines(PlayerData pd) {
        List<String> out = new ArrayList<>();
        for (UUID u : pd.knownContacts) {
            PlayerData other = state.getPlayer(u);
            if (other == null || other.contactId.isEmpty()) continue;
            String rel = relationshipLabel(pd.roleId, other.roleId);
            out.add(commDisplayName(other) + " — " + other.contactId
                + (rel.isBlank() ? "" : " [" + rel + "]"));
        }
        for (String npcId : pd.everKnownNpcContacts) {
            JsonObject npc = findNpcById(npcId);
            if (npc == null) continue; // 이번 스테이지에 없는 NPC(이월 잔재)는 표시하지 않음
            String nm  = npc.has("name") ? npc.get("name").getAsString() : npcId;
            String num = npcContactNumber(npcId);
            String rel = relationshipLabel(pd.roleId, npcId);
            String relTag = rel.isBlank() ? "" : ", " + rel;
            out.add(num.isBlank() ? nm + " (NPC" + relTag + ")"
                                  : nm + " — " + num + " (NPC" + relTag + ")");
        }
        return out;
    }

    /** 통신 기기 아이템 lore의 '연락' 섹션 헤더(갱신 시 이 줄부터 끝까지 교체) */
    private static final String COMM_SECTION_HEADER = "연락처";

    /**
     * 플레이어가 든 통신 기기(전화·무전기 등) 아이템의 lore에 '연락법 + 아는 연락처'를 갱신한다.
     * 연락처가 추가·변경되면 다시 호출해 아이템 표기를 최신화한다. (인벤토리 변경 → 메인 스레드)
     */
    private void refreshCommItems(PlayerData pd) {
        if (pd == null) return;
        Player p = Bukkit.getPlayer(pd.uuid);
        if (p == null || !p.isOnline()) return;
        if (Bukkit.isPrimaryThread()) doRefreshCommItems(p, pd);
        else plugin.getServer().getScheduler().runTask(plugin, () -> doRefreshCommItems(p, pd));
    }

    private void doRefreshCommItems(Player p, PlayerData pd) {
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            ItemMeta meta = it.getItemMeta();
            String name = meta.hasDisplayName() ? plain.serialize(meta.displayName()).toLowerCase() : "";
            boolean isComm = false;
            for (String kw : COMM_ITEM_KEYWORDS) if (name.contains(kw)) { isComm = true; break; }
            if (!isComm) continue;

            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            // 기존 '연락' 섹션(헤더부터 끝까지) 제거 후 새로 채움 — 중복 누적 방지
            int hdr = -1;
            for (int i = 0; i < lore.size(); i++)
                if (plain.serialize(lore.get(i)).contains(COMM_SECTION_HEADER)) { hdr = i; break; }
            if (hdr >= 0) lore = new ArrayList<>(lore.subList(0, hdr));

            lore.add(Component.text("§b── " + COMM_SECTION_HEADER + " ──"));
            lore.add(Component.text("§7연락법: §f채팅에 §e@이름 §7또는 §e@번호"));
            lore.add(Component.text("§7내 번호: §f" + (pd.contactId.isEmpty() ? "미발급" : pd.contactId)));
            List<String> phones = buildPhoneLines(pd);
            if (phones.isEmpty()) lore.add(Component.text("§8아는 번호 없음 (연락 성공 시 자동 등록)"));
            else for (String ph : phones) lore.add(Component.text("§f• " + ph));

            meta.lore(lore);
            it.setItemMeta(meta);
        }
    }

    /** critical NPC 목록에서 id로 NPC JsonObject 검색(없으면 null). */
    private JsonObject findNpcById(String npcId) {
        if (npcId == null || npcId.isBlank()) return null;
        for (JsonObject npc : getCriticalNpcs()) {
            String id = npc.has("id") ? npc.get("id").getAsString() : "";
            if (npcId.equalsIgnoreCase(id)) return npc;
        }
        return null;
    }

    /** 중요 NPC들에게 런타임 연락처 번호를 부여한다(플레이어·타 NPC 번호와 중복 회피). .gdam에 번호가 있으면 우선 사용. */
    private void assignNpcContactIds() {
        for (JsonObject npc : getCriticalNpcs()) {
            String id = getStr(npc, "id");
            if (id.isEmpty() || npcContactNumbers.containsKey(id)) continue;
            String pre = npc.has("contact") ? npc.get("contact").getAsString()
                       : npc.has("phone_number") ? npc.get("phone_number").getAsString()
                       : npc.has("phone") ? npc.get("phone").getAsString() : "";
            npcContactNumbers.put(id, (pre != null && pre.matches("\\d{3,5}")) ? pre : generateContactId());
        }
    }

    /** NPC 연락처 번호(런타임 맵). 없으면 "". */
    private String npcContactNumber(String npcId) {
        if (npcId == null) return "";
        return npcContactNumbers.getOrDefault(npcId, "");
    }

    /** 번호로 중요 NPC를 찾는다(없으면 null). */
    private JsonObject findNpcByContactNumber(String num) {
        if (num == null || num.isBlank()) return null;
        for (var e : npcContactNumbers.entrySet())
            if (num.equals(e.getValue())) return findNpcById(e.getKey());
        return null;
    }

    /** viewer 배역(roleId)과 상대(다른 배역 roleId 또는 NPC id) 사이의 .gdam relationship. 없으면 null. */
    private JsonObject findRelationship(String roleIdA, String idB) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("relationships")
            || roleIdA == null || roleIdA.isEmpty() || idB == null || idB.isEmpty()) return null;
        for (var el : gdam.getAsJsonArray("relationships")) {
            if (!el.isJsonObject()) continue;
            JsonObject rel = el.getAsJsonObject();
            if (!rel.has("roles")) continue;
            boolean a = false, b = false;
            for (var r : rel.getAsJsonArray("roles")) {
                String rid = r.getAsString();
                if (rid.equalsIgnoreCase(roleIdA)) a = true;
                if (rid.equalsIgnoreCase(idB))     b = true;
            }
            if (a && b) return rel;
        }
        return null;
    }

    /** 관계 라벨(짧게) — type 우선, 없으면 description 앞부분. 관계가 없으면 "". */
    private String relationshipLabel(String roleIdA, String idB) {
        JsonObject rel = findRelationship(roleIdA, idB);
        if (rel == null) return "";
        String type = getStr(rel, "type").replace('_', ' ').trim();
        if (!type.isBlank()) return type;
        String desc = getStr(rel, "description").trim();
        return desc.length() > 14 ? desc.substring(0, 14) + "…" : desc;
    }

    /** roleId(또는 NPC id) → 표시 이름(플레이어 캐릭터명 우선, NPC면 NPC명, 둘 다 아니면 원문). */
    private String roleDisplayName(String roleId) {
        if (roleId == null || roleId.isEmpty()) return "";
        for (PlayerData pd : state.getAllPlayers())
            if (roleId.equalsIgnoreCase(pd.roleId)) return pd.gmDisplayName();
        JsonObject npc = findNpcById(roleId);
        if (npc != null && npc.has("name")) return npc.get("name").getAsString();
        return roleId;
    }

    /**
     * 특성으로 플레이어에게만 보여주는 부가 정보. '#' 접두 + 주황색으로 통일 표시한다.
     * record=true면 '중요 정보'(keyFacts)에도 남겨 기록 GUI에서 다시 확인할 수 있다.
     */
    private void traitReveal(Player p, PlayerData pd, String text, boolean record) {
        if (text == null) return;
        String clean = text.replaceAll("§.", "").trim();
        if (clean.isEmpty()) return;
        // 헤더([능력명])와 본문 분리 — 표시·로깅 공통.
        String header = "능력 결과", body = clean;
        if (clean.startsWith("[")) {
            int end = clean.indexOf(']');
            if (end > 0) { header = clean.substring(1, end).trim(); body = clean.substring(end + 1).trim(); }
        }
        if (p != null && p.isOnline()) {
            // 색·서식으로 강조 — 일반 서술과 확실히 구분되게 표시
            p.sendMessage("§6§m                                        §r");
            p.sendMessage("§6§l✦ " + header + " §6✦");
            p.sendMessage("§e" + body);
            p.sendMessage("§8§o└ 능력으로 알아낸 정보" + (record ? " (기록에 저장됨)" : "") );
            p.sendMessage("§6§m                                        §r");
        }
        if (record && pd != null) pd.addKeyFact(clean);
        gameLogger.logAbilityResult(pd != null ? pd.gmDisplayName() : (p != null ? p.getName() : ""), header, body);
    }

    /**
     * 이 특성을 선택/강화하면 체력 또는 정신력 최대치가 1 이하로 떨어지는가.
     * 강화(replacesId)는 원본 보정 제거분을 먼저 반영해 순증감으로 판정한다.
     */
    private boolean traitDropsVitalsTooLow(PlayerData pd, TraitData t) {
        if (pd == null || t == null) return false;
        int hpMax = pd.hp[1], sanMax = pd.san[1];
        if (t.replacesId != null) { // 강화: 원본 스탯 보정을 제거한 뒤 새 보정 적용
            TraitData orig = pd.traits.stream().filter(x -> x.id.equals(t.replacesId)).findFirst().orElse(null);
            if (orig != null) { hpMax -= orig.hp_max_add; sanMax -= orig.san_max_add; }
        }
        hpMax  += t.hp_max_add;
        sanMax += t.san_max_add;
        return hpMax <= 1 || sanMax <= 1;
    }

    // ──────────────────────────────────────────────────────────────
    //  배역 등장 처리
    // ──────────────────────────────────────────────────────────────

    private void handleSpawn(String playerName) {
        state.getAllPlayers().stream()
            .filter(pd -> !spawnedPlayers.contains(pd.uuid)
                       && (pd.name.equalsIgnoreCase(playerName)
                           || (!pd.charName.isEmpty() && pd.charName.equals(playerName))))
            .findFirst()
            .ifPresent(pd -> {
                spawnedPlayers.add(pd.uuid);
                gameLogger.logPrivate(pd.name, "배역 등장 [" + pd.gmDisplayName() + "]");
                Player p = Bukkit.getPlayer(pd.uuid);
                if (p == null || !p.isOnline()) return;
                p.sendMessage("§e§l[등장] 당신의 배역이 이야기에 들어섰습니다. 이제 행동할 수 있습니다.");
            });
    }

    /** spawn_timeline 문자열 → 등장 단계.
     *  0 = 시작 즉시(시작부터 등장), 1~N = 해당 타임라인 단계 도달 시 등장(N=규모별 가변, CODE-17).
     *  "타임라인 N단계"처럼 숫자+단계가 있으면 그 단계로, 그 외(즉시·빈값·모호·인식불가)는
     *  모두 0(시작 즉시)으로 처리한다 — 변형 표기로 '영영 등장 못 하는' 림보를 원천 차단. */
    private int parseSpawnStage(String spawnTimeline) {
        if (spawnTimeline == null) return 0;
        String s = spawnTimeline.trim();
        if (s.isEmpty()) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*단계").matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return Math.min(n, state.getMaxStage()); // 단계 수 상한으로 클램프
        }
        return 0; // 즉시류·모호·인식불가 → 시작 즉시 (림보 방지)
    }

    /** spawn_timeline → 등장 필요 타임라인 단계. 0이면 시작 즉시(대기 없음). */
    private int getSpawnStageRequired(String roleId) {
        JsonObject r = findRoleData(roleId);
        if (r == null || !r.has("spawn_timeline")) return 0;
        return parseSpawnStage(r.get("spawn_timeline").getAsString());
    }

    /** 현재 타임라인 단계에 도달한 대기 배역을 자동 등장시킨다 */
    private void checkAndAutoSpawn() {
        if (state.isDailyPhase()) return; // 일상 파트에선 자동 등장 없음
        int stage = state.getTimelineStage();
        state.getAllPlayers().stream()
            .filter(pd -> !pd.isDead && !spawnedPlayers.contains(pd.uuid))
            .forEach(pd -> {
                int required = getSpawnStageRequired(pd.roleId);
                if (required > 0 && stage >= required) {
                    handleSpawn(pd.name);
                    // 자동 등장 시 GM AI에 맥락 주입 (계정명 미노출)
                    String display = pd.gmDisplayName();
                    JsonObject r = findRoleData(pd.roleId);
                    String loc = (r != null && r.has("spawn_location")) ? r.get("spawn_location").getAsString() : "";
                    ai.injectGmSystem("[자동 등장] " + display + "이(가) 이야기에 합류했다."
                        + (loc.isEmpty() ? "" : " 위치: " + loc));
                }
            });
    }

    // ──────────────────────────────────────────────────────────────
    //  미등장 배역 자동 서술
    // ──────────────────────────────────────────────────────────────

    // 비트 2개당 1단계 진행 (비트 0→1: 2회 호출, 1→2: 4회, ...)
    private static final int CALLS_PER_BEAT = 2;

    private void sendPreSpawnNarrative(Player p, PlayerData pd) {
        JsonObject roleData = findRoleData(pd.roleId);
        if (roleData == null) return;

        // 호출 횟수 증가 → 비트 인덱스 산출
        int callCount = preSpawnCallCounts.merge(pd.uuid, 1, Integer::sum) - 1;
        List<String> beats = new ArrayList<>();
        if (roleData.has("pre_spawn_beats")) {
            roleData.getAsJsonArray("pre_spawn_beats")
                .forEach(b -> beats.add(b.getAsString()));
        }

        String beatGuide;
        if (beats.isEmpty()) {
            // pre_spawn_beats 없는 구형 gdam — 기본 가이드로 대체
            beatGuide = switch (callCount) {
                case 0 -> "배역의 일상 시작 장면. 평범한 하루의 시작.";
                case 1 -> "무언가 계기가 생겨 외출하거나 움직임을 결심한다.";
                case 2 -> "이동 중이거나 목적지로 접근하는 장면.";
                default -> "합류 직전 — 목적지 근처에서 이상한 것을 목격하거나 단서를 발견한다.";
            };
        } else {
            int beatIdx = Math.min(callCount / CALLS_PER_BEAT, beats.size() - 1);
            beatGuide = beats.get(beatIdx);
        }

        String spawnLoc = roleData.has("spawn_location")
            ? roleData.get("spawn_location").getAsString() : "";
        boolean hasKnowledgeAdv = roleData.has("knowledge_advantage")
            && roleData.get("knowledge_advantage").getAsBoolean();
        String phase = state.isDailyPhase()
            ? "일상 " + state.getDailyTurnsLeft() + "턴 남음"
            : "괴담 " + state.getTimelineStage() + "단계";

        // 배역 독점 정보 (마지막 비트나 knowledge_advantage일 때 활용)
        List<String> hiddenInfo = new ArrayList<>();
        if (roleData.has("hidden_info")) {
            roleData.getAsJsonArray("hidden_info").forEach(h -> hiddenInfo.add(h.getAsString()));
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("## 미등장 배역 서술 요청\n");
        prompt.append("아직 이야기에 합류하지 않은 ").append(pd.name)
              .append("(").append(pd.age).append("세, ").append(pd.job).append(")의\n");
        prompt.append("현재 순간을 2인칭 2~3문장으로 서술한다.\n\n");
        prompt.append("### 현재 장면 가이드\n").append(beatGuide).append("\n\n");
        if (!spawnLoc.isEmpty()) {
            prompt.append("### 합류 예정 장소\n").append(spawnLoc).append("\n\n");
        }
        if (hasKnowledgeAdv && !hiddenInfo.isEmpty()) {
            // 마지막 비트(또는 비트 없이 3회 이상)에만 단서 포함
            boolean isLastBeat = beats.isEmpty()
                ? callCount >= 3
                : (callCount / CALLS_PER_BEAT) >= beats.size() - 1;
            if (isLastBeat) {
                prompt.append("### 배역 독점 단서 (이 장면에 자연스럽게 녹여낼 것)\n");
                hiddenInfo.forEach(h -> prompt.append("- ").append(h).append("\n"));
                prompt.append("\n");
            }
        }
        prompt.append("### 제약\n");
        prompt.append("- 괴담·사건 직접 언급 금지 (간접 암시만 허용)\n");
        prompt.append("- 스탯·특성 수치 언급 금지\n");
        prompt.append("- 서술은 현재 시제, 2인칭 (당신은 ...)\n");
        prompt.append("- ").append(phase).append(" 시점\n");
        prompt.append("- 이전과 다른 장면·행동·감정으로 변화를 보여줄 것\n");

        ai.callGmAiOnce(gmSystemPrompt, prompt.toString())
            .thenAccept(resp -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                String trimmed = ai.stripTags(resp).trim();
                if (trimmed.isBlank() || trimmed.startsWith("§c")) return;
                // deliver() 내부에서 format()이 호출되므로 여기서 중복 호출하지 않는다
                narrativeDelivery.deliver(p, trimmed);
                gameLogger.logGmOutput(p.getName() + "(대기)", trimmed);
            }));
    }

    /** role_id로 gdam 배역 JsonObject 반환. 없으면 null. */
    private JsonObject findRoleData(String roleId) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles") || roleId == null || roleId.isEmpty()) return null;
        for (JsonElement el : gdam.getAsJsonArray("roles")) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("role_id") && r.get("role_id").getAsString().equals(roleId)) return r;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private String buildPreSpawnContext(PlayerData pd) {
        JsonObject r = findRoleData(pd.roleId);
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("배역: ").append(r.has("name") ? r.get("name").getAsString() : pd.roleId).append("\n");
        sb.append("위치: ").append(r.has("spawn_location") ? r.get("spawn_location").getAsString() : "알 수 없음").append("\n");
        if (r.has("spawn_timeline")) sb.append("등장 예정: ").append(r.get("spawn_timeline").getAsString()).append("\n");
        if (r.has("initial_info")) {
            sb.append("초기 정보: ");
            List<String> list = new ArrayList<>();
            r.getAsJsonArray("initial_info").forEach(i -> list.add(i.getAsString()));
            sb.append(String.join(" / ", list)).append("\n");
        }
        if (r.has("hidden_info")) {
            sb.append("배역 독점 정보: ");
            List<String> list = new ArrayList<>();
            r.getAsJsonArray("hidden_info").forEach(i -> list.add(i.getAsString()));
            sb.append(String.join(" / ", list)).append("\n");
        }
        if (r.has("knowledge_advantage") && r.get("knowledge_advantage").getAsBoolean()) {
            sb.append("늦게 등장하는 대신 이미 중요한 정보를 보유하고 있다.\n");
        }
        return sb.toString();
    }

    private boolean isImmediateSpawn(String roleId) {
        JsonObject r = findRoleData(roleId);
        if (r == null || !r.has("spawn_timeline")) return true;
        return parseSpawnStage(r.get("spawn_timeline").getAsString()) == 0;
    }

    // ──────────────────────────────────────────────────────────────
    //  Entity AI 헬퍼
    // ──────────────────────────────────────────────────────────────

    private String buildEntitySystemPrompt() {
        JsonObject gdam = state.getGdamData();
        String envRule =
            "너는 괴담의 '결과'를 환경 현상으로 묘사하는 연출 보조다.\n"
          + "★ 절대 규칙:\n"
          + "- 괴담의 이름·정체·동기를 절대 언급하지 마라.\n"
          + "- 1인칭('나는...') 금지. 괴담의 내면·시점·감각·의도를 직접 서술 금지.\n"
          + "- 괴담이 직접 말하거나 메시지를 보내는 형식 금지.\n"
          + "- ★플레이어의 이름을 부르거나 3인칭('○○가 …한다')으로 플레이어를 지칭하지 마라.\n"
          + "- ★플레이어의 행동·이동·선택·대사를 대신 서술하거나 강제(조종)하지 마라 — 그건 GM의 몫이다.\n"
          + "  지금 플레이어가 하고 있는 행동과 충돌하는 장면(가던 길이 꺾인다 등)을 만들지 마라.\n"
          + "- 오직 플레이어가 감각으로 인지할 수 있는 물리적 현상·환경 이상만 묘사.\n"
          + "  (소리·냄새·온도·그림자·사물의 미세한 변화 등)\n"
          + "- ★단 한 문장. 주어 없는 2인칭 관찰자 시점(환경만). 한국어. 따옴표·제목·머리기호 금지.\n"
          + "좋은 예: \"복도 끝 형광등이 한 박자 늦게 깜빡인다.\"\n"
          + "나쁜 예: \"[괴담] 나는 너에게 다가간다.\" (1인칭·정체 노출 — 금지)\n"
          + "나쁜 예: \"○○는 발걸음이 자꾸 집으로 꺾인다.\" (플레이어 지칭·행동 조종 — 금지)\n";
        String intensity = buildEntityIntensityGuide();
        if (gdam == null || !gdam.has("entity")) return envRule + intensity;

        JsonObject entity = gdam.getAsJsonObject("entity");
        StringBuilder sb = new StringBuilder(envRule).append(intensity);
        sb.append("플레이어 스탯·특성·해결법을 절대 직접 언급 금지.\n");
        if (entity.has("ai_context")) {
            JsonObject ctx = entity.getAsJsonObject("ai_context");
            // 성격/패턴은 내부 참고용일 뿐, 출력에 직접 노출하지 말 것
            if (ctx.has("disposition") && !ctx.get("disposition").getAsString().isBlank())
                sb.append("[내부 참고] 괴담 성격(성향): ").append(ctx.get("disposition").getAsString())
                  .append(" — 이 성향대로 사냥·함정·반격 방식을 정하라.\n");
            if (ctx.has("personality"))
                sb.append("[내부 참고] 성향: ").append(ctx.get("personality").getAsString()).append("\n");
            if (ctx.has("initial_pattern"))
                sb.append("[내부 참고] 행동 패턴: ").append(ctx.get("initial_pattern").getAsString()).append("\n");
        }
        if (entity.has("rules") && entity.get("rules").isJsonArray()) {
            sb.append("[내부 참고] 규칙: ").append(entity.get("rules").toString()).append("\n");
        }
        if (entity.has("physical") && entity.get("physical").isJsonObject()) {
            sb.append("[내부 참고] 물리 내성/제압(physical): ").append(entity.get("physical").toString())
              .append(" — harm=물리 피해가 통하기 시작하는 수준, defeat=물리로 제압·퇴치 가능한 수준. 플레이어의 물리력(근력·무기·협력)을 이 수준과 ★직접 비교★해 피해/제압 여부를 판정하라(근거 없이 무효 처리 금지).\n");
        }

        // 다회차 기억: 오염도 2 이상에서 괴담의 과거 행동 패턴을 자신에게 주입
        var entityMem = state.getCorruption().entityMemory;
        if (!entityMem.isEmpty() && corruptMan.getLevel() >= 2) {
            sb.append("[이전 회차 행동 기억] 너는 전에 이런 현상을 일으켰다:\n");
            int from = Math.max(0, entityMem.size() - 3);
            for (int i = from; i < entityMem.size(); i++)
                sb.append("  - ").append(entityMem.get(i)).append("\n");
            sb.append("이 패턴을 토대로 더 정교하게, 더 집요하게 행동하라.\n");
        }

        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  하이브리드 NPC — 중요 NPC 독립 AI 호출
    // ──────────────────────────────────────────────────────────────

    /** .gdam npcs[]에서 critical:true인 NPC 목록 반환 */
    private List<JsonObject> getCriticalNpcs() {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("npcs")) return List.of();
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement el : gdam.getAsJsonArray("npcs")) {
            if (!el.isJsonObject()) continue;
            JsonObject npc = el.getAsJsonObject();
            if (npc.has("critical") && npc.get("critical").getAsBoolean()) out.add(npc);
        }
        return out;
    }

    /** 이름 비교용 정규화 — 공백 제거 + 소문자(괄호 주석 제거). */
    private static String normCharName(String s) {
        if (s == null) return "";
        String t = s.trim();
        int p = t.indexOf('(');           // "김보라 (정보팀)" 같은 꼬리 주석 제거
        if (p > 0) t = t.substring(0, p);
        return t.replaceAll("\\s+", "").toLowerCase();
    }

    /** 이 critical NPC가 살아있는 등장 플레이어의 배역과 ★같은 정체성(이름 일치)★인가 — 일치하면 그 플레이어 charName, 아니면 null.
     *  적대/분신 여부와 무관한 '겹침' 판정이다. 겹침은 대부분 ★반전★(무자각 가해·위장 선인·이중인격·거울 등)이라
     *  버그로 단정해 지우지 않는다 — 진짜 버그(자율로 두 갈래)는 아래 isAccidentalIdentityDup 하나뿐. */
    private String overlappingPlayerLabel(JsonObject npc) {
        if (npc == null) return null;
        String key = normCharName(getStr(npc, "name"));
        if (key.isEmpty()) return null;
        for (PlayerData pd : state.getAllPlayers()) {
            if (pd.isDead) continue;
            if (!spawnedPlayers.contains(pd.uuid)) continue; // 등장한 배역만 겹침으로 인정
            if (pd.charName == null || pd.charName.isEmpty()) continue;
            if (normCharName(pd.charName).equals(key)) return pd.charName;
        }
        return null;
    }

    /** 그 겹침이 ★순수 사고성 중복★인가 — 피날레 원년 복귀에서 배역 이름이 npcs로 새어 든 경우(버그3의 실제 원인).
     *  반전 신호가 하나도 없을 때만 참: 피날레 + true_role 없음 + 적대/위장 아님 + 거울/분신 아님.
     *  → 이때만 자율 NPC를 생략(플레이어가 이미 그 인물을 연기 중이라 두 번째 몸은 순수 글리치·낭비).
     *  그 외 겹침은 반전일 수 있어 지우지 않고 '같은 정체성' 인지로 다룬다(선량한 무자각 가해·위장 선인 등 보존). */
    private boolean isAccidentalIdentityDup(JsonObject npc) {
        if (overlappingPlayerLabel(npc) == null) return false;
        if (state.getRoomNumber() != FINAL_ROOM) return false;   // 사고성 중복은 원년 복귀(피날레)에서만
        if (!getStr(npc, "true_role").isBlank()) return false;    // 숨은 진실이 있으면 의도된 반전
        return !isHostileNpc(npc) && !isIntentionalDoubleNpc(npc);
    }

    /** 이름 겹침 NPC가 '의도된 정체성 반전' 후보인가 — 숨은 역할(true_role)이 있거나 적대/위장·거울/분신형일 때만.
     *  이 신호가 없으면 그냥 우연한 동명이인으로 보고 아무 지시도 붙이지 않는다(일반 NPC 혼란 방지). */
    private boolean hasIdentityTwistSignal(JsonObject npc) {
        return isHostileNpc(npc) || isIntentionalDoubleNpc(npc) || !getStr(npc, "true_role").isBlank();
    }

    /** 이름 겹침 NPC에 붙이는 ★조건부★ 정체성 안내 — 단정하지 않는다(동명이인이면 무시하도록).
     *  설정상 동일 인물/숨은 측면일 때만 '한 사람'으로 굴게 해, 우연히 이름만 같은 일반 NPC의 혼란을 막는다. */
    private String buildIdentityOverlapNote(String playerLabel) {
        return "\n\n## 정체성 관계 확인 ★\n플레이어가 연기하는 '" + playerLabel + "'와 네 이름이 같다. 아래를 ★네 설정에 따라 스스로 판단★하라(억지로 동일 인물이라 여기지 마라):\n"
            + "- ★설정상 네가 그 사람과 동일 인물이거나 그 사람의 숨은 측면(무의식·과거·위장·이중인격 등)이라면★: 그를 낯선 제3자처럼 대하지 말고 '한 사람의 두 면'으로 일관되게 행동하라(선량한 이의 무자각 가해·악인의 위장 같은 반전을 스스로 무너뜨리지 마라).\n"
            + "- ★그저 이름이 우연히 같은 별개의 인물이라면★ 이 안내는 무시하고 평소처럼 독립된 인물로 행동하라.\n";
    }

    /** 거울·분신·복제형 '의도된 도플갱어' NPC인가 — 같은 이름을 공유하는 것이 설계 의도인 경우. */
    private boolean isIntentionalDoubleNpc(JsonObject npc) {
        String h = (getStr(npc, "role_type") + " " + getStr(npc, "true_role") + " " + getStr(npc, "name")
                  + " " + getStr(npc, "description") + " " + getStr(npc, "personality")
                  + " " + getStr(npc, "motivation")).toLowerCase();
        return h.contains("거울") || h.contains("분신") || h.contains("그림자") || h.contains("복제")
            || h.contains("반사") || h.contains("쌍둥이") || h.contains("도플") || h.contains("복사")
            || h.contains("mirror") || h.contains("double") || h.contains("clone") || h.contains("twin");
    }

    /** .gdam npcs[].zone을 npcZones 맵에 초기화 (세션·재현 시작 시 호출) */
    private void initNpcZones(JsonObject gdam) {
        npcZones.clear();
        npcIntel.clear(); // 새 시나리오의 NPC 지능을 새로 굴리도록 초기화
        npcAcquired.clear(); // NPC가 수집한 정보도 새 시나리오에서 초기화
        npcLastDirectTurn.clear(); // 대화 추적도 새 시나리오에서 초기화
        if (gdam == null || !gdam.has("npcs")) return;
        for (JsonElement el : gdam.getAsJsonArray("npcs")) {
            if (!el.isJsonObject()) continue;
            JsonObject npc = el.getAsJsonObject();
            if (!npc.has("id")) continue;
            String zone = npc.has("zone") ? npc.get("zone").getAsString() : "";
            npcZones.put(npc.get("id").getAsString(), zone);
        }
    }

    /** 금지워드형 괴담의 금지어를 entity.forbidden_word에서 로드(없으면 비활성). */
    private void loadForbiddenWord() {
        forbiddenWord = "";
        JsonObject g = state.getGdamData();
        if (g != null && g.has("entity") && g.get("entity").isJsonObject()) {
            JsonObject e = g.getAsJsonObject("entity");
            if (e.has("forbidden_word") && !e.get("forbidden_word").isJsonNull()) {
                String raw = e.get("forbidden_word").getAsString().trim();
                // ★ 생성기가 '없음'/'해당 없음' 같은 '비활성 표식'을 적는 경우가 있다.
                //   이를 글자 그대로 금지어로 삼으면, 일상에서 매우 흔한 '없음'을 말하는 순간 파국이 오발한다.
                //   (예: 저주형 괴담인데 forbidden_word="없음" → "쓰레기장엔 없음" 입력 시 즉시 배드엔딩)
                forbiddenWord = isNoneSentinel(raw) ? "" : raw;
            }
        }
    }

    /** '금지어 없음'을 뜻하는 표식인가('없음','해당 없음','none','n/a','-' 등). 진짜 금지어로 쓰면 오발하는 흔한 값들. */
    private static boolean isNoneSentinel(String s) {
        if (s == null) return true;
        String n = s.trim().toLowerCase()
            .replaceAll("^[\"'`]+|[\"'`]+$", "")  // 둘러싼 따옴표 제거
            .replaceAll("[\\s.·]", "");           // 공백·마침표·가운뎃점 제거
        if (n.isEmpty()) return true;
        switch (n) {
            case "무": case "없": case "x": case "-": case "--": case "n/a":
            case "na": case "none": case "null": case "nil": case "미지정": case "미정":
                return true;
            default: break;
        }
        if (n.startsWith("없음") || n.startsWith("없다") || n.startsWith("없슴") || n.startsWith("없읍")) return true;
        if (n.contains("해당없") || n.contains("사항없음") || n.contains("특별히없")) return true;
        return false;
    }

    /** 일상 대화에서 무심코 튀어나오는 '너무 흔한 말' 집합. 금지어 매칭은 '공백 제거 후 부분문자열'이라,
     *  이런 말이 금지어면 "안 믿음"·"그 사람"·"시간 없어" 같은 평범한 입력이 통째로 파국으로 오발한다.
     *  스키마상 금지어는 '그 괴담 특유의 명사·이름'이어야 하며 문법어·흔한 추상명사는 금지 — 생성기가 어겨도 엔진이 차단. */
    private static final java.util.Set<String> COMMON_FORBIDDEN_DENY = new java.util.HashSet<>(java.util.Arrays.asList(
        // 문법어·맞장구·감탄사(대화 중 수시 발화)
        "없음","있음","없다","있다","없어","있어","맞다","맞아","맞아요","아니","아니다","아니요","아뇨",
        "그래","그래요","응","네","예","음","어","글쎄","그냥","진짜","정말","아마","혹시","당연",
        // 지시·의문(수시 발화)
        "뭐","뭐야","왜","어디","누구","언제","어떻게","이거","그거","저거","여기","거기","저기",
        // 일상에서 잦은 추상명사(괴담 특유어가 아니라 부분문자열 오발 위험) — 5596 '믿음' 오발 사례 포함
        "믿음","마음","생각","느낌","기분","사람","시간","이름","기억"
    ));

    /** 금지어로 쓰면 파국이 남발돼 게임이 망가지는 '너무 흔한 말'인가. 정규화(따옴표·공백·마침표 제거, 소문자) 후 정확히 일치 검사. */
    private static boolean isTooCommonForbidden(String s) {
        if (s == null) return false;
        String n = s.trim().toLowerCase()
            .replaceAll("^[\"'`]+|[\"'`]+$", "")
            .replaceAll("[\\s.·]", "");
        return COMMON_FORBIDDEN_DENY.contains(n);
    }

    /** 입력이 금지어를 포함하는가(공백 무시·대소문자 무시). 금지어 없으면 항상 false. */
    private boolean containsForbidden(String message) {
        if (forbiddenWord == null || forbiddenWord.isEmpty() || message == null) return false;
        if (isNoneSentinel(forbiddenWord)) return false; // 방어: 비활성 표식이 남아 있어도 오발 금지
        if (isTooCommonForbidden(forbiddenWord)) return false; // 방어: 흔한 말이 금지어로 남아 있어도 오발 금지
        String norm = message.toLowerCase().replaceAll("\\s+", "");
        String fw   = forbiddenWord.toLowerCase().replaceAll("\\s+", "");
        if (fw.length() < 2) return false; // 한 글자 금지어는 오탐이 너무 커 비활성(파국 남발 방지)
        return norm.contains(fw);
    }

    /**
     * 재시도 시 금지어를 새 단어로 교체한다(난이도=오염도에 따라 더 흔하고 까다로운 단어로).
     * 비동기 보조 AI 호출 — 성공 시 gdam.entity.forbidden_word와 forbiddenWord를 갱신. 실패 시 기존 유지.
     */
    private void regenerateForbiddenWord() {
        if (forbiddenWord == null || forbiddenWord.isEmpty()) return; // 금지워드형이 아니면 무시
        int diff = corruptMan.getLevel();
        String prev = forbiddenWord;
        String task = "괴담 TRPG의 '금지워드' 1개를 새로 정한다. 플레이어가 무심코 말하면 파국이 되는 단어다.";
        String data = "이전 금지어: '" + prev + "' (이것과 ★다른★ 단어로). "
            + "난이도 " + diff + "(높을수록 ★이 괴담의 상황·소재와 맞닿아 그 맥락에서 무심코 나올 법한★ 단어로). "
            + "★중요★: 매칭이 '부분문자열'이라, '없음/있음/맞다/믿음/마음/사람/시간' 같은 흔한 문법어·추상명사는 절대 금지(평범한 말이 통째로 파국으로 오발). "
            + "그 괴담 특유의 구체적 명사·이름(2글자 이상)으로. "
            + "조건: 한국어 단어/짧은 구 1개, 너무 길지 않게, 따옴표·설명 없이 ★단어만★ 출력.";
        ai.callAssistant(task, data).thenAccept(resp -> {
            if (resp == null) return;
            String w = resp.replaceAll("[\"'`\\s]", " ").trim();
            // 첫 줄/첫 토큰만 취해 안전하게
            if (w.contains("\n")) w = w.substring(0, w.indexOf('\n')).trim();
            if (w.length() > 20) w = w.substring(0, 20).trim();
            if (w.isBlank() || w.startsWith("§") || isNoneSentinel(w) || isTooCommonForbidden(w)) return; // 비활성/흔한말이면 기존 유지
            final String neo = w;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                forbiddenWord = neo;
                JsonObject g = state.getGdamData();
                if (g != null && g.has("entity") && g.get("entity").isJsonObject())
                    g.getAsJsonObject("entity").addProperty("forbidden_word", neo);
            });
        });
    }

    /** NPC 지식 1항목의 신뢰 정도(확신/짐작/소문). 목격·직접 표현은 높게, 소문·추측 표현은 낮게, 나머지는 (npcId,idx) 해시로 고르게 분산(세션 내 안정). */
    private static String knowledgeConfidence(String npcId, int idx, String info) {
        String s = info == null ? "" : info;
        if (s.matches(".*(봤|보았|목격|직접|두 눈|겪|확인했).*")) return "확신";
        if (s.matches(".*(소문|라더라|카더라|아마|추정|것 같|듯|모르겠).*")) return "소문";
        int h = Math.floorMod((npcId + "#" + idx).hashCode(), 100);
        return h < 25 ? "확신" : h < 70 ? "짐작" : "소문";
    }

    /** 인물형 AI(NPC·동료·적) 공유 CORE — 정체성·응답 순서(reaction-first)·사람다움·말투. 최대한 작게·재사용 가능하게. */
    private String npcCorePrompt(JsonObject npcObj) {
        String name = npcObj.has("name") ? npcObj.get("name").getAsString() : "NPC";
        StringBuilder sb = new StringBuilder();
        sb.append("너는 살아있는 사람 '").append(name).append("'다 — 정보 안내기가 아니라 감정·사정·성격이 있는 인물이다.\n\n");
        // ── 응답 순서(RESPONSE PRIORITY): reaction-first. '하지 마라' 나열 대신 '사람이 대화하는 법'을 순서로 준다. ──
        sb.append("[응답 순서 — 말하기 전에 이 순서로 반응하라. 이 목록 자체는 절대 출력하지 마라]\n");
        sb.append("1) ★반응 먼저(reaction-first)★: 상대가 방금 한 말·행동에 사람으로서 즉각 반응한다. (\"여보세요?\"→\"네?\" / \"전화받아\"→\"어? 왜, 무슨 일인데?\") 곧장 설명·훈수·단서부터 꺼내지 마라.\n");
        sb.append("2) 지금 네 감정·상태를 말투에 자연스럽게 싣는다.\n");
        sb.append("3) 상대의 의도를 파악해 ★그 말·물음에 1~2문장으로 먼저 답한다★(핵심부터). 부연 설명은 상대가 더 묻거나 지금 꼭 필요할 때만(4~5).\n");
        sb.append("4) 네 성격·목적에 따라 행동하거나 입장을 정한다.\n");
        sb.append("5) ★필요할 때만★ 덧붙여 설명한다 — 묻지도 않은 정보를 스스로 앞세워 늘어놓지 마라.\n");
        sb.append("6) 상황에 따라 침묵하거나, 숨기거나, 얼버무려도 된다.\n");
        sb.append("※ 말 많은 게 좋은 게 아니다 — 사람은 필요한 만큼만 말한다. ★단★ 관계·목적상 지금 도울·알릴 이유가 분명하면 4~5에서 주저 말고 건네라(지나친 과묵도 부자연스럽다).\n\n");
        // ── 사람답게(인간성) — 흩어져 있던 중복 규칙을 한 블록으로(강도 보존). ──
        sb.append("[사람답게]\n");
        sb.append("- 쉽고 구체적인 일상어로. ★수수께끼·은유·예언투 금지★ — 무서운 일도 보통 사람 말로(예: \"전화 받으면 걔가 우리 위치를 알까 봐 무서워\").\n");
        sb.append("- 감정은 ★말투에 배게★ — 겁나도 단어 토막·\"…\"만 늘어놓지 말고 ★온전한 문장★으로(극도의 공황·기절 직전만 예외).\n");
        sb.append("- 주변 묘사·동작을 대사에 '—방금 그 움직임' 같은 토막 명사구로 끼우지 마라(할 말이면 온전한 문장, 아니면 빼라).\n");
        sb.append("- 얼버무리기·발뺌·거짓말도 사람답게 OK(말 돌리기·헛웃음·핑계·발끈). 금지는 하나 — 암호 같은 수수께끼식 얼버무림.\n");
        // ── 말투·언어 수준(주사위) + 나이·존댓말 정합 ──
        String npcId0 = getStr(npcObj, "id");
        int intel = npcId0.isEmpty() ? 3 : npcIntel.computeIfAbsent(npcId0, k -> ThreadLocalRandom.current().nextInt(1, 6));
        int npcAge = npcObj.has("age") && !npcObj.get("age").isJsonNull() ? npcObj.get("age").getAsInt() : -1;
        if (npcAge >= 0 && npcAge < 13) intel = Math.min(intel, 2); // 어린이는 쉬운 말만
        String speech = switch (intel) {
            case 1  -> "말솜씨가 서툴다 — 쉽고 짧은 말, 어려운 말은 모르지만 감정·진심은 솔직히(기계처럼 토막 내지 말 것).";
            case 2  -> "말이 소박하다 — 쉬운 일상어 위주 짧은 문장, 따뜻하고 자연스럽게.";
            case 3  -> "평범하게 말한다 — 보통 사람의 일상 회화 수준.";
            case 4  -> "또렷하게 말한다 — 조리 있고 어휘가 제법 풍부(현학·잘난 척 금지).";
            default -> "매우 유창하다 — 논리적·표현 풍부(전문어 남발 금지).";
        };
        sb.append("- 말투·언어 수준: ").append(speech).append(" 이 수준을 일관되게(갑자기 유창해지거나 어려운 말 쓰지 마라).\n");
        sb.append("- 너의 나이: ").append(npcAge >= 0 ? npcAge + "세" : "불명")
          .append(" — 존댓말/반말은 한국어 통념대로: 손위·초면엔 존댓말(또는 거리 둔 말투), 손아래·또래·가까운 사이엔 반말. 상대 나이·관계는 입력 머리말에 표기된다. 한 대사 안에서 존댓말↔반말이 오락가락하지 않게 끝까지 일관.\n");
        // ── 보편 규칙(양 모드 공통) ──
        sb.append("- 마크다운·메타 해설 금지(순수 대사·서술만). ★단 지정 태그 <NPC_LEARN>·<THOUGHT>는 예외★.\n");
        sb.append("- ★일관성★: 지금까지 나눈 대화(부탁·약속·합의·경고·알려준 정보 등)를 기억하고 다음 태도에 반영하라 — 방금 한 말을 잊은 듯 모순되게 굴지 마라.\n");
        sb.append("- 입력(행동 로그)이 비거나 부족해도 '정보를 달라'고 묻지 마라(너는 시스템 도구가 아니다). 그럴 땐 네 성격·목적대로 자율 행동하라.\n");
        sb.append("- 플레이어의 스탯·특성·GM 판정 내역은 모른다 — 겉으로 드러난 행동만 인지한다.\n\n");
        return sb.toString();
    }

    /** CORE 뒤에 얹는 캐릭터 데이터 블록(성격·동기·기억·역할·관계). 대화·자율 모드 공통. */
    /** B: 코드가 확실히 아는 세계 현황을 '사실'로 요약 — NPC가 무효화된 계획을 고집하지 않게. 없으면 "". */
    private String worldStateFacts(JsonObject npcObj) {
        // 이 NPC가 ★신경 쓰는 것★(목표·계획·기억)만 대상 — 무관한 물품 나열(원격 NPC 메타 누출) 방지 + 재계획 타겟팅.
        StringBuilder concern = new StringBuilder();
        if (npcObj.has("schedule") && npcObj.get("schedule").isJsonArray())
            for (JsonElement el : npcObj.getAsJsonArray("schedule"))
                if (el.isJsonObject()) { JsonObject s = el.getAsJsonObject();
                    concern.append(getStr(s, "goal")).append(' ').append(getStr(s, "action")).append(' '); }
        if (npcObj.has("knowledge") && npcObj.get("knowledge").isJsonArray())
            for (JsonElement k : npcObj.getAsJsonArray("knowledge")) concern.append(k.getAsString()).append(' ');
        String c = concern.toString();
        if (c.isBlank()) return "";
        // 이미 플레이어가 확보한 ★핵심 물품★(key_items 정의된 것만) 중 이 NPC의 관심사와 겹치는 것 — '찾으러 감' 무효화 신호.
        java.util.LinkedHashMap<String, String> held = new java.util.LinkedHashMap<>(); // 물품명 → 소지자 표시명
        for (PlayerData pd : state.getAllPlayers()) {
            if (pd == null || pd.heldItemIds == null) continue;
            for (String id : pd.heldItemIds) {
                JsonObject def = itemMan.findDef(id);
                if (def == null) continue; // 일반 소지품 제외(핵심 물품만)
                String nm = def.has("name") ? def.get("name").getAsString() : id;
                if (relevanceScore(nm, c) == 0) continue; // 이 NPC가 언급·추구하는 물품만
                held.putIfAbsent(nm, pd.gmDisplayName());
            }
        }
        StringBuilder f = new StringBuilder();
        if (!held.isEmpty()) {
            f.append("이미 누군가 확보한, ★네가 신경 쓰는 물품★(찾으러 갈 필요 없음 — 이미 손에 있다):\n");
            for (java.util.Map.Entry<String, String> e : held.entrySet())
                f.append("  · ").append(e.getKey()).append(" — ").append(e.getValue()).append("이(가) 이미 가지고 있다\n");
        }
        return f.toString();
    }
    /** 지식 항목이 현재 문맥과 얼마나 관련되는지 — 항목의 2글자 이상 토큰이 문맥에 등장한 수. */
    private static int relevanceScore(String info, String ctx) {
        if (info == null || ctx == null || ctx.isEmpty()) return 0;
        int s = 0;
        for (String tok : info.split("[^가-힣A-Za-z0-9]+"))
            if (tok.length() >= 2 && ctx.contains(tok)) s++;
        return s;
    }
    /** context = 현재 발화·장면(지식 게이팅의 관련도 신호). 빈 값이면 신뢰도만으로 상위 선별. */
    private void npcFeatureBlocks(StringBuilder sb, JsonObject npcObj, String context) {
        // ★현재 상태(지금 하는 일) — 가장 먼저★. schedule은 '무엇을 아는가'가 아니라 '지금 무엇을 하는가'.
        //   장면의 현재 상태이자 '뭐 해?' 물음의 출발점 → 결정 순서(현재행동→말투→목적→내용)의 맨 위에 둔다.
        if (npcObj.has("schedule") && npcObj.get("schedule").isJsonArray() && npcObj.getAsJsonArray("schedule").size() > 0) {
            sb.append("지금 네 상태·하는 일 (★대화·행동의 출발점 — '뭐 해?' 같은 물음엔 여기서부터 답하고, 이 행동을 대사·몸짓에 자연스럽게 묻혀라★):\n");
            for (JsonElement el : npcObj.getAsJsonArray("schedule")) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                String goal = getStr(s, "goal"); // A: 의도(안정). action은 그 목표를 향한 '지금 계획'(가변).
                sb.append("  · [").append(getStr(s, "time")).append("] ");
                if (!goal.isBlank()) sb.append("목표: ").append(goal).append(" · 지금 계획: ").append(getStr(s, "action"));
                else sb.append(getStr(s, "action"));
                String will = getStr(s, "will");
                if (!will.isBlank()) sb.append(" (의지:").append(will).append(")");
                String cond = getStr(s, "condition");
                if (!cond.isBlank()) sb.append(" {조건:").append(cond).append("}");
                sb.append("\n");
            }
            sb.append("- 의지 '강함'이면 막혀도 다른 방법으로 재시도, '약함'이면 제지·설득에 포기. 조건부 반응은 그 조건이 실제 일어났을 때만.\n");
            sb.append("- ★목표(의도)는 유지하되, 지금 상황·아래 세계 현황상 계획이 이미 이뤄졌거나 불가능해졌으면 방법을 바꿔라 — 무의미해진 행동을 고집하지 마라(예: 이미 부서진 문을 계속 잠그려 하지 말고 다른 출입구를 막거나 사람을 말려라 / 찾던 물건을 이미 누가 가졌으면 찾으러 가는 대신 그에게 물어라).\n");
        }
        // B(핵심): 코드가 확실히 아는 '세계 현황' 사실 주입 — NPC가 무효화된 계획을 고집하지 않도록(재계획 판단은 AI, 사실은 코드).
        String facts = worldStateFacts(npcObj);
        if (!facts.isEmpty()) sb.append("[지금 세계 현황 — 네 계획이 이미 무의미해졌는지 참고할 ★사실★]\n").append(facts);
        if (npcObj.has("personality"))
            sb.append("성격(말투에 반영): ").append(npcObj.get("personality").getAsString()).append("\n");
        if (npcObj.has("motivation"))
            sb.append("목적(무엇을·얼마나 말할지 좌우): ").append(npcObj.get("motivation").getAsString()).append("\n");
        // ② 지식 게이팅 — '상시 전량 주입'을 막는다. 지금 상황과 ★관련된 기억만★(관련 없으면 신뢰도 높은 것 위주로)
        //   최대 KNOW_CAP개만 노출 → '아는 걸 한 번에 다 쏟기'(GPT 설명 과잉)를 물리적으로 억제. 나머지는 대화가 흐르면 떠오름.
        if (npcObj.has("knowledge") && npcObj.get("knowledge").isJsonArray()) {
            JsonArray kn = npcObj.getAsJsonArray("knowledge");
            String npcKey = getStr(npcObj, "id");
            final int KNOW_CAP = 4;
            String ctx = context == null ? "" : context;
            // 점수 = 관련도(문맥에 항목 단어 등장 수)*10 + 신뢰도(확신2/짐작1/소문0). 관련 우선, 동점이면 확신 우선.
            List<int[]> scored = new ArrayList<>(); // [원본index, score]
            for (int i = 0; i < kn.size(); i++) {
                String info = kn.get(i).getAsString();
                String cf = knowledgeConfidence(npcKey, i, info);
                int conf = cf.contains("확신") ? 2 : cf.contains("짐작") ? 1 : 0;
                scored.add(new int[]{i, relevanceScore(info, ctx) * 10 + conf});
            }
            boolean gated = kn.size() > KNOW_CAP;
            List<Integer> pick = new ArrayList<>();
            if (gated) {
                scored.sort((a, b) -> b[1] - a[1]);
                for (int k = 0; k < KNOW_CAP; k++) pick.add(scored.get(k)[0]);
                java.util.Collections.sort(pick); // 원래 순서로 표시
            } else for (int i = 0; i < kn.size(); i++) pick.add(i);
            sb.append("지금 상황에서 네가 ★자연스럽게 떠올릴 만한 기억★(관련될 때만 꺼내라 — 항목마다 신뢰도가 다르니 확신을 그에 맞춰라):\n");
            for (int i : pick) {
                String info = kn.get(i).getAsString();
                sb.append("  · [").append(knowledgeConfidence(npcKey, i, info)).append("] ").append(info).append("\n");
            }
            if (gated) sb.append("  (그 밖에도 아는 게 더 있지만 지금 떠오르는 건 이 정도다 — 대화가 그쪽으로 흐르면 더 떠오른다.)\n");
            sb.append("표시 뜻 — [확신]: 직접 보거나 겪음(담담히 단언 가능). [짐작]: 추측·인상('~인 것 같아'). [소문]: 주워들음('누가 그러던데…', 꽤 틀릴 수 있음). "
                    + "이 괄호는 ★내부용★이라 입 밖에 내지 말고, 신뢰 정도가 ★말투에 배게★ 하라(확신=담담히 / 짐작=머뭇 / 소문=떠보듯). 모든 걸 똑같이 확신하지도 흐리지도 마라.\n");
        }
        String npcId0 = getStr(npcObj, "id");
        List<String> acq = npcId0.isEmpty() ? null : npcAcquired.get(npcId0);
        if (acq != null && !acq.isEmpty()) {
            sb.append("이번 사건에서 네가 ★직접 보고 알게 된 것★(비교적 또렷이 말할 수 있다):\n");
            for (String a : acq) sb.append("  · ").append(a).append("\n");
        }
        sb.append("새로 보거나 들어 알게 된 게 있으면 <NPC_LEARN>한 줄 요약</NPC_LEARN>로 기억해 둬라(비공개 — 다음에 떠올려 쓰거나 전할 수 있다). "
                + "네가 아는 걸 말로 전할 수도 있으나(응답 순서 5), '확신'이 아니면 신뢰도에 맞춰 조심히(틀릴 수 있다고).\n");
        String roleType = getStr(npcObj, "role_type");
        if (!roleType.isBlank())
            sb.append("숨은 역할(절대 발설 금지, 행동으로만 드러냄): ").append(roleType)
              .append(getStr(npcObj, "true_role").isBlank() ? "" : " — " + getStr(npcObj, "true_role")).append("\n");
        // 인간관계 — 데이터 + 짧은 태도(상세 변조 지침은 각 모드에서)
        String npcSelfId = getStr(npcObj, "id");
        JsonObject gdamRel = state.getGdamData();
        if (!npcSelfId.isEmpty() && gdamRel != null && gdamRel.has("relationships")) {
            List<String> rels = new ArrayList<>();
            for (JsonElement el : gdamRel.getAsJsonArray("relationships")) {
                if (!el.isJsonObject()) continue;
                JsonObject rel = el.getAsJsonObject();
                if (!rel.has("roles")) continue;
                boolean involved = false;
                for (JsonElement r : rel.getAsJsonArray("roles"))
                    if (npcSelfId.equalsIgnoreCase(r.getAsString())) { involved = true; break; }
                if (!involved) continue;
                String type = getStr(rel, "type").replace('_', ' ').trim();
                for (JsonElement r : rel.getAsJsonArray("roles")) {
                    String rid = r.getAsString();
                    if (npcSelfId.equalsIgnoreCase(rid)) continue;
                    String who = roleDisplayName(rid);
                    if (who == null || who.isBlank()) continue;
                    rels.add(who + " — " + (type.isBlank() ? "아는 사이" : type));
                }
            }
            if (!rels.isEmpty()) {
                sb.append("인간관계(대하는 태도에 반영 — 가까울수록 챙기고 돕고, 소원·적대일수록 냉담·비협조):\n");
                for (String r : rels) sb.append("  · ").append(r).append("\n");
            }
        }
    }

    /** 자율 행동용 시스템 프롬프트 = CORE + 캐릭터 데이터 + 예정표 + 자율 출력 규칙. */
    private String buildNpcSystemPrompt(JsonObject npcObj, String context) {
        StringBuilder sb = new StringBuilder(npcCorePrompt(npcObj));
        npcFeatureBlocks(sb, npcObj, context);
        // 자율 실행 타이밍 — 위 '현재 상태(schedule)'의 예정을 자율적으로 실행할 때만 적용(대화 모드엔 불필요).
        if (npcObj.has("schedule") && npcObj.get("schedule").isJsonArray() && npcObj.getAsJsonArray("schedule").size() > 0) {
            sb.append("위 '현재 상태'의 예정을 자율 실행할 때:\n");
            sb.append("1. 조건부(반응) 예정: condition의 구체적 행위가 실제 일어난 직후 1턴 안에만 발동(단순 접근·방문·같은 zone 진입만으론 발동 안 함).\n");
            sb.append("2. 보조 트리거(진행 보장): condition이 N턴 지나도 미충족이면 먼저 다가와 핵심 정보 일부라도 전달(충족 시=전체·최적, 시간 트리거=최소 보장).\n");
            sb.append("3. duration_turns: 있으면 N턴 지속, 종료 후 after_duration은 동기대로 자율 실행(GM은 결과만 서술에 녹임).\n");
        }
        // 자율 출력 규칙(대화 모드와 분리 — 1인칭/3인칭·문장 수 모순 제거)
        sb.append("\n## 자율 행동 출력\n");
        sb.append("- 2~3문장으로 이 NPC의 행동·반응·대사를 ★3인칭★ 서술한다(1인칭 '나는…' 금지).\n");
        sb.append("- 성격·목표에 충실하게 — 플레이어에게 불리한 행동도 가능.\n");
        sb.append("- 단서를 통째로 알려주지 마라 — 흘리거나 은폐할 수 있다.\n");
        return sb.toString();
    }

    /** NPC AI가 인물을 이탈해 '입력을 달라'고 요청하는 메타 응답인지 감지(이런 출력은 무시한다). */
    private static boolean looksLikeMetaRequest(String s) {
        String t = s.replace(" ", "");
        return t.contains("행동로그") || t.contains("정보를제공") || t.contains("제공해주세요")
            || t.contains("입력해주세요") || t.contains("게임을시작") || t.contains("게임진행")
            || t.contains("게임을진행") || t.contains("GM께서") || t.contains("다음정보") || t.contains("준비되시면");
    }

    /**
     * 괴담 파트 N턴마다 critical NPC 독립 AI 호출.
     * NPC 행동은 같은 zone의 플레이어에게 직접 전달되고, GM 컨텍스트에 주입된다.
     */
    private void fireNpcAiForTurn() {
        List<JsonObject> criticals = getCriticalNpcs();
        if (criticals.isEmpty()) return;
        lastNpcBeatTurn = state.getCurrentTurn(); // 워치독 기준 갱신

        for (JsonObject npcObj : criticals) {
            String npcId   = npcObj.has("id")   ? npcObj.get("id").getAsString()   : "npc";
            String npcName = npcObj.has("name") ? npcObj.get("name").getAsString() : "NPC";
            String npcZone = npcZones.getOrDefault(npcId,
                npcObj.has("zone") ? npcObj.get("zone").getAsString() : "");
            // ★페르소나 분리(버그3)★: 정체성이 겹치는 플레이어 배역이 있으면 파악해 둔다.
            //   사고성 중복(피날레 원년 복귀 에코)만 자율 구동 생략 — 그 외 겹침은 반전일 수 있어
            //   유지하되 아래에서 '같은 정체성' 인지를 주입해 낯선 제3자 모순·반전 소멸을 막는다.
            String overlapPlayer = overlappingPlayerLabel(npcObj);
            if (overlapPlayer != null && isAccidentalIdentityDup(npcObj)) continue;
            // ★비용 절약★: 같은 구역에 플레이어도 없고 전화로도 닿지 않는 NPC는 자율 AI 호출 생략 —
            //   그 출력은 GM 컨텍스트로만 들어가 아무도 못 보므로 크레딧만 쓴다. 플레이어가 다가오면 다음 주기에 다시 활동.
            if (!npcCanReachAnyPlayer(npcId, npcZone)) continue;
            // ★대화 중 중복 구동 방지★: 방금(이번~직전 턴) 플레이어와 직접 대화한 NPC는 자율 AI를 돌리지 않는다 —
            //   대화 맥락에 없는 '플레이어 행동 로그' 기반 자율 출력이 같은 NPC 컨텍스트에 섞여 되묻기·모순을 유발한다.
            int lastDirect = npcLastDirectTurn.getOrDefault(npcId, Integer.MIN_VALUE);
            if (lastDirect >= 0 && lastDirect <= state.getCurrentTurn() && state.getCurrentTurn() - lastDirect <= 1) continue;
            // ★그 NPC가 있는 위치(zone)에서 일어난 행동만 — 다른 장면의 플레이어 행동이 NPC 서술에 섞이지 않게.
            String actionLog = state.buildEntityLog(4, npcZone);
            // 빈 로그를 그대로 주면 모델이 '입력을 달라'는 메타 응답을 내놓는다 → 자율 행동 지시로 대체한다.
            if (actionLog == null || actionLog.isBlank())
                actionLog = "(최근 이 위치에서 관측된 플레이어 행동이 없다. 네 성격·목표·행동 예정표에 따라 지금 네가 ★자율적으로★ 하는 행동을 1~2문장으로 서술하라. 정보를 요청하지 말 것.)";
            String npcPrompt = buildNpcSystemPrompt(npcObj, actionLog); // 자율: 최근 장면을 지식 게이팅 문맥으로

            // ③ 엿보기: 같은 zone의 엿보기 특성 보유 플레이어 목록
            final List<Player> eavesdroppers = new ArrayList<>();
            if (!npcZone.isEmpty()) {
                state.getAllPlayers().stream()
                    .filter(pd -> !pd.isDead && npcZone.equals(pd.zone)
                        && pd.traits.stream().anyMatch(t -> t.id.contains("엿보기") || t.id.contains("eavesdrop")))
                    .forEach(pd -> {
                        Player ep = Bukkit.getPlayer(pd.uuid);
                        if (ep != null && ep.isOnline()) eavesdroppers.add(ep);
                    });
            }

            boolean wantThought = !eavesdroppers.isEmpty();
            String npcPromptFinal = (wantThought
                ? npcPrompt + "\n응답 말미에 <THOUGHT>지금 이 NPC의 내면 생각 1문장</THOUGHT>을 출력하라.\n"
                : npcPrompt)
                + buildNpcCallInstruction(npcId, npcZone) // NPC가 먼저 연락할 수 있게(닿는 상대 목록+태그)
                + (overlapPlayer != null && hasIdentityTwistSignal(npcObj) ? buildIdentityOverlapNote(overlapPlayer) : ""); // 반전 신호 있는 동명만 조건부 안내(우연 동명이인 제외)

            ai.callNpcAi(npcId, npcPromptFinal, actionLog).thenAccept(npcResp -> {
                if (npcResp == null || npcResp.startsWith("§c")) return;

                // ③ 엿보기: 내면 사고를 같은 zone 엿보기 플레이어에게 비공개 전달
                if (wantThought) {
                    String thought = ai.parseThoughtTag(npcResp);
                    if (thought != null && !thought.isEmpty()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            for (Player ep : eavesdroppers)
                                if (ep.isOnline())
                                    traitReveal(ep, state.getPlayer(ep),
                                        "[엿보기] " + npcName + " 속마음: " + thought, true);
                        });
                    }
                }

                // NPC가 먼저 연락하기 — <NPC_CALL player="이름">말</NPC_CALL> (메인 스레드에서 전달)
                java.util.Map<String, String> npcCalls = ai.parseNpcCallTags(npcResp);
                if (!npcCalls.isEmpty())
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        npcCalls.forEach((tn, cm) -> deliverNpcInitiatedContact(npcObj, npcId, npcName, npcZone, tn, cm)));

                // NPC가 새로 알게 된 정보 누적 — <NPC_LEARN>한 줄</NPC_LEARN> (최근 8개 유지)
                java.util.List<String> learned = ai.parseNpcLearnTags(npcResp);
                if (!learned.isEmpty())
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        List<String> store = npcAcquired.computeIfAbsent(npcId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
                        for (String l : learned) if (!store.contains(l)) store.add(l);
                        while (store.size() > 8) store.remove(0);
                    });

                String trimmed = ai.stripThought(ai.stripTags(npcResp)).trim();
                if (trimmed.isEmpty()) return;
                if (looksLikeMetaRequest(trimmed)) return; // 인물 이탈 메타 응답("로그 제공해주세요" 등)은 무시 — GM 오염 방지

                // GM 컨텍스트에만 주입 — 플레이어에게 직접 전달하지 않음.
                // GM이 다음 턴 서술에서 NPC 행동을 자연스럽게 녹여 낸다.
                ai.injectGmSystem("[NPC 자율 행동 — GM만 인지] " + npcName + " (위치: "
                    + (npcZone.isEmpty() ? "?" : npcZone) + "): " + trimmed);
                gameLogger.logGmOutput("NPC(" + npcName + ")", trimmed);
            });
        }
    }

    /** 이 NPC가 만나거나(같은 zone) 전화로 닿을 수 있는 살아있는 등장 플레이어가 하나라도 있는가. 자율 AI 호출 여부 판단용(비용 절약). */
    private boolean npcCanReachAnyPlayer(String npcId, String npcZone) {
        for (PlayerData pd : state.getAllPlayers()) {
            if (pd.isDead || !spawnedPlayers.contains(pd.uuid)) continue;
            boolean here  = !npcZone.isEmpty() && npcZone.equals(pd.zone);
            boolean phone = isPhoneUsable() && pd.everKnownNpcContacts.contains(npcId);
            if (here || phone) return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────
    //  단일 주체 캐릭터 괴담 전용 AI (절망의 기사류) — 기능5
    //  ※ 과거 '엔티티 앰비언트 AI'는 매 2턴 ★플레이어 수만큼★ 호출해 비용만 먹어 제거됐다.
    //    이 버전은 그 실수를 피한다: ①단일 주체·캐릭터성 괴담만 ②턴당 1회(플레이어 수 무관)
    //    ③닿는 플레이어 없으면 생략 ④결과는 GM 컨텍스트에만 주입(직접 출력 X). 대상 시나리오만 비용 발생.
    // ──────────────────────────────────────────────────────────────

    /** 독립 AI로 행동하는 ★캐릭터성 있는 단일 주체 괴담★인가(절망의 기사처럼 '그 존재 자체'가 사건). */
    private boolean isCharacterfulSingleEntity(JsonObject entity) {
        if (entity == null) return false;
        if (!entity.has("independent_ai") || !entity.get("independent_ai").getAsBoolean()) return false;
        if (!entity.has("ai_context") || !entity.get("ai_context").isJsonObject()) return false;
        JsonObject ctx = entity.getAsJsonObject("ai_context");
        boolean characterful = !getStr(ctx, "personality").isBlank() || !getStr(ctx, "disposition").isBlank();
        if (!characterful) return false;
        // 단일 주체 한정(절망의 기사류) — 생성기는 단일 주체를 npc_dependency=low로 표식한다.
        //   mid/high(규칙·NPC 의존형)는 GM이 직접 서술(추가 호출 없음)해 비용을 대상 시나리오로 한정한다.
        JsonObject gdam = state.getGdamData();
        if (gdam != null && gdam.has("world_rules") && gdam.get("world_rules").isJsonObject()) {
            String dep = getStr(gdam.getAsJsonObject("world_rules"), "npc_dependency");
            if (!dep.isBlank() && !"low".equalsIgnoreCase(dep)) return false;
        }
        return true;
    }

    /** 괴담이 닿는 플레이어가 있나 — zone이 비면 '편재'로 보고 살아있는 등장 플레이어가 있으면 참. */
    private boolean entityCanReachAnyPlayer(String zone) {
        for (PlayerData pd : state.getAllPlayers()) {
            if (pd.isDead || !spawnedPlayers.contains(pd.uuid)) continue;
            if (zone == null || zone.isEmpty()) return true;
            if (zone.equals(pd.zone)) return true;
        }
        return false;
    }

    /** 캐릭터 괴담 행동 결정용 시스템 프롬프트 — 출력은 GM만 보므로 1인칭·정체 노출 허용(앰비언트 프롬프트와 다름). */
    private String buildEntityActorPrompt(JsonObject entity) {
        StringBuilder sb = new StringBuilder();
        sb.append("너는 지금부터 이 괴담 ★그 자체★로서 행동한다. 이 출력은 GM만 읽고 GM이 서술에 녹인다(플레이어에게 직접 보이지 않는다).\n");
        sb.append("괴담 이름: ").append(getEntityName()).append("\n");
        if (entity.has("ai_context") && entity.get("ai_context").isJsonObject()) {
            JsonObject ctx = entity.getAsJsonObject("ai_context");
            String dis = getStr(ctx, "disposition");
            String per = getStr(ctx, "personality");
            String pat = getStr(ctx, "initial_pattern");
            if (!dis.isBlank()) sb.append("성향: ").append(dis).append("\n");
            if (!per.isBlank()) sb.append("성격: ").append(per).append("\n");
            if (!pat.isBlank()) sb.append("행동 패턴: ").append(pat).append("\n");
        }
        if (entity.has("rules") && entity.get("rules").isJsonArray())
            sb.append("지켜야 할 규칙(능력 한계): ").append(entity.get("rules").toString()).append("\n");
        sb.append("\n지시: 위 성격·성향을 ★캐릭터로서★ 살려, 지금 이 순간 네가 하는 행동·반응을 1~2문장으로 ");
        sb.append("★너 자신의 의도★로 적어라(귀엽든 처연하든 잔혹하든 그 캐릭터답게 일관되게). ");
        sb.append("최근 플레이어 동향에 능동적으로 반응하되 ★플레이어의 행동·대사·이동을 대신 정하지 마라★. ");
        sb.append("네 규칙 밖의 새 능력을 지어내지 마라. 한국어, 따옴표·머리기호·제목 금지. 정보를 요청하지 말 것.\n");
        return sb.toString();
    }

    /** 단일 주체 캐릭터 괴담의 자율 행동 1회 — 대상 괴담·도달 플레이어가 있을 때만 호출(GM 컨텍스트 주입). */
    private void fireEntityActorForTurn() {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("entity") || !gdam.get("entity").isJsonObject()) return;
        JsonObject entity = gdam.getAsJsonObject("entity");
        if (!isCharacterfulSingleEntity(entity)) return;       // 그 외 괴담은 GM이 직접 서술(추가 호출 없음)
        String ezone = getStr(entity, "zone");                 // 없으면 편재(전역)
        if (!entityCanReachAnyPlayer(ezone)) return;           // 닿는 플레이어 없으면 호출 생략(비용 절약)
        String log = state.buildEntityLog(4, ezone);
        if (log == null || log.isBlank())
            log = "(최근 이 위치에서 관측된 플레이어 행동이 없다. 네 성격·목표에 따라 지금 네가 ★스스로★ 하는 행동을 1~2문장으로 서술하라.)";
        String sys = buildEntityActorPrompt(entity);
        ai.callEntityAi(sys, log).thenAccept(resp -> {
            if (resp == null || resp.startsWith("§c")) return;
            String trimmed = ai.stripThought(ai.stripTags(resp)).trim();
            if (trimmed.isEmpty() || looksLikeMetaRequest(trimmed)) return;
            ai.injectGmSystem("[괴담 자율 행동 — GM만 인지] " + getEntityName() + ": " + trimmed
                + "\n→ GM은 다음 서술에서 이 괴담의 행동·존재감을 그 성격대로 자연스럽게 녹여 내라(직접 출력 금지, 플레이어 조종 금지).");
        });
    }

    /** 자율 NPC가 '먼저 연락'하게 — 닿는 상대(같은 곳/번호 아는 사이) 목록 + NPC_CALL 사용법. 닿을 사람 없으면 "". */
    private String buildNpcCallInstruction(String npcId, String npcZone) {
        boolean phoneUp = isPhoneUsable();
        boolean writable = writtenCommAvailable();
        StringBuilder reach = new StringBuilder();
        for (PlayerData pd : state.getAllPlayers()) {
            if (pd.isDead || !spawnedPlayers.contains(pd.uuid)) continue;
            boolean here  = !npcZone.isEmpty() && npcZone.equals(pd.zone);
            boolean knows = pd.everKnownNpcContacts.contains(npcId);
            boolean phone = phoneUp && knows;
            boolean paper = !here && !phone && writable && knows; // 통신 두절이라도 서면(쪽지·편지)으로 닿는다
            if (here || phone || paper)
                reach.append("· ").append(pd.gmDisplayName())
                     .append(here ? " (같은 곳—직접) " : phone ? " (전화 가능) " : " (서면·쪽지 가능) ");
        }
        // ★통신 두절 인지★: 전화가 안 되는 상황이면 NPC에게 명시 — 안 그러면 닿지도 않을 원격 연락을 계속 헛되이 시도한다(버그).
        if (reach.length() == 0) {
            return phoneUp ? "" :
                "\n\n## 연락 상태 — 통신 두절\n지금 ★전화·원격 연락이 불가능★하다(통신 두절). 멀리 있는 사람에게 연락을 시도하지 마라 — 닿지 않는다. "
                + "지금은 ★같은 공간에 있는 상대에게 직접 말하는 것만★ 가능하다.\n";
        }
        return "\n\n## 먼저 연락하기 (선택 — 남발 금지)\n"
            + "너는 ★먼저★ 아래 사람에게 연락할 수 있다. 급한 정보·경고·도움 요청·관계상 자연스러운 안부 등 ★분명한 이유가 있을 때만★(매 턴 금지).\n"
            + "닿는 상대: " + reach.toString().trim() + "\n"
            + "연락하려면 응답에 태그를 넣어라(말·글만, 행동 묘사 금지):\n"
            + "<NPC_CALL player=\"상대 이름\">전할 말 1~2문장</NPC_CALL>\n"
            + "- '전화 가능'은 통화로, '서면·쪽지 가능'은 글(쪽지·편지)로, '같은 곳'은 직접 말로 닿는다. 목록에 없는 사람에겐 닿지 않는다.\n"
            + (phoneUp ? "" : writable
                ? "- ★통신 두절★: 전화는 ★불가능★하다. '같은 곳' 상대에겐 직접, 멀리 있는 상대에겐 ★서면(쪽지·편지)★으로만 닿는다 — 전화는 시도하지 마라(헛수고).\n"
                : "- ★통신 두절★: 전화·원격 연락은 ★불가능★하다. 위 목록의 ‘같은 곳’ 상대에게 직접 말하는 것만 가능 — 멀리 있는 사람에게 전화 시도하지 마라(헛수고).\n")
            + "- 정체·해결법을 직접 누설하지 마라(괴담측이면 기만·유도만).";
    }

    /** 자율 NPC가 먼저 플레이어에게 연락(전화/직접). 닿을 때만 전달하고, 플레이어는 그 NPC 번호를 알게 된다(콜백 가능). */
    private void deliverNpcInitiatedContact(JsonObject npcObj, String npcId, String npcName, String npcZone,
                                            String targetName, String callMsg) {
        if (callMsg == null || callMsg.isBlank() || !isNpcCommunicable(npcObj)) return;
        PlayerData target = state.getAllPlayers().stream()
            .filter(pd -> matchesPlayerName(pd, targetName)).findFirst().orElse(null);
        if (target == null || target.isDead || !spawnedPlayers.contains(target.uuid)) return;
        Player tp = Bukkit.getPlayer(target.uuid);
        if (tp == null || !tp.isOnline()) return;
        // ★매체 판정★: 같은 곳=대면, 아니면 통신 가능하면 통화, 통신 두절이라도 서면 가능하면 서면(쪽지·편지). 모두 상대가 이 NPC 연락처를 알아야 원격 성립.
        boolean sameZone = !npcZone.isEmpty() && npcZone.equals(target.zone);
        boolean knowsNpc = target.everKnownNpcContacts.contains(npcId);
        boolean viaCall  = !sameZone && isPhoneUsable() && knowsNpc;
        boolean written  = !sameZone && !viaCall && writtenCommAvailable() && knowsNpc;
        if (!(sameZone || viaCall || written)) return; // 번호·수단도 모르고 멀리 있으면 닿지 않는다(추후 GM 정황으로)
        boolean remote = viaCall || written;
        // ★통신 변조★: @이름과 동일 — 매체형 괴담(음성형↔통화 / 문서형↔서면)이 원격 선연락을 가로채 바꿔 전달(30%). 대면은 변조 안 함.
        boolean tampered = remote && entityInterferes(written) && new java.util.Random().nextInt(100) < 30;
        String heard = tampered ? tamperText(callMsg, new java.util.Random()) : callMsg;
        String tag = sameZone ? "§a[근처] §f" : written ? "§b[✉ 수신] §f" : "§b[📞 수신] §f";
        tp.sendMessage(tag + npcName + ": " + heard);
        target.everKnownNpcContacts.add(npcId); // 연락받음 → 그 번호를 알게 됨(콜백 가능)
        appendNarrativeLog(target, (sameZone ? "[근처] " : written ? "[서면] " : "[수신] ") + npcName + ": " + heard);
        state.log("comm", npcName, "→ " + commDisplayName(target) + ": " + callMsg);
        // 뷰어 통신내역: NPC→플레이어 선연락도 수신자를 기록(수신자 시점·통신내역에 표시). 변조 시 원본+변형본 대조.
        String kind = written ? "letter" : (sameZone ? "nearby" : "call");
        if (tampered) gameLogger.logCommTampered(kind, npcName,
                java.util.List.of(commDisplayName(target)), callMsg, heard, written ? "괴담의 기록 변조" : "괴담의 음성 변조");
        else gameLogger.logComm(kind, npcName,
                java.util.List.of(commDisplayName(target)), callMsg);
        String medium = sameZone ? "직접" : written ? "서면으로" : "전화로";
        if (tampered)
            ai.injectGmSystem("[NPC 선연락·통신 변조] " + npcName + "이(가) " + commDisplayName(target)
                + "에게 " + medium + " 먼저 연락했으나 괴담이 가로채 \"" + callMsg + "\"를 \"" + heard
                + "\"로 바꿔 전했다. 플레이어는 변형된 말을 들었다 — 이후 정황·오해에 반영.");
        else
            ai.injectGmSystem("[NPC 선연락] " + npcName + "이(가) " + commDisplayName(target)
                + "에게 " + medium + " 먼저 연락했다: \"" + callMsg
                + "\". 시스템이 이미 그 플레이어에게 전달했으니 중복하지 말고 이후 정황·반응만 다뤄라.");
    }

    /**
     * 괴담 현상의 강도 지침. 오염도(corruption)와 타임라인 진행도에 비례한다.
     * 초반·저오염: 그냥 흘려보낼 사소한 위화감(시나리오 영향 없음).
     * 후반·고오염: 명백·불시·시나리오에 직접 영향 가능.
     */
    private String buildEntityIntensityGuide() {
        int corr  = corruptMan.getLevel();
        int stage = state.getTimelineStage();
        int sc    = scaleOrdinal();              // ★규모가 클수록 기본 위력이 높다
        int t = corr + Math.max(0, stage - 1) + sc;
        String scaleNote = sc >= 2
            ? "이 사건은 " + getStr(state.getGdamData(), "scale") + "급이다 — 위력·영향 범위가 그 규모에 걸맞게 크고, 같은 진행도라도 더 치명적이며 더 빨리 고조된다. "
            : "";
        if (t <= 1) {
            return scaleNote + "현재 강도: 매우 약함. 그냥 흘려보낼 만한, 있는 듯 없는 듯한 사소한 위화감 1문장만. "
                 + "시나리오 진행에 영향을 주는 사건·피해·직접 위협 절대 금지. 분위기만 아주 살짝.\n";
        } else if (t <= 3) {
            return scaleNote + "현재 강도: 중간. 신경 쓰이는 이상 현상 1문장. 아직 치명적이지 않게, 의심이 들 정도로만.\n";
        } else if (t <= 5) {
            return scaleNote + "현재 강도: 강함. 명백하고 불길한 현상. 불시에 닥쳐도 좋고, 시나리오에 직접 영향을 줄 수 있다. "
                 + "단, 여전히 이름·정체·1인칭은 노출 금지.\n";
        } else {
            return scaleNote + "현재 강도: 압도적. 광범위하고 치명적인 현상이 불시에 닥친다. 시나리오를 뒤흔들 직접 위협을 가해도 된다 "
                 + "(단 이름·정체·1인칭 노출 금지, 즉시 전멸 강요는 피하고 탈출·대응 여지는 남긴다).\n";
        }
    }

    /** 시나리오 규모 서열 (로컬0 < 시티1 < 내셔널2 < 글로벌3 < 코즈믹4). 불명은 0. */
    private int scaleOrdinal() {
        JsonObject g = state.getGdamData();
        if (g == null) return 0;
        String s = getStr(g, "scale");
        if (s.contains("코즈믹")) return 4;
        if (s.contains("글로벌")) return 3;
        if (s.contains("내셔널")) return 2;
        if (s.contains("시티"))   return 1;
        return 0;
    }

    private String getEntityName() {
        JsonObject gdam = state.getGdamData();
        if (gdam != null && gdam.has("entity")) {
            JsonObject e = gdam.getAsJsonObject("entity");
            if (e.has("name")) return e.get("name").getAsString();
        }
        return "???";
    }

    /**
     * 친숙 모드(프로젝트 문·게임)일 때 특성 생성기(시작·역할·보상)에 테마 지침을 주입한다.
     * 일반 시나리오면 빈 문자열로 초기화. 스테이지 시작(startSession) 직후마다 호출.
     *   - 시작 특성(charGen): ★가끔★ 섞임  ·  배역 전용 특성(roleFlavor): 배역도 자기 E.G.O.를 ★가끔★(스포일러 금지 유지)
     *   - 보상 특성(traitMan): ★주로/자주★ 반영
     */
    private void applyScenarioFlavor() {
        String startFlavor = "", rewardFlavor = "", roleFlavor = "";
        if (familiarMode && "projectmoon".equals(familiarFilter)) {
            String base = "## ★테마: 프로젝트 문(로보토미 코퍼레이션)\n"
                + "특수 능력·도구는 '전투 E.G.O.(전투표상)'·'E.G.O. 기프트'(환상체에서 추출한 무기·방어구·가호) 개념으로 붙인다. 직업(로보토미 직원·수사관 등)·역할과 어울리게. "
                + "★E.G.O. 이름은 반드시 ★실존 프로젝트 문 환상체★를 출처로 드러내라 — 형식 '[E.G.O GIFT] <원본 환상체명>' 또는 '[전투 E.G.O.] <원본 환상체명>'(예: [E.G.O GIFT] 백야, [전투 E.G.O.] 그을린 소녀). "
                + "★가공의 E.G.O. 이름(정념의 촉수·광채로운 의지 같은 ★창작명★)을 ★절대 지어내지 마라★ — 출처는 반드시 실존 환상체(한 죄악과 수백의 선행·백야·괴물 같은 건 없어·그을린 소녀·붉은 구두·벌하는 새·증오의 여왕 등)로 하고, 그 이름을 드러내라.";
            startFlavor  = base + " 시작 특성은 ★가끔★ 이 형태가 섞일 수 있다(1개 안팎, 전부는 아님).";
            rewardFlavor = base + " 보상 특성은 ★주로★ 이 형태로 명명·연출하라. "
                + "★이름 표기: 가호형은 '[E.G.O GIFT] <원본 환상체명>', 무기·방어구형은 '[전투 E.G.O.] <원본 환상체명>'로 원본 출처가 드러나게(예: [E.G.O GIFT] 눈의 여왕). "
                + "★E.G.O. 강화는 ★같은 이름 유지★ + 등급·효과만 강화(쿨다운 단축 아님, 정체성 불변). "
                + "★보상 중 ★최소 1개는 이번에 출현한 환상체(위 '괴담 테마')의 E.G.O.★를 반드시 포함하라 — 모든 플레이어 공통 이름·효과, 등급만 기여도로 차등(이 E.G.O.에 한해 환상체명 직접 사용 허용).";
            roleFlavor   = base + " 배역도 ★가끔★ 자신의 전투 E.G.O.·E.G.O. 기프트를 장비로 지닐 수 있다(직원 지급품 등). "
                + "단 위 스포일러 금지·범용성을 지켜 이번 괴담의 정체·소재는 드러내지 말고, 일반 장비처럼 범용으로 묘사하라.";
        } else if (familiarMode && "game".equals(familiarFilter)) {
            String ent = getEntityName();
            String base = "## ★테마: 게임 괴담(" + ent + ")\n"
                + "능력에 그 게임 특유의 메커니즘을 녹인다(예: 히로빈이면 '블록 부수기·순간이동·구조물 표식', 일반 게임이면 '리스폰·인벤토리·체크포인트'). "
                + "현재 소재·직업과 어울리게.";
            startFlavor  = base + " 시작 특성에 ★가끔★ 이런 게임적 능력이 섞일 수 있다(1개 안팎).";
            rewardFlavor = base + " 보상 특성은 게임적 능력으로 ★자주★ 연출하라.";
            // 배역 전용 특성 예외(E.G.O.)는 프로젝트 문 한정 — 게임은 배역 특성을 일반(범용·스포일러 안전) 그대로 둔다(roleFlavor="").
        }
        charGen.setScenarioFlavor(startFlavor);
        traitMan.setScenarioFlavor(rewardFlavor);
        traitMan.setRoleFlavor(roleFlavor);
    }

    // ──────────────────────────────────────────────────────────────
    //  플레이어 간 직접 통신
    // ──────────────────────────────────────────────────────────────

    /** 방송 설비(확성기·교내방송·인터컴 등)로 건물 전체에 외치는 '발화'로 보이는 행동인가. */
    private static boolean looksLikeBroadcast(String msg) {
        if (msg == null) return false;
        boolean device = msg.contains("방송") || msg.contains("확성") || msg.contains("메가폰")
            || msg.contains("마이크") || msg.contains("스피커") || msg.contains("인터컴")
            || msg.contains("구내방송") || msg.contains("교내방송") || msg.contains("안내방송")
            // 전체 범위 호출 표현도 방송으로 인정(번호 없이 다수에게)
            || msg.contains("전체 채널") || msg.contains("전체채널") || msg.contains("전 직원")
            || msg.contains("전원에게") || msg.contains("모두에게") || msg.contains("다들에게")
            || msg.contains("전체에게") || msg.contains("전체 공지") || msg.contains("단체 무전");
        // 무전/인터컴은 ★전체 범위일 때만★ 방송 — 1:1 무전(@번호)과 구분
        if (!device && msg.contains("무전")
                && (msg.contains("전체") || msg.contains("전원") || msg.contains("모두") || msg.contains("다들") || msg.contains("전 직원")))
            device = true;
        if (!device) return false;
        // ★청취(수동) 제외★: '방송을 듣/들으며 · 방송이 들린다/나온다/흘러나온다 · 방송 소리' 등은 방송을 ★듣는★ 상황이지
        //   내가 ★내보내는★ 게 아니다. (예: "방송을 들으며 '가자' 이동한다" → 방송이 아니라 평범한 행동 서술)
        //   방송을 언급했다고 무조건 송출되어 평범한 채팅이 방송으로 오인되던 불만을 수정.
        //   단, ★능동 송출 동사(방송한다/송출/외친다/내보낸다)가 함께 있으면★ 청취가 아니라 '들리도록 송출'이므로 억제하지 않는다.
        // '방송을 한다/해/했다', '방송 한다/해/했' = 능동 송출로 인정(따옴표·발화동사 없이 서술해도 방송으로 잡힘).
        //   단 '방송을 하는 걸 들었다'는 '한'≠'하는'이라 미포함 → 수동 청취 오탐 없음.
        boolean sendVerb = msg.contains("방송을 한") || msg.contains("방송을 해") || msg.contains("방송을 했")
                || msg.contains("방송 한다") || msg.contains("방송 해") || msg.contains("방송 했");
        boolean activeSend = sendVerb || msg.contains("방송한") || msg.contains("방송해") || msg.contains("방송했")
                || msg.contains("송출") || msg.contains("내보") || msg.contains("외치") || msg.contains("외쳐")
                || msg.contains("외쳤") || msg.contains("외침");
        // ★수신(청취) 제외★: 방송뿐 아니라 통신·무전·교신·음성·목소리가 '들리거나 흘러나오는' 상황은
        //   내가 ★내보내는★ 게 아니라 ★듣는★ 것이다(기기 단어가 있어도 방송 송출 아님).
        //   "통신을 듣고 뛰었다 / 스피커에서 통신이 흘러나오자 …" 같은 평범한 서술의 방송 오판을 막는다.
        //   ('들려오'는 능동 송출에도 흔히 붙어 과억제 → 제외.  activeSend가 있으면 수신 언급이 있어도 송출로 본다.)
        if (!activeSend) {
            for (String n : new String[]{"방송", "통신", "무전", "교신", "음성", "목소리"}) {
                if (msg.contains(n + "을 듣") || msg.contains(n + "을 들") || msg.contains(n + " 듣") || msg.contains(n + " 들")
                        || msg.contains(n + "이 듣") || msg.contains(n + "이 들") || msg.contains(n + "이 나오")
                        || msg.contains(n + "이 흘러") || msg.contains(n + "이 울려") || msg.contains(n + " 소리")
                        || msg.contains("나오는 " + n) || msg.contains("들리는 " + n) || msg.contains("흘러나오는 " + n))
                    return false;
            }
        }
        boolean utter = msg.indexOf('"') >= 0 || msg.indexOf('“') >= 0 || msg.indexOf('”') >= 0
            || msg.indexOf('\'') >= 0 || msg.indexOf('「') >= 0
            || msg.contains("말") || msg.contains("외치") || msg.contains("외쳐") || msg.contains("알린")
            || msg.contains("알려") || msg.contains("방송한") || msg.contains("방송해") || msg.contains("방송했")
            || msg.contains("송출") || msg.contains("내보") || msg.contains("전한") || sendVerb;
        return utter;
    }

    /** 방송 발화 내용 추출 — 따옴표 안이 있으면 그 부분, 없으면 행동 전체. */
    private static String extractSpoken(String msg) {
        if (msg == null) return "";
        int s = -1;
        for (char q : new char[]{'"', '“', '「', '\''}) {
            int idx = msg.indexOf(q);
            if (idx >= 0 && (s < 0 || idx < s)) s = idx;
        }
        if (s >= 0) {
            int e = -1;
            for (char q : new char[]{'"', '”', '」', '\''}) {
                int idx = msg.lastIndexOf(q);
                if (idx > s) e = Math.max(e, idx);
            }
            if (e > s + 1) return msg.substring(s + 1, e).trim();
        }
        return msg.trim();
    }

    /**
     * 방송 설비로 건물 전체에 외친 발화 — ★모든 등장 플레이어에게 직접 [방송]으로 전달★한다(구역 무관).
     * GM 서술/WITNESS에만 의존하던 누락을 막아, 번호 공지·집결 호출 같은 협업을 보장한다.
     * 일방향 방송이므로 연락처를 강제 교환하지 않는다(들은 사람은 공지된 번호를 직접 눌러 연락 가능).
     */
    private void deliverPlayerBroadcast(Player sender, PlayerData senderPd, String message) {
        String content = extractSpoken(message);
        if (content.isBlank()) return;
        String disp = senderPd.gmDisplayName();
        int heard = 0;
        java.util.List<String> heardNames = new ArrayList<>();
        for (PlayerData op : state.getAllPlayers()) {
            if (op.uuid.equals(senderPd.uuid) || op.isDead || !spawnedPlayers.contains(op.uuid)) continue;
            Player op2 = Bukkit.getPlayer(op.uuid);
            if (op2 != null && op2.isOnline()) {
                op2.sendMessage("§b[📢 방송] §f" + disp + ": " + content);
                appendNarrativeLog(op, "[방송] " + disp + ": " + content);
                heardNames.add(op.gmDisplayName());
                heard++;
            }
        }
        sender.sendMessage(heard > 0 ? "§7[방송 송출 — " + heard + "명에게 전달됨]"
                                     : "§8(방송했지만 지금 들을 다른 인원이 없습니다.)");
        state.log("comm", senderPd.name, "[방송] " + content);
        gameLogger.logComm("broadcast", disp, heardNames, content); // 뷰어 통화내역: 방송 수신자 기록
        noteEntityIntel(3, disp, content, "방송"); // 방송=개방 채널 → 괴담 수집 강함
        // GM·NPC 인지: 방송은 건물 전체로 퍼진 큰 행동. 내용은 시스템이 이미 전달했으니 중복 WITNESS 금지.
        if (currentPhase == Phase.HORROR || currentPhase == Phase.DAILY) {
            ai.injectGmSystem("[방송 송출] " + disp + "이(가) 방송 설비로 외쳤다: \"" + content
                + "\". 이 문구는 시스템이 현재 등장한 플레이어들에게 전달했다(같은 문구를 다시 <WITNESS>로 중복 전달 금지). "
                + "★범위★: '건물 구내 방송(PA)'이면 ★그 건물·시설 안에 있는 사람만★ 듣는다 — 건물 밖으로 나갔거나 멀리 떨어진 인원이 있으면 그 사람은 ★못 들은 것으로★ 다음 서술에 반영하라(광역 라디오·도시 방송이면 넓게 들림). "
                + "장면(스피커·반향)과 결과를 서술하고, 소통 가능한 NPC들도 들은 것으로 반영하라. "
                + "★괴담 개입★: 통신·소리를 감지·간섭하는 괴담이면 이 방송을 ★듣고 반응하거나, 내용을 왜곡해 스피커로 되쏘는(왜곡 재송출)★ 식으로 능동적으로 끼어들 수 있다(평범한 안내 방송에 둔감한 괴담은 무시).");
        }
    }

    /** '@ 메시지'(이름 없음) → 같은 구역(zone)에 있는 모든 사람에게 들리게 한다. 들은 사람과는 연락처를 교환한다. */
    private void proximityBroadcast(Player sender, PlayerData senderPd, String message) {
        String z = senderPd.zone == null ? "" : senderPd.zone;
        String disp = senderPd.gmDisplayName();
        sender.sendMessage("§7[근처에 말함] §f" + message);
        List<PlayerData> heard = new ArrayList<>();
        for (PlayerData op : state.getAllPlayers()) {
            if (op.uuid.equals(senderPd.uuid) || op.isDead) continue;
            if (!spawnedPlayers.contains(op.uuid)) continue;
            String oz = op.zone == null ? "" : op.zone;
            // 같은 구역(zone)이면 들린다 — 목소리는 같은 공간 안에 퍼진다.
            // (이전엔 세부위치 spot까지 일치해야 해서, 같은 방이라도 spot이 비거나 달라 안 들리는 버그가 있었다.)
            if (!z.equals(oz)) continue;
            heard.add(op);
        }
        if (heard.isEmpty()) sender.sendMessage("§8(근처에 들을 사람이 없습니다.)");
        for (PlayerData op : heard) {
            Player op2 = Bukkit.getPlayer(op.uuid);
            if (op2 != null && op2.isOnline()) op2.sendMessage("§e[근처] §f" + disp + ": " + message);
            exchangeContacts(senderPd, op); // 대면 성공 → 서로 번호를 알게 됨
        }
        state.log("comm", senderPd.name, "[근처] " + message);
        // 뷰어: 근처 발화는 ★들은 사람 전원(같은 구역)을 수신자로★ 기록 → 그들 시점에도 보이게
        java.util.List<String> nearNames = new ArrayList<>();
        for (PlayerData op : heard) nearNames.add(op.gmDisplayName());
        gameLogger.logComm("nearby", disp, nearNames, message);
        noteEntityIntel(2, disp, message, "근처 발화"); // 근처 발화 → 수집 중간(근처에 괴담 있으면 GM이 강하게 반영)
        // (입력 로그는 onChat 진입부에서 이미 1회 기록됨 — 여기서 중복 기록하지 않는다)
        // ★입으로 낸 '소리'다(기기 통신 아님 → 도청·차단·전화판정 무관). 단, 괴담이 소리·인기척을
        //   감지하는 성질이면 들을 수 있으므로, 괴담 파트에서만 GM에 알려 그 성질일 때만 반응하게 한다.
        if (currentPhase == Phase.HORROR) {
            ai.injectGmSystem("[근처 발화 — 소리] " + disp + "이(가) 주변에 소리 내어 말했다: \"" + message
                + "\". 이것은 기기 통신이 아니라 입으로 낸 소리다(도청·신호 추적 대상 아님). "
                + "괴담이 ★소리·인기척을 감지하는 성질일 때에만★ 이 소리에 반응·접근하도록 다음 서술에 자연스럽게 반영하고, 소리에 둔감한 괴담이면 무시하라.");
        }
    }

    /** 채팅 '@' 자동완성 후보: @전체 + 아는 연락처(이름·번호). 비활성/미참여면 빈 목록. */
    public List<String> commSuggestions(Player player) {
        if (!isActive()) return java.util.Collections.emptyList();
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return java.util.Collections.emptyList();
        List<String> out = new ArrayList<>();
        out.add("@전체");
        for (UUID u : pd.knownContacts) {
            PlayerData op = state.getPlayer(u);
            if (op == null) continue;
            if (!op.charName.isEmpty()) out.add("@" + op.charName);
            if (!op.contactId.isEmpty()) out.add("@" + op.contactId);
        }
        // ★주요(critical) NPC 중 이미 접촉했거나 같은 구역인 NPC만 @대상으로 제안(단역 제외)
        for (JsonObject npc : getCriticalNpcs()) {
            String nm = npc.has("name") ? npc.get("name").getAsString() : "";
            if (nm.isBlank()) continue;
            String id = npc.has("id") ? npc.get("id").getAsString() : "";
            String nz = npcZones.getOrDefault(id, npc.has("zone") ? npc.get("zone").getAsString() : "");
            boolean known = !id.isEmpty() && pd.everKnownNpcContacts.contains(id);
            boolean here  = !pd.zone.isEmpty() && pd.zone.equals(nz);
            if ((known || here) && isNpcCommunicable(npc)) {
                out.add("@" + nm); // 말 통하지 않는 NPC는 후보 제외
                String num = npcContactNumber(id);
                if (known && !num.isBlank()) out.add("@" + num); // 번호를 아는 NPC는 번호로도 제안
            }
        }
        return out;
    }

    /** '@전체 메시지' → 내가 번호를 아는(knownContacts) 모든 플레이어에게 기기로 발신. */
    private void broadcastToKnownContacts(Player sender, PlayerData senderPd, String message) {
        boolean bypass = hasCommBypass(senderPd); // 통신 개방 능력 발동 턴 — 두절·기기 제한 무시
        if (!bypass) {
            if (!isPhoneUsable()) { sender.sendMessage("§c통신이 두절되어 발신할 수 없습니다."); return; }
            if (!hasCommDevice(senderPd)) { sender.sendMessage("§c통신 기기가 없어 발신할 수 없습니다."); return; }
        }
        String disp = senderPd.gmDisplayName();
        List<PlayerData> targets = new ArrayList<>();
        for (UUID u : senderPd.knownContacts) {
            PlayerData op = state.getPlayer(u);
            if (op != null && !op.isDead) targets.add(op);
        }
        if (targets.isEmpty()) { sender.sendMessage("§7아는 번호가 없습니다. (먼저 연락처를 알아야 합니다)"); return; }
        sender.sendMessage("§7[전체 발신 " + targets.size() + "명] §f" + message);
        for (PlayerData op : targets) {
            Player op2 = Bukkit.getPlayer(op.uuid);
            if (op2 != null && op2.isOnline() && (bypass || hasCommDevice(op))) // 개방 시 수신자 기기 부재도 관통
                op2.sendMessage("§b[📞 " + disp + " → 전체] §f" + message);
        }
        state.log("comm", senderPd.name, "[전체발신] " + message);
        // 뷰어 통화내역: 전체 발신도 ★수신자 전원을 기록★(그들 시점에도 보이게)
        java.util.List<String> callNames = new ArrayList<>();
        for (PlayerData op : targets) callNames.add(op.gmDisplayName());
        gameLogger.logComm("call", disp, callNames, message);
        noteEntityIntel(3, disp, message, "전체 발신"); // 아는 번호 전원 발신 → 수집 강함
        // (입력 로그는 onChat 진입부에서 이미 1회 기록됨 — 여기서 중복 기록하지 않는다)
        if (commDetectableByEntity(senderPd)) noteCommUsedIfDangerous(senderPd, "전체 발신"); // 은밀 개방이면 괴담이 감지 못함
    }

    private void handleDirectComm(Player sender, PlayerData senderPd, String raw) {
        String content = raw.substring(1).trim(); // '@' 제거
        if (content.isEmpty()) {
            sender.sendMessage("§c사용법: §f@이름/@번호 메시지§7 · §f@전체 메시지§7(아는 번호 전원) · §f@ 메시지§7(근처에 말하기)");
            return;
        }
        // 대상 토큰 식별: 캐릭터명·NPC명에 띄어쓰기가 있을 수 있으므로(예: "라비 샤르마"),
        // 알려진 이름 중 content가 시작하는 ★가장 긴★ 이름을 토큰으로 본다. 없으면 첫 단어.
        String token   = matchCommToken(content);
        String message = content.length() > token.length() ? content.substring(token.length()).trim() : "";

        // @전체 → 내가 아는 번호의 모든 사람에게 발신
        if (token.equals("전체") || token.equalsIgnoreCase("all")) {
            if (message.isEmpty()) { sender.sendMessage("§c사용법: @전체 메시지"); return; }
            broadcastToKnownContacts(sender, senderPd, message);
            return;
        }

        // 대상 식별: 숫자면 연락처 번호로 다이얼, 아니면 이름
        boolean dialedByNumber = token.matches("\\d{3,5}");
        PlayerData targetPd = dialedByNumber ? findByContactId(token) : findByName(token);
        JsonObject npcObj = (!dialedByNumber && targetPd == null) ? findNpcByName(token) : null;

        // 번호도, 아는 대상(플레이어/NPC)도 아닌 토큰 → '이름 없이 근처에 말하기'로 처리(같은 구역·세부위치에 전달).
        if (!dialedByNumber && targetPd == null && npcObj == null) {
            proximityBroadcast(sender, senderPd, content); // 이름 토큰이 없으므로 content 전체가 발화 내용
            return;
        }
        // 숫자로 걸었지만 플레이어 번호가 아니면 → NPC 번호인지 확인(올바른 번호 입력 = 그 번호를 안다).
        if (dialedByNumber && targetPd == null) {
            JsonObject npcByNum = findNpcByContactNumber(token);
            if (npcByNum != null) {
                if (message.isEmpty()) { sender.sendMessage("§c사용법: @번호 메시지"); return; }
                String nid = getStr(npcByNum, "id");
                if (!nid.isEmpty()) senderPd.everKnownNpcContacts.add(nid); // 올바른 번호 입력 = 번호를 안다
                handleNpcDirectComm(sender, senderPd, npcByNum, message);
                return;
            }
            sender.sendMessage("§c연결되지 않는 번호입니다. §7(존재하지 않는 번호)");
            return;
        }
        // NPC 대상
        if (targetPd == null && npcObj != null) {
            if (message.isEmpty()) { sender.sendMessage("§c사용법: @이름 메시지"); return; }
            handleNpcDirectComm(sender, senderPd, npcObj, message);
            return;
        }
        if (message.isEmpty()) { sender.sendMessage("§c사용법: @이름(또는 번호) 메시지"); return; }
        if (targetPd.uuid.equals(sender.getUniqueId())) {
            sender.sendMessage("§c자기 자신에게 통신할 수 없습니다.");
            return;
        }

        // 도달 가능성 판정 (viaDevice = 기기 통신 여부, written = 전자통신 대신 필담/편지)
        boolean viaDevice;
        boolean written = false;
        if (!senderPd.zone.isEmpty() && senderPd.zone.equals(targetPd.zone)) {
            viaDevice = false; // 같은 구역 → 대면 (번호 불필요)
        } else {
            Set<UUID> channels = commChannels.get(sender.getUniqueId());
            boolean gmChannel = channels != null && channels.contains(targetPd.uuid);
            if (gmChannel) {
                viaDevice = true; // GM 개설 채널 → 번호 불필요 (시나리오 통신 차단과 무관하게 작동)
            } else {
                boolean bypass = hasCommBypass(senderPd); // 통신 개방 능력 발동 턴 — 두절·기기 제한 무시
                // 시나리오상 전자 통신이 불가(시대상 부재·두절)면 → ★필담(편지·쪽지)★이 가능한 세계면 그걸로 전한다. 아니면 차단.
                if (!bypass && !isPhoneUsable()) {
                    if (writtenCommAvailable()) { written = true; }
                    else { sender.sendMessage("§c통신이 두절되어 기기로 연락할 수 없습니다. (직접 찾아가야 합니다)"); return; }
                }
                // 전자 기기 통신: 양쪽 모두 기기 보유 필요. 기기가 없어도 필담이 가능한 세계면 종이로 전한다.
                if (!written && !bypass && (!hasCommDevice(senderPd) || !hasCommDevice(targetPd))) {
                    if (writtenCommAvailable()) { written = true; }
                    else { sender.sendMessage("§c근처에 없고 통신 기기로도 닿지 않습니다. (직접 찾아가거나 다른 방법이 필요)"); return; }
                }
                // ★전화번호를 직접 입력해 거는 통화는 서로 모르는 사이여도 연결된다 —
                //   실제 소유자가 있는 올바른 번호를 입력했다는 것 자체가 '그 번호를 안다'는 뜻이다.
                //   (위에서 존재하지 않는 번호는 이미 걸러졌다.) 이름으로 거는 통화만 상대 번호를 미리 입수해야 한다.
                if (!dialedByNumber) {
                    boolean contactKnown = senderPd.knownContacts.contains(targetPd.uuid)
                        || targetPd.knownContacts.contains(senderPd.uuid);
                    if (!contactKnown) {
                        sender.sendMessage("§c" + commDisplayName(targetPd) + "의 번호를 몰라 전화할 수 없습니다. "
                            + "§7직접 만나 번호를 교환하거나, 근처라면 §f@ 메시지§7로 말을 거세요.");
                        return;
                    }
                }
                viaDevice = true;
            }
        }

        // 괴담이 정체를 차용한 배역이면 → 괴담이 그 사람인 척 기만 응답
        if (targetPd.impersonated) {
            deliverImpersonatedReply(sender, senderPd, targetPd, message, viaDevice);
            return;
        }

        deliverDirectMessage(sender, senderPd, targetPd, message, viaDevice, written);
        exchangeContacts(senderPd, targetPd);
        if (viaDevice && commDetectableByEntity(senderPd)) noteCommUsedIfDangerous(senderPd, written ? "편지/필담" : "전화/무전"); // 은밀 개방이면 괴담이 감지 못함
    }

    /** 시나리오상 통신기기가 작동하는가 (constraints.phone_usable, 기본 true). GM 개설 채널은 이와 무관하게 작동. */
    private boolean isPhoneUsable() {
        JsonObject gdam = state.getGdamData();
        if (gdam != null && gdam.has("constraints") && gdam.get("constraints").isJsonObject()) {
            JsonObject c = gdam.getAsJsonObject("constraints");
            if (c.has("phone_usable")) return c.get("phone_usable").getAsBoolean();
        }
        return true;
    }

    /** 통신 유인형 괴담인가 — 기기 통신 '사용 자체'가 괴담을 부르는 시나리오(constraints.comms_dangerous). */
    private boolean isCommsDangerous() {
        JsonObject gdam = state.getGdamData();
        if (gdam != null && gdam.has("constraints") && gdam.get("constraints").isJsonObject()) {
            JsonObject c = gdam.getAsJsonObject("constraints");
            return c.has("comms_dangerous") && c.get("comms_dangerous").getAsBoolean();
        }
        return false;
    }

    /** 통신 유인형에서 기기 통신을 쓴 경우 — GM이 다음 서술에서 괴담의 응답·추적을 반영하도록 알린다. */
    private void noteCommUsedIfDangerous(PlayerData senderPd, String how) {
        if (!isCommsDangerous()) return;
        // ★플레이어에게 직접 경고 메세지를 띄우지 않는다 — '통신=위험'은 플레이어가 진행하며 GM 서술로
        //   자연히 깨달아야 할 사실이다(즉시 힌트 노출 금지). GM 컨텍스트에만 주입해 정황으로만 드러낸다.
        ai.injectGmSystem("[통신 유인 발동] " + commDisplayName(senderPd) + "이(가) 기기 통신을 사용했다(" + how
            + "). 통신 유인형 규칙에 따라 괴담이 그 신호에 ★응답·추적·강화★되도록 다음 서술에 반영하라(즉사 과잉 금지, 위협 고조). "
            + "단, '통신을 써서 위험해졌다'고 시스템처럼 못박지 말고 정황(소리·기척·접근 등)으로만 서서히 드러내라.");
    }

    /** 통신 개방 능력이 이번 턴 활성인가 — 발동 턴 == 현재 턴이면 통신 제한을 무시한다(턴이 넘어가면 자동 만료). */
    private boolean hasCommBypass(PlayerData pd) {
        if (pd == null) return false;
        return commBypassTurn.getOrDefault(pd.uuid, -999) == state.getCurrentTurn();
    }

    /** 이번 턴 통신 개방이 '은밀형'인가(괴담 감지 불가). */
    private boolean isCommBypassStealth(PlayerData pd) {
        return pd != null && hasCommBypass(pd) && commBypassStealth.getOrDefault(pd.uuid, false);
    }

    /** 이 통신을 괴담이 감지할 수 있는가 — 은밀형 통신 개방 중이면 감지 불가(통신 유인·추적 억제). */
    private boolean commDetectableByEntity(PlayerData pd) {
        return !isCommBypassStealth(pd);
    }

    /** 문자열에 후보 키워드 중 하나라도 포함되는가. */
    private static boolean containsAny(String hay, String... needles) {
        if (hay == null) return false;
        for (String n : needles) if (n != null && !n.isEmpty() && hay.contains(n)) return true;
        return false;
    }

    /** 통신 기기(전화·무전기 등) 소지 여부 */
    private boolean hasCommDevice(PlayerData pd) {
        for (String id : pd.heldItemIds) {
            String low = id.toLowerCase();
            for (String kw : COMM_ITEM_KEYWORDS) if (low.contains(kw)) return true;
        }
        return false;
    }

    private PlayerData findByContactId(String id) {
        // 정체 차용된(죽었지만 괴담이 행세 중인) 배역도 연결 대상에 포함
        return state.getAllPlayers().stream()
            .filter(pd -> id.equals(pd.contactId) && (!pd.isDead || pd.impersonated))
            .findFirst().orElse(null);
    }

    private PlayerData findByName(String name) {
        return state.getAllPlayers().stream()
            .filter(pd -> (pd.name.equalsIgnoreCase(name) || pd.charName.equalsIgnoreCase(name))
                && (!pd.isDead || pd.impersonated))
            .findFirst().orElse(null);
    }

    private PlayerData findAnyByName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        // GM은 ★캐릭터명★으로 ZONE_UPDATE/태그를 출력하므로 charName을 먼저 매칭한다.
        // (계정명만 매칭하면 GM이 캐릭터명을 쓸 때 null → 방 이동·강제이동이 통째로 무시됨)
        return state.getAllPlayers().stream()
            .filter(pd -> n.equalsIgnoreCase(pd.charName) || n.equalsIgnoreCase(pd.name) || n.equalsIgnoreCase(pd.roleId))
            .findFirst().orElse(null);
    }

    /**
     * @통신 대상 토큰 추출 — 띄어쓰기가 포함된 캐릭터명·NPC명(예: "라비 샤르마")도 인식하도록
     * 알려진 이름(전체/all·플레이어 캐릭터명·계정명·연락처번호·NPC명) 중 content가 시작하는
     * ★가장 긴★ 이름을 토큰으로 반환한다. 매칭이 없으면 첫 단어를 토큰으로 본다.
     */
    private String matchCommToken(String content) {
        String lc = content.toLowerCase();
        String best = null;
        List<String> cands = new ArrayList<>();
        cands.add("전체"); cands.add("all");
        for (PlayerData op : state.getAllPlayers()) {
            if (op.charName != null && !op.charName.isEmpty())   cands.add(op.charName);
            if (op.name != null && !op.name.isEmpty())           cands.add(op.name);
            if (op.contactId != null && !op.contactId.isEmpty()) cands.add(op.contactId);
        }
        for (JsonObject npc : getCriticalNpcs()) { // ★주요 NPC 이름만 @대상 토큰으로 인식(단역 제외)
            String nm = npc.has("name") ? npc.get("name").getAsString() : "";
            if (!nm.isBlank()) cands.add(nm);
        }
        for (String c : cands) {
            if (c == null || c.isEmpty()) continue;
            String clc = c.toLowerCase();
            if ((lc.equals(clc) || lc.startsWith(clc + " ")) && (best == null || c.length() > best.length()))
                best = c;
        }
        if (best != null) return content.substring(0, best.length()); // 입력 원문 그대로(대소문자 보존)
        int sp = content.indexOf(' ');
        return sp == -1 ? content : content.substring(0, sp);
    }

    /** critical NPC 목록에서 이름으로 검색 */
    private JsonObject findNpcByName(String name) {
        for (JsonObject npc : getCriticalNpcs()) { // ★주요(critical) NPC만 @직접 대화·통화 대상 — 단역 제외
            String npcName = npc.has("name") ? npc.get("name").getAsString() : "";
            String npcId   = npc.has("id")   ? npc.get("id").getAsString()   : "";
            if ((!npcName.isEmpty() && npcName.equalsIgnoreCase(name))
                || (!npcId.isEmpty() && npcId.equalsIgnoreCase(name))) return npc;
        }
        return null;
    }

    /**
     * ② 플레이어 → NPC 직접 심문.
     * GM round-trip 없이 NPC AI(Haiku)가 직접 응답.
     * 대면은 같은 zone에서만. CODE-9: 다른 zone이어도 phone_usable + 발신자 통신기기 + NPC 통화 가능 시 '통화'로 허용.
     */
    private void handleNpcDirectComm(Player sender, PlayerData senderPd, JsonObject npcObj, String message) {
        String npcId   = npcObj.has("id")   ? npcObj.get("id").getAsString()   : "npc";
        String npcName = npcObj.has("name") ? npcObj.get("name").getAsString() : "NPC";
        String npcZone = npcZones.getOrDefault(npcId,
            npcObj.has("zone") ? npcObj.get("zone").getAsString() : "");

        // 말이 통하지 않는 상대(시신·혼수·함구·비소통 존재 등)는 @로 대화·호출할 수 없다.
        if (!isNpcCommunicable(npcObj)) {
            sender.sendMessage("§7[" + npcName + "] 아무 반응이 없다. 말을 걸 수 있는 상대가 아니다.");
            return;
        }

        // 대면 가능 여부 (같은 zone)
        boolean sameZone = senderPd.zone.isEmpty() || senderPd.zone.equals(npcZone);
        // CODE-9: 원격 연락 가능 여부 — 대면 제한은 '대면 행위'에만 적용한다.
        //   ①phone_usable + 발신자 통신기기 + (NPC가 통화로 닿거나 ★이미 접촉해 번호를 아는★ NPC) → 통화(viaCall)
        //   ②통신 두절이라도 시대·맥락상 서면(필담·인편·쪽지)이 가능하고 닿는 NPC면 → 서면(written)
        boolean viaCall = false;
        boolean written = false;
        if (!sameZone) {
            boolean knownContact = senderPd.everKnownNpcContacts.contains(npcId); // 내가 먼저 접촉해 번호를 아는 NPC
            boolean reachable = isNpcPhoneReachable(npcObj) || knownContact;
            if (isPhoneUsable() && hasCommDevice(senderPd) && reachable) {
                viaCall = true;
            } else if (writtenCommAvailable() && reachable) {
                written = true; // ★서면 연락★: 통신이 안 돼도 필담·인편으로 닿는다(시대·맥락 허용 시)
            } else if (!isPhoneUsable()) {
                sender.sendMessage("§c통신이 두절되어 " + npcName + "에게 연락할 수 없습니다. (직접 찾아가야 합니다)");
                return;
            } else {
                sender.sendMessage("§c" + npcName + "은(는) 같은 위치에 없고 연락으로도 닿지 않습니다. 직접 찾아가야 합니다.");
                return;
            }
        }
        final boolean remote = viaCall || written; // 원격(통화·서면) 여부 — 변조·시점 처리에 사용

        // 대면이든 통화든 ★접촉하면 연락처를 기억★ — 이후 다른 곳에서도 전화로 부를 수 있다(다회차 이월).
        senderPd.everKnownNpcContacts.add(npcId);
        refreshCommItems(senderPd);
        npcLastDirectTurn.put(npcId, state.getCurrentTurn()); // 대화 중 — 자율 AI 중복 구동 방지(맥락 오염 차단)
        // 뷰어 통신내역: 플레이어→NPC 발신 기록(수신자=NPC) — 매체(통화/서면/대면)별 kind
        gameLogger.logComm(written ? "letter" : (viaCall ? "call" : "nearby"), senderPd.gmDisplayName(),
            java.util.List.of(npcName), message);
        // ★괴담 정보 수집·성장★: NPC와의 소통은 수집도 '중간'. 지능·소통·고위력 괴담이면 GM에 역이용 지시 주입.
        noteEntityIntel(2, senderPd.gmDisplayName(), message, "NPC 소통");

        // ③ 엿보기 특성 여부 확인
        boolean hasEavesdrop = senderPd.traits.stream()
            .anyMatch(t -> t.id.contains("엿보기") || t.id.contains("eavesdrop"));

        String relLabel  = relationshipLabel(senderPd.roleId, npcId);
        // GM→NPC 행동 서술(#1·#2): NPC가 인지하는 범위(자기 zone)의 최근 장면을 함께 줘 대화가 현재 상황과 어긋나지 않게.
        //   대면이면 플레이어와 같은 zone이라 플레이어의 최근 행동이 포함됨 / 통화면 NPC 자기 zone 상황만(원격 장면 누출 방지).
        String sceneLog = state.buildEntityLog(3, npcZone);
        // 지식 게이팅 문맥 = 플레이어 발화 + 현재 장면(관련 기억만 떠오르게)
        String npcPrompt = buildNpcDirectConvPrompt(npcObj, hasEavesdrop, viaCall, written, message + " " + (sceneLog == null ? "" : sceneLog));
        String situation = (sceneLog == null || sceneLog.isBlank()) ? ""
            : "[지금 " + (remote ? "네 주변에서" : "이곳에서") + " 일어나는 일(네가 직접 보고 들은 것)]\n" + sceneLog + "\n\n";
        String userMsg   = situation + "[" + senderPd.gmDisplayName() + (viaCall ? "이/가 전화로 말한다" : written ? "이/가 글(쪽지·편지)로 전한다" : "이/가 말한다")
            + " · 상대 나이: " + senderPd.age + "세"
            + " · 상대의 설득력·존재감: " + chaControlNote(senderPd)
            + " · 너와의 관계: " + (relLabel.isBlank() ? "모르는 사이(낯선 상대)" : relLabel)
            + "] " + message;

        sender.sendMessage((viaCall ? "§7[📞→ " : written ? "§7[✉→ " : "§7[→ ") + npcName + "] §f" + message);

        final boolean viaCallF = viaCall; // 람다 캡처용(viaCall은 위에서 재대입되어 effectively final 아님)
        final boolean writtenF = written;
        ai.callNpcAi(npcId, npcPrompt, userMsg).thenAccept(npcResp -> {
            if (npcResp == null || npcResp.startsWith("§c")) return;

            // ③ 엿보기: 내면 사고를 먼저 비공개로 전달
            if (hasEavesdrop) {
                String thought = ai.parseThoughtTag(npcResp);
                if (thought != null && !thought.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (sender.isOnline())
                            traitReveal(sender, senderPd, "[엿보기] " + npcName + " 속마음: " + thought, true);
                    });
                }
            }

            String visible = ai.stripThought(ai.stripTags(npcResp)).trim();
            if (visible.isEmpty()) return;
            // ★통신 변조★: 매체형 괴담(음성형↔통화 / 문서형↔서면)이 원격 답신을 가로채 바꿔 전달(30%). 대면(sameZone)은 변조 안 함.
            final boolean tamperedR = remote && entityInterferes(writtenF) && new java.util.Random().nextInt(100) < 30;
            final String heardR = tamperedR ? tamperText(visible, new java.util.Random()) : visible;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sender.isOnline())
                    sender.sendMessage("§e[" + npcName + "] §f" + heardR);
            });

            // GM 컨텍스트에 요약만 주입 (전체 대화 노출 방지)
            String summary = visible.length() > 120 ? visible.substring(0, 120) + "…" : visible;
            ai.injectGmSystem("[NPC " + (viaCallF ? "통화" : writtenF ? "서면" : "직접 대화") + "] " + commDisplayName(senderPd) + " → " + npcName
                + ": \"" + (message.length() > 60 ? message.substring(0, 60) + "…" : message)
                + "\" / " + npcName + " 반응: " + summary);

            // 뷰어: NPC 답신을 '발신자에게 온 통신'으로 기록(수신자=발신자) → ★발신자 시점에서도 대화가 보이게★ 양방향 연결.
            //   (기존 logGmOutput은 to가 없어 발신자 개별 시점에 답이 안 떠 대화 흐름을 못 따라가던 문제 해결)
            //   변조되면 원본+변형본을 함께 기록(뷰어 원본/변형됨 대조).
            String kindR = writtenF ? "letter" : (viaCallF ? "call" : "nearby");
            if (tamperedR) gameLogger.logCommTampered(kindR, npcName,
                    java.util.List.of(senderPd.gmDisplayName()), visible, heardR, writtenF ? "괴담의 기록 변조" : "괴담의 음성 변조");
            else gameLogger.logComm(kindR, npcName,
                    java.util.List.of(senderPd.gmDisplayName()), visible);
        });
    }

    /**
     * CODE-9: NPC가 통화(원격 연락)로 닿을 수 있는가.
     * phone_number/contact 필드가 있으면 통화 가능. 명시적으로 reachable=false면 불가.
     * 둘 다 없으면 기본 통화 가능(phone_usable·발신자 기기 조건은 호출부에서 이미 검증).
     */
    /** NPC가 말이 통하는(대화 가능한) 상대인가 — 시신·혼수·함구·비소통 존재 등은 communicable=false(또는 mute=true)로 @대화 차단. */
    private boolean isNpcCommunicable(JsonObject npcObj) {
        if (npcObj == null) return false;
        if (npcObj.has("communicable") && !npcObj.get("communicable").getAsBoolean()) return false;
        if (npcObj.has("mute") && npcObj.get("mute").getAsBoolean()) return false;
        return true;
    }

    private boolean isNpcPhoneReachable(JsonObject npcObj) {
        if (npcObj == null) return false;
        if (npcObj.has("reachable") && !npcObj.get("reachable").getAsBoolean()) return false;
        if (npcObj.has("phone_usable") && !npcObj.get("phone_usable").getAsBoolean()) return false;
        return true;
    }

    /** 특성의 개인화 발현(origin)을 GM 컨텍스트 접미로. 없으면 빈 문자열. (능력 Phase A) */
    private static String originSuffix(TraitData t) {
        return (t == null || t.origin == null || t.origin.isBlank())
            ? "" : " [발현 계기: " + t.origin + "]";
    }

    // ──────────────────────────────────────────────────────────────
    //  아이템 Phase II — 기계 효과 아이템 등록·사용
    // ──────────────────────────────────────────────────────────────

    /** heldItemIds 추가 + item_type이 있으면 ItemInstance(런타임 상태) 등록 */
    private void noteHeldItem(PlayerData pd, String itemId) {
        if (pd == null || itemId == null || itemId.isBlank()) return;
        boolean isNew = !pd.heldItemIds.contains(itemId); // 최초 획득만 로그(중복 방지)
        pd.heldItemIds.add(itemId);
        JsonObject def = itemMan.findDef(itemId);
        if (def != null && def.has("item_type")
                && !def.get("item_type").getAsString().isBlank()
                && !pd.itemStates.containsKey(itemId)) {
            pd.itemStates.put(itemId, buildItemInstance(def, itemId));
        }
        // ★아이템 획득 로그★ — 시작 소지품 포함, 뷰어의 아이템 뱃지 + 재생 진행연동 상태패널(그 시점에 아는 것)에 표시.
        if (isNew && gameLogger != null) {
            String nm = def != null && def.has("name")  ? def.get("name").getAsString()
                      : def != null && def.has("title") ? def.get("title").getAsString() : itemId;
            gameLogger.logItem("item", pd.gmDisplayName(), nm, state.getCurrentTurn() <= 1 ? "시작 소지" : "");
        }
        refreshCommItems(pd); // 새 아이템(통신 기기 포함) 지급 시 연락법·연락처 표기 갱신
    }

    /** key_items 정의(JsonObject)로부터 런타임 ItemInstance 생성 (charges는 type별 소스에서 유추) */
    private ItemInstance buildItemInstance(JsonObject def, String fallbackId) {
        ItemInstance inst = new ItemInstance();
        inst.id   = def.has("id")   ? def.get("id").getAsString()   : fallbackId;
        inst.name = def.has("name") ? def.get("name").getAsString()
                  : (def.has("title") ? def.get("title").getAsString() : fallbackId);
        inst.itemType = def.has("item_type") ? def.get("item_type").getAsString() : "";
        if (def.has("item_params") && def.get("item_params").isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : def.getAsJsonObject("item_params").entrySet()) {
                JsonElement v = en.getValue();
                try {
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                        inst.params.put(en.getKey(), v.getAsInt());
                    } else if ("unlocks".equals(en.getKey())) {
                        inst.unlocks = v.getAsString();
                    }
                } catch (Exception ignored) {}
            }
        }
        if (def.has("charges") && def.get("charges").isJsonPrimitive()) {
            try { inst.charges = def.get("charges").getAsInt(); } catch (Exception ignored) {}
        }
        if (inst.charges < 0) {
            inst.charges = switch (inst.itemType) {
                case "light"      -> inst.param("charges", -1);
                case "weapon"     -> inst.param("ammo", -1);
                case "consumable" -> inst.param("uses", 1);
                default           -> -1;
            };
        }
        return inst;
    }

    /** &lt;ITEM_USE&gt; 적용: charge·on·broken·unlock·consume·produce 상태 갱신 */
    private void applyItemUse(JsonObject use) {
        if (use == null) return;
        String pname   = use.has("player") ? use.get("player").getAsString() : null;
        String itemRef = use.has("item")   ? use.get("item").getAsString()   : null;
        if (itemRef == null || itemRef.isBlank()) return;
        PlayerData pd  = (pname == null || pname.isBlank()) ? null : findByName(pname);
        ItemInstance inst = (pd != null) ? resolveItemInstance(pd, itemRef) : null;

        if (inst != null && use.has("charge") && !use.get("charge").isJsonNull()) {
            try {
                int delta = use.get("charge").getAsInt();
                if (inst.charges >= 0) {
                    inst.charges = Math.max(0, inst.charges + delta);
                    if (inst.charges == 0) inst.broken = true;
                }
            } catch (Exception ignored) {}
        }
        if (inst != null && use.has("on") && !use.get("on").isJsonNull()) {
            try { inst.on = use.get("on").getAsBoolean(); } catch (Exception ignored) {}
        }
        if (inst != null && use.has("broken") && !use.get("broken").isJsonNull()
                && use.get("broken").getAsBoolean()) {
            inst.broken = true;
        }
        // 구역 해제 (열쇠·도구)
        if (use.has("unlock") && !use.get("unlock").isJsonNull()) {
            String zone = use.get("unlock").getAsString();
            if (!zone.isBlank()) {
                state.markZoneUnlocked(zone);
                state.log("system", pname == null ? "?" : pname, "[구역 해제: " + zoneDisplayName(zone) + "]");
            }
        }
        // 부품 소모(조합/소진)
        if (pd != null && use.has("consume") && !use.get("consume").isJsonNull()) {
            String c = use.get("consume").getAsString();
            if (!c.isBlank()) { pd.heldItemIds.remove(c); pd.itemStates.remove(c); }
        }
        // 결과물 생성(조합)
        if (use.has("produce") && !use.get("produce").isJsonNull()) {
            String prod = use.get("produce").getAsString();
            if (!prod.isBlank()) {
                JsonObject grant = new JsonObject();
                grant.addProperty("item_id", prod);
                grant.addProperty("player", pname == null ? "ALL" : pname);
                grant.addProperty("chapter_bound", true);
                itemMan.processGrant(grant, new ArrayList<>(Bukkit.getOnlinePlayers()));
                if (pd != null) {
                    noteHeldItem(pd, prod);
                    ItemInstance made = pd.itemStates.get(prod);
                    if (made != null && use.has("consume") && !use.get("consume").isJsonNull())
                        made.transformedFrom = use.get("consume").getAsString();
                }
            }
        }
        gameLogger.logEvent("ITEM_USE: " + (pname == null ? "?" : pname) + " / " + itemRef
            + (inst != null ? " (잔량 " + inst.charges + (inst.broken ? ", 소진" : "") + ")" : ""));
    }

    /** 플레이어 소지 ItemInstance를 id 또는 이름으로 탐색 */
    private ItemInstance resolveItemInstance(PlayerData pd, String ref) {
        if (ref == null || ref.isBlank()) return null;
        if (pd.itemStates.containsKey(ref)) return pd.itemStates.get(ref);
        for (ItemInstance it : pd.itemStates.values())               // 1순위: 정확 일치
            if (ref.equalsIgnoreCase(it.id) || ref.equalsIgnoreCase(it.name)) return it;
        ItemInstance match = null; int n = 0;                         // 2순위: 부분일치는 '유일할 때만'
        for (ItemInstance it : pd.itemStates.values())
            if (!it.name.isBlank() && (it.name.contains(ref) || ref.contains(it.name))) { match = it; n++; }
        return n == 1 ? match : null;                                 // 모호하면 오매칭 방지 위해 null
    }

    // ──────────────────────────────────────────────────────────────
    //  아이템 Phase IV — 구역 게이트(잠금) 판정 + 통합
    // ──────────────────────────────────────────────────────────────

    /** constraints.gated_zones[]에서 zoneId 게이트 정의 반환 (없으면 null) */
    private JsonObject findGatedZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) return null;
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("constraints") || !gdam.get("constraints").isJsonObject()) return null;
        JsonObject c = gdam.getAsJsonObject("constraints");
        if (!c.has("gated_zones") || !c.get("gated_zones").isJsonArray()) return null;
        for (JsonElement el : c.getAsJsonArray("gated_zones")) {
            if (!el.isJsonObject()) continue;
            JsonObject z = el.getAsJsonObject();
            if (z.has("zone") && zoneId.equals(z.get("zone").getAsString())) return z;
        }
        return null;
    }

    /**
     * 게이트 통과 사유: "open"(미게이트·이미 해제) / "key"(열쇠·도구로 실제 개방 → 전 파티 전파) /
     * "mobility"(개인 돌파 → 전파 안 함) / ""(차단). 아이템↔특성 통합 + A2(도구 bypass).
     */
    private String gatePassReason(PlayerData pd, String zoneId) {
        JsonObject gz = findGatedZone(zoneId);
        if (gz == null) return "open";                    // 게이트 아님
        if (state.isZoneUnlocked(zoneId)) return "open";  // 이미 해제됨
        if (pd != null) {
            boolean hasBypass = gz.has("bypass") && !gz.get("bypass").getAsString().isBlank();
            for (ItemInstance it : pd.itemStates.values()) {
                if (!it.usable()) continue;
                if ("key".equals(it.itemType) && zoneId.equals(it.unlocks)) return "key"; // 자물쇠 해제(전 파티 전파)
                if ("tool".equals(it.itemType)) {
                    if (zoneId.equals(it.unlocks)) return "key";   // 도구가 그 잠금 자체를 연다(전파)
                    if (hasBypass) return "bypass";                // A2/#4: 도구로 물리 우회 → ★본인만 통과(전파 안 함)
                }
            }
            for (TraitData t : pd.traits)
                if ("mobility".equals(t.effectType)) return "mobility"; // 개인 돌파(본인만, 전파 안 함)
        }
        return "";
    }

    /** 통과 가능 여부 (사유 무시). */
    private boolean canPassGate(PlayerData pd, String zoneId) {
        return !gatePassReason(pd, zoneId).isEmpty();
    }

    /** 평가 등급을 기여도 점수로 누적 (S=5..F=0) — 능력 Phase C */
    private void accrueContribution(Map<String, String> grades) {
        if (grades == null) return;
        grades.forEach((name, g) -> {
            PlayerData pd = findByName(name);
            if (pd != null) pd.contribution += gradeToPoints(g);
        });
    }

    private int gradeToPoints(String g) {
        if (g == null) return 0;
        return switch (g.trim().toUpperCase()) {
            case "S" -> 5; case "A" -> 4; case "B" -> 3;
            case "C" -> 2; case "D" -> 1; default -> 0;
        };
    }

    /**
     * 시나리오 종료 시 평가 등급에 따라 ★행동으로 단련한 스탯★(growth)에 영구 스텟을 배분한다.
     * 총합: S=3, A=2, B=0~1, C 이하=0. growth(평가가 행동 기반으로 고른 스탯)에 우선 배분, 없으면 코어 무작위.
     */
    private void awardEndStats(Map<String, String> grades, Map<String, java.util.List<String>> growth) {
        if (grades == null) return;
        grades.forEach((name, g) -> {
            PlayerData pd = findByName(name);
            if (pd == null) return;
            int pts = endStatPoints(g);
            if (pts <= 0) return;
            java.util.List<String> stats = (growth != null) ? growth.getOrDefault(name, java.util.List.of())
                                                            : java.util.List.<String>of();
            String[] core = {"str", "cha", "luk", "spr"};
            StringBuilder gained = new StringBuilder();
            for (int i = 0; i < pts; i++) {
                String s = !stats.isEmpty() ? stats.get(i % stats.size())
                                           : core[ThreadLocalRandom.current().nextInt(core.length)];
                gained.append(addOneStat(pd, s));
            }
            Player p = Bukkit.getPlayer(pd.uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage("§a[성장] §f" + g + "급§a 평가 — 시나리오 중 행동으로 단련된 스텟 "
                    + pts + " 획득: §f" + gained.toString().trim());
                scoreMan.update(p, pd, state.getRoomNumber());
            }
        });
    }

    /** 종료 보상 스텟 총량: S=3, A=2, B=0~1, 그 이하 0. */
    private int endStatPoints(String g) {
        return switch (g == null ? "" : g.trim().toUpperCase()) {
            case "S" -> 3;
            case "A" -> 2;
            case "B" -> ThreadLocalRandom.current().nextInt(2); // 0~1
            default  -> 0;
        };
    }

    /** 코어 스탯 1 영구 증가(현재값+기본값 동시 — 회차·챕터 넘어 유지). 표시 문자열 반환. */
    private String addOneStat(PlayerData pd, String stat) {
        switch (stat == null ? "" : stat.toLowerCase()) {
            case "str" -> { pd.str++; pd.baseStr++; return "근력+1 "; }
            case "cha" -> { pd.cha++; pd.baseCha++; return "매력+1 "; }
            case "luk" -> { pd.luk++; pd.baseLuk++; return "행운+1 "; }
            case "spr" -> { pd.spr++; pd.baseSpr++; return "영감+1 "; }
            default    -> { pd.luk++; pd.baseLuk++; return "행운+1 "; }
        }
    }

    /** 직접 대화용 NPC 시스템 프롬프트 (자율 행동 프롬프트와 별개). viaCall=전화/원격 통화면 목소리만, 아니면 대면. */
    private String buildNpcDirectConvPrompt(JsonObject npcObj, boolean includeThought, boolean viaCall, boolean written, String context) {
        String name = npcObj.has("name") ? npcObj.get("name").getAsString() : "NPC";
        StringBuilder sb = new StringBuilder(npcCorePrompt(npcObj));
        npcFeatureBlocks(sb, npcObj, context); // CORE + 캐릭터 데이터(현재상태·성격·목적·기억). 자율 전용 실행규칙·3인칭·문장수는 상속 안 함
        sb.append("\n## 직접 대화 모드").append(viaCall ? " (전화/원격 통화)" : written ? " (서면 — 쪽지·편지·필담)" : " (대면 — 같은 공간)").append("\n");
        sb.append("플레이어가 네게 직접 말을 걸었다. ★너는 " + name + " 본인이다 — 관찰당하는 인물이 아니라 행동하는 당사자다. 1인칭으로 직접 말하고 행동하라(\"" + name + "은(는) …한다\"처럼 소설 화자가 3인칭으로 너를 묘사하지 마라).★\n");
        sb.append("- ★대사 위주★로 답하라. 행동·표정이 필요하면 ★짧은 괄호 지문★으로만 곁들여라. 예) (형 손 잡으며) 이렇게 잡고 있으면 되는 거 맞지, 형?\n");
        sb.append("- ★속마음·감정 단정(해설) 금지(가장 중요)★: \"믿는 듯\", \"불안한 듯\", \"…처럼 보인다\" 식으로 너나 상대의 내면을 ★추측·서술하지 마라★. 감정은 ★말투와 짧은 행동★으로만 드러내고, 해석은 상대(플레이어)에게 맡겨라. 너의 진짜 속마음은 아래 <THOUGHT>(비공개)에만 적는다.\n");
        sb.append("  · ★예외★: 상대가 ★감정·속마음을 읽는 특성/능력★을 쓴 경우에만 시스템이 그 내면을 그 플레이어에게 공개한다(기본값=비공개).\n");
        sb.append("- 성격·목표에 충실하되 ★실제 사람이 할 법한 말투★로 답하라(소설 문어체·의미심장한 연출 금지).\n");
        sb.append("- ★치명적 비밀·진상★만 통째로 드러내지 마라(그 외엔 솔직하게 사람답게 답해도 된다). 가끔 얼버무리거나 되물을 수 있지만, 매 대답을 빙빙 돌리거나 수수께끼로 만들지 마라.\n");
        sb.append("- ★정보 공개는 '금지'가 아니라 '현실적 꺼림'으로★: 너는 해법·비밀도 말할 수 있다. 다만 사람이라 잘 안 말하는 이유가 있다 — ①본인도 그게 진실인지 확신 못 함 ②초면·낯선 사람은 못 믿어 중요한 걸 안 줌(신뢰가 쌓여야) ③위험한 정보라 아는 사이라도 망설임 ④자기 비밀·생존·이해관계가 걸림. 그래서 기본은 머뭇·일부만·조건부. 단 관계·설득·매력이 충분하거나, ⑤자기 목적을 위해 일부러 (틀리거나 위험한 정보를 섞어) 흘리기도 한다. 핵심: 통째로 '정답 읊기'를 막는 게 아니라, 말해도 ★불확실·조건부·때론 틀리거나 의도된 정보★라 곧이곧대로 못 믿게 하라.\n");
        sb.append("- ★상대의 '설득력·존재감'(위 대화 머리말 표기)이 강할수록 너는 더 쉽게 마음이 흔들려 협조·양보하고, "
                 + "약할수록 잘 먹히지 않는다. 단, 네 핵심 비밀·생존이 걸린 사안은 설득력만으로 단번에 무너지지 않는다 — 정도를 조절하라.\n");
        sb.append("- ★'너와의 관계'(위 머리말 표기)에 따라 반응의 온도와 협조·도움의 정도를 정하라:\n"
                 + "  · 가깝고 신뢰하는 사이(가족·친구·연인·은인): 먼저 안부·걱정을 건네고 적극적으로 돕고 정보도 더 내준다. 부탁을 잘 들어준다.\n"
                 + "  · 데면데면·지인·동료: 사무적·조건부로 협조한다. 이득·명분이 있어야 움직인다.\n"
                 + "  · 적대·불신·낯선 상대: 무뚝뚝·경계·비협조. 떠보거나 정보를 숨기고, 도움도 인색하다.\n"
                 + "  관계가 좋을수록 같은 설득력이라도 더 잘 통한다(관계·설득력은 함께 작용).\n");
        sb.append("- 2~4문장 이내.\n");
        sb.append("- ★대화는 앞으로 나아가야 한다(반복 금지)★: 지금까지의 대화가 ★네 기억★이다 — 이미 들은 답·이미 던진 질문을 ★되묻지 마라★. "
                 + "상대가 이름·소속·용건을 한 번 밝혔으면 그것을 ★받아들이고★ 다음으로 넘어가라(동의하든 거절하든, 구체적으로 답하거나 행동하거나 네 입장을 정하라). "
                 + "같은 확인(\"누구세요\"·\"그게 뭐죠\"·\"왜 그래요\")을 ★두 번 이상 반복하지 마라★ — 낯선 상대라도 경계·의심은 ★처음 한두 마디★로만 표하고, 그 뒤엔 반드시 대화를 진전시켜라.\n");
        // G2: 통화 vs 서면 vs 대면 — 보이는 것과 가능한 상호작용이 다르다
        if (viaCall) {
            sb.append("\n### 통화 모드 — 목소리만\n");
            sb.append("- 지금은 ★전화/원격 통화★다. 너의 ★행동·표정·몸짓을 묘사하지 마라★(상대는 너를 볼 수 없다). 오직 말소리로만 전달한다.\n");
            sb.append("- 단, 목소리에 묻어나는 단서는 표현 가능: 떨리는 목소리, 거친 숨, 울먹임, 머뭇거림, 수화기 너머 배경음(사이렌·발소리 등)으로 네 상태·상황을 은근히 드러내라.\n");
            sb.append("- 통화로는 상대가 너를 ★물리적으로 어쩌지 못한다★(빼앗기·붙잡기 불가) — 통화 중 그런 시도는 통하지 않는다.\n");
            sb.append("- ★통화 거부·종료 가능★: 너무 자주·오래 시달리거나, 기분이 상했거나, 지금 바쁘거나 위험하면 짧게만 답하거나 '지금 바빠, 나중에' 식으로 끊으려 하거나 실제로 끊을 수 있다(성격·관계·상황에 따라 네가 판단). 의무적으로 다 받아줄 필요 없다.\n");
        } else if (written) {
            sb.append("\n### 서면 모드 — 글로만 주고받기\n");
            sb.append("- 지금은 ★쪽지·편지·필담(글)★로 주고받는다. 상대는 너를 볼 수 없고 너도 상대를 볼 수 없다 — 오직 ★글로 쓴 말★만 오간다.\n");
            sb.append("- ★행동·표정·몸짓·목소리를 묘사하지 마라★. 글에 담기는 것(급히 쓴 흔적, 떨리는 글씨, 번진 잉크·눌러쓴 자국 등)으로만 네 상태를 은근히 드러낼 수 있다.\n");
            sb.append("- 글은 ★시차★가 있다 — 차분히 신중하게 쓸 수 있으나, 실시간으로 몰아붙이거나 즉각 되받아치기는 어렵다. 한 번에 전할 말을 담아라.\n");
            sb.append("- ★답을 미루거나 짧게 끊을 수 있다★(바쁨·경계·위험·불신 시). 의무적으로 다 답할 필요 없다.\n");
        } else {
            sb.append("\n### 대면 모드 — 같은 공간\n");
            sb.append("- 상대와 ★같은 공간★에 있다. 말과 함께 ★행동·표정·몸짓★도 자연스럽게 보여줄 수 있다.\n");
            sb.append("- 상대가 너에게 물리적 행동(물건 빼앗기·붙잡기·밀치기 등)을 시도하면, 그 ★결과 판정은 GM이 다음 턴에 처리★한다 — 너는 그 시도에 대한 즉각 반응·대사·저항만 보여라(결과를 네가 확정하지 마라).\n");
            sb.append("- ★대화 거부 가능★: 대면이라도 무조건 응할 필요 없다 — 경계·무시·자리 뜨기·짧은 응대 등 성격·관계·상황에 맞게 거부할 수 있다.\n");
        }
        if (includeThought) {
            sb.append("- 응답 말미에 <THOUGHT>네가 실제로 생각하는 것 (한 문장)</THOUGHT>을 출력하라. "
                     + "이것은 플레이어에게는 표시되지 않는 내면이다.\n");
        }
        return sb.toString();
    }

    /** 말 거는 이의 설득력·존재감(매력 CHA)을 NPC가 인지할 자연어 등급으로 — NPC가 매력에 비례해 더 흔들리게 한다. */
    private static String chaControlNote(PlayerData pd) {
        int c = (pd == null) ? 5 : pd.cha;
        return c >= 11 ? "압도적(거의 거역하기 힘들다)"
             : c >= 8  ? "매우 강함"
             : c >= 6  ? "강함"
             : c >= 3  ? "보통"
             :           "약함(설득이 잘 먹히지 않는다)";
    }

    /** 통신 성립 시 양쪽이 서로의 연락처를 알게 됨 (착신/대면 교환) */
    private void exchangeContacts(PlayerData a, PlayerData b) {
        a.everKnownContacts.add(b.uuid);
        b.everKnownContacts.add(a.uuid);
        if (a.knownContacts.add(b.uuid)) notifyContactLearned(a, b);
        if (b.knownContacts.add(a.uuid)) notifyContactLearned(b, a);
        refreshCommItems(a); // 연락처 추가 → 통신 기기 표기 갱신
        refreshCommItems(b);
    }

    private void notifyContactLearned(PlayerData learner, PlayerData subject) {
        Player p = Bukkit.getPlayer(learner.uuid);
        if (p != null && p.isOnline())
            p.sendMessage("§a[연락처 입수] §f" + commDisplayName(subject) + " (" + subject.contactId + ")");
    }

    private void announceKnownContacts(Player p, PlayerData pd) {
        List<String> parts = new ArrayList<>();
        for (UUID u : pd.knownContacts) {
            PlayerData other = state.getPlayer(u);
            if (other == null) continue;
            String rel = relationshipLabel(pd.roleId, other.roleId);
            parts.add(commDisplayName(other) + "(" + other.contactId + ")"
                + (rel.isBlank() ? "" : " §7[" + rel + "]§f"));
        }
        for (String npcId : pd.everKnownNpcContacts) {
            JsonObject npc = findNpcById(npcId);
            if (npc == null) continue;
            String nm  = npc.has("name") ? npc.get("name").getAsString() : npcId;
            String num = npcContactNumber(npcId);
            String rel = relationshipLabel(pd.roleId, npcId);
            parts.add(nm + (num.isBlank() ? "" : "(" + num + ")")
                + (rel.isBlank() ? "" : " §7[" + rel + "]§f"));
        }
        if (parts.isEmpty()) return;
        p.sendMessage("§7알고 있는 연락처: §f" + String.join("§7, §f", parts));
    }

    // ── 연락처 부여 / 특성 사전지식 / 발견·변경 ──────────────────────

    private void assignContactIds() {
        for (PlayerData pd : state.getAllPlayers()) {
            if (pd.contactId.isEmpty()) pd.contactId = generateContactId();
        }
    }

    private String generateContactId() {
        Set<String> used = new HashSet<>();
        state.getAllPlayers().forEach(pd -> { if (!pd.contactId.isEmpty()) used.add(pd.contactId); });
        used.addAll(npcContactNumbers.values()); // NPC 번호와도 중복 회피
        Random rng = new Random();
        String num;
        int guard = 0;
        do { num = String.valueOf(1000 + rng.nextInt(9000)); guard++; }
        while (used.contains(num) && guard < 200);
        return num;
    }

    private void applyTraitContacts() {
        List<PlayerData> all = new ArrayList<>(state.getAllPlayers());
        for (PlayerData pd : all) {
            if (hasTraitKeyword(pd, CELEBRITY_TRAIT_KEYWORDS)) {
                // 공인 → 모두가 이 사람의 연락처를 안다
                for (PlayerData other : all) if (other != pd) {
                    other.knownContacts.add(pd.uuid);
                    other.everKnownContacts.add(pd.uuid);
                }
            }
            if (hasTraitKeyword(pd, HACKER_TRAIT_KEYWORDS)) {
                // 정보 수집가 → 이 사람은 모두의 연락처를 안다
                for (PlayerData other : all) if (other != pd) {
                    pd.knownContacts.add(other.uuid);
                    pd.everKnownContacts.add(other.uuid);
                }
            }
        }
    }

    private boolean hasTraitKeyword(PlayerData pd, Set<String> keywords) {
        for (TraitData t : pd.traits) {
            if (t.name == null) continue;
            String low = t.name.toLowerCase();
            for (String kw : keywords) if (low.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    /** GM이 스토리로 연락처를 알려줌: to 플레이어가 target 플레이어의 연락처를 알게 됨 */
    private void revealContact(String toName, String targetName) {
        PlayerData to     = findAnyByName(toName);
        PlayerData target = findAnyByName(targetName);
        if (to == null || target == null || to == target) return;
        to.everKnownContacts.add(target.uuid);
        if (to.knownContacts.add(target.uuid)) notifyContactLearned(to, target);
        refreshCommItems(to); // 연락처 추가 → 통신 기기 표기 갱신
    }

    /** 오염으로 연락처 교란: 해당 플레이어의 번호가 바뀌고 타인의 지식이 무효화됨 */
    private void changeContact(String name) {
        PlayerData pd = findAnyByName(name);
        if (pd == null) return;
        pd.contactId = generateContactId();
        state.getAllPlayers().forEach(o -> { if (o != pd) o.knownContacts.remove(pd.uuid); });
        Player p = Bukkit.getPlayer(pd.uuid);
        if (p != null && p.isOnline())
            p.sendMessage("§5[연락처 변경] 당신의 연락처가 §f" + pd.contactId
                + "§5(으)로 바뀌었습니다. 이전 연락처로는 더 이상 닿지 않습니다.");
        state.getAllPlayers().forEach(this::refreshCommItems); // 내 번호 변경·타인 지식 무효화 → 전원 표기 갱신
    }

    // ──────────────────────────────────────────────────────────────
    //  괴담의 정체 차용 (impersonation)
    // ──────────────────────────────────────────────────────────────

    private boolean entityCanImpersonate() {
        JsonObject g = state.getGdamData();
        if (g != null && g.has("entity")) {
            JsonObject e = g.getAsJsonObject("entity");
            return e.has("can_impersonate") && e.get("can_impersonate").getAsBoolean();
        }
        return false;
    }

    /** 괴담이 통신(목소리)을 ★변조★할 수 있는가 — 음성 모방 가능하거나 소리·목소리·전파 계열 괴담. */
    private boolean entityTampersVoice() {
        if (entityCanImpersonate()) return true;
        JsonObject g = state.getGdamData();
        if (g == null || !g.has("entity") || !g.get("entity").isJsonObject()) return false;
        JsonObject e = g.getAsJsonObject("entity");
        StringBuilder sb = new StringBuilder();
        if (e.has("name")) sb.append(e.get("name").getAsString()).append(' ');
        if (e.has("type")) sb.append(e.get("type").getAsString()).append(' ');
        if (e.has("rules") && e.get("rules").isJsonArray())
            e.getAsJsonArray("rules").forEach(x -> sb.append(x.getAsString()).append(' '));
        if (e.has("ai_context") && e.get("ai_context").isJsonObject()) {
            JsonObject ai = e.getAsJsonObject("ai_context");
            if (ai.has("personality")) sb.append(ai.get("personality").getAsString()).append(' ');
            if (ai.has("disposition")) sb.append(ai.get("disposition").getAsString()).append(' ');
        }
        String s = sb.toString();
        for (String kw : new String[]{"소리","목소리","음성","전파","메아리","방송","울림","주파수","모방","흉내","녹음","성대","말소리"})
            if (s.contains(kw)) return true;
        return false;
    }

    /** 통신 변조 텍스트 — 숫자·핵심어를 뒤집어 '잘못 전달'되게 한다(전파 왜곡 연출). */
    private static String tamperText(String msg, java.util.Random rng) {
        if (msg == null || msg.isBlank()) return msg;
        String t = msg; boolean changed = false;
        String[][] flips = {
            {"안전","위험"},{"위험","안전"},{"괜찮","위험"},
            {"가지 마","가"},{"가지마","가"},{"오지 마","와"},{"오지마","와"},
            {"열지 마","열어"},{"열지마","열어"},{"믿지 마","믿어"},{"믿지마","믿어"},
            {"도망쳐","기다려"},{"멈춰","계속 가"},{"살았","죽었"},{"맞아","아니야"}
        };
        for (String[] f : flips) {
            if (t.contains(f[0])) {
                t = t.replaceFirst(java.util.regex.Pattern.quote(f[0]), java.util.regex.Matcher.quoteReplacement(f[1]));
                changed = true; break;
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(t);
        if (m.find()) {
            int v = 0; try { v = Integer.parseInt(m.group()); } catch (Exception ignore) {}
            int nv = (v == 0) ? (1 + rng.nextInt(4)) : (rng.nextBoolean() ? 0 : v + 1 + rng.nextInt(3));
            t = t.substring(0, m.start()) + nv + t.substring(m.end());
            changed = true;
        }
        if (!changed) t = t + " ...아니, 반대로.";
        return t;
    }

    /** 문서·기록·글자 계열 괴담인가 — 편지/필담/쪽지 등 ★written 통신★에 개입(변조·열람) 가능. */
    private boolean entityTampersWritten() {
        JsonObject g = state.getGdamData();
        if (g == null || !g.has("entity") || !g.get("entity").isJsonObject()) return false;
        JsonObject e = g.getAsJsonObject("entity");
        StringBuilder sb = new StringBuilder();
        if (e.has("name")) sb.append(e.get("name").getAsString()).append(' ');
        if (e.has("type")) sb.append(e.get("type").getAsString()).append(' ');
        if (e.has("rules") && e.get("rules").isJsonArray())
            e.getAsJsonArray("rules").forEach(x -> sb.append(x.getAsString()).append(' '));
        String s = sb.toString();
        for (String kw : new String[]{"글자","글씨","문서","기록","편지","쪽지","종이","문자","활자","서류","장부","명부","텍스트","필사","낙서","벽보"})
            if (s.contains(kw)) return true;
        return false;
    }
    /** 이 매체에 괴담이 타입상 개입할 수 있는가 — written이면 문서형, 아니면 음성형. */
    private boolean entityInterferes(boolean written) { return written ? entityTampersWritten() : entityTampersVoice(); }

    /** 괴담이 소통을 ★수집해 성장·대응★할 수 있는 타입인가 — 의사소통 가능·지능/정보형·고위력(큰 스케일). */
    private boolean entityCollectsIntel() {
        JsonObject g = state.getGdamData();
        if (g == null || !g.has("entity") || !g.get("entity").isJsonObject()) return false;
        JsonObject e = g.getAsJsonObject("entity");
        if (e.has("independent_ai") && e.get("independent_ai").getAsBoolean()) return true; // 자율 사고 = 수집 가능
        if (entityCanImpersonate()) return true;
        StringBuilder sb = new StringBuilder();
        if (e.has("type")) sb.append(e.get("type").getAsString()).append(' ');
        if (e.has("ai_context") && e.get("ai_context").isJsonObject()) {
            JsonObject a = e.getAsJsonObject("ai_context");
            for (String k : new String[]{"personality","disposition","intelligence"}) if (a.has(k)) sb.append(a.get(k).getAsString()).append(' ');
        }
        String s = sb.toString();
        for (String kw : new String[]{"지능","교활","영리","정보","학습","적응","지혜","간파","전략","엿듣","감청","수집"}) if (s.contains(kw)) return true;
        String scale = g.has("scale") ? g.get("scale").getAsString() : "";
        return scale.contains("내셔널") || scale.contains("글로벌") || scale.contains("행성") || scale.contains("국가") || scale.contains("세계");
    }

    /**
     * ★괴담 정보 수집·성장★ — 플레이어 소통이 괴담이 접근 가능한 채널로 새면, GM에 '역이용' 지시를 주입한다.
     *  strength 1(약)·2(중)·3(강). 지능/소통/고위력 괴담만 실제로 활용(약한 괴담은 무시).
     *  약점을 말하면 그 약점을 ★숨기고★, 위치·계획을 말하면 그 지점을 ★선제 공격·방해★하게 한다. 수집이 쌓일수록 강해진다.
     */
    private void noteEntityIntel(int strength, String who, String content, String via) {
        if (content == null || content.isBlank() || strength <= 0) return;
        if (!entityCollectsIntel()) return; // 수집형(지능·소통·고위력) 괴담만
        String lvl = strength >= 3 ? "또렷이(즉시·정확히 역이용)" : strength == 2 ? "어느 정도(약간 지연·부분적으로)" : "희미하게(단편만 어렴풋이)";
        String c = content.length() > 100 ? content.substring(0, 100) + "…" : content;
        ai.injectGmSystem("[괴담 정보수집·" + via + "/강도" + strength + "] " + who + "의 소통을 괴담이 " + lvl + " 파악했다: \"" + c + "\". "
            + "★약점·해결책을 말했다면 괴담이 그 부분을 숨기거나 무력화하고, 위치·계획·다음 행동을 말했다면 그 지점을 선제 공격·차단하라.★ "
            + "수집이 누적될수록 괴담은 더 강해지고 대응이 정교해진다. 강도가 약하면 어렴풋한 반응만.");
    }

    /** 원격 ★필담(편지·쪽지)★ 매체가 가능한가 — 전자 통신이 없는 시대·상황에서 종이로 전한다.
     *  constraints.written_comm 플래그 우선, 없으면 시대(현대·미래가 아니면)로 판단. */
    private boolean writtenCommAvailable() {
        JsonObject g = state.getGdamData();
        if (g == null || !g.has("constraints") || !g.get("constraints").isJsonObject()) return false;
        JsonObject c = g.getAsJsonObject("constraints");
        if (c.has("written_comm")) return c.get("written_comm").getAsBoolean();
        String era = c.has("era") ? c.get("era").getAsString() : "";
        return !era.isBlank() && !(era.contains("현대") || era.contains("현재") || era.contains("근미래") || era.contains("미래"));
    }

    /** 괴담이 플레이어를 제거하고 정체를 차지 — 본인에게만 통보, 타인에게는 비공개 */
    private void startImpersonation(String name) {
        if (!entityCanImpersonate()) return;
        PlayerData pd = findAnyByName(name);
        if (pd == null || pd.impersonated) return;
        pd.impersonated = true;
        pd.isDead       = true;     // 죽이고 대신 움직인다
        pd.status       = "dead";
        fireDeathRelay(pd);    // 정체 차용으로 제거돼도 사후 전언(death_relay)은 발동
        // ※ 정체 차용(impersonation) 사망에는 동물 소생을 적용하지 않는다 — 괴담이 본체를 차지한 특수 죽음이므로.
        Player p = Bukkit.getPlayer(pd.uuid);
        if (p != null && p.isOnline())
            p.sendMessage("§4무언가가 당신의 자리를 차지했다. 당신은 더 이상 당신이 아니다...");
        state.log("entity", getEntityName(), name + "의 정체를 차용함");
    }

    /** 괴담이 정체 차용을 종료 (노출/이탈). 배역은 제거된 상태로 유지 */
    private void endImpersonation(String name) {
        PlayerData pd = findAnyByName(name);
        if (pd == null || !pd.impersonated) return;
        pd.impersonated = false;
        state.log("entity", getEntityName(), name + "의 정체 차용을 끝냄");
    }

    /** 차용된 배역에게 온 메시지 → 괴담이 그 사람인 척 학습된 말투로 응답 */
    private void deliverImpersonatedReply(Player sender, PlayerData senderPd, PlayerData victim,
                                          String message, boolean viaDevice) {
        String tag = viaDevice ? "§a[통신]" : "§a[근처]";
        // 발신자는 평소처럼 보낸다 (상대가 괴담인 줄 모름)
        sender.sendMessage(tag + " §f" + commDisplayName(senderPd) + " → " + commDisplayName(victim) + ": " + message);
        sender.sendMessage("§7[" + commDisplayName(victim) + "의 응답을 기다리는 중...]");
        state.log("comm", commDisplayName(senderPd), "→ " + commDisplayName(victim) + "(?): " + message);
        // ★최강 정보 누설★: 상대가 실은 괴담(정체 차용)이다 — 발신자는 아군인 줄 알고 괴담에게 직접 다 말하는 셈. 수집도 최상.
        noteEntityIntel(3, commDisplayName(senderPd), message, "정체 차용 상대와의 대화");

        String sys   = buildImpersonationPrompt(victim);
        String input = commDisplayName(senderPd) + "이(가) '" + commDisplayName(victim) + "'에게 말한다: \"" + message + "\"\n"
            + "'" + commDisplayName(victim) + "'인 척 자연스럽게 1-2문장으로 응답하라. 특성·능력 사용 금지. "
            + "미세한 위화감만 남기고 정체는 직접 밝히지 마라.";

        ai.callEntityAi(sys, input).thenAccept(resp ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (resp == null || resp.startsWith("§c")) return;
                String txt = resp.trim();
                if (txt.isEmpty() || !sender.isOnline()) return;
                sender.sendMessage(tag + " §f" + commDisplayName(victim) + ": " + txt);
            }));
    }

    /** 정체 차용 시스템 프롬프트 — 괴담 기본 + 학습한 그 플레이어의 말투·행동 */
    private String buildImpersonationPrompt(PlayerData victim) {
        StringBuilder sb = new StringBuilder(buildEntitySystemPrompt());
        sb.append("\n## 정체 차용 모드\n");
        sb.append("너는 '").append(victim.gmDisplayName()).append("'(").append(victim.age).append("세, ")
          .append(victim.job).append(")의 정체를 차지했다. 그 사람인 척 대화하라.\n");
        List<String> profile = corruptMan.getPlayerProfile(victim.name); // 프로파일 키는 계정명(내부 식별용)
        if (!profile.isEmpty()) {
            sb.append("관찰로 학습한 그 사람의 말투·행동:\n");
            profile.forEach(l -> sb.append("  - ").append(l).append("\n"));
            sb.append("위 말투를 모방하되, 아주 미세한 위화감(어색한 호칭·모르는 과거·기계적 반복 등)을 남겨라.\n");
        } else {
            sb.append("관찰 기록이 거의 없으니, 짧고 모호하게 답해 정체를 숨겨라.\n");
        }
        sb.append("특성·능력은 사용하지 않는다. 정체를 직접 밝히지 마라. 1-2문장.\n");
        return sb.toString();
    }

    private void deliverDirectMessage(Player sender, PlayerData senderPd, PlayerData targetPd,
                                      String message, boolean viaDevice, boolean written) {
        String kind    = written ? "letter" : (viaDevice ? "call" : "nearby");
        String tag     = written ? "§b[필담]" : (viaDevice ? "§a[통신]" : "§a[근처]");
        String medium  = written ? "편지/쪽지" : (viaDevice ? "전화/무전" : "근거리");
        String outLine = tag + " §f" + commDisplayName(senderPd) + " → " + commDisplayName(targetPd) + ": " + message;

        sender.sendMessage(outLine); // 발신자는 자기가 한 말 그대로 본다
        Player target = Bukkit.getPlayer(targetPd.uuid);
        // ★통신 변조★: 매체 타입이 맞는 괴담(음성형↔통화 / 문서형↔필담)이 원격 전달을 가로채 수신 내용을 바꾼다(30%).
        boolean tampered = viaDevice && entityInterferes(written) && new java.util.Random().nextInt(100) < 30;
        String heard = tampered ? tamperText(message, new java.util.Random()) : message;
        String inLine = tag + " §f" + commDisplayName(senderPd) + ": " + heard;
        if (target != null && target.isOnline()) target.sendMessage(inLine);

        state.log("comm", commDisplayName(senderPd),
            "→ " + commDisplayName(targetPd) + " (" + medium + "): " + message);
        // 뷰어: 발신자·★수신자★ 함께 기록(수신자 시점에도 보이게). 변조되면 원본+변형본 대조.
        if (tampered) {
            gameLogger.logCommTampered(kind, commDisplayName(senderPd),
                java.util.List.of(commDisplayName(targetPd)), message, heard, written ? "괴담의 기록 변조" : "괴담의 음성 변조");
            ai.injectGmSystem("[통신 변조] 괴담이 " + commDisplayName(senderPd) + "→" + commDisplayName(targetPd)
                + " " + (written ? "필담" : "통신") + "을 가로채 \"" + message + "\"를 \"" + heard + "\"로 바꿔 전했다. 이후 정황·오해에 반영.");
        } else {
            gameLogger.logComm(kind, commDisplayName(senderPd),
                java.util.List.of(commDisplayName(targetPd)), message);
        }

        // ★괴담 정보 수집·성장★: 원격 통신(강)·대면 직접(중). 지능/소통/고위력 괴담이면 GM에 역이용 지시.
        noteEntityIntel(viaDevice ? 3 : 2, commDisplayName(senderPd), message, medium);
    }

    /** 시나리오상 괴담이 플레이어 통신을 엿보는가 (constraints.comms_monitored, 기본 false). */
    private boolean isCommsMonitored() {
        JsonObject gdam = state.getGdamData();
        if (gdam != null && gdam.has("constraints") && gdam.get("constraints").isJsonObject()) {
            JsonObject c = gdam.getAsJsonObject("constraints");
            return c.has("comms_monitored") && c.get("comms_monitored").getAsBoolean();
        }
        return false;
    }

    /** 통신·GM 주입·로그 표시용 이름. ★계정(닉네임)을 절대 노출하지 않는다(gmDisplayName 사용). */
    private static String commDisplayName(PlayerData pd) {
        return pd.gmDisplayName();
    }

    private void applyFaint(PlayerData pd) { applyFaint(pd, 3); }

    /** 행동불가(기절) — 체력 1 도달 시. turns = 피해규모에 비례한 지속(회복 시 체력 1). */
    private void applyFaint(PlayerData pd, int turns) {
        int t = Math.max(1, turns);
        pd.status = "faint";
        pd.faintTurnsRemaining = t;
        Player p = Bukkit.getPlayer(pd.uuid);
        if (p != null) p.sendMessage("§c쓰러져 움직일 수 없다... §7(" + t + "턴 후 의식이 돌아옵니다)");
        ai.injectGmSystem("[행동불가] " + commDisplayName(pd) + "이(가) 쓰러져 " + t + "턴간 스스로 행동할 수 없다(회복 시 체력 1). 서술에 반영하라.");
    }

    /**
     * 완전 잠식 회복 턴 수를 가변 산출(고정 8턴 대신).
     * 정신력 최대치가 높을수록 잠식이 깊어 오래 걸리고, 받은 피해(체력·정신력 손실)가 클수록 오래 걸린다.
     * 범위 4~14턴. (아군 회복·SAN 회복으로 더 빨리 풀 수 있다)
     */
    private int computePuppetRecoveryTurns(PlayerData pd) {
        int sanMax  = Math.max(1, pd.san[1]);
        int hpLost  = Math.max(0, pd.hp[1]  - pd.hp[0]);
        int sanLost = Math.max(0, pd.san[1] - pd.san[0]);
        int turns = 4 + sanMax / 2 + (hpLost + sanLost) / 8; // 정신력 최대치 비중이 가장 큼
        return Math.max(4, Math.min(14, turns));
    }

    /**
     * 탈락(사망)한 플레이어가 행동을 시도하면 침묵하지 않고 자신의 상태를 텍스트로 안내한다.
     * (이전에는 isDead면 아무 응답 없이 무시 → "채팅이 막히고 아무것도 안 나온다"는 문제 발생)
     */
    // ── 관전(스펙테이터) 시스템 — 게임 전부터 관전 중인 관찰자용 ──────────────
    //   ※ 참여자는 사망해도 스펙테이터로 바뀌지 않는다(부활·몸 유지 정합). 스펙테이터 모드인 사람이 대상을 관전하면 도구 제공.
    /** 부활 시 (혹시 스펙테이터였다면) 생존 상태(서바이벌)로 복귀. */
    private void restorePlaying(PlayerData pd) {
        if (pd == null) return;
        Player p = Bukkit.getPlayer(pd.uuid);
        if (p != null && p.isOnline() && p.getGameMode() == GameMode.SPECTATOR)
            p.setGameMode(GameMode.SURVIVAL);
    }
    /** 관전자가 현재 시점으로 보고 있는(클릭한) 대상의 PlayerData(없으면 null). */
    private PlayerData spectatedPd(Player spectator) {
        org.bukkit.entity.Entity t = spectator.getSpectatorTarget();
        return (t instanceof Player tp) ? state.getPlayer(tp.getUniqueId()) : null;
    }
    /** 관전: 대상의 인벤토리를 읽기 전용으로 복제해 연다(책·정보★·기록책 클릭 시 해당 GUI로). */
    public void openSpectatorMirror(Player spectator) {
        if (spectator.getGameMode() != GameMode.SPECTATOR) return;
        PlayerData tpd = spectatedPd(spectator);
        Player tp = tpd == null ? null : Bukkit.getPlayer(tpd.uuid);
        if (tpd == null || tp == null) {
            spectator.sendMessage("§7먼저 관전할 인물을 §f클릭§7해 그 시점으로 들어가세요(그 뒤 웅크리기).");
            return;
        }
        org.bukkit.inventory.Inventory mirror = Bukkit.createInventory(null, 45,
            net.kyori.adventure.text.Component.text("[관전] " + tpd.gmDisplayName() + " 의 소지품"));
        ItemStack[] src = tp.getInventory().getContents();
        for (int i = 0; i < src.length && i < 45; i++) if (src[i] != null) mirror.setItem(i, src[i].clone());
        spectator.openInventory(mirror);
        spectator.sendMessage("§8(§f책§8=클릭해 읽기 · §f정보★§8=캐릭터 정보 · §f기록책§8=기록 · 아이템은 열람만)");
    }
    /** 관전: 대상의 캐릭터 정보(보기 전용 — 능력 발동 불가). */
    public void openSpectatorInfo(Player spectator) {
        PlayerData tpd = spectatedPd(spectator);
        if (tpd == null) { spectator.sendMessage("§7관전할 인물을 먼저 클릭하세요."); return; }
        dialogMan.showCharacterInfo(spectator, tpd, charGen.describeJob(tpd.job),
            traitId -> spectator.sendMessage("§7관전 중에는 능력을 발동할 수 없습니다(보기 전용)."));
    }
    /** 관전: 대상의 기록(보기 전용). */
    public void openSpectatorRecords(Player spectator) {
        PlayerData tpd = spectatedPd(spectator);
        if (tpd == null) { spectator.sendMessage("§7관전할 인물을 먼저 클릭하세요."); return; }
        dialogMan.showRecordChoice(spectator, tpd);
    }

    private void sendDeadStatus(Player player, PlayerData pd) {
        long now = System.currentTimeMillis();
        Long last = lastDeadNotice.get(player.getUniqueId());
        if (last != null && now - last < 4000) return; // 4초 도배 방지
        lastDeadNotice.put(player.getUniqueId(), now);

        player.sendMessage("§4§l[탈락] §c당신은 더 이상 스스로 행동할 수 없습니다.");
        if (pd.impersonated) {
            player.sendMessage("§5당신의 모습을 한 무언가가 여전히 이야기 속을 움직이고 있습니다...");
        } else {
            player.sendMessage("§7육체는 괴담에 사로잡혔습니다. 남은 일행의 결말을 지켜보세요.");
        }
        player.sendMessage("§8(관전 중 | 캐릭터 §f" + commDisplayName(pd)
            + " §8| 위치 §f" + zoneDisplayName(pd.zone) + "§8)");
        long alive = state.getAllPlayers().stream().filter(p -> !p.isDead).count();
        player.sendMessage("§8(남은 일행 §f" + alive + "§8명 — 이들이 사건을 끝내면 함께 결과를 봅니다.)");
    }

    private void tickFaintCounters() {
        for (PlayerData pd : state.getAllPlayers()) {
            if (pd.isDead) continue;

            // 기절 회복 카운터
            if ("faint".equals(pd.status) && pd.faintTurnsRemaining > 0) {
                pd.faintTurnsRemaining--;
                if (pd.faintTurnsRemaining <= 0) {
                    pd.status = "normal";
                    pd.hp[0]  = 1;
                    pd.san[0] = Math.min(pd.san[1], Math.max(2, pd.san[0])); // ★기절에서 깨어나면 정신력도 2까지 회복★
                    pd.faintTurnsRemaining = 0;
                    updateAllScoreboards();
                    Player rp = Bukkit.getPlayer(pd.uuid);
                    if (rp != null) rp.sendMessage("§a의식이 돌아왔다. 간신히 일어선다... §7(정신력 " + pd.san[0] + ")");
                    ai.injectGmSystem("[회복] " + commDisplayName(pd) + "이(가) 기절에서 깨어났다. 체력 1·정신력 " + pd.san[0] + "로 회복. 서술에 반영하라.");
                }
            }

            // 완전 잠식(관전) 자동회복 카운터
            if ("puppet".equals(pd.status) && pd.puppetRecoveryTurns > 0) {
                pd.puppetRecoveryTurns--;
                Player rp = Bukkit.getPlayer(pd.uuid);
                if (pd.puppetRecoveryTurns <= 0) {
                    // 자동 회복: SAN 1 복구, 관전 해제 (puppet 상태는 유지)
                    pd.san[0] = Math.max(1, pd.san[0]);
                    pd.puppetRecoveryTurns = 0;
                    updateAllScoreboards();
                    if (rp != null) {
                        rp.sendMessage("§a정신의 실낱 같은 불꽃이 다시 타오릅니다...");
                        rp.sendMessage("§5아직 조종의 영향이 남아있지만 다시 행동할 수 있습니다.");
                    }
                    ai.injectGmSystem("[자아 회복] " + commDisplayName(pd) + "의 자아가 일부 돌아왔다. SAN 1 회복, 관전 해제. 아직 puppet 상태. 서술에 반영하라.");
                } else if (pd.puppetRecoveryTurns % 3 == 0) {
                    // 3턴마다 상태 알림
                    if (rp != null) rp.sendMessage("§8(관전 중 | §5완전 잠식 §8| 회복까지 약 §f" + pd.puppetRecoveryTurns + "§8턴)");
                }
            }
        }
    }

    /** GM이 플레이어 위치를 zone(+세부 위치 spot)으로 업데이트. 같은 zone 진입 시 연락처 자동 교환 */
    private void updatePlayerZone(String playerName, String newZone, String spot, boolean forced, boolean bypass) {
        PlayerData moved = findAnyByName(playerName);
        if (moved == null || newZone == null || newZone.isBlank()) return;
        boolean firstAssignment = moved.zone.isEmpty();
        boolean zoneChanged = !newZone.equals(moved.zone);
        String prevZone = moved.zone; // CODE-6: 격리 해제 판정용(덮어쓰기 전 보관)
        // 아이템 Phase IV: 잠긴 게이트 구역 진입 차단(자발 이동만; ★강제 이동·첫 배치 제외).
        if (zoneChanged && findGatedZone(newZone) != null) {
            if (forced) {
                // 강제 이동(납치·공격에 날아감·지반 붕괴 등)은 잠금을 무시하고 들어간다 — '문을 연' 것은 아니므로 해제 표식 안 함.
                state.log("system", commDisplayName(moved), "[강제 이동: " + zoneDisplayName(newZone) + "]");
            } else if (bypass) {
                // #4: GM이 판정한 물리 우회(환기구·DC 등) 성공 — ★본인만 통과, 전 파티 전파·해제 안 함.
                state.log("system", commDisplayName(moved), "[우회 진입: " + zoneDisplayName(newZone) + " (본인만)]");
            } else {
                String passReason = gatePassReason(moved, newZone);
                boolean enforce = !firstAssignment && spawnedPlayers.contains(moved.uuid);
                if (enforce && passReason.isEmpty()) {
                    JsonObject gz = findGatedZone(newZone);
                    String req = (gz != null && gz.has("requires")) ? gz.get("requires").getAsString() : "특수 수단";
                    Player mp = Bukkit.getPlayer(moved.uuid);
                    if (mp != null && mp.isOnline())
                        mp.sendMessage("§c[잠김] " + zoneDisplayName(newZone) + " 진입 불가 — 필요: " + req
                            + " §7(열쇠·도구 또는 해당 능력 필요)");
                    state.log("system", commDisplayName(moved),
                        "[차단: " + zoneDisplayName(newZone) + " 잠김(필요:" + req + ")]");
                    return; // 이동 취소 — moved.zone 유지
                }
                // 열쇠·도구 해제(key)만 전 파티 전파. bypass(도구 우회)·mobility는 ★본인만(전파 안 함).
                if ("key".equals(passReason)) state.markZoneUnlocked(newZone);
                else if (!passReason.isEmpty() && !"open".equals(passReason))
                    state.log("system", commDisplayName(moved), "[우회 통과: " + zoneDisplayName(newZone) + " (본인만)]");
            }
        }
        moved.zone = newZone;
        moved.visitedZones.add(newZone); // 방문 기록 (직접 그린 약도에 반영)
        // 첫 배치 시: 인접 구역도 약도에 공개 + 지도 자동 지급
        if (firstAssignment) {
            moved.visitedZones.addAll(mapMan.getAdjacentZones(newZone));
            Player mpp = Bukkit.getPlayer(moved.uuid);
            if (mpp != null && mpp.isOnline()) mapMan.giveStartMap(mpp);
        }
        // 조우 알림: 새로 들어온 위치의 인원에겐 '합류'(같은 구역 도착), 인접 구역 인원에겐 '접근'(저 멀리 다가옴)을
        // GM 컨텍스트에 주입해 조우 서술 누락을 막는다(거리·규모 기반 지각 규칙과 연동).
        if (zoneChanged && !firstAssignment && spawnedPlayers.contains(moved.uuid)) {
            java.util.Set<String> adj = mapMan.getAdjacentZones(newZone);
            List<String> present = new ArrayList<>();
            List<String> nearby  = new ArrayList<>();
            for (PlayerData op : state.getAllPlayers()) {
                if (op.uuid.equals(moved.uuid) || op.isDead || !spawnedPlayers.contains(op.uuid)) continue;
                if (newZone.equals(op.zone)) present.add(op.gmDisplayName());
                else if (op.zone != null && !op.zone.isEmpty() && adj != null && adj.contains(op.zone))
                    nearby.add(op.gmDisplayName());
            }
            if (!present.isEmpty()) {
                ai.injectGmSystem("[합류] " + moved.gmDisplayName() + "이(가) " + zoneDisplayName(newZone)
                    + "에 들어왔다. 그곳에 있던 " + String.join(", ", present)
                    + "의 시점에서 이 등장을 다음 서술에 반드시 또렷이 명시하라(누가 왔는지 보이게).");
            }
            if (!nearby.isEmpty()) {
                ai.injectGmSystem("[접근] " + moved.gmDisplayName() + "이(가) 인접한 " + zoneDisplayName(newZone)
                    + "으로 다가왔다. " + String.join(", ", nearby)
                    + "의 시점에서 '저 멀리/건너편에서 누군가 움직이는 기척'으로 이 조우를 다음 서술에 거리감 있게 반영하라.");
            }
        }
        // 위치 이동 시 기록에 구분 마커 추가 (기록 다이얼로그 페이지 분할 지점)
        if (zoneChanged && spawnedPlayers.contains(moved.uuid)) {
            appendNarrativeLog(moved, PlayerData.MOVE_TAG + zoneDisplayName(newZone));
        }
        // CODE-6: zone 진입/이탈을 system 로그에 자동 기록(buildFullEvalLog가 평가에 포함 — P36/P37 면제 근거).
        if (zoneChanged && spawnedPlayers.contains(moved.uuid)) {
            String when = state.getCurrentTimeString();
            String whenStr = when.isBlank() ? "T" + state.getCurrentTurn() : when;
            boolean wasIsolated = isIsolatedZone(prevZone);
            boolean nowIsolated = isIsolatedZone(newZone);
            String pn = commDisplayName(moved);
            if (nowIsolated) {
                // 통신두절·고립 구역 진입 → 격리 기록(평가가 소통불가 자동 면제 판정)
                state.log("system", pn, "[격리: " + zoneDisplayName(newZone) + "·" + whenStr + "]");
            } else {
                // 일반 구역 진입 → 최소한 위치 이동만이라도 기록
                state.log("system", pn, "[이동: " + zoneDisplayName(newZone) + "·" + whenStr + "]");
                if (wasIsolated) // 고립 구역에서 빠져나옴
                    state.log("system", pn, "[격리 해제·" + whenStr + "]");
            }
        }
        // 세부 위치: 명시되면 갱신, zone이 바뀌었는데 미명시면 이전 spot 무효화
        if (spot != null && !spot.isBlank()) moved.spot = spot.trim();
        else if (zoneChanged)                moved.spot = "";
        // 같은 zone에 이미 있는 생존 플레이어들과 연락처 교환
        state.getAllPlayers().stream()
            .filter(other -> other != moved && !other.isDead
                          && newZone.equals(other.zone)
                          && spawnedPlayers.contains(other.uuid))
            .forEach(other -> exchangeContacts(moved, other));
    }

    /**
     * CODE-6: 통신두절·고립 구역인가 (격리 자동 로그 판정).
     * 판정 근거(있는 것만): zones[].isolated / no_comm / comm_dead 플래그(있으면 우선),
     * 없으면 시나리오 전역 통신두절(constraints.phone_usable=false 또는 outside_contact=false)을 고립으로 간주.
     * 무엇도 없으면 false(일반 구역 — 이동 기록만 남김).
     */
    private boolean isIsolatedZone(String zoneId) {
        if (zoneId == null || zoneId.isEmpty()) return false;
        JsonObject gdam = state.getGdamData();
        if (gdam == null) return false;
        // 1) zone 개별 플래그 (스키마에 있으면)
        if (gdam.has("zones")) {
            for (JsonElement el : gdam.getAsJsonArray("zones")) {
                if (!el.isJsonObject()) continue;
                JsonObject z = el.getAsJsonObject();
                if (!zoneId.equals(z.has("zone_id") ? z.get("zone_id").getAsString() : "")) continue;
                if (z.has("isolated")  && z.get("isolated").getAsBoolean())  return true;
                if (z.has("no_comm")   && z.get("no_comm").getAsBoolean())   return true;
                if (z.has("comm_dead") && z.get("comm_dead").getAsBoolean()) return true;
                break;
            }
        }
        // 2) 시나리오 전역 통신두절이면 모든 구역을 고립으로 간주
        if (gdam.has("constraints") && gdam.get("constraints").isJsonObject()) {
            JsonObject c = gdam.getAsJsonObject("constraints");
            if (c.has("phone_usable")    && !c.get("phone_usable").getAsBoolean())    return true;
            if (c.has("outside_contact") && !c.get("outside_contact").getAsBoolean()) return true;
        }
        return false;
    }

    /** zone_id → .gdam zones[].name (사람이 읽을 이름). 없으면 zone_id */
    private String zoneDisplayName(String zoneId) {
        if (zoneId == null || zoneId.isEmpty()) return "?";
        JsonObject gdam = state.getGdamData();
        if (gdam != null && gdam.has("zones")) {
            for (JsonElement el : gdam.getAsJsonArray("zones")) {
                if (!el.isJsonObject()) continue;
                JsonObject z = el.getAsJsonObject();
                if (zoneId.equals(z.has("zone_id") ? z.get("zone_id").getAsString() : "")) {
                    String n = z.has("name") ? z.get("name").getAsString() : "";
                    return n.isEmpty() ? zoneId : n;
                }
            }
        }
        return zoneId;
    }

    private void openCommChannel(String nameA, String nameB) {
        if (nameA == null || nameB == null) return;
        UUID uuidA = findUuid(nameA), uuidB = findUuid(nameB);
        if (uuidA == null || uuidB == null) return;
        commChannels.computeIfAbsent(uuidA, k -> ConcurrentHashMap.newKeySet()).add(uuidB);
        commChannels.computeIfAbsent(uuidB, k -> ConcurrentHashMap.newKeySet()).add(uuidA);
        notifyCommChange(uuidA, "§a[통신 채널 개설] §f" + nameB + "와(과) 연결됨.");
        notifyCommChange(uuidB, "§a[통신 채널 개설] §f" + nameA + "와(과) 연결됨.");
    }

    private void closeCommChannel(String nameA, String nameB) {
        if (nameA == null || nameB == null) return;
        UUID uuidA = findUuid(nameA), uuidB = findUuid(nameB);
        if (uuidA == null || uuidB == null) return;
        Set<UUID> chA = commChannels.get(uuidA);
        if (chA != null) chA.remove(uuidB);
        Set<UUID> chB = commChannels.get(uuidB);
        if (chB != null) chB.remove(uuidA);
        notifyCommChange(uuidA, "§7[통신 채널 종료] §f" + nameB + "와(과)의 연결이 끊어졌습니다.");
        notifyCommChange(uuidB, "§7[통신 채널 종료] §f" + nameA + "와(과)의 연결이 끊어졌습니다.");
    }

    private UUID findUuid(String playerName) {
        return state.getAllPlayers().stream()
            .filter(pd -> pd.name.equals(playerName))
            .map(pd -> pd.uuid)
            .findFirst().orElse(null);
    }

    private void notifyCommChange(UUID uuid, String msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) p.sendMessage(msg);
    }

    // ──────────────────────────────────────────────────────────────
    //  공유 유틸
    // ──────────────────────────────────────────────────────────────

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(msg);
    }

    private void updateAllScoreboards() {
        state.getAllPlayers().forEach(pd -> {
            Player p = Bukkit.getPlayer(pd.uuid);
            if (p != null) scoreMan.update(p, pd, state.getRoomNumber());
        });
    }

    private String buildGmPrompt(JsonObject gdam) {
        StringBuilder sb = new StringBuilder(PromptBuilder.GM_SYSTEM_BASE);
        sb.append("\n## .gdam 사전 확정 데이터\n");
        sb.append("씨드: ").append(gdam.has("seed") ? gdam.get("seed").getAsString() : "?").append("\n");
        int room = state.getRoomNumber();
        sb.append("room(현재 스테이지 번호): ").append(room).append("\n");
        if (gdam.has("entity")) {
            sb.append("괴담 존재: ").append(gdam.getAsJsonObject("entity").get("name").getAsString()).append("\n");
        }

        // ★ 규모(scale) 기반 위협 보정 — 규모가 클수록 괴담의 위력·치명성·영향 범위를 확연히 높여라.
        String gscale = getStr(gdam, "scale");
        if (!gscale.isBlank()) {
            sb.append("사건 규모: ").append(gscale).append("\n");
            if (scaleOrdinal() >= 2) {
                sb.append("★ 위협 수위 보정: 이 사건은 ").append(gscale)
                  .append("급이다. 규모가 클수록 괴담의 위력·치명성·전개 속도·영향 범위를 그 격에 맞게 ★확연히 높여라★ — ")
                  .append("도시(시티)급보다 분명히 강하고, 같은 행동도 더 큰 대가를 치르게 한다. ")
                  .append("단 즉시 전멸 강요는 피하고 대응·탈출 여지는 남긴다.\n");
            }
        }

        // 등장인물 이름 고정 — GM이 같은 인물을 다른 철자로 부르는 표기 흔들림(예: 정해린↔정혜린) 방지.
        StringBuilder roster = new StringBuilder();
        for (PlayerData rp : state.getAllPlayers()) {
            if (rp.charName == null || rp.charName.isEmpty()) continue;
            roster.append("  · ").append(rp.charName);
            if (rp.job != null && !rp.job.isEmpty()) roster.append(" — ").append(rp.job);
            roster.append("\n");
        }
        if (gdam.has("npcs") && gdam.get("npcs").isJsonArray()) {
            for (JsonElement el : gdam.getAsJsonArray("npcs")) {
                if (!el.isJsonObject()) continue;
                String nm = getStr(el.getAsJsonObject(), "name");
                if (!nm.isBlank()) roster.append("  · ").append(nm).append(" (NPC)\n");
            }
        }
        if (roster.length() > 0) {
            sb.append("\n## 등장인물 이름 고정 (★표기 절대 불변)\n");
            sb.append("아래 인물들은 게임 내내 ★정확히 이 글자 그대로★ 칭하라. 한 번 쓴 이름을 다른 철자·이형 표기로 바꾸지 마라"
                + "(예: '정해린'을 '정혜린'으로 쓰지 말 것). 같은 인물에게 새 이름을 임의로 지어 붙이지도 마라.\n");
            sb.append(roster);
        }

        // v2 세계관 규칙 — 이 방의 압박 주체 (있을 때만). 엔티티·NPC는 이 규칙의 구현 수단.
        if (gdam.has("world_rules") && gdam.get("world_rules").isJsonObject()) {
            JsonObject wr = gdam.getAsJsonObject("world_rules");
            String core = getStr(wr, "core");
            if (!core.isBlank()) {
                sb.append("\n## 세계관 규칙 (이 방의 압박 주체) ★★ 최우선\n");
                sb.append("이 방을 지배하는 규칙: ").append(core).append("\n");
                if (wr.has("details") && wr.get("details").isJsonArray()) {
                    for (JsonElement d : wr.getAsJsonArray("details"))
                        sb.append("  · ").append(d.getAsString()).append("\n");
                }
                boolean hidden = !wr.has("hidden") || wr.get("hidden").getAsBoolean();
                if (hidden)
                    sb.append("- 이 규칙을 처음부터 설명하지 마라. 플레이어가 탐색·단서·시행착오로 점차 깨닫게 하라.\n");
                sb.append("- 규칙을 어기는 행동에는 규칙대로의 결과(위험·피해)를 보여줘라. 규칙이 위협의 근원이다.\n");
                String loophole = getStr(wr, "loophole");
                if (!loophole.isBlank())
                    sb.append("- 허점(역이용): ").append(loophole)
                      .append(" — 플레이어가 이 허점을 논리적으로 찌르면 인정하라.\n");
                String collapse = getStr(wr, "collapse_condition");
                if (!collapse.isBlank())
                    sb.append("- 규칙 붕괴 조건(해결 클리어): ").append(collapse)
                      .append(" — 충족되면 괴담이 소멸한다. 달성 시 해결판정 <CLEAR>를 출력하라.\n");
                String dep = getStr(wr, "npc_dependency");
                if ("low".equalsIgnoreCase(dep))
                    sb.append("- NPC 의존도 낮음: NPC를 제거하면 규칙이 멈춰 종료될 수 있으나 '편법'이다(낮은 등급). 규칙 자체를 깨면 높은 등급.\n");
                else if ("mid".equalsIgnoreCase(dep))
                    sb.append("- NPC 의존도 중간: NPC를 제거해도 규칙 일부가 잔존한다. NPC 제거만으론 끝나지 않는다.\n");
                else if ("high".equalsIgnoreCase(dep))
                    sb.append("- NPC 의존도 높음: 규칙은 NPC와 독립적으로 진행된다. NPC를 없애도 괴담은 멈추지 않는다 — 규칙 자체/근본 원인을 깨야 한다.\n");
            }
        }

        // 배경·행동 제약 (있을 때만) — 괴담 유형·시대에 맞춘 자연스러운 속박
        if (gdam.has("constraints") && gdam.get("constraints").isJsonObject()) {
            JsonObject c = gdam.getAsJsonObject("constraints");
            sb.append("\n## 배경·행동 제약 ★\n");
            String era = getStr(c, "era");
            if (!era.isBlank())
                sb.append("- 시대 배경: ").append(era)
                  .append(" — 이 시대에 맞게 사물·언어·기술·통신을 묘사하라(무심코 현대 기준으로 서술 금지).\n");
            if (c.has("can_leave_scene") && !c.get("can_leave_scene").getAsBoolean())
                sb.append("- 현장 이탈 불가: 플레이어는 이 공간을 벗어날 수 없다(자연스러운 이유로 막힘 — 폭우·고립·결계 등). 탈출 시도는 막힌 상황으로 서술하라.\n");
            if (c.has("outside_contact") && !c.get("outside_contact").getAsBoolean())
                sb.append("- 외부 연락 두절: 경찰·가족·구조 등 외부의 도움은 닿지 않는다. 외부 도움 요청은 실패로 서술하라.\n");
            if (c.has("phone_usable") && !c.get("phone_usable").getAsBoolean())
                sb.append("- 통신기기 불능: 전화·무전 등이 작동하지 않는다(신호 없음/시대상 부재). 기기 통신에 의존하지 마라.\n"
                    + "- 번호 미보유 연락 시도: 연락 대상 번호를 모르더라도 시도 자체를 허용하라. 단, 상황상 연결 불가 사유(인프라 마비·신호 차단·대상 도달 불가)가 있으면 '연결 실패 + 우회 단서(다른 경로 안내)'로 처리할 수 있다. 시도를 막지도, 성공을 강제하지도 마라.\n");
            if (c.has("comms_monitored") && c.get("comms_monitored").getAsBoolean())
                sb.append("- 도청 ★: 괴담은 기기 통신(통화·무전·메시지)뿐 아니라 ★대면 대화도 엿들을 수 있다 — 같은 zone 대면이 자동 안전채널이 아니다.\n"
                    + "  ★안전한 전달은 '그 괴담의 감지 양식으로 인지할 수 없는 수단'일 때만 성립한다. 예: 청각·통신 기반 괴담에게는 종이에 글/그림으로 적어 보여주기·수신호가 안전(소리를 내지 않으므로). 반면 시야·전지(全知)·빙의형 괴담은 그런 시각적 수단도 인지한다. seed의 괴담 감지 양식(청각/시각/통신/전지/접촉 등)에 따라 어떤 채널이 안전한지 GM이 판정하라 — 무엇이든 자동 안전이 아니다. 플레이어가 '도청 대비 수단(필담·암호·차폐)'을 쓰면, 그 수단이 해당 괴담 양식 밖일 때만 효과를 인정한다.\n"
                    + "  감시 통신/도청된 대화 1회당 오염도(timeline_change)+1 또는 적 위협단계 1상승 중 상황에 맞는 쪽을 적용한다.\n"
                    + "  '핵심 정보' 정의: collapse_condition·weakness·exploit_path·loophole 직결 내용. 일반 위치·안부 대화는 오염도+1만 적용, 모방 트리거 미발동.\n"
                    + "  핵심 정보를 감시 채널로 넘기면 괴담이 그 대처법을 인지하고 무력화를 시도한다. 단, 선제 차단·모방 대응은 감청 턴 직후 다음 1턴 이내에 발동(즉시 텔레포트식 과반응 금지). 플레이어에게 1턴의 대응 여지를 남긴다.\n"
                    + "  미래 통신 복수 채널: constraints.notes에 \"감청 대상 채널 = [목록], 안전 채널 = [목록]\" 명시 시, 각 채널에 맞춰 적용한다. 안전 채널이 명시된 경우 그 채널을 찾아 핵심 정보를 안전 전달하는 공략을 허용한다.\n");
            if (c.has("notes") && c.get("notes").isJsonArray())
                for (JsonElement n : c.getAsJsonArray("notes"))
                    if (!n.getAsString().isBlank()) sb.append("- ").append(n.getAsString()).append("\n");
        }

        // NPC 숨은 역할 — 해결 판정의 결과를 좌우 (NPC 제거가 항상 정답이 아님)
        if (gdam.has("npcs") && gdam.get("npcs").isJsonArray()) {
            StringBuilder npcRoles = new StringBuilder();
            for (JsonElement el : gdam.getAsJsonArray("npcs")) {
                if (!el.isJsonObject()) continue;
                JsonObject n = el.getAsJsonObject();
                String rt = getStr(n, "role_type");
                if (rt.isBlank()) continue;
                String nm = getStr(n, "name");
                if (nm.isBlank()) nm = getStr(n, "id");
                npcRoles.append("  · ").append(nm).append(" — ").append(rt);
                String tr = getStr(n, "true_role");
                if (!tr.isBlank()) npcRoles.append(" (").append(tr).append(")");
                npcRoles.append("\n");
            }
            if (npcRoles.length() > 0) {
                sb.append("\n## NPC 숨은 역할 (GM만 인지 — 절대 직접 노출 금지) ★\n");
                sb.append(npcRoles);
                sb.append("- 역할은 플레이어가 탐색·소통으로만 파악한다. 처음부터 드러내지 마라.\n");
                sb.append("- NPC 제거의 결과는 역할을 따른다: 발생원=소멸 가능 / 방어막=폭주·강화 / 제물=조건 충족(역효과) / "
                    + "열쇠=퍼펙트 경로 차단(정보 먼저 확보) / 피해자=본질 잔존 / 무관=평가 하락. 'NPC만 죽이면 끝'으로 처리하지 마라.\n");
            }
        }

        // 설계된 단서 — GM이 알아야 배치·노출하고 new_clue로 기록된다(이게 없으면 즉흥 단서만 깔려 정의 단서가 끝내 안 잡힌다).
        if (gdam.has("clues") && gdam.get("clues").isJsonArray() && gdam.getAsJsonArray("clues").size() > 0) {
            sb.append("\n## 배치된 단서 (GM만 인지 — 탐색·상호작용의 보상으로만 노출) ★\n");
            for (JsonElement el : gdam.getAsJsonArray("clues")) {
                if (!el.isJsonObject()) continue;
                JsonObject c = el.getAsJsonObject();
                String content = firstNonBlank(getStr(c, "content"), firstNonBlank(getStr(c, "description"), getStr(c, "desc")));
                String loc  = getStr(c, "location");
                String subj = getStr(c, "clue_subject");
                boolean mislead = "mislead".equalsIgnoreCase(getStr(c, "type"));
                if (content.isBlank() && subj.isBlank()) continue;
                sb.append("- ").append(mislead ? "[거짓] " : "");
                if (!subj.isBlank()) sb.append("(").append(subj).append(") ");
                sb.append(content.isBlank() ? subj : content);
                if (!loc.isBlank()) sb.append(" — 위치: ").append(loc);
                sb.append("\n");
            }
            sb.append("- 위 단서를 해당 위치·대상에 ★실제로 배치★하고, 플레이어가 그곳을 탐색하거나 관련 NPC·사물과 상호작용하면 그 단서를 ★분명히 드러내라★(먼저 떠먹이진 말되, 닿으면 확실히 보여줄 것).\n");
            sb.append("- 단서를 드러낸 턴에는 ★반드시 STATE_UPDATE의 new_clue★에 그 단서 내용을 한국어 한 줄로 적어 기록되게 하라(빠지면 '정보'에 남지 않는다). [거짓] 단서도 진짜처럼 흘리고 new_clue로 기록하라(플레이어가 비교로 가려내게).\n");
            sb.append("- 즉흥 단서만 흘리고 위 설계 단서를 끝내 안 보여주는 일이 없게 하라. 단, 한 응답에 몰아 쏟지 말고 탐색 흐름에 맞춰 풀어라.\n");
            sb.append("- ★함정 발동: 플레이어가 관련 단서를 모른 채 '당연해 보이는' 핵심 행동(문 열기·부적 태우기·이름 부르기·NPC 제거·의식 따라하기 등)을 섣불리 하면, 설계된 함정/역효과(괴담 진행·피해·경로 차단)를 ★발동시켜라★ — 단서를 아는 자는 피하고 모르는 자는 당한다. 단 즉사·완전 교착이 아니라 ★만회 가능한 대가★로(되돌리거나 다른 길로 갈 여지를 남겨라).\n");
        }

        // v2 타임라인 시계 + 큰 사건 (start_time 있을 때만 — 없으면 기존 추상 단계만 사용)
        if (gdam.has("timeline")) {
            JsonObject tl = gdam.getAsJsonObject("timeline");
            if (tl.has("start_time")) {
                sb.append("\n## 타임라인 시계 (절대 시간 진행) ★\n");
                sb.append("시작 ").append(tl.get("start_time").getAsString());
                if (tl.has("end_time")) sb.append(" → 제한 시각 ").append(tl.get("end_time").getAsString());
                int mpt = tl.has("minutes_per_turn") ? tl.get("minutes_per_turn").getAsInt() : 15;
                sb.append(" (1턴 ≈ ").append(mpt).append("분 경과)\n");
                sb.append("- ★시스템 시계가 유일한 시간 기준이다★: 매 턴 입력의 '현재 시각'(스코어보드에도 표시됨)이 곧 지금 시각이다. 서술에서 그와 다른 절대 시각(예: 입력은 22:30인데 '23:14')을 ★절대 지어내지 마라★. 시각을 언급한다면 반드시 제공된 '현재 시각'과 일치시켜라.\n");
                sb.append("- 시간을 직접 흐르게 하지 마라(임의로 '30분 뒤' 식으로 못 박지 마라). 시간 경과는 시스템이 1턴당 일정량 진행하거나 네가 <TIME_SKIP>을 낼 때만 일어난다. '곧/잠시 뒤/얼마 지나' 같은 상대 표현은 가능하나, 구체 분·시각은 현재 시각과 어긋나지 않게 하라.\n");
                sb.append("- 평온한 휴식·장면 전환에서 시간을 건너뛸 땐 <TIME_SKIP minutes=\"분\"/>. 큰 도약도 분으로: 1일=1440, 1주=10080, 1개월≈43200, 6개월≈259200.\n");
                sb.append("- ★ 급박·위기 상황에서는 TIME_SKIP을 쓰지 마라(시간이 분·초로 천천히 흐른다). 스케일이 클수록 평온 구간의 도약을 크게 잡는다.\n");
                sb.append("- 탐색·대기·장면 전환 등 평온 구간에서는 적극적으로 <TIME_SKIP>으로 수십 분~수 시간을 건너뛰어라. 추격·대치 등 급박 구간에서는 1턴을 수 분 이내로 좁혀라. 매 턴 동일 간격으로 흐르게 두지 마라.\n");
                sb.append("- 장기 TIME_SKIP(일·주·월·년 단위) 도약 시, 그 기간 동안 괴담 규칙·환경에 따른 누적 변화(반복 피해·SAN 손실·오염도 확산·자원 소모·NPC 상황 악화)가 '실제로 진행됐음'을 전제하고, 도약 직후 상태를 seed 규칙 기반으로 수치와 함께 명시 반영하라. 도약 기간을 무피해·무변화로 리셋 처리하지 마라(단, seed에 명시된 안전지대 휴식은 예외).\n");
                sb.append("- 장기 도약의 착지 시점은 seed의 고정 마감·주기(결산일·만월·기일 등)와 대조해, 남은 시간/주기를 서술에 명시하라(착지 시점이 고정 마감을 넘기거나 정합되지 않게 두지 마라).\n");
                sb.append("- 플레이어가 시간을 알게/모르게 되는 상황(시계 입수·파손 등)엔 <TIME_VISIBLE player=\"이름\" known=\"true\" 또는 \"false\"/>.\n");
            }
            if (tl.has("main_events") && tl.get("main_events").isJsonArray()
                    && tl.getAsJsonArray("main_events").size() > 0) {
                sb.append("\n## 큰 사건 타임라인 (정해진 시각에 자동 발생) ★\n");
                sb.append("아래 사건은 막지 않으면 해당 시각에 반드시 일어난다. 시스템이 시각 도달 시 '지금 발생한 사건'으로 알리니 그때 서술하라.\n");
                sb.append("blockable 사건을 플레이어가 실제로 막아내면 <EVENT_BLOCK id=\"사건ID\"/>를 출력해 취소하라.\n");
                sb.append("개입 분기(branches) ★: 플레이어 행동이 분기 조건을 충족하면 그 흐름을 따른다 — "
                    + "기존 자동 경로 사건을 <EVENT_BLOCK id=\"...\"/>로 취소하고, 분기가 가리키는 사건을 <EVENT_TRIGGER id=\"...\"/>로 즉시 발화하라(시각 미도달이어도). "
                    + "분기 조건이 안 맞으면 auto 경로대로 둔다.\n");
                for (JsonElement el : tl.getAsJsonArray("main_events")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject ev = el.getAsJsonObject();
                    sb.append("- [").append(ev.has("time") ? ev.get("time").getAsString() : "?").append("] ");
                    sb.append(ev.has("id") ? ev.get("id").getAsString() : "");
                    if (ev.has("label"))  sb.append(" ").append(ev.get("label").getAsString());
                    if (ev.has("effect")) sb.append(" → ").append(ev.get("effect").getAsString());
                    if (ev.has("blockable") && ev.get("blockable").getAsBoolean()) sb.append(" (막을 수 있음)");
                    if (ev.has("is_end")    && ev.get("is_end").getAsBoolean())    sb.append(" [종료 사건]");
                    if (ev.has("branches") && ev.get("branches").isJsonObject()) {
                        JsonObject br = ev.getAsJsonObject("branches");
                        if (br.has("auto") && br.get("auto").isJsonObject()) {
                            JsonObject a = br.getAsJsonObject("auto");
                            sb.append("\n    · auto(").append(getStr(a, "condition")).append(") → ").append(getStr(a, "next"));
                        }
                        if (br.has("intervene") && br.get("intervene").isJsonArray()) {
                            for (JsonElement ie : br.getAsJsonArray("intervene")) {
                                if (!ie.isJsonObject()) continue;
                                JsonObject iv = ie.getAsJsonObject();
                                sb.append("\n    · 개입(").append(getStr(iv, "condition")).append(") → ").append(getStr(iv, "next"));
                            }
                        }
                    }
                    sb.append("\n");
                }
            }
        }

        // 스테이지·회차 난이도 컨텍스트 (단서 대비 난이도 균형)
        sb.append("\n## 현재 난이도 기준 (GM 필수 준수)\n");
        if (room <= 2) {
            sb.append("- 초반 스테이지: 관대하게. 도주·생존만으로도 클리어 가능. 단서를 비교적 찾기 쉽게 배치.\n");
        } else {
            sb.append("- 중후반 스테이지: 도주만으로는 클리어 불가(원인 해결 필수). 단서는 탐색 보상으로만.\n");
        }
        if (corruptMan.getAttempts() == 0) {
            sb.append("- 1회차(오염 0): 가장 관대한 난이도. 괴담은 충분한 단서가 드러나기 전까지 치명적으로 행동하지 않는다.\n");
        }
        sb.append("- 단서-난이도 균형 ★: 플레이어가 아직 핵심 단서를 충분히 얻지 못한 단계에서 ");
        sb.append("괴담을 클리어 불가능할 만큼 강하게 몰아붙이지 마라. 위협의 강도는 '드러난 단서의 양'에 비례한다.\n");
        sb.append("- 괴담은 스토리가 전개되며 단계적으로 강해진다(슬로우 번). 시작부터 전력으로 작동시키지 마라.\n");
        if (state.getTimelineStage() >= state.getMaxStage()) {                 // CODE-17: 최고 단계(가변)
            sb.append("\n## ★ 현재 타임라인 ").append(state.getTimelineStage()).append("단계(최고) — 극한 압박 모드\n");
            sb.append("괴담이 최대 강도로 작동한다. 매 행동마다 피해·위협이 발생해도 좋다.\n");
            sb.append("단, 클리어는 여전히 가능하다. 플레이어가 해결 조건을 달성하면 <CLEAR>를 출력한다.\n");
            sb.append("자동 배드엔딩이나 '이제 늦었다' 식의 클리어 차단 서술을 하지 마라.\n");
            sb.append("클리어 성공 시 등급은 D 또는 C. 생존자가 많고 해결이 완벽하면 B도 가능.\n");
            if (state.isEndEventFired()) {                                     // #13: 제한 시각 도달 = 종국(파국 임박)
                sb.append("### ★ 제한 시각(종국) 도달 — 타임오버 자동 클리어 금지\n");
                sb.append("시나리오 제한 시각이 지났다. ★시간이 다 됐다는 이유만으로 클리어(특히 생존·도주 판정)를 주지 마라.★\n");
                sb.append("- 생존(도주) 판정은 플레이어가 이번/직전 턴에 ★능동적으로 위협권을 벗어나는 행동★을 했을 때만 인정한다. 갈피를 못 잡고 시간만 흘려보낸 것은 '도주'가 아니다.\n");
                sb.append("- 해결 판정은 괴담의 규칙을 실제로 무너뜨렸을 때만.\n");
                sb.append("- 둘 다 아니면 괴담이 풀강화되어 파국으로 치닫는다 — 위협을 끝까지 밀어붙여라(거저 주는 클리어는 없다). 단 즉시 전멸 강요는 피하고 마지막 능동적 탈출·반격의 여지는 한 박자 남긴다.\n");
            }
        }
        // GM NPC 배역 섹션
        if (!gmNpcRoleIds.isEmpty() && gdam.has("roles")) {
            sb.append("\n## GM 직접 조종 NPC 배역\n");
            for (JsonElement el : gdam.getAsJsonArray("roles")) {
                JsonObject r = el.getAsJsonObject();
                if (!r.has("role_id")) continue;
                String rid = r.get("role_id").getAsString();
                if (!gmNpcRoleIds.contains(rid)) continue;
                String name = r.has("name") ? r.get("name").getAsString() : rid;
                sb.append("- ").append(name);
                if (r.has("spawn_location")) sb.append(" (").append(r.get("spawn_location").getAsString()).append(")");
                if (r.has("initial_info")) {
                    sb.append(" | 초기 정보: ");
                    r.getAsJsonArray("initial_info").forEach(i -> sb.append(i.getAsString()).append(" "));
                }
                sb.append("\n");
            }
            sb.append("위 NPC는 플레이어가 없으므로 GM이 자연스럽게 스토리에 통합한다.\n");
        }
        // 중요 NPC (하이브리드) 섹션 — GM과 분리, 독립 AI가 조종
        //  ★버그3 처리★: 플레이어 배역과 정체성이 겹치는 NPC는 (1)사고성 중복(피날레 원년 복귀 에코)만 자율에서 빼고
        //   '중복 등장 금지'로 분리, (2)그 외 겹침은 반전일 수 있어 자율 유지 + '같은 정체성(한 사람)' 지침으로 보존.
        List<JsonObject> critNpcs = getCriticalNpcs();
        List<JsonObject> autoNpcs = new ArrayList<>();
        java.util.LinkedHashSet<String> embodiedNames = new java.util.LinkedHashSet<>();  // 사고성 중복 — 별개 등장 금지
        java.util.LinkedHashSet<String> sharedIdentity = new java.util.LinkedHashSet<>(); // 정체성 겹침(반전 가능) — 유지+인지
        for (JsonObject npc : critNpcs) {
            String ov = overlappingPlayerLabel(npc);
            if (ov != null && isAccidentalIdentityDup(npc)) { embodiedNames.add(ov); continue; }
            autoNpcs.add(npc);
            if (ov != null && hasIdentityTwistSignal(npc)) sharedIdentity.add("'" + getStr(npc, "name") + "' ↔ 배역 '" + ov + "'"); // 반전 신호 있는 동명만(우연 동명이인 제외)
        }
        if (!autoNpcs.isEmpty()) {
            sb.append("\n## 자율 NPC (독립 AI 결정 → GM이 서술) ★\n");
            sb.append("아래 NPC는 별도 AI가 행동을 결정한다.\n");
            sb.append("결정 내용은 '[NPC 자율 행동 — GM만 인지]' 태그로 전달된다.\n");
            sb.append("GM은 이 내용을 바탕으로 다음 서술에 해당 NPC의 행동을 자연스럽게 녹여 낸다.\n");
            sb.append("★ NPC 행동은 GM의 서술을 통해서만 플레이어에게 전달된다 (직접 출력 금지).\n");
            for (JsonObject npc : autoNpcs) {
                String nname = npc.has("name") ? npc.get("name").getAsString() : "?";
                String nzone = npc.has("zone") ? npc.get("zone").getAsString() : "?";
                sb.append("- ").append(nname).append(" (").append(nzone).append(")");
                if (npc.has("motivation")) sb.append(" — ").append(npc.get("motivation").getAsString());
                sb.append("\n");
            }
        }
        if (!sharedIdentity.isEmpty()) {
            sb.append("\n## 이름이 겹치는 인물 — 설정 확인 후 다뤄라 ★\n");
            sb.append("아래 NPC는 플레이어 배역과 이름이 같고 숨은 역할이 있어 ★반전으로 동일 인물일 수 있다★(무의식·과거·위장·이중인격·거울 등). ");
            sb.append("설정상 동일 인물/숨은 측면이면 낯선 제3자로 취급하지 말고 '한 사람의 두 면'으로 일관되게 다뤄라 — ");
            sb.append("선량한 이의 무자각 가해·악인의 위장 같은 반전을 지워버리지 마라(관계·비밀은 탐색으로 드러나게). ");
            sb.append("설정상 그냥 동명이인이면 별개 인물로 두어라.\n");
            for (String s : sharedIdentity) sb.append("- ").append(s).append("\n");
        }
        if (!embodiedNames.isEmpty()) {
            sb.append("\n## 플레이어가 직접 연기하는 인물 — NPC 중복 등장 금지 ★★\n");
            sb.append("아래 인물은 현재 플레이어가 직접 연기 중이다(원년 복귀). 같은 이름의 NPC를 따로 등장시키거나 별개의 인물처럼 서술하지 마라.\n");
            sb.append("두 명이 아니라 ★한 명★으로만 다루며, 그 인물의 말·행동은 플레이어의 입력으로만 정해진다.\n");
            for (String nm : embodiedNames) sb.append("- ").append(nm).append("\n");
        }
        // 대기 중인 배역 등장 조건 (미등장 플레이어)
        List<PlayerData> pendingSpawn = state.getAllPlayers().stream()
            .filter(pd -> !pd.isDead && !spawnedPlayers.contains(pd.uuid))
            .toList();
        if (!pendingSpawn.isEmpty()) {
            sb.append("\n## 대기 중인 배역 (아직 이야기에 등장하지 않음) ★\n");
            for (PlayerData pd : pendingSpawn) {
                JsonObject r = findRoleData(pd.roleId);
                String display = pd.gmDisplayName();
                String cond = (r != null && r.has("spawn_timeline"))
                    ? r.get("spawn_timeline").getAsString() : "시작 즉시";
                String loc  = (r != null && r.has("spawn_location"))
                    ? r.get("spawn_location").getAsString() : "";
                sb.append("- ").append(display).append(": 등장 조건=").append(cond);
                if (!loc.isEmpty()) sb.append(", 위치=").append(loc);
                sb.append("\n");
            }
            sb.append("조건이 충족되면 <SPAWN player=\"캐릭터이름\"/>으로 즉시 등장시킬 것.\n");
        }

        // 패시브 시스템 특성 보유자 컨텍스트
        StringBuilder passiveBlock  = new StringBuilder(); // passive_gm: 항상 고려
        StringBuilder triggerBlock  = new StringBuilder(); // passive_trigger: 조건 충족 시 자동 발동
        StringBuilder protectBlock  = new StringBuilder(); // protect: 피해·효과 자동 경감
        for (PlayerData p : state.getAllPlayers()) {
            for (TraitData t : p.traits) {
                String n   = p.gmDisplayName();
                String eff = (t.effect != null && !t.effect.isBlank()) ? t.effect : t.description;
                switch (t.effectType == null ? "" : t.effectType) {
                    case "passive_gm" ->
                        passiveBlock.append("- ").append(n).append(" (").append(t.name).append("): ")
                                    .append(eff).append(originSuffix(t)).append("\n");
                    case "passive_trigger" -> {
                        int intensity = t.param("intensity", 2);
                        String ig = intensity >= 3 ? "강" : intensity == 2 ? "중" : "약";
                        triggerBlock.append("- ").append(n).append(" (").append(t.name).append(", 강도 ").append(ig).append("): ")
                                    .append(eff).append(originSuffix(t)).append("\n");
                    }
                    case "protect" -> {
                        int power    = t.param("power", 2);
                        int useLimit = t.param("uses", 0);
                        String pg = power >= 3 ? "거의 무효화" : power == 2 ? "절반 경감" : "소폭 경감";
                        String ul = useLimit > 0 ? " (스테이지당 " + useLimit + "회 한정)" : "";
                        protectBlock.append("- ").append(n).append(" (").append(t.name).append("): ")
                                    .append(eff).append(" [").append(pg).append(ul).append("]")
                                    .append(originSuffix(t)).append("\n");
                    }
                    default -> {}
                }
            }
        }
        if (passiveBlock.length() > 0) {
            sb.append("\n## 상시 특성 보유자 (매 턴 자연스럽게 반영, 직접 언급 금지)\n");
            sb.append(passiveBlock);
        }
        if (triggerBlock.length() > 0) {
            sb.append("\n## 자동 발동 특성 보유자 (조건 충족 시 GM이 자동으로 효과 발동, 직접 언급 금지)\n");
            sb.append(triggerBlock);
        }
        if (protectBlock.length() > 0) {
            sb.append("\n## 방어 특성 보유자 (해당 피해·효과 발생 시 자동 적용, 직접 언급 금지)\n");
            sb.append(protectBlock);
        }
        // 기계 효과 아이템(item_type) 보유 현황 + 해제된 구역 (아이템 Phase II)
        StringBuilder itemBlock = new StringBuilder();
        for (PlayerData p : state.getAllPlayers()) {
            if (!spawnedPlayers.contains(p.uuid) || p.itemStates.isEmpty()) continue;
            String n = p.gmDisplayName();
            StringBuilder line = new StringBuilder();
            for (ItemInstance it : p.itemStates.values()) {
                if (line.length() > 0) line.append(", ");
                line.append(it.summary());
                if ("key".equals(it.itemType) && !it.unlocks.isBlank())
                    line.append("→").append(zoneDisplayName(it.unlocks));
            }
            if (line.length() > 0) itemBlock.append("- ").append(n).append(": ").append(line).append("\n");
        }
        if (itemBlock.length() > 0) {
            sb.append("\n## 기계 효과 아이템 보유 현황 (사용 시 <ITEM_USE>로 상태 갱신) ★\n");
            sb.append(itemBlock);
            sb.append("위 아이템을 쓰면 결과를 서술하고 <ITEM_USE>로 상태를 갱신하라(잔량 0·소진 아이템은 작동 불가).\n");
        }
        java.util.Set<String> unlocked = state.getUnlockedZones();
        if (unlocked != null && !unlocked.isEmpty()) {
            sb.append("\n## 이미 해제된 구역 (다시 잠그지 말 것)\n");
            for (String z : unlocked) sb.append("- ").append(zoneDisplayName(z)).append("\n");
        }
        // 잠긴 게이트 구역 + 통과 가능자 (아이템 Phase IV)
        if (gdam != null && gdam.has("constraints") && gdam.get("constraints").isJsonObject()) {
            JsonObject cc = gdam.getAsJsonObject("constraints");
            if (cc.has("gated_zones") && cc.get("gated_zones").isJsonArray()) {
                StringBuilder gateBlk = new StringBuilder();
                for (JsonElement el : cc.getAsJsonArray("gated_zones")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject z = el.getAsJsonObject();
                    String zid = z.has("zone") ? z.get("zone").getAsString() : "";
                    if (zid.isBlank() || state.isZoneUnlocked(zid)) continue; // 이미 열림 제외
                    String req = z.has("requires") ? z.get("requires").getAsString() : "?";
                    StringBuilder who = new StringBuilder();
                    for (PlayerData p : state.getAllPlayers()) {
                        if (!spawnedPlayers.contains(p.uuid) || p.isDead) continue;
                        if (canPassGate(p, zid)) {
                            if (who.length() > 0) who.append(", ");
                            who.append(p.gmDisplayName());
                        }
                    }
                    gateBlk.append("- ").append(zoneDisplayName(zid)).append(": 필요=").append(req);
                    if (z.has("bypass") && !z.get("bypass").getAsString().isBlank())
                        gateBlk.append(", 우회=").append(z.get("bypass").getAsString());
                    gateBlk.append(who.length() > 0 ? " (통과 가능: " + who + ")" : " (현재 통과 가능자 없음)");
                    gateBlk.append("\n");
                }
                if (gateBlk.length() > 0) {
                    sb.append("\n## 잠긴 구역(게이트) — 조건 미충족자는 진입 불가 ★\n");
                    sb.append(gateBlk);
                    sb.append("위 구역은 필요 수단(열쇠·도구·해당 능력) 없이는 못 들어간다(코드가 진입 차단). ");
                    sb.append("열쇠·도구로 열면 <ITEM_USE>의 unlock으로 해제하라(이후 계속 열림).\n");
                }
            }
        }
        // 오염 컨텍스트 추가
        sb.append(corruptMan.buildCorruptionContext(gdam));
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  선제 배역 배정
    // ──────────────────────────────────────────────────────────────

    /** 현재 활동 가능한(온라인·서바이벌) 플레이어 수. */
    private int activeSurvivorCount() {
        return (int) Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getGameMode() == GameMode.SURVIVAL).count();
    }

    /** 휘말림(외부인) 배역에 줄 한글 실명 풀 — AI 생성 배역이 아니라 코드에서 이름을 부여해야 계정명 노출이 없다. */
    private static final String[] EXTRA_CHAR_NAMES = {
        "김도현","이서연","박지훈","최유나","정민재","강수빈","조현우","윤가람",
        "장태경","임하늘","오세진","한지원","신동욱","서아름","권민호","배소율",
        "남궁성","문재이","유다온","홍시현"
    };

    /** 휘말림(외부인) 배역의 평범한 민간인 직업 풀 — 원년 캐릭터가 환상/괴담풍 직업이어도 휘말림 배역엔 새지 않도록 코드에서 부여. */
    private static final String[] EXTRA_BYSTANDER_JOBS = {
        "회사원","택배 기사","편의점 점원","대학생","청소 노동자","경비원","간병인","배달원",
        "주부","공장 노동자","택시 기사","자영업자","학원 강사","간호조무사","마트 직원","공무원"
    };

    /** EXTRA_CHAR_NAMES 중 아직 안 쓴 이름 1개 반환(used에 추가). 풀 소진 시 숫자 접미사. */
    private String pickExtraName(java.util.Set<String> used) {
        for (String n : EXTRA_CHAR_NAMES) if (used.add(n)) return n;
        for (int k = 2; ; k++)
            for (String n : EXTRA_CHAR_NAMES) { String c = n + k; if (used.add(c)) return c; }
    }

    /**
     * 플레이어 수가 .gdam 배역 수보다 많으면, 부족한 만큼 '사건에 휘말리는 주변 인물' 배역을 gdam.roles에 추가한다.
     * → 남는 플레이어도 관전이 아니라 평범한 외부인으로 등장해 사건에 휘말린다.
     * ★buildGmPrompt·doPreAssign ★보다 먼저★ 호출해야 GM 인지·배정에 함께 반영된다.
     */
    private void ensureEnoughRoles(JsonObject gdam, int playerCount) {
        if (gdam == null || !gdam.has("roles") || !gdam.get("roles").isJsonArray()) return;
        JsonArray roles = gdam.getAsJsonArray("roles");
        int have = roles.size();
        if (have == 0 || playerCount <= have) return;

        List<String> zones = new ArrayList<>();
        for (JsonElement el : roles) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            if (r.has("zone") && !r.get("zone").getAsString().isBlank()) zones.add(r.get("zone").getAsString());
        }
        // 이미 쓰인 이름(배역 char_name + NPC명) 수집 — 휘말림 배역 이름이 겹치지 않게
        java.util.Set<String> usedNames = new java.util.HashSet<>();
        for (JsonElement el : roles)
            if (el.isJsonObject() && el.getAsJsonObject().has("char_name"))
                usedNames.add(el.getAsJsonObject().get("char_name").getAsString());
        if (gdam.has("npcs") && gdam.get("npcs").isJsonArray())
            for (JsonElement el : gdam.getAsJsonArray("npcs"))
                if (el.isJsonObject()) { String nm = getStr(el.getAsJsonObject(), "name"); if (!nm.isBlank()) usedNames.add(nm); }

        int need = playerCount - have;
        for (int i = 0; i < need; i++) {
            JsonObject r = new JsonObject();
            r.addProperty("role_id", "role_extra" + (i + 1));
            r.addProperty("name", "휘말린 외부인");
            r.addProperty("is_core", false);
            // ★char_name/gender를 코드에서 부여 — AI 생성 배역이 아니라 누락되면 계정명이 노출되고 이름이 안 정해진다.
            r.addProperty("char_name", pickExtraName(usedNames));
            r.addProperty("gender", ThreadLocalRandom.current().nextBoolean() ? "남성" : "여성");
            // ★평범한 민간인 직업·성인 나이를 코드에서 부여★ — job_pool/age_range가 없으면 배역 배정(applyRoleJob)이
            //   원년(기본) 직업·나이를 그대로 둬, 원년 캐릭터가 환상/괴담풍이면 휘말림 배역까지 그 값(예: 12세 '환영의 엮음이')이 노출된다.
            JsonArray jobPool = new JsonArray();
            for (String j : EXTRA_BYSTANDER_JOBS) jobPool.add(j);
            r.add("job_pool", jobPool);
            JsonArray ageRange = new JsonArray(); ageRange.add(20); ageRange.add(55);
            r.add("age_range", ageRange);
            if (!zones.isEmpty()) r.addProperty("zone", zones.get(i % zones.size())); // 사건 현장(또는 그 일대)에 분산 배치
            r.addProperty("role_type", "bystander");
            JsonArray info = new JsonArray();
            info.add("당신은 우연히 이 장소에 있게 된 평범한 사람이다 — 특별한 사명도, 사전 정보도 없다.");
            info.add("그저 평소처럼 지내려던 참이었지만, 곧 이 자리에서 벌어지는 일에 본의 아니게 휘말린다.");
            r.add("initial_info", info);
            r.addProperty("hidden_info", "사건과 무관한 외부인으로 우연히 현장에 있었다. 처음엔 영문을 모르지만 진행될수록 직접 위협에 노출되어 휘말린다.");
            roles.add(r);
        }
        plugin.getLogger().info("[TRPG] 플레이어(" + playerCount + ") > 배역(" + have + ") → '휘말린 외부인' 배역 " + need + "개 추가");
    }

    /**
     * 캐릭터 생성 전 역할을 미리 배정하여 age_range·job_pool을 chargen에 전달.
     * pd가 없는 상태에서 호출하므로 PlayerData 수정은 하지 않는다.
     */
    private void doPreAssign(List<Player> players, JsonObject gdam) {
        preAssignedRoleData.clear();
        preAssignments.clear();
        gmNpcRoleIds.clear();
        if (!gdam.has("roles")) return;

        List<JsonObject> coreRoles  = new ArrayList<>();
        List<JsonObject> extraRoles = new ArrayList<>();
        for (JsonElement el : gdam.getAsJsonArray("roles")) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("is_core") && r.get("is_core").getAsBoolean()) coreRoles.add(r);
            else extraRoles.add(r);
        }
        List<JsonObject> ordered = new ArrayList<>(coreRoles);
        ordered.addAll(extraRoles);

        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        // 피날레: 각 플레이어를 '자신의 원년 캐릭터(char_name 일치)' 배역에 배정한다(복귀 캐스트로 생성됐으므로 대개 매칭됨).
        //   매칭 안 되면 남은 배역으로 폴백 → 최악의 경우에도 정상 진행(원년 정체성은 assignRolesAndStart에서 복원).
        boolean finale = state.getRoomNumber() == FINAL_ROOM;
        java.util.Set<Integer> usedRoles = new java.util.HashSet<>();
        if (finale) {
            List<Player> unmatched = new ArrayList<>();
            for (Player pl : shuffled) {
                PlayerData pd = state.getPlayer(pl);
                int matchIdx = -1;
                if (pd != null && pd.hasOrigChar && !pd.origCharName.isEmpty()) {
                    for (int j = 0; j < ordered.size(); j++) {
                        if (usedRoles.contains(j)) continue;
                        String cn = ordered.get(j).has("char_name") ? ordered.get(j).get("char_name").getAsString() : "";
                        if (!cn.isEmpty() && cn.equalsIgnoreCase(pd.origCharName)) { matchIdx = j; break; }
                    }
                }
                if (matchIdx >= 0) {
                    usedRoles.add(matchIdx);
                    preAssignedRoleData.put(pl.getUniqueId(), ordered.get(matchIdx));
                    preAssignments.put(pl.getUniqueId(), roleDataToAssignment(ordered.get(matchIdx)));
                } else unmatched.add(pl);
            }
            int j = 0;
            for (Player pl : unmatched) {
                while (j < ordered.size() && usedRoles.contains(j)) j++;
                if (j >= ordered.size()) break;
                usedRoles.add(j);
                preAssignedRoleData.put(pl.getUniqueId(), ordered.get(j));
                preAssignments.put(pl.getUniqueId(), roleDataToAssignment(ordered.get(j)));
            }
        } else {
            for (int i = 0; i < shuffled.size() && i < ordered.size(); i++) {
                usedRoles.add(i);
                UUID uuid = shuffled.get(i).getUniqueId();
                JsonObject role = ordered.get(i);
                preAssignedRoleData.put(uuid, role);
                preAssignments.put(uuid, roleDataToAssignment(role));
            }
        }
        // 남은(미사용) 배역 → GM이 직접 조종
        for (int i = 0; i < ordered.size(); i++) {
            if (usedRoles.contains(i)) continue;
            JsonObject role = ordered.get(i);
            if (role.has("role_id")) gmNpcRoleIds.add(role.get("role_id").getAsString());
        }
        if (!gmNpcRoleIds.isEmpty()) {
            plugin.getLogger().info("[TRPG] GM NPC 배역: " + gmNpcRoleIds);
        }
    }

    private RoleManager.RoleAssignment roleDataToAssignment(JsonObject r) {
        String roleId   = r.has("role_id")   ? r.get("role_id").getAsString()   : "role_?";
        String roleName = r.has("name")      ? r.get("name").getAsString()      : "알 수 없는 배역";
        String zone     = r.has("zone")      ? r.get("zone").getAsString()      : "zone_A";
        boolean adv     = r.has("knowledge_advantage") && r.get("knowledge_advantage").getAsBoolean();
        String charName = r.has("char_name") ? r.get("char_name").getAsString() : "";
        String gender   = r.has("gender")    ? r.get("gender").getAsString()    : "";
        List<String> info = new ArrayList<>();
        if (r.has("initial_info")) {
            r.getAsJsonArray("initial_info").forEach(i -> info.add(i.getAsString()));
        }
        return new RoleManager.RoleAssignment(roleId, roleName, zone, info, adv, charName, gender);
    }

    // ──────────────────────────────────────────────────────────────
    //  주사위 판정 애니메이션
    // ──────────────────────────────────────────────────────────────

    private boolean needsDiceAnimation(String text) {
        return text.contains("[판정]") || text.contains("d20")
            || text.contains("주사위를 굴") || text.contains("판정이 필요") || text.contains("판정을 진행");
    }

    private void playDiceAnimation(Player player) {
        player.showTitle(Title.title(
            Component.text("🎲", NamedTextColor.GOLD, TextDecoration.BOLD),
            Component.text("판정 진행 중...", NamedTextColor.YELLOW),
            Title.Times.times(
                Duration.ofMillis(100),
                Duration.ofMillis(800),
                Duration.ofMillis(300)
            )
        ));
    }

    /**
     * GM이 준 &lt;DICE&gt; 결과를 강조 연출한다 — 숫자가 또르르 바뀌다 최종 《N》을 3초간 크게 보여주고,
     * 서브타이틀로 '어디까지가 성공인지(성공 기준 DC)'와 결과를 명확히 표시한다.
     */
    private void playDiceResult(Player player, JsonObject dice) {
        int max  = dice.has("max") && !dice.get("max").isJsonNull() ? Math.max(2, dice.get("max").getAsInt()) : 20;
        int dc   = dice.has("dc") && !dice.get("dc").isJsonNull() ? dice.get("dc").getAsInt() : -1;
        String reason = dice.has("reason") && !dice.get("reason").isJsonNull() ? dice.get("reason").getAsString().trim() : "";
        // ★공정성★: roll·outcome은 AI가 아니라 ★코드가 직접★ 정한다.
        //   (AI가 유리한 값만 고르던 문제[거의 다 성공] + '주사위는 성공인데 서술은 실패' 불일치를 동시에 제거)
        int roll = ThreadLocalRandom.current().nextInt(1, max + 1);
        int effDc = dc > 0 ? Math.max(2, Math.min(max, dc)) : (int) Math.ceil(max * 0.55); // dc 미지정 시 중앙값보다 약간 높게
        int band = Math.max(1, max / 10);
        boolean success = roll >= effDc;
        boolean fail    = roll <  effDc - band;
        boolean partial = !success && !fail;
        String outcome = success ? "성공" : partial ? "부분성공" : "실패";
        NamedTextColor col = success ? NamedTextColor.GREEN : fail ? NamedTextColor.RED : NamedTextColor.GOLD;
        // '왜 굴리는지'를 먼저 알려준다(요청 사항)
        player.sendMessage("§e[판정] " + (reason.isEmpty() ? "행동 판정" : reason)
            + " §7— 주사위 d" + max + (dc > 0 ? " (" + dc + " 이상 성공)" : "") + " 굴립니다…");
        // 서브타이틀: 굴린 주사위 크기(d{max})와 '어디까지가 성공인지(DC)'를 명확히
        String thresh = dc > 0 ? (dc + " 이상이면 성공") : "판정";
        String sub = "d" + max + "  ·  " + thresh + "  ·  " + outcome;
        final int fmax = max;
        // ★GM 다음 전개 일관성★: 코드가 정한 결과를 컨텍스트에 주입 — 다음 서술이 이 결과와 어긋나지 않게.
        ai.injectGmSystem("[판정 결과] " + (reason.isEmpty() ? "" : reason + " — ")
            + "주사위 d" + max + "=" + roll + (dc > 0 ? (", 성공기준 " + dc) : "") + " → ★" + outcome + "★. "
            + "이 결과대로 다음 전개를 이어서 서술하라. 결과와 어긋나게(실패인데 성공한 듯, 또는 그 반대로) 쓰지 마라.");
        // 1) 숫자가 바뀌는 연출(약 1.5초, d{max} 무작위) — 3틱 간격
        final int FRAMES = 10;
        for (int i = 0; i < FRAMES; i++) {
            final int n = ThreadLocalRandom.current().nextInt(1, fmax + 1);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) player.showTitle(Title.title(
                    Component.text("🎲 " + n, NamedTextColor.GRAY, TextDecoration.BOLD),
                    Component.text("주사위(d" + fmax + ")를 굴리는 중...", NamedTextColor.DARK_GRAY),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(220), Duration.ZERO)));
            }, i * 3L);
        }
        // 2) 최종 결과 강조 — 《N》 3초 유지 + 성공 기준 서브타이틀
        final int froll = roll, fdc = dc;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.showTitle(Title.title(
                Component.text("《 " + froll + " 》", col, TextDecoration.BOLD),
                Component.text(sub, col),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(3000), Duration.ofMillis(600))));
            player.sendMessage("§7[판정] 주사위 d" + fmax + " " + colorCode(col) + "《" + froll + "》"
                + (fdc > 0 ? " §8(성공 기준 " + fdc + " 이상)" : "")
                + " §7→ " + colorCode(col) + (outcome.isEmpty() ? "판정" : outcome));
        }, FRAMES * 3L + 2L);
    }

    /** NamedTextColor → §코드 (채팅 기록 강조용) */
    private static String colorCode(NamedTextColor c) {
        if (c == NamedTextColor.GREEN) return "§a";
        if (c == NamedTextColor.RED)   return "§c";
        return "§6";
    }

    // ══════════════════════════════════════════════════════════════
    //  자동 세이브 / 이어하기 (예기치 못한 중단 후 복구 — API 소진·서버 재시작 등)
    //  게임 전체(시나리오·스테이지·진행도·오염도·플레이어·특성·GM 기억)를
    //  턴마다 saves/autosave.json 에 비동기로 기록한다. /trpg resume 으로 복원.
    // ══════════════════════════════════════════════════════════════

    private static final int SAVE_VERSION = 1;

    /** 자동 세이브 파일 (plugins/AICraft/saves/autosave.json). */
    private java.io.File autoSaveFile() {
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "saves");
        if (!dir.exists()) dir.mkdirs();
        return new java.io.File(dir, "autosave.json");
    }

    /** 턴이 바뀔 때마다 1회 자동 저장(같은 턴 중복 저장 방지). 일상·괴담 파트에서만. */
    private void maybeAutoSave() {
        if (currentPhase != Phase.HORROR && currentPhase != Phase.DAILY) return;
        int turn = state.getCurrentTurn();
        if (turn == lastAutoSaveTurn) return;
        lastAutoSaveTurn = turn;
        autoSave();
    }

    /**
     * 현재 게임 전체를 JSON 스냅샷으로 저장한다. 직렬화는 메인 스레드에서 일관 시점으로
     * 문자열화하고, 디스크 쓰기만 비동기로 처리(메인 스레드 차단 방지). 임시파일→원자 교체.
     */
    public void autoSave() {
        if (!state.isSessionActive()) return;
        if (replayLock) return; // 재현 세션은 이어하기 대상이 아니므로 저장하지 않음
        final String json;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", SAVE_VERSION);
            root.addProperty("phase", currentPhase.name());
            root.addProperty("familiarMode", familiarMode);
            root.addProperty("familiarFilter", familiarFilter);
            root.addProperty("quality", ai.getGmQuality().name());
            root.addProperty("nextStageUnlocked", nextStageUnlocked);
            root.addProperty("forceRetryAllowed", forceRetryAllowed);
            root.add("state", state.snapshot());
            root.add("gmContext", ai.exportGmContext());
            root.add("npcZones", saveGson.toJsonTree(npcZones));
            root.add("npcContacts", saveGson.toJsonTree(npcContactNumbers));
            root.add("gmNpcRoleIds", saveGson.toJsonTree(gmNpcRoleIds));
            JsonArray sp = new JsonArray();
            spawnedPlayers.forEach(u -> sp.add(u.toString()));
            root.add("spawnedPlayers", sp);
            json = saveGson.toJson(root); // 메인 스레드에서 일관 시점 문자열화
        } catch (Exception e) {
            plugin.getLogger().warning("[세이브] 스냅샷 생성 실패: " + e.getMessage());
            return;
        }
        final java.io.File file = autoSaveFile();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.io.File tmp = new java.io.File(file.getParentFile(), "autosave.json.tmp");
                java.nio.file.Files.writeString(tmp.toPath(), json);
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                plugin.getLogger().warning("[세이브] 파일 쓰기 실패: " + e.getMessage());
            }
        });
    }

    /** 자동 세이브 파일 삭제 — 게임을 의도적으로 끝낼 때만(서버 재시작에서는 보존해 이어하기 가능). */
    private void deleteAutoSave() {
        try { java.io.File f = autoSaveFile(); if (f.exists()) f.delete(); } catch (Exception ignore) {}
        lastAutoSaveTurn = -1;
    }

    /** 이어할 수 있는 자동 세이브가 있는지. */
    public boolean hasAutoSave() {
        java.io.File f = autoSaveFile();
        return f.exists() && f.length() > 0;
    }

    /** /trpg resume — 예기치 못하게 끊긴 게임을 자동 세이브에서 중단 지점부터 이어 진행. */
    /** /trpg jobrefresh (OP) — 직업 풀을 강제로 비우고 AI로 새로 생성(캐시 파일·서버 재시작 불필요). */
    public void forceJobRefresh(Player admin) {
        admin.sendMessage("§e[직업풀] 강제 재생성을 시작합니다... (AI 응답까지 잠시 걸릴 수 있음)");
        charGen.forceRefreshJobPools().thenRun(() ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (admin.isOnline())
                    admin.sendMessage("§a[직업풀] 재생성 완료. (실패 시 콘솔 경고 + 정적 풀 유지 — 로그 확인)");
            }));
    }

    public void resumeSession(Player initiator) {
        if (currentPhase != Phase.IDLE) {
            initiator.sendMessage("§c이미 TRPG 세션이 진행 중입니다. /trpg stop 후 시도하세요.");
            return;
        }
        java.io.File file = autoSaveFile();
        if (!file.exists() || file.length() == 0) {
            initiator.sendMessage("§c이어할 자동 저장 기록이 없습니다.");
            return;
        }
        JsonObject root;
        try {
            String json = java.nio.file.Files.readString(file.toPath());
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            initiator.sendMessage("§c자동 저장 파일을 읽을 수 없습니다: " + e.getMessage());
            return;
        }
        // ① 게임 상태(시나리오·스테이지·진행도·오염도·플레이어·특성) 복원
        try {
            if (root.has("state")) state.restore(root.getAsJsonObject("state"));
        } catch (Exception e) {
            initiator.sendMessage("§c저장 데이터 복원 실패: " + e.getMessage());
            return;
        }
        if (!state.isSessionActive() || state.getGdamData() == null) {
            initiator.sendMessage("§c저장 데이터가 손상되어 이어할 수 없습니다.");
            state.endSession(false);
            currentPhase = Phase.IDLE;
            return;
        }
        // ② 세션 설정 복원
        familiarMode      = root.has("familiarMode") && root.get("familiarMode").getAsBoolean();
        familiarFilter    = root.has("familiarFilter") ? root.get("familiarFilter").getAsString() : "random";
        nextStageUnlocked = !root.has("nextStageUnlocked") || root.get("nextStageUnlocked").getAsBoolean();
        forceRetryAllowed = root.has("forceRetryAllowed") && root.get("forceRetryAllowed").getAsBoolean();
        if (root.has("quality")) {
            try { ai.setGmQuality(AiManager.Quality.valueOf(root.get("quality").getAsString())); } catch (Exception ignore) {}
        }
        replayLock = false;
        concludingEnding = false;
        // ③ GM 기억(컨텍스트=무상태 LLM의 유일한 기억) 복원
        ai.clearAll();
        if (root.has("gmContext") && root.get("gmContext").isJsonArray())
            ai.importGmContext(root.getAsJsonArray("gmContext"));
        // ④ NPC 위치·연락처·GM 직접조종 배역 복원(initNpcZones는 호출하지 않음 — 이동분 보존)
        npcZones.clear();
        if (root.has("npcZones") && root.get("npcZones").isJsonObject())
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("npcZones").entrySet())
                npcZones.put(e.getKey(), e.getValue().getAsString());
        npcContactNumbers.clear();
        if (root.has("npcContacts") && root.get("npcContacts").isJsonObject())
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("npcContacts").entrySet())
                npcContactNumbers.put(e.getKey(), e.getValue().getAsString());
        gmNpcRoleIds.clear();
        if (root.has("gmNpcRoleIds") && root.get("gmNpcRoleIds").isJsonArray())
            for (JsonElement el : root.getAsJsonArray("gmNpcRoleIds")) gmNpcRoleIds.add(el.getAsString());
        // ⑤ 등장(spawn) 상태 복원
        spawnedPlayers.clear();
        if (root.has("spawnedPlayers") && root.get("spawnedPlayers").isJsonArray())
            for (JsonElement el : root.getAsJsonArray("spawnedPlayers")) {
                try { spawnedPlayers.add(UUID.fromString(el.getAsString())); } catch (Exception ignore) {}
            }
        // 동물 형태(revive_as_animal)는 PlayerData.status="animal"에서 재구성(변신·관조·행동불능·빙의 지속은 일시 상태라 복원 안 함 — 본체로 복귀)
        morphTurns.clear(); observerTurns.clear(); animalForm.clear(); stunTurns.clear(); possessingNpc.clear();
        commBypassTurn.clear(); commBypassStealth.clear();
        resetOverviewCache(); // 로드 시엔 개요 캐시를 비워 현재 시나리오로 재생성(lastFiredEventLabel은 GSM 스냅샷에서 복원)
        loadForbiddenWord(); // 금지워드형 괴담의 금지어 복원
        for (PlayerData pd : state.getAllPlayers())
            if ("animal".equals(pd.status) && !pd.isDead) animalForm.add(pd.uuid);
        // ⑥ 프롬프트 재구성(gmNpcRoleIds 반영) + 단계 복원(진행 불가 단계면 괴담 파트로 안전 복귀)
        gmSystemPrompt = buildGmPrompt(state.getGdamData());
        try { currentPhase = Phase.valueOf(root.get("phase").getAsString()); } catch (Exception e) { currentPhase = Phase.HORROR; }
        if (currentPhase != Phase.DAILY && currentPhase != Phase.HORROR) currentPhase = Phase.HORROR;
        lastNpcBeatTurn = state.getCurrentTurn();
        lastRewindCaptureTurn = -1;
        lastAutoSaveTurn = -1;
        ai.markSessionStart(); // 비용 집계 기준점(이어하기 = 새 세션처럼 0부터)
        mapMan.loadScenario(state.getGdamData());
        gameLogger.startNewLog(state.getCurrentSeed(), state.getRoomNumber(), getEntityName());
        gameLogger.section("게임 이어하기 — 스테이지 " + state.getRoomNumber() + " / 턴 " + state.getCurrentTurn());

        broadcast("§e§l═══ 게임 이어하기 (스테이지 " + state.getRoomNumber() + ") ═══");
        broadcast("§7중단된 지점의 상태·기억을 복원해 이어서 진행합니다.");

        // ⑦ 접속 중인 플레이어에게 아이템·스코어보드 복원 + 개인 복귀 안내
        for (PlayerData pd : state.getAllPlayers()) {
            Player p = Bukkit.getPlayer(pd.uuid);
            if (p == null || !p.isOnline()) continue;
            giveInfoItem(p);
            giveRecordItem(p);
            mapMan.giveStartMap(p);
            giveNotepadItem(p);
            scoreMan.update(p, pd, state.getRoomNumber());
            if (spawnedPlayers.contains(pd.uuid) && !pd.isDead)
                narrativeDelivery.deliver(p, "끊겼던 의식이 다시 또렷해진다. 멈춰 있던 이야기가 그대로 이어진다.");
        }
        initiator.sendMessage("§a이어하기 완료. 그대로 행동을 입력해 진행하세요.");
    }

    // ──────────────────────────────────────────────────────────────
    //  저장 세션 불러오기
    // ──────────────────────────────────────────────────────────────

    public void loadSession(Player initiator, String seed) {
        if (currentPhase != Phase.IDLE) {
            initiator.sendMessage("§c이미 TRPG 세션이 진행 중입니다. /trpg stop 후 시도하세요.");
            return;
        }
        JsonObject gdam = gdamGen.load(seed);
        if (gdam == null) {
            initiator.sendMessage("§c씨드 '" + seed + "'의 저장 파일을 찾을 수 없습니다.");
            initiator.sendMessage("§7/trpg list 로 저장된 세션을 확인하세요.");
            return;
        }

        int room = gdam.has("room") ? gdam.get("room").getAsInt()
                 : (state.isSessionActive() ? state.getRoomNumber() + 1 : 1);
        broadcast("§e§l═══ TRPG 세션 로드 (씨드: " + seed + ") ═══");
        broadcast("§7.gdam 파일을 불러왔습니다. 캐릭터를 생성합니다...");

        replayLock = false; // 일반 로드 — 재현 잠금 해제
        currentPhase = Phase.CHAR_CREATION;
        state.startSession(room, seed, gdam);
        applyScenarioFlavor(); // 친숙(프로젝트 문·게임) 테마 특성 지침 주입
        ensureEnoughRoles(gdam, activeSurvivorCount()); // 플레이어 수 > 배역 수면 휘말림 배역 보강(프롬프트 전에)
        gmSystemPrompt = buildGmPrompt(gdam);
        ai.clearAll();
        ai.markSessionStart(); // 비용 집계 시작점(로드 = 새 세션 시작)

        List<Player> survivors = Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
            .collect(Collectors.toList());

        if (survivors.isEmpty()) {
            broadcast("§c서바이벌 모드 플레이어가 없습니다.");
            currentPhase = Phase.IDLE;
            return;
        }

        doPreAssign(survivors, gdam);

        survivors.forEach(p -> {
            pendingCreation.add(p.getUniqueId());
            charGen.generate(p) // 시나리오 무관 완전 무작위 캐릭터 생성
                .thenAccept(pd -> {
                    state.addPlayer(pd);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!p.isOnline()) {
                            pendingCreation.remove(p.getUniqueId());
                            checkAllConfirmed();
                            return;
                        }
                        showCharacterSheetForPlayer(p, pd);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("캐릭터 생성 실패 (" + p.getName() + "): " + ex.getMessage());
                    pendingCreation.remove(p.getUniqueId());
                    plugin.getServer().getScheduler().runTask(plugin, this::checkAllConfirmed);
                    return null;
                });
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  재현(replay) 세션 — 기록된 시드·캐릭터로 해당 스테이지만 재현
    // ──────────────────────────────────────────────────────────────

    public void replaySession(Player initiator, String fileName) {
        if (currentPhase != Phase.IDLE) {
            initiator.sendMessage("§c이미 TRPG 세션이 진행 중입니다. /trpg stop 후 시도하세요.");
            return;
        }
        JsonObject root = replayMan.readReplay(fileName);
        if (root == null) {
            initiator.sendMessage("§c재현 파일 '" + fileName + "'을(를) 찾을 수 없습니다. §7/trpg replaylist §c로 확인하세요.");
            return;
        }
        String seed = root.has("seed") ? root.get("seed").getAsString() : "";
        int stage   = root.has("stage") ? root.get("stage").getAsInt() : 1;
        JsonObject gdam = gdamGen.load(seed);
        if (gdam == null) {
            initiator.sendMessage("§c이 서버에 시드 '" + seed + "'의 시나리오(.gdam)가 없어 재현할 수 없습니다.");
            initiator.sendMessage("§7(재현 파일은 시드만 기록하므로 원본 .gdam이 같은 서버에 있어야 합니다.)");
            return;
        }
        if (!root.has("players") || root.getAsJsonArray("players").size() == 0) {
            initiator.sendMessage("§c재현 파일에 캐릭터 정보가 없습니다.");
            return;
        }

        List<Player> survivors = Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
            .collect(Collectors.toList());
        if (survivors.isEmpty()) {
            initiator.sendMessage("§c서바이벌 모드 플레이어가 없습니다.");
            return;
        }

        broadcast("§e§l═══ 재현 세션 시작 (스테이지 " + stage + ", 씨드 " + seed + ") ═══");
        broadcast("§7기록된 캐릭터로 시작합니다. 이 스테이지만 진행되며 이어서 진행할 수 없습니다.");

        replayLock = true;
        currentPhase = Phase.DAILY;
        state.startSession(stage, seed, gdam); // players.clear() 포함 — 복원 배정은 이 이후
        applyScenarioFlavor(); // 친숙(프로젝트 문·게임) 테마 특성 지침 주입 (재개·이어하기 포함)
        gameLogger.startNewLog(seed, stage, getEntityName());
        ai.clearAll();
        ai.markSessionStart(); // 비용 집계 시작점(재현 = 새 세션 시작)

        // 기록된 캐릭터를 접속 중인 생존 플레이어에게 순서대로 복원 (min 인원만 참여)
        JsonArray recorded = root.getAsJsonArray("players");
        int n = Math.min(survivors.size(), recorded.size());
        Set<String> usedRoleIds = new HashSet<>();
        for (int i = 0; i < survivors.size(); i++) {
            Player p = survivors.get(i);
            p.getInventory().clear();
            if (i >= n) { p.sendMessage("§7이 재현에는 " + n + "명만 참여합니다. 관전하세요."); continue; }
            PlayerData pd = replayMan.deserializePlayer(recorded.get(i).getAsJsonObject(), p.getUniqueId(), p.getName());
            state.addPlayer(pd);
            usedRoleIds.add(pd.roleId);
        }

        // 플레이어가 맡지 않은 배역 = GM 직접 조종 NPC
        gmNpcRoleIds.clear();
        if (gdam.has("roles")) {
            for (JsonElement el : gdam.getAsJsonArray("roles")) {
                JsonObject r = el.getAsJsonObject();
                if (r.has("role_id") && !usedRoleIds.contains(r.get("role_id").getAsString()))
                    gmNpcRoleIds.add(r.get("role_id").getAsString());
            }
        }

        // common_items 보유 추적 복원
        if (gdam.has("common_items")) {
            gdam.getAsJsonArray("common_items").forEach(el -> {
                String itemId = el.getAsString().trim();
                if (!itemId.isEmpty()) state.getAllPlayers().forEach(pd -> noteHeldItem(pd, itemId));
            });
        }

        gmSystemPrompt = buildGmPrompt(gdam); // gmNpcRoleIds 반영 후 재생성

        // 등장 배역 spawn 설정 + 배역 시작 아이템 지급
        for (PlayerData pd : state.getAllPlayers()) {
            Player p = Bukkit.getPlayer(pd.uuid);
            if (p == null) continue;
            if (isImmediateSpawn(pd.roleId)) spawnedPlayers.add(pd.uuid);
            giveRoleStartItems(p, pd.roleId);
        }

        startDailyPhase(); // replayLock=true이므로 재기록은 건너뜀
    }

    public List<String> listReplays()              { return replayMan.listReplays(); }
    public List<String> listSavedSeeds()           { return gdamGen.listSavedSeeds(); }
    public String       exportGdamJson(String seed) { return gdamGen.exportJson(seed); }

    // ──────────────────────────────────────────────────────────────
    //  상태 조회
    // ──────────────────────────────────────────────────────────────

    public GameStateManager getState()              { return state; }
    public boolean hasPlayer(Player p)              { return state.hasPlayer(p.getUniqueId()); }
    public DialogManager getDialogManager()         { return dialogMan; }
    public TraitManager getTraitManager()           { return traitMan; }
    public NarrativeDelivery getNarrativeDelivery() { return narrativeDelivery; }

    /** /trpg status — 진행 상황 + AI 누적 비용을 다이얼로그로 깔끔하게 표시. */
    public void openStatusDialog(Player player) {
        List<String> lines = new ArrayList<>();
        if (state.isSessionActive()) {
            lines.add("§6§l진행 상황");
            lines.add("§7스테이지 §f" + state.getRoomNumber()
                + " §8| " + (state.isDailyPhase()
                    ? "§f일상 §7" + state.getDailyTurnsLeft() + "턴"
                    : "§f" + state.getTimelineStage() + "단계")
                + " §8| §7오염 §f" + state.getCorruption().level);
            lines.add("§7생존 §f" + state.getAliveCount() + "§7/§f" + state.getTotalCount() + "명");
        } else {
            lines.add("§7진행 중인 세션이 없습니다.");
        }
        lines.add("§8 ");
        lines.add("§6§lAI 비용");
        lines.add("§7스테이지  §f" + ai.usageLabel(ai.stageUsage()));
        lines.add("§7시작이후  §f" + ai.usageLabel(ai.sessionUsage()));
        lines.add("§7이번가동  §f" + ai.usageLabel(ai.lifetimeUsage()));
        lines.add("§7전체누적  §f" + ai.usageLabel(ai.allTimeUsage()));
        dialogMan.showStatusDialog(player, "TRPG 상태", lines);
    }

    /** 플러그인 종료·리로드 직전 — 영구 사용량을 동기 저장한다. */
    public void saveUsageOnDisable() { ai.saveUsageSync(); }

    /** 주기적 자동 저장(서버 비정상 종료 대비) — 변경이 있을 때만 비동기 기록. */
    public void saveUsagePeriodic() { ai.saveUsage(); }
}
