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
            "발동 시 플레이어가 GM에게 직접 질문할 수 있다. 질문이 구체적일수록 더 정확히 답한다.",
            "uses=스테이지당 질문 횟수(1~3), info=정보 깊이(1=암시·적은정보, 2=중간, 3=핵심 근접)"),
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
            "발동 시 effect에 적힌 지시를 GM에게 전달해 사건 전개에 반영시킨다(예: 우군 NPC 등장).",
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

        // ─── 패시브 효과 (active = false) ────────────────────────────────────────────
        SCENARIO_INSIGHT("scenario_insight", false,
            "패시브. 시작 시 핵심 정보를 제외한 시나리오의 전체 구조를 파악한 채 시작한다.",
            "depth=파악 깊이(1=개략, 2=중간, 3=상세)"),
        PASSIVE_GM("passive_gm", false,
            "패시브. effect에 적힌 상시 효과를 GM이 매 턴 항상 고려한다(예: 주인공 보호, 미묘한 이상 감지).",
            "(별도 파라미터 없음. 효과 내용은 effect 텍스트로 표현)"),
        PASSIVE_TRIGGER("passive_trigger", false,
            "패시브. effect에 적힌 조건이 GM 판단으로 충족되면 효과가 자동 발동된다. passive_gm(항상 고려)과 달리 트리거 조건이 명확하다. 예: '위험 직전 자동 경고', '거짓말 탐지 자동 발동', '아군 근처에 있으면 감지'. 조건·효과 모두 effect 텍스트에 서술한다.",
            "intensity=발동 효과 강도(1=미약, 2=중간, 3=강), trigger_freq=발동 빈도(1=드물게, 2=가끔, 3=자주)"),
        PROTECT("protect", false,
            "패시브. effect에 적힌 종류의 피해·효과를 자동으로 줄이거나 막는다. 보호 대상(물리·정신·초자연 등)은 effect 텍스트로 표현한다.",
            "power=방어력(1=소폭 경감, 2=절반 경감, 3=거의 무효화), uses=스테이지당 발동 횟수(0=무제한, 1~3=횟수 제한)");

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

        sb.append("[S등급] 스테이지 전체를 뒤바꿀 수 있는 결정적 효과. 아주 희소하게 배정.\n");
        sb.append("  · instant_clear (스테이지 즉시 생존 클리어)\n");
        sb.append("  · revive_ally (완전 부활·전회복)\n");
        sb.append("  · choice_action(choices=4) — 확정 4지선다 행동 선택\n");
        sb.append("  · ai_query(info=3, uses>=2) — 핵심 정보 다수 반복 획득\n");
        sb.append("  · passive_gm (주인공급 — 불리한 상황에서 '반드시 돌파구'가 보장)\n");
        sb.append("  · sacrifice(scale=3) — 극적 대가로 극적 혜택\n");
        sb.append("  · passive_trigger(intensity=3, trigger_freq=3) — 강하고 잦은 자동 발동\n\n");

        sb.append("[A등급] 스테이지에 강한 영향을 미치는 핵심 효과. 표준 상한.\n");
        sb.append("  · ai_query(info=3, uses=1) — 핵심 근접 질문 1회\n");
        sb.append("  · luck_roll(scale>=10) — 강한 운 보정\n");
        sb.append("  · gm_directive (강력 NPC·사건 강제)\n");
        sb.append("  · area_scan(scope=3, uses=2) — 광역 고빈도 탐색\n");
        sb.append("  · link_ally(depth=3) — 소통 경로 발견 포함\n");
        sb.append("  · passive_trigger(intensity=3, trigger_freq=2) — 강하고 가끔 발동\n");
        sb.append("  · protect(power=3) — 거의 무효화 방어\n");
        sb.append("  · sacrifice(scale=2, cost<=10) — 합리적 대가로 상당한 혜택\n\n");

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
        sb.append("  · choice_action(choices=2~3, uses=1) — 기본 선택지\n\n");

        sb.append("[C등급] 제한적 상황에서만 가치 있음, 또는 부작용 동반.\n");
        sb.append("  · ai_query(info=1, uses=1) — 모호한 암시 1회\n");
        sb.append("  · scenario_insight(depth=1) — 개략 구조만\n");
        sb.append("  · passive_gm (매우 약하거나 조건이 좁은 상시 효과)\n");
        sb.append("  · passive_trigger(intensity=1, trigger_freq=1~2) — 미약하고 드문 발동\n");
        sb.append("  · protect(power=1) — 소폭 경감\n");
        sb.append("  · area_scan(scope=1, uses=1) — 현재 위치 1회 탐색\n");
        sb.append("  · luck_roll(scale<=4) — 약한 운 보정\n");
        sb.append("  · link_ally(depth=1) — 생존 여부만 확인\n\n");

        sb.append("[D~F등급] 효과보다 제약·대가가 크거나 극히 상황 한정. 개성 위주.\n");
        sb.append("  · sacrifice(scale=1, cost>=15) — 높은 대가, 작은 혜택\n");
        sb.append("  · effect_type=\"\" (일반 특성) 에 약한 버프 서술\n\n");

        sb.append("★ 통찰 계열 등급 예시 (같은 개념, 다른 effect_type과 등급):\n");
        sb.append("  C: passive_gm, effect=\"주변의 눈에 띄는 사소한 변화를 자연스럽게 알아챈다\" → '관찰안'\n");
        sb.append("  B: passive_trigger(intensity=2), effect=\"위험 징후가 나타나면 자동으로 경고를 받는다\" → '위기감지'\n");
        sb.append("  A: link_ally(depth=2), effect=\"감각으로 아군의 위치와 상태를 파악하고 소통 실마리를 찾는다\" → '소통탐지'\n");
        sb.append("  A: area_scan(scope=3, uses=2), effect=\"광역 탐색으로 숨겨진 위험·단서를 발견한다\" → '공간인식'\n\n");

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
        if (e == null) { td.effectType = ""; return; }
        td.active = e.active;
        if (td.effectParams == null) td.effectParams = new HashMap<>();
        switch (e) {
            case AI_QUERY -> {
                td.effectParams.putIfAbsent("uses", 2);
                td.effectParams.putIfAbsent("info", 1);
                clamp(td, "uses", 1, 3); clamp(td, "info", 1, 3);
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
            case SCENARIO_INSIGHT -> {
                td.effectParams.putIfAbsent("depth", 2);
                clamp(td, "depth", 1, 3);
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
    }

    private static void clamp(TraitData td, String key, int lo, int hi) {
        int v = td.param(key, lo);
        td.effectParams.put(key, Math.max(lo, Math.min(hi, v)));
    }

    public static boolean isSystemEffect(TraitData td) {
        return td != null && td.hasSystemEffect() && Effect.byKey(td.effectType) != null;
    }

    /** 스테이지당 사용 횟수 상한 (uses 기반 효과만; 그 외 0=쿨다운/패시브가 관리) */
    public static int maxUsesPerStage(TraitData td) {
        Effect e = Effect.byKey(td.effectType);
        if (e == null) return 0;
        return switch (e) {
            case AI_QUERY, CHOICE_ACTION, GM_DIRECTIVE, REVIVE_ALLY,
                 INSTANT_CLEAR, AREA_SCAN, LINK_ALLY, SACRIFICE -> td.param("uses", 1);
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
