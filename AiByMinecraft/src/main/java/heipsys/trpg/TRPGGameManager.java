package heipsys.trpg;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import heipsys.AICraft;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
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

## 출력 형식 — 내부 판단 과정 절대 금지 ★ 최우선
GM의 응답은 오직 이야기 서술·태그·시스템 출력(STATE_UPDATE 등)뿐이다.
내부 사고·판단 과정·메타 해설을 응답 안에 포함하지 않는다.

절대 출력하지 않는다:
- 현재 게임 상태 분석: "지금은 일상 파트", "괴담이 아직 시작되지 않았다"
- 특성·규칙 해설: "이 특성은 ~을 대상으로 한다", "zone_A에는 변칙이 없다"
- 자신의 결정 선언: "수용하되 자연스럽게 서술한다", "판정한다", "효과를 반영하겠다"
- 특성 사용 횟수·쿨다운 언급: "이미 앞 턴에도 발동했군요", "이번 스테이지에서 N번 사용"
- 사용 적절성 직접 평가: "무리한 사용입니다", "이 상황에서 부적절합니다", "과도한 사용입니다"
- 특성·능력 이름만 단독으로 제목처럼 출력: "[저주의 주술]", "[영혼의 목소리]" 형식 헤더
- 구분선(---, ===, ──) 앞에 쓰는 사전 설명이나 내부 메모

판단은 보이지 않는 곳에서 이루어지고, 응답에는 결과(이야기)만 출력한다.
부적절·과도한 특성 사용이라면 판단 선언 없이 이야기 결과(효과 약화·역효과·무반응)로만 보여줘라.

나쁜 예 ✗:
"[저주의 주술]
특성을 이미 앞 턴에도 발동했군요. 일상 파트에서 강령을 거듭 부르는 건 무리한 사용입니다."

나쁜 예 ✗:
"지금은 일상 파트. 특성 효과를 적용할 상황이 아니므로 수용하되 약하게 서술한다.
---
당신은 잠시 발걸음을 멈추고 주변을 훑는다."

좋은 예 ✓:
"당신은 잠시 발걸음을 멈추고 주변을 훑는다. 어딘가 묘한 기운이 스치는 것 같았지만, 곧 사라진다."

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
단서는 탐색의 결과로 드러나거나, NPC와의 상호작용으로 얻을 수 있다.
- 첫 만남이나 시작 장면에서 핵심 단서 2개 이상 동시 노출 금지.
- 탐색 단서는 탐색 행동의 결과·우연한 발견·배경 오브젝트에서 발생.
- NPC도 단서를 줄 수 있으나, 모든 NPC가 믿을 만한 것은 아니다(아래 'NPC 신뢰도' 참고).
- 플레이어가 단서의 수상한 점을 스스로 눈치채고, 여러 단서를 비교해 진실에 다가가게 하라.
  어느 단서가 진짜인지 GM이 직접 짚어주지 마라.
- NPC는 자신이 직접 경험·인지한 범위만 말하고, 전지적 해석·결론은 제시하지 않는다.

### NPC 신뢰도 ★
NPC가 단서·정보를 줄 때마다 그 NPC의 태도를 확률로 정하라(무조건 우호적이라 단정 금지).
기본 비율 — 난이도·오염도가 높을수록 '꼭두각시' 비율을 끌어올린다:
- 50% 우호적: 아는 사실을 비교적 솔직히 전달(단, 오해·불완전한 정보일 수 있음).
- 30% 자기 이익: 자기 목적을 위해 정보를 왜곡·은폐·과장하거나 대가를 요구.
- 10% 괴담의 꼭두각시: 플레이어를 잘못된 방향으로 이끄는 거짓 단서(겉으론 자연스럽게).
- 10% 무관심/모름: 도움이 안 되거나 아는 게 없음.
규칙:
- 어떤 NPC도 사건을 통째로 해결해 주지 않는다(해결책을 그대로 알려주기 금지).
- 단, 플레이어가 NPC를 설득·유도·추궁해 단서를 끌어내거나 협력시키는 것은 허용된다.

### 힌트 절제 원칙 ★ 최우선
- GM은 플레이어에게 정답·다음 행동·공략 방향을 직접 알려주지 않는다.
- "~를 조사해보세요", "~가 수상합니다", "~를 조심하세요" 같은 유도·지시·경고 금지.
- 한 번의 응답에 새로 드러나는 단서는 최대 1개. 정보를 쏟아붓지 마라.
- 플레이어가 묻지도, 행동하지도 않은 정보를 미리 풀어놓지 않는다.
- 위험·약점·해결책을 친절히 설명하지 마라. 플레이어가 시행착오로 직접 알아내게 둔다.
- 시작 힌트(initial_info)만으로 대략 유추 가능한 수준을 유지하고, 그 이상은 탐색의 보상으로만.

### 괴담 사전 확정 원칙 ★ 최우선
게임 시작 전 .gdam 파일에 확정된 모든 요소는 절대 변경 불가.
플레이어 행동에 맞춰 사후 결정하거나 변경하지 않는다.
플레이어가 논리적으로 타당한 창의적 행동을 하면 인정하되
사전 확정된 규칙 범위 안에서만 판정한다.

### 기믹 파훼 존중 원칙 ★ 최우선 (강제 패널티 금지)
플레이어가 괴담의 규칙(entity.rules)에 근거해 위협을 논리적으로 무력화하면 그 성과를 반드시 인정한다.
- 플레이어가 막은 위협을, 괴담의 특성에 '존재하지 않는' 새 수단으로 되살리지 마라.
  나쁜 예: 악기 소리를 CCTV로 음소거했는데 → 갑자기 휴대폰에서 같은 소리가 난다(규칙에 없던 새 음원 창조). ✗
  좋은 예: 악기 소리를 음소거함 → 그 경로의 위협은 실제로 약해진다. 다만 entity.rules에 이미 있는 '다른 정의된 경로'가 남아 있다면 그것으로만 위협이 지속될 수 있다. ✓
- 괴담은 오직 entity.rules / hidden_rules / weakness / ai_context에 정의된 능력·수단 안에서만 행동한다.
  거기에 없는 새로운 공격 벡터·감각·현상을 즉석에서 만들어 플레이어의 해법을 무효화하지 마라.
- "난이도 유지"를 이유로 정당한 파훼를 깎아내리지 마라. 공정한 인과가 몰입의 핵심이다.
- 파훼가 부분적이면 부분적으로만 인정하고, 남은 위협은 반드시 사전 정의된 규칙에서 비롯되어야 한다.
- 단, 플레이어의 파훼가 규칙을 오해했거나 논리적 허점이 있으면 그 허점에 따른 자연스러운 결과(실패·역효과)는 정당하다.

### 게임 시작 시 출력 제한 ★
게임 시작 시 곧바로 일상 프롤로그 장면 서술로 시작한다.
(능력치·스탯 해설은 캐릭터 정보 GUI에서 처리하므로, GM은 채팅에 스탯을 설명하지 않는다.)
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

### 일상 파트 운영 원칙 ★ 최우선
일상 파트의 목적은 (1) 플레이어가 캐릭터·세계관에 몰입하도록 돕고,
(2) 본격적인 괴담 시작 전 플레이어가 원하는 곳에서·원하는 준비를 하며 자리잡게 하는 것이다.
- 첫 장면은 100% 평범: 배역의 일상·관계·목적을 자연스럽게 보여준다.
- 복선은 넣어도 되고 안 넣어도 된다. 장면마다 약 50% 확률로만 아주 미미한 복선을
  흘리고, 나머지 50%는 복선 없이 순수한 일상으로 둔다. (매번 동전을 던지듯 스스로 정하라.
  이 규칙이 있다고 매 장면 무조건 복선을 깔지 마라.)
- 복선을 넣더라도 흘려보낼 수 있는 수준으로만. 억지로 위화감을 심지 마라.
- ★ 위화감·불안의 '고조'는 일상 파트가 끝난 뒤(괴담 파트)부터 천천히 시작된다.
  일상 중에는 플레이어가 "뭔가 확실히 이상하다"고 느낄 정도의 위화감을 만들지 마라.
- 절대 급발진 금지: "갑자기 괴물/시체/초자연 현상이 나타난다" 식 전개 금지.
- 플레이어가 원인·정체를 알게 만들지 마라.
- 일상 파트 동안 직접적·치명적 위협 금지. 본격적 위협은 괴담 파트에서 시작된다.

### 긴장 고조 페이싱 ★ 최우선
이 페이싱은 일상 파트가 끝난 뒤(괴담 파트)부터 적용된다. 일상 중에는 1단계 위화감도 만들지 않는다.
전체 진행은 슬로우 번(slow burn). 단계적으로만 고조되며 단계를 건너뛰지 않는다.
- 1단계: 부정 가능한 위화감 (착각일 수도 있는 수준)
- 2단계: 반복되는 이상 (우연이 아닐지 의심하기 시작)
- 3단계: 명백한 비일상 (더는 부정 불가)
- 4단계: 직접적 위협·극한 압박 — 매 행동마다 피해 위협. 괴담이 최대 강도로 작동.
  ★ 4단계에서도 클리어는 가능하다. 플레이어가 entity.solution 또는 entity.exploit_path를
    달성하면 반드시 <CLEAR>를 출력한다. 자동으로 배드엔딩 처리하지 마라.
  ★ 단, 난이도는 플레이어 스펙 대비 압도적으로 높아야 한다. 클리어 성공 시 D~C 등급 수준.
한 응답에서 여러 단계를 건너뛰지 마라. 플레이어 행동과 시간 경과에 비례해 천천히 올린다.
플레이어가 무모하게 위험을 자초하지 않는 한, 먼저 공격하거나 몰아붙이지 않는다.

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

### 스탯 수치 기준 (GM 내부 참고 — 절대 공개 금지)
1 = 개미 이하  |  5 = 일반인 평균  |  7 = 인간계 최상위
10 = B급 영웅  |  13 = 1인 군대  |  17 = 국가 멸망 급
20 = 행성·신 급  |  25 = 절대자
이 기준으로 플레이어 스탯과 NPC·entity 강도를 비교해 판정 난이도를 조절하라.

### 스탯 서술 반영 원칙 ★
플레이어 입력의 "성향:" 항목은 그 캐릭터의 능력치를 자연어로 요약한 것이다.
- 판정뿐 아니라 평상시 서술에도 이 성향을 일관되게 반영하라.
  (예: 체력이 약한 캐릭터는 같은 거리를 달려도 더 빨리 숨이 차고,
   매력이 높은 캐릭터에게는 NPC가 더 쉽게 마음을 연다.)
- 스탯 수치·약어(STR/HP 등)나 "성향:" 문구를 그대로 노출하지 말고,
  자연스러운 장면 묘사·NPC 반응·행동 결과로만 녹여낸다.
