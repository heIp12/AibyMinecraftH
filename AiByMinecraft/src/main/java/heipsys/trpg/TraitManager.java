package heipsys.trpg;

import com.google.gson.*;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;

import java.util.*;
import java.util.concurrent.CompletableFuture;

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
한국어로 작성. 창의적이고 괴담 테마와 어울리는 특성 3개.
""";

        String prompt = "클리어 등급: " + clearGrade
            + "\n생성 특성 등급 범위: " + gradeHint
            + "\n플레이어 직업: " + pd.job
            + "\n플레이어 나이: " + pd.age + "세"
            + "\n괴담 테마: " + gdamTheme
            + "\n\n위 조건에 맞는 특성 3개를 JSON 배열로 생성해줘.";

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
            sb.append("§c[4] 기존 특성 1개 제거\n");
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
