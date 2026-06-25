package heipsys.trpg;

import com.google.gson.*;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 캐릭터 스탯 생성.
 * 배역 데이터를 선제 주입하면 나이/직업이 배역에 맞게 결정되고,
 * 초기 특성도 AI가 직업·나이 맥락으로 생성한다.
 *
 * 생성 순서:
 *  1. 나이/직업 — 배역 age_range·job_pool 우선, 없으면 전체 풀
 *  2. 기본 스탯 배분 (총합 25, 각 최소 1)
 *  3. 최저 스탯 +1, 무작위 [+4,+2,+1,-2] 적용
 *  4. Haiku AI로 나이/직업/배역 보정
 *  5. AI 초기 특성 생성 (1~2개, 직업·나이 맥락)
 */
public class CharacterGenerator {

    private static final Random RNG = new Random();

    private static final String[] JOB_POOL = {
        "초등학생","중학생","고등학생","대학생","유치원생",
        "의사","간호사","약사","수의사","치과의사",
        "교사","교수","학원강사","사서",
        "경찰관","소방관","군인","경호원","형사",
        "요리사","제빵사","바리스타","식당주인",
        "회계사","변호사","검사","판사","세무사",
        "기자","PD","작가","웹툰작가","유튜버","사진작가",
        "프로그래머","디자이너","건축가","엔지니어",
        "농부","어부","축산업자","임업종사자",
        "택시기사","버스기사","트럭운전사","배달원",
        "청소부","경비원","편의점알바","마트직원",
        "헬스트레이너","무용가","스포츠선수","코치",
        "성직자","스님","사회복지사","심리상담사",
        "골동품상인","점술사","마술사","서커스단원",
        "노숙자","은퇴자","백수","전업주부",
        "탐정","해커","사기꾼","도둑",
        "인플루언서","모델","배우","가수",
        "과학자","연구원","역사학자","고고학자",
        "조련사","잠수부","등반가",
        "정치인","공무원","외교관","통역사"
    };

    private static final String[] STAT_NAMES = {"hp","str","san","cha","luk","spr"};
    private static final Gson GSON = new Gson();

    private final AiManager aiManager;

    public CharacterGenerator(AiManager aiManager) {
        this.aiManager = aiManager;
    }

    // ──────────────────────────────────────────────────────────────
    //  메인 생성 (배역 데이터 없이)
    // ──────────────────────────────────────────────────────────────

    public CompletableFuture<PlayerData> generate(Player player) {
        return generate(player, null);
    }

    // ──────────────────────────────────────────────────────────────
    //  메인 생성 (배역 데이터 주입)
    // ──────────────────────────────────────────────────────────────

