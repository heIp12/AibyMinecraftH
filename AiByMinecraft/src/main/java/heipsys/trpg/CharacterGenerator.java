package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 캐릭터 스탯 생성 (STEP 3-1).
 *
 * 생성 순서:
 *  1. 나이 무작위 (5~80세)
 *  2. 직업 무작위 (나이/괴담 무관)
 *  3. 기본 스탯: 총합 25를 6개 스탯에 배분 → 최저 스탯 +1 → 무작위 스탯 [+4,+2,+1,-2]
 *  4. Haiku AI로 나이/직업 보정
 *  5. 특성 결정 (스탯 8이상 = 강점, 3이하 = 약점)
 */
public class CharacterGenerator {

    private static final Random RNG = new Random();

    // 방대한 직업 풀 (편향 없이 광범위)
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
        "조련사","어부","잠수부","등반가",
        "정치인","공무원","외교관","통역사"
    };

    private static final String[] STAT_NAMES = {"hp", "str", "san", "cha", "luk", "spr"};

    private final AiManager aiManager;

    public CharacterGenerator(AiManager aiManager) {
        this.aiManager = aiManager;
    }

    // ──────────────────────────────────────────────────────────────
    //  메인 생성
    // ──────────────────────────────────────────────────────────────

    /** 스탯 생성 후 Haiku로 나이/직업 보정 적용 */
    public CompletableFuture<PlayerData> generate(Player player) {
        PlayerData pd = new PlayerData(player.getUniqueId(), player.getName());
        rollStats(pd);

        String prompt = buildAdjustPrompt(pd);
        return aiManager.callAssistant(
            "너는 TRPG 캐릭터 스탯 보정기야. 아래 JSON 형식으로만 응답해:\n"
            + "{\"str_adj\":0,\"cha_adj\":0,\"luk_adj\":0,\"spr_adj\":0,"
            + "\"hp_max_adj\":0,\"san_max_adj\":0,\"reason\":\"\"}",
            prompt
        ).thenApply(raw -> {
            applyAiAdjustment(pd, raw);
            determineTraits(pd);
            pd.snapshotBase();
            return pd;
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  스탯 굴림
    // ──────────────────────────────────────────────────────────────

    public void rollStats(PlayerData pd) {
        pd.age = 5 + RNG.nextInt(76); // 5~80
        pd.job = JOB_POOL[RNG.nextInt(JOB_POOL.length)];

        // 총합 25를 6개 스탯에 무작위 배분 (각 스탯 최소 1)
        int[] stats = distributePoints(25, 6, 1, 10);

        pd.hp  = new int[]{stats[0], stats[0]};
        pd.str = stats[1];
        pd.san = new int[]{stats[2], stats[2]};
        pd.cha = stats[3];
        pd.luk = stats[4];
        pd.spr = stats[5];

        // 최저 스탯 +1
        boostLowest(pd);

        // 무작위 스탯에 [+4, +2, +1, -2] 순서 부여
        applyBonuses(pd);
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 결정
    // ──────────────────────────────────────────────────────────────

    public void determineTraits(PlayerData pd) {
        pd.traits.clear();
        int[] vals = getStatArray(pd);
        String[] names = STAT_NAMES;

        for (int i = 0; i < vals.length; i++) {
            if (vals[i] >= 8) {
                pd.traits.add(new TraitData(
                    "init_pos_" + i, statKorName(names[i]) + " 강점",
                    "C", "높은 " + statKorName(names[i]) + "에서 비롯된 강점.",
                    false, "해당 스탯 판정 +2"
                ));
            } else if (vals[i] <= 3) {
                pd.traits.add(new TraitData(
                    "init_neg_" + i, statKorName(names[i]) + " 약점",
                    "F", "낮은 " + statKorName(names[i]) + "에서 비롯된 약점.",
                    false, "해당 스탯 판정 -2"
                ));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  채팅 시트 출력 포맷 (GM AI가 아닌 플러그인이 직접 출력)
    // ──────────────────────────────────────────────────────────────

    public String buildSheetMessage(PlayerData pd, int roomNumber, int attempt) {
        StringBuilder sb = new StringBuilder();
        sb.append("§f─────────────────────────\n");
        sb.append("§eSeed: ").append("...").append(" / ").append(attempt).append("회차\n");
        sb.append("§f─────────────────────────\n");
        sb.append("§7나이: §f").append(pd.age).append("세\n");
        sb.append("§7직업: §f").append(pd.job).append("\n\n");
        sb.append("§c체력  §f").append(pd.hp[0]).append("/").append(pd.hp[1])
          .append("   §9근력 §f").append(pd.str).append("\n");
        sb.append("§b정신력 §f").append(pd.san[0]).append("/").append(pd.san[1])
          .append("   §a매력 §f").append(pd.cha).append("\n");
        sb.append("§6행운  §f").append(pd.luk)
          .append("   §d영감 §f").append(pd.spr).append("\n\n");

        if (!pd.traits.isEmpty()) {
            sb.append("§e[특성]\n");
            pd.traits.forEach(t -> sb.append("§7").append(t.toDisplayLine()).append("\n"));
        }
        sb.append("§f─────────────────────────\n");
        sb.append("§7확정하려면 §f확정 §7입력, 재굴림은 §f재굴림 §7입력 (").append(pd.diceRollsRemaining).append("회 남음)\n");
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

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
        int[] vals = getStatArray(pd);
        int minIdx = 0;
        for (int i = 1; i < vals.length; i++) if (vals[i] < vals[minIdx]) minIdx = i;
        applyStatDelta(pd, minIdx, 1);
    }

    private void applyBonuses(PlayerData pd) {
        int[] bonuses = {4, 2, 1, -2};
        List<Integer> indices = new ArrayList<>(Arrays.asList(0,1,2,3,4,5));
        Collections.shuffle(indices, RNG);
        for (int i = 0; i < bonuses.length; i++) {
            applyStatDelta(pd, indices.get(i), bonuses[i]);
        }
    }

    private int[] getStatArray(PlayerData pd) {
        return new int[]{pd.hp[1], pd.str, pd.san[1], pd.cha, pd.luk, pd.spr};
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

    private String buildAdjustPrompt(PlayerData pd) {
        return "나이=" + pd.age + "세, 직업=" + pd.job
            + ", 현재스탯: HP=" + pd.hp[1] + " STR=" + pd.str
            + " SAN=" + pd.san[1] + " CHA=" + pd.cha
            + " LUK=" + pd.luk + " SPR=" + pd.spr
            + "\n\n이 나이와 직업을 고려해 스탯을 소폭 보정해줘. "
            + "각 보정값은 -2~+2 범위 내로 제한. "
            + "reason에 보정 이유를 한 줄로 설명.";
    }

    private void applyAiAdjustment(PlayerData pd, String raw) {
        try {
            String cleaned = raw.replaceAll("```json","").replaceAll("```","").trim();
            int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
            if (s == -1 || e == -1) return;
            com.google.gson.JsonObject j = new com.google.gson.Gson().fromJson(
                cleaned.substring(s, e+1), com.google.gson.JsonObject.class);

            pd.str = Math.max(1, pd.str + getAdj(j, "str_adj"));
            pd.cha = Math.max(1, pd.cha + getAdj(j, "cha_adj"));
            pd.luk = Math.max(1, pd.luk + getAdj(j, "luk_adj"));
            pd.spr = Math.max(1, pd.spr + getAdj(j, "spr_adj"));
            int hpAdj  = getAdj(j, "hp_max_adj");
            int sanAdj = getAdj(j, "san_max_adj");
            pd.hp[1]  = Math.max(1, pd.hp[1]  + hpAdj);  pd.hp[0]  = pd.hp[1];
            pd.san[1] = Math.max(1, pd.san[1] + sanAdj); pd.san[0] = pd.san[1];
        } catch (Exception ignored) {}
    }

    private int getAdj(com.google.gson.JsonObject j, String key) {
        if (!j.has(key)) return 0;
        int v = j.get(key).getAsInt();
        return Math.max(-2, Math.min(2, v));
    }

    private String statKorName(String name) {
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
}
