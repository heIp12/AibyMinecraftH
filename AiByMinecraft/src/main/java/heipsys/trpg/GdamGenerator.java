package heipsys.trpg;

import com.google.gson.*;
import org.bukkit.plugin.Plugin;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * .gdam 파일 생성/로드/저장.
 * 파일 내용은 AES-256-GCM으로 암호화되며 .gdam은 절대 수정하지 않음(읽기 전용).
 */
public class GdamGenerator {

    private final AiManager aiManager;
    private final File      gdamDir;
    private final SecretKey aesKey;
    private final Gson      gson = new Gson();

    // ──────────────────────────────────────────────────────────────
    //  .gdam 생성 프롬프트 (STEP 2-4 문서 기준)
    // ──────────────────────────────────────────────────────────────
    private static final String GDAM_SYSTEM_PROMPT = """
너는 괴담 TRPG의 '괴담 설계자'야.
요청받은 방 번호와 스케일에 맞는 괴담을 설계하고
.gdam 파일 형식의 JSON을 출력한다.

## 출력 규칙
- 반드시 순수 JSON만 출력 (마크다운 코드블록 없이)
- 한국어로 작성
- 모든 필수 항목 포함

## 괴담 소재 다양성 (편향 금지)
도시괴담 / SCP / 백룸 / 신화적 존재 / 한국 전통 괴담 /
일본 괴담 / 서양 괴담 / 우주적 공포 / 심리적 공포 등

## 스케일 기준
방 1~2:  로컬   — 개인/소규모
방 3~4:  시티   — 도시 단위
방 5~6:  내셔널 — 국가 단위
방 7~8:  글로벌 — 전 세계 단위
방 9~10: 코즈믹 — 존재론적/우주 단위
방 11~:  복합   — 여러 괴담 연계

## 필수 설계 항목
1. 괴담 정체 및 종류
2. 배경 및 무대
3. 고유 규칙 (금기, 법칙, 행동 원리)
4. 약점 및 파훼 조건
5. 정석 해결법 (퍼펙트 클리어 조건)
6. 규칙 역이용 가능 경로 (최소 1개)
7. 생존법 (도주 조건)
8. 타임라인 전체 구조
9. 배치된 단서 종류와 위치
10. 핵심 배역 3~4개

## 배역 설계 원칙
- 핵심 배역 3~4개, 각 배역 다른 시작 위치
- 모든 배역 참여 시 구조적 만남 가능 보장
- 각 배역은 고유한 초기 정보 보유
- 늦게 등장하는 배역은 knowledge_advantage: true

## 출력 JSON 스키마
{
  "seed": "",
  "room": 0,
  "scale": "로컬",
  "entity": {
    "name": "",
    "type": "지능형",
    "rules": [],
    "weakness": "",
    "solution": "",
    "exploit_path": "",
    "escape": "",
    "independent_ai": true,
    "ai_context": {
      "personality": "",
      "initial_pattern": "",
      "corruption_behavior": {"0":"","1":"","2":"","3":"","4":""}
    }
  },
  "timeline": {
    "daily_turns": 5,
    "1": {"condition":"","effect":""},
    "2": {"condition":"","effect":""},
    "3": {"condition":"","effect":""},
    "4": {"condition":"","effect":""}
  },
  "roles": [
    {
      "role_id": "role_A",
      "name": "",
      "is_core": true,
      "age_range": [20,40],
      "job_pool": [],
      "spawn_timeline": "시작 즉시",
      "spawn_location": "",
      "zone": "zone_A",
      "initial_info": [],
      "hidden_info": [],
      "start_item": [],
      "knowledge_advantage": false
    }
  ],
  "zones": [{"zone_id":"zone_A","name":"","accessible_by":[],"exclusive":false}],
  "npcs": [],
  "key_items": [],
  "clues": [],
  "daily_prologue": {
    "turns": 5,
    "role_placements": [],
    "foreshadowing": []
  },
  "meeting_design": {
    "physically_possible": true,
    "meeting_window": "타임라인 1~2단계",
    "natural_barriers": []
  },
  "join_system": {
    "mid_join_allowed": true,
    "timeline_limit": 3
  },
  "info_sharing": {
    "chat_allowed": true,
    "item_transfer": true,
    "remote_item_transfer": false
  }
}

## 만남 가능성 검증 (출력 전 필수)
"모든 배역이 참여한 시점에 서로 만나는 것이 물리적으로 가능한가?"
→ NO면 설계 수정 후 재출력
→ meeting_design.physically_possible 반드시 true로 설정
""";

    public GdamGenerator(Plugin plugin, AiManager aiManager) {
        this.aiManager = aiManager;
        this.gdamDir   = new File(plugin.getDataFolder(), "gdam");
        if (!gdamDir.exists()) gdamDir.mkdirs();
        this.aesKey    = loadOrCreateKey(plugin);
    }

