package heipsys.trpg.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class PlayerData {

    public enum TurnState { IDLE, ACTING, WAITING }

    public UUID uuid;
    public String name;
    public String roleId = "";
    public int age = 25;
    public String job = "일반인";

    // [current, max]
    public int[] hp  = {6, 6};
    public int   str = 5;
    public int[] san = {5, 5};
    public int   cha = 4;
    public int   luk = 4;
    public int   spr = 4;

    public List<TraitData> traits = new ArrayList<>();
    public int diceRollsRemaining = 3;

    public String    status       = "normal";  // normal / puppet / dead
    public String    zone         = "";
    public boolean   isDead       = false;
    /** 괴담이 이 배역을 제거하고 정체를 차지한 상태 (다른 플레이어 기만) */
    public boolean   impersonated = false;
    public TurnState turnState    = TurnState.IDLE;

    public boolean statsConfirmed = false;
    public boolean roleAssigned   = false;

    /** 현재 소지 중인 아이템 ID 집합 (통신 기기 추적 등에 사용) */
    public Set<String> heldItemIds = new HashSet<>();

    /** 무작위 비공개 연락처 번호 (예: "1186"). 1회차에서 타인은 모름 */
    public String contactId = "";
    /** 이 플레이어가 연락처를 알고 있는 상대들의 UUID */
    public final Set<UUID> knownContacts = new HashSet<>();

    // Base stats snapshot — used to reset on retry
    public int[] baseHp  = {6, 6};
    public int   baseStr = 5;
    public int[] baseSan = {5, 5};
    public int   baseCha = 4;
    public int   baseLuk = 4;
    public int   baseSpr = 4;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public void snapshotBase() {
        baseHp  = new int[]{hp[0],  hp[1]};
        baseStr = str;
        baseSan = new int[]{san[0], san[1]};
        baseCha = cha;
        baseLuk = luk;
        baseSpr = spr;
    }

    public void resetToBase() {
        hp  = new int[]{baseHp[0],  baseHp[1]};
        str = baseStr;
        san = new int[]{baseSan[0], baseSan[1]};
        cha = baseCha;
        luk = baseLuk;
        spr = baseSpr;
        isDead       = false;
        impersonated = false;
        status       = "normal";
        turnState    = TurnState.IDLE;
        // heldItemIds / contactId / knownContacts 는 회차(재도전)에도 유지
        // (마인크래프트 인벤토리와 학습한 연락처는 재도전 시 보존됨)
    }

    public String getStatsSummary() {
        return String.format(
            "체력 %d/100(%d)  근력 %d  정신력 %d/100(%d)  매력 %d  행운 %d  영감 %d",
            hp[0]*10, hp[1], str, san[0]*10, san[1], cha, luk, spr
        );
    }

    public String getTraitsDisplay() {
        if (traits.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        for (TraitData t : traits) {
            sb.append("▸ ").append(t.name).append(" (").append(t.grade).append(") ");
        }
        return sb.toString().trim();
    }

    /** GM AI turn input용 플레이어 상세 줄 (행동자에게만 사용) */
    public String toTurnLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(name)
          .append("[").append(roleId.isEmpty() ? "?" : roleId)
          .append(" ").append(age).append("세 ").append(job).append("]")
          .append(" HP").append(hp[0]).append("/").append(hp[1])
          .append(" SAN").append(san[0]).append("/").append(san[1])
          .append(" STR").append(str)
          .append(" CHA").append(cha)
          .append(" LUK").append(luk)
          .append(" SPR").append(spr);
        if (!traits.isEmpty()) {
            sb.append(" 특성:");
            traits.forEach(t -> sb.append(t.name).append("(").append(t.grade).append(")"));
        }
        if (!heldItemIds.isEmpty()) {
            sb.append(" 소지품:").append(String.join(",", heldItemIds));
        }
        sb.append(" 상태:").append(status).append(" 위치:").append(zone.isEmpty() ? "?" : zone);
        return sb.toString();
    }

    /** 비행동 플레이어용 압축 요약 (HP/SAN/상태만). GM 전용 — 플레이어에게 노출 금지 */
    public String toShortLine() {
        if (impersonated) return name + "[괴담이 정체 차용 중]";
        if (isDead) return name + "[사망]";
        String st = status.equals("puppet") ? "[꼭두각시]" : "";
        return name + " HP" + hp[0] + "/" + hp[1] + " SAN" + san[0] + "/" + san[1] + st;
    }
}
