package heipsys.trpg;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 시나리오 아키타입 풀 — 세계 규칙(world_rules) / 시작 구조(opener) / NPC 역할(role_type).
 *
 * 전부를 생성 프롬프트에 넣으면 ① 단일 String 64KB 한계 ② 주의력 희석(많을수록 각 규칙 준수율↓)
 * ③ 엔진이 못 받치는 유형 남발 — 세 문제가 생긴다. 그래서 데이터로 분리해두고
 * 매 생성마다 ★소수만 무작위 샘플링★해 주입한다(회차마다 조합이 달라 다양성도 올라간다).
 *
 * 각 유형엔 '작동 티어'를 단다:
 *   A = 서술만으로 완전 작동(엔진 기본 기능으로 충분)
 *   B = 경량 — 교차 플레이어/지속 상태 필요. 지금은 GM 서술+기존 효과(피해·오염·WITNESS·상태)로 근사(자동 강제 미구현)
 *   C = 대형 — 엔진이 못 받침. '서술 가능한 범위로 축소'하고 지킬 수 없는 약속 금지.
 * 샘플에 섞인 티어에 맞춰 구현 지침을 함께 주입한다.
 */
public final class ScenarioArchetypes {

    public enum Tier { A, B, C }
    public enum Slot { RULE, OPENER, ROLE }
    public record Arch(Tier tier, Slot slot, String text) {}

    private ScenarioArchetypes() {}

