package heipsys.trpg;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 재현(replay) 파일 관리.
 * 게임 시작 시 시드·스테이지·캐릭터 빌드(직업/특성/능력치)를 기록해,
 * 같은 서버에서 다른 플레이어가 동일한 시작 조건으로 그 스테이지를 재현할 수 있게 한다.
 * 시나리오 자체는 시드로 참조한다(시드만 기록 — 동일 서버 한정).
 *
 * 파일명: S{스테이지}_{시드}_{YYMMDD를 16진수로}T{HHMM}
 *   예) 스테이지3, 시드 #FSAW, 2026-06-26 21:30 → S3_#FSAW_3FA12T2130
 */
public class ReplayManager {

    private final File dir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final java.util.logging.Logger logger;

    public ReplayManager(Plugin plugin) {
        this.dir = new File(plugin.getDataFolder(), "replays");
        if (!dir.exists()) dir.mkdirs();
        this.logger = plugin.getLogger();
    }

    // ──────────────────────────────────────────────────────────────
    //  쓰기 / 읽기 / 목록
    // ──────────────────────────────────────────────────────────────

    /** 스테이지 시작 시 캐릭터 빌드 + 시드 + 스테이지를 파일로 기록. 파일명 반환(실패 시 null). */
    public String writeReplay(int stage, String seed, Collection<PlayerData> players) {
        try {
            LocalDateTime now = LocalDateTime.now();
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("stage", stage);
            root.addProperty("seed", seed);
            root.addProperty("created", now.toString());
            JsonArray arr = new JsonArray();
            for (PlayerData pd : players) arr.add(serializePlayer(pd));
            root.add("players", arr);

            String fname = buildFileName(stage, seed, now);
            Files.writeString(dir.toPath().resolve(fname), gson.toJson(root), StandardCharsets.UTF_8);
            logger.info("[replay] 기록 저장: " + fname + " (인원 " + arr.size() + ")");
            return fname;
        } catch (Exception e) {
            logger.warning("[replay] 기록 실패: " + e.getMessage());
            return null;
        }
    }

    public JsonObject readReplay(String fileName) {
        try {
            Path path = dir.toPath().resolve(sanitize(fileName));
            if (!Files.exists(path)) return null;
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            logger.warning("[replay] 읽기 실패: " + e.getMessage());
            return null;
        }
    }

    public List<String> listReplays() {
        String[] files = dir.list((d, n) -> n.startsWith("S"));
        if (files == null) return List.of();
        List<String> out = new ArrayList<>(Arrays.asList(files));
        Collections.sort(out);
        return out;
    }

    // ──────────────────────────────────────────────────────────────
    //  파일명 인코딩
    // ──────────────────────────────────────────────────────────────

    /** S{stage}_{seed}_{hex(YYMMDD)}T{HHMM} */
    private String buildFileName(int stage, String seed, LocalDateTime now) {
        int yymmdd = (now.getYear() % 100) * 10000 + now.getMonthValue() * 100 + now.getDayOfMonth();
        String dateHex = Integer.toHexString(yymmdd).toUpperCase();          // 260626 → 3FA12
        String time    = String.format("%02d%02d", now.getHour(), now.getMinute());
        return "S" + stage + "_" + seed + "_" + dateHex + "T" + time;
    }

    /** 경로 탈출 방지: 디렉터리 구분자·상위 참조 제거 */
    private String sanitize(String name) {
        return name.replace("/", "").replace("\\", "").replace("..", "");
    }

    // ──────────────────────────────────────────────────────────────
    //  직렬화 / 역직렬화
    // ──────────────────────────────────────────────────────────────

    private JsonObject serializePlayer(PlayerData pd) {
        JsonObject o = new JsonObject();
        o.addProperty("name",     pd.name);
        o.addProperty("charName", pd.charName);
        o.addProperty("gender",   pd.gender);
        o.addProperty("age",      pd.age);
        o.addProperty("job",      pd.job);
        o.addProperty("baseAge",  pd.baseAge);
        o.addProperty("roleAge",  pd.roleAge);
        o.addProperty("baseJob",  pd.baseJob);
        o.addProperty("roleId",   pd.roleId);
        o.addProperty("zone",     pd.zone);
        o.addProperty("contactId", pd.contactId);
        o.add("hp",  arr(pd.hp));   o.add("san", arr(pd.san));
        o.addProperty("str", pd.str); o.addProperty("cha", pd.cha);
        o.addProperty("luk", pd.luk); o.addProperty("spr", pd.spr);
        o.add("baseHp", arr(pd.baseHp)); o.add("baseSan", arr(pd.baseSan));
        o.addProperty("baseStr", pd.baseStr); o.addProperty("baseCha", pd.baseCha);
        o.addProperty("baseLuk", pd.baseLuk); o.addProperty("baseSpr", pd.baseSpr);
        o.add("traits",      gson.toJsonTree(pd.traits));
        o.add("heldItemIds", gson.toJsonTree(pd.heldItemIds));
        return o;
    }

    /** 기록된 캐릭터를 주어진 uuid/이름의 새 PlayerData로 복원 */
    public PlayerData deserializePlayer(JsonObject o, UUID uuid, String mcName) {
        PlayerData pd = new PlayerData(uuid, mcName);
        pd.charName  = str(o, "charName", "");
        pd.gender    = str(o, "gender", "");
        pd.age       = intv(o, "age", 25);
        pd.job       = str(o, "job", "일반인");
        pd.baseAge   = intv(o, "baseAge", pd.age);
        pd.roleAge   = intv(o, "roleAge", -1);
        pd.baseJob   = str(o, "baseJob", pd.job);
        pd.roleId    = str(o, "roleId", "");
        pd.zone      = str(o, "zone", "");
        pd.contactId = str(o, "contactId", "");
        pd.hp  = ints(o, "hp",  pd.hp);
        pd.san = ints(o, "san", pd.san);
        pd.str = intv(o, "str", pd.str); pd.cha = intv(o, "cha", pd.cha);
        pd.luk = intv(o, "luk", pd.luk); pd.spr = intv(o, "spr", pd.spr);
        pd.baseHp  = ints(o, "baseHp",  pd.hp);
        pd.baseSan = ints(o, "baseSan", pd.san);
        pd.baseStr = intv(o, "baseStr", pd.str); pd.baseCha = intv(o, "baseCha", pd.cha);
        pd.baseLuk = intv(o, "baseLuk", pd.luk); pd.baseSpr = intv(o, "baseSpr", pd.spr);
        if (o.has("traits")) {
            List<TraitData> traits = gson.fromJson(o.get("traits"),
                new TypeToken<List<TraitData>>(){}.getType());
            if (traits != null) pd.traits = traits;
        }
        if (o.has("heldItemIds")) {
            Set<String> held = gson.fromJson(o.get("heldItemIds"),
                new TypeToken<Set<String>>(){}.getType());
            if (held != null) pd.heldItemIds = held;
        }
        pd.statsConfirmed = true;
        pd.roleAssigned   = true;
        return pd;
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

    private JsonArray arr(int[] v) {
        JsonArray a = new JsonArray();
        for (int x : v) a.add(x);
        return a;
    }

    private int[] ints(JsonObject o, String key, int[] def) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return def;
        JsonArray a = o.getAsJsonArray(key);
        int[] out = new int[a.size()];
        for (int i = 0; i < a.size(); i++) out[i] = a.get(i).getAsInt();
        return out.length >= 2 ? out : def;
    }

    private int intv(JsonObject o, String key, int def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
    }

    private String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
