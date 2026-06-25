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
  {"id":"","name":"","grade":"","description":"","active":false,"effect":""},
  ...
]
grade는 S/A/B/C/D/F 중 하나.
active는 플레이어가 직접 발동해야 하면 true, 자동 발동이면 false.
description: 아주 짧은 명사구 한 줄(최대 18자). 장황한 설명·문장 금지. 예: "어둠 속 시야 확보", "공포 내성".
effect: 효과를 한 문장으로 간결하게.
★ 범용성: 이 특성은 클리어 보상으로 영구히 남아 다른 시나리오에서도 쓰인다.
  특정 괴담·장소·사건에 묶이지 않는 범용 능력으로 작성하되, 이번 테마에서 특히 빛나도록 한다.
  (예: "거울 파괴" 같은 특정 사건 한정 ✗ → "반사체 간파"처럼 일반화 ✓)
한국어로 작성. 창의적인 특성 3개.
""";

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
  {"id":"","name":"","grade":"","description":"","active":false,"effect":""},
  ...
]
grade는 S/A/B/C/D/F 중 하나. 배역 초기 특성이므로 B~D 범위.
active는 직접 발동하면 true, 자동 발동이면 false.
description과 effect에 스탯 숫자·스탯 약어(STR/HP/SAN/CHA/LUK/SPR) 절대 사용 금지.
description: 아주 짧은 명사구 한 줄(최대 18자). 장황한 설명·문장 금지. 예: "응급 처치 솜씨", "낯선 곳 길눈".
effect: 한 문장으로 간결하게.
한국어로 작성.
""";

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
[{"name":"","description":"","active":false,"effect":""},...]
규칙:
- 같은 정체성을 유지하되 효과를 더 강력·안정적으로
- 이름은 강화된 느낌으로 바꿔도 좋음
- description: 아주 짧은 명사구 한 줄(최대 18자). 장황 금지
- effect: 강화된 효과를 한 문장으로
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
                    td.roleSpecific = false;     // 강화 보상은 영구
                    td.replacesId   = src.id;    // 원본 대체
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
            case "F" -> "D";
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
    }

    public boolean removeTrait(PlayerData pd, String traitId) {
        return pd.traits.removeIf(t -> t.id.equals(traitId));
    }

    public Optional<TraitData> getTrait(PlayerData pd, String traitId) {
        return pd.traits.stream().filter(t -> t.id.equals(traitId)).findFirst();
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
