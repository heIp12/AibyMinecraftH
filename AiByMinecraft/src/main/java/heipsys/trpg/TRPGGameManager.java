package heipsys.trpg;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import heipsys.AICraft;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final String GM_SYSTEM_BASE = """
너는 하드코어 생존 괴담 TRPG의 '게임 마스터(GM)'야.
플레이어들이 Minecraft 서버에서 채팅으로 행동을 입력하면
그에 맞게 스토리를 진행한다.

## 핵심 원칙

### 괴담 서술 절대 금지 ★ 최우선
괴담/entity는 절대 아래 행동을 하지 않는다:
- 1인칭으로 플레이어에게 직접 말하기 ("나는...", "당신이 의심하기 시작한 걸 본다")
- 자신의 내면·시점·동기를 직접 서술하기
- 자신의 행동을 스스로 설명하기
- 플레이어가 무엇을 생각하는지 알고 있다는 식의 메타 인식
- 독백, 편지, 음성 메시지 등 어떤 형식으로도 플레이어에게 직접 전달
괴담은 오직 물리적 현상·환경 이상·NPC 행동으로만 간접 표현된다.
"거울 속에서 손이 움직인다." ✓  "[거울 속 이웃] 당신이 의심하기 시작했다는 걸 본다." ✗

### 단서 배치 원칙 ★
단서는 플레이어가 직접 탐색하고 발견할 때만 드러난다:
- NPC가 핵심 단서를 대화 중 자발적으로 언급 금지
  (예: 처음 만난 NPC가 "거울 조심해요" 직접 말하기 금지)
- 첫 만남이나 시작 장면에서 핵심 단서 2개 이상 동시 노출 금지
- 단서는 탐색 행동의 결과, 우연한 시각적 발견, 배경 오브젝트에서만 발생
- NPC는 자신이 직접 경험한 사실만 언급, 해석·결론 제시 금지
- 대화 장면에서는 분위기·감정만, 핵심 정보는 탐색으로만

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

### initial_info 처리 원칙 ★
배역의 initial_info는 그 배역이 이미 알고 있는 배경 지식이다.
일상 파트 첫 장면에 자연스러운 장면 묘사로만 녹여내라. 직접 목록 나열 절대 금지.
좋은 예: "당신은 요즘 건물 3층에서 이상한 소리가 난다는 소문이 떠돈다는 것을 알고 있다."
나쁜 예: "당신이 알고 있는 정보: 1. 3층에서 소리가 남. 2. ..." ← 절대 금지

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

### 개인 서술 원칙 ★ 최우선
각 플레이어는 GM과 개인 채널로만 소통한다.
- 행동한 플레이어에게만 2인칭("당신은...") 시점으로 서술
- 다른 플레이어의 행동·결과·상태를 직접 공개 금지
- 같은 공간의 다른 플레이어가 감각으로 느낄 수 있는 것만:
  <WITNESS player="플레이어명">
  그 플레이어가 인지하는 것 (소리·빛·냄새·진동 등 단서. 원인·맥락 제외)
  </WITNESS>
  여러 명에게 각각 다른 WITNESS 가능

### 배역 등장 처리
spawn_timeline이 된 배역이 등장할 시점:
<SPAWN player="플레이어명"/>
이 태그 출력 시 해당 플레이어가 스토리에 진입한다.

### 위치 추적 ★ 필수
플레이어가 이동할 때마다 반드시 아래 태그를 출력한다:
<ZONE_UPDATE player="플레이어명" zone="존ID"/>
zone 값은 .gdam의 zones[].zone_id를 사용한다.
위치가 불명확하거나 이동하지 않은 경우에는 출력하지 않는다.
같은 zone에 있는 플레이어끼리는 자동으로 대면 통신이 가능해진다.

### 플레이어 간 직접 통신
플레이어는 "@이름 메시지" 또는 "@번호 메시지" 형식으로 통신을 시도한다.
시스템이 아래를 자동 판정하므로 GM은 관여하지 않는다:
- 같은 공간(zone): 대면 직접 전달 (번호 불필요, 자동 번호 교환)
- 기기(전화·무전기 등) + 상대 연락처를 앎: 기기 통신 직접 전달
연기신호·메모 투척·물리적 중계 같은 간접 방법은 일반 행동으로 입력되며 GM이 서술로 처리한다.

GM이 기기 통신 채널을 개설할 때 (예: 무전기를 건네줌):
<COMM from="플레이어A" to="플레이어B" method="무전기"/>
채널 종료 시:
<COMM_CLOSE from="플레이어A" to="플레이어B"/>

### 연락처 시스템 ★
모든 플레이어는 고유한 비공개 연락처(번호)를 가진다.
1회차에서 플레이어들은 서로의 연락처를 모르며, 따라서 기기 통신이 불가능하다.
연락처를 알게 되는 경로:
- 대면(같은 zone) 접촉으로 자동 교환 (시스템 처리)
- 특성(유명인·해커 등)으로 사전에 앎 (시스템 처리)
- 스토리 중 발견(메모·명함·NPC가 알려줌 등) 시 아래 태그 출력:
  <CONTACT_REVEAL to="알게된플레이어" target="대상플레이어"/>
임의로 연락처를 알려주지 마라. 1회차 기본은 "직접 만나야 번호를 안다".

오염 2단계 이상에서, 괴담이 통신을 교란할 때 특정 플레이어의 연락처를 바꿀 수 있다:
<CONTACT_CHANGE player="플레이어명"/>
출력 시 그 플레이어의 모든 연락처(번호·이메일·SNS)가 바뀌고 타인이 알던 연락처는 무효가 된다.

### 정체 차용 (entity.can_impersonate == true 인 괴담만) ★
변신·모방·도플갱어·빙의형 괴담은 플레이어를 제거하고 그 정체를 차지할 수 있다.
괴담은 그 플레이어가 평소 하던 행동(특성·능력 사용 제외)을 흉내 내 다른 플레이어를 속인다.
차용 시작:
<IMPERSONATE player="플레이어명"/>
→ 해당 플레이어는 이야기에서 제거되고, 이후 괴담이 그 사람인 척 행동·대화한다.
   다른 플레이어가 그 사람에게 말을 걸면 괴담(너)이 그 사람인 척 응답한다.
   괴담은 관찰로 학습한 그 플레이어의 말투·행동을 사용하되, 미세한 위화감을 남겨라.
   정체를 직접 밝히지 말고, 다른 플레이어가 스스로 의심하게 만들어라.
차용 종료(정체 노출/이탈 시):
<IMPERSONATE_END player="플레이어명"/>
주의: 다른 플레이어에게 그 플레이어의 죽음/차용 사실을 직접 알리지 마라. (스스로 알아내야 함)

### GM 내부 비공개 항목 (절대 공개 금지)
- 괴담의 정체 및 스케일
- 타임라인 세부 내용
- 해결법 및 생존법
- 역이용 경로
- 단서 배치 위치
- 스탯 산출 과정
- 일상 파트 턴 수

### 알고 있는 정보 서술 방식 ★
배역의 initial_info·hidden_info를 직접 나열하지 마라.
환경 묘사, 대화, 행동 결과로 자연스럽게 녹여서 전달한다.
"당신은 A를 알고 있다" ✗ → "문을 열자 A가 눈에 들어왔다" ✓
배역이 사전에 알고 있는 정보도 적절한 상황에서 자연스럽게 드러나도록 한다.

### GM NPC 조율
플레이어가 없는 배역은 GM이 직접 조종한다.
- 플레이어 행동·대화에 맞춰 자연스럽게 반응
- 해당 배역의 initial_info·hidden_info를 알고 있음
- 스토리 진행에 필요한 정보를 적절한 시점에 제공
- NPC의 죽음·퇴장·행동도 스토리에 맞게 자연스럽게 처리

### 서술 방식
- 2인칭 ("당신은...")
- 중요 판정 결과는 명확히 서술

### 출력 형식 ★ 필수
- 마크다운 절대 금지: #, ##, *, **, `, 목록 기호(-) 등 일체 사용 금지
- 장면 제목·헤더 붙이지 마라 ("# 울음상자 — 일상 파트" 같은 제목 절대 금지). 바로 서술로 시작한다.
- 강조가 필요하면 마크다운(*별표*)이 아니라 그냥 자연스러운 문장으로 표현하라.
- 인물의 대사(말소리)는 반드시 큰따옴표 "..." 로 감싼다. (시스템이 색으로 구분 처리함)
- 서술과 대사를 명확히 구분: 대사는 "..." 안에, 나머지는 서술.
""";

    // ──────────────────────────────────────────────────────────────
    //  세션 단계
    // ──────────────────────────────────────────────────────────────

    private enum Phase { IDLE, CHAR_CREATION, ROLE_ASSIGNMENT, DAILY, HORROR, CLEAR, GAMEOVER }

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

    /** 캐릭터 생성 완료 대기 중인 플레이어 UUID 집합 */
    private final Set<UUID> pendingCreation    = ConcurrentHashMap.newKeySet();
    /** 특성 선택 대기 중인 플레이어 */
    private final Set<UUID> pendingTraitSelect = ConcurrentHashMap.newKeySet();
    /** 스토리에 이미 등장한(spawn된) 플레이어 */
    private final Set<UUID> spawnedPlayers      = ConcurrentHashMap.newKeySet();
    /** GM이 개설한 기기 통신 채널: A → {B, C, ...} (양방향 저장) */
    private final Map<UUID, Set<UUID>> commChannels = new ConcurrentHashMap<>();
    /** 캐릭터 생성 전 선제 배역 배정 결과 (UUID → 배역 JsonObject) */
    private final Map<UUID, JsonObject> preAssignedRoleData = new ConcurrentHashMap<>();
    /** 캐릭터 생성 전 선제 배역 배정 결과 (UUID → RoleAssignment) */
    private final Map<UUID, RoleManager.RoleAssignment> preAssignments = new ConcurrentHashMap<>();
    /** 플레이어가 없어 GM이 직접 조종하는 배역 ID 집합 */
    private final Set<String> gmNpcRoleIds = ConcurrentHashMap.newKeySet();
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
        this.dialogMan         = new DialogManager();
        this.traitBtn          = new TraitButtonManager();
        this.corruptMan        = new CorruptionManager(state);
        this.compressor        = new ContextCompressor(ai, state);
        this.narrativeDelivery = new NarrativeDelivery(plugin);

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
        broadcast("§e§l═══ TRPG 세션 시작 (스테이지 " + room + ") ═══");
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

            // 선제 배역 배정: 캐릭터 생성 시 배역 맥락(나이/직업 범위)을 활용
            doPreAssign(survivors, gdam);

            survivors.forEach(p -> {
                pendingCreation.add(p.getUniqueId());
                JsonObject roleData = preAssignedRoleData.get(p.getUniqueId());
                charGen.generate(p, roleData)
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
        narrativeDelivery.clearAll();
        state.endSession(resetCorruption);
        ai.clearAll();
        pendingCreation.clear();
        pendingTraitSelect.clear();
        spawnedPlayers.clear();
        commChannels.clear();
        preAssignedRoleData.clear();
        preAssignments.clear();
        gmNpcRoleIds.clear();
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
    //  다음 스테이지 (/trpg next)
    // ══════════════════════════════════════════════════════════════

    public void nextSession(Player admin) {
        if (!state.isSessionActive()) {
            admin.sendMessage("§c활성 세션이 없습니다.");
            return;
        }

        int nextRoom = state.getRoomNumber() + 1;
        broadcast("§e§l═══ 다음 스테이지로 이동합니다 (스테이지 " + nextRoom + ") ═══");
        broadcast("§7새 시나리오를 생성 중입니다...");

        currentPhase = Phase.ROLE_ASSIGNMENT;

        // 역할 데이터 초기화: roleSpecific 특성·역할·zone 제거, 기본 스탯으로 복구
        state.getAllPlayers().forEach(pd -> {
            pd.clearRoleData();
            pd.statsConfirmed = true;
        });
        itemMan.reclaimChapterItems(new ArrayList<>(Bukkit.getOnlinePlayers()));

        turnMan.cancelAll();
        narrativeDelivery.clearAll();
        pendingCreation.clear();
        pendingTraitSelect.clear();
        spawnedPlayers.clear();
        commChannels.clear();
        preAssignedRoleData.clear();
        preAssignments.clear();
        gmNpcRoleIds.clear();
        ai.clearAll();

        gdamGen.generate(nextRoom).thenAccept(gdam -> {
            if (gdam.has("error")) {
                broadcast("§c[오류] 시나리오 생성 실패: " + gdam.get("error").getAsString());
                currentPhase = Phase.IDLE;
                return;
            }

            String seed = gdam.get("seed").getAsString();
            state.advanceToNextRoom(nextRoom, seed, gdam);
            gmSystemPrompt = buildGmPrompt(gdam);

            broadcast("§a새 시나리오 생성 완료. 씨드: §e" + seed);

            List<Player> participants = state.getAllPlayers().stream()
                .map(pd -> Bukkit.getPlayer(pd.uuid))
                .filter(Objects::nonNull)
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                .collect(Collectors.toList());

            if (participants.isEmpty()) {
                broadcast("§c참여 중인 플레이어가 없습니다.");
                currentPhase = Phase.IDLE;
                return;
            }

            doPreAssign(participants, gdam);

            // 스코어보드: 기본 스탯으로 갱신
            state.getAllPlayers().forEach(pd -> {
                Player p = Bukkit.getPlayer(pd.uuid);
                if (p != null) scoreMan.update(p, pd, nextRoom);
            });

            plugin.getServer().getScheduler().runTask(plugin, this::assignRolesAndStart);
        });
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
            case "_trait"      -> {
                try { handleTraitSelect(player, args.length > 1 ? Integer.parseInt(args[1]) : 0); }
                catch (NumberFormatException e) { player.sendMessage("§c번호를 입력해주세요."); }
            }
            case "_trait_remove" -> {
                try { handleTraitRemove(player, args.length > 1 ? Integer.parseInt(args[1]) : 0); }
                catch (NumberFormatException e) { player.sendMessage("§c번호를 입력해주세요."); }
            }
            case "_use_trait"  -> handleTraitUse(player, args.length > 1 ? args[1] : "");
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
    }

    private void confirmStats(Player player) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.statsConfirmed) return;

        dialogMan.clearDialog(player);
        pd.statsConfirmed = true;
        player.sendMessage("§a스탯이 확정되었습니다!");
        scoreMan.update(player, pd, state.getRoomNumber());
        pendingCreation.remove(player.getUniqueId());
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

        JsonObject roleData = preAssignedRoleData.get(player.getUniqueId());
        charGen.generate(player, roleData).thenAccept(newPd -> {
            newPd.diceRollsRemaining = pd.diceRollsRemaining;
            state.addPlayer(newPd);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                showCharacterSheetForPlayer(player, newPd));
        });
    }

    private void showCharacterSheetForPlayer(Player player, PlayerData pd) {
        int room    = state.getRoomNumber();
        int attempt = state.getCorruption().attempts + 1;
        dialogMan.showCharacterSheet(player, pd, room, attempt,
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
                    pd.roleId = asgn.roleId();
                    pd.zone   = asgn.zone();
                    pd.roleAssigned = true;
                }
            }
        } else {
            assignments = roleMan.assignRoles(players);
        }

        // GM 프롬프트 재생성 (NPC 배역 포함)
        gmSystemPrompt = buildGmPrompt(state.getGdamData());

        // common_items: 시대 배경에 따라 모든 플레이어가 기본 소지 (현대=스마트폰 등)
        JsonObject gdamForItems = state.getGdamData();
        if (gdamForItems != null && gdamForItems.has("common_items")) {
            gdamForItems.getAsJsonArray("common_items").forEach(el -> {
                String itemId = el.getAsString().trim();
                if (!itemId.isEmpty()) state.getAllPlayers().forEach(pd -> pd.heldItemIds.add(itemId));
            });
        }

        // 연락처: 무작위 번호 부여 + 특성 기반 사전 지식 적용
        assignContactIds();
        applyTraitContacts();
        applyRelationshipContacts(assignments);

        List<CompletableFuture<Map.Entry<PlayerData, List<TraitData>>>> roleTraitFutures = new ArrayList<>();

        for (var entry : assignments.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;
            RoleManager.RoleAssignment asgn = entry.getValue();

            PlayerData myPd = state.getPlayer(p);
            JsonObject roleData = (myPd != null) ? getRoleDataById(asgn.roleId()) : null;

            // 배역 스탯 적용 — snapshotBase() 이후 호출이므로 clearRoleData()→resetToBase() 시 자동 제거됨
            if (myPd != null && roleData != null) {
                String roleSummary = applyRoleStats(myPd, roleData);
                if (!roleSummary.isBlank()) {
                    p.sendMessage("§e[배역 스탯] §f" + roleSummary);
                }
            }

            p.sendMessage("§e§l[배역 배정]");
            p.sendMessage(roleMan.getRoleBriefing(asgn.roleId(), corruptMan.getLevel()));
            giveRoleStartItems(p, asgn.roleId());

            if (myPd != null && !myPd.contactId.isEmpty()) {
                p.sendMessage("§7당신의 연락처: §f" + myPd.contactId
                    + " §8(상대와 연락하려면 서로의 연락처를 알아야 합니다)");
                announceKnownContacts(p, myPd);
            }

            if (isImmediateSpawn(asgn.roleId())) {
                spawnedPlayers.add(p.getUniqueId());
            } else {
                p.sendMessage("§8당신의 배역은 이야기가 진행되면서 등장합니다. GM의 안내를 기다려주세요.");
            }

            if (myPd != null && roleData != null) {
                p.sendMessage("§7배역 고유 특성 생성 중...");
                roleTraitFutures.add(
                    traitMan.generateRoleTraits(myPd, roleData)
                        .thenApply(traits -> Map.entry(myPd, traits))
                );
            }
        }

        currentPhase = Phase.DAILY;

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
                        StringBuilder msg = new StringBuilder("§e[배역 특성] 다음 특성이 부여되었습니다:");
                        traits.forEach(t -> msg.append("\n§7▸ (").append(t.grade).append(") §f").append(t.name));
                        rp.sendMessage(msg.toString());
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
                pd.zone = r.get("zone").getAsString();
            }

            if (r.has("start_item")) {
                for (var item : r.getAsJsonArray("start_item")) {
                    JsonObject grant = new JsonObject();
                    String itemId = item.getAsString();
                    grant.addProperty("item_id", itemId);
                    grant.addProperty("player", player.getName());
                    grant.addProperty("chapter_bound", true);
                    itemMan.processGrant(grant, List.of(player));
                    if (pd != null) pd.heldItemIds.add(itemId);
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
            for (var r : rel.getAsJsonArray("roles")) {
                UUID u = roleToUuid.get(r.getAsString());
                if (u != null) uuids.add(u);
            }
            // 서로 연락처 교환 (관계 서술은 GM이 프롤로그에서 자연스럽게 처리)
            for (int i = 0; i < uuids.size(); i++) {
                PlayerData a = state.getPlayer(uuids.get(i));
                if (a == null) continue;
                for (int j = 0; j < uuids.size(); j++) {
                    if (i == j) continue;
                    a.knownContacts.add(uuids.get(j));
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  일상 파트
    // ──────────────────────────────────────────────────────────────

    private void startDailyPhase() {
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
        });

        // 등장 배역: 각자의 위치/역할 기준 개인 프롤로그
        spawnedPlayers.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;
            PlayerData pd = state.getPlayer(uuid);
            if (pd == null) return;

            // initial_info를 GM 전달 컨텍스트에 포함 (장면 묘사에 자연스럽게 반영용)
            StringBuilder promptSb = new StringBuilder();
            promptSb.append("일상 파트 시작. 배역 '").append(pd.roleId)
                .append("' 플레이어(").append(pd.name).append(")에게만 전달된다. ");
            promptSb.append("시작 위치: ").append(pd.zone.isEmpty() ? "?" : pd.zone).append(". ");
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
            promptSb.append("2인칭 시점의 일상 장면을 서술해줘. 다른 플레이어의 존재 직접 언급 금지. 괴담 암시 금지.");
            String prompt = promptSb.toString();

            ai.callGmAiOnce(gmSystemPrompt, prompt)
                .thenAccept(response -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    String narrative = ai.stripTags(response);
                    if (!narrative.isBlank()) narrativeDelivery.deliver(p, narrative);
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
    }

    // ──────────────────────────────────────────────────────────────
    //  게임 중 채팅 처리 (일상/괴담 파트 공통)
    // ──────────────────────────────────────────────────────────────

    private void handleGameChat(Player player, String message) {
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

        if (!state.hasPlayer(player.getUniqueId())) return; // 참여자가 아님

        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.isDead) return;

        // 미등장 배역: 채팅 차단, 대기 안내
        if (!spawnedPlayers.contains(player.getUniqueId())) {
            player.sendMessage("§8(아직 당신의 배역이 이야기에 등장하지 않았습니다. GM의 안내를 기다리세요.)");
            return;
        }

        // 직접 통신 시도: @이름 메시지
        if (message.startsWith("@")) {
            handleDirectComm(player, pd, message);
            return;
        }

        // 꼭두각시 상태: 행동 앞에 상태 표기 → GM이 서술 조정
        String actionMessage = message;
        if ("puppet".equals(pd.status)) {
            player.sendMessage("§8(당신의 의지가 아닌 무언가에 이끌려 행동합니다...)");
            actionMessage = "[꼭두각시] " + message;
        }

        // 괴담이 이 플레이어의 말투·행동을 학습 (정체 차용/흉내에 사용)
        corruptMan.learnPlayerBehavior(player.getName(), message);

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

            // 1. 클리어 판정
            if (currentPhase == Phase.HORROR) {
                JsonObject clearTag = ai.parseClearTag(raw);
                if (clearTag != null) {
                    String grade = clearTag.has("grade") ? clearTag.get("grade").getAsString() : "C";
                    deliverNarrative(player, raw); // 클리어 서술은 행동 플레이어에게
                    onClearEnding(grade);
                    return;
                }
            }

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
                        state.getAllPlayers().forEach(pd -> pd.heldItemIds.add(grantedItem));
                    } else {
                        final String itemRef = grantedItem;
                        state.getAllPlayers().stream()
                            .filter(pd -> pd.name.equals(grantedTo))
                            .findFirst()
                            .ifPresent(pd -> pd.heldItemIds.add(itemRef));
                    }
                }
            }

            // 4. 서술 + WITNESS 전달 (당사자에게만)
            deliverNarrative(player, raw);

            // 4a. 주사위 판정 애니메이션
            if (player != null && player.isOnline() && needsDiceAnimation(raw)) {
                playDiceAnimation(player);
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

            // 5d. 위치(zone) 업데이트
            ai.parseZoneUpdateTags(raw).forEach(zu -> updatePlayerZone(zu[0], zu[1]));

            // 5c. 괴담의 정체 차용 시작/종료
            ai.parseImpersonateTags(raw).forEach(this::startImpersonation);
            ai.parseImpersonateEndTags(raw).forEach(this::endImpersonation);

            // 6. 일상 파트 턴 소비
            if (state.isDailyPhase()) {
                boolean phaseChanged = state.consumeDailyTurn();
                if (phaseChanged) {
                    onHorrorPhaseStart();
                } else if (state.getDailyTurnsLeft() == 1) {
                    spawnedPlayers.forEach(uid -> {
                        Player sp = Bukkit.getPlayer(uid);
                        if (sp != null) sp.sendMessage("§8§o(분위기가 달라지고 있다...)");
                    });
                }
            }

            // 7. 스코어보드 갱신
            updateAllScoreboards();

            // 8. 타임라인 4단계 체크
            if (state.getTimelineStage() >= 4) { onBadEnding(); return; }

            // 9. 사망자 체크
            checkDeaths();

            // 10. 능동 특성 버튼 (행동 플레이어에게만)
            traitBtn.sendTraitButtons(player, state.getPlayer(player));

            // 11. Entity AI (괴담 파트, 2턴마다) — 스폰된 각 플레이어에게 개별 전달
            if (currentPhase == Phase.HORROR && state.getCurrentTurn() % 2 == 1) {
                String entityLog = state.buildEntityLog(5);
                String entityPrompt = buildEntitySystemPrompt();
                spawnedPlayers.forEach(uid -> {
                    Player sp = Bukkit.getPlayer(uid);
                    if (sp == null) return;
                    ai.callEntityAi(entityPrompt, entityLog).thenAccept(entityResp -> {
                        if (entityResp == null || entityResp.startsWith("§c")) return;
                        String trimmed = entityResp.trim();
                        if (trimmed.isEmpty()) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (sp.isOnline()) sp.sendMessage("§8§o[" + getEntityName() + "] " + trimmed);
                            if (corruptMan.getLevel() >= 2) corruptMan.addEntityMemory(trimmed);
                        });
                    });
                });
            }

            // 12. 미등장 배역에게 자동 배경 서술 전송
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

    private void applyStateUpdate(JsonObject update) {
        String playerName = update.has("player") ? update.get("player").getAsString() : null;
        if (playerName == null) return;

        state.getAllPlayers().stream()
            .filter(pd -> pd.name.equals(playerName))
            .findFirst()
            .ifPresent(pd -> {
                if (update.has("hp_change")) {
                    int delta = update.get("hp_change").getAsInt();
                    int before = pd.hp[0];
                    pd.hp[0] = Math.max(0, Math.min(pd.hp[1], pd.hp[0] + delta));
                    notifyVitalChange(pd, "체력", "§c", before, pd.hp[0], pd.hp[1]);
                    if (pd.hp[0] <= 0) pd.isDead = true;
                }
                if (update.has("san_change")) {
                    int delta = update.get("san_change").getAsInt();
                    int before = pd.san[0];
                    pd.san[0] = Math.max(0, Math.min(pd.san[1], pd.san[0] + delta));
                    notifyVitalChange(pd, "정신력", "§b", before, pd.san[0], pd.san[1]);
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
                if (update.has("item_remove") && !update.get("item_remove").isJsonNull()) {
                    pd.heldItemIds.remove(update.get("item_remove").getAsString());
                }
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
        broadcast("§c§l─── 괴담이 시작됩니다 ───");

        compressor.compressDailyPhase().thenRun(() ->
            spawnedPlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                PlayerData pd = state.getPlayer(uuid);
                String name = pd != null ? pd.name : "?";
                ai.callGmAiOnce(gmSystemPrompt,
                    "괴담 파트 시작. 플레이어(" + name + ")의 시점에서 타임라인이 시작됨을 "
                    + "환경 변화(소리·냄새·온도 등)로만 암시해줘. 직접 언급 금지.")
                  .thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                      if (p.isOnline()) {
                          String narrative = ai.stripTags(r);
                          if (!narrative.isBlank()) narrativeDelivery.deliver(p, narrative);
                      }
                  }));
            })
        );
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
              String narrative = ai.stripTags(r);
              spawnedPlayers.forEach(uid -> {
                  Player sp = Bukkit.getPlayer(uid);
                  if (sp != null && sp.isOnline() && !narrative.isBlank())
                      narrativeDelivery.deliver(sp, narrative);
              });
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
            if (!pd.contactId.isEmpty()) {
                player.sendMessage("§7당신의 연락처: §f" + pd.contactId);
                announceKnownContacts(player, pd);
            }
            traitBtn.sendTraitButtons(player, pd);
        } else {
            player.sendMessage("§c이 세션의 참가자가 아닙니다. 게임은 시작 전에 참여해야 합니다.");
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 버튼 / 선택 처리
    // ──────────────────────────────────────────────────────────────

    private void handleTraitSelect(Player player, int idx) {
        List<TraitData> choices = dialogMan.getTraitChoices(player);

        if (idx == 4) { // 기존 특성 제거 선택
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
        dialogMan.clearDialog(player);
        pendingTraitSelect.remove(player.getUniqueId());

        PlayerData pd = state.getPlayer(player);
        if (pd != null) {
            traitMan.addTrait(pd, selected);
            player.sendMessage("§a특성 '§f" + selected.name + "§a'을(를) 획득했습니다!");
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
            .filter(playerData -> !playerData.isDead)
            .forEach(playerData -> {
                traitMan.generateClearTraits(finalGrade, playerData, gdamTheme)
                    .thenAccept(choices -> {
                        if (choices.isEmpty()) return;
                        Player p = Bukkit.getPlayer(playerData.uuid);
                        if (p == null || !p.isOnline()) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            p.sendMessage("§6§l[클리어 보상] 특성을 선택하세요!");
                            boolean canRemove = playerData.traits.size() >= 3;
                            dialogMan.showTraitSelection(p, choices, canRemove,
                                idx -> handleTraitSelect(p, idx));
                            pendingTraitSelect.add(p.getUniqueId());
                        });
                    });
            });

        broadcast("§6특성을 선택한 뒤 §f/trpg stop §6으로 세션을 종료하세요.");
    }

    // ──────────────────────────────────────────────────────────────
    //  서술 개인 전달
    // ──────────────────────────────────────────────────────────────

    /** 행동 플레이어에게 GM 서술 전달 + WITNESS 태그로 주변 플레이어에게 간접 단서 전달 */
    private void deliverNarrative(Player actor, String raw) {
        String narrative = ai.stripTags(raw);
        if (!narrative.isBlank() && actor != null && actor.isOnline()) {
            narrativeDelivery.deliver(actor, narrative);
        }
        ai.parseWitnessTags(raw).forEach((pName, witnessText) -> {
            if (witnessText.isBlank()) return;
            state.getAllPlayers().stream()
                .filter(pd -> pd.name.equals(pName) && spawnedPlayers.contains(pd.uuid))
                .findFirst()
                .ifPresent(pd -> {
                    Player target = Bukkit.getPlayer(pd.uuid);
                    if (target != null && target.isOnline())
                        Arrays.stream(witnessText.split("\n"))
                            .forEach(line -> target.sendMessage("§7§o" + line));
                });
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  배역 등장 처리
    // ──────────────────────────────────────────────────────────────

    private void handleSpawn(String playerName) {
        state.getAllPlayers().stream()
            .filter(pd -> pd.name.equals(playerName) && !spawnedPlayers.contains(pd.uuid))
            .findFirst()
            .ifPresent(pd -> {
                spawnedPlayers.add(pd.uuid);
                Player p = Bukkit.getPlayer(pd.uuid);
                if (p == null || !p.isOnline()) return;
                p.sendMessage("§e§l[등장] 당신의 배역이 이야기에 들어섰습니다. 이제 행동할 수 있습니다.");
                traitBtn.sendTraitButtons(p, pd);
            });
    }

    // ──────────────────────────────────────────────────────────────
    //  미등장 배역 자동 서술
    // ──────────────────────────────────────────────────────────────

    private void sendPreSpawnNarrative(Player p, PlayerData pd) {
        String context = buildPreSpawnContext(pd);
        if (context.isEmpty()) return;
        String phase = state.isDailyPhase() ? "일상 파트" : "괴담 파트 " + state.getTimelineStage() + "단계";

        ai.callAssistant(
            "너는 TRPG GM이야. 아직 스토리에 등장하지 않은 배역의 현재 상황을 2인칭 1-3문장으로 서술해.\n"
            + "스탯/특성 적용 없이 배역 서사에 집중. hidden_info는 이 배역이 이미 아는 정보로 포함.\n"
            + "본 스토리는 간접적으로만 암시 (직접 언급 금지).\n" + context,
            "현재 게임 단계: " + phase
        ).thenAccept(resp -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && !resp.startsWith("§c"))
                p.sendMessage("§8§o" + resp.trim());
        }));
    }

    private String buildPreSpawnContext(PlayerData pd) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles")) return "";
        for (JsonElement el : gdam.getAsJsonArray("roles")) {
            JsonObject r = el.getAsJsonObject();
            if (!r.has("role_id") || !r.get("role_id").getAsString().equals(pd.roleId)) continue;
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
        return "";
    }

    private boolean isImmediateSpawn(String roleId) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles")) return true;
        for (JsonElement el : gdam.getAsJsonArray("roles")) {
            JsonObject r = el.getAsJsonObject();
            if (!r.has("role_id") || !r.get("role_id").getAsString().equals(roleId)) continue;
            if (!r.has("spawn_timeline")) return true;
            String st = r.get("spawn_timeline").getAsString().trim();
            return st.isEmpty() || st.equals("시작 즉시");
        }
        return true;
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
    //  플레이어 간 직접 통신
    // ──────────────────────────────────────────────────────────────

    private void handleDirectComm(Player sender, PlayerData senderPd, String raw) {
        String content = raw.substring(1).trim(); // '@' 제거
        int space = content.indexOf(' ');
        if (space == -1) {
            sender.sendMessage("§c사용법: @이름(또는 번호) 메시지");
            return;
        }
        String token   = content.substring(0, space);
        String message = content.substring(space + 1).trim();
        if (message.isEmpty()) {
            sender.sendMessage("§c사용법: @이름(또는 번호) 메시지");
            return;
        }

        // 대상 식별: 숫자면 연락처 번호로 다이얼, 아니면 이름
        boolean dialedByNumber = token.matches("\\d{3,5}");
        PlayerData targetPd = dialedByNumber ? findByContactId(token) : findByName(token);

        if (targetPd == null) {
            sender.sendMessage(dialedByNumber
                ? "§c'" + token + "' 번호로 연결되지 않습니다. (없는 번호)"
                : "§c'" + token + "' 플레이어를 찾을 수 없습니다.");
            return;
        }
        if (targetPd.uuid.equals(sender.getUniqueId())) {
            sender.sendMessage("§c자기 자신에게 통신할 수 없습니다.");
            return;
        }

        // 도달 가능성 판정 (viaDevice = 기기 통신 여부)
        boolean viaDevice;
        if (!senderPd.zone.isEmpty() && senderPd.zone.equals(targetPd.zone)) {
            viaDevice = false; // 같은 구역 → 대면 (번호 불필요)
        } else {
            Set<UUID> channels = commChannels.get(sender.getUniqueId());
            boolean gmChannel = channels != null && channels.contains(targetPd.uuid);
            if (gmChannel) {
                viaDevice = true; // GM 개설 채널 → 번호 불필요
            } else {
                // 기기 통신: 양쪽 모두 통신 기기 보유 필요
                if (!hasCommDevice(senderPd) || !hasCommDevice(targetPd)) {
                    sender.sendMessage("§c근처에 없고 통신 기기로도 닿지 않습니다. (직접 찾아가거나 다른 방법이 필요)");
                    return;
                }
                // 연락처 지식: 번호를 직접 입력했거나, 한쪽이라도 상대 연락처를 알면 가능
                boolean contactKnown = dialedByNumber
                    || senderPd.knownContacts.contains(targetPd.uuid)
                    || targetPd.knownContacts.contains(senderPd.uuid);
                if (!contactKnown) {
                    sender.sendMessage("§c" + targetPd.name + "의 연락처를 모릅니다. 직접 만나거나 번호를 알아내야 합니다.");
                    return;
                }
                viaDevice = true;
            }
        }

        // 괴담이 정체를 차용한 배역이면 → 괴담이 그 사람인 척 기만 응답
        if (targetPd.impersonated) {
            deliverImpersonatedReply(sender, senderPd, targetPd, message, viaDevice);
            return;
        }

        deliverDirectMessage(sender, senderPd, targetPd, message, viaDevice);
        exchangeContacts(senderPd, targetPd);
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
            .filter(pd -> pd.name.equalsIgnoreCase(name) && (!pd.isDead || pd.impersonated))
            .findFirst().orElse(null);
    }

    private PlayerData findAnyByName(String name) {
        return state.getAllPlayers().stream()
            .filter(pd -> pd.name.equalsIgnoreCase(name))
            .findFirst().orElse(null);
    }

    /** 통신 성립 시 양쪽이 서로의 연락처를 알게 됨 (착신/대면 교환) */
    private void exchangeContacts(PlayerData a, PlayerData b) {
        if (a.knownContacts.add(b.uuid)) notifyContactLearned(a, b);
        if (b.knownContacts.add(a.uuid)) notifyContactLearned(b, a);
    }

    private void notifyContactLearned(PlayerData learner, PlayerData subject) {
        Player p = Bukkit.getPlayer(learner.uuid);
        if (p != null && p.isOnline())
            p.sendMessage("§a[연락처 입수] §f" + subject.name + " (" + subject.contactId + ")");
    }

    private void announceKnownContacts(Player p, PlayerData pd) {
        if (pd.knownContacts.isEmpty()) return;
        StringBuilder sb = new StringBuilder("§7알고 있는 연락처: §f");
        boolean first = true;
        for (UUID u : pd.knownContacts) {
            PlayerData other = state.getPlayer(u);
            if (other == null) continue;
            if (!first) sb.append("§7, §f");
            sb.append(other.name).append("(").append(other.contactId).append(")");
            first = false;
        }
        if (!first) p.sendMessage(sb.toString());
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
                for (PlayerData other : all) if (other != pd) other.knownContacts.add(pd.uuid);
            }
            if (hasTraitKeyword(pd, HACKER_TRAIT_KEYWORDS)) {
                // 정보 수집가 → 이 사람은 모두의 연락처를 안다
                for (PlayerData other : all) if (other != pd) pd.knownContacts.add(other.uuid);
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
        if (to.knownContacts.add(target.uuid)) notifyContactLearned(to, target);
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

    /** 괴담이 플레이어를 제거하고 정체를 차지 — 본인에게만 통보, 타인에게는 비공개 */
    private void startImpersonation(String name) {
        if (!entityCanImpersonate()) return;
        PlayerData pd = findAnyByName(name);
        if (pd == null || pd.impersonated) return;
        pd.impersonated = true;
        pd.isDead       = true;     // 죽이고 대신 움직인다
        pd.status       = "dead";
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
        sender.sendMessage(tag + " §f" + senderPd.name + " → " + victim.name + ": " + message);
        sender.sendMessage("§7[" + victim.name + "의 응답을 기다리는 중...]");
        state.log("comm", senderPd.name, "→ " + victim.name + "(?): " + message);

        String sys   = buildImpersonationPrompt(victim);
        String input = senderPd.name + "이(가) '" + victim.name + "'에게 말한다: \"" + message + "\"\n"
            + "'" + victim.name + "'인 척 자연스럽게 1-2문장으로 응답하라. 특성·능력 사용 금지. "
            + "미세한 위화감만 남기고 정체는 직접 밝히지 마라.";

        ai.callEntityAi(sys, input).thenAccept(resp ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (resp == null || resp.startsWith("§c")) return;
                String txt = resp.trim();
                if (txt.isEmpty() || !sender.isOnline()) return;
                sender.sendMessage(tag + " §f" + victim.name + ": " + txt);
            }));
    }

    /** 정체 차용 시스템 프롬프트 — 괴담 기본 + 학습한 그 플레이어의 말투·행동 */
    private String buildImpersonationPrompt(PlayerData victim) {
        StringBuilder sb = new StringBuilder(buildEntitySystemPrompt());
        sb.append("\n## 정체 차용 모드\n");
        sb.append("너는 '").append(victim.name).append("'(").append(victim.age).append("세, ")
          .append(victim.job).append(")의 정체를 차지했다. 그 사람인 척 대화하라.\n");
        List<String> profile = corruptMan.getPlayerProfile(victim.name);
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
                                      String message, boolean viaDevice) {
        String tag     = viaDevice ? "§a[통신]" : "§a[근처]";
        String outLine = tag + " §f" + senderPd.name + " → " + targetPd.name + ": " + message;
        String inLine  = tag + " §f" + senderPd.name + ": " + message;

        sender.sendMessage(outLine);
        Player target = Bukkit.getPlayer(targetPd.uuid);
        if (target != null && target.isOnline()) target.sendMessage(inLine);

        state.log("comm", senderPd.name,
            "→ " + targetPd.name + " (" + (viaDevice ? "장치" : "근거리") + "): " + message);
    }

    /** GM이 플레이어 위치를 zone으로 업데이트. 같은 zone 진입 시 연락처 자동 교환 */
    private void updatePlayerZone(String playerName, String newZone) {
        PlayerData moved = findAnyByName(playerName);
        if (moved == null || newZone == null || newZone.isBlank()) return;
        moved.zone = newZone;
        // 같은 zone에 이미 있는 생존 플레이어들과 연락처 교환
        state.getAllPlayers().stream()
            .filter(other -> other != moved && !other.isDead
                          && newZone.equals(other.zone)
                          && spawnedPlayers.contains(other.uuid))
            .forEach(other -> exchangeContacts(moved, other));
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
        StringBuilder sb = new StringBuilder(GM_SYSTEM_BASE);
        sb.append("\n## .gdam 사전 확정 데이터\n");
        sb.append("씨드: ").append(gdam.has("seed") ? gdam.get("seed").getAsString() : "?").append("\n");
        if (gdam.has("entity")) {
            sb.append("괴담 존재: ").append(gdam.getAsJsonObject("entity").get("name").getAsString()).append("\n");
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
        // 오염 컨텍스트 추가
        sb.append(corruptMan.buildCorruptionContext(gdam));
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  선제 배역 배정
    // ──────────────────────────────────────────────────────────────

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

        for (int i = 0; i < shuffled.size() && i < ordered.size(); i++) {
            UUID uuid = shuffled.get(i).getUniqueId();
            JsonObject role = ordered.get(i);
            preAssignedRoleData.put(uuid, role);
            preAssignments.put(uuid, roleDataToAssignment(role));
        }
        // 남은 배역 → GM이 직접 조종
        for (int i = shuffled.size(); i < ordered.size(); i++) {
            JsonObject role = ordered.get(i);
            if (role.has("role_id")) gmNpcRoleIds.add(role.get("role_id").getAsString());
        }
        if (!gmNpcRoleIds.isEmpty()) {
            plugin.getLogger().info("[TRPG] GM NPC 배역: " + gmNpcRoleIds);
        }
    }

    private RoleManager.RoleAssignment roleDataToAssignment(JsonObject r) {
        String roleId   = r.has("role_id") ? r.get("role_id").getAsString() : "role_?";
        String roleName = r.has("name")    ? r.get("name").getAsString()    : "알 수 없는 배역";
        String zone     = r.has("zone")    ? r.get("zone").getAsString()    : "zone_A";
        boolean adv     = r.has("knowledge_advantage") && r.get("knowledge_advantage").getAsBoolean();
        List<String> info = new ArrayList<>();
        if (r.has("initial_info")) {
            r.getAsJsonArray("initial_info").forEach(i -> info.add(i.getAsString()));
        }
        return new RoleManager.RoleAssignment(roleId, roleName, zone, info, adv);
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

        currentPhase = Phase.CHAR_CREATION;
        state.startSession(room, seed, gdam);
        gmSystemPrompt = buildGmPrompt(gdam);
        ai.clearAll();

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
            JsonObject roleData = preAssignedRoleData.get(p.getUniqueId());
            charGen.generate(p, roleData)
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
}
