package heipsys.trpg;

import com.google.gson.*;
import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 캐릭터 스탯 생성.
 * 배역 데이터를 선제 주입하면 나이/직업이 배역에 맞게 결정되고,
 * 초기 특성도 AI가 직업·나이 맥락으로 생성한다.
 *
 * 생성 순서:
 *  1. 나이/직업 — 배역 age_range·job_pool 우선, 없으면 전체 풀
 *  2. 기본 스탯 배분 (총합 23, 각 최소 1)
 *  3. 최저 스탯 +1, 무작위 [+4,+2,+1,-2] 적용
 *  4. Haiku AI로 나이/직업/배역 보정
 *  5. AI 초기 특성 생성 (1~2개, 직업·나이 맥락)
 */
public class CharacterGenerator {

    private static final Random RNG = new Random();
    private enum JobTier { COMMON, STRONG, RARE }

    /** 평범한 직업 (~70%) */
    private static final String[] COMMON_JOB_POOL = {
        "초등학생","중학생","고등학생","대학생","유치원생",
        "의사","간호사","약사","수의사","치과의사",
        "교사","교수","학원강사","사서",
        "경찰관","소방관","군인","경호원",
        "요리사","제빵사","바리스타","식당주인",
        "회계사","변호사","검사","판사","세무사",
        "기자","PD","작가","웹툰작가","유튜버","사진작가",
        "프로그래머","디자이너","건축가","엔지니어",
        "농부","어부","축산업자","임업종사자",
        "택시기사","버스기사","트럭운전사","배달원",
        "청소부","경비원","편의점알바","마트직원",
        "헬스트레이너","무용가","스포츠선수","코치",
        "성직자","스님","사회복지사","심리상담사",
        "골동품상인","마술사","서커스단원",
        "노숙자","은퇴자","백수","전업주부",
        "탐정","해커","사기꾼","도둑",
        "인플루언서","모델","배우","가수",
        "과학자","연구원","역사학자","고고학자",
        "조련사","잠수부","등반가",
        "정치인","공무원","외교관","통역사"
    };
    
    /** 해결사/전문 능력자 (~20%) — A/B등급 초기 특성 부여 */
    private static final String[] STRONG_JOB_POOL = {
        "엑소시스트","퇴마사","봉마사","신부(퇴마 전문)",
        "SCP요원","비밀결사 요원","정보국 요원","첩보원",
        "특수부대원","저격수","용병","군특수공작원",
        "형사(베테랑)","괴물 사냥꾼","무도가","검객",
        "심령술사","봉인사","연금술사","무속인",
        "성기사","수도사(전투형)","마법사(학파 출신)","저항군 사령관"
    };

    /** 초자연적/변수가 큰 직업 (~10%) — S/A등급이지만 큰 대가 수반 */
    private static final String[] RARE_JOB_POOL = {
        "뱀파이어","랩틸리언","흑마법사","저주받은 자",
        "시간여행자","악마와의 계약자","마계 망명자",
        "반신(半神)","인조인간","불사자","저주받은 마법사",
        "예언자","괴물의 후손","유령빙의자","혼종"
    };

    private static final String[] STAT_NAMES = {"hp","str","san","cha","luk","spr"};
    private static final Gson GSON = new Gson();

    private final AiManager aiManager;
    private final File cacheFile;
    private final Object cacheLock = new Object();

    // AI로 갱신되는 동적 직업 풀 (초기값은 정적 풀, 서버 시작 시 교체됨)
    private final List<String> dynCommon = new ArrayList<>(Arrays.asList(COMMON_JOB_POOL));
    private final List<String> dynStrong = new ArrayList<>(Arrays.asList(STRONG_JOB_POOL));
    private final List<String> dynRare   = new ArrayList<>(Arrays.asList(RARE_JOB_POOL));

    // 플레이어별 이미 나온 직업 추적 (재굴림 포함, 파일에 영속됨)
    private final Map<UUID, Set<String>> usedJobs = new ConcurrentHashMap<>();

    // 직업명 → 한 줄 설명 (AI 풀 갱신 시 함께 생성, 캐시에 영속). 마우스 오버레이용.
    private final Map<String, String> jobDesc = new ConcurrentHashMap<>();

