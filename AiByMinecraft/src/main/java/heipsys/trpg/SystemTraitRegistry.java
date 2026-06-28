package heipsys.trpg;

import heipsys.trpg.model.TraitData;
import java.util.*;

/**
 * 시스템 효과 프리미티브 레지스트리.
 *
 * 설계 철학: "능력(기계 효과)"은 코드가 정의하고, "변형(이름·등급·설명·쿨다운·수치)"은 AI가 정한다.
 * AI는 effect_type으로 프리미티브를 고르고 effect_params로 수치를 조절해
 * 같은 능력에서 다양한 특성 변형을 만들어낸다.
 *   예) ai_query(uses=2, info=1)     → "기도자A: 2회 제한, 적은 정보"
 *       ai_query(uses=1, info=3)     → "신내림A: 1회 제한, 핵심 정보"
 *       passive_gm(effect="…")       → "육감A: 항상 켜진 경고"
 *       passive_trigger(intensity=2) → "위기감지B: 조건 충족 시 자동 발동"
 */
public class SystemTraitRegistry {

    /** 코드가 정의하는 효과 프리미티브("능력"). 수치/이름/등급은 AI가 정한다. */
    public enum Effect {

        // ─── 능동 효과 (active = true) ───────────────────────────────────────────────
        AI_QUERY("ai_query", true,
            "발동 시 플레이어가 GM에게 직접 질문할 수 있다(플레이어가 직접 타이핑). 질문이 구체적일수록 더 정확히 답한다. ★기억회상·직관·환각 등 캐릭터가 자동으로 정보를 경험하는 효과는 gm_directive를 사용할 것.",
            "uses=스테이지당 질문 횟수(1~3), info=정보 깊이(1=암시·적은정보, 2=중간, 3=핵심 근접), auto_fire=자동발동여부(0=플레이어가질문타이핑[기본], 1=AI가자동서술)"),
        INSTANT_CLEAR("instant_clear", true,
            "발동 시 현재 스테이지를 즉시 생존 클리어 처리한다(평가 등급은 최하 고정).",
            "uses=사용 횟수(보통 1)"),
        REVIVE_ALLY("revive_ally", true,
            "발동 시 다른 플레이어 1명을 선택해 체력·정신력·상태이상을 완전 회복하고 사망 시 부활시킨다.",
            "uses=사용 횟수(보통 1)"),
        LUCK_ROLL("luck_roll", true,
            "발동 시 주사위를 굴려 다음 행동 1회에 행운 보정을 준다(낮으면 마이너스, 높으면 플러스).",
            "scale=보정 최대 절대값(예:10), dice=주사위 면수(기본 6)"),
        SHOW_PROGRESS("show_progress", true,
            "발동 시 현재 괴담의 진행 단계·상태를 본인만 확인한다.",
            "(별도 파라미터 없음. 제약은 cooldown으로 조절)"),
        CHOICE_ACTION("choice_action", true,
            "발동 시 다음 행동을 직접 입력 대신 선택지로 제시한다. 정답엔 큰 보정, 오답엔 큰 패널티.",
            "uses=사용 횟수, choices=선택지 개수(2~4)"),
        GM_DIRECTIVE("gm_directive", true,
            "발동 시 effect에 적힌 지시를 GM에게 전달해 사건 전개에 반영시킨다. ★기억회상·직관·예지·환각·내면경험(캐릭터가 자동으로 정보를 경험하는 효과) 특성에 반드시 사용. effect 텍스트에 '무엇을 떠올리는지/경험하는지' 서술하면 AI가 그 장면을 생생하게 묘사한다.",
            "uses=사용 횟수"),
        AREA_SCAN("area_scan", true,
            "발동 후 채팅으로 무엇을 찾는지 입력하면 현재 구역을 탐색해 숨겨진 정보·단서·위험 요소를 파악한다. 질문(ai_query)과 달리 관찰·탐색 기반이며 타임라인이 소모된다.",
            "scope=탐색 범위(1=현재위치, 2=인접구역·층, 3=광역·건물 전체), uses=스테이지당 횟수(1~3)"),
        SACRIFICE("sacrifice", true,
            "발동 시 HP 또는 SAN을 소모해 강력한 효과를 얻는다. 효과 내용은 effect 텍스트로 표현한다(예: 일시적 스탯 급등, 봉인 해제, 저주 전이 등).",
            "cost=소모량(1~20), use_san=소모 대상(0=HP소모 기본값, 1=SAN소모), scale=혜택 크기(1=소, 2=중, 3=대)"),
        LINK_ALLY("link_ally", true,
            "발동 시 다른 플레이어의 위치·상태를 감각으로 파악하거나 소통 수단을 발견한다. depth에 따라 얻는 정보 수준이 달라진다.",
            "uses=횟수(1~2), depth=감지 깊이(1=생존·대략 위치 확인 [즉시 표시], 2=상태 파악·소통 실마리 [AI 서술], 3=소통 경로 발견 포함 [AI 서술])"),
        GET_CONTACTS("get_contacts", true,
            "발동 시 아직 모르는 아군(플레이어 우선, 부족하면 조력 NPC)의 연락처를 즉시 입수한다.",
            "count=즉시 입수할 연락처 수(1~3), uses=스테이지당 횟수(1~2)"),
        FORCE_ENCOUNTER("force_encounter", true,
            "발동 시 진행을 돕는 조력 NPC 1명을 자신과 같은 구역에 확정 등장(조우)시킨다. 괴담 본체는 대상 아님.",
            "uses=스테이지당 횟수(1~2)"),
        DECOY("decoy", true,
            "발동 시 괴담/위협의 다음 표적·추적을 다른 대상(미끼)으로 돌린다. 자신은 잠시 직접 표적에서 벗어난다.",
            "uses=스테이지당 횟수(1~2)"),
        DELAY("delay", true,
            "발동 시 다가오던 파국 이벤트나 괴담의 다음 위협 행동을 몇 턴 지연시킨다(무효 아님, 미뤄짐).",
            "turns=지연 턴수(1~2), uses=스테이지당 횟수(1~2)"),
        ONE_WAY_CALL("one_way_call", true,
            "발동 시 지정한 아군 1명에게 ★일방적으로★ 말을 전한다(거리·연락처·통신차단 무관, 답신 불가, 소리 아님). 턴을 소모하지 않는다.",
            "uses=스테이지당 횟수(1~3)"),
        TELEPORT("teleport", true,
            "발동 시 무작위 구역·아군 위치·NPC 위치로 순간이동한다(아직 안 가본 곳도 갈 수 있다).",
            "uses=스테이지당 횟수(1~2)"),
        RALLY("rally", true,
            "발동 시 흩어진 아군들을 자신의 현재 위치로 불러모은다(파티 재집결).",
            "uses=스테이지당 횟수(1~2)"),
        EVADE_SENSE("evade_sense", true,
            "발동 시 N턴간 괴담의 감지(perception 양식 전부 — 청각·시각·통신·전지 등)에서 벗어난다. 그동안 괴담은 자신을 직접 표적·추적하지 못한다.",
            "turns=지속 턴수(1~3), uses=스테이지당 횟수(1~2)"),
        OBSERVER_SIGHT("observer_sight", true,
            "발동 시 '무대 뒤(연출자)의 현재 사고'를 엿본다 — 지금 이 순간 무슨 의도로 일이 굴러가는지. 전체 각본·정답은 제외, 현재 사고만.",
            "uses=스테이지당 횟수(1~2)"),
        PACT("pact", true,
            "발동 시 괴담과 1회 거래를 시도한다 — 대가(체력·정신력·단서 등)를 치르고 양보 1개를 얻는다. 고위험. GM이 거래를 판정·서술한다.",
            "uses=스테이지당 횟수(1)"),
        PAST_EDIT("past_edit", true,
            "발동 시 자신이 한 과거 행동 1개를 다른 것으로 개찬한다 — 인과가 바뀐다(정답 날조 불가, GM이 개연성 판정).",
            "uses=스테이지당 횟수(1)"),
        GDAM_MORPH("gdam_morph", true,
            "발동 시 N턴간 무작위 괴담으로 변신한다 — 그 괴담 본성대로 행동(★조작 불가, GM이 구동, 피아식별 없음). 난장판+통제 상실이 곧 대가.",
            "turns=변신 지속 턴수(1~3), uses=스테이지당 횟수(1)"),
        PHASE_OUT("phase_out", true,
            "발동 시 N턴간 턴을 건너뛰며 아무 간섭도 받지 않는다(위상 이탈). 종료 시 극적 탈출(건물 폭파 등)이 가능하다.",
            "turns=지속 턴수(1~3), uses=스테이지당 횟수(1)"),
        POSSESS_NPC("possess_npc", true,
            "발동 시 대상 NPC(적대 NPC 가능, 괴담 본체 불가)에 빙의한다 — 본체는 무방비로 남고(본체 사망 시 본인도 사망), NPC 몸으로 진행하며 그 NPC가 아는 정보를 모두 알게 된다(기록에 추가). 큰 피해·해제 선언 시 복귀.",
            "uses=스테이지당 횟수(1)"),
        MIMIC("mimic", true,
            "발동 시 지정한 아군의 대표 특성 1개를 이번 스테이지 동안 빌려 쓴다(복제본 획득). 스탯 보정은 빌리지 않고 능력만 모방.",
            "uses=스테이지당 횟수(1~2)"),
        NPC_BIND("npc_bind", true,
            "발동 시 대상 NPC를 '인연'으로 저장한다 — 다음 스테이지에 그 NPC가 아군으로 소환된다(1회).",
            "uses=스테이지당 횟수(1)"),
        GUARANTEED("guaranteed", true,
            "발동 시 다음 행동 1회를 확정 성공으로 처리한다(주사위·실패를 무시하고 GM이 성공으로 서술). 회피가 아니라 '반드시 성공'하는 결과 보장이다.",
            "uses=스테이지당 횟수(1~2), scope=확정 범위(1=단일 행동, 2=연관 행동 묶음, 3=상황 전체 국면)"),
        MOBILITY("mobility", true,
            "발동 시 이동·도주·지형 돌파에 강한 보정을 받는다(추격 회피·즉시 도달·막힌 길 우회). GM이 그 이동을 성공적으로 서술한다.",
            "power=보정 강도(1=유리, 2=상당, 3=거의 확정), uses=스테이지당 횟수(1~3)"),
        REMOTE_SENSE("remote_sense", true,
            "발동 후 대상을 입력하면 ★다른 구역(원격)의 정보를 감지한다(독순술·천리안·원격 투시). 현재 구역만 보는 area_scan과 달리 떨어진 곳을 본다.",
            "range=원격 범위(1=인접 구역, 2=같은 층, 3=전역), info=정보 깊이(0=암시, 1=부분, 2=중간, 3=핵심 근접), uses=스테이지당 횟수(1~2)"),
        FORESIGHT("foresight", true,
            "발동 후 의도한 다음 행동을 입력하면 그 행동의 예상 결과·분기를 미리 보여준다(예지·인생설계). 실제 판정 전에 전망만 제공한다.",
            "depth=예측 깊이(1=직후 결과만, 2=한 단계 분기, 3=여러 갈래·연쇄), uses=스테이지당 횟수(1~2)"),
        SOCIAL("social", true,
            "발동 시 대상 NPC의 호감·협조를 끌어내거나 설득·강제 접촉한다(친화·소통). GM이 NPC 반응을 우호적으로 조정한다.",
            "power=설득 강도(1=호의, 2=적극 협조, 3=강한 설득), uses=스테이지당 횟수(1~3)"),
        DOMINATE("dominate", true,
            "발동 시 NPC·하위 개체 1명을 짧게 제어해 1회 명령을 강제한다(지배). ★괴담 본체·핵심 존재에는 통하지 않는다.",
            "power=지배 강도(1=설득에 가까운 유도, 2=명백한 강제), uses=스테이지당 횟수(1)"),
        FATE("fate", true,
            "발동 시 직전 또는 다음 판정 1회의 결과를 유리하게 뒤집는다(운명). 최상위 희소 효과.",
            "uses=스테이지당 횟수(1)"),
        GROUP_REWIND("group_rewind", true,
            "발동 시 파티가 직전 국면으로 되감긴다(동반회귀). ★코드상으로는 재도전 제약을 해제한다 — 스테이지 3+에서 생존자가 없어도 재도전을 허용한다.",
            "uses=스테이지당 횟수(1)"),

