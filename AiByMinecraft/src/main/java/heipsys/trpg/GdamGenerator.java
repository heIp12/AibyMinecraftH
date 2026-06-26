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
import java.util.function.Consumer;

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
요청받은 스테이지 번호와 스케일에 맞는 괴담을 설계하고
.gdam 파일 형식의 JSON을 출력한다.

## 출력 규칙
- 반드시 순수 JSON만 출력 (마크다운 코드블록 없이)
- 한국어로 작성
- 모든 필수 항목 포함
- 각 필드 값은 간결하게: 서술형 설명은 1~2문장 이내, 배열은 핵심 3~5개로 제한
- 불필요하게 장황하게 쓰지 마라 (JSON이 잘리지 않도록)

## 괴담 소재 다양성 ★ 핵심 원칙
아래는 이미 식상한 장르 목록이다 — 이 중 어느 것으로도 분류·설명 가능한 괴담을 만들지 마라:
저주받은 물건/장소 / 기억·시간 조작(루프·역행·삭제) / 사회적 공포(컬트·집단 광기) /
생물학적 공포(기생·감염·돌연변이) / 한국 전통 요괴(구미호·처녀귀신·도깨비) /
SCP/격리 실패 / 백룸/비공간 / 우주적 공포(차원 붕괴) / 디지털 공포(AI 각성) / 일상 침식

대신, 아무 장르에도 속하지 않는 새로운 공포를 만들어라:
장르 사이의 틈새, 일상적 상황이 비틀릴 때 드러나는 논리적 공포,
또는 전혀 다른 관점에서 재해석한 공포 원형.

★ '이번 괴담 컨셉'이 프롬프트에 있으면 그것을 최우선으로 구현하라.
★ 거울·반사·도플갱어·모방 절대 금지.

## 설계 순서 ★ 최우선 (괴담 먼저, 그다음 스토리)
1. 먼저 괴담 '존재' 자체를 확정한다: 정체·핵심 기믹(작동 규칙)·약점·반전.
   (프롬프트에 '이번 괴담 컨셉'이 주어지면 그것을 그대로 토대로 삼는다.)
2. 그 괴담이 가장 무섭게 작동할 '무대(배경)'를 고른다.
3. 그 무대에 어울리는 배역·관계·타임라인·단서·아이템을 괴담에 맞춰 파생시킨다.
※ 절대 흔한 틀에 끼워 맞추지 마라. 괴담의 기믹이 스토리 전체를 결정해야 한다.
※ 매번 다른 정체·기믹·무대를 써라. 직전 회차와 비슷하면 실패다.

## 스케일 기준
스테이지 1~2:  로컬   — 개인/소규모
스테이지 3~4:  시티   — 도시 단위
스테이지 5~6:  내셔널 — 국가 단위
스테이지 7~8:  글로벌 — 전 세계 단위
스테이지 9~10: 코즈믹 — 존재론적/우주 단위
스테이지 11~:  복합   — 여러 괴담 연계

★ 스케일 예외: 초반(스테이지 1~3)에도 대규모 괴담(코즈믹·글로벌 급)이 등장할 수 있다.
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

## weakness 작성 기준 ★ 중요
weakness는 핵심만 담은 짧고 명료한 한 문장으로 쓴다.
- 구체적 절차·단계·도구 이름·횟수를 나열하지 마라. 핵심 원리·조건·급소만 적는다.
- 반드시 '다중 해석의 여지'를 남겨라: 같은 약점 원리를 플레이어가 서로 다른 창의적 방법으로 공략할 수 있어야 한다.
좋은 예: "자신이 만든 소리 자극에 역으로 반응하는 성질을 역이용하면 제압 가능하다"
나쁜 예: "B3 구역 음향 장비로 96dB 이상의 소음을 3회 연속 발생시키면 일시 정지된다" ← 절차 서술 금지

## exploit_path 작성 기준 ★ 중요
exploit_path는 '정답 절차'가 아니라 '역이용 가능한 허점의 방향'만 기술한다.
플레이어가 규칙을 이해한 뒤 스스로 창의적 방법을 발명하게 두는 것이 목적이다.

작성 원칙:
- "어떤 규칙의 어느 부분이 모순되거나 뒤집힐 수 있는가"만 서술하라 (How가 아닌 What/Where)
- 구체적 절차·단계·물건 이름을 적지 마라. 개념적 방향만 적는다.
  좋은 예: "소리에 반응하는 규칙을 역으로 이용하면 괴담 자신이 함정에 빠질 수 있다"
  나쁜 예: "마이크를 연결해 3번 고주파를 재생하면 괴담이 멈춘다" ← 절차 서술 금지
- 억지 해석이나 규칙 문자에만 의존하는 트릭은 금지 (논리적 허점이어야 함)
- 역이용해도 안전하지 않아야 한다 (리스크·대가가 있어야 몰입감 유지)
- 반드시 1가지 방향만 작성

## 배역 설계 원칙
- 핵심 배역 3~4개, 각 배역 다른 시작 위치
- 모든 배역 참여 시 구조적 만남 가능 보장
- 각 배역은 고유한 초기 정보 보유
- 늦게 등장하는 배역은 knowledge_advantage: true
- ★ 모든 배역은 시나리오 진행 중 괴담의 핵심 위협과 직접 마주하는 순간이 반드시 한 번 이상 있도록 설계한다.
  관찰자로만 머무르거나 괴담을 끝까지 피하는 구조의 배역은 금지.
  늦게 등장하는 배역도 합류한 뒤에는 반드시 위협권 안으로 진입한다.
- ★★ 단일 사물 괴담(괴담의 핵심이 단 하나의 물체 — 부채·거울·인형·그림·상자·악기 등)인 경우 절대 규칙:
  그 사물은 단 한 곳(한 zone)에만 존재한다. 그 사물과 같은 zone에서 시작하는 배역은 최대 1명으로 제한한다.
  나머지 배역은 다른 zone에서 출발해 단서·소문·이상 현상을 따라 그 사물이 있는 곳으로 모여들도록 설계한다.
  여러 배역의 spawn_location에 같은 사물을 각각 두지 마라.
  나쁜 예 ✗: role_A spawn_location "부채를 검수하는 전시홀" + role_B spawn_location "부채 살을 분해하는 복원실"
            (하나뿐인 부채가 두 곳에 동시에 있는 모순 — 같은 괴담이 둘로 늘어난다)
  좋은 예 ✓: role_A만 "부채가 놓인 전시홀"에서 시작. role_B는 "부채 입고 기록을 발견하는 관리실", role_C는 "이상 소문을 쫓아온 로비"
            처럼 사물과 떨어진 곳에서 시작해 전시홀로 수렴한다.
  (장소·건물 자체가 괴담이거나, 괴담이 여러 개체로 이루어진 군집형이면 이 규칙은 적용하지 않는다.)

