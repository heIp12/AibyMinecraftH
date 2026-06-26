package heipsys.trpg.model;

public class TraitData {
    public String  id;
    public String  name;
    public String  grade;        // S / A / B / C / D / F
    public String  description;
    public boolean active;       // true = 능동적 발동 (버튼 필요)
    public String  effect;       // GM 참고용 효과 설명
    public boolean roleSpecific; // true = 해당 챕터 배역 한정 특성 (챕터 종료 시 제거)
    /** 클리어 보상에서 기존 특성을 강화하는 선택지일 때, 대체 대상 특성 id (null = 신규 특성) */
    public String  replacesId;

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

    public String toDisplayLine() {
        String cd = (remainingCooldown > 0)
            ? " §c[쿨다운 " + remainingCooldown + "턴]" : (cooldownTurns == -1 && usedThisStage > 0 ? " §c[이번 스테이지 사용 완료]" : "");
        return "▸ (" + grade + ") " + name + ": " + description + cd;
    }
}