        // ─── 패시브 효과 (active = false) ────────────────────────────────────────────
        // 정보 계열 패시브 — 시작 시 '직감'으로 특정 영역의 정보를 안다. AI가 자연스럽게 가공해 전달.
        //   공통 규칙: 정답·해결법은 절대 노출 금지. 약점은 등급이 매우 높을 때(S)만 '방향'까지. depth가 클수록 양·선명도↑.
        SCENARIO_INSIGHT("scenario_insight", false,
            "패시브(정보-시나리오 이해). 시작 시 사건의 '대략적 윤곽'(어떤 상황이고 무엇이 벌어지는지·분위기)을 직감으로 안다. 정체·정답·해결법은 제외.",
            "depth=파악 깊이(1=개략, 2=중간, 3=상세)"),
        ENTITY_SENSE("entity_sense", false,
            "패시브(정보-적대자 감지). 시작 시 적대 존재의 '유형·본질'을 직감으로 안다(예: 어떤 종류의 존재인지). 정확한 정체·이름은 제외. 등급이 매우 높으면 약점의 방향까지.",
            "depth=감지 깊이(1=유형 암시, 2=유형·성향, 3=상세 본질)"),
        ALLY_SENSE("ally_sense", false,
            "패시브(정보-구원자 탐지). 시작 시 도움이 될 만한 아군/조력 성향 NPC의 '이름과 현재 위치'를 직감으로 안다. 적대·위장 가능성 있는 인물은 제외.",
            "depth=탐지 범위(1=가장 가까운 1명, 2=2~3명, 3=알려진 조력자 다수)"),
        LORE_RECORD("lore_record", false,
            "패시브(정보-전지적 독자시점). 시작 시 '과거에 이 사건에 도전했다 실패한 이들의 이야기'를 안다(특히 규칙 위반·행동 제약으로 탈락한 사례 → 규칙을 간접적으로 노출). 정답 자체는 제외.",
            "depth=이야기 깊이(1=단편 일화, 2=구체 사례, 3=여러 사례·교훈)"),
        PASSIVE_GM("passive_gm", false,
            "패시브. effect에 적힌 상시 효과를 GM이 매 턴 항상 고려한다(예: 주인공 보호, 미묘한 이상 감지).",
            "(별도 파라미터 없음. 효과 내용은 effect 텍스트로 표현)"),
        PASSIVE_TRIGGER("passive_trigger", false,
            "패시브. effect에 적힌 조건이 GM 판단으로 충족되면 효과가 자동 발동된다. passive_gm(항상 고려)과 달리 트리거 조건이 명확하다. 예: '위험 직전 자동 경고', '거짓말 탐지 자동 발동', '아군 근처에 있으면 감지'. 조건·효과 모두 effect 텍스트에 서술한다.",
            "intensity=발동 효과 강도(1=미약, 2=중간, 3=강), trigger_freq=발동 빈도(1=드물게, 2=가끔, 3=자주)"),
        PROTECT("protect", false,
            "패시브. effect에 적힌 종류의 피해·효과를 자동으로 줄이거나 막는다. 보호 대상(물리·정신·초자연 등)은 effect 텍스트로 표현한다.",
            "power=방어력(1=소폭 경감, 2=절반 경감, 3=거의 무효화), uses=스테이지당 발동 횟수(0=무제한, 1~3=횟수 제한)"),
        DEATH_RELAY("death_relay", false,
            "패시브. 사망하는 순간, 자신이 '능력으로 밝혀낸 사실(중요 정보)'을 가까운 아군 1명에게 자동으로 전달한다.",
            "(파라미터 없음)"),
        FATAL_GUARD("fatal_guard", false,
            "패시브. '돌이킬 수 없는 1회성 치명 행동(즉사 규칙 위반 등)'을 저질러도 ★1회에 한해★ 그 결과를 무효화한다(아슬아슬하게 무위로). 이후엔 정상 판정.",
            "(파라미터 없음)"),
        ENCOUNTER_SCAN("encounter_scan", false,
            "패시브(정보-첫 조우). 시작 시 처음 마주칠 적대 존재·핵심 인물에 대한 짧은 직감을 얻는다. 정답·정체·해결법은 제외.",
            "depth=파악 깊이(1=암시, 2=중간, 3=상세)"),
        REVIVE_AS_ANIMAL("revive_as_animal", false,
            "패시브. 사망 시 1회 주변 동물로 되살아난다 — 정찰·방해·몸짓 정보전달 가능(GM 서술), 능력·아이템·통신 불가, 괴담은 정체 인지 못함. 휘말리거나 공격받으면 높은 확률로 사망.",
            "(파라미터 없음)");

