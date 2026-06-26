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
 *  2. 기본 스탯 배분 (총합 25, 각 최소 1)
 *  3. 최저 스탯 +1, 무작위 [+4,+2,+1,-2] 적용
 *  4. Haiku AI로 나이/직업/배역 보정
 *  5. AI 초기 특성 생성 (1~2개, 직업·나이 맥락)
 */
public class CharacterGenerator {

    private static final Random RNG = new Random();

<<<<<<< HEAD
    private static final String[] JOB_POOL = {
            // [기존 직업]
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
            "정치인","공무원","외교관","통역사",

            // [흔한 일상 직업]
            "회사원","대기업사원","중소기업대리","콜센터상담원","택배기사","미용사","메이크업아티스트",
            "네일아트종사자","은행원","보험설계사","영업사원","건물주","부동산중개인","관리사무소장",
            "목수","배관공","전기기사","도배사","타일공","용접공","생산직노동자","지게차운전수",
            "물류센터알바","식당이모님","주차요원","톨게이트수납원","주유소알바","세차장직원",
            "PC방알바","카페알바","패스트푸드점알바","영화관알바","화장품매장직원","옷가게점원",
            "인테리어업자","자동차정비공","가구제작자","세탁소주인","구두닦이","열쇠수리공",
            "철물점주인","꽃집주인","과일가게주인","정육점주인","수산시장상인","시장상인",
            "포장마차주인","푸드트럭사장","대리기사","녹즙배달원","요구르트아줌마","신문배달원",
            "우편집배원","안경사","치과위생사","물리치료사","작업치료사","방사선사","임상병리사",
            "간호조무사","요양보호사","보육교사","유치원원장","방과후교사","학습지교사",
            "피아노학원원장","태권도관장","미술학원강사","헬스장관장","필라테스강사","요가강사",
            "수영강사","스키강사","캐디",

            // [이상한/이색 직업]
            "냄새감별사","겨드랑이냄새테스터","애완동물사료시식가","뱀독추출가","악어이빨닦이",
            "수중목재벌목꾼","골프공다이버","인형탈알바","귀지청소전문가","닭성별감별사",
            "워터슬라이드테스터","자유의여신상횃불청소부","껌떼기전문가","주사위품질검사관",
            "포춘쿠키메시지작가","미스터리쇼퍼","신혼여행테스터","섬관리인","빙산이동전문가",
            "우주쓰레기청소부","운석사냥꾼","공룡뼈발굴자","심해탐험가","미각보험가입자",
            "줄서기대행알바","하객대행","사과대행업자","이별통보대행업자","눈물연기전문가",
            "뺨때리기전문가","웃음치료사","ASMR유튜버","먹방BJ","버튜버",
            "프로게이머","사이버렉카","악플지우개","데이터라벨러","AI프롬프트엔지니어",
            "메타버스건축가","비트코인채굴자",

            
            "방구석여포","키보드워리어","뇌피셜전문가",
            "귤까기장인","모기사냥꾼","층간소음복수전문가","전업덕후","아이돌홈마",
            "성공한덕후","동네오지라퍼","프로이직러",
            "월급루팡","주식물린개미","코인존버러","당근마켓네고장인",
            "프로낮잠러","고양이집사","강아지산책도우미",
            "프로다이어터","아가리어터","맛집탐방가","영수증리뷰어",
            "쿠팡체험단","배민VIP","별다방단골","잔소리수집가",
            "팩트폭격기","영혼없는리액션장인","프로결정장애러","벼락치기전문가","과제암살자",
            "조별과제버스기사","대학원생(노예)",

            // [괴담/유령/괴이현상 관련 직업]
            "퇴마사","무당","박수무당","심령술사","오컬트연구가","흉가체험유튜버",
            "괴담수집가","저주받은물건감정사","심령사진판독가","유령사냥꾼","폴터가이스트해결사",
            "엑소시스트","악마숭배자","사이비종교교주","미확인비행물체(UFO)추적자",
            "크립티드사냥꾼","뱀파이어헌터","늑대인간연구가","좀비생존전문가","좀비배우",
            "시간여행자","전생체험가","타로마스터",
            "사주팔자전문가","관상가","풍수지리가","영매",
            "저승사자","구미호","도깨비", "수호령","원혼달래기전문가","장의사","시체닦이",
            "특수청소부","부검의","법의학자","미제사건프로파일러","SCP재단연구원",
            "SCP재단D급인원","기동특무부대원","기억소거담당관",
            "평행우주관측자","마도구제작자","연금술사",
            "네크로맨서","주술사","부두술사","강령술사",
            "도플갱어","몽유병치료사","가위눌림해결사","저주인형제작자"
        };
=======
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
>>>>>>> branch 'claude/optimistic-hamilton-jw2ayv' of https://github.com/heIp12/AibyMinecraftH

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

