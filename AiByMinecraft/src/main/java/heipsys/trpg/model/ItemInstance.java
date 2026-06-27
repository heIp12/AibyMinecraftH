package heipsys.trpg.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 아이템의 런타임 인스턴스 — 기계 효과(item_type)가 있는 아이템의 '상태'를 추적한다.
 *
 * 설계 철학(특성과 동일): 코드가 기계 상태(charges/on/broken)를 관리하고,
 * AI(GM)는 &lt;ITEM_USE&gt; 태그로 그 상태를 갱신한다.
 * key_items 정의(JsonObject)는 ItemManager가 외형(ItemStack) 생성에 쓰고,
 * 이 클래스는 사용·소모·변형 등 상태 변화만 담당한다(인벤토리와 별개의 논리 상태).
 */
public class ItemInstance {
    public String id   = "";     // key_items id 또는 이름
    public String name = "";     // 표시 이름
    /** key/tool/light/weapon/consumable/comm/protective/record/combine/ritual/evidence */
    public String itemType = "";
    /** 숫자 파라미터 (charges/scope/power/ammo/uses/hp/san/noise/battery 등) */
    public Map<String, Integer> params = new LinkedHashMap<>();
    /** 열쇠·도구가 여는 구역 zone_id (item_params.unlocks) */
    public String unlocks = "";

    public int     charges = -1;        // 배터리/탄/사용횟수 남은 수 (-1 = 해당 없음/무제한)
    public boolean on = false;          // 조명 등 토글 상태
    public boolean broken = false;      // 소진·고장 (더 이상 작동 안 함)
    public String  transformedFrom = ""; // 조합으로 만들어졌다면 출처(로그용)

    public ItemInstance() {}

    public ItemInstance(String id, String name, String itemType) {
        this.id = id; this.name = name; this.itemType = itemType;
    }

    public int param(String key, int def) {
        Integer v = params.get(key);
        return v != null ? v : def;
    }

    /** 작동 가능한가 (소진/고장 아님, 충전 남음) */
    public boolean usable() {
        return !broken && charges != 0;
    }

    /** GM 컨텍스트용 한 줄 요약 (예: "손전등(조명, 잔량 2)") */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(name.isBlank() ? id : name).append("(").append(typeLabel());
        if (charges >= 0) sb.append(", 잔량 ").append(charges);
        if (on)           sb.append(", 켜짐");
        if (broken)       sb.append(", 소진됨");
        sb.append(")");
        return sb.toString();
    }

    private String typeLabel() {
        return switch (itemType) {
            case "key"        -> "열쇠";
            case "tool"       -> "도구";
            case "light"      -> "조명";
            case "weapon"     -> "무기";
            case "consumable" -> "소모품";
            case "comm"       -> "통신";
            case "protective" -> "보호구";
            case "ritual"     -> "의례";
            case "evidence"   -> "증거";
            case "record"     -> "기록";
            case "combine"    -> "부품";
            default           -> itemType;
        };
    }
}