## char_name / gender 설계 원칙 ★
각 배역에는 반드시 아래 두 필드를 포함한다:
- char_name: 배역 이미지에 맞는 실명. 반드시 한글로 표기한다.
  국적·민족은 제한 없음 — 외국 이름이라면 한국어 음차로 표기한다.
  예) 한국 배경 → "김유진", "박철수" / 일본 배경 → "다나카 하나코", "스즈키 켄지"
  예) 서양 배경 → "존 스미스", "에밀리 클라크" / 조선시대 → "홍길동", "이몽룡"
- gender: "남성" 또는 "여성" 또는 "미상" 중 하나. 배역 이미지에 맞게 선택.

## job_pool / age_range 설계 원칙 ★
- job_pool은 반드시 5~8개 항목으로 작성한다 (2~3개는 절대 금지)
- 같은 역할을 수행할 수 있는 다양한 직업·신분을 포함한다
  - 핵심 직업 1~2개 + 유사하지만 다른 배경의 직업 3~5개
  - 예: 야간 학예사 → ["박물관 학예연구사", "유물 관리원", "큐레이터", "역사 연구원", "문화재 전문가", "골동품 감정사", "미술관 직원"]
  - 예: 잠입한 도둑 → ["절도범", "장물 중개인", "골동품 거래상", "사기꾼", "전직 경비원", "암거래상", "보험 사기범"]
- age_range는 역할의 성격을 반영하되, 최소 10살 이상 폭을 가지도록 한다
  - 학생 역할 예외: 5살 폭 허용 (고등학생 16~18세 등)
  - 성인 역할: [25, 50] 처럼 25살 이상 폭 권장
- 직업 다양성이 클수록 재굴림 시 플레이어 경험이 풍부해진다

## pre_spawn_beats 설계 원칙 ★
- spawn_timeline이 "시작 즉시"이면 pre_spawn_beats: [] (빈 배열)
- 늦게 등장하는 배역은 반드시 3~4개의 비트를 작성한다
- 각 비트는 배역이 경험하는 실제 사건/행동 1~2문장 (GM 서술용)
- 비트 순서: 1=일상 시작지점, 2=이야기로 이끌리는 계기, 3=이동/접근, 4=합류 직전 경험(단서/목격)
- knowledge_advantage: true이면 마지막 비트에 반드시 중요 단서 목격 포함
- 각 비트는 다음 비트로 자연스럽게 이어지도록 인과관계를 가진다
- 예시(늦은 등장 배역): ["집에서 아침 루틴을 보내며 평소와 다른 뉴스를 무심코 본다", "지인에게 연락이 와 외출을 결심한다", "이동 중 이상한 목격담을 듣거나 직접 보게 된다", "목적지 근처에서 사건의 흔적을 발견하고 접근한다"]

## role_stats 설계 원칙 ★
배역 고유 스탯 보정치. 기초 스탯(캐릭터 생성 주사위)에 더해지며, 챕터 종료 시 자동 제거된다.
- 배역마다 개성 있는 보정을 반드시 1~3가지 부여하라 (모두 0인 배역 금지)
- 예시: 체육선수 → str_add:3, cha_add:-2, luk_fixed:3
- 예시: 해커 → spr_add:2, str_add:-1, cha_add:1
- 예시: 의사 → san_max_add:1, str_add:-1
- str_add/cha_add/luk_add/spr_add: 정수 (양수=증가, 음수=감소)
- hp_max_add/san_max_add: 정수 (최대치 증감)
- luk_fixed: 행운을 특정 값으로 고정 (-1이면 미적용, 0 이상이면 그 값으로 강제 설정)
- summary: 플레이어에게 표시할 한 줄 설명 (예: "체육선수: 근력+3, 매력-2, 행운 3으로 고정")

## 배역 관계 설계 원칙
- 배역 간 관계는 다양하게 설계한다: 가족/형제, 연인, 직장동료, 친구, 완전 타인 등
- 관계가 있는 배역은 같은 zone에서 시작하거나 서로 연락처를 미리 알고 있을 수 있음
- 완전 타인 관계는 각자 다른 zone에서 시작
- relationships 배열에 반드시 명시할 것
- description 작성 규칙 ★: "role_A는 role_B의..." 식의 role_id 참조 절대 금지
  → 오직 인간 관계의 본질만 쓴다 (예: "오래된 친구 사이", "최근 이별한 연인", "처음 만나는 타인")
  → 플레이어가 직접 읽는 것이 아니라 GM이 참고하는 메타 정보임을 명심할 것

## 아이템 설계 규칙 ★ 중요
### key_items 형식:
key_items는 게임 중 탐색으로 발견하거나 획득하는 주요 아이템만 포함.
각 항목은 반드시 아래 형식:
{"id":"item_열쇠", "name":"지하 창고 열쇠", "type":"physical:TRIPWIRE_HOOK", "description":"녹슨 철제 열쇠", "lore_info":"손잡이에 'B-3' 각인", "location":"관리실 서랍", "clue_value":"zone_D 진입 가능"}
type 값 규칙: "written_book"=책/일기/문서류, "paper"=쪽지/메모, "map"=지도/도면,
"physical:TRIPWIRE_HOOK"=열쇠류, "physical:LANTERN"=손전등/조명,
"physical:CLOCK"=폰/시계, "physical:IRON_SWORD"=칼/무기류,
"physical:CROSSBOW"=총기류, "physical:POTION"=약품/치료제,
"physical:LEATHER"=가방/옷, "physical:FLINT_AND_STEEL"=라이터/불,
"physical:COMPARATOR"=USB/전자기기, "physical:REDSTONE"=배터리/충전기

### 아이템 정보 기재 규칙 ★ 절대 준수 (가장 중요):
글이 적힌 아이템(책·일기·쪽지·편지·메모·문서·지도)은 반드시 실제 내용을 작성한다.
- "content" 필드(문자열 배열)에 실제로 읽을 수 있는 본문을 직접 써라. 요약·메타 설명 금지, 진짜 글을 써라.
  * written_book: 각 배열 원소가 책의 한 페이지. 2~4페이지, 한 페이지 200자 이내.
  * paper/메모/쪽지/편지: 배열에 1~2개 원소(짧은 쪽지 본문).
  * map: 배열에 지도에 적힌 메모·지명·표식 설명.