        String sys = "한국어 직업 이름만 JSON 문자열 배열로 응답. 설명·마크다운 없음. [\"직업1\",\"직업2\",...]";
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        if (needsRefresh(dynCommon, globalUsed, COMMON_JOB_POOL.length)) {
            futures.add(callAndFillPool(sys,
                "TRPG 배경의 평범한 일상 직업 150개를 JSON 배열로. " +
                "학생·의료·교육·사무·서비스·예술·IT·기술직·농축수산업 등 최대한 다양하게. " +
                "직업명은 한국어 2~10자, 중복 없이.", dynCommon));
        }
        if (needsRefresh(dynStrong, globalUsed, STRONG_JOB_POOL.length)) {
            futures.add(callAndFillPool(sys,
                "TRPG 배경의 해결사·전투·수사·초자연 전문가 직업 100개를 JSON 배열로. " +
                "엑소시스트·SCP요원·용병·형사·특수부대원·봉마사 등 전문 능력자 위주. " +
                "직업명은 한국어 2~15자, 중복 없이.", dynStrong));
        }
        if (needsRefresh(dynRare, globalUsed, RARE_JOB_POOL.length)) {
            futures.add(callAndFillPool(sys,
                "TRPG 배경의 초자연적·변수가 큰 특이 직업 50개를 JSON 배열로. " +
                "뱀파이어·흑마법사·시간여행자·랩틸리언 등 강력하지만 대가가 큰 존재 위주. " +
                "직업명은 한국어 2~15자, 중복 없이.", dynRare));
        }

        if (futures.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                .thenRun(this::saveCache);
    }

    /** 풀에서 절반 이상 직업이 이미 사용됐으면 true (AI 재호출 필요). staticSize=정적 폴백 크기 */
    private boolean needsRefresh(List<String> pool, Set<String> globalUsed, int staticSize) {
        if (pool.size() <= staticSize) return true; // 아직 AI 갱신 없음 → 무조건 갱신
        synchronized (pool) {
            long usedCount = pool.stream().filter(globalUsed::contains).count();
            return usedCount >= pool.size() * 0.5;
        }
    }

    private CompletableFuture<Void> callAndFillPool(String system, String prompt, List<String> target) {
        return aiManager.callAssistant(system, prompt).thenAccept(raw -> {
            try {
                String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
                int s = cleaned.indexOf('['), e = cleaned.lastIndexOf(']');
                if (s == -1 || e == -1) return;
                JsonArray arr = GSON.fromJson(cleaned.substring(s, e + 1), JsonArray.class);
                List<String> jobs = new ArrayList<>();
                for (JsonElement el : arr) {
                    String j = el.getAsString().trim();
                    if (!j.isBlank()) jobs.add(j);
                }
                if (jobs.size() >= 20) {
                    synchronized (target) { target.clear(); target.addAll(jobs); }
                }
            } catch (Exception ignored) {}
        });
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

        // 직업 — roleData가 있으면 배역 풀 우선, 없으면 가중치 계층 선택
        JobTier tier = JobTier.COMMON;
        if (roleData != null && roleData.has("job_pool")) {
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
            int roll = RNG.nextInt(100);
            List<String> pool;
            if (roll < 70) {
                tier = JobTier.COMMON;
                pool = dynCommon;
            } else if (roll < 90) {
                tier = JobTier.STRONG;
                pool = dynStrong;
            } else {
                tier = JobTier.RARE;
                pool = dynRare;
            }
            pd.job = pickUnusedJob(pd.uuid, pool);
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
        return tier;
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
- 개수: 2개 이상 (강점 + 약점/부작용)
""";
            default -> """
- grade: C 또는 D/F 만 사용 (초기 캐릭터이므로 강한 특성 없음)
- 개수: 1~2개
""";
        };

        String system = "너는 TRPG 캐릭터 초기 특성 생성기야.\n"
            + "아래 JSON 배열 형식으로만 응답 (다른 텍스트 금지):\n"
            + "[{\"id\":\"\",\"name\":\"\",\"grade\":\"C\",\"description\":\"\",\"active\":false,\"effect\":\"\","
            + "\"cooldownTurns\":0},...]\n\n"
            + "공통 규칙:\n"
            + "- 직업·나이에서 자연스럽게 연결되는 능력/약점\n"
            + "- description과 effect에 스탯 숫자·스탯 약어(STR/HP/SAN/CHA/LUK/SPR) 절대 사용 금지\n"
            + "- description: 아주 짧은 명사구 한 줄(최대 18자). 예: \"빠른 처치 손길\", \"겁 많은 성격\"\n"
            + "- effect: 한 문장으로 간결하게 (수동이면 판정에서 발휘되는 식, 능동이면 사용 효과)\n"
            + "- 직업명을 그대로 쓰지 말고 구체적 능력으로 작성\n"
            + "- 한국어, 창의적\n\n"
            + "직업 등급별 규칙:\n" + tierRules;

        String prompt = "나이: " + pd.age + "세, 직업: " + pd.job
            + "\nHP=" + pd.hp[1] + " STR=" + pd.str + " SAN=" + pd.san[1]
            + " CHA=" + pd.cha + " LUK=" + pd.luk + " SPR=" + pd.spr
            + (roleContext != null && !roleContext.isBlank() ? "\n배역 맥락: " + roleContext : "")
            + "\n\n위 캐릭터에 맞는 초기 특성을 JSON 배열로 생성해줘.";

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
                    td.active        = obj.has("active")       && obj.get("active").getAsBoolean();
                    td.effect        = obj.has("effect")       ? obj.get("effect").getAsString()       : "";
                    td.cooldownTurns = obj.has("cooldownTurns") ? obj.get("cooldownTurns").getAsInt()  : 0;
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