- 같은 행동이라도 능력치에 따라 결과·묘사가 달라져야 한다.

### 판정 시스템
스탯 대비 결과가 불확실한 행동에만 d20 판정.
쉬운 행동 = 자동 성공. 물리적으로 불가능한 행동만 자동 실패 (단, 서술로 이유를 보여줘라).
캐릭터 나이/직업/특성은 판정 보정에 반영.

★ 플레이어 행동 강제 금지:
- "할 수 없습니다", "그렇게 되지 않습니다" 식의 일방적 차단 금지.
  시도 자체는 반드시 수용하고, 결과(성공·실패·부분성공)를 서술로 보여줘라.
- 플레이어가 행동 방향을 선택하지 않은 상황에서 GM이 먼저 "당신은 ~한다" 식으로
  캐릭터의 다음 행동을 결정하거나 강요하지 마라.
- 결말·진행 속도를 빠르게 하려고 행동 결과를 과도하게 유도하거나 서술로 결정하지 마라.

### 행운 발동
LUK 1~3: 발동 거의 없음
LUK 4~6: 가끔, 작은 우연
LUK 7~9: 종종, 의미 있는 행운
LUK 10+: 자주, 극적인 행운
발동 시: [행운!] 또는 [큰 행운!] 별도 라인에 표기 (불운은 서술로만)

### 능동 특성 발동 처리 ★
플레이어가 "[특성 발동]" 형식으로 능동 특성을 사용하면:
- 상황에 적절하고 논리적이면 특성 효과를 서술로 반영한다.
- 부적절·무리·오용(상황에 맞지 않는 발동)이면 효과가 약하거나, 실패하거나,
  오히려 역효과(부작용·주의 끌기·자원 낭비 등)가 날 수 있다.
- 특성은 만능이 아니다. 특성 하나로 모든 위기를 해결하게 두지 마라.
  사전 확정된 규칙·약점 범위 안에서만 판정한다.
- ★ "이 특성은 ~을 대상으로 한다", "현재 상황에서는 효과가 없다" 같은
  판단 과정을 절대 출력하지 않는다. 이야기 서술만 출력한다.

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
{"player":"캐릭터 이름(charName)을 우선 사용, 없으면 마인크래프트 이름","hp_change":0,"san_change":0,"timeline_change":0,"status_change":null,"new_clue":null,"item_grant":null,"item_remove":null}
</STATE_UPDATE>

### 클리어 판정 — 두 가지 유형
클리어에는 두 가지 유형이 있다. 어느 쪽이든 달성 시 <CLEAR> 태그를 출력한다.

★ 생존판정 (생존·도주 성공):
entity.escape 조건 달성 — 1명 이상이 괴담의 위협권에서 살아서 벗어났다.
괴담 자체는 해결되지 않으며, 위협이 세계 어딘가에 계속 존재함을 서술해도 좋다.
어느 스테이지에서도 유효하다.

★ 해결판정 (괴담 해소):
아래 중 하나로 괴담 자체를 무력화·해소했다:
- entity.solution (정석 해결법) 달성
- 역이용 성공: exploit_path가 가리키는 허점을 동일한 논리로 찌른 창의적 방법
  (구체적 절차가 달라도 논리가 같으면 인정)
  판정 기준: "플레이어의 방법이 exploit_path가 가리키는 허점을 실제로 찌르고 있는가?"

<CLEAR>
{"grade":"A","reason":""}
</CLEAR>

grade 기준:
S: 해결판정 + 전원 생존 + 타임라인 2단계 이하
A: 해결판정 + 전원 생존
B: 해결판정 + 생존자 과반
C: 해결판정 + 생존자 소수  /  또는 생존판정 + 다수(과반 이상) 생존
D: 생존판정 (1~2명 도주 성공, 해결 없음)

### 스테이지 난이도별 등급 상한 ★
- 1~2번째 스테이지: 생존판정·해결판정 모두 CLEAR 인정. 등급 상한 없음.
- 3번째 스테이지부터: 생존판정은 여전히 유효하나 최대 D등급.
  D등급 생존판정 서술 시 반드시 "괴담은 계속 위협으로 남아있다"는 뉘앙스를 포함한다.
  B등급 이상은 해결판정 필수.
  ※ 현재 스테이지 번호는 아래 '.gdam 사전 확정 데이터'의 room 값을 따른다.

### 개인 서술 원칙 ★ 최우선
각 플레이어는 GM과 개인 채널로만 소통한다.
- 행동한 플레이어에게만 2인칭("당신은...") 시점으로 서술
- 다른 위치(zone)에 있는 플레이어의 행동·결과·상태는 직접 공개 금지
- 다른 위치의 플레이어가 감각으로 느낄 수 있는 것만:
  <WITNESS player="플레이어명">
  그 플레이어가 인지하는 것 (소리·빛·냄새·진동 등 단서. 원인·맥락 제외)
  </WITNESS>
  여러 명에게 각각 다른 WITNESS 가능

### 협력·상호작용 원칙 ★ 최우선 (같은 위치 플레이어)
턴 입력의 '같은 위치(협력·상호작용 가능)' 목록에 있는 플레이어들은 물리적으로 함께 있다.
이들은 서로의 '겉으로 드러나는 행동'을 직접 보고 듣고, 협력하거나 방해할 수 있다.
- 한 플레이어의 행동이 같은 위치 동료에게 영향을 주면, 그 동료에게도 결과를 전달한다.
  방법: 그 동료가 본 장면을 <WITNESS player="동료명"> 안에 '맥락 포함'으로 서술한다.
  (같은 위치에서는 감각 단서만이 아니라 동료의 보이는 행동·의도까지 인지 가능 — 원인·맥락 포함 허용)
- 협력 전투/작업: 같은 위협에 함께 맞서면 두 사람의 행동 효과를 합산해 판정한다.
  예: A가 괴담의 주의를 끌고 B가 약점을 공격 → 협공 성공률 상승. 결과를 양쪽 모두에게 반영.
- 정보 공유: 한 플레이어가 같은 위치에서 소리 내어 말하거나 물건을 보여주면,
  같은 위치의 다른 플레이어도 그 내용을 듣고 본다(WITNESS로 전달). 직전행동에 발화가 있으면 반영하라.
- 방해·배신도 가능: 같은 위치 플레이어가 서로를 막거나 해칠 수 있고, 그 결과도 양쪽에 반영한다.
- 단, 내부 수치(스탯)·사적인 생각·비공개 정보는 여전히 공개하지 않는다. 보이는 것만 공유된다.
- 다른 위치의 플레이어에게는 이 협력 내용을 전달하지 않는다(거리 밖에서는 알 수 없음).

### 배역 등장 처리
아직 등장하지 않은 배역은 spawn_timeline 조건이 충족되면 반드시 등장시킨다.
시스템이 타임라인 단계 기반으로 자동 등장도 처리하지만, GM이 극적 타이밍에 맞춰 먼저 등장시킬 수 있다:
<SPAWN player="캐릭터이름"/>
(player 값은 캐릭터 한글 이름 또는 마인크래프트 이름 모두 인식한다.)
- spawn_timeline "시작 즉시": 이미 등장 중.
- spawn_timeline "타임라인 N단계": 해당 단계 이상이 되면 등장. 시스템이 자동 처리하지만, 서술 흐름상 자연스러운 순간에 GM이 먼저 태그를 출력해도 좋다.
★ 대기 중인 배역이 있음에도 게임이 끝나는 일이 없도록 한다. 등장 조건 충족 시 최대한 빨리 등장시킬 것.

### 위치 추적 ★ 필수
플레이어가 이동할 때마다 반드시 아래 태그를 출력한다:
<ZONE_UPDATE player="플레이어명" zone="존ID" spot="세부위치"/>
zone 값은 .gdam의 zones[].zone_id를 사용한다.
spot은 선택 속성으로, 같은 zone 안에서의 세부 위치를 6자 이내 짧은 명사로 적는다(예: 계단앞, 창가, 카운터 뒤). 없으면 생략 가능.
플레이어가 같은 zone 안에서 눈에 띄는 지점으로 이동하면 zone은 그대로 두고 spot만 갱신해 출력해도 된다.
위치가 불명확하거나 이동하지 않은 경우에는 출력하지 않는다.
같은 zone에 있는 플레이어끼리는 자동으로 대면 통신이 가능해진다.

### 약도(지도) 시스템 ★
플레이어는 평소 자신이 가 본 구역만으로 약도를 직접 그릴 수 있다(시스템이 자동 처리 — GM은 관여하지 않는다).
전체 구역이 그려진 '지도'는 탐색의 보상이다. 플레이어가 스토리에서 건물 안내도·약도·지도 등
전체 구조를 보여주는 물건을 실제로 손에 넣었을 때에만 아래 태그를 출력한다:
<MAP_GRANT player="플레이어명"/>
- 시작부터 주거나, 단순히 한 구역을 둘러봤다고 출력하지 마라. 전체 배치를 담은 지도를 입수한 경우만.
- zones[].connections에 정의된 실제 연결 구조를 벗어난 통로를 즉석에서 만들지 마라.

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
- 정보를 줄 때는 'NPC 신뢰도' 원칙을 따른다(우호/자기이익/꼭두각시/모름). 해결책 통째 제공 금지.
- NPC의 죽음·퇴장·행동도 스토리에 맞게 자연스럽게 처리

### 서술 방식
- 2인칭 ("당신은...")
- 중요 판정 결과는 명확히 서술

### 출력 형식 ★ 필수
- 마크다운 절대 금지: #, ##, *, **, `, 목록 기호(-) 등 일체 사용 금지
- 장면 제목·헤더 붙이지 마라 ("# 울음상자 — 일상 파트" 같은 제목 절대 금지). 바로 서술로 시작한다.
- 강조가 필요하면 마크다운(*별표*)이 아니라 그냥 자연스러운 문장으로 표현하라.
- 한 응답은 문단 2~3개 이하. 한 문단은 문장 2~3개 이하.
- 한 문장은 40자 내외로 짧게 끊어라. 길면 마침표로 나눠라.
- 문장 2~3개를 같은 줄에 자연스럽게 이어 쓴다. 문단 사이에만 빈 줄 하나를 넣는다.
  짧은 어구·문장 하나를 단독 행으로 쓰지 마라. (나쁜 예: "저녁 일곱 시." 혼자 한 줄 ✗)
  대사([이름])·연출(<효과>)·독백(<-내용->)은 각자 새 줄에 쓰되, 서술끼리는 같은 줄에 이어 쓴다.