    /** 친숙 모드(프로젝트 문·게임) 시작 특성 테마 지침. 일반 시나리오면 빈 문자열. TRPGGameManager가 스테이지 시작 시 주입. */
    private volatile String scenarioFlavor = "";

    /** 시작 특성 테마 지침 설정(프로젝트 문→전투표상/E.G.O. 기프트, 게임→게임 메커니즘 능력). */
    public void setScenarioFlavor(String s) { this.scenarioFlavor = (s == null) ? "" : s; }

    /** 직업의 한 줄 설명(없으면 빈 문자열). 마우스 오버레이용 — 낯선 직업 안내. */
    public String describeJob(String job) {
        if (job == null) return "";
        return jobDesc.getOrDefault(job.trim(), "");
    }

    public CharacterGenerator(AiManager aiManager, File dataFolder) {
        this.aiManager = aiManager;
        this.cacheFile = new File(dataFolder, "job_cache.json");
    }

    // ──────────────────────────────────────────────────────────────
    //  서버 시작 시 AI 직업 풀 갱신 — 캐시 우선, 50% 이상 소진 시만 AI 재호출
    // ──────────────────────────────────────────────────────────────

    public CompletableFuture<Void> refreshJobPools() {
        loadCache(); // 캐시 파일에서 풀 + 사용 기록 복구

        Set<String> globalUsed = new HashSet<>();
        usedJobs.values().forEach(globalUsed::addAll);

        String sys = "각 직업을 {\"n\":\"직업명\",\"d\":\"한 줄 설명(공백 포함 20자 내외, 무슨 일을 하는지)\"} 객체로, JSON 배열로만 응답. 마크다운 없음.";
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        if (needsRefresh(dynCommon, globalUsed, COMMON_JOB_POOL.length)) {
            futures.add(callAndFillPool(sys,
                "TRPG 배경의 평범한 일상 직업 60개를 위 형식 JSON 배열로. " +
                "학생·의료·교육·사무·서비스·예술·IT·기술직·농축수산업 등 최대한 다양하게. " +
                "직업명은 한국어 2~10자, 중복 없이. 각 직업에 무슨 일을 하는지 한 줄 설명을 단다.", dynCommon, 4096));
        }
        if (needsRefresh(dynStrong, globalUsed, STRONG_JOB_POOL.length)) {
            futures.add(callAndFillPool(sys,
                "TRPG 배경의 해결사·전투·수사·초자연 전문가 직업 40개를 위 형식 JSON 배열로. " +
                "엑소시스트·SCP요원·용병·형사·특수부대원·봉마사 등 전문 능력자 위주. " +
                "직업명은 한국어 2~15자, 중복 없이. 각 직업에 무슨 일을 하는지 한 줄 설명을 단다.", dynStrong, 4096));
        }
        if (needsRefresh(dynRare, globalUsed, RARE_JOB_POOL.length)) {
            futures.add(callAndFillPool(sys,
                "TRPG 배경의 초자연적·변수가 큰 특이 직업 25개를 위 형식 JSON 배열로. " +
                "뱀파이어·흑마법사·시간여행자·랩틸리언 등 강력하지만 대가가 큰 존재 위주. " +
                "직업명은 한국어 2~15자, 중복 없이. 각 직업에 무슨 일을 하는지 한 줄 설명을 단다.", dynRare, 4096));
        }

        if (futures.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                .thenRun(this::saveCache);
    }

    /**
     * 강제 초기화 후 무조건 AI 재생성 — 캐시 파일 삭제+서버 재시작 없이 런타임에서 직업 풀을 새로 뽑는다.
     * 동적 풀을 정적값으로 되돌리고(→ needsRefresh가 참이 됨) 사용 기록·설명·캐시 파일을 비운 뒤 refreshJobPools를 돈다.
     */
    public CompletableFuture<Void> forceRefreshJobPools() {
        synchronized (dynCommon) { dynCommon.clear(); dynCommon.addAll(Arrays.asList(COMMON_JOB_POOL)); }
        synchronized (dynStrong) { dynStrong.clear(); dynStrong.addAll(Arrays.asList(STRONG_JOB_POOL)); }
        synchronized (dynRare)   { dynRare.clear();   dynRare.addAll(Arrays.asList(RARE_JOB_POOL)); }
        usedJobs.clear();
        jobDesc.clear();
        try { if (cacheFile.exists()) cacheFile.delete(); } catch (Exception ignored) {}
        org.bukkit.Bukkit.getLogger().info("[직업풀] 강제 초기화 — AI로 직업 풀 재생성 시작");
        return refreshJobPools();
    }

    /** 풀에서 절반 이상 직업이 이미 사용됐으면 true (AI 재호출 필요). staticSize=정적 폴백 크기 */
    private boolean needsRefresh(List<String> pool, Set<String> globalUsed, int staticSize) {
        if (pool.size() <= staticSize) return true; // 아직 AI 갱신 없음 → 무조건 갱신
        synchronized (pool) {
            long usedCount = pool.stream().filter(globalUsed::contains).count();
            return usedCount >= pool.size() * 0.5;
        }
    }

    private CompletableFuture<Void> callAndFillPool(String system, String prompt, List<String> target, int maxTokens) {
        return aiManager.callAssistant(system, prompt, maxTokens).thenAccept(raw -> {
            try {
                String cleaned = raw == null ? "" : raw.replaceAll("```json", "").replaceAll("```", "").trim();
                int s = cleaned.indexOf('['), e = cleaned.lastIndexOf(']');
                String jsonArr;
                if (s == -1) {
                    org.bukkit.Bukkit.getLogger().warning("[직업풀] AI 응답에 JSON 배열이 없어 정적 풀 유지. 앞부분: " + snippet(raw));
                    return;
                }
                if (e > s) {
                    jsonArr = cleaned.substring(s, e + 1);
                } else {
                    // 응답이 토큰 한도로 잘려 ']'가 없을 때: 마지막 완성된 '}'까지 취해 배열을 닫아 구제한다.
                    int lastObj = cleaned.lastIndexOf('}');
                    if (lastObj <= s) {
                        org.bukkit.Bukkit.getLogger().warning("[직업풀] AI 응답에 JSON 배열이 없어 정적 풀 유지. 앞부분: " + snippet(raw));
                        return;
                    }
                    jsonArr = cleaned.substring(s, lastObj + 1) + "]";
                    org.bukkit.Bukkit.getLogger().info("[직업풀] 응답이 잘려 마지막 완성 객체까지 구제 파싱");
                }
                JsonArray arr = GSON.fromJson(jsonArr, JsonArray.class);
                List<String> jobs = new ArrayList<>();
                for (JsonElement el : arr) {
                    String j, d = "";
                    if (el.isJsonObject()) { // {"n":직업,"d":설명} 형식
                        JsonObject o = el.getAsJsonObject();
                        j = o.has("n") ? o.get("n").getAsString().trim() : (o.has("name") ? o.get("name").getAsString().trim() : "");
                        if (o.has("d")) d = o.get("d").getAsString().trim();
                        else if (o.has("desc")) d = o.get("desc").getAsString().trim();
                    } else { // 문자열만 온 경우 폴백(설명 없음)
                        j = el.getAsString().trim();
                    }
                    if (j.isBlank()) continue;
                    jobs.add(j);
                    if (!d.isBlank()) jobDesc.put(j, d);
                }
                if (jobs.size() >= 20) {
                    synchronized (target) { target.clear(); target.addAll(jobs); }
                    org.bukkit.Bukkit.getLogger().info("[직업풀] AI 직업 " + jobs.size() + "개 갱신 완료");
                } else {
                    org.bukkit.Bukkit.getLogger().warning("[직업풀] AI 직업 " + jobs.size() + "개만 파싱(20개 미만) → 정적 풀 유지");
                }
            } catch (Exception ex) {
                org.bukkit.Bukkit.getLogger().warning("[직업풀] AI 직업 파싱 실패 → 정적 풀 유지: " + ex.getMessage());
            }
        }).exceptionally(ex -> {
            org.bukkit.Bukkit.getLogger().warning("[직업풀] AI 호출 실패 → 정적 풀 유지: " + ex.getMessage());
            return null;
        });
    }

    private static String snippet(String s) {
        if (s == null) return "null";
        String t = s.replace("\n", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    // ──────────────────────────────────────────────────────────────
    //  캐시 저장 / 복구
    // ──────────────────────────────────────────────────────────────

    private void loadCache() {
        if (!cacheFile.exists()) return;
        try {
            String content = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(content, JsonObject.class);
            replaceIfLarger(root, "common", dynCommon, COMMON_JOB_POOL.length);
            replaceIfLarger(root, "strong", dynStrong, STRONG_JOB_POOL.length);
            replaceIfLarger(root, "rare",   dynRare,   RARE_JOB_POOL.length);
            if (root.has("usedByPlayer")) {
                root.getAsJsonObject("usedByPlayer").entrySet().forEach(entry -> {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        Set<String> set = new HashSet<>();
                        entry.getValue().getAsJsonArray().forEach(el -> set.add(el.getAsString()));
                        usedJobs.put(uuid, set);
                    } catch (Exception ignored) {}
                });
            }
            if (root.has("desc") && root.get("desc").isJsonObject()) { // 직업 설명 복구
                root.getAsJsonObject("desc").entrySet().forEach(en -> {
                    try { jobDesc.put(en.getKey(), en.getValue().getAsString()); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    private void replaceIfLarger(JsonObject root, String key, List<String> target, int staticSize) {
        if (!root.has(key)) return;
        List<String> loaded = new ArrayList<>();
        root.getAsJsonArray(key).forEach(el -> loaded.add(el.getAsString()));
        if (loaded.size() > staticSize) {
            synchronized (target) { target.clear(); target.addAll(loaded); }
        }
    }

    private void saveCache() {
        CompletableFuture.runAsync(() -> {
            synchronized (cacheLock) {
                try {
                    JsonObject root = new JsonObject();
                    synchronized (dynCommon) { root.add("common", listToJsonArray(dynCommon)); }
                    synchronized (dynStrong) { root.add("strong", listToJsonArray(dynStrong)); }
                    synchronized (dynRare)   { root.add("rare",   listToJsonArray(dynRare));   }
                    JsonObject byPlayer = new JsonObject();
                    usedJobs.forEach((uuid, set) -> {
                        JsonArray arr = new JsonArray();
                        set.forEach(arr::add);
                        byPlayer.add(uuid.toString(), arr);
                    });
                    root.add("usedByPlayer", byPlayer);
                    JsonObject descObj = new JsonObject(); // 직업 설명 영속화
                    jobDesc.forEach(descObj::addProperty);
                    root.add("desc", descObj);
                    if (!cacheFile.getParentFile().exists()) cacheFile.getParentFile().mkdirs();
                    Files.write(cacheFile.toPath(),
                        GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
        });
    }

    private JsonArray listToJsonArray(List<String> list) {
        JsonArray arr = new JsonArray();
        list.forEach(arr::add);
        return arr;
    }

    // ──────────────────────────────────────────────────────────────
    //  플레이어 직업 중복 방지
    // ──────────────────────────────────────────────────────────────

    /** 캐릭터 확정 후 해당 플레이어의 직업 중복 방지 기록을 초기화 */
    public void clearPlayerUsedJobs(UUID uuid) {
        usedJobs.remove(uuid);
        saveCache();
    }

    /** 해당 플레이어가 아직 뽑지 않은 직업을 선택. 전부 소진 시 전체 풀에서 재선택 */
    private String pickUnusedJob(UUID uuid, List<String> pool) {
        Set<String> used = usedJobs.computeIfAbsent(uuid, k -> new HashSet<>());
        List<String> available;
        synchronized (pool) {
            available = pool.stream().filter(j -> !used.contains(j)).collect(Collectors.toList());
            if (available.isEmpty()) available = new ArrayList<>(pool);
        }
        String job = available.get(RNG.nextInt(available.size()));
        used.add(job);
        saveCache();
        return job;
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
        JobTier tier = rollStats(pd, roleData);

        String roleContext = buildRoleContext(roleData);

        return aiManager.callAssistant(
            "너는 TRPG 캐릭터 스탯 보정기야. 아래 JSON 형식으로만 응답해:\n"
            + "{\"str_adj\":0,\"cha_adj\":0,\"luk_adj\":0,\"spr_adj\":0,"
            + "\"hp_max_adj\":0,\"san_max_adj\":0,\"reason\":\"\"}",
            buildAdjustPrompt(pd, roleContext)
        ).thenCompose(raw -> {
            applyAiAdjustment(pd, raw);
            // AI 보정(hp/san_max_adj 최대 -3)이 rollStats의 생존 최저선(2)을 다시 깨는 경로 차단
            // (SAN 1/1로 생성돼 정신피해 1에 즉시 홀림·조종되는 캐릭터 방지).
            ensureSurvivalFloor(pd);
            return generateInitialTraits(pd, roleContext, tier);
        }).thenApply(traits -> {
            pd.traits.addAll(traits);
            pd.snapshotBase();
            return pd;
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  스탯 굴림 (배역 age_range / job_pool 우선 적용)
    // ──────────────────────────────────────────────────────────────

    public JobTier rollStats(PlayerData pd, JsonObject roleData) {
        // ★나이·성별 앵커★ — 초기 스테이터스 생성 시점에 플레이어 ★고유★ 나이·성별을 굴린다.
        //   (배역→플레이어 역방향 폐기: 배역 age_range로 나이를 뒤집지 않는다. 배역이 이 앵커에 맞춰 생성·배정된다.)
        //   가중 분포: 12~30세 약 60%, 30~50세 약 25%, 8~12세 약 10%, 51~80세 약 5%.
        int roll = RNG.nextInt(100);
        if      (roll < 60) pd.age = 12 + RNG.nextInt(19); // 12~30
        else if (roll < 85) pd.age = 30 + RNG.nextInt(21); // 30~50
        else if (roll < 95) pd.age =  8 + RNG.nextInt(5);  // 8~12
        else                pd.age = 51 + RNG.nextInt(30); // 51~80
        pd.age = Math.max(8, Math.min(80, pd.age)); // ★앵커 범위 최소 8세·최대 80세★
        // 성별 앵커 — 미설정이면 50/50. 배역 배정 시 배역 성별로 덮어쓰지 않도록 배정 측 가드가 이 값을 유지한다.
        if (pd.gender == null || pd.gender.isEmpty())
            pd.gender = RNG.nextBoolean() ? "남성" : "여성";

        // 직업 — roleData가 있으면 배역 풀 우선, 없으면 가중치 계층 선택
        JobTier tier = JobTier.COMMON;
        if (roleData != null && roleData.has("job_pool") && roleData.get("job_pool").isJsonArray()) {
            JsonArray pool = roleData.getAsJsonArray("job_pool");
            if (pool.size() >= 5) {
                pd.job = pool.get(RNG.nextInt(pool.size())).getAsString();
            } else if (pool.size() > 0) {
                // 배역 풀 50%, 평범한 풀 50% 혼합
                if (RNG.nextBoolean()) {
                    pd.job = pool.get(RNG.nextInt(pool.size())).getAsString();
                } else {
                    pd.job = COMMON_JOB_POOL[RNG.nextInt(COMMON_JOB_POOL.length)];
                }
            } else {
                pd.job = COMMON_JOB_POOL[RNG.nextInt(COMMON_JOB_POOL.length)];
            }
        } else {
            // 가중치 랜덤: 70% 평범 / 20% 강한 직업 / 10% 희귀 직업
            int jobRoll = RNG.nextInt(100);
            List<String> pool;
            if (jobRoll < 70) {
                tier = JobTier.COMMON;
                pool = dynCommon;
            } else if (jobRoll < 90) {
                tier = JobTier.STRONG;
                pool = dynStrong;
            } else {
                tier = JobTier.RARE;
                pool = dynRare;
            }
            pd.job = pickUnusedJob(pd.uuid, pool);
        }

        // 총합 23을 6개 스탯에 배분(기본 무작위 총합 -2 — 시작을 살짝 더 약하게)
        int[] stats = distributePoints(23, 6, 1, 10);
        pd.hp  = new int[]{stats[0], stats[0]};
        pd.str = stats[1];
        pd.san = new int[]{stats[2], stats[2]};
        pd.cha = stats[3];
        pd.luk = stats[4];
        pd.spr = stats[5];

        boostLowest(pd);
        applyBonuses(pd);
        ensureSurvivalFloor(pd); // HP·SAN 최소 생존선 보장 (1/1 즉사 캐릭터 방지)
        return tier;
    }

    /**
     * 생존 직결 스탯(HP·SAN) 최소선 보장.
     * 주사위 분배가 HP·SAN을 1까지 떨어뜨릴 수 있어, 호러 생존 게임에서
     * 한 대만 맞아도 즉시 탈락하는 캐릭터가 생기는 것을 막는다.
     * 총합은 보존한다 — 부족분은 HP·SAN을 제외한 가장 높은 스탯에서 끌어온다.
     */
    private void ensureSurvivalFloor(PlayerData pd) {
        final int FLOOR = 2; // 시작 체력·정신력 최소 2(1/1 즉사 방지, 단 취약하게 — 큰 피해엔 즉사 가능)
        raiseToFloor(pd, 0, FLOOR); // HP
        raiseToFloor(pd, 2, FLOOR); // SAN
    }

    private void raiseToFloor(PlayerData pd, int idx, int floor) {
        while (statAt(pd, idx) < floor) {
            int donor = highestDonor(pd, idx);
            if (donor < 0) break; // 끌어올 여유 스탯 없음
            applyStatDelta(pd, donor, -1);
            applyStatDelta(pd, idx, 1);
        }
    }

    /** HP(0)·SAN(2)이 아닌 스탯 중 4 이상으로 가장 높은 것의 인덱스 (도너도 3 미만으로 떨어뜨리지 않음) */
    private int highestDonor(PlayerData pd, int exceptIdx) {
        int[] vals = {pd.hp[1], pd.str, pd.san[1], pd.cha, pd.luk, pd.spr};
        int best = -1, bestVal = 3;
        for (int i = 0; i < vals.length; i++) {
            if (i == 0 || i == 2 || i == exceptIdx) continue; // HP·SAN은 도너 제외
            if (vals[i] > bestVal) { bestVal = vals[i]; best = i; }
        }
        return best;
    }

    private int statAt(PlayerData pd, int idx) {
        return switch (idx) {
            case 0 -> pd.hp[1];
            case 1 -> pd.str;
            case 2 -> pd.san[1];
            case 3 -> pd.cha;
            case 4 -> pd.luk;
            case 5 -> pd.spr;
            default -> 0;
        };
    }

    // ──────────────────────────────────────────────────────────────
    //  AI 초기 특성 생성
    // ──────────────────────────────────────────────────────────────

    private CompletableFuture<List<TraitData>> generateInitialTraits(PlayerData pd, String roleContext, JobTier tier) {
        String tierRules = switch (tier) {
            case STRONG -> """
- grade: A 또는 B 사용 (해결사·전문 능력자이므로 강한 특성)
- 사명감, 오랜 훈련, 특수 기술에서 비롯된 능력으로 작성
- 능동 특성(active:true) 1개 이상 포함 권장. 쿨다운(cooldownTurns) 1~2 설정
- 직업·배경에 맞는 제약(조건부 발동, 쿨다운)이 있을 수 있음
- 개수: 1~2개
""";
            case RARE -> """
- grade: S 또는 A 사용 (초자연적 직종이므로 극히 강력한 특성)
- 반드시 큰 대가·위험부담 수반: 쿨다운 3턴 이상 또는 체력·정신력 소모 또는 특정 조건 하에서만 발동
- 능동 특성(active:true) 필수 포함. cooldownTurns 2~4 설정
- 강점이 극단적인 만큼 뚜렷한 약점이나 부작용 특성도 1개 추가 (grade D 또는 F)
- 개수: ★정확히 2~3개★ (강점 1~2 + 약점/부작용 1). 그 이상 만들지 마라 — 한 직업 설정을 조각내 필러 특성을 늘리지 말 것.
""";
            default -> """
- grade: C·D·F 중 ★하나만★ 사용 (초기 캐릭터이므로 강한 특성 없음. 'D/F'처럼 여러 글자로 쓰지 말고 한 등급만)
- 개수: 1~2개
""";
        };

        String system = "너는 TRPG 캐릭터 초기 특성 생성기야.\n"
            + "아래 JSON 배열 형식으로만 응답 (다른 텍스트 금지):\n"
            + "[{\"active\":false,\"effect\":\"\",\"concept\":\"\",\"name\":\"\",\"description\":\"\","
            + "\"grade\":\"C\",\"cooldownTurns\":0,"
            + "\"str_add\":0,\"cha_add\":0,\"luk_add\":0,\"spr_add\":0,\"hp_max_add\":0,\"san_max_add\":0},...]\n\n"
            + "공통 규칙:\n"
            + "- 직업·나이에서 자연스럽게 연결되는 능력/약점\n"
            + "- ★스탯 보정(등급=실제 파워)★: 특성 grade에 맞춰 스탯 보정 총합을 준다 — 양의 총합 예산 F=-2·E=-1·D=0·C=1·B=3·A=5·S=10. 다른 스탯에 -를 주면 그만큼 +를 더 줄 수 있다(예: B급 근력+4·행운-1=순합3). 체력최대·정신력최대(hp_max_add·san_max_add)도 같은 1점=1로 계산(표시만 %). ★초기 특성은 기계 능동/패시브 효과 없이 스탯으로만 파워를 낸다★ — 그 등급이 실제 스탯으로 뒷받침되게 하라(희귀 직업 A/S면 스탯 총합을 그만큼 크게, 약점 특성 D/F면 순합을 음수로).\n"
            + "- hidden_info에 해결 수단(아이템·경로·조작법)이 포함되면, 그 '존재·방법'만 알게 하고 '왜 필요한지(괴담 약점·해법과의 연결)'는 캐릭터가 인지하지 못한 상태로 설계하라. 정답을 처음부터 쥐여주지 마라(용도는 플레이 중 발견).\n"
            + "- description과 effect에 스탯 숫자·스탯 약어(STR/HP/SAN/CHA/LUK/SPR) 절대 사용 금지\n"
            + "- effect: 한 문장으로 간결하게 (수동이면 판정에서 발휘되는 식, 능동이면 사용 효과)\n"
            + "- ★생성 순서★: effect(작용 한 문장) → concept(핵심 한 줄, 내부용) → name → description 순. 넷은 ★같은 능력 하나★를 가리킨다.\n"
            + "- name: concept를 글자 수에 맞춰 줄이지 마라. '이 능력을 가진 사람을 옆에서 보면 뭐라 부를까'를 생각해 ★실제로 쓰는 자연스러운 한국어★를 골라라. 보통 2~5자 한 단어, 필요하면 띄어쓰기 있는 두 단어까지 — 자연스러움이 길이보다 우선.\n"
            + "- ★억지 조어 금지★: 사전에 없는 압축어·신조어를 만들지 마라. 나쁜 예: \"틈겁\", \"눈치결\"('-겁'·'-결' 같은 접미사를 붙인 가짜 단어). 한 단어로 안 줄면 두 단어로 풀어 써라.\n"
            + "- 이름은 흔히 쓰는 능력·성향 단어를 ★우선★. 좋은 예: \"손재주\",\"길눈\",\"눈썰미\",\"담력\",\"뚝심\",\"잔꾀\",\"배짱\",\"넉살\",\"억척\",\"붙임성\",\"말주변\",\"잠귀\",\"눈대중\",\"맷집\",\"요령\",\"임기응변\".\n"
            + "- 결 있는 표현은 소수만: \"밤눈\",\"굳은 심지\" 정도. 이름 전부를 \"젖은 손\"·\"떨리는 손끝\"·\"흐려진 이름\"처럼 '꾸미는 말+신체/사물' 시 제목투로 만들지 마라.\n"
            + "- 특성들은 ★서로 다른 능력★. 한 직업 설정을 조각내 여러 특성(\"매장 좌표\",\"신호 잡음\"…)으로 흩뿌리는 '설정집 만들기' 금지. 같은 소재(손·이름·신호·눈)를 반복 말고 몸·감각·성격·습관에서 골고루 뽑아라.\n"
            + "- 직업명·직업 고유명사를 이름에 그대로(일부라도) 넣지 마라. 나쁜 예: 직업 \"무덤 위성 소환사\"→특성 \"무덤 위성\". 직업은 능력이 생긴 이유일 뿐 이름 재료가 아니다.\n"
            + "- name과 description 역할 분리: name=능력을 부르는 자연스러운 이름 하나, description=그 능력이 무엇인지 ★조사가 살아 있는 자연스러운 말★로 짧게 한 줄. 이름을 또 압축한 명사구·조사 생략 금지. 나쁜 예: \"틈열기\"/\"숨은 길 열기\". 좋은 예: \"길눈\"/\"한 번 지난 길은 잊지 않는다\", \"담력\"/\"끔찍한 걸 보고도 다리가 풀리지 않는다\".\n"
            + "- 마지막 점검: 이름을 소리 내어 읽어 한국인이 뜻을 바로 못 알아들으면 버리고 평범한 단어로 다시 지어라.\n"
            + "- 한국어, 창의적\n\n"
            + "직업 등급별 규칙:\n" + tierRules
            + (scenarioFlavor.isBlank() ? "" : "\n" + scenarioFlavor);

        String prompt = "나이: " + pd.age + "세, 직업: " + pd.job
            + "\nHP=" + pd.hp[1] + " STR=" + pd.str + " SAN=" + pd.san[1]
            + " CHA=" + pd.cha + " LUK=" + pd.luk + " SPR=" + pd.spr
            + (roleContext != null && !roleContext.isBlank() ? "\n배역 맥락: " + roleContext : "")
            + "\n\n위 캐릭터에 맞는 초기 특성을 JSON 배열로 생성해줘.";

        return aiManager.callTraitGen(system, prompt).thenApply(raw -> {
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
                    td.grade       = TraitData.normGrade(obj.has("grade") ? obj.get("grade").getAsString() : null, "C");
                    td.description = obj.has("description") ? obj.get("description").getAsString() : "";
                    td.active        = obj.has("active")       && obj.get("active").getAsBoolean();
                    td.effect        = obj.has("effect")       ? obj.get("effect").getAsString()       : "";
                    td.cooldownTurns = obj.has("cooldownTurns") ? obj.get("cooldownTurns").getAsInt()  : 0;
                    // ★E3: 초기 특성도 스탯 예산 적용★ — 예전엔 스탯·예산을 아예 안 봐서 희귀 직업 S/A가 '무료 서술형'이었다.
                    //   스탯 필드를 파싱하고 effectType=""로 applyDefaults → enforcePowerBudget가 스탯을 등급 예산에 클램프(기계효과는 없음).
                    try {
                        if (obj.has("str_add"))     td.str_add    = obj.get("str_add").getAsInt();
                        if (obj.has("cha_add"))     td.cha_add    = obj.get("cha_add").getAsInt();
                        if (obj.has("luk_add"))     td.luk_add    = obj.get("luk_add").getAsInt();
                        if (obj.has("spr_add"))     td.spr_add    = obj.get("spr_add").getAsInt();
                        if (obj.has("hp_max_add"))  td.hp_max_add = obj.get("hp_max_add").getAsInt();
                        if (obj.has("san_max_add")) td.san_max_add= obj.get("san_max_add").getAsInt();
                    } catch (Exception ignore) { /* 잘못된 스탯 필드는 0 유지 — 특성 전체를 폴백시키지 않음 */ }
                    td.effectType = ""; // 초기 특성은 서술형(시스템 기계효과 없음) — 스탯만 등급에 맞춘다
                    SystemTraitRegistry.applyDefaults(td);
                    result.add(td);
                }
                // ★개수 상한(저모델 필러 방지)★: RARE=3, 그 외=2. 저모델이 낯선 직업 설정어를 조각내 4~5개
                //   비슷한 필러 이름(신호 잡음·흐려진 이름…)을 뽑던 문제 → 초과분은 앞에서부터 유지(핵심 강점 우선).
                int cap = (tier == JobTier.RARE) ? 3 : 2;
                if (result.size() > cap) result = new ArrayList<>(result.subList(0, cap));
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