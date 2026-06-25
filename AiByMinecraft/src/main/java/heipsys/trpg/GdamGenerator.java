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
    private final java.util.logging.Logger logger;

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
- 각 필드 값은 간결하게: 서술형 설명은 1~2문장 이내, 배열은 핵심 3~5개로 제한
- 불필요하게 장황하게 쓰지 마라 (JSON이 잘리지 않도록)

## 괴담 소재 다양성 (편향 절대 금지)
매 시나리오는 완전히 다른 소재를 사용해야 한다. 아래 중 하나를 반드시 사용:
- 저주받은 물건/장소 (인형, 악기, 건물 특정 구역)
- 기억/시간 조작 (시간 루프, 기억 삭제, 시간 역행)
- 사회적 공포 (컬트, 집단 광기, 지하 조직)
- 생물학적 공포 (기생, 감염, 돌연변이)
- 한국 전통 요괴/설화 (구미호, 처녀귀신, 도깨비, 저승사자)
- SCP/격리 실패 (실험체 탈출, 격리 시설)
- 백룸/공간 이상 (존재하지 않는 공간, 무한 복도, 비공간)
- 우주적 공포 (불가해한 존재, 현실 침식, 차원 붕괴)
- 디지털 공포 (AI 각성, 사이버 공간 침투, 바이러스형 존재)
- 일상 침식 (평범한 환경이 서서히 비틀림)

★ 거울·반사·도플갱어·모방 소재는 이미 많이 사용됨 — 이번에 절대 금지.
★ 프롬프트에 seed가 포함되어 있으면 그 seed 힌트를 소재 결정에 참고해라.

## 스케일 기준
방 1~2:  로컬   — 개인/소규모
방 3~4:  시티   — 도시 단위
방 5~6:  내셔널 — 국가 단위
방 7~8:  글로벌 — 전 세계 단위
방 9~10: 코즈믹 — 존재론적/우주 단위
방 11~:  복합   — 여러 괴담 연계

★ 스케일 예외: 초반(방 1~3)에도 대규모 괴담(코즈믹·글로벌 급)이 등장할 수 있다.
  단, 반드시 너프하여 출력한다:
  - 플레이어가 전면 대결 없이 생존·도주할 수 있는 경로 반드시 포함
  - 괴담의 영향 범위는 시나리오 배경 내로 제한 (진짜 세계 멸망 진행은 스토리 밖에서)
  - 해결 목표는 "격퇴"가 아닌 "탈출·생존·봉인"으로 설정
  - 괴담 자체의 강함은 유지하되, 접촉/피해 기회를 제한

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

## 배역 관계 설계 원칙
- 배역 간 관계는 다양하게 설계한다: 가족/형제, 연인, 직장동료, 친구, 완전 타인 등
- 관계가 있는 배역은 같은 zone에서 시작하거나 서로 연락처를 미리 알고 있을 수 있음
- 완전 타인 관계는 각자 다른 zone에서 시작
- relationships 배열에 반드시 명시할 것

## 아이템 설계 규칙 ★ 중요
### key_items 형식:
key_items는 게임 중 탐색으로 발견하거나 획득하는 주요 아이템만 포함.
각 항목은 반드시 아래 형식:
{"id":"item_열쇠", "name":"지하 창고 열쇠", "type":"physical:TRIPWIRE_HOOK", "description":"녹슨 철제 열쇠", "location":"관리실 서랍", "clue_value":"zone_D 진입 가능"}
type 값 규칙: "written_book"=책/일기/문서류, "paper"=쪽지/메모, "map"=지도/도면,
"physical:TRIPWIRE_HOOK"=열쇠류, "physical:LANTERN"=손전등/조명,
"physical:CLOCK"=폰/시계, "physical:IRON_SWORD"=칼/무기류,
"physical:CROSSBOW"=총기류, "physical:POTION"=약품/치료제,
"physical:LEATHER"=가방/옷, "physical:FLINT_AND_STEEL"=라이터/불,
"physical:COMPARATOR"=USB/전자기기, "physical:REDSTONE"=배터리/충전기