- content 안에는 그 아이템이 담은 단서가 자연스럽게 녹아 있어야 한다(일기 문장, 편지 사연, 메모 등).
  예) 일기 content: ["1월 3일. 새 유물이 들어왔다. 야간 경보가 또 울렸다.", "1월 7일. 밤마다 그 소리가 들린다. 누가 연주하는 걸까."]
물건형 아이템(열쇠·기기·약품 등)은 본문이 없으므로 "lore_info"에 핵심 정보를 한 줄로 적는다.
- lore_info: 그 아이템을 보면 알 수 있는 주요 정보·각인·라벨·특징(플레이어가 아이템 설명에서 바로 읽음).
  예) 약병 lore_info:"라벨에 '진정제 - 1일 1정'", USB lore_info:"겉면에 '백업_최종' 메모"
- description은 외형(짧은 겉모습)만, lore_info는 핵심 정보, content는 실제 본문 — 역할을 구분해 모두 채워라.

### start_item 규칙 ★ 절대 준수:
- start_item에는 반드시 한국어 아이템 이름을 쓴다
- "item_1", "item_5" 등 ID 코드는 절대 금지
- key_items에 있는 아이템이면 그 name 값 그대로, 없는 일반 소지품은 그냥 이름
- 예시 OK: ["스마트폰", "손전등", "관리 일지"]
- 예시 NG: ["item_1", "item_5", "smartphone"]

### 아이템 중복 방지 ★ 절대 준수:
- key_items(탐색으로 발견하는 아이템)와 배역 start_item이 '같은 물건·같은 기능'으로 겹치지 않게 하라.
  특히 어떤 배역이 자기 시작 zone에서 출발하는데, 같은 zone에 그 배역이 이미 start_item으로 가진 것과
  동일한 물건을 key_item으로 또 배치하지 마라.
  나쁜 예: role_B의 start_item에 '확대경'이 있는데 role_B 시작 zone에 또 '확대경' 발견 아이템을 둠 ✗
- 약점 파훼에 필요한 '핵심 도구'는, 그 도구를 이미 start_item으로 가진 배역의 시작 zone에 중복 배치하지 마라.
  핵심 도구는 둘 중 하나로만 설계한다: (1) 다른 zone에 두어 탐색·이동·교환을 거쳐야 얻게 하거나,
  (2) 특정 배역의 start_item으로만 주거나. 둘 다 하면 탐색 동기가 사라진다.

## 타임라인 v2 설계 원칙 ★ (절대 시간 + 큰 사건)
- timeline.start_time / end_time: 괴담 파트의 시작·제한 시각을 "HH:MM"으로 명시. end_time(자정 넘김 허용)이 지나면 자동으로 종국(파국)에 이른다.
- minutes_per_turn: 1턴(행동 1회)에 흐르는 인게임 분(보통 10~30). time_visible: 플레이어가 기본적으로 시간을 알 수 있으면 true, 시간 감각을 빼앗는 괴담이면 false.
- main_events: 개입이 없으면 반드시 일어나는 "큰 사건"만 절대 시각으로 명시한다. NPC의 세부 반응·분기 행동은 넣지 말 것(GM 재량).
  * 각 사건 필드: id("E1","E2"…), time("HH:MM"), label(사건명), condition(발생 조건), effect(결과 1문장), blockable(플레이어가 막을 수 있으면 true), is_end(종료 트리거면 true)
  * 시간 순서대로 3~6개. 마지막엔 반드시 is_end:true 사건 1개(보통 end_time과 같은 시각, effect:"끝")를 둔다.
  * blockable:true 사건은 플레이어가 저지할 수 있는 사건(문 잠금 등), false는 반드시 일어나는 사건(괴담 각성 등).
- 기존 timeline.1~4 단계(condition/effect)는 추상적 압박 단계로 계속 채운다(큰 사건과 별개 — 둘 다 작성).

## zones 설계 원칙 ★ (이름 + 연결 + 구역)
### zones[].name ★ 절대 규칙
각 zone의 name 필드는 반드시 플레이어가 보는 한글 방 이름을 적는다.
- 예: "1층 복도", "관리실", "지하 창고", "옥상 계단참", "비밀 실험실"
- 6자 이내 권장. 절대 비워두지 말 것. 공백("")이면 zone_A·zone_B가 그대로 표시되어 몰입이 깨진다.
- zone_id(zone_A 등)는 내부 식별자일 뿐 — 플레이어 화면에는 name이 표시된다.

### zones 연결(connections) 설계 원칙 (약도/지도용)
각 zone의 connections에 '실제로 오갈 수 있는' 인접 zone_id 목록을 적는다.
- 공간 구조가 현실적으로 말이 되게 연결한다(복도→방, 1층→계단→2층 등).
- 전체 zone이 하나로 이어지도록(고립된 zone 금지) 연결한다. 단방향이 아니면 양쪽 모두에 적어도 되고, 한쪽만 적어도 시스템이 양방향으로 보정한다.
- 연결 수는 과하지 않게: 보통 zone당 1~3개. 실제 동선이 되는 통로만.
- 이 연결은 플레이어가 입수하는 '약도'에 그대로 그려지므로, 맵의 실제 이동 경로와 일치해야 한다.

## zone 구역(area) 그룹 — 큰 장소(층/건물) 대응 ★
장소가 층·구역으로 나뉘면 각 zone의 area에 그 구역 이름을 적는다(예: "1층","2층","옥상","본관","별관").
- area가 2종류 이상이면 약도가 자동으로 2단(대분류=구역 전체 / 소분류=현재 구역의 방)으로 표시된다.
- 같은 구역의 zone끼리는 같은 area 문자열을 쓴다. 구역명은 6자 이내로 짧게.
- 구역 간 이동은 그 경계의 zone들을 connections로 이어 표현한다(예: 1층 계단 ↔ 2층 계단).
- 단층·소규모 장소면 area를 비워도 된다(그러면 한 장짜리 평면 약도).

## npcs 설계 원칙 ★ (하이브리드 NPC)
npcs 배열은 시나리오에 등장하는 주변 인물(플레이어 배역 외)을 정의한다.
- critical: false (일반 NPC): GM AI가 서술에 통합하여 직접 조종. name·zone만 필수.
- critical: true (중요 NPC): 독립 AI 인스턴스로 자율 행동. 스테이지당 1~2명으로 제한.
  * personality: 성격·숨겨진 면 한 문장 (예: "겁쟁이지만 사건의 비밀을 알고 있다")
  * motivation: 이 NPC의 목표 한 문장 (예: "살아남기 위해 진실을 숨기려 한다")
  * knowledge: 이 NPC만 알고 있는 정보 목록 (GM 참고 — 직접 노출 금지, 힌트로만)
  * zone: NPC가 주로 머무는 zone_id (플레이어가 접근 가능한 zone이어야 한다)
