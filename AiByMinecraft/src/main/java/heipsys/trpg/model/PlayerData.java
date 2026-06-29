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

    /** 마지막(피날레) 스테이지 '원년 배역 복귀'용 — 1스테이지 캐릭터 정체성 스냅샷. clearRoleData로 지워지지 않는다. */
    public boolean hasOrigChar = false;
    public String  origCharName = "";
    public String  origGender   = "";
    public int     origAge      = -1;
    public String  origJob      = "";

    /** npc_bind(NPC 저장→다음 게임 소환)로 저장한 NPC의 JSON. 다음 스테이지 시작 시 1회 소환되고 비워진다. clearRoleData로 지워지지 않는다. */
    public String  savedNpcJson = "";

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

    /**
     * resetToBase()로 현재 스탯을 base로 되돌린 뒤, 보유 중인 (영구) 특성의 스탯 보정을 다시 누적한다.
     * 특성은 스테이지를 넘어 유지되므로, 이 재적용이 없으면 클리어 보상 특성의 스탯이 다음 스테이지에서 사라진다.
     */
    public void reapplyTraitStats() {
        for (TraitData t : traits) {
            str += t.str_add;
            cha += t.cha_add;
            luk += t.luk_add;
            spr += t.spr_add;
            if (t.hp_max_add != 0) {
                hp[1] = Math.max(1, hp[1] + t.hp_max_add);
                hp[0] = Math.min(hp[0], hp[1]);
            }
            if (t.san_max_add != 0) {
                san[1] = Math.max(1, san[1] + t.san_max_add);
                san[0] = Math.min(san[0], san[1]);
            }
        }
    }

    /** 챕터 종료 후 다음 스테이지 진행 시: roleSpecific 특성 제거, 기본 스탯 복구, 역할 초기화 */
    public void clearRoleData() {
        traits.removeIf(t -> t.roleSpecific);
        roleAge = -1;          // 배역 해제 → 다음 배역 전까지 고유 나이로
        job = baseJob;         // 배역 해제 → 고유 직업으로 복귀
        resetToBase();
        reapplyTraitStats();   // 영구(비배역) 특성 스탯 보정 복원 — 다음 스테이지에서도 유지
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

    /**
     * 개별 능력치 값을 자연어로 짧게 설명한다 (hover 표시용). key: "hp","san","str","cha","luk","spr"
     * ★기준: 5 = 인간 평균★. 7 이상도 또렷한 차이를 느끼도록 20까지 세분화한다
     * (8~9 상위권 · 10~11 인간 정상급 · 12~13 인간 한계 · 14~16 초인 · 17+ 초월/괴물).
     */
    public static String statDesc(String key, int v) {
        return switch (key) {
            case "hp"  -> v <= 1 ? "조금만 다쳐도 쓰러지는 병약한 몸입니다."
                        : v == 2 ? "또래보다 한참 약골이라 금세 지칩니다."
                        : v == 3 ? "평균보다 약한 체력 — 오래 못 버팁니다."
                        : v == 4 ? "보통 사람보다 조금 약한 편입니다."
                        : v == 5 ? "평범한 성인의 체력입니다. (인간 평균)"
                        : v == 6 ? "또래보다 튼튼하고 회복도 빠릅니다."
                        : v == 7 ? "꾸준히 단련한 사람처럼 강건합니다."
                        : v <= 9  ? "운동선수급 지구력 — 웬만해선 지치지 않습니다."
                        : v <= 11 ? "특수부대·격투가 수준의 강골입니다."
                        : v <= 13 ? "인간 체력의 한계에 다다른 무쇠 몸입니다."
                        : v <= 16 ? "보통 무기론 흠집도 안 나는 초인적 육체입니다."
                        :           "치명상조차 버텨내는, 불사에 가까운 생명력입니다.";
            case "san" -> v <= 1 ? "무너지기 직전 — 작은 충격에도 정신이 깨집니다."
                        : v == 2 ? "극히 불안정해 환각·공황에 쉽게 빠집니다."
                        : v == 3 ? "충격에 예민하고 쉽게 겁먹습니다."
                        : v == 4 ? "보통보다 조금 여린 멘탈입니다."
                        : v == 5 ? "평범한 사람의 정신력입니다. (인간 평균)"
                        : v == 6 ? "웬만한 공포엔 흔들리지 않습니다."
                        : v == 7 ? "담대하고 침착한 편입니다."
                        : v <= 9  ? "어지간한 참극 앞에서도 냉정을 유지합니다."
                        : v <= 11 ? "베테랑 군인·구조대 수준의 강철 멘탈입니다."
                        : v <= 13 ? "인간 정신력의 한계 — 거의 무너지지 않습니다."
                        : v <= 16 ? "초자연적 공포조차 이성으로 받아내는 정신입니다."
                        :           "무엇을 보아도 흔들림 없는, 사람 같지 않은 정신입니다.";
            case "str" -> v <= 1 ? "가벼운 것도 들기 버거울 만큼 힘이 없습니다."
                        : v == 2 ? "아이 수준의 완력입니다."
                        : v == 3 ? "또래보다 약한 힘입니다."
                        : v == 4 ? "평균보다 조금 약한 힘입니다."
                        : v == 5 ? "평범한 성인의 힘입니다. (인간 평균)"
                        : v == 6 ? "힘 좀 쓴다는 소리를 듣는 수준입니다."
                        : v == 7 ? "탄탄한 근육질, 웬만한 사람보다 셉니다."
                        : v <= 9  ? "운동선수급 완력 — 성인을 가볍게 제압합니다."
                        : v <= 11 ? "역도선수·격투가급 인간 최상위 장사입니다."
                        : v <= 13 ? "철문도 비틀어 여는 인간 근력의 한계입니다."
                        : v <= 16 ? "맨손으로 강철을 휘는 초인적 괴력입니다."
                        :           "차를 집어던지는, 인간을 아득히 넘은 괴력입니다.";
            case "cha" -> v <= 1 ? "대화 자체가 부담스러워 사람을 밀어냅니다."
                        : v == 2 ? "낯가림이 심하고 인상이 흐릿합니다."
                        : v == 3 ? "다소 어색하게 소통하는 편입니다."
                        : v == 4 ? "평균보다 조금 수줍은 편입니다."
                        : v == 5 ? "무리 없이 어울리는 평범한 호감도입니다. (인간 평균)"
                        : v == 6 ? "사람들이 자연스레 호감을 느낍니다."
                        : v == 7 ? "설득력 있고 분위기를 잘 이끕니다."
                        : v <= 9  ? "타고난 인기인 — 처음 본 사람도 마음을 엽니다."
                        : v <= 11 ? "군중을 휘어잡는 강한 카리스마입니다."
                        : v <= 13 ? "한마디로 사람을 움직이는 비범한 매력입니다."
                        : v <= 16 ? "적의마저 누그러뜨리는 거부하기 힘든 흡인력입니다."
                        :           "존재만으로 사람을 사로잡는, 마성에 가까운 매력입니다.";
            case "luk" -> v <= 1 ? "하는 일마다 어긋나는 지독한 불운입니다."
                        : v == 2 ? "운이 잘 따르지 않는 편입니다."
                        : v == 3 ? "평균보다 조금 불운합니다."
                        : v == 4 ? "가끔 운이 비껴가는 평범 이하입니다."
                        : v == 5 ? "이따금 운이 따르는 평범한 운입니다. (인간 평균)"
                        : v == 6 ? "중요한 순간에 곧잘 운이 따릅니다."
                        : v == 7 ? "위기를 행운으로 넘길 때가 많습니다."
                        : v <= 9  ? "눈에 띄게 운이 좋아 아슬아슬하게 잘 풀립니다."
                        : v <= 11 ? "확률을 비웃는 타고난 행운아입니다."
                        : v <= 13 ? "거의 모든 위기를 운으로 넘기는 기적의 사람입니다."
                        : v <= 16 ? "운명이 편드는 듯한 초자연적 행운입니다."
                        :           "불가능을 가능으로 바꾸는, 운명 그 자체 같은 행운입니다.";
            case "spr" -> v <= 1 ? "육감이 전혀 없어 위험도 못 느낍니다."
                        : v == 2 ? "낌새를 눈치채는 게 매우 느립니다."
                        : v == 3 ? "직감이 무딘 편입니다."
                        : v == 4 ? "평균보다 조금 둔한 감입니다."
                        : v == 5 ? "가끔 이상한 낌새를 느끼는 평범한 직감입니다. (인간 평균)"
                        : v == 6 ? "직감이 예민해 위험을 곧잘 알아챕니다."
                        : v == 7 ? "강한 예감을 종종 느낍니다."
                        : v <= 9  ? "위험·거짓을 남보다 먼저 알아챕니다."
                        : v <= 11 ? "사건 직전 강렬한 예감이 찾아옵니다."
                        : v <= 13 ? "예지에 가까운 날카로운 통찰입니다."
                        : v <= 16 ? "보이지 않는 것을 읽어내는 초자연적 영감입니다."
                        :           "미래와 진실이 훤히 비치는, 예언자 같은 영감입니다.";
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

    /**
     * GM·서술 컨텍스트 전용 표시 이름. ★플레이어 계정(닉네임)을 절대 노출하지 않는다.★
     * char_name이 누락된 배역(.gdam에 char_name 없음)이라도 계정명 대신 인게임 호칭으로 폴백한다.
     * 우선순위: 캐릭터명 → 배역 직업(일반인 제외) → 일반 호칭.
     * (계정명은 시나리오 서술·후일담에 새어 들어가면 몰입을 깨므로 이 메서드로만 GM에 전달한다)
     */
    public String gmDisplayName() {
        if (charName != null && !charName.isEmpty()) return charName;
        if (job != null && !job.isBlank() && !job.equals("일반인")) return job;
        return "이름 모를 인물";
    }

    /** GM AI turn input용 플레이어 상세 줄 (행동자에게만 사용) */
    public String toTurnLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(gmDisplayName())
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
        String display = gmDisplayName();
        if (impersonated) return display + "[괴담이 정체 차용 중]";
        if (isDead) return display + "[사망]";
        String st = status.equals("puppet") ? "[홀림]"
                  : status.equals("faint")  ? "[기절]"
                  : "";
        return display + " HP" + hp[0] + "/" + hp[1] + " SAN" + san[0] + "/" + san[1] + st;
    }
}
