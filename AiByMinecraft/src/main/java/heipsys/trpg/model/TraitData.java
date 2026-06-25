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
        return "▸ (" + grade + ") " + name + ": " + description;
    }
}