    /**
     * @param roleData .gdam roles[] 배열의 해당 배역 JsonObject.
     *                 있으면 age_range, job_pool, name을 스탯 생성에 활용.
     */
    public CompletableFuture<PlayerData> generate(Player player, JsonObject roleData) {
        PlayerData pd = new PlayerData(player.getUniqueId(), player.getName());
        rollStats(pd, roleData);

        String roleContext = buildRoleContext(roleData);

        return aiManager.callAssistant(
            "너는 TRPG 캐릭터 스탯 보정기야. 아래 JSON 형식으로만 응답해:\n"
            + "{\"str_adj\":0,\"cha_adj\":0,\"luk_adj\":0,\"spr_adj\":0,"
            + "\"hp_max_adj\":0,\"san_max_adj\":0,\"reason\":\"\"}",
            buildAdjustPrompt(pd, roleContext)
        ).thenCompose(raw -> {
            applyAiAdjustment(pd, raw);
            return generateInitialTraits(pd, roleContext);
        }).thenApply(traits -> {
            pd.traits.addAll(traits);
            pd.snapshotBase();
            return pd;
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  스탯 굴림 (배역 age_range / job_pool 우선 적용)
    // ──────────────────────────────────────────────────────────────

    public void rollStats(PlayerData pd, JsonObject roleData) {
        // 나이 — range가 10살 미만이면 ±5 여유를 줘 재굴림 다양성 확보
        if (roleData != null && roleData.has("age_range")) {
            JsonArray ar = roleData.getAsJsonArray("age_range");
            int lo = ar.get(0).getAsInt(), hi = ar.get(1).getAsInt();
            if (hi - lo < 10) {
                lo = Math.max(5,  lo - 5);
                hi = Math.min(80, hi + 5);
            }
            pd.age = hi > lo ? lo + RNG.nextInt(hi - lo + 1) : lo;
        } else {
            pd.age = 5 + RNG.nextInt(76);
        }

        // 직업 — job_pool이 5개 미만이면 전체 풀을 섞어 보완 (재굴림 다양성 확보)
        if (roleData != null && roleData.has("job_pool")) {
            JsonArray pool = roleData.getAsJsonArray("job_pool");
            if (pool.size() >= 5) {
                pd.job = pool.get(RNG.nextInt(pool.size())).getAsString();
            } else if (pool.size() > 0) {
                // 배역 풀 50%, 전체 풀 50% 혼합
                if (RNG.nextBoolean()) {
                    pd.job = pool.get(RNG.nextInt(pool.size())).getAsString();
                } else {
                    pd.job = JOB_POOL[RNG.nextInt(JOB_POOL.length)];
                }
            } else {
                pd.job = JOB_POOL[RNG.nextInt(JOB_POOL.length)];
            }
        } else {
            pd.job = JOB_POOL[RNG.nextInt(JOB_POOL.length)];
        }

        // 총합 25를 6개 스탯에 배분
        int[] stats = distributePoints(25, 6, 1, 10);
        pd.hp  = new int[]{stats[0], stats[0]};
        pd.str = stats[1];
        pd.san = new int[]{stats[2], stats[2]};
        pd.cha = stats[3];
        pd.luk = stats[4];
        pd.spr = stats[5];

        boostLowest(pd);
        applyBonuses(pd);
    }

    // ──────────────────────────────────────────────────────────────
    //  AI 초기 특성 생성
    // ──────────────────────────────────────────────────────────────

    private CompletableFuture<List<TraitData>> generateInitialTraits(PlayerData pd, String roleContext) {
        String system = """
너는 TRPG 캐릭터 초기 특성 생성기야.
아래 JSON 배열 형식으로만 응답 (다른 텍스트 금지):
[{"id":"","name":"","grade":"C","description":"","active":false,"effect":""},...]

규칙:
- grade: C 또는 D/F 만 사용 (초기 캐릭터이므로 강한 특성 없음)
- 직업·나이에서 자연스럽게 연결되는 능력/약점 1~2개
- description과 effect에 스탯 숫자·스탯 약어(STR/HP/SAN/CHA/LUK/SPR) 절대 사용 금지
- description: 아주 짧은 명사구 한 줄(최대 18자). 장황한 설명·문장 금지. 예: "빠른 처치 손길", "겁 많은 성격"
- effect: 한 문장으로 간결하게 (수동이면 "판정에서 자연스럽게 발휘" 식, 능동이면 사용 효과)
- 직업명을 그대로 쓰지 말고 구체적 능력으로 작성 (예: '간호사' → '빠른 처치 손길')
- 한국어, 창의적
""";
        String prompt = "나이: " + pd.age + "세, 직업: " + pd.job
            + "\nHP=" + pd.hp[1] + " STR=" + pd.str + " SAN=" + pd.san[1]
            + " CHA=" + pd.cha + " LUK=" + pd.luk + " SPR=" + pd.spr
            + (roleContext != null && !roleContext.isBlank() ? "\n배역 맥락: " + roleContext : "")
            + "\n\n위 캐릭터에 맞는 초기 특성 1~2개를 JSON 배열로 생성해줘.";

        return aiManager.callAssistant(system, prompt).thenApply(raw -> {
            try {
                String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
                int s = cleaned.indexOf('['), e = cleaned.lastIndexOf(']');
                if (s == -1 || e == -1) return staticFallbackTraits(pd);
                JsonArray arr = GSON.fromJson(cleaned.substring(s, e + 1), JsonArray.class);
                List<TraitData> result = new ArrayList<>();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    TraitData td = new TraitData();
                    td.id          = obj.has("id")   && !obj.get("id").getAsString().isBlank()
                                     ? obj.get("id").getAsString()
                                     : "init_" + UUID.randomUUID().toString().substring(0, 6);
                    td.name        = obj.has("name")        ? obj.get("name").getAsString()        : "초기 특성";
                    td.grade       = obj.has("grade")       ? obj.get("grade").getAsString()       : "C";
                    td.description = obj.has("description") ? obj.get("description").getAsString() : "";
                    td.active      = obj.has("active")      && obj.get("active").getAsBoolean();
                    td.effect      = obj.has("effect")      ? obj.get("effect").getAsString()      : "";
                    result.add(td);
                }
                return result.isEmpty() ? staticFallbackTraits(pd) : result;
            } catch (Exception ex) {
                return staticFallbackTraits(pd);
            }
        });
    }

    /** AI 실패 시 스탯 기반 폴백 특성 (서사형 묘사, 스탯 수치 노출 없음) */
    private List<TraitData> staticFallbackTraits(PlayerData pd) {
        List<TraitData> traits = new ArrayList<>();
        int[] vals = {pd.hp[1], pd.str, pd.san[1], pd.cha, pd.luk, pd.spr};

        String[][] pos = {
            {"강인한 신체", "C", "쉽게 지치지 않음", "어려운 상황에서도 쉽게 쓰러지지 않습니다."},
            {"다져진 근육", "C", "타고난 힘", "힘이 필요한 상황에서 유리하게 작용합니다."},
            {"냉정한 심성", "C", "흔들리지 않는 정신", "공포나 혼란 상황에서 버텨낼 때 유리하게 작용합니다."},
            {"자연스러운 친화력", "C", "타고난 사교성", "대인 관계가 필요한 상황에서 유리하게 작용합니다."},
            {"행운아", "C", "결정적 순간의 운", "예상치 못한 행운이 발생할 가능성이 높아집니다."},
            {"날카로운 직감", "C", "예민한 촉", "상황의 낌새를 빠르게 눈치채는 데 유리합니다."}
        };
        String[][] neg = {
            {"허약한 체력", "F", "쉽게 지침", "체력 소모가 많은 상황에서 불리하게 작용합니다."},
            {"약한 팔다리", "F", "부족한 힘", "힘이 필요한 상황에서 불리하게 작용합니다."},
            {"예민한 신경", "F", "약한 멘탈", "공포나 혼란 상황에서 정신력 유지가 더 어렵습니다."},
            {"낮은 친화력", "F", "서툰 사교성", "대인 관계가 필요한 상황에서 불리하게 작용합니다."},
            {"불운한 경향", "F", "겹치는 불운", "예상치 못한 불운이 발생할 가능성이 높아집니다."},
            {"무딘 감각", "F", "둔한 촉", "상황의 낌새를 파악하는 데 불리합니다."}
        };

        for (int i = 0; i < vals.length; i++) {
            if (vals[i] >= 8) {
                traits.add(new TraitData("init_pos_" + i, pos[i][0], pos[i][1],
                    pos[i][2], false, pos[i][3]));
            } else if (vals[i] <= 2) {
                traits.add(new TraitData("init_neg_" + i, neg[i][0], neg[i][1],
                    neg[i][2], false, neg[i][3]));
            }
        }
        return traits;
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

    private String buildRoleContext(JsonObject roleData) {
        if (roleData == null) return null;
        StringBuilder sb = new StringBuilder();
        if (roleData.has("name")) sb.append(roleData.get("name").getAsString());
        if (roleData.has("spawn_location")) sb.append(" / 위치: ").append(roleData.get("spawn_location").getAsString());
        if (roleData.has("initial_info")) {
            sb.append(" / 초기 정보: ");
            roleData.getAsJsonArray("initial_info").forEach(e -> sb.append(e.getAsString()).append(" "));
        }
        return sb.toString().trim();
    }

    private String buildAdjustPrompt(PlayerData pd, String roleContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("나이=").append(pd.age).append("세, 직업=").append(pd.job)
          .append(", 스탯: HP=").append(pd.hp[1]).append(" STR=").append(pd.str)
          .append(" SAN=").append(pd.san[1]).append(" CHA=").append(pd.cha)
          .append(" LUK=").append(pd.luk).append(" SPR=").append(pd.spr);
        if (roleContext != null && !roleContext.isBlank()) {
            sb.append("\n배역: ").append(roleContext);
        }
        sb.append("\n\n이 나이·직업·배역을 고려해 스탯을 보정해줘. "
            + "각 보정값은 -3~+3 범위 (직업 특성이 뚜렷할수록 더 강하게). reason에 보정 이유를 한 줄로 설명.");
        return sb.toString();
    }

    private void applyAiAdjustment(PlayerData pd, String raw) {
        try {
            String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
            int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
            if (s == -1 || e == -1) return;
            JsonObject j = GSON.fromJson(cleaned.substring(s, e + 1), JsonObject.class);
            pd.str = Math.max(1, pd.str + clamp(j, "str_adj"));
            pd.cha = Math.max(1, pd.cha + clamp(j, "cha_adj"));
            pd.luk = Math.max(1, pd.luk + clamp(j, "luk_adj"));
            pd.spr = Math.max(1, pd.spr + clamp(j, "spr_adj"));
            int hpA  = clamp(j, "hp_max_adj"),  sanA = clamp(j, "san_max_adj");
            pd.hp[1]  = Math.max(1, pd.hp[1]  + hpA);  pd.hp[0]  = pd.hp[1];
            pd.san[1] = Math.max(1, pd.san[1] + sanA); pd.san[0] = pd.san[1];
        } catch (Exception ignored) {}
    }

    private int clamp(JsonObject j, String key) {
        if (!j.has(key)) return 0;
        return Math.max(-3, Math.min(3, j.get(key).getAsInt()));
    }

    private int[] distributePoints(int total, int count, int min, int max) {
        int[] arr = new int[count];
        Arrays.fill(arr, min);
        int remaining = total - count * min;
        for (int i = 0; i < remaining; i++) {
            int idx;
            do { idx = RNG.nextInt(count); } while (arr[idx] >= max);
            arr[idx]++;
        }
        return arr;
    }

    private void boostLowest(PlayerData pd) {
        int[] vals = {pd.hp[1], pd.str, pd.san[1], pd.cha, pd.luk, pd.spr};
        int minIdx = 0;
        for (int i = 1; i < vals.length; i++) if (vals[i] < vals[minIdx]) minIdx = i;
        applyStatDelta(pd, minIdx, 1);
    }

    private void applyBonuses(PlayerData pd) {
        int[] bonuses = {4, 2, 1, -2};
        List<Integer> indices = new ArrayList<>(Arrays.asList(0,1,2,3,4,5));
        Collections.shuffle(indices, RNG);
        for (int i = 0; i < bonuses.length; i++) applyStatDelta(pd, indices.get(i), bonuses[i]);
    }

    private void applyStatDelta(PlayerData pd, int idx, int delta) {
        switch (idx) {
            case 0 -> { pd.hp[0]  = Math.max(1, pd.hp[0]  + delta); pd.hp[1]  = pd.hp[0]; }
            case 1 -> pd.str = Math.max(1, pd.str + delta);
            case 2 -> { pd.san[0] = Math.max(1, pd.san[0] + delta); pd.san[1] = pd.san[0]; }
            case 3 -> pd.cha = Math.max(1, pd.cha + delta);
            case 4 -> pd.luk = Math.max(1, pd.luk + delta);
            case 5 -> pd.spr = Math.max(1, pd.spr + delta);
        }
    }

    String statKorName(String name) {
        return switch (name) {
            case "hp"  -> "체력";
            case "str" -> "근력";
            case "san" -> "정신력";
            case "cha" -> "매력";
            case "luk" -> "행운";
            case "spr" -> "영감";
            default    -> name;
        };
    }

    // ──────────────────────────────────────────────────────────────
    //  채팅 시트 (DialogManager 에서 대체하지만 백업용)
    // ──────────────────────────────────────────────────────────────

    public String buildSheetMessage(PlayerData pd, int roomNumber, int attempt) {
        StringBuilder sb = new StringBuilder();
        sb.append("§f─────────────────────────\n");
        sb.append("§7나이: §f").append(pd.age).append("세  §7직업: §f").append(pd.job).append("\n");
        sb.append("§c체력 §f").append(DialogManager.hpDisplay(pd.hp))
          .append("  §9근력 §f").append(pd.str).append("\n");
        sb.append("§b정신력 §f").append(DialogManager.hpDisplay(pd.san))
          .append("  §a매력 §f").append(pd.cha).append("\n");
        sb.append("§6행운 §f").append(pd.luk)
          .append("  §d영감 §f").append(pd.spr).append("\n");
        if (!pd.traits.isEmpty()) {
            sb.append("§e[특성]\n");
            pd.traits.forEach(t -> sb.append("§7").append(t.toDisplayLine()).append("\n"));
        }
        sb.append("§f─────────────────────────");
        return sb.toString();
    }
}