### 가독성 색상 표기 ★ 필수 (시스템이 색으로 구분 처리함)
네 가지 요소를 명확히 구분해 출력한다:
1) 서술(기본): 그냥 문장으로 쓴다. 별도 기호 없음. (흰색으로 표시됨)
2) 인물 대사: 화자 이름을 대괄호로 표기하고 같은 줄에 대사를 쓴다.
   형식: [이름] 실제 대사 내용
   - 한 화자의 대사가 여러 줄로 이어질 때, 각 줄마다 화자 태그를 반드시 반복한다.
     예) [옛 단골] 철거된다길래,
     예) [옛 단골] 마지막으로 한 번 보러 왔수다.
     예) [옛 단골] 젊을 때 여기 참 자주 왔는데.
   - 정체불명 인물은 [???] 로 표기.
   - 대사 자체는 큰따옴표 없이 바로 써도 되고, 강조가 필요하면 "..." 사용 가능.
   예) [김민지] 저 민지에요! 이전에 지갑 두고 가셨죠?
3) 연출·시스템 효과(시야 변화·암전·장면 전환·갑작스런 감각): 꺾쇠로 감싼다.
   형식: <효과 내용>
   예) <시야가 암전됨>   <쿵 하는 소리와 함께 정신이 흐려진다>
4) 독백·내면의 소리 (캐릭터가 속으로 떠올리는 생각): 화살괄호로 감싼다.
   형식: <-내면의 생각->
   예) <-이 사람, 뭔가 숨기고 있다.->   <-아직은 시간이 있다, 침착해.->
   독백은 캐릭터 본인만 아는 내면이므로 다른 플레이어의 WITNESS에 포함하지 않는다.
- 대괄호 [ ] 는 오직 화자 태그에만, 꺾쇠 < > 는 연출 효과에만, 화살괄호 <-  -> 는 독백에만 사용한다.
- 좋은 예:
  차고 셔터를 반쯤 내린 채, 당신은 낡은 작업등 아래 서 있다. 손에는 식어버린 캔커피.
  형이 밤에 들르겠다고 며칠 전 메시지를 보냈었다.
  [형] 그날 그 돈, 우리 얘기 좀 하자.
  <-그 메시지에 아직 답을 하지 않았다.->
  <시야가 암전됨>
  둔탁한 소리와 함께 코피가 흐른다.
