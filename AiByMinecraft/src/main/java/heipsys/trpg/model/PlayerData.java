package heipsys.trpg.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerData {

    public enum TurnState { IDLE, ACTING, WAITING }

    public UUID uuid;
    public String name;
    public String roleId = "";
    public String charName = ""; // 맵배경에서 배정된 캐릭터 이름 (시나리오 한정)
    public String gender   = ""; // 맵배경에서 배정된 성별: 남성/여성/미상
    public int age = 25;
    public String job = "일반인";

    /** 캐릭터 고유(기본) 나이 — 배역이 없을 때 복귀할 값 */
    public int baseAge = 25;
    /** 현재 배역에 맞춰 임시로 부여된 나이 (-1 = 배역 없음) */
    public int roleAge = -1;

    /** 캐릭터 고유(기본) 직업 — 배역이 없을 때 복귀할 값 */
    public String baseJob = "일반인";

    // [current, max]
    public int[] hp  = {6, 6};
    public int   str = 5;
    public int[] san = {5, 5};
    public int   cha = 4;
    public int   luk = 4;
    public int   spr = 4;

    public List<TraitData> traits = new ArrayList<>();
    public int diceRollsRemaining = 3;
    /** 기절 상태 남은 회복 턴 수 (0 = 기절 아님 또는 이미 회복) */
    public int faintTurnsRemaining = 0;
    /** 완전 잠식(관전) 상태 자동회복 카운터 (0=행동가능, >0=관전 중 — 0이 되면 SAN 1 회복) */
    public int puppetRecoveryTurns = 0;

    public String    status       = "normal";  // normal / puppet / dead
    public String    zone         = "";
    public String    spot         = "";        // 세부 위치 (zone 내 위치, 예: 계단앞)
    public boolean   isDead       = false;
    /** 괴담이 이 배역을 제거하고 정체를 차지한 상태 (다른 플레이어 기만) */
    public boolean   impersonated = false;
    public TurnState turnState    = TurnState.IDLE;

    public boolean statsConfirmed = false;
    public boolean roleAssigned   = false;
    /** 기여도 — 스테이지 평가 등급 누적치 (능력 강화 게이팅·진척 표시용, 능력 Phase C). 회차·챕터 넘어 유지. */
    public int contribution = 0;

    /** 현재 소지 중인 아이템 ID 집합 (통신 기기 추적 등에 사용) */
    public Set<String> heldItemIds = new HashSet<>();
    /** 기계 효과(item_type) 아이템의 런타임 상태 (아이템 Phase II). 키=아이템 id. heldItemIds와 병행. */
    public Map<String, ItemInstance> itemStates = new LinkedHashMap<>();

    /** GM 서술 + 행동 기록 (Log GUI용) */
    public List<String> narrativeLog = new ArrayList<>();
    /** AI가 추출한 정보 조각 목록 (Info GUI용) */
    public List<String> infoItems    = new ArrayList<>();
    public static final int NARRATIVE_LOG_MAX = 80;
    public static final int INFO_ITEMS_MAX    = 120;
    /** narrativeLog 안의 '위치 이동' 구분 마커 접두사 (페이지 분할 지점). PUA 문자라 trim/일반 텍스트와 충돌 없음 */
    public static final String MOVE_TAG = "##MOVE##";

    /**
     * 정보 자동기록을 대상 태그(주제)별로 묶은 그룹 구조 (Info GUI 헤더 렌더용).
     * key = 대상 태그(예: "괴담의 정체", "전화번호", NPC 이름 등), value = 그 대상의 단서 줄들.
     * 기존 평탄한 {@link #infoItems} 와 함께 유지되며(하위호환 mirror), 그룹 출력은 이쪽을 사용한다.
     */
    public Map<String, List<String>> infoGroups = new LinkedHashMap<>();

    /**
     * 중요 정보 — 능력(특성)으로 밝혀낸 사실들(원격감지·예지·탐색·엿보기 등).
     * 일반 단서(infoGroups)와 분리해 '중요 정보' GUI에 모은다. 최근 KEY_FACTS_MAX개 유지.
     */
    public final List<String> keyFacts = new ArrayList<>();
    public static final int KEY_FACTS_MAX = 60;
    public void addKeyFact(String fact) {
        if (fact == null || fact.isBlank()) return;
        String f = fact.trim();
        synchronized (keyFacts) {
            if (keyFacts.contains(f)) return;
            keyFacts.add(f);
            if (keyFacts.size() > KEY_FACTS_MAX) keyFacts.remove(0);
        }
    }

    /**
     * 단서를 대상 태그(주제)별 그룹에 기록한다. 기존 {@link #infoItems} 에도 "[subject] line" 형태로 mirror 추가(하위호환).
     * @param subject 대상 태그. null/blank면 "단서"로 분류.
     * @param line    단서 내용 한 줄. 같은 그룹에 동일 줄이 이미 있으면 중복 추가하지 않는다.
     */
    public void addInfo(String subject, String line) {
        if (line == null) return;
        String subj = (subject == null || subject.isBlank()) ? "단서" : subject;
        synchronized (infoGroups) {
            List<String> group = infoGroups.computeIfAbsent(subj, k -> new ArrayList<>());
            if (!group.contains(line)) group.add(line);
        }
        // 하위호환: 다른 파일이 읽는 평탄 목록에도 mirror 추가
        String mirror = "[" + subj + "] " + line;
        synchronized (infoItems) {
            infoItems.add(mirror);
            if (infoItems.size() > INFO_ITEMS_MAX) infoItems.remove(0);
        }
    }

    /** 방문해 본 zone 집합 (직접 그린 약도에 드러나는 범위) */
    public Set<String> visitedZones = new HashSet<>();
    /** 전체 지도를 입수했는지 (true면 약도에 모든 zone 표시) */
    public boolean hasFullMap = false;

    /** 무작위 비공개 연락처 번호 (예: "1186"). 1회차에서 타인은 모름 */
    public String contactId = "";
    /** 이 플레이어가 연락처를 알고 있는 상대들의 UUID */
    public final Set<UUID> knownContacts = new HashSet<>();
    /** 한 번이라도 알게 된 연락처 (다회차 보정 — 재도전 시 재적용해 이전에 안 번호를 유지) */
    public final Set<UUID> everKnownContacts = new HashSet<>();
    /** 한 번이라도 알게 된 NPC 연락처 id 누적 (다회차 이월 — 재도전 시 NPC 번호 유지. 복구는 GameStateManager/TRPGGameManager가 수행) */
    public final Set<String> everKnownNpcContacts = new HashSet<>();

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
        baseJob = job;
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
        isDead              = false;
        impersonated        = false;
        status              = "normal";
        faintTurnsRemaining = 0;
        puppetRecoveryTurns = 0;
        spot                = "";
        turnState          = TurnState.IDLE;
        // heldItemIds / contactId / knownContacts 는 회차(재도전)에도 유지
        // (마인크래프트 인벤토리와 학습한 연락처는 재도전 시 보존됨)
    }

    /** 챕터 종료 후 다음 스테이지 진행 시: roleSpecific 특성 제거, 기본 스탯 복구, 역할 초기화 */
    public void clearRoleData() {
        traits.removeIf(t -> t.roleSpecific);
        roleAge = -1;          // 배역 해제 → 다음 배역 전까지 고유 나이로
        job = baseJob;         // 배역 해제 → 고유 직업으로 복귀
        resetToBase();
        roleId       = "";
        zone         = "";
        spot         = "";
        charName     = "";
        gender       = "";
        roleAssigned = false;
        heldItemIds.clear();
        itemStates.clear();
        knownContacts.clear();
        contactId    = "";
        narrativeLog.clear();
        infoItems.clear();
        infoGroups.clear();
        synchronized (keyFacts) { keyFacts.clear(); }
        visitedZones.clear();
        hasFullMap = false;
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

    /** 개별 능력치 값을 자연어로 짧게 설명한다 (hover 표시용). key: "hp","san","str","cha","luk","spr" */
    public static String statDesc(String key, int v) {
        return switch (key) {
            case "hp"  -> v <= 1 ? "조금만 다쳐도 위험한 상태입니다."
                        : v <= 2 ? "매우 허약한 편입니다."
                        : v <= 3 ? "평균보다 약한 체력입니다."
                        : v <= 5 ? "평범한 성인의 체력입니다."
                        : v <= 6 ? "체력 관리가 잘 된 사람 수준입니다."
                        : v <= 7 ? "운동선수급 지구력입니다."
                        :          "인간 한계에 근접한 강인함입니다.";
            case "san" -> v <= 1 ? "정신이 이미 한계에 다다른 상태입니다."
                        : v <= 2 ? "극히 불안정한 정신 상태입니다."
                        : v <= 3 ? "충격에 예민한 편입니다."
                        : v <= 5 ? "평범한 수준의 정신력입니다."
                        : v <= 6 ? "웬만한 공포엔 흔들리지 않습니다."
                        : v <= 7 ? "강인한 정신력을 갖고 있습니다."
                        :          "거의 무너지지 않는 정신입니다.";
            case "str" -> v <= 1 ? "거의 힘이 없습니다."
                        : v <= 2 ? "아이 수준의 완력입니다."
                        : v <= 3 ? "초등학생 정도의 힘입니다."
                        : v <= 5 ? "평범한 성인의 힘입니다."
                        : v <= 6 ? "꾸준히 운동하는 사람 수준입니다."
                        : v <= 7 ? "탄탄한 근육질 체형입니다."
                        :          "인간 한계에 가까운 근력입니다.";
            case "cha" -> v <= 1 ? "대화 자체가 부담스러운 편입니다."
                        : v <= 2 ? "낯가림이 심하고 인상이 강하지 않습니다."
                        : v <= 3 ? "다소 어색하게 소통하는 편입니다."
                        : v <= 5 ? "무리 없이 어울릴 수 있습니다."
                        : v <= 6 ? "사람들이 자연스레 호감을 느낍니다."
                        : v <= 7 ? "설득력과 카리스마가 있습니다."
                        :          "존재감 자체가 특별합니다.";
            case "luk" -> v <= 1 ? "무언가 잘 풀리는 법이 없습니다."
                        : v <= 2 ? "운이 잘 따르지 않는 편입니다."
                        : v <= 3 ? "평균보다 조금 불운한 편입니다."
                        : v <= 5 ? "가끔 운이 도와주는 편입니다."
                        : v <= 6 ? "중요한 순간에 운이 따릅니다."
                        : v <= 7 ? "위기를 행운으로 넘길 때가 많습니다."
                        :          "타고난 행운아입니다.";
            case "spr" -> v <= 1 ? "육감이나 직감이 거의 없습니다."
                        : v <= 2 ? "낌새를 눈치채는 게 느린 편입니다."
                        : v <= 3 ? "직감이 무딘 편입니다."
                        : v <= 5 ? "가끔 이상한 낌새를 감지합니다."
                        : v <= 6 ? "직감이 예민한 편입니다."
                        : v <= 7 ? "강렬한 예감을 종종 느낍니다."
                        :          "예지에 가까운 직감을 갖고 있습니다.";
            default    -> "";
        };
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
        sb.append(charName.isEmpty() ? name : charName)
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
            traits.forEach(t -> {
                sb.append(t.name).append("(").append(t.grade);
                if (t.active) sb.append(",능동");
                if (t.effect != null && !t.effect.isBlank()) sb.append(",효과:").append(t.effect);
                sb.append(")");
            });
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
        String display = charName.isEmpty() ? name : charName;
        if (impersonated) return display + "[괴담이 정체 차용 중]";
        if (isDead) return display + "[사망]";
        String st = status.equals("puppet") ? "[꼭두각시]"
                  : status.equals("faint")  ? "[기절]"
                  : "";
        return display + " HP" + hp[0] + "/" + hp[1] + " SAN" + san[0] + "/" + san[1] + st;
    }
}