        public final String  key;
        public final boolean active;
        public final String  whatItDoes;
        public final String  paramHint;

        Effect(String key, boolean active, String whatItDoes, String paramHint) {
            this.key = key; this.active = active;
            this.whatItDoes = whatItDoes; this.paramHint = paramHint;
        }

        public static Effect byKey(String key) {
            if (key == null) return null;
            for (Effect e : values()) if (e.key.equals(key)) return e;
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  AI 프롬프트용 카탈로그
    // ──────────────────────────────────────────────────────────────

    /** AI 특성 생성 프롬프트에 삽입할 효과 프리미티브 카탈로그 */
    public static String buildAiCatalog() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 시스템 효과(effect_type) — 선택적 기계 효과\n");
        sb.append("특성에 아래 기계 효과 중 하나를 부여할 수 있다(없어도 됨; 없으면 GM 서술로만 처리).\n");
        sb.append("부여하려면 effect_type에 키를, effect_params에 수치를 넣는다.\n");
        sb.append("같은 effect_type이라도 이름·등급·설명·쿨다운·파라미터로 다양한 변형을 만들 수 있다.\n\n");

        sb.append("=== 능동 효과 (active=true) ===\n");
        for (Effect e : Effect.values()) {
            if (!e.active) continue;
            sb.append("- ").append(e.key).append(": ").append(e.whatItDoes)
              .append("\n  파라미터: ").append(e.paramHint).append("\n");
        }
        sb.append("\n=== 패시브 효과 (active=false) ===\n");
        for (Effect e : Effect.values()) {
            if (e.active) continue;
            sb.append("- ").append(e.key).append(": ").append(e.whatItDoes)
              .append("\n  파라미터: ").append(e.paramHint).append("\n");
        }

        sb.append("\n## ★ 등급 밸런스 가이드 (반드시 준수) ★\n");
        sb.append("effect_type과 파라미터 조합에 따른 등급 기준. 이 기준을 벗어나지 않는다.\n\n");

        sb.append("## ★ 등급 = '스텟 + 패시브 + 발동형' 3요소 파워의 총합 ★\n");
        sb.append("한 특성의 등급은 아래 세 종류 파워의 '합계'다. 한 종류에 몰아도, 섞어도 된다.\n");
        sb.append(" ① 스텟 파워 — 능력치 상승. 양의 보정 총합 예산: F=-2  E=-1  D=0  C=1  B=3  A=5  S=10.\n");
        sb.append("     · 다른 스탯에 -를 주면 그만큼 +를 더(순합 기준). 순수 -결점형도 가능(약체·개성).\n");
        sb.append("     · hp_max/san_max(체력·정신력 최대치)도 다른 스텟과 ★동일하게 1점=1로 계산(플레이어 표시는 %지만 예산은 동일).\n");
        sb.append("     · E/F등급(순값 -1/-2): 단점·제약이 많은 고위험 특성. 단 큰 단점으로 상쇄해 '좋은 효과 1개'를 달 수 있다.\n");
        sb.append(" ② 패시브 파워 — 상시/조건부 자동 효과. active=false. effect_type: passive_gm·passive_trigger·protect·scenario_insight.\n");
        sb.append(" ③ 발동형 파워 — 플레이어가 직접 발동(버튼). active=true. effect_type: ai_query·area_scan·instant_clear·guaranteed·mobility 등.\n");
        sb.append("★ 등급 = ①+②+③ 합산. ★ 기계효과(②/③)는 한 특성에 '하나만'(패시브 또는 발동형 택1) + 거기에 스텟을 더하는 식.\n");
        sb.append("★ 그 등급을 '단독으로 꽉 채우는' 강한 기계효과(예: instant_clear·revive_ally·fate·group_rewind·choice_action(4)·주인공급 passive_gm)를 쓰면 스텟은 0~1만 준다(합이 등급을 넘지 않게). 스텟을 크게 주려면 기계효과를 그만큼 약하게.\n");
        sb.append("   예) 순수 스텟 A급: stat 합 5, 기계효과 없음.\n");
        sb.append("   예) 순수 발동형 S급: instant_clear 하나, stat 0.\n");
        sb.append("   예) 혼합 B급: 약한 패시브(육감) + stat +1 → 합쳐서 B.\n");
        sb.append("   예) 혼합 A급: 중간 발동형(area_scan scope=2) + stat +1.\n");
        sb.append("아래 effect_type 등급표는 '그 기계효과 단독(스텟 0) 기준'이다. 스텟을 더하면 그만큼 기계효과를 낮춰 합을 맞춘다.\n\n");

        sb.append("## ★★ 능력 코스트 & 예산 (반드시 이 안에서 설계) ★★\n");
        sb.append("등급 예산(점): S=10 A=5 B=3 C=1 D=0 E=-1 F=-2.  규칙: ★(능력 코스트 + 양의 스텟 합) ≤ 등급 예산★.\n");
        sb.append("능력 기본 코스트(제약 없을 때, 등급 예산과 같은 점수):\n");
        sb.append("  · 10점: instant_clear · revive_ally · fate · group_rewind · dominate(power2) · choice_action(choices4) · ai_query(info3·uses2+) · sacrifice(scale3) · passive_trigger(intensity3·freq3)\n");
        sb.append("  · 5점 : gm_directive · ai_query(info3·uses1) · luck_roll(scale10+) · area_scan(scope3) · link_ally(depth3) · protect(power3) · guaranteed(scope3) · mobility(power3) · remote_sense(range3&info3) · foresight(depth3) · social(power3) · dominate(power1) · sacrifice(scale2)\n");
        sb.append("  · 3점 : passive_gm · show_progress · scenario_insight·entity_sense·ally_sense·lore_record(depth2~3) · area_scan(scope2) · passive_trigger(intensity2) · protect(power2) · luck_roll(scale5~9) · link_ally(depth2) · choice_action(choices2~3) · guaranteed · mobility(power2) · remote_sense · foresight · social(power1~2) · ai_query(info1~2)\n");
        sb.append("  · 1점 : ai_query(info1·uses1) · scenario_insight·entity_sense·ally_sense·lore_record(depth1) · protect(power1) · area_scan(scope1) · luck_roll(scale≤4) · link_ally(depth1) · passive_trigger(intensity1)\n");
        sb.append("★ 제약을 걸수록 코스트가 싸진다(할인): 스테이지당 1회(cooldown_turns=-1) −3 · 쿨다운 5턴+ −2 · 쿨다운 2턴+ −1 · 최소 횟수(uses=1) −1. (능력은 최소 1점)\n");
        sb.append("★ 단점(음의 스텟)을 주면 그만큼 예산이 늘어난다(상쇄). 예: B(3) 특성에 매력 −2 → 예산 5로 늘어 A급 능력 1개 탑재 가능.\n");
        sb.append("→ 강한 능력을 낮은 등급에 넣고 싶으면 쿨다운·1회성·횟수제한·단점스텟으로 코스트를 예산 안에 맞춰라. ");
        sb.append("못 맞추면 시스템이 자동으로 제약을 추가하고, 그래도 넘치면 능력을 약화·제거한다(설계가 헛수고가 됨).\n\n");

        sb.append("[S등급] 스테이지 전체를 뒤바꿀 수 있는 결정적 효과. 아주 희소하게 배정.\n");
        sb.append("  · instant_clear (스테이지 즉시 생존 클리어)\n");
        sb.append("  · revive_ally (완전 부활·전회복)\n");
        sb.append("  · choice_action(choices=4) — 확정 4지선다 행동 선택\n");
        sb.append("  · ai_query(info=3, uses>=2) — 핵심 정보 다수 반복 획득\n");
        sb.append("  · passive_gm (주인공급 — 불리한 상황에서 '반드시 돌파구'가 보장)\n");
        sb.append("  · sacrifice(scale=3) — 극적 대가로 극적 혜택\n");
        sb.append("  · passive_trigger(intensity=3, trigger_freq=3) — 강하고 잦은 자동 발동\n");
        sb.append("  · fate (직전/다음 판정 1회 유리하게 뒤집기 — 운명)\n");
        sb.append("  · group_rewind (직전 국면으로 동반회귀 — 재도전 제약 해제)\n");
        sb.append("  · dominate(power=2) — NPC·하위 개체 1회 명령 강제(지배)\n\n");

        sb.append("[A등급] 스테이지에 강한 영향을 미치는 핵심 효과. 표준 상한.\n");
        sb.append("  · ai_query(info=3, uses=1) — 핵심 근접 질문 1회\n");
        sb.append("  · luck_roll(scale>=10) — 강한 운 보정\n");
        sb.append("  · gm_directive (강력 NPC·사건 강제)\n");
        sb.append("  · area_scan(scope=3, uses=2) — 광역 고빈도 탐색\n");
        sb.append("  · link_ally(depth=3) — 소통 경로 발견 포함\n");
        sb.append("  · passive_trigger(intensity=3, trigger_freq=2) — 강하고 가끔 발동\n");
        sb.append("  · protect(power=3) — 거의 무효화 방어\n");
        sb.append("  · sacrifice(scale=2, cost<=10) — 합리적 대가로 상당한 혜택\n");
        sb.append("  · guaranteed(scope=3) — 상황 전체 국면을 확정 성공으로\n");
        sb.append("  · mobility(power=3) — 거의 확정적인 이동·도주·돌파\n");
        sb.append("  · remote_sense(range=3, info=3) — 전역 원격 감지로 핵심 근접 정보\n");
        sb.append("  · foresight(depth=3) — 여러 갈래·연쇄 결과 예지\n");
        sb.append("  · social(power=3) — 강한 설득·강제 접촉\n");
        sb.append("  · dominate(power=1) — 유도에 가까운 약한 지배\n\n");

        sb.append("[B등급] 유용하지만 조건·횟수 제한이 명확하다. 가장 흔한 등급.\n");
        sb.append("  · ai_query(info=1~2, uses=1~2) — 부분 정보\n");
        sb.append("  · show_progress — 진행도 확인\n");
        sb.append("  · scenario_insight(depth=2~3) — 구조 파악\n");
        sb.append("  · area_scan(scope=2, uses=1~2) — 중범위 탐색\n");
        sb.append("  · passive_gm (구체적 상황 힌트, 제한적 보호)\n");
        sb.append("  · passive_trigger(intensity=2) — 중간 자동 발동\n");
        sb.append("  · protect(power=2) — 절반 경감 방어\n");
        sb.append("  · luck_roll(scale=5~9) — 중간 운 보정\n");
        sb.append("  · link_ally(depth=2) — 아군 상태 파악·소통 실마리\n");
        sb.append("  · choice_action(choices=2~3, uses=1) — 기본 선택지\n");
        sb.append("  · guaranteed(uses=1) — 단일 행동 1회 확정 성공\n");
        sb.append("  · mobility(power=2) — 상당한 이동·도주 보정\n");
        sb.append("  · remote_sense(range=1~2) — 인접·동층 원격 감지\n");
        sb.append("  · foresight(depth=1~2) — 직후 결과·한 단계 분기 예측\n");
        sb.append("  · social(power=1~2) — NPC 호의·협조 유도\n\n");

        sb.append("[C등급] 제한적 상황에서만 가치 있음, 또는 부작용 동반.\n");
        sb.append("  · ai_query(info=1, uses=1) — 모호한 암시 1회\n");
        sb.append("  · scenario_insight(depth=1) — 개략 구조만\n");
        sb.append("  · passive_gm (매우 약하거나 조건이 좁은 상시 효과)\n");
        sb.append("  · passive_trigger(intensity=1, trigger_freq=1~2) — 미약하고 드문 발동\n");
        sb.append("  · protect(power=1) — 소폭 경감\n");
        sb.append("  · area_scan(scope=1, uses=1) — 현재 위치 1회 탐색\n");
        sb.append("  · luck_roll(scale<=4) — 약한 운 보정\n");
        sb.append("  · link_ally(depth=1) — 생존 여부만 확인\n\n");

        sb.append("[D등급] 효과보다 제약·대가가 크거나 극히 상황 한정. 개성 위주.\n");
        sb.append("  · sacrifice(scale=1, cost>=15) — 높은 대가, 작은 혜택\n");
        sb.append("  · effect_type=\"\" (일반 특성) 에 약한 버프 서술\n");
        sb.append("[E·F등급] 디버프·고제약 (순값 E≈-1, F≈-2). 단점·제약이 핵심이되 그 대가로 '쓸 만한 효과 1개'를 달 수 있다(고위험 트레이드오프).\n");
        sb.append("  · 예) F '저주받은 눈'(area_scan은 강하나 발동마다 san 크게 감소) / E '폭주'(힘 크게 + 매·영 크게 감소)\n");
        sb.append("  · 디버프만 있는 순수 결점형(예: 만성 부상 = hp_max 큰 -)도 가능. ★좋은 효과를 달면 반드시 그보다 큰 단점·제약으로 상쇄.\n\n");

        sb.append("★ 통찰 계열 등급 예시 (같은 개념, 다른 effect_type과 등급):\n");
        sb.append("  C: passive_gm, effect=\"주변의 눈에 띄는 사소한 변화를 자연스럽게 알아챈다\" → '관찰안'\n");
        sb.append("  B: passive_trigger(intensity=2), effect=\"위험 징후가 나타나면 자동으로 경고를 받는다\" → '위기감지'\n");
        sb.append("  A: link_ally(depth=2), effect=\"감각으로 아군의 위치와 상태를 파악하고 소통 실마리를 찾는다\" → '소통탐지'\n");
        sb.append("  A: area_scan(scope=3, uses=2), effect=\"광역 탐색으로 숨겨진 위험·단서를 발견한다\" → '공간인식'\n\n");

        sb.append("## ★ ai_query vs gm_directive 선택 규칙 (매우 중요) ★\n");
        sb.append("- ai_query: '플레이어가 직접 질문 내용을 타이핑'하는 경우만 사용.\n");
        sb.append("  예) '기도자' — 스테이지당 2회 GM에게 원하는 질문 가능 → ai_query(uses=2, info=1)\n");
        sb.append("- gm_directive: '캐릭터가 자동으로 기억·직관·환각·예지 등을 경험'하는 경우에 반드시 사용.\n");
        sb.append("  AI가 effect 텍스트 내용을 바탕으로 그 기억/경험 장면을 생생하게 서술한다.\n");
        sb.append("  예) '흐릿한 기억 더듬기' — 형과의 과거에서 정보 하나를 떠올린다 → gm_directive\n");
        sb.append("  예) '직관의 섬광' — 위기 직전 번뜩이는 예감 → gm_directive\n");
        sb.append("  예) '악몽의 잔상' — 지난 악몽에서 단서 한 조각 → gm_directive\n");
        sb.append("★ 이 구분을 절대 무시하지 말 것. 회상·직관·내면 경험형 특성에 ai_query를 쓰면 치명적 버그 발생.\n\n");

        sb.append("## ★ 발동 방식 3분류 (자동/수동/상태조건) — 능력 표현 기준 ★\n");
        sb.append("능력의 '발동 조건'은 아래 셋 중 하나로 표현한다(소설식 '자동/수동/상태조건'과 동일):\n");
        sb.append("  · 수동(직접 발동): active=true. 플레이어가 버튼으로 발동. 예) 질문권·즉시판정.\n");
        sb.append("  · 자동(상시): effect_type=passive_gm, active=false. 매 턴 항상 작동. 예) 행운가호·현대지식 우위.\n");
        sb.append("  · 상태조건(조건충족 시 자동): effect_type=passive_trigger, active=false. effect에 '언제 발동되는지' 명시.\n");
        sb.append("    예) '위험 직전 자동 경고'(육감), '거짓을 들으면 위화감'(직감), '아군이 가까이 있으면 감지'.\n\n");

        sb.append("## ★ protect 확장 — 피해뿐 아니라 '상태·변화 저항'도 포함 ★\n");
        sb.append("protect는 물리·정신 피해 경감 외에, effect 텍스트로 '상태이상·외부 변화·간섭 저항'까지 표현한다.\n");
        sb.append("  예) 불변(불변성): protect(power=3), effect=\"상태 변화·외부 간섭을 거의 무효화한다\"\n");
        sb.append("  예) 용기(공포 저항): protect(power=2), effect=\"공포·정신 동요에 저항한다\"\n\n");

        sb.append("## ★ 다양한 능력을 '기존 프리미티브'로 표현하는 예시 (소설풍 변주) ★\n");
        sb.append("새 능력을 만들 때 아래처럼 기존 effect_type에 매핑해 변주한다(이름·등급·수치는 자유):\n");
        sb.append("  · 행운의 가호(상시 운) → passive_gm, effect=\"중요한 순간 운이 따른다\"\n");
        sb.append("  · 현대인의 지식(상식 우위) → passive_gm, effect=\"현대 지식으로 상황을 빠르게 이해한다\"\n");
        sb.append("  · 클리셰 발현(전개 유도) → gm_directive, effect=\"호러 전개의 정석을 끌어낸다\"\n");
        sb.append("  · 정의감(부당함에 분기) → passive_trigger, effect=\"부당한 위협 앞에서 힘이 솟는다\"\n");
        sb.append("  · 통찰·지혜(구조 파악) → scenario_insight(depth) 또는 ai_query(info)\n");
        sb.append("  · 끈기·인내(상태 저항) → protect(power=1~2), effect=\"고통·압박을 견뎌낸다\"\n\n");

        sb.append("## ★ 개인화 발현 (origin) ★\n");
        sb.append("각 특성 JSON에 \"origin\"을 넣어 '이 캐릭터에게 이 능력이 발현된 계기'를 한 줄로 적는다(유산·성향·과거).\n");
        sb.append("  같은 효과라도 캐릭터마다 다르게 발현된다. 예) \"돌아가신 형이 남긴 습관\", \"오랜 길거리 생활의 직감\".\n");
        sb.append("  ★ 스포일러 금지(괴담 소재 누설 금지). 일반·범용 특성이면 \"\"로 둔다.\n\n");

        sb.append("## ★★ 특성 작성 가이드라인 (반드시 따른다) ★★\n");
        sb.append("[규칙1 — 반영 방식] 효과 종류별로 반영이 다르다(이중 반영 금지):\n");
        sb.append("  · 패시브(passive_gm/passive_trigger)·발동형 효과 → GM이 행동·전개에 실제로 반영(시스템이 주입).\n");
        sb.append("  · 스텟만 주는 부분 → 능력치 수치만 올린다. ★'행동을 더 잘한다'식 보정 텍스트로 또 쓰지 마라(스텟에 이미 반영됨).\n");
        sb.append("  · ★행동을 더 잘하게 하려면 그 부분은 '반드시 패시브'로 만들어라(effect_type=passive_gm/passive_trigger).\n");
        sb.append("    effect_type=\"\"인데 effect에 '~를 잘한다'고만 쓰면 실제로는 반영되지 않는다(장식일 뿐, 스텟만 적용).\n");
        sb.append("[규칙2 — 제약으로 강화, 등급 상한] 대상·조건·범위를 좁히면(제약) 그 좁은 영역에서 효과를 끌어올릴 수 있다.\n");
        sb.append("  · 단 상한: 현재 등급보다 '조금 더 좋은 것 ~ 최대 한 단계 위' 효과까지만. 등급을 넘는 위력 금지.\n");
        sb.append("  · 예) D급 '자물쇠 따기': 대상을 '상자'로 한정하는 대신 '대부분의 상자'를 열 수 있다(좁힌 영역 내 C~B 위력).\n");
        sb.append("  · S급은 예외적으로 '초월적' 효과를 허용한다(영역 제약 없이 강력).\n");
        sb.append("[규칙3 — 생성 절차] 아래 순서로 무작위 설계한다:\n");
        sb.append("  ① 메인 분류를 무작위로: 스텟 / 패시브 / 발동형 중 하나.\n");
        sb.append("  ② 단일/복합: 단일=메인 한 종류만. 복합=메인+보조(예: 패시브+스텟).\n");
        sb.append("  ③ 만능/특화: 만능=넓고 두루(여러 대상·여러 스탯). 특화=한 곳에 집중(좁고 강함).\n");
        sb.append("  ④ 최종 스텟 설계: 등급 예산(F=-2·E=-1·D=0·C=1·B=3·A=5·S=10) 안에서 ①~③ 선택에 맞게 배분.\n");
        sb.append("  같은 A급 예시: [스텟 특화형]'강력한 힘' 힘+5  /  [패시브·스텟 복합 만능형]'농구선수' 힘+2 & (패시브)던지기를 더 잘한다  /  [스텟 단일 만능형]'다재무능' 모든 스탯 +1\n");
        sb.append("  ★ ①~③ 분류·단일복합·만능특화·등급은 모두 '설계용 내부 개념'이다 — 특성 이름·설명·effect에 이 분류 용어를 ★절대 노출하지 마라(플레이어는 모른다). 이름은 '강철 주먹'처럼 자연스럽게 짓는다.\n\n");

        sb.append("## 규칙\n");
        sb.append("- 능동 효과는 active=true, 패시브(scenario_insight/passive_gm/passive_trigger/protect)는 active=false.\n");
        sb.append("- 스테이지당 여러 번 쓰는 효과(ai_query/area_scan 등)는 cooldown_turns=0, uses 파라미터로 제한.\n");
        sb.append("- 1회성 강력 효과(instant_clear/revive_ally/gm_directive 등)는 cooldown_turns=-1(스테이지당 1회) 권장.\n");
        sb.append("- 일반 특성(기계 효과 불필요)은 effect_type=\"\"로 둔다.\n");
        sb.append("- effect_type 남용 금지: 한 세션 전체에서 S등급 1~2개, A등급 2~3개 이내로 균형을 맞춘다.\n");
        sb.append("- 같은 플레이어에게 연속 고등급 특성 지양. 전체 파티 밸런스를 고려하라.\n");
        return sb.toString();
    }

