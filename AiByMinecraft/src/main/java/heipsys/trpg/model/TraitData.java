package heipsys.trpg.model;

import java.util.HashMap;
import java.util.Map;

public class TraitData {
    public String  id;
    public String  name;
    public String  grade;        // S / A / B / C / D / E / F  (E·F=디버프·고제약 등급)
    public String  description;
    public boolean active;       // true = 능동적 발동 (버튼 필요)
    public String  effect;       // GM 참고용 효과 설명
    /** 이 특성이 '이 캐릭터'에게 발현된 계기·출처(유산·성향·과거). 개인화 서술용. 빈 문자열=일반. */
    public String  origin = "";
    public boolean roleSpecific; // true = 해당 챕터 배역 한정 특성 (챕터 종료 시 제거)
    /** 클리어 보상에서 기존 특성을 강화하는 선택지일 때, 대체 대상 특성 id (null = 신규 특성) */
    public String  replacesId;
    /** 능력 레벨(강화 단계). 1=기본. 강화 보상으로 상승. (능력 Phase C) */
    public int level    = 1;
    public int maxLevel = 1;
    /** 처음 생성됐을 때의 등급(출신). 강화해도 보존. 출신이 D보다 낮을수록 강화 시 실효 파워가 더 높다. */
    public String originGrade = "";

    /**
     * 시스템 효과 종류 (빈 문자열 = 일반 특성, 효과는 GM 서술로만 처리).
     * 값이 있으면 코드가 정의한 기계적 효과 프리미티브를 사용한다.
     * 종류·파라미터는 SystemTraitRegistry 참조. 이름·등급·설명·쿨다운은 AI가 결정.
     */
    public String effectType = "";
    /** 효과 프리미티브의 수치 파라미터 (AI가 조절). 예: {"uses":2,"info":1} */
    public Map<String, Integer> effectParams = new HashMap<>();

    /** effectParams에서 정수 값을 읽되, 없으면 기본값 반환 */
    public int param(String key, int def) {
        Integer v = effectParams.get(key);
        return v != null ? v : def;
    }

    /** 시스템 기계 효과를 가진 특성인지 */
    public boolean hasSystemEffect() {
        return effectType != null && !effectType.isEmpty();
    }

    // 쿨다운: 0=없음, 양수=N턴 대기, -1=스테이지당 1회
    public int cooldownTurns = 0;
    public int remainingCooldown = 0;  // 현재 남은 쿨다운 턴

    // 연속 사용 추적 (같은 스테이지 내, 효과 감소 판정용)
    public int usedThisStage = 0;
    public int lastUsedTurn = -1;

    // 특성 보유 시 영구 스탯 보정 (addTrait 시 적용, removeTrait 시 환원)
    public int str_add = 0;
    public int cha_add = 0;
    public int luk_add = 0;
    public int spr_add = 0;
    public int hp_max_add = 0;
    public int san_max_add = 0;

    public TraitData() {}

    public TraitData(String id, String name, String grade,
                     String description, boolean active, String effect) {
        this.id          = id;
        this.name        = name;
        this.grade       = grade;
        this.description = description;
        this.active      = active;
        this.effect      = effect;
    }

    // 등급 사다리 F<E<D<C<B<A<S
    private static final String[] LADDER = {"F","E","D","C","B","A","S"};
    private static int gradeInt(String g) {
        return switch (g == null ? "" : g.trim().toUpperCase()) {
            case "S" -> 6; case "A" -> 5; case "B" -> 4; case "C" -> 3; case "D" -> 2; case "E" -> 1; default -> 0;
        };
    }
    /**
     * 실효(파워) 등급: 출신(originGrade)이 D보다 낮고 그보다 강화됐다면, '낮았던 만큼' 파워가 더 높다.
     * 예) F(출신) → 명목 C 까지 강화 = 실효 A. (예산·실제 효과는 실효 등급 기준)
     */
    public String effectiveGrade() {
        int cur = gradeInt(grade);
        int og  = (originGrade == null || originGrade.isEmpty()) ? cur : gradeInt(originGrade);
        int bonus = (cur > og) ? Math.max(0, 2 - og) : 0; // D(2)보다 낮은 출신 + 강화된 경우만
        return LADDER[Math.min(LADDER.length - 1, cur + bonus)];
    }

    public String toDisplayLine() {
        String cd = (remainingCooldown > 0)
            ? " §c[쿨다운 " + remainingCooldown + "턴]" : (cooldownTurns == -1 && usedThisStage > 0 ? " §c[이번 스테이지 사용 완료]" : "");
        String eff = effectiveGrade();
        String gradeStr = eff.equals(grade) ? grade : (grade + "§7·실효§e" + eff); // 출신보너스 발현 시 병기
        return "▸ (§e" + gradeStr + "§r) " + name + (level > 1 ? " §7Lv." + level : "") + ": " + description + cd;
    }
}