    // 데이터 형식: 한 줄에 "TIER|SLOT|설명". 설명 안의 '|'는 split 한도(3)로 보존된다.
    private static final String RULES_DATA = """
A|RULE|규칙형 — 규칙이 별도 주체이고 entity·NPC는 그것을 구현·집행하는 수단. ★필수산출★ ①지배 규칙 1개를 별도 주체로 확정 ②entity·NPC가 그 규칙을 어떻게 집행하는가.
A|RULE|단일 존재형 — 쿠네쿠네처럼 '그 존재 자체가 곧 규칙'. ★필수산출★ ①그 존재의 본질을 core에 한 줄 ②그 본질에서 위협·금기가 어떻게 따라 나오는가.
A|RULE|정보 격리형 — 아는 자가 표적·모르는 자가 안전. ★필수산출★ ①표적 조건 1개 확정('무엇을·누가 알면 표적인가') ②그 사실을 직접 명시 없이 최후반에만 간접 단서로 흘리는 사슬(다른 배역은 '저 사람에겐 숨겨야 한다'를 스스로 눈치) ③모르는 자를 지키거나 아는 자를 숨기는 구체 행동. 이미 아는 다회차 캐릭은 시작부터 표적.
A|RULE|통신 유인형 — 전화·무전·@통신을 '쓰는 행위 자체'가 괴담을 부른다(작동하되 쓰면 위험). ★필수산출★ ①통신 시 무엇이 응답·추적·강화하는가를 world_rules에 ②constraints.comms_dangerous=true 설정.
A|RULE|자동 해결형(비개입형) — 두면 해결·개입하면 악화('가만히 있었으면 클리어'). ★필수산출★ ①스스로 매듭지어지는 사건과 그것을 망치는 개입 ②그 진행이 플레이어 눈엔 위험·해악으로 보여 말리고 싶은 외형 ③constraints.noninterference=true, collapse=비개입, loophole=그 행동이 해결 과정임을 알아채는 단서. 진실을 모르면 참기 어렵게.
A|RULE|규칙 목록 준수형 — 지킬 규칙 목록이 주어지되 일부는 가짜·함정. ★필수산출★ ①details에 진짜+함정 규칙을 섞고 각각 어기면 무슨 일이 나는가 ②loophole=가짜를 알아채는 단서. 압박 속에 진짜만 지키는 게 플레이.
A|RULE|순번·연쇄형 — 정해진 순서로 표적이 된다(이름·자리·나이·들어온 순서 등). ★필수산출★ ①순번 기준 1개 확정 ②collapse=순번 고리를 끊거나 다음 표적을 보호·교란.
A|RULE|금기 행동형 — 특정 행동(소리내기·뒤돌아보기·이름 부르기·불 켜기 등)이 괴담을 부른다. ★필수산출★ ①어떤 행동이 무엇을 부르는가 명시. 행동을 절제하는 긴장이 핵심.
A|RULE|교환·대가형 — 통과·안전·정보엔 대가가 따른다. ★필수산출★ ①교환표 2개 이상('무엇을 바치면 무엇을 얻나': 물건·기억·신체·동료) ②거부 시 결과 ③최소 1개는 '동료·자신'을 거는 도덕 딜레마.
A|RULE|시한 의식형 — 마감(자정·만조·의식 완성·N턴)이 다가온다. ★필수산출★ ①구체적 마감과 그 전에 막거나 완수할 핵심 조건 ②collapse=마감 전 그 조건 달성. 타임라인·시계 연동.
A|RULE|조건부 안전지대형 — 특정 위치·자세·조건에서만 안전하고 그 조건이 계속 변한다(빛 속·그림자·물 밖 등). ★필수산출★ ①어디가 언제 안전한지 규칙 ②안전 조건이 어떻게 변하는가. 위치·타이밍 싸움.
A|RULE|역(逆)의식·수집형 — 흩어진 재료·조각을 모아 반대 의식으로 봉인·해제. ★필수산출★ ①모을 재료·조각과 흩어진 위치 ②collapse=재료를 모아 역의식 완성. 탐색·조합이 플레이.
A|RULE|도덕 딜레마형 — 괴담이 불가능한 선택을 강요한다(누굴 살릴지·뭘 포기할지). ★필수산출★ ①정답 없는 선택지 ②선택지마다 다른 결과·후폭풍. 대가가 핵심.
A|RULE|집단환각형 — 시작부터 모두가 '뒤바뀐 상식·기억'을 진짜로 믿는다. ★필수산출★ ①details에 '진짜 현실 vs 모두가 믿는 거짓' ②거짓을 처음엔 당연한 듯 서술하고 작은 균열을 흘려 스스로 깨닫게 ③collapse=거짓을 깨고 진짜를 복원.
A|RULE|금지워드형 — 특정 단어를 입에 올리거나 정체를 깨닫는 순간 표적. ★필수산출★ ①entity.forbidden_word를 그 괴담 특유의 명사·이름으로 반드시 채움('없음·있음·맞다' 등 일상 문법어 금지=오발) ②무엇이 금지어인지 모른 채 피하는 긴장(시스템이 입력 감지).
A|RULE|환경 침식형(현실 변질) — 공간의 물리적 현실 자체가 변질된다(중력·거리·방향·시간, 생물 법칙, 공간 구조, 물질). ★필수산출★ ①details에 '현실의 무엇이 어떻게 변질됐는가'(개념 아닌 ★세계의 물성★ — 자해는 한 예일 뿐)와 잠식 진행 ②collapse=근원 정상화 또는 이탈. 인과역전형(물성 멀쩡·의미효과 반대)과 구분.
A|RULE|소문 실체화형 — 본래 백지·나약한 영체가 관찰·소문·집단 인지로 치명적 실체가 된다(정보격리형의 반대: 알수록·말할수록 강해짐). ★필수산출★ ①무엇이 어떻게 투영돼 완성되는가 ②collapse=인지·소문의 고리를 끊어 무로. 언급·관찰·공포할수록 키우고 무시·부정으로 약화.
A|RULE|역할극 강제형 — 닫힌 시공간의 참여자에게 배역이 부여되고 극본에 순응해 연기해야 진실·해피엔딩에 닿는다. ★필수산출★ ①details에 극본의 큰 흐름과 '배역을 벗어나면 무슨 일이 나는가' ②collapse=극을 완수하거나 극의 모순을 짚어 막을 내림. 어설피 깨면 파국.
A|RULE|메타-법칙 조작형 — 플레이어 측이 능력·장르 클리셰·시스템 맹점을 역이용해 세계 법칙 자체를 덮어쓸 여지. ★필수산출★ ①정공법이 막혀도 통하는 '메타적 비틀기'의 방향을 loophole·exploit_path에(절차 X, 방향만) ②논리가 성립하는 메타 해법이면 인정.
A|RULE|인과 역전형(개념 변질) — 인과·개념의 관계가 거꾸로 작동한다(치료가 독·출혈이 은신·밝음이 치명·도움이 해). ★필수산출★ ①details에 '무엇의 인과가 뒤집혔는가' 2~4개(★물리 세계는 멀쩡·의미효과만 반대★ — 환경침식형=물성 변질과 구분) ②초반에 상식대로 하면 당하게 해 깨닫게 ③collapse=뒤집힌 인과를 역이용해 근원 무력화. 살려고 의도적 자해·실패를 계획.
A|RULE|전원 괴이형 — 플레이어 외 모든 NPC·대상이 괴이(진짜 우호 NPC 없음). ★필수산출★ ①relationships·npc를 '사람인 척' 위장으로 ②처음엔 사람처럼 굴다 역할극 끝 무렵 정체를 드러내며 게임오버로 치닫음. 누가 사람인지 의심하는 긴장(단 플레이어 본인은 진짜).
A|RULE|비(非)지구 무대형 — 무대가 지구 일상이 아니다(우주·이세계·판타지·지옥·사후·뒷세계 등). ★필수산출★ ①constraints.era·setting에 무대 명시 ②사물·언어·물리를 그 세계에 맞게. 일상 도입은 짧게·생략하고 바로 이질적 규칙으로 압박해도 됨.
A|RULE|함정 지시형 — '반드시 해야 할 것처럼' 써둔 행동이 실은 함정(따르면 클리어 불가). ★필수산출★ ①'지시가 수상하다'를 처음엔 안 들키게 흘리는 단서 ②loophole=지시의 모순을 짚어 따르지 않기. 진짜 길은 지시를 의심하고 거스르기.
A|RULE|진영 대립형(NPC팀 vs 플레이어팀) — NPC 세력과 플레이어가 목적 충돌로 대립한다. ★필수산출★ ①NPC팀의 우위(수·정보·지형)와 충돌하는 목적 ②npc role_type을 적대자·집단세력 등으로·관계를 적대로. 협상·기만·전투·이간이 플레이.
A|RULE|수수께끼·추리형(바다거북수프형) — 기괴한 상황의 숨은 진상을 질문·관찰·연역으로 푼다. ★필수산출★ ①details에 '겉보기 상황 vs 숨은 진상'과 진상에 닿는 단서 사슬 ②loophole=올바른 해석에 이르는 결정적 질문·관찰 ③collapse=진상을 꿰뚫어 그에 맞게 행동. 정답은 반직관적·잘못된 해석대로 행동하면 파국.
A|RULE|감정 금기형 — 특정 감정의 표출·자극이 괴담을 부른다(공포·분노·슬픔·애정 등 지정). ★필수산출★ ①details에 '어떤 감정이 트리거이고 무엇이 그것을 자극하는가'(특히 NPC 상호작용으로 울리기·겁주기·분노케 하기·본인 동요) ②collapse=감정 근원 해소 또는 끝까지 평정 유지. 금기행동형(행동 절제)과 구분=감정 절제.
B|RULE|감각 동기화형 — 참여 배역들의 감각·고통·기억이 강제로 이어진다(A가 다치면 멀리의 B도 피). ★필수산출★ ①details에 '무엇이 어디까지 공유되는가' ②한쪽의 피해·발견을 연결된 쪽에 일관되게 전파(서술+피해로). 극단적 협력·정보전파가 핵심.
B|RULE|신체 기생·교환형 — 입장과 동시에 괴담 일부가 몸에 기생하거나 시점마다 배역이 뒤바뀐다. ★필수산출★ ①기생체가 주는 유용한 힘(어둠 시야·괴물 감지) ②details에 '이득과 잠식 진행'('언제까지 쓰고 잘라낼까' 딜레마). 교환 부분은 서술로 근사.
B|RULE|사후 정보형(죽거나 끝나야 알게 됨) — 실질 해결법이 위험한 선택·사망·타임라인 종료 뒤에야 드러난다. ★필수산출★ ①언제(사망 시 진상·클리어 시점 넘긴 뒤) 무엇이 풀리는가 ②재도전·다회차 연계로 한 번 죽어본 지식이 다음 판을 쉽게(관측자·세이브포인트 NPC와 궁합).
B|RULE|시간·인과율형(루프) — 특정 시간대가 반복되거나 시간선이 엉킨다(지식은 남음). ★필수산출★ ①details에 '무엇이 반복되고 무엇을 바꿔야 끊기는가' ②collapse=루프의 고리를 끊는 변화 달성. 시한의식형(카운트다운)과 구분=비선형·반복. 엔진 근사=시계·재도전·세이브포인트로 반복 연출+지식 이월, 완전한 상태 리셋은 약속 금지.
B|RULE|기억·망각형 — 시간·조건마다 기억이 실제로 지워진다(괴담·규칙·서로의 정체·직전 사건). ★필수산출★ ①details에 '무엇이 언제 지워지는가'와 망각을 버티는 수단(기록·표식) ②collapse=기억·기록을 지켜 진상을 끝까지 보존. 인지·정신형(지각 속임·기억은 멀쩡)과 구분=기억 실제 삭제. 엔진 근사=일부 정보를 GM이 '잊음' 처리, 기록된 단서만 신뢰.
C|RULE|공간 왜곡형(비유클리드·백룸) — 방과 방의 연결이 고정되지 않고 바뀐다(지나온 문으로 돌아가도 원래 자리가 아님). ★필수산출★ ①공간을 '고정'해 탈출하는 수단(표식·특정 패턴). 엔진 근사=지도·이동이 고정 연결이라 실제 방 재배치 대신 '방향감각 붕괴·길 잃음' 연출로 축소.
C|RULE|차원 분리 협력형 — 절반은 현실·절반은 이면으로 찢겨 시작(못 만나고 무전·매개체로만 소통, 한쪽 행동이 다른쪽 세계를 바꿈). ★필수산출★ ①양쪽이 주고받는 비대칭 정보·단서. 엔진 근사=레이어 시스템 없으니 무전 너머 단서 교환·비대칭 퍼즐 중심으로 축소.
C|RULE|천년 운영형 — 메인 사건엔 시간이 천천히·끝나면 천년 단위로 가속(장기 서사를 쌓아 후반을 그 축적으로 클리어). ★필수산출★ ①후반에 회수할 장기 축적과 대처 실패 시 괴담이 쌓는 힘. 엔진 근사=메타 진행 레이어 없으니 TIME_SKIP 대도약+요약 서술로 축소.
A|OPENER|위기 즉시 시작형 — 일상 파트를 건너뛰고 시작부터 죽을 위기에 몰린 채 출발. ★필수산출★ ①daily 파트를 최소화·생략하고 첫 장면을 곧장 위기로. 도입의 평온 없이 긴박.
B|OPENER|봉인 해방형 — 한 명을 제외한 플레이어가 봉인된 채 시작(갇힘·잠듦·NPC 안에 봉인). ★필수산출★ ①봉인 위치·해제 조건을 단서로 ②자유로운 플레이어가 풀어줘야 동료 합류(초반 1인 → 점진 합류).
B|OPENER|가짜 동료형 — 시작 시 곁의 동료가 가짜 NPC일 수 있다(플레이어처럼 상호작용·같은 통신 방식). ★필수산출★ ①가짜의 위화감을 흘리는 단서 ②진짜 나·동료는 어디 갇혔는가와 되찾는 길. 누가 진짜인지가 핵심.
C|OPENER|릴레이 계주형(멀티 전용) — 플레이어마다 타임라인이 다르고 생존 시간이 정해져 바통을 넘긴다. ★필수산출★ ①짧은 소통 창과 교대 순서(둘 이상 함께면 몇 턴 안에 이전 주자 퇴장). 엔진 근사=공유 타임라인 1개뿐이라 '교대·시한부 생존'을 서술로 근사, 강제 사망 타이머는 약속 금지.
""";