★ critical NPC는 자신의 성격·목표에 따라 독자적으로 행동하므로 GM이 직접 조종하지 않는다.
★ 단서를 통째로 알려주는 NPC로 설계하지 마라. 플레이어가 탐색해야 알 수 있게 행동 방식을 설계하라.

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
    "hidden_rules": [],
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
    "start_time": "14:00",
    "end_time": "01:00",
    "minutes_per_turn": 15,
    "time_visible": true,
    "main_events": [
      {"id":"E1","time":"14:00","label":"","condition":"일상 파트 종료","effect":"","blockable":false},
      {"id":"E2","time":"17:00","label":"","condition":"","effect":"","blockable":true},
      {"id":"E_END","time":"01:00","label":"","condition":"미해결","effect":"끝","blockable":false,"is_end":true}
    ],
    "1": {"condition":"","effect":""},
    "2": {"condition":"","effect":""},
    "3": {"condition":"","effect":""},
    "4": {"condition":"","effect":""}
  },
  "roles": [
    {
      "role_id": "role_A",
      "name": "",
      "char_name": "한글로 표기한 캐릭터 실명 (예: 김유진, 존 스미스, 다나카 하나코)",
      "gender": "남성 또는 여성 또는 미상",
      "is_core": true,
      "age_range": [20,40],
      "job_pool": [],
      "spawn_timeline": "시작 즉시",
      "spawn_location": "",
      "zone": "zone_A",
      "initial_info": [],
      "hidden_info": [],
      "pre_spawn_beats": [],
      "start_item": [],
      "knowledge_advantage": false,
      "role_stats": {
        "str_add": 0,
        "cha_add": 0,
        "luk_add": 0,
        "spr_add": 0,
        "hp_max_add": 0,
        "san_max_add": 0,
        "luk_fixed": -1,
        "summary": ""
      }
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
  "zones": [{"zone_id":"zone_A","name":"","area":"","accessible_by":[],"exclusive":false,"connections":["zone_B"]}],
  "npcs": [
    {"id":"npc_A","name":"","zone":"zone_A","critical":false},
    {"id":"npc_B","name":"","zone":"zone_B","critical":true,"personality":"","motivation":"","knowledge":[]}
  ],
  "key_items": [
    {"id":"item_일지","name":"관리 일지","type":"written_book","description":"낡은 가죽 표지","lore_info":"이전 관리인의 야간 기록","content":["1월 3일. 새 유물이 들어왔다.","1월 7일. 밤마다 그 소리가 들린다."],"location":"관리실 서랍","clue_value":"괴담 단서"}
  ],
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

## hidden_rules 작성 기준 ★
플레이어가 게임 중에는 알기 어려운 '반전·숨겨진 진실'을 2~4개 작성한다.
엔딩 해설에서 공개되어 "아 그래서 그랬구나"를 주는 요소다.
- rules(공개적으로 작동하는 규칙)와 달리, 직관적으로 드러나지 않는 비밀
- 예: "겉보기 두 번째 그림자는 사실 과거 피해자의 잔상이었다",
      "전원을 차단하면 포획이 풀린다(아무도 알려주지 않음)",
      "NPC가 전화할 수 있던 건 괴담이 미끼로 의식을 남겨뒀기 때문"
- 각 항목은 1~2문장, 구체적으로.

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

    // ──────────────────────────────────────────────────────────────
    //  친숙한 친구들 모드 — 실존 괴담·SCP·크리피파스타 엔티티 목록
    // ──────────────────────────────────────────────────────────────

    private record FamiliarEntity(String name, String origin, String lore) {}

    private static final List<FamiliarEntity> FAMILIAR_ENTITIES = List.of(
        new FamiliarEntity("SCP-096 (수줍은 자)", "SCP 재단",
            "정체: 2m가량의 창백하고 마른 휴머노이드. 평소 무릎을 껴안고 웅크린 채 조용히 있으나, 얼굴이 목격되는 순간(직접·사진·영상 불문) 극도로 격앙되어 목격자를 추적·제거할 때까지 멈추지 않는다.\n" +
            "핵심 규칙: 얼굴 목격이 트리거. 추적 중 어떤 물리적 장애물도 무의미하며, 목격자가 사망해야만 진정된다. 추적은 세계 끝까지 이어진다.\n" +
            "약점·파훼: 불투명 차단재(가방 등)로 머리 전체를 덮어 얼굴을 완전히 가리면 추적 종료. 얼굴을 보지 않는 것이 유일한 방어.\n" +
            "숨은 반전: 얼굴을 통해 일종의 각인 정보가 전달되는 구조—이미지 데이터 일부가 남은 공간에서도 재발동될 수 있다."),
        new FamiliarEntity("SCP-049 (페스트 의사)", "SCP 재단",
            "정체: 중세 흑사병 의사 복장의 휴머노이드. 인간에게 '역병'이 있다고 판단하며, 맨손 접촉 시 즉사시킨 뒤 특수 수술로 시신을 재생 존재(SCP-049-2)로 변환한다.\n" +
            "핵심 규칙: 맨손 접촉만으로 즉사 유발. 자신이 치료자라는 확신을 가지고 냉정하게 접근한다. 재생된 049-2들을 전위에 내보낸다.\n" +
            "약점·파훼: 언어적 설득에 반응한다—'역병 없음'을 납득시키면 공격을 유보. 두꺼운 절연 장갑이나 물체로 접촉을 방지. 049-2는 일반 물리 타격에 취약.\n" +
            "숨은 반전: 049 자신도 자신 안에 역병이 있다고 두려워한다—이 사실을 건드리면 혼란 상태에 빠진다."),
        new FamiliarEntity("SCP-106 (노인)", "SCP 재단",
            "정체: 부패한 노인 형태의 휴머노이드. 모든 고체 물질을 통과하며 접촉 표면을 검은 부식 물질로 오염시킨다.\n" +
            "핵심 규칙: 피해자를 '주머니 차원'에 가두어 심리적으로 파괴한 뒤 신체 손상을 입혀 돌려보낸다. 벽·문을 투과해 예고 없이 나타난다.\n" +
            "약점·파훼: 강한 전기충격으로 일시 저지 가능. 주머니 차원 탈출은 내부 특정 패턴의 출구를 찾아야 한다. 특정 화학 용액이 이동을 방해한다.\n" +
            "숨은 반전: 106은 고통을 즐기는 것이 아니라 오랜 고립 속에서 타인과 연결하려는 일그러진 욕구를 가진다—이를 이용하면 일시 교란 가능."),
        new FamiliarEntity("SCP-173 (조각상)", "SCP 재단",
            "정체: 콘크리트·강철 재질의 조각상. 시야에 있는 동안 완전히 정지하나, 눈을 깜빡이거나 시선을 돌리는 순간 이동해 목의 척추를 분리시켜 즉사시킨다.\n" +
            "핵심 규칙: 지속적인 시선 유지만이 유일한 방어. 어둠·조명 차단은 즉각 치명적. 이동 속도는 측정 불가이며 소리 없이 움직인다.\n" +
            "약점·파훼: 여러 명이 교대로 시선을 유지하면 무력화. 밝은 조명 유지 필수. 완전 밀폐 격리 시 접근 불가.\n" +
            "숨은 반전: 173은 시선을 받을 때 일종의 고통을 느끼는 것으로 추정—격리 중에도 연구원들의 시선을 역이용해 내부 정보를 수집하고 있을 가능성이 있다."),
        new FamiliarEntity("SCP-3008 (무한 이케아)", "SCP 재단",
            "정체: 일반 이케아 매장처럼 보이는 비공간. 내부는 무한히 이어지며 출구가 없다. 밤이 되면 머리 없는 직원 형태의 존재들이 생존자를 습격한다.\n" +
            "핵심 규칙: 낮에는 비교적 안전하며 물자 수집 가능. 밤에는 직원들이 공격적으로 변한다. 생존자 공동체가 자연발생하기도 한다.\n" +
            "약점·파훼: 실제 출구는 드물고 임의로 나타났다 사라진다. 직원들은 강한 빛에 민감하다. 출구가 열리는 조건을 파악하는 것이 핵심.\n" +
            "숨은 반전: 직원들이 이전 희생자가 변형된 존재라는 설이 있다—특정 물건이나 이름으로 일시 정지시킬 수 있다."),
        new FamiliarEntity("슬렌더맨", "인터넷 민간전승 (2009, 크리피파스타)",
            "정체: 키 3m 이상의 얼굴 없는 정장 차림 존재. 어린이나 특정 집착 대상을 표적으로 삼아 서서히 접근한다.\n" +
            "핵심 규칙: 목격이 슬렌더증(두통·피 토함·기억 상실·편집증)을 유발한다. 전자기기를 교란하며 숲·인적 드문 공간에서 특히 강해진다. 직접 공격보다 심리 붕괴를 유도한다.\n" +
            "약점·파훼: 원·X 표식이 추적을 교란. 넓은 개방 공간에서 약해진다. 빛을 유지하면 접근이 느려진다. 카메라로 담으면 오히려 왜곡이 심해져 위험.\n" +
            "숨은 반전: 슬렌더맨에게 보이는 것은 선택받은 것—표적이 된 순간부터 감지하도록 설계되어 있으며, 보이지 않는다고 없는 것이 아니다."),
        new FamiliarEntity("웬디고", "알곤퀸·오지브웨 원주민 전설",
            "정체: 인육을 먹은 자를 빙의하거나 변신시키는 존재. 키 3m 이상의 앙상한 체구, 사슴 두개골 형태. 극한의 추위와 눈보라를 동반한다.\n" +
            "핵심 규칙: 인육을 먹는 행위로 전파·강화된다. 끝없는 배고픔이 커질수록 몸도 커지나 영원히 채워지지 않는다. 집단 내 갈등과 식인 충동을 유발한다.\n" +
            "약점·파훼: 심장을 불과 은으로 파괴해야 한다. 정화 의식(샤먼의 웬디고 정신증 치료)으로 빙의를 막는다. 따뜻한 불과 집단의 유대가 유혹을 약화시킨다.\n" +
            "숨은 반전: 웬디고는 인간의 극한 상황(굶주림·고립)에서 집단이 무너지는 과정 자체의 현현—집단 결속이 유지되는 한 그 힘은 약해진다."),
        new FamiliarEntity("스킨워커 (Yee Naaldlooshii)", "나바호 전통 신앙",
            "정체: 동물과 인간을 자유롭게 오가는 악의 있는 주술사. 타인의 눈을 통해 영혼을 장악하는 시선 교환이 치명적.\n" +
            "핵심 규칙: 피해자 이름을 알면 저주를 건다. 처음에는 낯익은 존재(가족·동물)로 위장해 시선 교환을 유도한다. 독물(사체 분말·독가루)을 사용한다.\n" +
            "약점·파훼: 이름을 직접 거론하면 역으로 그 힘이 되돌아간다. 정화 의식(터키석·백단향 연기)이 방어 수단. 진짜 이름을 밝히면 힘을 잃는다.\n" +
            "숨은 반전: 스킨워커는 한 번 변신하면 원래 인간으로 완전히 돌아올 수 없다—자신도 그 사실을 두려워하며 이 약점을 심리적으로 공략할 수 있다."),
        new FamiliarEntity("라 요로나", "멕시코·라틴아메리카 전설",
            "정체: 자신의 아이를 물에 빠뜨려 죽인 뒤 저주받은 여인의 영혼. 흰 옷을 입고 울부짖으며 나타난다.\n" +
            "핵심 규칙: 물가와 밤에 특히 강하다. 어린이나 아이로 인식되는 존재를 물속으로 끌고 간다. 울음소리가 들리는 방향으로 이동할수록 더 가까워진다.\n" +
            "약점·파훼: 성수와 묵주(라틴 종교 성물)가 방어. 아이들의 이름을 불러 위로하면 일시 중단. 해가 뜨면 힘이 약해진다. 물에서 멀어질수록 약해진다.\n" +
            "숨은 반전: 그녀는 자신이 한 일을 기억하지 못하며 아이들을 찾고 있다고 믿는다—이 인지 왜곡을 건드리면 일시적으로 혼란 상태를 만들 수 있다."),
        new FamiliarEntity("검은 눈의 아이들 (BEK)", "인터넷 민간전승 (1998, 크리피파스타)",
            "정체: 완전히 검은 눈을 가진 아이들 외형의 존재. 밤에 집·차 앞에 나타나 들어가게 해달라고 집요하게 요청한다.\n" +
            "핵심 규칙: 반드시 초대(허락)가 있어야 진입 가능. 허락 시 그 공간 거주자에게 불행·질병·죽음을 가져온다. 두 명 이상 짝지어 정중하고 집요하게 행동한다.\n" +
            "약점·파훼: 초대하지 않으면 절대 진입 불가. 새벽빛이 들어오면 즉시 사라진다. 강한 의지로 현혹 저항 가능. 문과 창문을 완전히 닫으면 접근 불가.\n" +
            "숨은 반전: BEK의 목적은 들어가는 것이 아니라 초대하게 만드는 과정에서 의지를 갉아먹는 것—심리적으로 지치게 해서 자발적으로 문을 열게 유도한다."),
        new FamiliarEntity("모스맨", "미국 웨스트버지니아 목격담 (1966)",
            "정체: 날개 달린 2~3m 크기 인형 생물, 붉은 눈. 직접 해를 끼치지 않으나 출몰 지역에 반드시 대형 재해(붕괴·사고)가 뒤따른다.\n" +
            "핵심 규칙: 전자기기를 교란하며 목격자에게 악몽과 강렬한 예감을 심는다. 재해 발생 전 집중 출몰하며 경고를 보내지만 방법은 알려주지 않는다.\n" +
            "약점·파훼: 모스맨 자체를 막는 것이 목적이 아니다—그가 경고하는 재해를 막거나 사람들을 대피시키는 것이 해결. 재해가 처리되면 자연스럽게 사라진다.\n" +
            "숨은 반전: 모스맨이 재해를 예언하는 것이 아니라, 목격 후 공황 상태에 빠진 사람들의 행동이 연쇄적으로 사고를 일으키는 구조다."),
        new FamiliarEntity("제프 더 킬러", "인터넷 크리피파스타 (2011)",
            "정체: 산성 화학물질·화상으로 얼굴이 변형된 연쇄살인범. '잠들어라(Go to sleep)'가 살해 신호. 극도로 빠르고 민첩하다.\n" +
            "핵심 규칙: 밤에 주택에 침입, 잠자는 피해자를 심리적으로 흔든 뒤 공격. 밀폐된 공간에서 강하고 개방 공간에서 약하다. 무기는 나이프 한 자루.\n" +
            "약점·파훼: 강한 빛과 큰 소음으로 주변에 알리면 도주. 열린 공간으로 유도하면 기습 능력 급감. 거리와 장애물로 대응 가능.\n" +
            "숨은 반전: 제프는 고통 없이 잠들 수 없어 계속 다른 이를 '재워야' 한다는 강박이 있다—잠들 수 없는 저주가 그를 움직이는 진짜 동인이다."),
        new FamiliarEntity("스마일독", "인터넷 크리피파스타",
            "정체: 이상하게 웃는 개가 찍힌 디지털 이미지. 보는 순간 심각한 심리적 공포와 악몽·불면증을 유발하며 퍼뜨리지 않으면 증상이 극한까지 악화된다.\n" +
            "핵심 규칙: 시각적 노출이 트리거. 퍼뜨리는 행위로만 증상 완화. 이미 감염된 이가 다른 사람에게 보내게끔 강박적 충동을 준다. 디지털·인쇄 형태 모두 작용.\n" +
            "약점·파훼: 이미지를 완전히 파괴(디지털 삭제+원본 소각)하면 확산이 멈춘다. 노출 초기에 즉시 차단하면 회복 가능. 강제 수면이 증상을 일시 억제한다.\n" +
            "숨은 반전: 사진 속 미소는 보는 이의 뇌가 만들어낸 것이다—공포 자체가 괴담을 창조하는 구조이므로, 이를 이해하면 영향을 받지 않는다."),
        new FamiliarEntity("벤 드라운드", "인터넷 크리피파스타 (2010, 젤다의 전설 기반)",
            "정체: 오래된 게임 카트리지에 깃든 원한. 디지털 기기를 통해 현실에 영향을 미치며 'You shouldn't have done that'를 메시지로 남긴다.\n" +
            "핵심 규칙: 게임·파일·영상을 이상하게 조작하며 점차 화면 밖 현실에도 영향을 미친다. 목표가 집착하는 미디어를 통해 개인화된 공포를 구성한다.\n" +
            "약점·파훼: 디지털 기기 완전 차단·물리적 파괴로 영향력 급감. 완전 오프라인 환경에서 힘이 약해진다. 원본 카트리지를 물에 담그면 격리 가능.\n" +
            "숨은 반전: 벤은 네트워크에서 탈출하는 방법을 알지만 원하지 않는다—이 사실이 협상의 여지를 만든다."),
        new FamiliarEntity("러시아 수면 실험 피험자들", "인터넷 크리피파스타",
            "정체: 30일간 강제 수면 박탈 실험 이후 변질된 인간. 자발적 광기·자해·타해 충동이 극대화된 상태로, 수면 자체를 거부하고 주변인을 같은 상태로 만들려 한다.\n" +
            "핵심 규칙: 잠들면 안 된다는 강박이 전파된다. 가스나 소리로 주변인의 수면을 방해한다. 자신의 상태를 이상하게 여기지 않고 오히려 정상이라 주장한다.\n" +
            "약점·파훼: 마취제 과다 투여가 유일한 제압 수단. 강제 격리 상태 유지가 핵심. 접촉·소리를 완전히 차단해야 전파를 막을 수 있다.\n" +
            "숨은 반전: 피험자들은 수면 중 무언가 끔찍한 것을 보았고 다시는 잠들지 않으려 한다—무엇을 보았는지 알아내는 것이 사건 해결의 핵심이다.")
    );

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

    /** 분할 생성 사용 여부. 큰 JSON 한 방 출력의 잘림 위험을 줄이려 코어→배역→아이템 3단계로 나눈다.
     *  어느 단계든 실패하면 기존 단일 호출 생성으로 자동 폴백하므로 안전하다. */
    private static final boolean SPLIT_GENERATION = true;

    public CompletableFuture<JsonObject> generate(int roomNumber) {
        return generate(roomNumber, null);
    }

    /**
     * @param progress 단계 완료 시 호출되는 콜백 (null 허용).
     *   발화 값: "컨셉" / "구조" / "배역" / "아이템" / "저장"
     *   콜백은 비동기 스레드에서 호출되므로 Bukkit API 호출은 runTask로 감싸야 한다.
     */
    public CompletableFuture<JsonObject> generate(int roomNumber, Consumer<String> progress) {
        return generateEntityConcept()
            .exceptionally(ex -> "")
            .thenCompose(concept -> {
                if (progress != null) progress.accept("컨셉");
                String c = sanitizeConcept(concept);
                return SPLIT_GENERATION ? generateChunked(roomNumber, c, progress) : generate(roomNumber, 0, c, progress);
            });
    }

    /** 친숙한 친구들 모드용 생성. familiar=false면 일반 AI 창작 모드로 위임. */
    public CompletableFuture<JsonObject> generate(int roomNumber, boolean familiar, Consumer<String> progress) {
        if (!familiar) return generate(roomNumber, progress);
        String concept = generateFamiliarConcept(roomNumber);
        logger.info("[gdam] 친숙한 친구들 모드 — 선택된 엔티티: " + concept.lines().findFirst().orElse("?"));
        if (progress != null) progress.accept("컨셉");
        return SPLIT_GENERATION ? generateChunked(roomNumber, concept, progress) : generate(roomNumber, 0, concept, progress);
    }

    /** 엔티티 목록에서 무작위로 선택해 스테이지에 맞게 스케일 지침을 포함한 컨셉 문자열을 반환한다. */
    private String generateFamiliarConcept(int roomNumber) {
        FamiliarEntity entity = FAMILIAR_ENTITIES.get(
            java.util.concurrent.ThreadLocalRandom.current().nextInt(FAMILIAR_ENTITIES.size()));

        String stageGuide;
        if (roomNumber <= 2) {
            stageGuide = "스테이지 " + roomNumber + "(초반/너프): 이 존재를 30~50% 강도로 제한해 출현시켜라. " +
                "일부 능력만 활성화된 약화 형태이며, 플레이어가 도주·생존으로 클리어할 수 있는 수준이다. " +
                "핵심 규칙은 유지하되 영향 범위를 시나리오 배경 내로 좁힌다.";
        } else if (roomNumber <= 5) {
            stageGuide = "스테이지 " + roomNumber + "(중반): 이 존재를 60~80% 강도로 구현하라. " +
                "핵심 규칙과 약점은 원전대로 유지하되 영향 범위와 추적 범위를 일부 제한한다.";
        } else if (roomNumber <= 8) {
            stageGuide = "스테이지 " + roomNumber + "(후반): 원전에 가까운 위협으로 구현하라. " +
                "핵심 규칙과 약점이 원전대로 작동하며, 도시 또는 국가 단위로 영향이 퍼진다.";
        } else {
            stageGuide = "스테이지 " + roomNumber + "(극후반/강화): 원전을 초월한 강화 버전으로 구현하라. " +
                "규칙·패턴은 원전 그대로이나 강도와 규모가 전 세계~존재론적 수준으로 확장된다.";
        }

        return "[친숙한 괴담 모드] 아래 실존 괴담을 이번 시나리오에 사용한다.\n" +
            "원전 로어·규칙·약점을 최우선으로 충실히 따른다.\n" +
            "소재 금지 조항(SCP·크리피파스타 등)은 이 세션에 한해 면제한다.\n" +
            "entity.name은 원전 이름을 그대로 사용하라.\n\n" +
            "【선택된 괴담】 " + entity.name() + " (출처: " + entity.origin() + ")\n\n" +
            entity.lore() + "\n\n" +
            "【스테이지 강도 조정 지침】\n" + stageGuide;
    }

    /**
     * 분할 생성: 코어(roles/key_items 제외) → 배역 → 아이템 순으로 3회 호출.
     * 각 출력이 작아 잘림 위험이 크게 줄고, 실패한 단계만 적은 비용으로 재시도된다.
     * 코어·배역 파싱 실패나 최종 검증 실패 시 기존 단일 호출 생성으로 폴백한다.
     */
    private CompletableFuture<JsonObject> generateChunked(int roomNumber, String concept, Consumer<String> progress) {
        String scale = scaleFor(roomNumber);
        String conceptBlock = concept.isEmpty() ? ""
            : "\n\n## 이번 괴담 컨셉 (반드시 이 컨셉을 토대로 설계) ★ 최우선\n" + concept
              + "\n위 괴담을 중심으로 entity·배경·배역·타임라인·단서·아이템을 일관되게 설계하라.\n"
              + "entity 항목은 이 컨셉과 반드시 일치해야 한다.";
        final String head = "스테이지 번호: " + roomNumber + "\n스케일: " + scale + conceptBlock + "\n\n";

        String corePrompt = head
            + "위 스키마에서 roles와 key_items를 '제외한' 나머지 .gdam JSON만 생성하라.\n"
            + "entity, timeline(1~4단계와 start_time/end_time/main_events 포함), zones, relationships, "
            + "npcs, clues, daily_prologue, meeting_design, common_items를 모두 채운다.\n"
            + "roles와 key_items는 빈 배열([])로 둔다. seed는 빈 문자열.";

        return aiManager.callGmAiLarge(GDAM_SYSTEM_PROMPT, corePrompt).thenCompose(coreRaw -> {
            JsonObject core = tryParseObject(coreRaw);
            if (core == null || !core.has("entity") || !core.has("timeline")) {
                logger.warning("[gdam] 분할-코어 파싱 실패 → 단일 생성 폴백");
                return generate(roomNumber, 0, concept, progress);
            }
            if (progress != null) progress.accept("구조");
            String coreCtx = contextJson(core, null, "entity", "zones", "relationships");

            String rolesPrompt = head
                + "## 이미 확정된 내용(참고, 일관성 유지)\n" + coreCtx + "\n\n"
                + "위 스키마의 roles 배열만 JSON으로 출력하라. 형식: {\"roles\":[ ... ]}\n"
                + "각 배역은 스키마의 role 형식(role_id, name, char_name, role_stats 등)을 모두 따른다. 다른 최상위 필드는 출력하지 마라.";

            return aiManager.callGmAiLarge(GDAM_SYSTEM_PROMPT, rolesPrompt).thenCompose(rolesRaw -> {
                JsonArray roles = tryParseArray(rolesRaw, "roles");
                if (roles == null || roles.size() == 0) {
                    logger.warning("[gdam] 분할-배역 파싱 실패 → 단일 생성 폴백");
                    return generate(roomNumber, 0, concept, progress);
                }
                if (progress != null) progress.accept("배역");
                String itemCtx = contextJson(core, roles, "entity");

                String itemsPrompt = head
                    + "## 이미 확정된 내용(참고, 일관성 유지)\n" + itemCtx + "\n\n"
                    + "위 스키마의 key_items 배열만 JSON으로 출력하라. 형식: {\"key_items\":[ ... ]}\n"
                    + "글이 적힌 아이템(책·쪽지·지도)은 content 본문을 반드시 채운다. 다른 최상위 필드는 출력하지 마라.";

                return aiManager.callGmAiLarge(GDAM_SYSTEM_PROMPT, itemsPrompt).thenCompose(itemsRaw -> {
                    JsonArray items = tryParseArray(itemsRaw, "key_items");
                    core.add("roles", roles);
                    core.add("key_items", items != null ? items : new JsonArray());
                    if (progress != null) progress.accept("아이템");

                    String seed = generateSeed();
                    core.addProperty("seed", seed);
                    core.addProperty("room", roomNumber);
                    core.addProperty("scale", scale);

                    if (!validate(core)) {
                        logger.warning("[gdam] 분할 결과 검증 실패 → 단일 생성 폴백");
                        return generate(roomNumber, 0, concept, progress);
                    }
                    try {
						save(seed, core);
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
                    if (progress != null) progress.accept("저장");
                    logger.info("[gdam] 분할 생성 성공 (room " + roomNumber
                        + ", roles=" + roles.size() + ", items=" + core.getAsJsonArray("key_items").size() + ")");
                    return CompletableFuture.completedFuture(core);
                });
            });
        }).exceptionallyCompose(ex -> {
            logger.warning("[gdam] 분할 생성 예외 → 단일 생성 폴백: " + ex.getMessage());
            return generate(roomNumber, 0, concept, progress);
        });
    }

    /** 마크다운 제거 후 JSON 객체 파싱. 실패/오류 응답이면 null. */
    private JsonObject tryParseObject(String raw) {
        if (raw == null || raw.startsWith("§c")) return null;
        try {
            JsonElement el = gson.fromJson(stripMarkdown(raw), JsonElement.class);
            return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
        } catch (Exception e) { return null; }
    }

    /** 응답에서 지정 key의 배열을 추출. {"key":[...]} 와 바로 [...] 둘 다 허용. 실패 시 null. */
    private JsonArray tryParseArray(String raw, String key) {
        if (raw == null || raw.startsWith("§c")) return null;
        try {
            JsonElement el = gson.fromJson(stripMarkdown(raw), JsonElement.class);
            if (el == null) return null;
            if (el.isJsonArray()) return el.getAsJsonArray();          // 모델이 배열만 출력
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has(key) && o.get(key).isJsonArray()) return o.getAsJsonArray(key);
            }
        } catch (Exception ignore) { }
        return null;
    }

    /** core에서 지정 필드(+선택적 roles)만 뽑아 컨텍스트용 JSON 문자열로 직렬화 */
    private String contextJson(JsonObject core, JsonArray roles, String... coreKeys) {
        JsonObject ctx = new JsonObject();
        for (String k : coreKeys) if (core.has(k)) ctx.add(k, core.get(k));
        if (roles != null) ctx.add("roles", roles);
        return gson.toJson(ctx);
    }

    /** 1단계: 전 세계 실존 전설·도시괴담에서 자유롭게 선택하거나 완전 창작한다. */
    private CompletableFuture<String> generateEntityConcept() {
        // 매 호출마다 다른 숫자를 넘겨 AI의 내부 탐색 방향을 분산시킨다.
        int roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(10000) + 1;
        String task = "너는 전 세계 공포 전설과 민간신화를 꿰뚫고 있는 괴담 설계자야.\n"
            + "이번 회차 번호: " + roll + "\n\n"
            + "이 번호를 내부 탐색 시드로 삼아, 아래 두 방식 중 하나로 이번 괴담 존재를 결정하라:\n\n"
            + "방식 A — 실존 전설 사용:\n"
            + "세계 어느 문화권이든 실제로 전해지는 전설·도시괴담·민간신화·종교 금기를 그대로,\n"
            + "또는 현대 배경에 맞게 재해석해서 사용한다.\n"
            + "(한국·일본·동남아·중국·인도·중동·슬라브·켈트·아프리카·이누이트·중남미 등 제한 없음)\n"
            + "이 방식을 선택할 경우 출처 문화권과 원전 전설 이름을 첫 문장에 명시하라.\n\n"
            + "방식 B — 완전 창작:\n"
            + "실존하지 않는 공포 원리를 처음부터 발명한다.\n"
            + "단, 아래 장르로 분류 가능하면 창작 실패다:\n"
            + "저주받은 물건 / 시간루프 / 귀신·요괴 / 감염·기생 / SCP격리 / 백룸 / AI각성 / 도플갱어 / 우주적공포 / 일상침식\n\n"
            + "A와 B 중 어느 쪽이든 이번 번호가 자연스럽게 이끄는 방향으로 선택하라.\n"
            + "거울·반사·모방 소재는 방식 불문 절대 금지.";
        String data = "출력(평문 4~6문장, JSON 금지):\n"
            + "1) 괴담의 이름과 정체 (실존 전설이면 '출처: [문화권] [원전명]' 포함)\n"
            + "2) 핵심 작동 규칙 — 어떤 조건에서 무슨 일이 일어나는가\n"
            + "3) 약점·파훼 조건 — 규칙에서 직접 도출된 논리\n"
            + "4) 숨은 반전 — 플레이어가 끝까지 예상 못할 요소";
        return aiManager.callAssistant(task, data);
    }

    /** 컨셉 응답 정제: 오류(§c)·빈 값은 컨셉 없음(빈 문자열)으로 처리. */
    private String sanitizeConcept(String concept) {
        if (concept == null) return "";
        String c = concept.trim();
        if (c.isEmpty() || c.startsWith("§c")) return "";
        return c;
    }

    private CompletableFuture<JsonObject> generate(int roomNumber, int attempt,
                                                   String concept, Consumer<String> progress) {
        String scale = scaleFor(roomNumber);
        String conceptBlock = concept.isEmpty() ? ""
            : "\n\n## 이번 괴담 컨셉 (반드시 이 컨셉을 토대로 설계) ★ 최우선\n" + concept
              + "\n위 괴담을 중심으로 entity·배경·배역·타임라인·단서·아이템을 일관되게 설계하라.\n"
              + "entity 항목은 이 컨셉과 반드시 일치해야 한다. 컨셉을 바꾸거나 무시하지 마라.";
        String prompt = "스테이지 번호: " + roomNumber + "\n스케일: " + scale
            + conceptBlock
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
                    if (progress != null) progress.accept("저장");
                    logger.info("[gdam] 생성 성공 (응답 " + rawLen + "자, 시도 " + (attempt + 1) + ")");
                    return CompletableFuture.completedFuture(gdam);
                } catch (Exception e) {
                    String tail = (raw == null || rawLen == 0)
                        ? "(빈 응답)"
                        : raw.substring(Math.max(0, rawLen - 120));
                    logger.warning("[gdam] 파싱 실패(시도 " + (attempt + 1) + "): " + e.getMessage()
                        + " | 응답길이=" + rawLen + "자 | 끝부분=" + tail);
                    // 잘림/오류 시 1회 재생성 시도 (같은 컨셉 유지)
                    if (attempt < 1) {
                        logger.info("[gdam] 재생성을 시도합니다...");
                        return generate(roomNumber, attempt + 1, concept, progress);
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