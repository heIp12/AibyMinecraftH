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

    /** 캐릭터 고유(기본) 나이 — 배역이 없을 때 복귀할 값 */
    public int baseAge = 25;
    /** 현재 배역에 맞춰 임시로 부여된 나이 (-1 = 배역 없음) */
    public int roleAge = -1;

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
        baseAge = age;
    }

    public void resetToBase() {
        hp  = new int[]{baseHp[0],  baseHp[1]};
        str = baseStr;
        san = new int[]{baseSan[0], baseSan[1]};
        cha = baseCha;
        luk = baseLuk;
        spr = baseSpr;
        // 배역이 있으면 배역 나이로, 없으면 고유 나이로 복귀 (재도전 시 배역 나이 유지)
        age = (roleAge >= 0) ? roleAge : baseAge;
        isDead       = false;
        impersonated = false;
        status       = "normal";
        turnState    = TurnState.IDLE;
        // heldItemIds / contactId / knownContacts 는 회차(재도전)에도 유지
        // (마인크래프트 인벤토리와 학습한 연락처는 재도전 시 보존됨)
    }

    /** 챕터 종료 후 다음 스테이지 진행 시: roleSpecific 특성 제거, 기본 스탯 복구, 역할 초기화 */
    public void clearRoleData() {
        traits.removeIf(t -> t.roleSpecific);
        roleAge = -1;          // 배역 해제 → 다음 배역 전까지 고유 나이로
        resetToBase();
        roleId       = "";
        zone         = "";
        roleAssigned = false;
        heldItemIds.clear();
        knownContacts.clear();
        contactId    = "";
    }

    public String getStatsSummary() {
        return String.format(
            "체력 %d/100(%d)  근력 %d  정신력 %d/100(%d)  매력 %d  행운 %d  영감 %d",
            pct(hp[0], hp[1]), hp[1], str, pct(san[0], san[1]), san[1], cha, luk, spr
        );
    }

    /** 현재/최대를 0-100 퍼센트로 환산 */
    private static int pct(int current, int max) {
        if (max <= 0) return 0;
        return (int) Math.round((double) current / max * 100.0);
    }

    /**
     * 능력치 수치를 자연어로 가볍게 해설한다(스탯 이름·숫자 노출 없이).
     * 예: "쉽게 지치지만 사람들과 쉽게 친해집니다."
     * 플레이어 정보 표시와 GM 서술 일관성 유지에 함께 사용한다.
     */
    public String getStatNarrative() {
        List<String> strengths  = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        evalStat(strengths, weaknesses, hp[1],  "좀처럼 지치지 않습니다",      "쉽게 지칩니다");
        evalStat(strengths, weaknesses, str,    "완력이 좋습니다",            "힘이 약한 편입니다");
        evalStat(strengths, weaknesses, san[1], "웬만해선 동요하지 않습니다",  "쉽게 불안에 휩싸입니다");
        evalStat(strengths, weaknesses, cha,    "사람들과 쉽게 친해집니다",    "낯을 가리는 편입니다");
        evalStat(strengths, weaknesses, luk,    "운이 따르는 편입니다",        "운이 잘 따르지 않습니다");
        evalStat(strengths, weaknesses, spr,    "직감이 예리합니다",          "직감이 무딘 편입니다");

        // 가장 두드러진 약점·강점 위주로 최대 3개 문장만
        List<String> picked = new ArrayList<>();
        if (!weaknesses.isEmpty()) picked.add(weaknesses.get(0));
        if (!strengths.isEmpty())  picked.add(strengths.get(0));
        if (picked.size() < 3) {
            if (weaknesses.size() > 1) picked.add(weaknesses.get(1));
            else if (strengths.size() > 1) picked.add(strengths.get(1));
        }
        if (picked.isEmpty()) return "전반적으로 무난한 능력입니다.";
        return String.join(" ", picked);
    }

    /** value가 평균 이상이면 강점, 이하면 약점 목록에 추가 (4~5는 평범으로 제외) */
    private static void evalStat(List<String> strengths, List<String> weaknesses,
                                 int value, String high, String low) {
        if (value >= 6)      strengths.add(high);
        else if (value <= 3) weaknesses.add(low);
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
        // 수치를 자연어 성향으로도 제공 → GM이 서술·판정에 일관되게 반영
        sb.append(" 성향:").append(getStatNarrative());
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