    private static final String ROLES_DATA = """
A|ROLE|발생원: 괴담을 일으키거나 유지. 제거하면 괴담 소멸 가능. (반복되면 공식이 되니 남발 금지)
A|ROLE|방어막: 오히려 괴담을 억제 중. 제거하면 괴담이 폭주·강화(반전). 사전에 알 수 없어야 함.
A|ROLE|제물: 제거하면 오히려 괴담 조건이 충족됨. 살려야 막을 수 있는 역설.
A|ROLE|열쇠: 해결에 필요한 정보/아이템을 유일하게 보유. 죽이면 퍼펙트 경로가 막힘(죽이기 전 정보 확보 필수).
A|ROLE|피해자: 이미 잠식된 존재. 제거해도 본질은 남음(일반 클리어만 가능).
A|ROLE|무관: 괴담과 무관. 불필요한 제거는 평가 하락만.
A|ROLE|적대적공조: 괴담 생태계의 ★위험한 적대 개체★지만 주체(발생원)에게 억압당하거나 원한이 있다 — 설득·교섭하면 발생원에 맞서 공조 가능(배신·폭주 위험 상존). true_role에 '무엇에 원한이 있고 어떤 조건에서 협력하는가'.
A|ROLE|시스템부품: 자아·원한이 ★완전히 소멸★한 채 규칙만 집행하는 ★맹목적 톱니★. 설득·교섭 불가, 규칙대로만 반응(communicable:false 권장). 막으려면 규칙·무대 자체를 흔들어야 한다.
A|ROLE|잘못된가이드: ★옳은 길을 알려주는 척★ 신뢰를 얻은 뒤 잘못된 길·거짓을 섞어 ★괴담 폭주·붕괴★를 유도하는 기만자. true_role에 '진짜 의도와 어떤 거짓을 어떻게 섞는가'. 단서로 모순을 짚어야 간파(처음부터 들키면 안 됨).
A|ROLE|맹신자: 이 공간의 ★미친 규칙을 섭리·종교로 숭배★하는 인간. 규칙을 깨려는 플레이어를 물리적·논리적으로 필사 방해('괴물보다 사람이 무섭다'). true_role에 '무엇을 숭배하고 어디까지 방해하는가'.
A|ROLE|다른괴담소환자: 몸에 ★또 다른 괴담이 봉인★돼 불러내려 한다. 도움이 될 때도 있으나 대개 ★더 큰 사건★을 일으킨다. true_role에 '무엇이 봉인됐고 풀리면 무슨 일이 나는가'.
A|ROLE|빙의해결사: 플레이어처럼 ★사건을 해결하려 빙의한★ 존재(강력한 능력, 사건을 알아가는 중). 미치거나 트롤링하면 까다로워진다. 높은 확률로 자신이 NPC임을 안다. true_role에 능력·목적.
A|ROLE|집단세력: ★여럿이 늘 함께 다니는★ 무리(숫자의 폭력). 흩어져 각각 일을 처리할 수 있다(한 AI가 다수를 동시 조종). 적대·중립·조력 어느 쪽도 가능.
A|ROLE|외부세력: 괴담과 무관한 ★완전히 다른 세력★(강도단·요원·사이비 등). 자기 목적으로 움직이며 별개의 사건을 일으킨다.
A|ROLE|수상한자: 정보를 쏟아내지만 ★도움이 안 되는★ 인물 — 대개 '완전히 다른 괴담'의 정보(오도·소음원). 진짜 단서와 섞여 혼란을 준다.
A|ROLE|적대자: 특별한 반전 없이 ★그냥 플레이어와 대적★하는 인간(목적 충돌·악의). 진영 대립형과 궁합.
A|ROLE|메인열쇠: ★이 인물만이 괴담을 해결할 수 있고★ 플레이어 단독으론 불가. 지키고·설득하고·해결 시점까지 살려내는 게 플레이.
A|ROLE|괴담이사랑하는자: 괴담이 ★집착·애착하는 대상★. 죽으면 괴담이 크게 폭주, 살아 있으면 오히려 억제·달랜다. 누군지 모른 채 다루는 긴장.
A|ROLE|쾌락주의자: 항상 ★사건을 더 키우려★ 든다(불을 지르고 즐긴다). 안정되려는 흐름을 일부러 망친다. true_role에 무엇으로 판을 키우는가.
A|ROLE|백과사전: 사건의 ★모든 정보를 아는★ 인물. 단 통째로 주지 않게 — 대가·조건·신뢰를 요구하거나 일부만 흘리게(열쇠와 구분: 전지하나 협조가 까다롭다).
A|ROLE|조종자: 뒤에서 ★플레이어나 괴담을 조종해 별개 목적★을 이루려는 배후. 표면적으론 조력자·방관자로 위장. true_role에 진짜 목적·조종 수단.
A|ROLE|약화된열쇠: 봉인·제약으로 ★힘이 묶인★ NPC. 제약을 풀어주면 ★초월적 힘으로 강력히 지원★. 제약 해제 조건을 단서로.
A|ROLE|도청자: 플레이어·괴담의 ★대화 내역을 모두 엿보는★ 존재. 그 정보로 무엇을 할지 모른다(협박·거래·폭주 유발 등). true_role에 의도.
A|ROLE|중재자: 누구의 편도 아니다 — 한쪽이 ★과하게 불리해지면 나타나 약자(괴담이든 플레이어든)를 돕는다★(균형추). 양쪽 모두에게 변수.
B|ROLE|오염매개체: 플레이어에게 ★실질적 도움(단서·회복·아이템)을 주는 우호적 인물★이지만, 곁에 오래 머물거나 자주 소통할수록 ★플레이어가 더 빨리 잠식★된다. 본인은 모른다(선량할수록 비극). '버리면 생존이 어렵고 데려가면 잠식'의 딜레마. true_role에 '어떤 접촉이 얼마나 잠식을 가속하는가'.
B|ROLE|거울자아: 특정 플레이어의 ★잃어버린 기억·죄책감·생명력이 형상화★된 인물. 다치거나 죽으면 ★연결된 플레이어도 같은 피해·확정 사망★. 방패막이·방치를 막는 핸디캡 — 제 목숨처럼 지켜야 한다. true_role에 '누구와 이어졌고 무엇이 공유되는가'.
B|ROLE|관측자기록관: 사건 내내 플레이어를 ★조용히 기록만★ 한다(대화는 되나 개입 안 함). 죽이거나 기록을 빼앗으면 ★타임라인·인과 붕괴(치명 패널티)★. 끝까지 살려두면 실패·재도전 시 ★이전 회차 핵심 단서를 넘겨주는 세이브포인트★.
""";

