package heipsys.trpg;

import heipsys.trpg.model.TraitData;
import java.util.*;

/**
 * 시스템 사전정의 특성 카탈로그 (AI가 아닌 개발자가 정의하는 특성).
 * 기계적 효과는 코드에서, 이름·설명·등급은 이 클래스에서 확정.
 */
public class SystemTraitRegistry {

    public enum EffectType {
        PASSIVE_GM,       // 주인공 — GM 프롬프트에 컨텍스트 추가
        ACTIVE_LEAPER,    // 도약자 — 즉시 스테이지 클리어(F등급)
        ACTIVE_SAINT,     // 성녀 — 타 플레이어 완전 회복·부활
        PASSIVE_INSIGHT,  // 시나리오 이해 — 배역 배정 시 추가 정보
        ACTIVE_PRAYER,    // 기도자 — 스테이지당 2회 AI 직접 질문
        ACTIVE_ORACLE,    // 신내림 — 다음 행동 선택지 제시
        ACTIVE_LUCK,      // 행운 — 1d6 주사위 → 행운 보정
        ACTIVE_SHAMAN,    // 무당 — 괴담 진행도 표시
        ACTIVE_GM,        // 조우자 — GM에게 특별 지시 전달
    }

    public record SysTrait(
        String id, String name, String grade,
        String description, String effect,
        boolean active, int cooldownTurns,
        EffectType effectType
    ) {
        public TraitData toTraitData() {
            TraitData td = new TraitData();
            td.id = id;
            td.name = name;
            td.grade = grade;
            td.description = description;
            td.active = active;
            td.effect = effect;
            td.cooldownTurns = cooldownTurns;
            td.roleSpecific = false;
            return td;
        }
    }

    private static final List<SysTrait> CATALOG = List.of(
        new SysTrait("sys_leaper",      "도약자",       "A",
            "즉시 생존 판정",
            "1회 발동 후 소멸. 현재 스테이지를 즉시 클리어하나 평가 등급이 F로 고정된다.",
            true, -1, EffectType.ACTIVE_LEAPER),

        new SysTrait("sys_saint",       "성녀",         "A",
            "동료 부활·전회복",
            "근처 플레이어 1명의 체력·정신력·상태이상을 완전히 회복시키고 사망 시 부활시킨다.",
            true, -1, EffectType.ACTIVE_SAINT),

        new SysTrait("sys_insight",     "시나리오 이해", "B",
            "시나리오 파악",
            "핵심 정보를 제외한 시나리오의 전체 구조를 처음부터 파악한 상태로 시작한다.",
            false, 0, EffectType.PASSIVE_INSIGHT),

        new SysTrait("sys_prayer",      "기도자",       "A",
            "AI 질문권 ×2",
            "스테이지당 2회 GM에게 직접 질문 가능. 질문이 구체적일수록 더 명확한 힌트를 받는다.",
            true, 0, EffectType.ACTIVE_PRAYER),

        new SysTrait("sys_oracle",      "신내림",       "S",
            "선택지 행동",
            "발동 후 다음 행동이 선택지로 제시된다. 정답 시 큰 보정, 오답 시 큰 패널티.",
            true, -1, EffectType.ACTIVE_ORACLE),

        new SysTrait("sys_luck",        "행운",         "A",
            "행운 주사위",
            "1d6 발동. 1=-10, 2=-6, 3=-2, 4=+2, 5=+6, 6=+10 행운 보정(다음 행동 1회 적용).",
            true, 3, EffectType.ACTIVE_LUCK),

        new SysTrait("sys_shaman",      "무당",         "B",
            "괴담 진행도",
            "현재 괴담의 타임라인 단계와 진행 상황을 확인한다.",
            true, 2, EffectType.ACTIVE_SHAMAN),

        new SysTrait("sys_protagonist", "주인공",       "S",
            "운명의 중심",
            "중요 사건에 자주 휘말리나, 불리한 상황에서도 반드시 돌파구가 존재한다.",
            false, 0, EffectType.PASSIVE_GM),

        new SysTrait("sys_encounter_a", "조우자",       "A",
            "우군 NPC 조우",
            "발동 후 2턴 내에 진행을 돕는 우군 NPC를 반드시 만난다.",
            true, -1, EffectType.ACTIVE_GM),

        new SysTrait("sys_encounter_s", "강령 조우자",  "S",
            "강력 NPC 조우",
            "발동 후 다음 턴에 강력한 NPC를 만난다. 우군 보장 없음, 설득이 필요할 수 있다.",
            true, -1, EffectType.ACTIVE_GM)
    );

    private static final Map<String, SysTrait> BY_ID;
    static {
        Map<String, SysTrait> m = new LinkedHashMap<>();
        for (SysTrait t : CATALOG) m.put(t.id(), t);
        BY_ID = Collections.unmodifiableMap(m);
    }

    public static Optional<SysTrait> get(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<SysTrait> all() { return CATALOG; }

    public static boolean isSystemTrait(String traitId) {
        return traitId != null && traitId.startsWith("sys_");
    }

    public static void printCatalog(org.bukkit.entity.Player player) {
        player.sendMessage("§e[시스템 특성 목록]");
        for (SysTrait t : CATALOG) {
            String cdStr = t.cooldownTurns() == -1 ? "스테이지당 1회"
                : t.cooldownTurns() > 0 ? "쿨다운 " + t.cooldownTurns() + "턴" : "없음";
            player.sendMessage("§f" + t.id() + " §7│ §e(" + t.grade() + ") §f" + t.name()
                + " §7— " + t.description() + " §8[" + cdStr + "]");
        }
    }
}
