package heipsys.trpg.model;

public class TraitData {
    public String  id;
    public String  name;
    public String  grade;        // S / A / B / C / D / F
    public String  description;
    public boolean active;       // true = 능동적 발동 (버튼 필요)
    public String  effect;       // GM 참고용 효과 설명

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