    private static final List<Arch> RULES = parse(RULES_DATA);
    private static final List<Arch> ROLES = parse(ROLES_DATA);

    private static List<Arch> parse(String data) {
        List<Arch> out = new ArrayList<>();
        for (String line : data.split("\n")) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            String[] p = s.split("\\|", 3);
            if (p.length < 3) continue;
            try {
                out.add(new Arch(Tier.valueOf(p[0].trim()), Slot.valueOf(p[1].trim()), p[2].trim()));
            } catch (IllegalArgumentException ignore) { /* 잘못된 줄은 건너뜀 */ }
        }
        return out;
    }

    /** 어려운 효과(B·C 티어)와 변형 오프너는 ★스테이지 3부터★ 등장. 1~2는 기본(A·RULE)만. */
    private static boolean basicOnly(int stage) { return stage <= 2; }

    public static String worldRulesBlock(int stage) { return worldRulesBlock(stage, ""); }

    /**
     * 세계 규칙 후보 블록.
     * 1~2스테이지: 기본 규칙(A티어·RULE)만 5개. 3+스테이지: A/B 5개 + (40%)C 1개 + (50%)오프너 1개.
     * typeHint(운영 설정 유형 고정)가 있으면 그 유형을 ★최우선 강제★하고 매칭 아키타입을 후보 맨 앞으로
     * 끌어온다 — /trpg s s 에서 고른 유형이 괴담 '성격'이 아니라 ★world_rules 구조★로 반영되게 한다.
     * ('성격:' 힌트는 world_rules 유형이 아니므로 여기선 무시하고 컨셉 단계에서만 성격에 반영.)
     */
    public static String worldRulesBlock(int stage, String typeHint) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean basic = basicOnly(stage);
        List<Arch> ab = new ArrayList<>(), c = new ArrayList<>(), openers = new ArrayList<>();
        for (Arch a : RULES) {
            if (basic && (a.tier() != Tier.A || a.slot() != Slot.RULE)) continue; // 초반: 어려운 효과·오프너 제외
            if (a.slot() == Slot.OPENER) openers.add(a);
            else if (a.tier() == Tier.C) c.add(a);
            else ab.add(a);
        }
        Collections.shuffle(ab, r); Collections.shuffle(c, r); Collections.shuffle(openers, r);
        List<Arch> pick = new ArrayList<>();
        for (int i = 0; i < Math.min(5, ab.size()); i++) pick.add(ab.get(i));
        if (!basic && !c.isEmpty() && r.nextInt(100) < 40) pick.add(c.get(0));
        if (!basic && !openers.isEmpty() && r.nextInt(100) < 50) pick.add(openers.get(0));
        Collections.shuffle(pick, r);

        // 운영 설정 유형 고정 — '성격:'(괴담 성격 지정)은 world_rules 유형이 아니므로 제외.
        String hint = typeHint == null ? "" : typeHint.trim();
        if (!hint.isEmpty() && !hint.startsWith("성격:")) {
            Arch match = findArchByHint(hint);
            if (match != null) { pick.remove(match); pick.add(0, match); } // 매칭 아키타입을 맨 앞으로(없으면 창작)
            return format("★유형 고정(운영 설정 — 최우선): 이번 회차 world_rules는 반드시 ★" + hint
                + "★ 유형으로 설계하라 — 아래 후보 중 이 유형에 맞는 것을 고르거나 이 유형으로 직접 창작하고, 다른 유형으로 새지 마라", pick);
        }

        String header = "세계관 규칙(world_rules) 후보 — 이 중 ★성격이 다른 것을 고르거나★ 같은 결의 새 유형을 창작하라(직전 방과 다른 유형으로)"
            + (basic ? " · 초반 스테이지라 ★기본 규칙★ 위주(복잡한 변칙은 다음 스테이지부터)" : "");
        String block = format(header, pick);
        if (stage >= 2 && r.nextInt(100) < 25) {
            block += "★ 규칙 반전(이번 회차): 고른·창안한 규칙의 ★핵심 조건을 반전★시켜 설계하라 — 예: 정보격리형 '알면 위험'→'모르면 위험', 금기행동형 '하면 위험'→'안 하면 위험', 비개입형 '두면 해결'→'개입해야 해결'. 단 ①반전형도 풀 수 있게 loophole·collapse·단서 성립 ②인과역전형엔 적용 말 것(이미 반전) ③반전이 어색한 유형은 정상형 유지 ④친숙(실존) 괴담 모드면 원전 충실로 반전하지 마라.\n";
        }
        return block;
    }

    /** typeHint(운영 설정)와 가장 잘 맞는 RULE 아키타입 1개 — 괄호 안 세부어 우선, 없으면 앞머리 토큰 매칭. */
    private static Arch findArchByHint(String hint) {
        String kw = hintKeyword(hint);
        if (kw.isEmpty()) return null;
        String[] toks = kw.split("[\\s·]+");
        for (Arch a : RULES) {
            if (a.slot() != Slot.RULE) continue;
            String t = a.text();
            for (String tk : toks) if (tk.length() >= 2 && t.contains(tk)) return a;
        }
        return null;
    }

    /** 유형 힌트에서 핵심어 추출 — "규칙·금기형(감정 금기) — …" → "감정 금기"(괄호 우선), 없으면 "—/-" 앞 앞머리. */
    private static String hintKeyword(String hint) {
        int p = hint.indexOf('(');
        if (p >= 0) { int q = hint.indexOf(')', p); if (q > p) return hint.substring(p + 1, q).trim(); }
        int d = hint.indexOf('—'); if (d < 0) d = hint.indexOf('-');
        String head = (d > 0 ? hint.substring(0, d) : hint).trim();
        return head.replace("형", "").trim();
    }

    /** NPC 역할 후보 블록: 6개 샘플링. 1~2스테이지는 기본(A티어) 역할만. */
    public static String rolesBlock(int stage) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean basic = basicOnly(stage);
        List<Arch> list = new ArrayList<>();
        for (Arch a : ROLES) { if (basic && a.tier() != Tier.A) continue; list.add(a); }
        Collections.shuffle(list, r);
        List<Arch> pick = new ArrayList<>(list.subList(0, Math.min(6, list.size())));
        return format("NPC 역할(role_type) 후보 — 한 방에 ★서로 다른 역할★을 섞어라(전부 적대/전부 조력 금지). 진짜 역할은 처음부터 드러내지 마라. ★대부분 NPC는 역할 1개 — 단 ★일부만★ 역할 2개 이상 조합(전원 아님·가끔). 조합 시 role_type에 둘 다 표기(예: '방어막&쾌락주의자' / '방어막+열쇠'=살려야 하고 정보도 얻어야 하는 딜레마)", pick);
    }

    private static String format(String header, List<Arch> picks) {
        StringBuilder sb = new StringBuilder("\n## ").append(header).append(" ★\n");
        boolean hasB = false, hasC = false;
        for (Arch a : picks) {
            String mark = a.tier() == Tier.B ? "[경량] " : a.tier() == Tier.C ? "[대형] " : "";
            sb.append("- ").append(mark).append(a.text()).append("\n");
            if (a.tier() == Tier.B) hasB = true;
            else if (a.tier() == Tier.C) hasC = true;
        }
        if (hasB) sb.append("※[경량] 유형은 교차 플레이어·지속 상태가 필요하다 — 지금은 GM 서술과 기존 효과(피해·오염·WITNESS·상태)로 ★일관되게★ 구현하라(엔진 자동 강제는 아직 없으니 그 범위에서만 약속).\n");
        if (hasC) sb.append("※[대형] 유형은 엔진이 완전히 못 받친다 — ★서술 가능한 범위로 축소★해 구현하고, 실제 방 재배치·플레이어 강제 스왑·플레이어별 독립 타임라인처럼 ★지킬 수 없는 약속은 하지 마라★.\n");
        return sb.toString();
    }
}
