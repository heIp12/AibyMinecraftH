package heipsys.trpg;

import com.google.gson.*;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 특성 시스템 (STEP 3-2).
 * 클리어 후 AI가 3개 생성 → 플레이어 선택 → 보유 / 기존 특성 1개 제거.
 */
public class TraitManager {

    private final AiManager aiManager;
    private final Gson gson = new Gson();

    // 클리어 등급 → 특성 등급 풀 매핑
    private static final Map<String, String[]> GRADE_POOL = Map.of(
        "S", new String[]{"S","A"},
        "A", new String[]{"A","B"},
        "B", new String[]{"B","C"},
        "C", new String[]{"C","D"},
        "D", new String[]{"D","F"}
    );

    public TraitManager(AiManager aiManager) {
        this.aiManager = aiManager;
    }

    /**
     * AI JSON에서 effect_type / effect_params를 읽어 TraitData에 반영하고 기본값을 보정한다.
     * 기계 효과를 쓰지 않는 일반 특성이면 effectType은 빈 문자열로 남는다.
     */
    private void parseEffect(TraitData td, JsonObject obj) {
        if (obj.has("origin") && !obj.get("origin").isJsonNull()) {
            td.origin = obj.get("origin").getAsString().trim(); // 개인화 발현(유산·성향)
        }
        if (obj.has("effect_type") && !obj.get("effect_type").isJsonNull()) {
            td.effectType = obj.get("effect_type").getAsString().trim();
        }
        if (obj.has("effect_params") && obj.get("effect_params").isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : obj.getAsJsonObject("effect_params").entrySet()) {
                try { td.effectParams.put(en.getKey(), en.getValue().getAsInt()); }
                catch (Exception ignored) {}
            }
        }
        if (td.originGrade == null || td.originGrade.isEmpty()) td.originGrade = td.grade; // 출신 등급 기록(최초 생성 시 1회)
        SystemTraitRegistry.applyDefaults(td); // active 강제 + 파라미터 보정 (비시스템이면 무시)
    }

    // ──────────────────────────────────────────────────────────────
    //  클리어 후 특성 3개 생성
    // ──────────────────────────────────────────────────────────────

    public CompletableFuture<List<TraitData>> generateClearTraits(
            String clearGrade, PlayerData pd, String gdamTheme) {

        String[] pool = GRADE_POOL.getOrDefault(clearGrade, new String[]{"C","D"});
        String gradeHint = String.join(" 또는 ", pool);

        String system = """
너는 TRPG 특성 생성기야.
아래 JSON 배열 형식으로만 응답해 (다른 텍스트 금지):
[
  {"id":"","name":"","grade":"","description":"","active":false,"effect":"",
   "cooldown_turns":0,"str_add":0,"cha_add":0,"luk_add":0,"spr_add":0,"hp_max_add":0,"san_max_add":0,
   "effect_type":"","effect_params":{},"origin":""},
  ...
]
grade는 S/A/B/C/D/E/F 중 하나. (E·F=단점·제약 위주의 고위험 등급; 보상 특성은 보통 D 이상)
active는 플레이어가 직접 발동해야 하면 true, 자동 발동이면 false.
description: 아주 짧은 명사구 한 줄(최대 18자). 장황한 설명·문장 금지. 예: "어둠 속 시야 확보", "공포 내성".
effect: 효과를 한 문장으로 간결하게.

## cooldown_turns 설정 기준 (active=true 특성만 해당):
- 강력한 능동 특성(S/A급): 3~5 또는 -1(스테이지당 1회)
- 보통 능동 특성(B급): 1~2
- 약한 능동 특성(C/D급): 0~1
- 수동 특성(active=false): 반드시 0

## 스탯 보정 설정 기준 (등급 = 스텟 + 패시브 + 발동형 3요소 파워 합산):
- 모든 값은 정수. 스텟 파워의 양의 보정 총합 예산 → F=-2, E=-1, D=0, C=1, B=3, A=5, S=10. (E/F는 디버프·제약 위주라 순값이 음수)
- 패시브(active=false)나 발동형(active=true) 기계효과가 있으면 그만큼 스탯 예산을 줄인다(셋의 합이 등급).
- 다른 스탯에 -를 주면 그만큼 +를 더 줄 수 있다 (예: B급 str+4·cha-1 = 순합 3).
- effect_type="" 인 '순수 능력치 특성'도 좋은 변주다 (예산을 스탯에 전부 투자, 예: A급 str+5, S급 합 10).
- 그냥 -만 주는 결점형 스탯 특성도 가능(약체·개성용). hp_max/san_max(체력·정신력)도 다른 스텟과 동일하게 1점=1로 계산(표시만 %).
- E/F등급: 단점·제약이 많아 순값이 음수(E≈-1, F≈-2). 단 '좋은 효과 1개'를 큰 단점으로 상쇄해 달 수 있다(고위험·고개성).

★ 범용성: 이 특성은 클리어 보상으로 영구히 남아 다른 시나리오에서도 쓰인다.
  특정 괴담·장소·사건에 묶이지 않는 범용 능력으로 작성하되, 이번 테마에서 특히 빛나도록 한다.
한국어로 작성. 창의적인 특성 3개.

""" + SystemTraitRegistry.buildAiCatalog();

        String prompt = "클리어 등급: " + clearGrade
            + "\n생성 특성 등급 범위: " + gradeHint
            + "\n플레이어 직업: " + pd.job
            + "\n플레이어 나이: " + pd.age + "세"
            + "\n이번 괴담 테마(참고용, 직접 언급 금지): " + gdamTheme
            + "\n\n다른 시나리오에서도 통하는 범용 특성 3개를 JSON 배열로 생성해줘. "
            + "이번 테마에서 특히 유용하되, 설명에 이번 사건을 직접 언급하지 마.";

        return aiManager.callAssistant(system, prompt).thenApply(raw -> {
            try {
                String cleaned = raw.replaceAll("```json","").replaceAll("```","").trim();
                int s = cleaned.indexOf('['), e = cleaned.lastIndexOf(']');
                if (s == -1 || e == -1) return Collections.emptyList();
                JsonArray arr = gson.fromJson(cleaned.substring(s, e+1), JsonArray.class);
                List<TraitData> result = new ArrayList<>();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    TraitData td = new TraitData();
                    td.id          = obj.has("id")          ? obj.get("id").getAsString()          : UUID.randomUUID().toString().substring(0,8);
                    td.name        = obj.has("name")        ? obj.get("name").getAsString()        : "알 수 없는 특성";
                    td.grade       = obj.has("grade")       ? obj.get("grade").getAsString()       : "C";
                    td.description = obj.has("description") ? obj.get("description").getAsString() : "";
                    td.active      = obj.has("active")      && obj.get("active").getAsBoolean();
                    td.effect      = obj.has("effect")      ? obj.get("effect").getAsString()      : "";
                    td.cooldownTurns = obj.has("cooldown_turns") ? obj.get("cooldown_turns").getAsInt() : 0;
                    td.str_add = obj.has("str_add") ? obj.get("str_add").getAsInt() : 0;
                    td.cha_add = obj.has("cha_add") ? obj.get("cha_add").getAsInt() : 0;
                    td.luk_add = obj.has("luk_add") ? obj.get("luk_add").getAsInt() : 0;
                    td.spr_add = obj.has("spr_add") ? obj.get("spr_add").getAsInt() : 0;
                    td.hp_max_add = obj.has("hp_max_add") ? obj.get("hp_max_add").getAsInt() : 0;
                    td.san_max_add = obj.has("san_max_add") ? obj.get("san_max_add").getAsInt() : 0;
                    parseEffect(td, obj);
                    result.add(td);
                }
                return result;
            } catch (Exception ex) {
                return Collections.emptyList();
            }
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  배역 배정 시 추가 특성 1~2개 생성
    // ──────────────────────────────────────────────────────────────

    public CompletableFuture<List<TraitData>> generateRoleTraits(PlayerData pd, JsonObject roleData) {
        StringBuilder roleCtx = new StringBuilder();
        roleCtx.append("배역 이름: ");
        roleCtx.append(roleData.has("name") ? roleData.get("name").getAsString()
            : roleData.get("role_id").getAsString());
        if (roleData.has("initial_info")) {
            roleCtx.append("\n배역 초기 정보: ");
            roleData.getAsJsonArray("initial_info").forEach(e -> roleCtx.append(e.getAsString()).append(" "));
        }
        if (roleData.has("spawn_location") && !roleData.get("spawn_location").getAsString().isBlank()) {
            roleCtx.append("\n시작 위치: ").append(roleData.get("spawn_location").getAsString());
        }

        String existingTraits = pd.traits.isEmpty() ? "없음"
            : pd.traits.stream().map(t -> t.name).collect(Collectors.joining(", "));

        String system = """
너는 TRPG 특성 생성기야.
아래 JSON 배열 형식으로만 응답해 (다른 텍스트 금지):
[
  {"id":"","name":"","grade":"","description":"","active":false,"effect":"",
   "cooldown_turns":0,"str_add":0,"cha_add":0,"luk_add":0,"spr_add":0,"hp_max_add":0,"san_max_add":0,
   "effect_type":"","effect_params":{},"origin":""},
  ...
]
grade는 S/A/B/C/D/E/F. 배역 초기 특성이므로 B~D 범위(드물게 결점형 E/F로 약체·고위험 배역 개성 표현 가능).
active는 직접 발동하면 true, 자동 발동이면 false.

## 스포일러 금지 · 범용성 (★ 최우선)
특성은 '범용 감각·기술·재능'이어야 한다. 괴담의 정체나 핵심 소재(아이·거울·물·불·인형 등 특정 대상)를
특성 안에서 절대 암시하지 마라. 누설하면 플레이어가 사건을 즉시 간파해 재미가 사라진다.
효과는 반드시 '일반 상황'에 통하는 범용 표현으로만 적되, 이번 챕터에서도 실제로 도움이 되도록 설계한다.
(특정 사건에서 특히 빛나는 건 좋지만, 글로는 그 사건을 드러내지 않는다.)
- 나쁜 예 ✗: "아이의 울음소리를 들으면 분위기를 파악" (괴담이 '아이'라고 누설)
- 나쁜 예 ✗: "거울 속 존재를 감지한다" (괴담이 '거울'이라고 누설)
- 좋은 예 ✓: "예민한 청각 — 미세한 소리를 멀리서 감지" (범용, 이번 챕터에도 유효)
- 좋은 예 ✓: "위기 직감 — 위험이 닥치면 소름이 돋는다" (범용, 두루 쓰임)
배역의 직업·성격에서 착안하되 괴담 소재는 직접 언급 금지.
hidden_info에 해결 수단(아이템·경로·조작법)이 포함되면, 그 '존재·방법'만 알게 하고 '왜 필요한지(괴담 약점·해법과의 연결)'는 캐릭터가 인지하지 못한 상태로 설계하라. 정답을 처음부터 쥐여주지 마라(용도는 플레이 중 발견).

description과 effect에 스탯 숫자·스탯 약어(STR/HP/SAN/CHA/LUK/SPR) 절대 사용 금지.
description: 아주 짧은 명사구 한 줄(최대 18자). 장황한 설명·문장 금지. 예: "응급 처치 솜씨", "낯선 곳 길눈".
effect: 한 문장으로 간결하게.
cooldown_turns: B~D급이므로 능동이면 0~2, 수동이면 반드시 0.
str_add 등 스탯 보정: 양의 총합 예산 D=0/C=1/B=3 (배역 초기 특성은 B~D). 다른 스탯 -로 상쇄 시 +추가 가능. effect_type="" 순수 능력치 특성도 허용(예산을 스탯에 투자).
한국어로 작성.

""" + SystemTraitRegistry.buildAiCatalog();

        String prompt = "직업: " + pd.job + "\n나이: " + pd.age + "세\n"
            + "기존 특성(중복 금지): " + existingTraits + "\n"
            + roleCtx + "\n\n위 배역에 맞는 추가 특성 1~2개를 JSON 배열로 생성해줘.";

        return aiManager.callAssistant(system, prompt).thenApply(raw -> {
            try {
                String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
                int s = cleaned.indexOf('['), e = cleaned.lastIndexOf(']');
                if (s == -1 || e == -1) return Collections.emptyList();
                JsonArray arr = gson.fromJson(cleaned.substring(s, e + 1), JsonArray.class);
                List<TraitData> result = new ArrayList<>();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    TraitData td = new TraitData();
                    td.id          = obj.has("id")          ? obj.get("id").getAsString()          : UUID.randomUUID().toString().substring(0, 8);
                    td.name        = obj.has("name")        ? obj.get("name").getAsString()        : "알 수 없는 특성";
                    td.grade       = obj.has("grade")       ? obj.get("grade").getAsString()       : "C";
                    td.description = obj.has("description") ? obj.get("description").getAsString() : "";
                    td.active      = obj.has("active")      && obj.get("active").getAsBoolean();
                    td.effect      = obj.has("effect")      ? obj.get("effect").getAsString()      : "";
                    td.cooldownTurns = obj.has("cooldown_turns") ? obj.get("cooldown_turns").getAsInt() : 0;
                    td.str_add = obj.has("str_add") ? obj.get("str_add").getAsInt() : 0;
                    td.cha_add = obj.has("cha_add") ? obj.get("cha_add").getAsInt() : 0;
                    td.luk_add = obj.has("luk_add") ? obj.get("luk_add").getAsInt() : 0;
                    td.spr_add = obj.has("spr_add") ? obj.get("spr_add").getAsInt() : 0;
                    td.hp_max_add = obj.has("hp_max_add") ? obj.get("hp_max_add").getAsInt() : 0;
                    td.san_max_add = obj.has("san_max_add") ? obj.get("san_max_add").getAsInt() : 0;
                    parseEffect(td, obj);
                    td.roleSpecific = true;
                    result.add(td);
                }
                return result;
            } catch (Exception ex) {
                return Collections.emptyList();
            }
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  클리어 보상: 이번 세션 특성 강화 선택지 생성
    // ──────────────────────────────────────────────────────────────

    /**
     * 이번 세션에서 보유한 무작위 특성들의 '강화 버전'을 생성한다.
     * 선택 시 원본을 대체하며 영구 특성이 된다(roleSpecific=false, replacesId=원본).
     * 배역 특성(챕터 종료 시 사라질 것)을 우선 후보로 한다.
     */
    public CompletableFuture<List<TraitData>> generateEnhancedTraits(PlayerData pd, String gdamTheme) {
        List<TraitData> candidates = new ArrayList<>();
        pd.traits.stream().filter(t -> t.roleSpecific).forEach(candidates::add);
        pd.traits.stream().filter(t -> !t.roleSpecific).forEach(candidates::add);
        if (candidates.size() > 3) candidates = new ArrayList<>(candidates.subList(0, 3));
        if (candidates.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyList());

        final List<TraitData> cands = candidates;
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < cands.size(); i++) {
            TraitData t = cands.get(i);
            list.append(i + 1).append(". 이름:").append(t.name)
                .append(" / 등급:").append(t.grade)
                .append(" / 효과:").append(t.effect)
                .append(" / active:").append(t.active).append("\n");
        }

        String system = """
너는 TRPG 특성 강화기야.
입력된 기존 특성들을 '한 단계 강화된 버전'으로 다시 써라.
아래 JSON 배열로만 응답 (입력과 같은 개수·같은 순서):
[{"name":"","description":"","active":false,"effect":"","cooldown_turns":0,"str_add":0,"cha_add":0,"luk_add":0,"spr_add":0,"hp_max_add":0,"san_max_add":0},...]
규칙:
- 같은 정체성을 유지하되 효과를 더 강력·안정적으로
- 이름은 강화된 느낌으로 바꿔도 좋음
- description: 아주 짧은 명사구 한 줄(최대 18자). 장황 금지
- effect: 강화된 효과를 한 문장으로
- cooldown_turns: 원본보다 1 낮거나 같게 (최소 0). 수동이면 0 유지.
- 스탯 보정: 강화 시 원본 대비 소폭 상향 가능 (과도한 보정 금지)
- 범용성: 다른 시나리오에서도 통하게, 특정 사건 직접 언급 금지
- 스탯 약어(STR/HP/SAN/CHA/LUK/SPR) 금지. 한국어.
""";
        String prompt = "이번 테마(참고용, 직접 언급 금지): " + gdamTheme
            + "\n강화할 기존 특성:\n" + list
            + "\n위 각 특성의 강화 버전을 같은 순서로 JSON 배열로 생성해줘.";

        return aiManager.callAssistant(system, prompt).thenApply(raw -> {
            try {
                String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
                int s = cleaned.indexOf('['), e = cleaned.lastIndexOf(']');
                if (s == -1 || e == -1) return Collections.<TraitData>emptyList();
                JsonArray arr = gson.fromJson(cleaned.substring(s, e + 1), JsonArray.class);
                List<TraitData> result = new ArrayList<>();
                for (int i = 0; i < arr.size() && i < cands.size(); i++) {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    TraitData src = cands.get(i);
                    TraitData td = new TraitData();
                    td.id          = "enh_" + UUID.randomUUID().toString().substring(0, 6);
                    td.name        = obj.has("name") && !obj.get("name").getAsString().isBlank()
                                     ? obj.get("name").getAsString() : src.name;
                    td.grade       = bumpGrade(src.grade); // 원본보다 항상 한 단계 위 보장
                    td.description = obj.has("description") ? obj.get("description").getAsString() : src.description;
                    td.active      = obj.has("active") ? obj.get("active").getAsBoolean() : src.active;
                    td.effect      = obj.has("effect") ? obj.get("effect").getAsString() : src.effect;
                    int baseCd = obj.has("cooldown_turns") ? obj.get("cooldown_turns").getAsInt() : src.cooldownTurns;
                    // baseCd < 0 = 스테이지당 1회(-1); 유지. 양수면 1 감소(최소 0).
                    td.cooldownTurns = (baseCd < 0) ? baseCd : Math.max(0, baseCd - 1);
                    td.str_add = obj.has("str_add") ? obj.get("str_add").getAsInt() : src.str_add;
                    td.cha_add = obj.has("cha_add") ? obj.get("cha_add").getAsInt() : src.cha_add;
                    td.luk_add = obj.has("luk_add") ? obj.get("luk_add").getAsInt() : src.luk_add;
                    td.spr_add = obj.has("spr_add") ? obj.get("spr_add").getAsInt() : src.spr_add;
                    td.hp_max_add = obj.has("hp_max_add") ? obj.get("hp_max_add").getAsInt() : src.hp_max_add;
                    td.san_max_add = obj.has("san_max_add") ? obj.get("san_max_add").getAsInt() : src.san_max_add;
                    // 시스템 효과는 원본에서 계승 (강화해도 같은 기계 효과 유지)
                    td.effectType   = src.effectType;
                    td.effectParams = new HashMap<>(src.effectParams);
                    td.origin       = src.origin; // 같은 캐릭터의 강화판 — 발현 출처 계승
                    td.originGrade  = (src.originGrade == null || src.originGrade.isEmpty()) ? src.grade : src.originGrade; // 출신 등급 계승
                    SystemTraitRegistry.applyDefaults(td);
                    td.roleSpecific = false;     // 강화 보상은 영구
                    td.replacesId   = src.id;    // 원본 대체
                    td.level    = src.level + 1; // 강화 = 레벨 상승 (능력 Phase C)
                    td.maxLevel = Math.max(src.maxLevel, td.level);
                    result.add(td);
                }
                return result;
            } catch (Exception ex) {
                return Collections.<TraitData>emptyList();
            }
        });
    }

    /** 등급 한 단계 상승 (F→D→C→B→A→S) */
    private static String bumpGrade(String g) {
        return switch (g == null ? "" : g) {
            case "F" -> "E";
            case "E" -> "D";
            case "D" -> "C";
            case "C" -> "B";
            case "B" -> "A";
            default  -> "S";
        };
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 부여 / 제거
    // ──────────────────────────────────────────────────────────────

    public void addTrait(PlayerData pd, TraitData trait) {
        pd.traits.add(trait);
        pd.str     += trait.str_add;
        pd.cha     += trait.cha_add;
        pd.luk     += trait.luk_add;
        pd.spr     += trait.spr_add;
        if (trait.hp_max_add != 0) {
            pd.hp[1] = Math.max(1, pd.hp[1] + trait.hp_max_add);
            pd.hp[0] = Math.min(pd.hp[0], pd.hp[1]);
        }
        if (trait.san_max_add != 0) {
            pd.san[1] = Math.max(1, pd.san[1] + trait.san_max_add);
            pd.san[0] = Math.min(pd.san[0], pd.san[1]);
        }
    }

    public boolean removeTrait(PlayerData pd, String traitId) {
        Optional<TraitData> found = pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst();
        if (found.isEmpty()) return false;
        TraitData trait = found.get();
        pd.str     -= trait.str_add;
        pd.cha     -= trait.cha_add;
        pd.luk     -= trait.luk_add;
        pd.spr     -= trait.spr_add;
        if (trait.hp_max_add != 0) {
            pd.hp[1] = Math.max(1, pd.hp[1] - trait.hp_max_add);
            pd.hp[0] = Math.min(pd.hp[0], pd.hp[1]);
        }
        if (trait.san_max_add != 0) {
            pd.san[1] = Math.max(1, pd.san[1] - trait.san_max_add);
            pd.san[0] = Math.min(pd.san[0], pd.san[1]);
        }
        return pd.traits.remove(trait);
    }

    public Optional<TraitData> getTrait(PlayerData pd, String traitId) {
        return pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst();
    }

    // ──────────────────────────────────────────────────────────────
    //  스테이지 종료 특성 성장 3선택지
    // ──────────────────────────────────────────────────────────────

    public record StageEndChoices(
        TraitData myUpgrade,   // 플레이어 특성 강화 (replacesId 세팅)
        TraitData mapUpgrade,  // 맵 특성 → 범용 강화 (replacesId 세팅, roleSpecific=false)
        TraitData newTrait,    // 신규 범용 특성
        TraitData srcMyTrait,  // myUpgrade의 원본 특성 (쿨다운 비교용)
        TraitData srcMapTrait  // mapUpgrade의 원본 특성 (쿨다운 비교용)
    ) {}

    /**
     * 스테이지 클리어 후 3가지 특성 성장 선택지를 AI로 생성한다.
     * myUpgrade: 기여도 높은 플레이어(영구) 특성 강화
     * mapUpgrade: 기여도 높은 배역 전용 특성 → 범용 강화
     * newTrait: 새 범용 특성
     */
    public CompletableFuture<StageEndChoices> generateStageEndChoices(PlayerData pd, String gdamTheme, int gradeBoost, int powerBonus) {
        // 기여도(사용 횟수) 높은 순으로 선택
        TraitData bestPlayer = pd.traits.stream()
            .filter(t -> !t.roleSpecific)
            .max(Comparator.comparingInt((TraitData t) -> t.usedThisStage)
                .thenComparingInt(t -> gradeToInt(t.grade)))
            .orElse(null);
        TraitData bestMap = pd.traits.stream()
            .filter(t -> t.roleSpecific)
            .max(Comparator.comparingInt((TraitData t) -> t.usedThisStage)
                .thenComparingInt(t -> gradeToInt(t.grade)))
            .orElse(null);

        // 표시 등급 보정: gradeBoost(=성과)만 표시(명목) 등급을 올린다 (프롬프트·파싱 양쪽에서 동일 적용).
        // 실효 파워 보강(표시 등급엔 미반영): ⓐ낮은 출신(originGrade) 강화 보너스 + ⓑ시작 약세(powerBonus).
        //   → '약했던 만큼' 같은 표시 등급이라도 효과는 더 강하게 (보상 등급 인플레 없이 약자 보정).
        String myOrigin  = (bestPlayer != null)
            ? (bestPlayer.originGrade == null || bestPlayer.originGrade.isEmpty() ? bestPlayer.grade : bestPlayer.originGrade) : "";
        int    oBonus    = (bestPlayer != null) ? Math.max(0, gradeToInt("D") - gradeToInt(myOrigin)) : 0; // F=+2, E=+1, D이상=0
        int    effBonus  = oBonus + Math.max(0, powerBonus);  // 실효 파워 가산 (표시 등급엔 미반영)
        String myNominal = bumpGrade(bestPlayer != null ? computeUpgradeGrade(bestPlayer) : "B", gradeBoost); // 표시(명목) 등급 — 정상 climb
        String myTarget  = bumpGrade(myNominal, effBonus); // AI에 알릴 '실효 파워' 등급(효과·스탯 강도 기준)
        String mapOrigin = (bestMap != null)
            ? (bestMap.originGrade == null || bestMap.originGrade.isEmpty() ? bestMap.grade : bestMap.originGrade) : "";
        String mapTarget = bestMap != null ? bumpGrade(computeUpgradeGrade(bestMap), gradeBoost) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("괴담 테마(직접 언급 금지): ").append(gdamTheme).append("\n");
        sb.append("플레이어 직업: ").append(pd.job).append("\n");
        sb.append("플레이어 기여도: ").append(pd.contribution)
          .append(" (강화는 목표레벨×3 기여도를 소비한다. 부족하면 강화가 보류될 수 있으니, 기여도가 적으면 new_trait 비중을 높여라.)\n\n");

        if (bestPlayer != null) {
            sb.append("## 내 특성 강화 대상\n")
              .append("이름: ").append(bestPlayer.name).append("\n")
              .append("현재 등급: ").append(bestPlayer.grade).append("\n")
              .append("효과: ").append(bestPlayer.effect).append("\n")
              .append("이번 게임 사용 횟수: ").append(bestPlayer.usedThisStage).append("\n")
              .append("목표 등급(실효 파워): ").append(myTarget)
              .append(effBonus > 0
                  ? " ※약세 보정 — 효과·스탯을 이 '실효 등급(" + myTarget + ")' 수준으로 강하게 만들어라(표시 등급은 시스템이 명목으로 낮춰 보여주고 예산도 자동 처리한다).\n"
                  : "\n");
            appendDownsideContext(sb, bestPlayer);
            sb.append("\n");
        } else {
            sb.append("## 내 특성 강화 대상: 없음 (new_trait2 항목 대신 생성)\n\n");
        }

        if (bestMap != null) {
            sb.append("## 맵 전용 특성 → 범용화 대상\n")
              .append("이름: ").append(bestMap.name).append("\n")
              .append("현재 등급: ").append(bestMap.grade).append("\n")
              .append("효과: ").append(bestMap.effect).append("\n")
              .append("이번 게임 사용 횟수: ").append(bestMap.usedThisStage).append("\n")
              .append("목표 등급: ").append(mapTarget).append("\n");
            appendDownsideContext(sb, bestMap);
            sb.append("\n");
        } else {
            sb.append("## 맵 전용 특성 → 범용화 대상: 없음 (null로 응답)\n\n");
        }

        if (gradeBoost > 0) {
            sb.append("## 오염 보정 (중요)\n")
              .append("오염도 보정으로 보상 등급이 ").append(gradeBoost)
              .append("단계 상향되었다. 위 '목표 등급'은 이미 보정된 값이며, new_trait도 평소보다 강력하고 가치 있게 생성하라.\n\n");
        }

        String system = """
너는 TRPG 특성 성장 시스템이야.
아래 JSON 형식으로만 응답 (다른 텍스트 금지):
{"my_upgrade":{...},"map_upgrade":{...},"new_trait":{...}}
각 특성 JSON 스키마:
{"id":"","name":"","grade":"","description":"","active":false,"effect":"","cooldown_turns":0,"str_add":0,"cha_add":0,"luk_add":0,"spr_add":0,"hp_max_add":0,"san_max_add":0}

my_upgrade 규칙:
- '내 특성 강화 대상'의 강화 버전. 대상이 없으면 새 범용 특성 생성.
- grade는 반드시 '목표 등급'을 사용 (대상이 없으면 B).
- 강화 방향은 특성의 단점 크기에 따라 선택:
  A) 긍정 효과 강화·정교화 (단점이 없거나 작을 때 기본)
  B) 단점 완화 — 쿨다운 단축, 패널티 스탯 개선, 발동 조건 완화 (단점이 클 때 우선)
  C) 혼합 — 긍정 효과 소폭 강화 + 단점 소폭 완화
- 반드시 긍정 효과만 키워야 하는 건 아님. 단점이 있으면 완화가 가장 가치 있는 강화일 수 있음.
- 같은 정체성·이름 유지. 범용성 유지.

map_upgrade 규칙:
- '맵 전용 특성 → 범용화 대상'의 범용 강화 버전.
- 대상이 없으면 null로 응답.
- grade는 반드시 '목표 등급'을 사용.
- 시나리오 한정 내용 제거, 다른 상황에서도 통하게 범용화. 사건 직접 언급 금지.
- 단점이 있으면 범용화하면서 단점도 완화할 수 있음.

new_trait: 완전히 새로운 범용 특성. 직업·테마에서 착안하되 사건 직접 언급 금지. 단점 없이 순수 긍정이어도 됨.

공통:
- description: 최대 18자 명사구 한 줄. 장황 금지.
- effect: 한 문장으로 간결하게.
- 스탯 약어(STR/HP/SAN/CHA/LUK/SPR) 절대 금지. 한국어.
- 각 특성 JSON에 effect_type/effect_params 필드를 둘 수 있다(아래 카탈로그 참고). 강화 시 원본의 효과를 유지·심화해도 좋다.

""" + SystemTraitRegistry.buildAiCatalog();
        String prompt = sb + "\n위 내용을 바탕으로 3가지 특성을 JSON으로 생성해줘.";

        final TraitData fp = bestPlayer;
        final TraitData fm = bestMap;

        return aiManager.callAssistant(system, prompt).thenApply(raw -> {
            try {
                String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
                int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
                if (s == -1 || e == -1) return null;
                JsonObject root = gson.fromJson(cleaned.substring(s, e + 1), JsonObject.class);

                // my_upgrade: 표시는 명목(myNominal), 실효 파워는 출신 보너스(myTarget)로 — enforceStatBudget가 실효 기준 적용
                TraitData myUpg = root.has("my_upgrade") && !root.get("my_upgrade").isJsonNull()
                    ? parseStageEndTrait(root.getAsJsonObject("my_upgrade"), myNominal, myOrigin) : null;
                if (myUpg != null && fp != null) myUpg.replacesId = fp.id;

                TraitData mapUpg = fm != null && root.has("map_upgrade") && !root.get("map_upgrade").isJsonNull()
                    ? parseStageEndTrait(root.getAsJsonObject("map_upgrade"), mapTarget, mapOrigin) : null;
                if (mapUpg != null) { mapUpg.replacesId = fm.id; mapUpg.roleSpecific = false; }

                TraitData newT = root.has("new_trait") && !root.get("new_trait").isJsonNull()
                    ? parseStageEndTrait(root.getAsJsonObject("new_trait"), null, null) : null;
                if (newT != null && gradeBoost > 0) newT.grade = bumpGrade(newT.grade, gradeBoost);

                return new StageEndChoices(myUpg, mapUpg, newT, fp, fm);
            } catch (Exception ex) {
                return null;
            }
        });
    }

    private TraitData parseStageEndTrait(JsonObject obj, String gradeOverride, String originGrade) {
        TraitData td = new TraitData();
        td.id = "se_" + UUID.randomUUID().toString().substring(0, 6);
        td.name        = obj.has("name")        ? obj.get("name").getAsString()        : "강화 특성";
        td.grade       = gradeOverride != null   ? gradeOverride
                       : (obj.has("grade")       ? obj.get("grade").getAsString()       : "C");
        td.description = obj.has("description") ? obj.get("description").getAsString() : "";
        td.active      = obj.has("active")      && obj.get("active").getAsBoolean();
        td.effect      = obj.has("effect")      ? obj.get("effect").getAsString()      : "";
        td.cooldownTurns = obj.has("cooldown_turns") ? obj.get("cooldown_turns").getAsInt() : 0;
        td.str_add    = obj.has("str_add")    ? obj.get("str_add").getAsInt()    : 0;
        td.cha_add    = obj.has("cha_add")    ? obj.get("cha_add").getAsInt()    : 0;
        td.luk_add    = obj.has("luk_add")    ? obj.get("luk_add").getAsInt()    : 0;
        td.spr_add    = obj.has("spr_add")    ? obj.get("spr_add").getAsInt()    : 0;
        td.hp_max_add = obj.has("hp_max_add") ? obj.get("hp_max_add").getAsInt() : 0;
        td.san_max_add = obj.has("san_max_add") ? obj.get("san_max_add").getAsInt() : 0;
        if (originGrade != null && !originGrade.isEmpty()) td.originGrade = originGrade; // 강화: 출신 등급 계승(낮은 출신 보너스 유지)
        parseEffect(td, obj);
        td.roleSpecific = false;
        return td;
    }

    /** 프롬프트에 특성의 단점 정보를 추가 (쿨다운·패널티 스탯이 있을 때만) */
    private void appendDownsideContext(StringBuilder sb, TraitData t) {
        boolean hasCd = t.cooldownTurns != 0;
        boolean hasPenalty = t.str_add < 0 || t.cha_add < 0 || t.luk_add < 0
                          || t.spr_add < 0 || t.hp_max_add < 0 || t.san_max_add < 0;
        if (!hasCd && !hasPenalty) return;

        sb.append("단점 정보:");
        if (t.cooldownTurns == -1) sb.append(" 스테이지당 1회 제한");
        else if (t.cooldownTurns > 0) sb.append(" 쿨다운 ").append(t.cooldownTurns).append("턴");
        if (hasPenalty) {
            if (t.str_add    < 0) sb.append(" 근력").append(t.str_add);
            if (t.cha_add    < 0) sb.append(" 매력").append(t.cha_add);
            if (t.luk_add    < 0) sb.append(" 행운").append(t.luk_add);
            if (t.spr_add    < 0) sb.append(" 영감").append(t.spr_add);
            if (t.hp_max_add < 0) sb.append(" 체력최대").append(t.hp_max_add);
            if (t.san_max_add< 0) sb.append(" 정신력최대").append(t.san_max_add);
        }
        sb.append("\n");
    }

    /** 기여도(usedThisStage) + 현재 등급 → 목표 등급 계산 */
    private String computeUpgradeGrade(TraitData t) {
        int u = t.usedThisStage;
        return switch (t.grade) {
            case "S" -> "S";
            case "A" -> u >= 3 ? "S" : "A";
            case "B" -> u >= 2 ? "A" : (u >= 1 ? "B" : "B");
            case "C" -> u >= 2 ? "B" : (u >= 1 ? "C" : "C");
            case "D" -> u >= 1 ? "C" : "D";
            case "E" -> u >= 1 ? "D" : "E";
            case "F" -> u >= 1 ? "E" : "F";
            default  -> t.grade;
        };
    }

    /** 등급을 steps 단계 상향 (F<E<D<C<B<A<S, 상한 S) */
    private String bumpGrade(String grade, int steps) {
        if (steps <= 0) return grade == null ? "C" : grade;
        String[] grades = {"F","E","D","C","B","A","S"};
        int idx = Math.min(gradeToInt(grade) + steps, grades.length - 1);
        return grades[idx];
    }

    private int gradeToInt(String g) {
        return switch (g == null ? "" : g) {
            case "S" -> 6; case "A" -> 5; case "B" -> 4; case "C" -> 3; case "D" -> 2; case "E" -> 1; default -> 0;
        };
    }

    /** 스테이지 종료/재도전 시 스테이지당 1회 쿨다운(-1)과 usedThisStage 초기화 */
    public void resetStageTraits(PlayerData pd) {
        for (TraitData t : pd.traits) {
            t.usedThisStage = 0;
            if (t.cooldownTurns == -1) t.remainingCooldown = 0;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  선택 화면 메시지 (채팅 기반)
    // ──────────────────────────────────────────────────────────────

    public String buildSelectionMessage(List<TraitData> choices, PlayerData pd) {
        StringBuilder sb = new StringBuilder();
        sb.append("§e§l═══ 특성 선택 ═══\n");
        for (int i = 0; i < choices.size(); i++) {
            TraitData t = choices.get(i);
            sb.append("§a[").append(i+1).append("] §f(").append(t.grade).append(") ")
              .append(t.name).append(": ").append(t.description).append("\n");
        }
        sb.append("§7────────────\n");
        if (!pd.traits.isEmpty()) {
            sb.append("§c[0] 기존 특성 1개 제거\n");
            sb.append("§7보유 특성:\n");
            for (int i = 0; i < pd.traits.size(); i++) {
                sb.append("  §7[").append(i).append("] ").append(pd.traits.get(i).name)
                  .append(" (").append(pd.traits.get(i).grade).append(")\n");
            }
        }
        sb.append("§e번호를 채팅으로 입력하세요.");
        return sb.toString();
    }
}