    /** JSON 스키마 문자열 조각 (생성 프롬프트의 스키마에 추가) */
    public static final String SCHEMA_FIELDS = "\"effect_type\":\"\",\"effect_params\":{}";

    // ──────────────────────────────────────────────────────────────
    //  파라미터 기본값/검증
    // ──────────────────────────────────────────────────────────────

    /** effectType에 맞춰 능/수동을 강제하고 누락된 파라미터에 기본값을 채운다 */
    public static void applyDefaults(TraitData td) {
        if (td == null) return;
        Effect e = Effect.byKey(td.effectType);
        if (e == null) { td.effectType = ""; enforcePowerBudget(td); return; } // 순수 스텟도 예산 적용
        td.active = e.active;
        if (td.effectParams == null) td.effectParams = new HashMap<>();
        switch (e) {
            case AI_QUERY -> {
                td.effectParams.putIfAbsent("uses", 2);
                td.effectParams.putIfAbsent("info", 1);
                td.effectParams.putIfAbsent("auto_fire", 0);
                clamp(td, "uses", 1, 3); clamp(td, "info", 1, 3); clamp(td, "auto_fire", 0, 1);
            }
            case INSTANT_CLEAR, REVIVE_ALLY, GM_DIRECTIVE -> {
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "uses", 1, 3);
            }
            case LUCK_ROLL -> {
                td.effectParams.putIfAbsent("scale", 10);
                td.effectParams.putIfAbsent("dice", 6);
                clamp(td, "scale", 2, 30); clamp(td, "dice", 2, 20);
            }
            case CHOICE_ACTION -> {
                td.effectParams.putIfAbsent("uses", 1);
                td.effectParams.putIfAbsent("choices", 3);
                clamp(td, "uses", 1, 3); clamp(td, "choices", 2, 4);
            }
            case SCENARIO_INSIGHT, ENTITY_SENSE, ALLY_SENSE, LORE_RECORD, ENCOUNTER_SCAN -> {
                td.effectParams.putIfAbsent("depth", 2);
                clamp(td, "depth", 1, 3);
            }
            case OBSERVER_SIGHT -> {
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "uses", 1, 2);
            }
            case PACT, PAST_EDIT, POSSESS_NPC, NPC_BIND -> {
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "uses", 1, 1);
            }
            case GDAM_MORPH, PHASE_OUT -> {
                td.effectParams.putIfAbsent("turns", 2);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "turns", 1, 3); clamp(td, "uses", 1, 1);
            }
            case AREA_SCAN -> {
                td.effectParams.putIfAbsent("scope", 2);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "scope", 1, 3); clamp(td, "uses", 1, 3);
            }
            case SACRIFICE -> {
                td.effectParams.putIfAbsent("cost", 10);
                td.effectParams.putIfAbsent("use_san", 0);
                td.effectParams.putIfAbsent("scale", 2);
                clamp(td, "cost", 1, 20);
                clamp(td, "use_san", 0, 1);
                clamp(td, "scale", 1, 3);
            }
            case LINK_ALLY -> {
                td.effectParams.putIfAbsent("uses", 1);
                td.effectParams.putIfAbsent("depth", 1);
                clamp(td, "uses", 1, 2); clamp(td, "depth", 1, 3);
            }
            case GET_CONTACTS -> {
                td.effectParams.putIfAbsent("count", 1);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "count", 1, 3); clamp(td, "uses", 1, 2);
            }
            case FORCE_ENCOUNTER, DECOY, TELEPORT, RALLY, MIMIC -> {
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "uses", 1, 2);
            }
            case ONE_WAY_CALL -> {
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "uses", 1, 3);
            }
            case EVADE_SENSE -> {
                td.effectParams.putIfAbsent("turns", 2);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "turns", 1, 3); clamp(td, "uses", 1, 2);
            }
            case DELAY -> {
                td.effectParams.putIfAbsent("turns", 1);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "turns", 1, 2); clamp(td, "uses", 1, 2);
            }
            case GUARANTEED -> {
                td.effectParams.putIfAbsent("uses", 1);
                td.effectParams.putIfAbsent("scope", 1);
                clamp(td, "uses", 1, 2); clamp(td, "scope", 1, 3);
            }
            case MOBILITY -> {
                td.effectParams.putIfAbsent("power", 2);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "power", 1, 3); clamp(td, "uses", 1, 3);
            }
            case REMOTE_SENSE -> {
                td.effectParams.putIfAbsent("range", 2);
                td.effectParams.putIfAbsent("info", 1);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "range", 1, 3); clamp(td, "info", 0, 3); clamp(td, "uses", 1, 2);
            }
            case FORESIGHT -> {
                td.effectParams.putIfAbsent("depth", 2);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "depth", 1, 3); clamp(td, "uses", 1, 2);
            }
            case SOCIAL -> {
                td.effectParams.putIfAbsent("power", 2);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "power", 1, 3); clamp(td, "uses", 1, 3);
            }
            case DOMINATE -> {
                td.effectParams.putIfAbsent("power", 1);
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "power", 1, 2); clamp(td, "uses", 1, 1);
            }
            case FATE -> {
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "uses", 1, 1);
            }
            case GROUP_REWIND -> {
                td.effectParams.putIfAbsent("uses", 1);
                clamp(td, "uses", 1, 1);
            }
            case PASSIVE_TRIGGER -> {
                td.effectParams.putIfAbsent("intensity", 2);
                td.effectParams.putIfAbsent("trigger_freq", 2);
                clamp(td, "intensity", 1, 3); clamp(td, "trigger_freq", 1, 3);
            }
            case PROTECT -> {
                td.effectParams.putIfAbsent("power", 2);
                td.effectParams.putIfAbsent("uses", 0);
                clamp(td, "power", 1, 3); clamp(td, "uses", 0, 3);
            }
            default -> {}
        }
        enforcePowerBudget(td); // 파라미터 확정 후: 능력 코스트 + 스텟 합을 등급 예산에 맞춰 강제
    }

    private static void clamp(TraitData td, String key, int lo, int hi) {
        int v = td.param(key, lo);
        td.effectParams.put(key, Math.max(lo, Math.min(hi, v)));
    }

    /** 등급별 총 파워 예산(점). '능력 코스트 + 양의 스텟 합'이 이 예산을 넘지 못한다. */
    private static int gradeBudget(String grade) {
        return switch (grade == null ? "" : grade) {
            case "S" -> 10; case "A" -> 5; case "B" -> 3; case "C" -> 1;
            case "E" -> -1; case "F" -> -2; default -> 0; // D=0; E/F 음수
        };
    }

    /**
     * 능력(effect_type) 코스트 — 등급 예산과 같은 점수 단위.
     * 파라미터(범위·강도·횟수·정보깊이)가 클수록 비싸고, 제약(쿨다운·1회성·소수 횟수)을 걸수록 싸진다.
     * effect_type이 없으면(순수 스텟) 0. 능력이 있으면 최소 1.
     */
    private static int abilityCost(TraitData td) {
        Effect e = Effect.byKey(td.effectType);
        if (e == null) return 0;
        int uses = td.param("uses", 1);
        int base = switch (e) {
            case INSTANT_CLEAR, REVIVE_ALLY, FATE, GROUP_REWIND -> 10;
            case DOMINATE         -> td.param("power", 1) >= 2 ? 10 : 5;
            case CHOICE_ACTION    -> td.param("choices", 3) >= 4 ? 10 : 3;
            case AI_QUERY         -> td.param("info", 1) >= 3 ? (uses >= 2 ? 10 : 5) : 3;
            case SACRIFICE        -> { int sc = td.param("scale", 2); yield sc >= 3 ? 10 : sc == 2 ? 5 : 1; }
            case PASSIVE_TRIGGER  -> { int it = td.param("intensity", 2), fq = td.param("trigger_freq", 2);
                                       yield it >= 3 ? (fq >= 3 ? 10 : 5) : it >= 2 ? 3 : 1; }
            case GM_DIRECTIVE     -> 5;
            case LUCK_ROLL        -> { int sc = td.param("scale", 10); yield sc >= 10 ? 5 : sc >= 5 ? 3 : 1; }
            case AREA_SCAN        -> { int sp = td.param("scope", 1); yield sp >= 3 ? 5 : sp >= 2 ? 3 : 1; }
            case LINK_ALLY        -> { int d = td.param("depth", 1); yield d >= 3 ? 5 : d >= 2 ? 3 : 1; }
            case GET_CONTACTS     -> td.param("count", 1) >= 3 ? 3 : 1;
            case FORCE_ENCOUNTER  -> 3;
            case DECOY            -> 3;
            case DELAY            -> td.param("turns", 1) >= 2 ? 5 : 3;
            case ONE_WAY_CALL     -> 1;
            case TELEPORT         -> 3;
            case RALLY            -> 3;
            case EVADE_SENSE      -> td.param("turns", 2) >= 3 ? 5 : 3;
            case DEATH_RELAY      -> 2;
            case FATAL_GUARD      -> 3; // 5→3: B등급부터 생성돼도 예산 초과로 무력화되지 않게
            case MIMIC            -> 5;
            case PROTECT          -> { int p = td.param("power", 2); yield p >= 3 ? 5 : p >= 2 ? 3 : 1; }
            case GUARANTEED       -> td.param("scope", 1) >= 3 ? 5 : 3;
            case MOBILITY         -> { int p = td.param("power", 2); yield p >= 3 ? 5 : p >= 2 ? 3 : 1; }
            case REMOTE_SENSE     -> (td.param("range", 2) >= 3 && td.param("info", 1) >= 3) ? 5 : 3;
            case FORESIGHT        -> td.param("depth", 2) >= 3 ? 5 : 3;
            case SOCIAL           -> td.param("power", 2) >= 3 ? 5 : 3;
            case SCENARIO_INSIGHT, ENTITY_SENSE, ALLY_SENSE, LORE_RECORD, ENCOUNTER_SCAN -> td.param("depth", 1) >= 2 ? 3 : 1;
            case OBSERVER_SIGHT   -> 5;
            case PACT             -> 5;
            case PAST_EDIT        -> 5;
            case GDAM_MORPH       -> 5;
            case PHASE_OUT        -> td.param("turns", 2) >= 3 ? 10 : 5;
            case REVIVE_AS_ANIMAL -> 3; // 5→3: B등급부터 생성돼도 무력화되지 않게
            case POSSESS_NPC      -> 10;
            case NPC_BIND         -> 5;
            default               -> 3; // passive_gm·show_progress 등 텍스트 의존 = 기본 B
        };
        int discount = 0;
        if (td.cooldownTurns == -1)     discount += 3; // 스테이지당 1회 = 가장 큰 제약
        else if (td.cooldownTurns >= 5) discount += 2;
        else if (td.cooldownTurns >= 2) discount += 1;
        if (uses == 1)                  discount += 1; // 최소 횟수 제한(1회). uses=0(무제한 패시브)은 할인 제외.
        return Math.max(1, base - discount);
    }

    /** 코스트가 예산을 넘을 때 가장 강한 파라미터를 1 낮춘다. 더 낮출 게 없으면 false. */
    private static boolean reduceOneParam(TraitData td) {
        if (td.effectParams == null || td.effectParams.isEmpty()) return false;
        String[] order = {"choices","scale","power","scope","depth","intensity","range","trigger_freq","turns","count","info","uses"};
        for (String k : order) {
            Integer v = td.effectParams.get(k);
            int min = switch (k) { case "choices", "scale" -> 2; case "info" -> 0; default -> 1; };
            if (v != null && v > min) { td.effectParams.put(k, v - 1); return true; }
        }
        return false;
    }

    private static double posSum(TraitData td) {
        return Math.max(0, td.str_add) + Math.max(0, td.cha_add) + Math.max(0, td.luk_add)
             + Math.max(0, td.spr_add) + Math.max(0, td.hp_max_add) + Math.max(0, td.san_max_add);
    }
    private static double negSum(TraitData td) {
        return Math.max(0, -td.str_add) + Math.max(0, -td.cha_add) + Math.max(0, -td.luk_add)
             + Math.max(0, -td.spr_add) + Math.max(0, -td.hp_max_add) + Math.max(0, -td.san_max_add);
    }

    /**
     * 등급 예산 = 능력 코스트 + 양의 스텟 합 (음의 스텟 단점만큼 예산 추가).
     * 능력이 예산을 초과하면 ⓐ제약(쿨다운 -1) → ⓑ파라미터 축소 → ⓒ최후 효과 제거 순으로 맞추고,
     * 남은 예산으로 양의 스텟을 비례 축소한다. (멍청한 AI가 등급을 넘겨도 시스템이 강제 정렬)
     */
    private static void enforcePowerBudget(TraitData td) {
        int budget = gradeBudget(td.effectiveGrade()); // 실효(파워) 등급 기준 — 낮은 출신 강화 보너스 반영
        int neg = (int) negSum(td);
        int ec = abilityCost(td);
        if (ec > budget + neg && Effect.byKey(td.effectType) != null) {
            if (td.cooldownTurns != -1) { td.cooldownTurns = -1; ec = abilityCost(td); } // 제약 추가로 할인
            int guard = 0;
            while (ec > budget + neg && reduceOneParam(td) && guard++ < 24) ec = abilityCost(td);
            if (ec > budget + neg) { // 최소 형태로도 초과 → 등급에 안 맞는 강효과 제거
                td.effectType = ""; td.effectParams = new HashMap<>(); td.active = false; ec = 0;
            }
        }
        double statCap = Math.max(0, budget + neg - ec); // 능력이 쓴 만큼 빼고 남은 예산을 스텟에
        double pos = posSum(td);
        if (pos > statCap && pos > 0) {
            double s = statCap / pos;
            td.str_add     = scaleP(td.str_add, s);
            td.cha_add     = scaleP(td.cha_add, s);
            td.luk_add     = scaleP(td.luk_add, s);
            td.spr_add     = scaleP(td.spr_add, s);
            td.hp_max_add  = scaleP(td.hp_max_add, s);
            td.san_max_add = scaleP(td.san_max_add, s);
        }
    }
    /** 양수만 비례 축소(내림), 음수·0은 유지. */
    private static int scaleP(int v, double s) { return v > 0 ? (int) Math.floor(v * s) : v; }

    public static boolean isSystemEffect(TraitData td) {
        return td != null && td.hasSystemEffect() && Effect.byKey(td.effectType) != null;
    }

    /** 스테이지당 사용 횟수 상한 (uses 기반 효과만; 그 외 0=쿨다운/패시브가 관리) */
    public static int maxUsesPerStage(TraitData td) {
        Effect e = Effect.byKey(td.effectType);
        if (e == null) return 0;
        return switch (e) {
            case AI_QUERY, CHOICE_ACTION, GM_DIRECTIVE, REVIVE_ALLY,
                 INSTANT_CLEAR, AREA_SCAN, LINK_ALLY, SACRIFICE,
                 GUARANTEED, MOBILITY, REMOTE_SENSE, FORESIGHT,
                 SOCIAL, DOMINATE, FATE, GROUP_REWIND,
                 GET_CONTACTS, FORCE_ENCOUNTER, DECOY, DELAY, ONE_WAY_CALL,
                 TELEPORT, RALLY, EVADE_SENSE,
                 OBSERVER_SIGHT, PACT, PAST_EDIT, GDAM_MORPH, PHASE_OUT, POSSESS_NPC, MIMIC, NPC_BIND -> td.param("uses", 1);
            default -> 0;
        };
    }

    // ──────────────────────────────────────────────────────────────
    //  사전정의 프리셋 (관리자 /trpg givetrait 용)
    // ──────────────────────────────────────────────────────────────

    public record Preset(String id, String name, String grade, String description,
                         String effect, int cooldownTurns, String effectType,
                         Map<String, Integer> params) {
        public TraitData toTraitData() {
            TraitData td = new TraitData();
            td.id = id; td.name = name; td.grade = grade; td.description = description;
            td.effect = effect; td.cooldownTurns = cooldownTurns;
            td.effectType = effectType;
            td.effectParams = new HashMap<>(params);
            td.roleSpecific = false;
            applyDefaults(td);
            return td;
        }
    }

    private static Map<String, Integer> p(Object... kv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static final List<Preset> PRESETS = List.of(
        // === 능동 효과 프리셋 ===
        new Preset("sys_leaper", "도약자", "A", "즉시 생존 판정",
            "1회 발동 후 소멸. 현재 스테이지를 즉시 클리어하나 평가 등급이 최하로 고정된다.",
            -1, "instant_clear", p()),
        new Preset("sys_saint", "성녀", "A", "동료 부활·전회복",
            "다른 플레이어 1명의 체력·정신력·상태이상을 완전히 회복시키고 사망 시 부활시킨다.",
            -1, "revive_ally", p()),
        new Preset("sys_prayer", "기도자", "A", "질문권 ×2(약)",
            "스테이지당 2회 GM에게 질문 가능. 구체적일수록 명확한 힌트를 받는다.",
            0, "ai_query", p("uses", 2, "info", 1)),
        new Preset("sys_oracle", "신내림", "A", "통찰 ×1(강)",
            "스테이지당 1회 GM에게 질문해 핵심에 근접한 통찰을 얻는다.",
            -1, "ai_query", p("uses", 1, "info", 3)),
        new Preset("sys_luck", "행운", "A", "행운 주사위",
            "발동 시 주사위를 굴려 다음 행동 1회에 행운 보정을 받는다.",
            3, "luck_roll", p("scale", 10, "dice", 6)),
        new Preset("sys_shaman", "무당", "B", "괴담 진행도",
            "현재 괴담의 타임라인 단계와 진행 상황을 확인한다.",
            2, "show_progress", p()),
        new Preset("sys_choice", "계시", "S", "선택지 행동",
            "발동 후 다음 행동이 선택지로 제시된다. 정답 시 큰 보정, 오답 시 큰 패널티.",
            -1, "choice_action", p("uses", 1, "choices", 3)),
        new Preset("sys_encounter_a", "조우자", "A", "우군 NPC 조우",
            "발동 후 2턴 이내에 진행을 돕는 우군 NPC를 자연스럽게 등장시켜야 한다.",
            -1, "gm_directive", p()),
        new Preset("sys_encounter_s", "강령 조우자", "S", "강력 NPC 조우",
            "발동 후 다음 턴에 강력한 NPC(회귀자·SCP 재단원·영적 존재 등)를 등장시킨다. 우군 보장 없음.",
            -1, "gm_directive", p()),
        new Preset("sys_scanner_b", "현장감식", "B", "중범위 환경 탐색",
            "발동 후 탐색 목표를 입력하면 현재 층 전체의 숨겨진 단서·위험요소·인물을 탐색한다.",
            2, "area_scan", p("scope", 2, "uses", 1)),
        new Preset("sys_scanner_a", "공간인식", "A", "광역 환경 탐색",
            "발동 후 탐색 목표를 입력하면 건물 전체에서 숨겨진 정보·인물·위험 요소를 2회 탐색할 수 있다.",
            -1, "area_scan", p("scope", 3, "uses", 2)),
        new Preset("sys_blood_pact", "피의 계약", "B", "대가의 힘",
            "자신의 체력 10을 소모해 다음 행동에 상당한 보정을 얻는다.",
            0, "sacrifice", p("cost", 10, "use_san", 0, "scale", 2)),
        new Preset("sys_contact_radar", "소통탐지", "A", "아군 소통로 탐지",
            "발동 후 목표를 입력하면 다른 플레이어의 위치와 상태를 감각으로 파악하고 소통 수단을 발견한다.",
            -1, "link_ally", p("uses", 1, "depth", 2)),
        new Preset("sys_survivor_scan", "생존감지", "C", "아군 생존 확인",
            "다른 플레이어 전원의 생존 여부를 즉시 확인한다.",
            2, "link_ally", p("uses", 1, "depth", 1)),
        // === 패시브 효과 프리셋 ===
        new Preset("sys_insight", "시나리오 이해", "B", "시나리오 파악",
            "핵심 정보를 제외한 시나리오의 전체 구조를 처음부터 파악한 상태로 시작한다.",
            0, "scenario_insight", p("depth", 2)),
        new Preset("sys_protagonist", "주인공", "S", "운명의 중심",
            "중요 사건의 중심에 위치하며, 불리한 상황에서도 반드시 돌파구가 존재한다.",
            0, "passive_gm", p()),
        new Preset("sys_sixthsense", "육감", "B", "위험 자동 경고",
            "위험 상황이 다가오면 GM이 자동으로 경고를 발동한다. 대응 여부는 본인의 판단에 달렸다.",
            0, "passive_trigger", p("intensity", 2, "trigger_freq", 2)),
        new Preset("sys_observe", "관찰안", "C", "환경 관찰",
            "주변의 눈에 띄는 변화를 본능적으로 재인식하며, GM이 사소한 환경 단서를 자연스럽게 서술한다.",
            0, "passive_gm", p()),
        new Preset("sys_ward", "결계", "B", "정신 방어",
            "초자연적 공포·정신 공격에 대한 저항력을 가진다. 정신력 피해를 절반으로 경감한다.",
            0, "protect", p("power", 2, "uses", 0)),
        new Preset("sys_armor", "불굴", "C", "물리 내성",
            "물리적 피해를 소폭 경감한다. 스테이지당 최대 2회까지 발동한다.",
            0, "protect", p("power", 1, "uses", 2))
    );

    private static final Map<String, Preset> BY_ID;
    static {
        Map<String, Preset> m = new LinkedHashMap<>();
        for (Preset t : PRESETS) m.put(t.id(), t);
        BY_ID = Collections.unmodifiableMap(m);
    }

    public static Optional<Preset> getPreset(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<Preset> presets() { return PRESETS; }

    public static void printCatalog(org.bukkit.entity.Player player) {
        player.sendMessage("§e[시스템 특성 프리셋]");
        for (Preset t : PRESETS) {
            String cdStr = t.cooldownTurns() == -1 ? "스테이지당 1회"
                : t.cooldownTurns() > 0 ? "쿨다운 " + t.cooldownTurns() + "턴" : "없음";
            player.sendMessage("§f" + t.id() + " §7│ §e(" + t.grade() + ") §f" + t.name()
                + " §7— " + t.description() + " §8[" + cdStr + "]");
        }
    }
}