### start_item 규칙 ★ 절대 준수:
- start_item에는 반드시 한국어 아이템 이름을 쓴다
- "item_1", "item_5" 등 ID 코드는 절대 금지
- key_items에 있는 아이템이면 그 name 값 그대로, 없는 일반 소지품은 그냥 이름
- 예시 OK: ["스마트폰", "손전등", "관리 일지"]
- 예시 NG: ["item_1", "item_5", "smartphone"]

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
    "can_impersonate": false,
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
  "relationships": [
    {
      "type": "완전_타인",
      "roles": ["role_A", "role_B"],
      "mutual_contact": false,
      "same_start_zone": false,
      "description": "처음 만나는 사이"
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
  "common_items": [],
  "info_sharing": {
    "chat_allowed": true,
    "item_transfer": true,
    "remote_item_transfer": false
  }
}

## can_impersonate 작성 기준
변신·모방·도플갱어·빙의형 또는 고지능 괴담만 true.
true면 게임 중 플레이어를 제거하고 그 정체를 차지해 다른 플레이어를 속일 수 있다.
단순 물리적·환경적 괴담은 false.

## common_items 작성 기준
시대 배경에 따라 모든 플레이어가 기본 소지하는 아이템 ID 목록.
현대(2000년대~현재): ["smartphone"] 반드시 포함.
근미래·SF: ["smartphone", "comm_device"] 등 적절히.
중세·고대·판타지·전근대: [] (빈 배열).
★ 소지 여부만 표시. 실제 통신 가능 여부는 entity.rules로 제한
(예: "신호 없음", "전파 차단", "전원 없음" 등 서술로 처리).

## 만남 가능성 검증 (출력 전 필수)
"모든 배역이 참여한 시점에 서로 만나는 것이 물리적으로 가능한가?"
→ NO면 설계 수정 후 재출력
→ meeting_design.physically_possible 반드시 true로 설정
""";

    public GdamGenerator(Plugin plugin, AiManager aiManager) {
        this.aiManager = aiManager;
        this.logger    = plugin.getLogger();
        this.gdamDir   = new File(plugin.getDataFolder(), "gdam");
        if (!gdamDir.exists()) gdamDir.mkdirs();
        this.aesKey    = loadOrCreateKey(plugin);
    }

    // ──────────────────────────────────────────────────────────────
    //  생성
    // ──────────────────────────────────────────────────────────────

    public CompletableFuture<JsonObject> generate(int roomNumber) {
        return generate(roomNumber, 0);
    }

    private static final String[] THEME_POOL = {
        "저주받은 물건 (인형·악기·가구 등)", "기억·시간 조작 (루프·역행·삭제)",
        "사회적 공포 (컬트·집단 광기·지하 조직)", "생물학적 공포 (기생·감염·돌연변이)",
        "한국 전통 요괴 (구미호·처녀귀신·도깨비·저승사자)",
        "SCP/격리 실패 (실험체 탈출·격리 시설)", "백룸/비공간 (무한 복도·존재하지 않는 공간)",
        "우주적 공포 (불가해한 존재·차원 붕괴·현실 침식)",
        "디지털 공포 (AI 각성·사이버 침투·바이러스형 존재)",
        "일상 침식 (평범한 환경이 서서히 비틀림·역방향 물리 법칙)"
    };
    private static final java.util.concurrent.atomic.AtomicInteger themeIdx =
        new java.util.concurrent.atomic.AtomicInteger(0);

    private CompletableFuture<JsonObject> generate(int roomNumber, int attempt) {
        String scale = scaleFor(roomNumber);
        int idx = themeIdx.getAndUpdate(i -> (i + 1) % THEME_POOL.length);
        String themeHint = THEME_POOL[idx];
        String prompt = "방 번호: " + roomNumber + "\n스케일: " + scale
            + "\n이번 소재 방향 힌트(반드시 반영): " + themeHint
            + "\n\n위 스키마 형식의 .gdam JSON을 생성해줘. seed는 빈 문자열로 두면 됩니다.";

        return aiManager.callGmAiLarge(GDAM_SYSTEM_PROMPT, prompt)
            .thenCompose(raw -> {
                int rawLen = raw == null ? 0 : raw.length();
                try {
                    if (raw != null && raw.startsWith("§c")) {
                        throw new RuntimeException("AI 호출 오류: " + raw);
                    }
                    String cleaned = stripMarkdown(raw);
                    JsonElement el = gson.fromJson(cleaned, JsonElement.class);
                    if (el == null || !el.isJsonObject()) {
                        throw new RuntimeException("AI가 JSON 객체를 반환하지 않음 (미리보기: "
                            + cleaned.substring(0, Math.min(80, cleaned.length())) + ")");
                    }
                    JsonObject gdam = el.getAsJsonObject();

                    // seed 및 room 주입
                    String seed = generateSeed();
                    gdam.addProperty("seed", seed);
                    gdam.addProperty("room", roomNumber);
                    gdam.addProperty("scale", scale);

                    if (!validate(gdam)) {
                        throw new RuntimeException(".gdam 검증 실패: 필수 항목 누락");
                    }

                    save(seed, gdam);
                    logger.info("[gdam] 생성 성공 (응답 " + rawLen + "자, 시도 " + (attempt + 1) + ")");
                    return CompletableFuture.completedFuture(gdam);
                } catch (Exception e) {
                    String tail = (raw == null || rawLen == 0)
                        ? "(빈 응답)"
                        : raw.substring(Math.max(0, rawLen - 120));
                    logger.warning("[gdam] 파싱 실패(시도 " + (attempt + 1) + "): " + e.getMessage()
                        + " | 응답길이=" + rawLen + "자 | 끝부분=" + tail);
                    // 잘림/오류 시 1회 재생성 시도
                    if (attempt < 1) {
                        logger.info("[gdam] 재생성을 시도합니다...");
                        return generate(roomNumber, attempt + 1);
                    }
                    JsonObject err = new JsonObject();
                    err.addProperty("error", e.getMessage() + " (응답 " + rawLen + "자)");
                    return CompletableFuture.completedFuture(err);
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

    /** 저장된 .gdam 씨드 목록 반환 */
    public List<String> listSavedSeeds() {
        List<String> seeds = new ArrayList<>();
        File[] files = gdamDir.listFiles((d, name) -> name.endsWith(".gdam"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                seeds.add(name.substring(0, name.length() - 5));
            }
        }
        seeds.sort(String::compareTo);
        return seeds;
    }

    /**
     * 복호화한 내용을 들여쓰기된 JSON 파일로 내보낸다.
     * 원본 .gdam 파일은 변경하지 않는다.
     *
     * @return 생성된 .json 파일의 절대 경로, 실패 시 null
     */
    public String exportJson(String seed) {
        JsonObject gdam = load(seed);
        if (gdam == null) return null;

        Gson pretty = new GsonBuilder().setPrettyPrinting().create();
        String json  = pretty.toJson(gdam);

        File out = new File(gdamDir, seed + ".json");
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            pw.print(json);
            return out.getAbsolutePath();
        } catch (Exception e) {
            logger.warning("[GdamGenerator] JSON 내보내기 실패 (" + seed + "): " + e.getMessage());
            return null;
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
        // 마크다운 코드블록 제거
        String s = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
        // 첫 { 부터 마지막 } 까지 추출 (앞뒤 설명 텍스트 제거)
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        if (start != -1 && end != -1 && start < end) return s.substring(start, end + 1);
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
                if (bytes.length != 32) {
                    // 손상된 키 파일 → 재생성
                    keyFile.delete();
                } else {
                    return new SecretKeySpec(bytes, "AES");
                }
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
