package heipsys.trpg;

import heipsys.trpg.model.TraitData;
import java.util.*;

/**
 * 시스템 효과 프리미티브 레지스트리.
 *
 * 설계 철학: "능력(기계 효과)"은 코드가 정의하고, "변형(이름·등급·설명·쿨다운·수치)"은 AI가 정한다.
 * AI는 effect_type으로 프리미티브를 고르고 effect_params로 수치를 조절해
 * 같은 능력에서 다양한 특성 변형을 만들어낸다.
 *   예) ai_query(uses=2, info=1) → "기도자A: 2회 제한, 적은 정보"
 *       ai_query(uses=1, info=3) → "신내림A: 1회 제한, 핵심 정보"
 *       passive_gm(effect="위험 직전 육감 경고") → "육감A: 패시브 경고"
 */
public class SystemTraitRegistry {

    /** 코드가 정의하는 효과 프리미티브("능력"). 수치/이름/등급은 AI가 정한다. */
    public enum Effect {
        AI_QUERY("ai_query", true,
            "발동 시 플레이어가 GM에게 직접 질문할 수 있다. 질문이 구체적일수록 더 정확히 답한다.",
            "uses=스테이지당 질문 횟수(1~3), info=정보 깊이(1=암시적·적은정보, 2=중간, 3=핵심 근접)"),
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
        SCENARIO_INSIGHT("scenario_insight", false,
            "패시브. 시작 시 핵심 정보를 제외한 시나리오의 전체 구조를 파악한 채 시작한다.",
            "depth=파악 깊이(1=개략, 2=중간, 3=상세)"),
        PASSIVE_GM("passive_gm", false,
            "패시브. effect에 적힌 상시 효과를 GM이 매 턴 고려한다(예: 위험 전 육감 경고, 주인공 보정).",
            "(별도 파라미터 없음. 효과 내용은 effect 텍스트로 표현)");

        public final String  key;
        public final boolean active;
        public final String  whatItDoes;  // AI 프롬프트용 설명
        public final String  paramHint;   // AI 프롬프트용 파라미터 설명

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
        sb.append("같은 효과라도 이름·등급·설명·쿨다운·파라미터 수치로 다양한 변형을 만들 수 있다.\n");
        sb.append("예: ai_query를 uses=2,info=1 → '약한 질문 특성', uses=1,info=3 → '강한 통찰 특성'.\n\n");
        sb.append("사용 가능한 effect_type:\n");
        for (Effect e : Effect.values()) {
            sb.append("- ").append(e.key).append(e.active ? " (능동)" : " (패시브)")
              .append(": ").append(e.whatItDoes)
              .append(" / 파라미터: ").append(e.paramHint).append("\n");
        }
        sb.append("주의:\n");
        sb.append("- 능동 효과는 active=true, 패시브(scenario_insight/passive_gm)는 active=false.\n");
        sb.append("- ai_query처럼 스테이지당 여러 번 쓰는 효과는 cooldown_turns=0으로 두고 uses로 제한.\n");
        sb.append("- 1회성 강력 효과(instant_clear/revive_ally 등)는 cooldown_turns=-1(스테이지당 1회) 권장.\n");
        sb.append("- 일반 특성(기계 효과 불필요)은 effect_type을 빈 문자열(\"\")로 둔다.\n");
        sb.append("- effect_type을 쓸 땐 남용 금지: 한 번에 1~2개 특성에만, 균형을 고려해 부여.\n");
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
        td.active = e.active; // 프리미티브 종류가 능/수동을 강제
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
            case SCENARIO_INSIGHT -> { td.effectParams.putIfAbsent("depth", 2); clamp(td, "depth", 1, 3); }
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
            case AI_QUERY, CHOICE_ACTION, GM_DIRECTIVE, REVIVE_ALLY, INSTANT_CLEAR -> td.param("uses", 1);
            default -> 0;
        };
    }

    // ──────────────────────────────────────────────────────────────
    //  사전정의 프리셋 (관리자 /trpg givetrait 용 + AI 변형 예시)
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
            applyDefaults(td); // active 설정 + 파라미터 보정
            return td;
        }
    }

    private static Map<String, Integer> p(Object... kv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static final List<Preset> PRESETS = List.of(
        new Preset("sys_leaper", "도약자", "A", "즉시 생존 판정",
            "1회 발동 후 소멸. 현재 스테이지를 즉시 클리어하나 평가 등급이 최하로 고정된다.",
            -1, "instant_clear", p()),
        new Preset("sys_saint", "성녀", "A", "동료 부활·전회복",
            "다른 플레이어 1명의 체력·정신력·상태이상을 완전히 회복시키고 사망 시 부활시킨다.",
            -1, "revive_ally", p()),
        new Preset("sys_insight", "시나리오 이해", "B", "시나리오 파악",
            "핵심 정보를 제외한 시나리오의 전체 구조를 처음부터 파악한 상태로 시작한다.",
            0, "scenario_insight", p("depth", 2)),
        new Preset("sys_prayer", "기도자", "A", "질문권 ×2(약)",
            "스테이지당 2회 GM에게 질문 가능. 구체적일수록 명확한 힌트를 받는다.",
            0, "ai_query", p("uses", 2, "info", 1)),
        new Preset("sys_oracle", "신내림", "A", "통찰 ×1(강)",
            "스테이지당 1회 GM에게 질문해 핵심에 근접한 통찰을 얻는다.",
            -1, "ai_query", p("uses", 1, "info", 3)),
        new Preset("sys_sixthsense", "육감", "A", "위험 예지",
            "위험이 닥치기 직전, 육감으로 미묘한 경고를 받는다.",
            0, "passive_gm", p()),
        new Preset("sys_choice", "계시", "S", "선택지 행동",
            "발동 후 다음 행동이 선택지로 제시된다. 정답 시 큰 보정, 오답 시 큰 패널티.",
            -1, "choice_action", p("uses", 1, "choices", 3)),
        new Preset("sys_luck", "행운", "A", "행운 주사위",
            "발동 시 주사위를 굴려 다음 행동 1회에 행운 보정을 받는다.",
            3, "luck_roll", p("scale", 10, "dice", 6)),
        new Preset("sys_shaman", "무당", "B", "괴담 진행도",
            "현재 괴담의 타임라인 단계와 진행 상황을 확인한다.",
            2, "show_progress", p()),
        new Preset("sys_protagonist", "주인공", "S", "운명의 중심",
            "중요 사건의 중심에 위치하며, 불리한 상황에서도 반드시 돌파구가 존재한다.",
            0, "passive_gm", p()),
        new Preset("sys_encounter_a", "조우자", "A", "우군 NPC 조우",
            "발동 후 2턴 이내에 진행을 돕는 우군 NPC를 자연스럽게 등장시켜야 한다.",
            -1, "gm_directive", p()),
        new Preset("sys_encounter_s", "강령 조우자", "S", "강력 NPC 조우",
            "발동 후 다음 턴에 강력한 NPC(회귀자·SCP 재단원·영적 존재 등)를 등장시킨다. 우군 보장 없음, 설득이 필요할 수 있다.",
            -1, "gm_directive", p())
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