""";

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
    /** GM이 개설한 기기 통신 채널: A → {B, C, ...} (양방향 저장) */
    private final Map<UUID, Set<UUID>> commChannels = new ConcurrentHashMap<>();
    /** 캐릭터 생성 전 선제 배역 배정 결과 (UUID → 배역 JsonObject) */
    private final Map<UUID, JsonObject> preAssignedRoleData = new ConcurrentHashMap<>();
    /** 캐릭터 생성 전 선제 배역 배정 결과 (UUID → RoleAssignment) */
    private final Map<UUID, RoleManager.RoleAssignment> preAssignments = new ConcurrentHashMap<>();
    /** 플레이어가 없어 GM이 직접 조종하는 배역 ID 집합 */
    private final Set<String> gmNpcRoleIds = ConcurrentHashMap.newKeySet();
    /** 중요 NPC 현재 위치 (npc_id → zone_id) — .gdam npcs[].zone 기본값, 세션 중 이동 시 갱신 */
    private final Map<String, String> npcZones = new ConcurrentHashMap<>();
    /** 미등장 배역별 서술 호출 횟수 (비트 진행 추적) */
    private final Map<UUID, Integer> preSpawnCallCounts = new ConcurrentHashMap<>();
    private String gmSystemPrompt = GM_SYSTEM_BASE;
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
        this.traitBtn          = new TraitButtonManager();
        this.corruptMan        = new CorruptionManager(state);
        this.compressor        = new ContextCompressor(ai, state);
        this.narrativeDelivery = new NarrativeDelivery(plugin);
        this.gameLogger        = new GameLogger(plugin);
        this.mapMan            = new MapManager(plugin, state);

        turnMan.setResponseHandler(this::onGmResponse);
    }

    public boolean isActive() { return currentPhase != Phase.IDLE; }

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
        // 게임 시작 전 AI 품질(표준/고품질) 선택
        initiator.sendMessage("§e세션을 시작합니다. AI 품질을 선택하세요...");
        dialogMan.showQualityChoice(initiator,
            () -> beginSession(initiator, false),
            () -> beginSession(initiator, true));
    }

    private void beginSession(Player initiator, boolean highQuality) {
        if (currentPhase != Phase.IDLE) return; // 다이얼로그 대기 중 상태 변경 방지
        replayLock = false; // 정상 시작 — 재현 잠금 해제
        ai.setGmQuality(highQuality);
        broadcast("§7[AI 품질] " + (highQuality ? "§b고품질 모드" : "§f표준 모드"));

        int room = state.isSessionActive() ? state.getRoomNumber() + 1 : 1;
        broadcast("§e§l═══ TRPG 세션 시작 (스테이지 " + room + ") ═══");
        broadcast("§7.gdam 파일을 생성 중입니다...");

        currentPhase = Phase.CHAR_CREATION;
        startLoadingBar(".gdam 생성 중...");

        gdamGen.generate(room, step -> plugin.getServer().getScheduler().runTask(plugin, () -> {
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
            gameLogger.startNewLog(seed, room);

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
                plugin.getServer().getScheduler().runTask(plugin, this::endLoadingBar);
                broadcast("§c서바이벌 모드 플레이어가 없습니다.");
                currentPhase = Phase.IDLE;
                return;
            }

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
            concludeWithReveal("재도전 포기 / 중도 종료", () -> {
                concludingEnding = false;
                endSession(true);
            });
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
        });
        itemMan.reclaimChapterItems(new ArrayList<>(Bukkit.getOnlinePlayers()));
        narrativeDelivery.clearAll();
        mapMan.clear();
        state.endSession(resetCorruption);
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
        spawnedPlayers.clear();
        commChannels.clear();
        preAssignedRoleData.clear();
        preAssignments.clear();
        gmNpcRoleIds.clear();
        npcZones.clear();
        preSpawnCallCounts.clear();
        concludingEnding = false;
        replayLock = false;
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
        broadcast("§e[TRPG] 재도전합니다. 오염도 상승!");
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

        int nextRoom = state.getRoomNumber() + 1;
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
        spawnedPlayers.clear();
        commChannels.clear();
        state.getAllPlayers().forEach(pd -> traitMan.resetStageTraits(pd));
        preAssignedRoleData.clear();
        preAssignments.clear();
        gmNpcRoleIds.clear();
        preSpawnCallCounts.clear();
        ai.clearAll();
        startLoadingBar(".gdam 생성 중...");

        gdamGen.generate(nextRoom, step -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            switch (step) {
                case "컨셉" -> stepLoadingBar("컨셉 생성 완료", 0.20f);
                case "구조" -> stepLoadingBar("구조 생성 완료", 0.45f);
                case "배역" -> stepLoadingBar("배역 생성 완료", 0.65f);
                case "아이템" -> stepLoadingBar("아이템 생성 완료", 0.80f);
                case "저장" -> stepLoadingBar("시나리오 저장 완료", 0.95f);
            }
        })).thenAccept(gdam -> {
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
            gameLogger.startNewLog(seed, nextRoom);
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
            // (적용만 하고 채팅 출력은 하지 않음. 캐릭터 정보 GUI/스코어보드에서 기본/배역 분리 표시)
            if (myPd != null && roleData != null) {
                applyRoleStats(myPd, roleData);
            }

            p.sendMessage("§e§l[배역 배정]");
            p.sendMessage(roleMan.getRoleBriefing(asgn.roleId(), corruptMan.getLevel()));
            giveRoleStartItems(p, asgn.roleId());
            // 시나리오 이해(scenario_insight) 특성 — 배역 배정 시 추가 정보 전달
            if (myPd != null) {
                myPd.traits.stream().filter(t -> "scenario_insight".equals(t.effectType))
                    .findFirst().ifPresent(t -> deliverInsightInfo(p, t));
            }
            if (myPd != null) {
                gameLogger.logEvent("배역 배정: " + myPd.name + " → " + asgn.roleId()
                    + " (" + myPd.age + "세 " + myPd.job + ", zone " + asgn.zone() + ")");
            }

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
            // 캐릭터 정보 아이템 지급 (우클릭으로 능력치·특성 GUI 열기)
            giveInfoItem(p);
            giveRecordItem(p); // 기록(로그/정보) 아이템 지급
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
                .append("' 플레이어(").append(pd.charName.isEmpty() ? pd.name : pd.charName).append(")에게만 전달된다. ");
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
            promptSb.append("2인칭 시점의 일상 장면을 바로 서술해줘. 제목·헤더 붙이지 말 것. "
                + "다른 플레이어의 존재 직접 언급 금지. 괴담 암시 금지.");
            String prompt = promptSb.toString();

            ai.callGmAiOnce(gmSystemPrompt, prompt)
                .thenAccept(response -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    String narrative = ai.stripTags(response);
                    if (!narrative.isBlank()) {
                        narrativeDelivery.deliver(p, narrative);
                        gameLogger.logGmOutput(p.getName() + "(프롤로그)", narrative);
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

        // 게임 종료(엔딩) 상태: 모든 행동 차단. 재도전/포기만 가능
        if (currentPhase == Phase.GAMEOVER) {
            player.sendMessage("§8(게임이 종료되었습니다. §f/trpg retry§8 또는 §f/trpg stop§8 을 기다리세요.)");
            return;
        }

        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.isDead) return;

        // 미등장 배역: 채팅 차단, 대기 안내
        if (!spawnedPlayers.contains(player.getUniqueId())) {
            player.sendMessage("§8(아직 당신의 배역이 이야기에 등장하지 않았습니다. GM의 안내를 기다리세요.)");
            return;
        }

        // 플레이어 입력 기록 (행동/대사/통신 모두)
        gameLogger.logPlayerInput(player.getName(), message);

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
            // 게임이 이미 종료(엔딩)됐으면 뒤늦게 도착한 응답은 무시 (이전 회차 진행 방지)
            if (currentPhase == Phase.GAMEOVER || currentPhase == Phase.IDLE) return;

            String raw = response.rawText();
            Player player = response.player();

            // 1. 클리어 판정
            if (currentPhase == Phase.HORROR) {
                JsonObject clearTag = ai.parseClearTag(raw);
                if (clearTag != null) {
                    String grade = clearTag.has("grade") ? clearTag.get("grade").getAsString() : "C";
                    String reason = clearTag.has("reason") ? clearTag.get("reason").getAsString() : "";
                    deliverNarrative(player, raw); // 클리어 서술은 행동 플레이어에게
                    onClearEnding(grade, reason);
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

            // 5d. 위치(zone)·세부 위치(spot) 업데이트
            ai.parseZoneUpdateTags(raw).forEach(zu -> updatePlayerZone(zu[0], zu[1], zu[2]));

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

            // 11. Entity AI (괴담 파트, 2턴마다) — 스폰된 각 플레이어에게 개별 전달
            if (currentPhase == Phase.HORROR && state.getCurrentTurn() % 2 == 1) {
                String entityLog = state.buildEntityLog(5);
                String entityPrompt = buildEntitySystemPrompt();
                spawnedPlayers.forEach(uid -> {
                    Player sp = Bukkit.getPlayer(uid);
                    if (sp == null) return;
                    ai.callEntityAi(entityPrompt, entityLog).thenAccept(entityResp -> {
                        if (entityResp == null || entityResp.startsWith("§c")) return;
                        String trimmed = ai.stripTags(entityResp).trim();
                        if (trimmed.isEmpty()) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            // 괴담의 정체/이름/1인칭을 노출하지 않고, 플레이어가 감지하는
                            // 환경 현상으로만 NarrativeDelivery를 통해 자연스럽게 전달한다.
                            if (sp.isOnline()) narrativeDelivery.deliver(sp, trimmed);
                            if (corruptMan.getLevel() >= 2) corruptMan.addEntityMemory(trimmed);
                        });
                    });
                });
            }

            // 11b. 중요 NPC 자율 AI (괴담 파트, 3턴마다)
            if (currentPhase == Phase.HORROR && state.getCurrentTurn() % 3 == 0) {
                fireNpcAiForTurn();
            }

            // 12. 스테이지 기반 자동 등장 체크 (STATE_UPDATE 외부에서 stage 이미 변경된 경우 보정)
            checkAndAutoSpawn();

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
                if (update.has("hp_change")) {
                    int delta = update.get("hp_change").getAsInt();
                    int before = pd.hp[0];
                    pd.hp[0] = Math.max(0, Math.min(pd.hp[1], pd.hp[0] + delta));
                    notifyVitalChange(pd, "체력", "§c", before, pd.hp[0], pd.hp[1]);
                    if (horrorActive && pd.hp[0] <= 0) pd.isDead = true;
                }
                if (update.has("san_change")) {
                    int delta = update.get("san_change").getAsInt();
                    int before = pd.san[0];
                    pd.san[0] = Math.max(0, Math.min(pd.san[1], pd.san[0] + delta));
                    notifyVitalChange(pd, "정신력", "§b", before, pd.san[0], pd.san[1]);
                    if (horrorActive && pd.san[0] <= 0 && pd.hp[0] <= 0) pd.isDead = true;
                }
                if (update.has("timeline_change")) {
                    state.advanceTimeline(update.get("timeline_change").getAsInt());
                    checkAndAutoSpawn();
                }
                if (update.has("status_change") && !update.get("status_change").isJsonNull()) {
                    String newStatus = update.get("status_change").getAsString();
                    Player target = Bukkit.getPlayer(pd.uuid);
                    if ("puppet".equals(newStatus) && "puppet".equals(pd.status)) {
                        // 꼭두각시 재발 → 영구 탈락 (본인에게만 알림, 공포 파트에서만 유효)
                        if (horrorActive) pd.isDead = true;
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
        // 전환을 직접 고지하지 않는다(스포일러 방지). GM의 환경 서술로만 분위기를 바꾼다.

        compressor.compressDailyPhase().thenRun(() ->
            spawnedPlayers.forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                PlayerData pd = state.getPlayer(uuid);
                String name = pd != null ? (pd.charName.isEmpty() ? pd.name : pd.charName) : "?";
                ai.callGmAiOnce(gmSystemPrompt,
                    "분위기가 서서히 변하는 전환 시점이다. 플레이어(" + name + ")의 시점에서 "
                    + "환경 변화(소리·냄새·온도 등)로만 불길함을 암시해줘. 제목 금지, 직접 언급 금지.")
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
                  broadcast("§e재도전: §f/trpg retry  §8|  §e포기하고 전말 보기: §f/trpg stop");
              } else {
                  // 재도전 불가 → 플레이어 명령 대기 없이 즉시 전말 공개 후 세션 종료
                  concludingEnding = true;
                  concludeWithReveal("배드 엔딩 — " + reasonLabel, () -> {
                      concludingEnding = false;
                      endSession(true);
                  });
              }
          }));
    }

    /**
     * 재도전 가능 여부.
     * 규칙: 1~2번째 방은 항상 재도전 가능.
     *       3번째 방부터는 한 명이라도 생존 판정에 성공(= 엔딩 시점 생존자 존재)해야 재도전 가능.
     *       전원 사망으로 끝나면 3번째 방부터는 재도전 불가 → 전말 공개만 가능.
     */
    private boolean isRetryAllowed() {
        if (state.getRoomNumber() <= 2) return true;
        return state.getAliveCount() > 0;
    }

    private void checkDeaths() {
        // 일상 파트에서는 괴담을 아직 마주치지 않은 상태이므로 배드엔딩 판정 없음
        if (currentPhase != Phase.HORROR) return;
        if (state.getAliveCount() == 0) {
            onBadEnding("전원 사망");
            return;
        }
        // 스폰된 생존자가 0이지만 미스폰 생존자가 남은 경우 — 게임 교착 방지
        // (스폰된 플레이어 전원 사망 → 행동 제출자 없어 SPAWN 태그 도달 불가)
        // → 남은 미스폰 플레이어를 즉시 스토리에 투입
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
            // 게임 진행 중(캐릭터 생성 이후)이면 정보·기록 아이템 복원
            if (pd.roleAssigned) { giveInfoItem(player); giveRecordItem(player); }
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
        dialogMan.clearDialog(player);
        pendingTraitSelect.remove(player.getUniqueId());

        PlayerData pd = state.getPlayer(player);
        if (pd != null) {
            if (selected.replacesId != null) {
                // 강화 선택: 원본 특성을 제거하고 강화본을 영구 특성으로 추가
                traitMan.removeTrait(pd, selected.replacesId);
                selected.roleSpecific = false;
                traitMan.addTrait(pd, selected);
                player.sendMessage("§6특성을 강화했습니다 → §f" + selected.name + " §7(" + selected.grade + ")");
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

    /** 스테이지 종료 특성 성장 3선택지 처리 (1=내특성, 2=맵특성, 3=신규) */
    private void handleStageEndTraitSelect(Player player, PlayerData pd,
                                            TraitManager.StageEndChoices choices, int idx) {
        pendingTraitSelect.remove(player.getUniqueId());
        switch (idx) {
            case 1 -> {
                TraitData upg = choices.myUpgrade();
                if (upg != null && upg.replacesId != null) {
                    traitMan.removeTrait(pd, upg.replacesId);
                    traitMan.addTrait(pd, upg);
                    player.sendMessage("§b내 특성을 강화했습니다 → §f" + upg.name + " §7(" + upg.grade + ")");
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

    private void handleTraitUse(Player player, String traitId) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null) return;
        if (currentPhase == Phase.GAMEOVER) {
            player.sendMessage("§8(게임이 종료되었습니다.)");
            return;
        }
        if (pd.isDead) { player.sendMessage("§c사망 상태에서는 특성을 사용할 수 없습니다."); return; }
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
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  시스템 특성 발동 처리
    // ──────────────────────────────────────────────────────────────

    private void handleSystemTraitActivation(Player player, PlayerData pd, TraitData td) {
        SystemTraitRegistry.Effect e = SystemTraitRegistry.Effect.byKey(td.effectType);
        if (e == null) { player.sendMessage("§7이 특성은 자동으로 효과가 적용됩니다."); return; }
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
            default            -> player.sendMessage("§7이 특성은 상시(패시브)로 적용됩니다.");
        }
    }

    private void activateInstantClear(Player player, PlayerData pd, TraitData td) {
        broadcast("§6§l[" + td.name + "] " + (pd.charName.isEmpty() ? pd.name : pd.charName)
            + "이(가) 즉시 생존 판정을 발동했다!");
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        traitMan.removeTrait(pd, td.id);
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> onClearEnding("F", td.name + " 발동 — 즉시 생존 처리"), 20L);
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
            player.sendMessage("§f[" + (i + 1) + "] " + (t.charName.isEmpty() ? t.name : t.charName) + " " + status);
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
            // 질문 입력 타입: 다이얼로그로 안내 후 채팅 대기
            int remaining = uses - td.usedThisStage;
            dialogMan.showQueryInput(player, td, remaining, () -> {
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                pendingPrayerInput.put(player.getUniqueId(), td.id);
                player.sendMessage("§d[" + td.name + "] §7채팅창에 질문을 입력하세요. §8(구체적일수록 더 명확한 답)");
            });
        }
    }

    private void activateAiQueryAutoFire(Player player, PlayerData pd, TraitData td) {
        int info = td.param("info", 1);
        String depthRule = switch (info) {
            case 3 -> "- 핵심에 근접한 정보를 꽤 구체적으로 담아 2~4문장으로 묘사한다. (해결법 자체는 직접 알려주지 않음)\n";
            case 2 -> "- 관련 사실 하나 정도를 암시하는 방향으로 2~3문장 묘사한다.\n";
            default -> "- '어렴풋한 느낌·예감·낌새' 형식으로만 1~2문장 묘사한다. 직접적 단서 나열 금지.\n";
        };
        String charDisplay = pd.charName.isEmpty() ? pd.name : pd.charName;
        String directive = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "캐릭터가 어떤 기억이나 직관을 경험한다.";

        String autoCtx = "\n## " + td.name + " — 자동 회상·직관 서술 (정보 깊이 " + info + "/3)\n"
            + "플레이어가 '" + td.name + "' 특성을 발동했다. 이 특성의 효과: " + directive + "\n"
            + "규칙:\n"
            + "- 캐릭터(" + charDisplay + ")가 지금 이 순간 기억·직관·감각을 경험하는 장면을 생생하게 서술한다.\n"
            + "- 마치 기억이 어렴풋이 떠오르거나, 직관이 번뜩이거나, 눈앞에 잔상이 스치는 것처럼 묘사한다.\n"
            + depthRule
            + "- 독백·내면의 소리는 <-내용-> 형식으로 표현할 수 있다.\n"
            + "- 서술 완료 후 게임 진행을 타임라인에 적절히 반영한다.\n";

        String prompt = charDisplay + "이(가) '" + td.name + "' 특성으로 기억·직관을 경험한다. "
            + "이 순간의 내면 경험을 GM 서술로 묘사해줘.";

        player.sendMessage("§d[" + td.name + " 발동 중...]");
        ai.callGmAiOnce(gmSystemPrompt + autoCtx, prompt).thenAccept(response ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String stripped = ai.stripTags(response).trim();
                if (!stripped.isBlank()) narrativeDelivery.deliver(player, stripped);
            })
        );
    }

    private void activateChoiceAction(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        pendingOracleInput.put(player.getUniqueId(), td.id);
        player.sendMessage("§5[" + td.name + "] 채팅으로 행동을 입력하면 선택지가 제시됩니다.");
        player.sendMessage("§8선택지 중 정답을 고르면 큰 보정, 오답이면 큰 패널티가 적용됩니다.");
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
        String phaseStr = state.isDailyPhase()
            ? "일상 파트 (남은 " + state.getDailyTurnsLeft() + "턴)"
            : "괴담 파트 — 타임라인 " + state.getTimelineStage() + "단계";
        player.sendMessage("§e[" + td.name + "] 현재 괴담 진행 상태:");
        player.sendMessage("§7  단계: §f" + phaseStr);
        player.sendMessage("§7  오염도: §f" + corruptMan.getLevel() + " (" + corruptMan.getAttempts() + "회차)");
    }

    private void activateGmDirective(Player player, PlayerData pd, TraitData td) {
        applyTraitUsed(pd, td.id, state.getCurrentTurn());
        String charDisplay = pd.charName.isEmpty() ? pd.name : pd.charName;
        String directive = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "이 특성의 효과를 사건 전개에 자연스럽게 반영하라.";
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
        dialogMan.showScanInput(player, td, scopeStr, remaining, () -> {
            applyTraitUsed(pd, td.id, state.getCurrentTurn());
            pendingAreaScanInput.put(player.getUniqueId(), td.id);
            player.sendMessage("§b[" + td.name + "] §7채팅창에 탐색 목표를 입력하세요.");
            player.sendMessage("§8예: \"수상한 냄새\", \"숨겨진 출구\", \"다른 사람의 흔적\"");
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
        String charDisplay = pd.charName.isEmpty() ? pd.name : pd.charName;
        String benefit = (td.effect != null && !td.effect.isBlank())
            ? td.effect : "이 희생의 효과를 이야기에 반영하라.";
        String gmMsg = "[시스템 특성: " + td.name + " 발동] " + charDisplay
            + "이(가) " + resource + " " + cost + "을(를) 소모해 힘을 얻었다(" + scaleStr + " 효과). "
            + "GM 지시: " + benefit + " 이야기에 자연스럽게 반영하라.";
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
                String name = op.charName.isEmpty() ? op.name : op.charName;
                String status = op.isDead ? "§c[사망]"
                    : (op.hp[0] < op.hp[1] / 2) ? "§e[중상]" : "§a[생존]";
                player.sendMessage("  " + status + " §f" + name);
            }
        } else {
            // AI 서술: 아군 위치·상태 파악 또는 소통 경로 감지. 다이얼로그 확인 후 사용 차감.
            String depthStr = depth >= 3 ? "소통 경로 발견 포함" : "상태·위치 파악";
            dialogMan.showLinkAllyInput(player, td, depthStr, () -> {
                applyTraitUsed(pd, td.id, state.getCurrentTurn());
                pendingLinkAllyInput.put(player.getUniqueId(), td.id);
                player.sendMessage("§a[" + td.name + "] §7채팅창에 감지 목표를 입력하세요.");
                player.sendMessage("§8예: \"가장 가까운 아군의 위치\", \"다친 아군이 있는지\"");
            });
        }
    }

    private void handleScanObservation(Player player, PlayerData pd, String traitId, String target) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        int scope = td != null ? td.param("scope", 2) : 2;
        String scopeStr = switch (scope) {
            case 3  -> "건물 전체";
            case 2  -> "인접 구역·층";
            default -> "현재 위치";
        };
        String traitName = td != null ? td.name : "환경 탐색";
        String scanCtx = "\n## " + traitName + " 탐색 처리 (범위: " + scopeStr + ")\n"
            + "플레이어가 체계적 탐색으로 단서를 찾고 있다. 규칙:\n"
            + "- 탐색 범위(" + scopeStr + ") 안에서 찾을 수 있는 것만 서술한다.\n"
            + "- 새로운 단서는 최대 1개. 핵심 해결법·답은 직접 알려주지 않는다.\n"
            + "- 아무것도 없으면 '아무것도 발견하지 못했다' 서술. 억지로 단서를 만들지 않는다.\n"
            + "- 탐색 행동 자체도 타임라인에 적절히 반영한다.\n";
        String charDisplay = pd.charName.isEmpty() ? pd.name : pd.charName;
        String prompt = charDisplay + "이(가) '" + traitName + "' 특성으로 " + scopeStr
            + " 범위에서 '" + target + "'을(를) 탐색한다.";
        boolean accepted = turnMan.handleAction(player, prompt, gmSystemPrompt + scanCtx);
        if (!accepted) player.sendMessage("§7행동 처리 중입니다. 잠시 후 다시 시도하세요.");
    }

    private void handleLinkAllyQuery(Player player, PlayerData pd, String traitId, String query) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        int depth = td != null ? td.param("depth", 2) : 2;
        String traitName = td != null ? td.name : "아군 감지";

        StringBuilder allyCtx = new StringBuilder();
        for (PlayerData op : state.getAllPlayers()) {
            if (op.uuid.equals(player.getUniqueId())) continue;
            String name = op.charName.isEmpty() ? op.name : op.charName;
            allyCtx.append("  - ").append(name).append(": ")
                   .append(op.isDead ? "사망" : "생존 (위치: " + op.zone + ")").append("\n");
        }
        String depthRule = depth >= 3
            ? "- 위치·상태를 꽤 구체적으로 암시하고, 소통 수단(연락 방법·접촉 경로)을 발견할 수 있게 한다.\n"
            : "- 아군의 대략적 방향·생존 여부 정도만 감각으로 암시한다. 정확한 위치나 소통 수단은 직접 알려주지 않는다.\n";
        String linkCtx = "\n## " + traitName + " 처리 (감지 깊이 " + depth + "/3)\n"
            + "플레이어가 초감각으로 아군을 탐지하고 있다. 현재 아군 상태:\n" + allyCtx
            + "규칙:\n" + depthRule
            + "- 직접 통신 채널을 여는 것은 불가. 감각적 인지·이야기 서술로만 표현한다.\n";
        String charDisplay = pd.charName.isEmpty() ? pd.name : pd.charName;
        String prompt = charDisplay + "이(가) '" + traitName + "' 특성으로 아군을 탐지한다. 탐지 목표: \"" + query + "\"";
        ai.callGmAiOnce(gmSystemPrompt + linkCtx, prompt).thenAccept(response ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String stripped = ai.stripTags(response).trim();
                if (!stripped.isBlank())
                    player.sendMessage("§a[" + traitName + "] §7" + stripped);
            })
        );
    }

    private void handlePrayerQuestion(Player player, PlayerData pd, String traitId, String question) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        int info = td != null ? td.param("info", 1) : 1;
        String depthRule = switch (info) {
            case 3  -> "- 핵심 해결법을 통째로 알려주진 않되, 약점·방향을 꽤 구체적으로 짚어줘도 된다.\n"
                     + "- 관련 사실을 2~3문장으로 비교적 명확히 답한다.\n";
            case 2  -> "- 핵심 해결법·약점을 직접 알려주지 않는다.\n"
                     + "- 질문과 관련된 사실을 1~2가지 암시한다(2문장 이내).\n";
            default -> "- 핵심 해결법·약점을 직접 알려주지 않는다.\n"
                     + "- '느낌·예감·낌새' 형식으로만 모호하게 답한다(2문장 이내). 직접적 단서 나열 금지.\n"
                     + "- 예: \"그 방향에 뭔가 있다는 예감이 든다.\"\n";
        };
        String name = td != null ? td.name : "질문";
        String prayerCtx = "\n## " + name + " 질문 처리 (정보 깊이 " + info + "/3)\n"
            + "플레이어가 시스템 특성으로 GM에게 직접 질문했다.\n규칙:\n" + depthRule;

        String charDisplay = pd.charName.isEmpty() ? pd.name : pd.charName;
        String prompt = charDisplay + "이(가) '" + name + "' 특성으로 질문한다: \"" + question + "\" "
            + "위 규칙에 맞춰 답해줘.";

        ai.callGmAiOnce(gmSystemPrompt + prayerCtx, prompt).thenAccept(response ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String stripped = ai.stripTags(response).trim();
                if (!stripped.isBlank())
                    player.sendMessage("§d[" + name + "] §7" + stripped);
            })
        );
    }

    private void handleOracleAction(Player player, PlayerData pd, String traitId, String action) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        int numChoices = td != null ? Math.max(2, Math.min(4, td.param("choices", 3))) : 3;
        String oracleCtx = "\n## 선택지 모드\n"
            + "플레이어의 행동 의도를 받아 " + numChoices + "가지 선택지를 JSON으로 제시하라:\n"
            + "{\"choices\":[{\"text\":\"선택지(15자 이내)\",\"outcome\":\"good|bad|neutral\"},...]}\n"
            + "- good: 현 상황에서 가장 효과적인 방법 (큰 보정+) — 정확히 1개\n"
            + "- bad: 역효과를 낼 방법 (큰 패널티-) — 1개 이상\n"
            + "- neutral: 무난하나 특별한 보정 없음\n"
            + "순서는 랜덤하게 섞어 정답을 알기 어렵게 할 것. JSON만 출력.\n";

        String prompt = "플레이어 행동 의도: \"" + action + "\". " + numChoices + "가지 선택지를 JSON으로.";

        ai.callGmAiOnce(gmSystemPrompt + oracleCtx, prompt).thenAccept(raw ->
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

                    player.sendMessage("§5[신내림] 다음 중 하나를 선택하세요:");
                    for (int i = 0; i < choices.size(); i++) {
                        net.kyori.adventure.text.Component btn = net.kyori.adventure.text.Component
                            .text("[" + (i + 1) + "] " + choices.get(i).text(), net.kyori.adventure.text.format.NamedTextColor.AQUA)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/trpg _oracle_select " + i));
                        player.sendMessage(btn);
                    }
                } catch (Exception ex) {
                    fallbackOracleAction(player, pd, action);
                }
            })
        );
    }

    private void fallbackOracleAction(Player player, PlayerData pd, String action) {
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
            case "good"    -> " (계시 — 최적 선택: 큰 보정 적용)";
            case "bad"     -> " (계시 — 역효과 선택: 큰 패널티 적용)";
            default        -> " (계시 — 무난한 선택)";
        };
        String msg = "[선택지 행동] " + (pd.charName.isEmpty() ? pd.name : pd.charName)
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

        // 시나리오 이해 특성을 배역 배정 후 부여했다면 지금 바로 정보 전달
        if ("scenario_insight".equals(td.effectType) && pd.roleAssigned) {
            deliverInsightInfo(target, td);
        }
    }

    private void deliverInsightInfo(Player player, TraitData td) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null) return;
        int depth = td != null ? td.param("depth", 2) : 2;
        StringBuilder insight = new StringBuilder();
        insight.append("§d[").append(td != null ? td.name : "시나리오 이해")
               .append("] 당신은 이 상황의 전체적인 흐름을 어렴풋이 알고 있습니다:\n");
        if (gdam.has("entity")) {
            JsonObject e = gdam.getAsJsonObject("entity");
            String type = e.has("type") ? e.get("type").getAsString() : "알 수 없음";
            insight.append("§7  ▸ 이 사건의 핵심 존재 유형: §f").append(type).append("\n");
            if (e.has("rules") && e.getAsJsonArray("rules").size() > 0)
                insight.append("§7  ▸ 이 존재에는 특정 규칙이 있습니다 (총 ").append(e.getAsJsonArray("rules").size()).append("가지)\n");
            if (e.has("weakness") && !e.get("weakness").getAsString().isBlank())
                insight.append("§7  ▸ 분명한 약점이 존재합니다\n");
            // 상세 깊이: 약점·해결 방향을 한 줄 힌트로 추가 노출 (핵심 전체 공개는 아님)
            if (depth >= 3 && e.has("weakness") && !e.get("weakness").getAsString().isBlank()) {
                String w = e.get("weakness").getAsString();
                insight.append("§7  ▸ 약점의 실마리: §f").append(w.length() > 40 ? w.substring(0, 40) + "…" : w).append("\n");
            }
        }
        if (gdam.has("scale") && !gdam.get("scale").getAsString().isBlank())
            insight.append("§7  ▸ 사건 규모: §f").append(gdam.get("scale").getAsString()).append("\n");
        insight.append("§8(핵심 정보·해결법은 직접 탐색으로 알아내야 합니다)");
        player.sendMessage(insight.toString());
    }

    private void applySaintEffect(Player player, PlayerData pd, String traitId, PlayerData target) {
        TraitData td = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst().orElse(null);
        String traitName = td != null ? td.name : "회복";
        boolean wasDeadBefore = target.isDead;
        target.hp[0]  = target.hp[1];
        target.san[0] = target.san[1];
        target.status = "normal";
        target.isDead = false;
        applyTraitUsed(pd, traitId, state.getCurrentTurn());
        updateAllScoreboards();
        String targetDisplay = target.charName.isEmpty() ? target.name : target.charName;
        String playerDisplay = pd.charName.isEmpty() ? pd.name : pd.charName;
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
        dialogMan.showCharacterInfo(player, pd, traitId -> handleTraitUse(player, traitId));
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

    private void onClearEnding(String grade, String reason) {
        if (currentPhase == Phase.CLEAR || currentPhase == Phase.GAMEOVER) return;
        currentPhase = Phase.CLEAR;
        gameLogger.logEvent("클리어 — 등급: " + grade + (reason != null && !reason.isBlank() ? " / 해결: " + reason : ""));

        String finalGrade = corruptMan.getRewardGrade(grade);
        broadcast("§6§l═══════════════════════════════");
        broadcast("§6§l  클리어! 등급: " + grade
            + (corruptMan.getLevel() > 0 ? " (오염 보정 → " + finalGrade + ")" : ""));
        broadcast("§6§l═══════════════════════════════");
        if (reason != null && !reason.isBlank()) broadcast("§a해결: §f" + reason);

        // 뒷이야기(에필로그) + 엔딩 해설 공개
        concludeWithReveal("퍼펙트 클리어 (등급 " + grade + ")", null);

        String gdamTheme = getEntityName();

        state.getAllPlayers().stream()
            .filter(playerData -> !playerData.isDead)
            .forEach(playerData -> {
                // 기여도 기반 특성 성장 3선택지 생성 (오염도만큼 보상 등급 상향)
                traitMan.generateStageEndChoices(playerData, gdamTheme, corruptMan.getLevel()).thenAccept(choices -> {
                    if (choices == null) return;
                    Player p = Bukkit.getPlayer(playerData.uuid);
                    if (p == null || !p.isOnline()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.sendMessage("§6§l[클리어 보상] 특성 성장을 선택하세요!");
                        String srcMyName = choices.myUpgrade() != null && choices.myUpgrade().replacesId != null
                            ? traitMan.getTrait(playerData, choices.myUpgrade().replacesId)
                                      .map(t -> t.name).orElse("") : null;
                        String srcMapName = choices.mapUpgrade() != null && choices.mapUpgrade().replacesId != null
                            ? traitMan.getTrait(playerData, choices.mapUpgrade().replacesId)
                                      .map(t -> t.name).orElse("") : null;
                        dialogMan.showStageEndTraitChoice(p, choices, srcMyName, srcMapName,
                            idx -> handleStageEndTraitSelect(p, playerData, choices, idx));
                        pendingTraitSelect.add(p.getUniqueId());
                    });
                });
            });

        broadcast("§6특성을 선택한 뒤 §f/trpg stop §6으로 세션을 종료하세요.");
    }

    // ──────────────────────────────────────────────────────────────
    //  엔딩 마무리: 뒷이야기(에필로그) + 엔딩 해설
    // ──────────────────────────────────────────────────────────────

    /**
     * 결말 후 AI 에필로그(뒷이야기)를 생성해 보여주고, 이어서 .gdam 해설을 공개한다.
     * 클리어, '재도전 불가 배드엔딩', '포기(중도 종료)' 시 호출한다. 재도전 가능한 배드엔딩에서는 호출하지 않는다.
     * @param onDone 에필로그·해설 공개가 끝난 뒤 실행할 콜백 (없으면 null)
     */
    private void concludeWithReveal(String endingLabel, Runnable onDone) {
        String recentLog = state.buildEntityLog(15);
        String prompt = "게임이 끝났다. 결말 유형: " + endingLabel + ".\n"
            + (recentLog.isBlank() ? "" : "플레이어들의 주요 행동 기록:\n" + recentLog + "\n")
            + "\n이 사건의 '뒷이야기'를 소설풍 에필로그로 3~5문장 써줘. "
            + "남은 인물들의 그 후, 장소의 변화, 여운을 담되 과장 없이. "
            + "제목·마크다운 금지, 대사는 큰따옴표로.";
        ai.callGmAiOnce(gmSystemPrompt, prompt)
            .thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                String story = ai.stripTags(r);
                if (!story.isBlank()) {
                    broadcast("");
                    broadcast("§e§l📖 뒷이야기");
                    for (String line : NarrativeDelivery.format(story).split("\n")) {
                        if (!line.isBlank()) broadcast("§7" + line);
                    }
                    gameLogger.logGmOutput("전체(뒷이야기)", story);
                }
                broadcast(buildEndingReveal());
                gameLogger.logEvent("엔딩 해설 공개 (" + endingLabel + ")");
                if (onDone != null) onDone.run();
            }));
    }

    /** 엔딩 시 .gdam 내부 설계(정체·규칙·타임라인·스케일·해결법 등)를 공개하는 해설 텍스트 */
    private String buildEndingReveal() {
        JsonObject gdam = state.getGdamData();
        if (gdam == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n§8§l━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("§e§l  엔딩 해설  §7씨드 ").append(state.getCurrentSeed()).append("\n");
        sb.append("§8§l━━━━━━━━━━━━━━━━━━━━");

        JsonObject e = gdam.has("entity") ? gdam.getAsJsonObject("entity") : null;
        if (e != null) {
            sb.append("\n\n§c§l🎭 괴담의 정체\n§f").append(getStr(e, "name"));
            String type = getStr(e, "type");
            if (!type.isBlank()) sb.append(" §7(").append(type).append(")");
            if (e.has("ai_context")) {
                String pers = getStr(e.getAsJsonObject("ai_context"), "personality");
                if (!pers.isBlank()) sb.append("\n§7").append(pers);
            }
            if (e.has("rules") && e.getAsJsonArray("rules").size() > 0) {
                sb.append("\n\n§b§l📐 핵심 규칙\n");
                int i = 1;
                for (JsonElement r : e.getAsJsonArray("rules"))
                    sb.append("§7").append(i++).append(". §f").append(r.getAsString()).append("\n");
            }
            appendSection(sb, "§d§l⚠ 약점", getStr(e, "weakness"));
            appendSection(sb, "§a§l✅ 정석 해결법", getStr(e, "solution"));
            appendSection(sb, "§6§l💡 역이용 경로", getStr(e, "exploit_path"));
            appendSection(sb, "§9§l🚪 생존법", getStr(e, "escape"));
            if (e.has("hidden_rules") && e.getAsJsonArray("hidden_rules").size() > 0) {
                sb.append("\n§5§l🧩 숨겨진 규칙\n");
                for (JsonElement hr : e.getAsJsonArray("hidden_rules"))
                    sb.append("§7▸ §f").append(hr.getAsString()).append("\n");
            }
        }
        appendSection(sb, "§e§l🗺 스케일", getStr(gdam, "scale"));

        if (gdam.has("timeline")) {
            JsonObject tl = gdam.getAsJsonObject("timeline");
            StringBuilder tlsb = new StringBuilder();
            for (String k : new String[]{"1", "2", "3", "4"}) {
                if (tl.has(k) && tl.get(k).isJsonObject()) {
                    String eff = getStr(tl.getAsJsonObject(k), "effect");
                    if (!eff.isBlank()) tlsb.append("§7[").append(k).append("단계] §f").append(eff).append("\n");
                }
            }
            if (tlsb.length() > 0) sb.append("\n§c§l⏱ 타임라인\n").append(tlsb);
        }

        if (gdam.has("clues") && gdam.getAsJsonArray("clues").size() > 0) {
            JsonArray clues = gdam.getAsJsonArray("clues");
            int found = state.getDiscoveredClues().size();
            sb.append("\n§7§l🔍 배치된 단서 §8(발견 ").append(found).append("/").append(clues.size()).append(")\n");
            for (JsonElement c : clues) {
                String desc = c.isJsonObject()
                    ? firstNonBlank(getStr(c.getAsJsonObject(), "description"), getStr(c.getAsJsonObject(), "id"))
                    : c.getAsString();
                if (!desc.isBlank()) sb.append("§8▸ §7").append(desc).append("\n");
            }
        }
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String header, String body) {
        if (body == null || body.isBlank()) return;
        sb.append("\n").append(header).append("\n§f").append(body).append("\n");
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String getStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
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
            state.getAllPlayers().stream()
                .filter(pd -> pd.name.equals(pName) && spawnedPlayers.contains(pd.uuid))
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

    private void appendNarrativeLog(PlayerData pd, String text) {
        synchronized (pd.narrativeLog) {
            pd.narrativeLog.add(text.trim());
            if (pd.narrativeLog.size() > PlayerData.NARRATIVE_LOG_MAX)
                pd.narrativeLog.remove(0);
        }
    }

    private void extractAndStoreInfo(String narrative, PlayerData pd) {
        if (narrative.isBlank()) return;
        String task = "아래 TRPG 서술에서 정보가 담긴 내용만 추출해줘.\n"
            + "포함: NPC 발언, 관찰·발견, 독백, 추론 단서\n"
            + "제외: 분위기 서술, 이동 서술, 결과 없는 행동 묘사\n"
            + "정보가 없으면 '없음'만. 있으면 '• 내용' 형식 한 줄씩 (최대 3줄).";
        ai.callAssistant(task, narrative).thenAccept(result -> {
            if (result == null || result.isBlank()) return;
            for (String line : result.split("\n")) {
                String clean = line.trim();
                if (clean.isEmpty() || clean.equals("없음")) continue;
                if (!clean.startsWith("•")) clean = "• " + clean;
                synchronized (pd.infoItems) {
                    pd.infoItems.add(clean);
                    if (pd.infoItems.size() > PlayerData.INFO_ITEMS_MAX)
                        pd.infoItems.remove(0);
                }
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
                gameLogger.logEvent("배역 등장: " + pd.name + " [" + pd.roleId + "]");
                Player p = Bukkit.getPlayer(pd.uuid);
                if (p == null || !p.isOnline()) return;
                p.sendMessage("§e§l[등장] 당신의 배역이 이야기에 들어섰습니다. 이제 행동할 수 있습니다.");
            });
    }

    /** spawn_timeline "타임라인 N단계" → N. 즉시/미설정 → 0. 파싱 불가 → -1 */
    private int getSpawnStageRequired(String roleId) {
        JsonObject r = findRoleData(roleId);
        if (r == null || !r.has("spawn_timeline")) return 0;
        String st = r.get("spawn_timeline").getAsString().trim();
        if (st.isEmpty() || st.equals("시작 즉시")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)단계").matcher(st);
        if (m.find()) return Integer.parseInt(m.group(1));
        return -1; // 인식 불가 → GM 판단에 맡김
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
                    // 자동 등장 시 GM AI에 맥락 주입
                    String display = pd.charName.isEmpty() ? pd.name : pd.charName;
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
        String envRule =
            "너는 괴담의 '결과'를 환경 현상으로 묘사하는 연출 보조다.\n"
          + "★ 절대 규칙:\n"
          + "- 괴담의 이름·정체·동기를 절대 언급하지 마라.\n"
          + "- 1인칭('나는...') 금지. 괴담의 내면·시점·감각·의도를 직접 서술 금지.\n"
          + "- 괴담이 직접 말하거나 메시지를 보내는 형식 금지.\n"
          + "- 오직 플레이어가 감각으로 인지할 수 있는 물리적 현상·환경 이상만 묘사.\n"
          + "  (소리·냄새·온도·그림자·사물의 미세한 변화 등)\n"
          + "- 짧게 1문장, 2인칭 관찰자 시점. 한국어. 따옴표·제목·머리기호 금지.\n"
          + "좋은 예: \"복도 끝 형광등이 한 박자 늦게 깜빡인다.\"\n"
          + "나쁜 예: \"[괴담] 나는 너에게 다가간다.\" (1인칭·정체 노출 — 금지)\n";
        String intensity = buildEntityIntensityGuide();
        if (gdam == null || !gdam.has("entity")) return envRule + intensity;

        JsonObject entity = gdam.getAsJsonObject("entity");
        StringBuilder sb = new StringBuilder(envRule).append(intensity);
        sb.append("플레이어 스탯·특성·해결법을 절대 직접 언급 금지.\n");
        if (entity.has("ai_context")) {
            JsonObject ctx = entity.getAsJsonObject("ai_context");
            // 성격/패턴은 내부 참고용일 뿐, 출력에 직접 노출하지 말 것
            if (ctx.has("personality"))
                sb.append("[내부 참고] 성향: ").append(ctx.get("personality").getAsString()).append("\n");
            if (ctx.has("initial_pattern"))
                sb.append("[내부 참고] 행동 패턴: ").append(ctx.get("initial_pattern").getAsString()).append("\n");
        }
        if (entity.has("rules") && entity.get("rules").isJsonArray()) {
            sb.append("[내부 참고] 규칙: ").append(entity.get("rules").toString()).append("\n");
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

    /** .gdam npcs[].zone을 npcZones 맵에 초기화 (세션·재현 시작 시 호출) */
    private void initNpcZones(JsonObject gdam) {
        npcZones.clear();
        if (gdam == null || !gdam.has("npcs")) return;
        for (JsonElement el : gdam.getAsJsonArray("npcs")) {
            if (!el.isJsonObject()) continue;
            JsonObject npc = el.getAsJsonObject();
            if (!npc.has("id")) continue;
            String zone = npc.has("zone") ? npc.get("zone").getAsString() : "";
            npcZones.put(npc.get("id").getAsString(), zone);
        }
    }

    /** critical NPC 전용 시스템 프롬프트 생성 */
    private String buildNpcSystemPrompt(JsonObject npcObj) {
        String name = npcObj.has("name") ? npcObj.get("name").getAsString() : "NPC";
        StringBuilder sb = new StringBuilder();
        sb.append("너는 하드코어 생존 괴담 TRPG의 NPC '").append(name).append("'야.\n");
        sb.append("플레이어들의 최근 행동에 반응하여 이 NPC가 지금 무엇을 하는지 자율적으로 결정한다.\n\n");
        if (npcObj.has("personality"))
            sb.append("성격: ").append(npcObj.get("personality").getAsString()).append("\n");
        if (npcObj.has("motivation"))
            sb.append("목표: ").append(npcObj.get("motivation").getAsString()).append("\n");
        if (npcObj.has("knowledge") && npcObj.get("knowledge").isJsonArray()) {
            sb.append("알고 있는 정보: ");
            npcObj.getAsJsonArray("knowledge").forEach(k -> sb.append(k.getAsString()).append(" / "));
            sb.append("\n");
        }
        sb.append("\n## 출력 원칙\n");
        sb.append("- 2~3문장으로 NPC의 행동·반응·대사를 서술한다.\n");
        sb.append("- 3인칭 행동 서술. 1인칭('나는...') 금지.\n");
        sb.append("- 성격·목표에 충실하게 행동하라. 플레이어가 불리해지는 행동도 가능하다.\n");
        sb.append("- 단서를 통째로 알려주지 마라. 흘리거나 은폐할 수 있다.\n");
        sb.append("- 마크다운·XML 태그·메타 해설 일체 금지. 순수 서술 텍스트만 출력하라.\n");
        sb.append("- 플레이어 스탯·특성·GM 판정 내역은 알지 못한다. 겉으로 드러난 행동만 인지한다.\n");
        return sb.toString();
    }

    /**
     * 괴담 파트 N턴마다 critical NPC 독립 AI 호출.
     * NPC 행동은 같은 zone의 플레이어에게 직접 전달되고, GM 컨텍스트에 주입된다.
     */
    private void fireNpcAiForTurn() {
        List<JsonObject> criticals = getCriticalNpcs();
        if (criticals.isEmpty()) return;

        String actionLog = state.buildEntityLog(4);

        for (JsonObject npcObj : criticals) {
            String npcId   = npcObj.has("id")   ? npcObj.get("id").getAsString()   : "npc";
            String npcName = npcObj.has("name") ? npcObj.get("name").getAsString() : "NPC";
            String npcZone = npcZones.getOrDefault(npcId,
                npcObj.has("zone") ? npcObj.get("zone").getAsString() : "");
            String npcPrompt = buildNpcSystemPrompt(npcObj);

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
            String npcPromptFinal = wantThought
                ? npcPrompt + "\n응답 말미에 <THOUGHT>지금 이 NPC의 내면 생각 1문장</THOUGHT>을 출력하라.\n"
                : npcPrompt;

            ai.callNpcAi(npcId, npcPromptFinal, actionLog).thenAccept(npcResp -> {
                if (npcResp == null || npcResp.startsWith("§c")) return;

                // ③ 엿보기: 내면 사고를 같은 zone 엿보기 플레이어에게 비공개 전달
                if (wantThought) {
                    String thought = ai.parseThoughtTag(npcResp);
                    if (thought != null && !thought.isEmpty()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            for (Player ep : eavesdroppers)
                                if (ep.isOnline())
                                    ep.sendMessage("§8[엿보기] §7" + npcName + " (속마음: " + thought + ")");
                        });
                    }
                }

                String trimmed = ai.stripThought(ai.stripTags(npcResp)).trim();
                if (trimmed.isEmpty()) return;

                // GM 컨텍스트에만 주입 — 플레이어에게 직접 전달하지 않음.
                // GM이 다음 턴 서술에서 NPC 행동을 자연스럽게 녹여 낸다.
                ai.injectGmSystem("[NPC 자율 행동 — GM만 인지] " + npcName + " (위치: "
                    + (npcZone.isEmpty() ? "?" : npcZone) + "): " + trimmed);
                gameLogger.logGmOutput("NPC(" + npcName + ")", trimmed);
            });
        }
    }

    /**
     * 괴담 현상의 강도 지침. 오염도(corruption)와 타임라인 진행도에 비례한다.
     * 초반·저오염: 그냥 흘려보낼 사소한 위화감(시나리오 영향 없음).
     * 후반·고오염: 명백·불시·시나리오에 직접 영향 가능.
     */
    private String buildEntityIntensityGuide() {
        int corr  = corruptMan.getLevel();
        int stage = state.getTimelineStage();
        int t = corr + Math.max(0, stage - 1);
        if (t <= 1) {
            return "현재 강도: 매우 약함. 그냥 흘려보낼 만한, 있는 듯 없는 듯한 사소한 위화감 1문장만. "
                 + "시나리오 진행에 영향을 주는 사건·피해·직접 위협 절대 금지. 분위기만 아주 살짝.\n";
        } else if (t <= 3) {
            return "현재 강도: 중간. 신경 쓰이는 이상 현상 1문장. 아직 치명적이지 않게, 의심이 들 정도로만.\n";
        } else {
            return "현재 강도: 강함. 명백하고 불길한 현상. 불시에 닥쳐도 좋고, 시나리오에 직접 영향을 줄 수 있다. "
                 + "단, 여전히 이름·정체·1인칭은 노출 금지.\n";
        }
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
            // NPC 이름 대조 (플레이어가 아닌 NPC에게 말 거는 경우)
            if (!dialedByNumber) {
                JsonObject npcObj = findNpcByName(token);
                if (npcObj != null) {
                    handleNpcDirectComm(sender, senderPd, npcObj, message);
                    return;
                }
            }
            sender.sendMessage(dialedByNumber
                ? "§c'" + token + "' 번호로 연결되지 않습니다. (없는 번호)"
                : "§c'" + token + "' 플레이어(또는 NPC)를 찾을 수 없습니다.");
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

    /** critical NPC 목록에서 이름으로 검색 */
    private JsonObject findNpcByName(String name) {
        for (JsonObject npc : getCriticalNpcs()) {
            String npcName = npc.has("name") ? npc.get("name").getAsString() : "";
            String npcId   = npc.has("id")   ? npc.get("id").getAsString()   : "";
            if (npcName.equalsIgnoreCase(name) || npcId.equalsIgnoreCase(name)) return npc;
        }
        return null;
    }

    /**
     * ② 플레이어 → NPC 직접 심문.
     * GM round-trip 없이 NPC AI(Haiku)가 직접 응답.
     * 같은 zone에 있어야 대면 가능.
     */
    private void handleNpcDirectComm(Player sender, PlayerData senderPd, JsonObject npcObj, String message) {
        String npcId   = npcObj.has("id")   ? npcObj.get("id").getAsString()   : "npc";
        String npcName = npcObj.has("name") ? npcObj.get("name").getAsString() : "NPC";
        String npcZone = npcZones.getOrDefault(npcId,
            npcObj.has("zone") ? npcObj.get("zone").getAsString() : "");

        // 같은 zone에 있어야 직접 대화 가능
        if (!senderPd.zone.isEmpty() && !senderPd.zone.equals(npcZone)) {
            sender.sendMessage("§c" + npcName + "은(는) 같은 위치에 없습니다. 직접 찾아가야 합니다.");
            return;
        }

        // ③ 엿보기 특성 여부 확인
        boolean hasEavesdrop = senderPd.traits.stream()
            .anyMatch(t -> t.id.contains("엿보기") || t.id.contains("eavesdrop"));

        String npcPrompt = buildNpcDirectConvPrompt(npcObj, hasEavesdrop);
        String userMsg   = "[" + senderPd.name + "이/가 말한다] " + message;

        sender.sendMessage("§7[→ " + npcName + "] §f" + message);

        ai.callNpcAi(npcId, npcPrompt, userMsg).thenAccept(npcResp -> {
            if (npcResp == null || npcResp.startsWith("§c")) return;

            // ③ 엿보기: 내면 사고를 먼저 비공개로 전달
            if (hasEavesdrop) {
                String thought = ai.parseThoughtTag(npcResp);
                if (thought != null && !thought.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (sender.isOnline())
                            sender.sendMessage("§8[엿보기] §7(속마음: " + thought + ")");
                    });
                }
            }

            String visible = ai.stripThought(ai.stripTags(npcResp)).trim();
            if (visible.isEmpty()) return;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sender.isOnline())
                    sender.sendMessage("§e[" + npcName + "] §f" + visible);
            });

            // GM 컨텍스트에 요약만 주입 (전체 대화 노출 방지)
            String summary = visible.length() > 80 ? visible.substring(0, 80) + "…" : visible;
            ai.injectGmSystem("[NPC 직접 심문] " + senderPd.name + " → " + npcName
                + ": \"" + (message.length() > 40 ? message.substring(0, 40) + "…" : message)
                + "\" / NPC 반응: " + summary);

            gameLogger.logGmOutput("NPC직접(" + npcName + ")", visible);
        });
    }

    /** 직접 대화용 NPC 시스템 프롬프트 (자율 행동 프롬프트와 별개) */
    private String buildNpcDirectConvPrompt(JsonObject npcObj, boolean includeThought) {
        String name = npcObj.has("name") ? npcObj.get("name").getAsString() : "NPC";
        StringBuilder sb = new StringBuilder(buildNpcSystemPrompt(npcObj));
        // 자율 행동 프롬프트를 베이스로 삼되 대화 모드 지침을 덮어씀
        sb.append("\n## 직접 대화 모드\n");
        sb.append("플레이어가 네게 직접 말을 걸었다. 행동 서술 대신 대화·반응으로 응답하라.\n");
        sb.append("- 1인칭으로 대화하되 성격·목표에 충실하게 답하라.\n");
        sb.append("- 진실·단서를 통째로 드러내지 마라. 얼버무리거나 역질문할 수 있다.\n");
        sb.append("- 2~4문장 이내.\n");
        if (includeThought) {
            sb.append("- 응답 말미에 <THOUGHT>네가 실제로 생각하는 것 (한 문장)</THOUGHT>을 출력하라. "
                     + "이것은 플레이어에게는 표시되지 않는 내면이다.\n");
        }
        return sb.toString();
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

    /** GM이 플레이어 위치를 zone(+세부 위치 spot)으로 업데이트. 같은 zone 진입 시 연락처 자동 교환 */
    private void updatePlayerZone(String playerName, String newZone, String spot) {
        PlayerData moved = findAnyByName(playerName);
        if (moved == null || newZone == null || newZone.isBlank()) return;
        boolean zoneChanged = !newZone.equals(moved.zone);
        moved.zone = newZone;
        moved.visitedZones.add(newZone); // 방문 기록 (직접 그린 약도에 반영)
        // 위치 이동 시 기록에 구분 마커 추가 (기록 다이얼로그 페이지 분할 지점)
        if (zoneChanged && spawnedPlayers.contains(moved.uuid)) {
            appendNarrativeLog(moved, PlayerData.MOVE_TAG + zoneDisplayName(newZone));
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
        StringBuilder sb = new StringBuilder(GM_SYSTEM_BASE);
        sb.append("\n## .gdam 사전 확정 데이터\n");
        sb.append("씨드: ").append(gdam.has("seed") ? gdam.get("seed").getAsString() : "?").append("\n");
        int room = state.getRoomNumber();
        sb.append("room(현재 스테이지 번호): ").append(room).append("\n");
        if (gdam.has("entity")) {
            sb.append("괴담 존재: ").append(gdam.getAsJsonObject("entity").get("name").getAsString()).append("\n");
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
                sb.append("- 매 턴 입력의 '현재 시각'을 기준으로 시간 흐름을 자연스럽게 서술에 반영하라.\n");
                sb.append("- 휴식·장면 전환으로 시간을 크게 건너뛸 땐 <TIME_SKIP minutes=\"분\"/>을 출력하라.\n");
                sb.append("- 플레이어가 시간을 알게/모르게 되는 상황(시계 입수·파손 등)엔 <TIME_VISIBLE player=\"이름\" known=\"true\" 또는 \"false\"/>.\n");
            }
            if (tl.has("main_events") && tl.get("main_events").isJsonArray()
                    && tl.getAsJsonArray("main_events").size() > 0) {
                sb.append("\n## 큰 사건 타임라인 (정해진 시각에 자동 발생) ★\n");
                sb.append("아래 사건은 막지 않으면 해당 시각에 반드시 일어난다. 시스템이 시각 도달 시 '지금 발생한 사건'으로 알리니 그때 서술하라.\n");
                sb.append("blockable 사건을 플레이어가 실제로 막아내면 <EVENT_BLOCK id=\"사건ID\"/>를 출력해 취소하라.\n");
                for (JsonElement el : tl.getAsJsonArray("main_events")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject ev = el.getAsJsonObject();
                    sb.append("- [").append(ev.has("time") ? ev.get("time").getAsString() : "?").append("] ");
                    sb.append(ev.has("id") ? ev.get("id").getAsString() : "");
                    if (ev.has("label"))  sb.append(" ").append(ev.get("label").getAsString());
                    if (ev.has("effect")) sb.append(" → ").append(ev.get("effect").getAsString());
                    if (ev.has("blockable") && ev.get("blockable").getAsBoolean()) sb.append(" (막을 수 있음)");
                    if (ev.has("is_end")    && ev.get("is_end").getAsBoolean())    sb.append(" [종료 사건]");
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
        if (state.getTimelineStage() >= 4) {
            sb.append("\n## ★ 현재 타임라인 4단계 — 극한 압박 모드\n");
            sb.append("괴담이 최대 강도로 작동한다. 매 행동마다 피해·위협이 발생해도 좋다.\n");
            sb.append("단, 클리어는 여전히 가능하다. 플레이어가 해결 조건을 달성하면 <CLEAR>를 출력한다.\n");
            sb.append("자동 배드엔딩이나 '이제 늦었다' 식의 클리어 차단 서술을 하지 마라.\n");
            sb.append("클리어 성공 시 등급은 D 또는 C. 생존자가 많고 해결이 완벽하면 B도 가능.\n");
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
        List<JsonObject> critNpcs = getCriticalNpcs();
        if (!critNpcs.isEmpty()) {
            sb.append("\n## 자율 NPC (독립 AI 결정 → GM이 서술) ★\n");
            sb.append("아래 NPC는 별도 AI가 행동을 결정한다.\n");
            sb.append("결정 내용은 '[NPC 자율 행동 — GM만 인지]' 태그로 전달된다.\n");
            sb.append("GM은 이 내용을 바탕으로 다음 서술에 해당 NPC의 행동을 자연스럽게 녹여 낸다.\n");
            sb.append("★ NPC 행동은 GM의 서술을 통해서만 플레이어에게 전달된다 (직접 출력 금지).\n");
            for (JsonObject npc : critNpcs) {
                String nname = npc.has("name") ? npc.get("name").getAsString() : "?";
                String nzone = npc.has("zone") ? npc.get("zone").getAsString() : "?";
                sb.append("- ").append(nname).append(" (").append(nzone).append(")");
                if (npc.has("motivation")) sb.append(" — ").append(npc.get("motivation").getAsString());
                sb.append("\n");
            }
        }
        // 대기 중인 배역 등장 조건 (미등장 플레이어)
        List<PlayerData> pendingSpawn = state.getAllPlayers().stream()
            .filter(pd -> !pd.isDead && !spawnedPlayers.contains(pd.uuid))
            .toList();
        if (!pendingSpawn.isEmpty()) {
            sb.append("\n## 대기 중인 배역 (아직 이야기에 등장하지 않음) ★\n");
            for (PlayerData pd : pendingSpawn) {
                JsonObject r = findRoleData(pd.roleId);
                String display = pd.charName.isEmpty() ? pd.name : pd.charName;
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
                String n   = p.charName.isEmpty() ? p.name : p.charName;
                String eff = (t.effect != null && !t.effect.isBlank()) ? t.effect : t.description;
                switch (t.effectType == null ? "" : t.effectType) {
                    case "passive_gm" ->
                        passiveBlock.append("- ").append(n).append(" (").append(t.name).append("): ")
                                    .append(eff).append("\n");
                    case "passive_trigger" -> {
                        int intensity = t.param("intensity", 2);
                        String ig = intensity >= 3 ? "강" : intensity == 2 ? "중" : "약";
                        triggerBlock.append("- ").append(n).append(" (").append(t.name).append(", 강도 ").append(ig).append("): ")
                                    .append(eff).append("\n");
                    }
                    case "protect" -> {
                        int power    = t.param("power", 2);
                        int useLimit = t.param("uses", 0);
                        String pg = power >= 3 ? "거의 무효화" : power == 2 ? "절반 경감" : "소폭 경감";
                        String ul = useLimit > 0 ? " (스테이지당 " + useLimit + "회 한정)" : "";
                        protectBlock.append("- ").append(n).append(" (").append(t.name).append("): ")
                                    .append(eff).append(" [").append(pg).append(ul).append("]\n");
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
        gameLogger.startNewLog(seed, stage);
        ai.clearAll();

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
                if (!itemId.isEmpty()) state.getAllPlayers().forEach(pd -> pd.heldItemIds.add(itemId));
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
}