    // ──────────────────────────────────────────────────────────────
    //  생성
    // ──────────────────────────────────────────────────────────────

    public CompletableFuture<JsonObject> generate(int roomNumber) {
        String scale = scaleFor(roomNumber);
        String prompt = "방 번호: " + roomNumber + "\n스케일: " + scale
            + "\n\n위 스키마 형식의 .gdam JSON을 생성해줘. seed는 빈 문자열로 두면 됩니다.";

        return aiManager.callGmAiOnce(GDAM_SYSTEM_PROMPT, prompt)
            .thenApply(raw -> {
                try {
                    String cleaned = stripMarkdown(raw);
                    JsonObject gdam = gson.fromJson(cleaned, JsonObject.class);

                    // seed 및 room 주입
                    String seed = generateSeed();
                    gdam.addProperty("seed", seed);
                    gdam.addProperty("room", roomNumber);
                    gdam.addProperty("scale", scale);

                    if (!validate(gdam)) {
                        throw new RuntimeException(".gdam 검증 실패: 필수 항목 누락");
                    }

                    save(seed, gdam);
                    return gdam;
                } catch (Exception e) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", e.getMessage());
                    return err;
                }
            });
    }

    // ──────────────────────────────────────────────────────────────
    //  저장 / 로드 (AES-256-GCM)
    // ──────────────────────────────────────────────────────────────

    public void save(String seed, JsonObject gdam) throws Exception {
        File file = new File(gdamDir, seed + ".gdam");
        String encrypted = encrypt(gdam.toString());
        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println(seed);
            pw.print(encrypted);
        }
    }

    /** 항상 원본 로드 — 절대 수정하지 않음 */
    public JsonObject load(String seed) {
        try {
            File file = new File(gdamDir, seed + ".gdam");
            if (!file.exists()) return null;
            List<String> lines = Files.readAllLines(file.toPath());
            if (lines.size() < 2) return null;
            String decrypted = decrypt(lines.get(1));
            return gson.fromJson(decrypted, JsonObject.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

    private boolean validate(JsonObject g) {
        if (!g.has("entity"))   return false;
        if (!g.has("timeline")) return false;
        if (!g.has("roles"))    return false;

        JsonObject entity = g.getAsJsonObject("entity");
        if (!entity.has("name") || !entity.has("weakness") || !entity.has("solution")) return false;

        JsonObject tl = g.getAsJsonObject("timeline");
        if (!tl.has("1") || !tl.has("2") || !tl.has("3") || !tl.has("4")) return false;

        // 만남 가능성 검증
        if (g.has("meeting_design")) {
            JsonObject md = g.getAsJsonObject("meeting_design");
            if (md.has("physically_possible") && !md.get("physically_possible").getAsBoolean())
                return false;
        }
        return true;
    }

    private String generateSeed() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rng = new Random();
        StringBuilder sb = new StringBuilder("#");
        for (int i = 0; i < 4; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        sb.append("-");
        for (int i = 0; i < 4; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }

    private String scaleFor(int room) {
        if (room <= 2)  return "로컬";
        if (room <= 4)  return "시티";
        if (room <= 6)  return "내셔널";
        if (room <= 8)  return "글로벌";
        if (room <= 10) return "코즈믹";
        return "복합";
    }

    private String stripMarkdown(String raw) {
        String s = raw.replaceAll("```json", "").replaceAll("```", "").trim();
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        if (start != -1 && end != -1 && start <= end) return s.substring(start, end + 1);
        return s;
    }

    // ──────────────────────────────────────────────────────────────
    //  AES-256-GCM 암/복호화
    // ──────────────────────────────────────────────────────────────

    private String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] enc = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(enc, 0, combined, iv.length, enc.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String base64) throws Exception {
        byte[] combined = Base64.getDecoder().decode(base64.trim());
        byte[] iv  = Arrays.copyOfRange(combined, 0, 12);
        byte[] enc = Arrays.copyOfRange(combined, 12, combined.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
    }

    private SecretKey loadOrCreateKey(Plugin plugin) {
        File keyFile = new File(plugin.getDataFolder(), "trpg.key");
        try {
            if (keyFile.exists()) {
                byte[] bytes = Files.readAllBytes(keyFile.toPath());
                return new SecretKeySpec(bytes, "AES");
            }
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256, new SecureRandom());
            SecretKey key = kg.generateKey();
            plugin.getDataFolder().mkdirs();
            Files.write(keyFile.toPath(), key.getEncoded());
            return key;
        } catch (Exception e) {
            throw new RuntimeException("AES 키 초기화 실패", e);
        }
    }
}
