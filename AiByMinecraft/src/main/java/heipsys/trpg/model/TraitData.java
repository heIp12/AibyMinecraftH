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

    /** AI가 등급을 'D/F'·'D 또는 F'·'B~C'처럼 여러 개로 줄 때 첫 유효 등급 한 글자로 정규화(없으면 def). */
    public static String normGrade(String g, String def) {
        if (g != null) {
            for (char c : g.toUpperCase().toCharArray()) {
                if ("SABCDEF".indexOf(c) >= 0) return String.valueOf(c);
            }
        }
        return def;
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
        // 스테이지당 1회형은 사용 후 remainingCooldown이 센티넬(MAX_VALUE)이므로 이 분기를 먼저 확인한다
        // (그렇지 않으면 '[쿨다운 2147483647턴]'으로 표시된다).
        String cd = (cooldownTurns == -1 && usedThisStage > 0)
            ? " §c[이번 스테이지 사용 완료]" : (remainingCooldown > 0 ? " §c[쿨다운 " + remainingCooldown + "턴]" : "");
        String eff = effectiveGrade();
        String gradeStr = eff.equals(grade) ? grade : (grade + "§7·실효§e" + eff); // 출신보너스 발현 시 병기
        return "▸ (§e" + gradeStr + "§r) " + name + (level > 1 ? " §7Lv." + level : "") + ": " + description + cd
             + statDeltaChat();
    }

    // ── 스탯 증감 표시 (특성이 주는 능력치 변화) ────────────────────────────
    /** 스탯 증감 항목 목록 (예: ["근력 +5", "행운 -2"]). 변화 없으면 빈 리스트. */
    public java.util.List<String> statDeltas() {
        java.util.List<String> out = new java.util.ArrayList<>();
        addDelta(out, "근력",   str_add);
        addDelta(out, "매력",   cha_add);
        addDelta(out, "행운",   luk_add);
        addDelta(out, "영감",   spr_add);
        addDelta(out, "체력",   hp_max_add);
        addDelta(out, "정신력", san_max_add);
        return out;
    }
    private static void addDelta(java.util.List<String> out, String label, int v) {
        if (v != 0) out.add(label + " " + (v > 0 ? "+" + v : String.valueOf(v)));
    }

    /** 채팅용 색 입힌 스탯 증감 (양수=초록, 음수=빨강). 없으면 빈 문자열. */
    public String statDeltaChat() {
        StringBuilder sb = new StringBuilder();
        appendChatDelta(sb, "근력",   str_add);
        appendChatDelta(sb, "매력",   cha_add);
        appendChatDelta(sb, "행운",   luk_add);
        appendChatDelta(sb, "영감",   spr_add);
        appendChatDelta(sb, "체력",   hp_max_add);
        appendChatDelta(sb, "정신력", san_max_add);
        return sb.length() == 0 ? "" : " §8[" + sb + "§8]";
    }
    private static void appendChatDelta(StringBuilder sb, String label, int v) {
        if (v == 0) return;
        if (sb.length() > 0) sb.append("§8, ");
        sb.append(v > 0 ? "§a" : "§c").append(label).append(' ').append(v > 0 ? "+" : "").append(v);
    }

    /** 다이얼로그 툴팁용 평문 스탯 증감 (예: "근력 +5, 행운 -2"). 없으면 빈 문자열. */
    public String statDeltaPlain() {
        return String.join(", ", statDeltas());
    }
}
