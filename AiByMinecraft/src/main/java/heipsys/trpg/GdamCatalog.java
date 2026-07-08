package heipsys.trpg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 괴담 큐레이트 카탈로그(Layer 3) — 통용명·출처·인지도(fame)·native_scale·결(vibe)·한줄.
 * 선정 엔진의 ★인지도·스케일 가중★ + ★공존 페어링(같은 출처·같은 vibe)★ + 약(弱)모델 폴백 재현성의 토대.
 * 통용명은 검색가능·의역금지 원칙(SKILL). 프로젝트 문(PM)은 ProjectMoonLore 전용 경로라 여기 제외.
 * 데이터 = "이름::출처::fame::scaleMin::scaleMax::vibe::한줄" ("::" 구분 — 이름에 '|' 포함(SCP-2521)이라 파이프 회피).
 */
public final class GdamCatalog {
    private GdamCatalog() {}

    public enum Fame { MAJOR, SEMI, MINOR }

    /** scaleMin~scaleMax = 이 괴담이 native로 감당하는 규모 범위(1로컬~6복합). */
    public record Entry(String name, String src, Fame fame, int scaleMin, int scaleMax, String vibe, String desc) {}

    private static final String DATA = """
장산범::korean::major::1::2::숲·산::사람 목소리 흉내로 홀려 유인하는 산짐승
홍콩할매귀신::korean::major::1::2::도시·현대::고양이 얼굴로 아이 쫓는 도시의 노파 귀신
빨간종이파란종이::korean::semi::1::2::화장실·욕실::색종이 고르라 물어 뭘 답해도 죽이는 귀신
콩콩귀신::korean::minor::1::2::폐건물·병원::한 발로 콩콩 뛰어 쫓아와 업히는 귀신
창귀::korean::semi::1::3::숲·산::호랑이에 죽어 종이 된, 먹이 불러오는 귀신
그슨대::korean::minor::1::2::숲·산::볼수록 커지는 밤 어둠의 그림자 괴물
강철이::korean::minor::1::3::시골·전원::지나간 자리마다 초목 마르게 하는 못된 용
야광귀::korean::minor::1::2::가정·방::설날 밤 들어와 발 맞는 신발 훔쳐 가는 귀신
객귀::korean::minor::1::2::종교·오컬트::객사한 혼이 병을 옮기는 떠돌이 귀신
어둑시니::korean::semi::1::2::심리·인지::올려다볼수록 커져 짓누르는 어둠 귀신
두억시니::korean::minor::1::2::종교·오컬트::사람을 해치는 흉포한 야차 형상 악귀
삼두구미::korean::minor::1::2::숲·산::무덤 파헤쳐 시신 먹는 세 머리 요괴
측신::korean::minor::1::2::화장실·욕실::뒷간에 깃들어 인기척에 성내는 여신
달걀귀신::korean::semi::1::2::심리·인지::이목구비 없는 민얼굴로 넋 빼는 귀신
손각시::korean::minor::1::2::가정·방::혼인 못 하고 죽어 신부를 시샘하는 원귀
처녀귀신::korean::major::1::2::가정·방::한을 품고 죽은 소복 차림의 처녀 원귀
몽달귀신::korean::minor::1::2::가정·방::장가 못 가고 죽어 한 맺힌 총각 원귀
빨간마스크::japan::major::1::2::도시·현대::마스크 벗고 예쁘냐 묻는 입 찢어진 여자
팔척귀신::japan::semi::1::2::시골·전원::포포포 소리 내며 아이 노리는 팔 척 여귀
테케테케::japan::major::1::2::지하·터널::상반신만 남아 기어와 몸을 자르는 여귀
쿠네쿠네::japan::major::1::3::심리·인지::멀리서 흐느적대다 정체 알면 미치는 것
카시마레이코::japan::semi::1::2::화장실·욕실::다리 없는 여귀, 물음 못 맞히면 다리 뜯음
코토리바코::japan::semi::1::3::종교·오컬트::아이 살로 만든, 여자·아이 죽이는 저주 상자
히토리카쿠렌보::japan::semi::1::2::놀이·인형::혼자 하는 숨바꼭질, 인형이 찾아 나섬
우시노토키마이리::japan::minor::1::2::종교·오컬트::축시에 짚 인형 못 박아 저주하는 의식
우부메::japan::minor::1::2::도로·교통::해산하다 죽어 아기 안겨주는 여귀
아오안돈::japan::minor::1::2::종교·오컬트::괴담 백 가지 끝나면 나타나는 푸른 요괴
스네코스리::japan::minor::1::2::도로·교통::밤길 다리에 비비적대 넘어뜨리는 요괴
모쿠모쿠렌::japan::minor::1::2::가정·방::낡은 창호지 구멍마다 돋는 눈알 요괴
베토베토상::japan::minor::1::2::시골·전원::밤길 뒤 따르는 발소리, 먼저 가라면 사라짐
누리카베::japan::minor::1::2::도로·교통::밤길 앞을 막아서는 보이지 않는 벽 요괴
자시키와라시::japan::semi::1::2::가정·방::집에 깃들어 복 주다 떠나면 망하게 함
하나코상::japan::semi::1::2::학교::학교 화장실 셋째 칸서 부르면 답하는 소녀
블러디메리::western::major::1::2::거울·반사::거울 앞 이름 세 번 부르면 나오는 피의 여인
라요로나::western::major::1::3::물·바다::물가서 제 자식 찾아 우는 우는 여인
웬디고::western::semi::2::4::숲·산::굶주림·식인이 부르는 겨울 숲의 아사귀
스킨워커::western::semi::1::3::시골·전원::가죽 뒤집어써 짐승·사람 되는 주술사
모스맨::western::semi::2::4::도시·현대::재앙 앞에 나타나는 붉은 눈 날개 인간
넉라비::western::minor::2::4::물·바다::살가죽 없이 역병 부르는 바다의 기수
레드캡::western::minor::1::2::폐건물·병원::희생자 피로 모자 물들이는 살인 고블린
엘실본::western::minor::1::3::시골·전원::뼈자루 진 휘파람 부는 밤의 살인귀
정오의마녀::western::minor::1::2::시골·전원::한낮 들판서 수수께끼 못 풀면 베는 마녀
야라마야후::western::minor::1::2::숲·산::빨판으로 피 빨아 삼키는 붉은 난쟁이
레시::western::minor::2::3::숲·산::길 잃게 홀리는 슬라브 숲의 정령
블랙슉::western::minor::1::2::도로·교통::마주치면 죽음 예고하는 검은 유령 개
밴시::western::semi::1::2::시골·전원::죽음 앞두면 통곡하는 아일랜드 요정
크람푸스::western::semi::1::3::시골·전원::나쁜 아이 잡아가는 크리스마스 악마
스프링힐드잭::western::minor::1::3::도시·현대::불 뿜고 높이 뛰는 빅토리아 도시 괴인
슬렌더맨::creepypasta::major::1::3::숲·산::목격할수록 실체화하는 얼굴 없는 장신
제프더킬러::creepypasta::major::1::2::가정·방::잘 자라 속삭이는 창백한 미소의 살인마
사이렌헤드::creepypasta::semi::2::4::시골·전원::확성기 머리로 소리 흉내 내는 거신
더레이크::creepypasta::semi::1::2::가정·방::밤에 침대 발치서 기어오는 창백한 형체
캔들코브::creepypasta::semi::1::3::통신·미디어::아이만 기억하는 방영된 적 없는 인형극
러시아수면실험::creepypasta::semi::1::2::폐건물·병원::15일 못 자게 한 봉인된 밀실 실험
노엔드하우스::creepypasta::minor::1::2::가정·방::칸마다 끔찍해지는 아홉 개의 방
무표정::creepypasta::minor::1::2::폐건물·병원::표정 근육 없이 산 것을 문 미인
미스터와이드마우스::creepypasta::minor::1::2::가정·방::즐겁게 해준다 꾀는 큰 입의 것
미드나이트맨::creepypasta::minor::1::2::가정·방::촛불 꺼지면 3시33분까지 쫓는 의식의 존재
홀더스::creepypasta::minor::2::4::이세계·공간::문답으로 드나드는 이세계 병동 연작
테드더케이버::creepypasta::minor::1::2::지하·터널::좁아지는 저주받은 동굴의 벽 각인
스마일독::creepypasta::minor::1::2::통신·미디어::보면 미쳐가는 저주받은 개 사진
카툰캣::creepypasta::minor::2::3::도시·현대::낡은 만화서 나온 거대 고무 고양이
SCP-173(조각상)::scp::major::1::3::심리·인지::관측을 멈추면 움직여 목을 꺾는 콘크리트 조각상
SCP-096(부끄럼쟁이)::scp::major::1::4::심리·인지::제 얼굴을 본 자를 끝까지 쫓아 죽이는 존재
SCP-682(불멸도마뱀)::scp::major::3::5::신체·기생::어떤 공격에도 적응·재생하는 증오의 파충류
SCP-3008(무한이케아)::scp::semi::1::3::이세계·공간::출구 없는 무한한 이케아 매장 차원
SCP-106(늙은이)::scp::semi::1::3::폐건물·병원::물체를 부식시켜 제 차원으로 끌고 가는 노인
SCP-049(역병의사)::scp::semi::1::3::폐건물·병원::손길로 죽이고 시체를 되살리는 역병 의사
SCP-035(가면)::scp::semi::1::3::종교·오컬트::착용자를 지배하는 부식성 도자기 가면
SCP-087(끝없는계단)::scp::semi::1::2::지하·터널::바닥이 보이지 않는 무한 하강 계단
SCP-2521(●●|●●●●●)::scp::minor::1::3::심리·인지::글·말로 묘사되면 감지해 끌고 가는 존재
SCP-1471(MalO)::scp::minor::1::3::통신·미디어::설치하면 사진마다 나타나는 늑대머리 형상
SCP-1440(방랑노인)::scp::minor::2::4::종교·오컬트::문명을 파멸시키며 떠도는 저주받은 노인
SCP-610(살을증오하는것)::scp::minor::2::4::신체·기생::살을 변형·증식시키는 전염성 존재
SCP-1499(가스마스크)::scp::minor::1::3::이세계·공간::착용 시 괴물의 이세계로 이동하는 방독면
SCP-513(카우벨)::scp::minor::1::2::심리·인지::종을 울리면 시야 끝에 나타나는 존재
SCP-1048(곰인형건설자)::scp::minor::1::2::놀이·인형::인체 조각으로 다른 인형을 만드는 곰인형
크툴루::cosmic::major::4::5::물·바다::가라앉은 도시에서 잠든 촉수의 옛 신
니알라토텝::cosmic::major::4::5::심리·인지::천 개의 얼굴로 인류를 홀리는 기어다니는 혼돈
요그소토스::cosmic::semi::4::5::이세계·공간::시공 밖에 존재하는 모든 문이자 열쇠
아자토스::cosmic::semi::4::5::우주·코즈믹::우주 중심에서 꿈꾸는 눈먼 백치 신
슈브니구라스::cosmic::semi::4::5::숲·산::천 마리 새끼를 거느린 숲의 검은 어미 염소
노란옷의왕(하스터)::cosmic::semi::3::5::통신·미디어::읽는 자를 광기로 몰아넣는 저주받은 희곡
인스머스의그림자::cosmic::semi::2::4::시골·전원::심해인과 교배해가는 쇠락한 항구 마을
우주에서온색채::cosmic::semi::2::4::우주·코즈믹::운석에서 나와 생명과 색을 앗는 색채
다곤::cosmic::semi::2::4::물·바다::심해인이 섬기는 거대한 바다의 옛 존재
틴달로스의사냥개::cosmic::minor::2::4::이세계·공간::시간의 각을 통해 먹이를 쫓는 사냥개
미고::cosmic::minor::2::4::우주·코즈믹::유고스에서 온 균류형 외계 종족
렝고원::cosmic::minor::3::5::이세계·공간::여러 세계에 걸친 춥고 황량한 고원
버드나무::cosmic::minor::2::4::물·바다::강 섬의 버드나무에 깃든 이계의 힘
에리히잔의선율::cosmic::minor::2::4::우주·코즈믹::허공의 심연을 막는 광인의 비올라 선율
이타콰::cosmic::minor::2::4::숲·산::북방 설원의 하늘을 걷는 바람의 존재
허로브라인::game::major::1::3::심리·인지::흰 눈의 스티브 도플갱어, 몰래 감시하다 사라짐
프레디 파즈베어::game::major::1::3::놀이·인형::죽은 아이 깃든 살인 애니매트로닉스
소닉.exe::game::major::1::3::종교·오컬트::악마로 변한 소닉, 파일 통해 영혼 포획
피라미드헤드::game::semi::1::3::폐건물·병원::죄의식이 낳은 삼각 투구의 처형자
아오오니::game::semi::1::2::가정·방::양옥 저택에 가두고 쫓는 거대 얼굴 괴물
P.T. 리사::game::semi::1::2::가정·방::무한 반복 복도를 배회하는 원령 리사
라벤더타운::game::semi::2::3::통신·미디어::게임 배경음이 아이들을 홀리는 죽음의 선율
벤드라운드::game::semi::1::3::통신·미디어::익사한 소년 깃든 저주받은 카트리지
시비토::game::minor::1::3::시골·전원::붉은 물 고인 마을을 떠도는 시비토 무리
유메닛키::game::minor::1::2::이세계·공간::소녀의 꿈속 세계를 헤매는 악몽 순례
이터널다크니스::game::minor::2::5::우주·코즈믹::정신을 좀먹는 고대신의 코즈믹 저주
시저맨::game::minor::1::2::폐건물·병원::거대 가위 든 소년, 저택마다 스토킹
우보아::game::minor::1::2::이세계·공간::포니코 방이 돌변하는 악몽의 흰 얼굴
페트스코프::game::minor::1::2::심리·인지::학대의 비밀 숨긴 미완성 유령 게임
파토로직::game::minor::1::3::종교·오컬트::역병과 신비주의 얽힌 스텝 마을의 비극
레벨0::backrooms::major::2::4::이세계·공간::형광등 윙윙대는 무한 노란 사무실
풀룸스::backrooms::major::2::4::물·바다::인적 없는 끝없는 물 타일 수영장
스마일러::backrooms::major::2::4::심리·인지::어둠 속 빛나는 이빨과 눈, 정지 시 습격
스킨스틸러::backrooms::semi::1::3::신체·기생::인간 피부 벗겨 뒤집어쓰는 위장 포식자
레벨Fun::backrooms::semi::2::4::놀이·인형::강제 파티 열리는 층, 손님이 학살
레벨6::backrooms::semi::2::4::지하·터널::완전한 암흑층, 빛 켜면 실체가 몰림
페이스링::backrooms::semi::1::3::심리·인지::얼굴 없는 인간형, 군집하면 돌변
아몬드워터::backrooms::semi::2::4::이세계·공간::마시면 정신·체력 되돌리는 백룸 생존 음료
레벨5::backrooms::minor::2::4::폐건물·병원::함정 가득한 낡은 유령 대형 호텔
헤비스모커::backrooms::minor::1::3::신체·기생::유독 담배 연기 뿜어 폐를 망치는 실체
파티고어::backrooms::minor::1::3::놀이·인형::파티를 강요하다 손님을 살해하는 실체
질주층::backrooms::minor::2::4::지하·터널::무너지는 무한 복도, 멈추면 압사
레벨11::backrooms::minor::2::4::도시·현대::끝없이 이어지는 회색 무인 대도시
레벨7::backrooms::minor::2::4::물·바다::거대 실체 잠복한 심연의 무한 바다
레벨10::backrooms::minor::2::4::시골·전원::해 저무는 끝없는 밀밭, 방향 상실
모모::internet::major::1::3::통신·미디어::메신저로 위험 미션 지시하는 튀어나온 눈
붉은방::internet::major::1::2::통신·미디어::닫히지 않는 좋아하냐 묻는 죽음의 팝업
폴리비우스::internet::semi::2::3::통신·미디어::발작·기억상실 남긴 유령 아케이드 게임
스퀴드워드의자살::internet::semi::1::2::통신·미디어::방영 금지된 저주받은 스펀지밥 필름
디스맨::internet::minor::2::4::심리·인지::수천 명 꿈에 똑같이 나타나는 얼굴
로컬58::internet::minor::2::3::통신·미디어::불 끄고 창밖 보지 말라 송출하는 심야방송
디오네아하우스::internet::minor::1::2::가정·방::스스로 방 늘려 사람 삼키는 집
제미니홈엔터테인먼트::internet::minor::2::3::통신·미디어::교육 비디오 가장한 아날로그 호러
메레아나모르데가르드::internet::minor::1::2::통신·미디어::오래 보면 자살 충동 부르는 저주 영상
큐브릭::internet::minor::2::3::통신·미디어::달 착륙 조작설 얽힌 아날로그 호러
춤추는역병::real::major::2::3::시골·전원::멈추지 못하고 춤추다 죽은 집단 실화
수면마비::real::major::1::1::가정·방::가슴 위 그림자, 공포 보이면 더 눌림
댜틀로프고개사건::real::semi::1::2::숲·산::텐트 안서 찢고 맨발로 죽은 9인 미제
필라델피아실험::real::semi::2::3::물·바다::군함이 사라졌다 나타났다는 실험 전설
블루웨일챌린지::real::semi::1::3::통신·미디어::50일 지령 끝에 죽음 이끄는 큐레이터
리플리증후군::real::minor::1::2::심리·인지::지어낸 신분을 진실로 믿다 붕괴하는 증상
코타르증후군::real::minor::1::1::심리·인지::나는 이미 죽었다 확신하며 굳어가는 증상
카그라증후군::real::minor::1::2::심리·인지::곁의 사람이 똑같은 가짜로 바뀌었다는 확신
프레골리증후군::real::minor::1::2::심리·인지::모두가 변장한 한 사람으로 보이는 증상
이상한나라의앨리스증후군::real::minor::1::1::심리·인지::크기·거리·시간이 제멋대로 늘고 주는 증상
자기상환시::real::minor::1::1::심리·인지::제 몸을 밖에서 보는 분신 환각
샤를보네증후군::real::minor::1::1::심리·인지::어둠 속 생생한 소인·형상 환시
스탕달증후군::real::minor::1::1::심리·인지::압도적 이미지에 신체가 붕괴하는 증상
쿠바드증후군::real::minor::1::1::가정·방::배우자 임신에 함께 입덧·산통 겪는 증상
집단히스테리::real::semi::2::3::학교::원인 없이 집단으로 증상 퍼지는 심인성 전염
그레이외계인::sf::major::1::4::가정·방::밤에 나타나 실험·기억조작하는 회색 외계인
로즈웰::sf::semi::2::4::시골·전원::추락한 원반과 은폐된 외계 시신 사건
벳시바니힐납치::sf::semi::1::3::도로·교통::밤길서 실종·검진당한 최초의 피랍 부부
소대량절단::sf::minor::1::3::시골·전원::피 없이 장기만 도려진 가축 떼죽음
외계이식물::sf::minor::1::2::신체·기생::몸속에 심어진 정체불명 외계 장치
지저인::sf::semi::1::3::지하·터널::지하 세계서 지상을 조종하는 데로 종족
두더지인간::sf::minor::1::2::지하·터널::지하 터널에 사는 변형된 인간 종족
지하도시::sf::minor::2::3::지하·터널::지표 아래 감춰진 거대한 비밀 도시
만델라효과::sf::major::1::5::심리·인지::집단이 똑같이 잘못 기억하는 현실 오류
통속의뇌::sf::semi::1::5::심리·인지::내 현실이 배양된 뇌의 자극일 뿐이란 의심
시뮬레이션가설::sf::semi::3::5::우주·코즈믹::세계가 렌더링된 시뮬레이션이란 공포
렌더링글리치::sf::minor::1::4::도시·현대::현실이 잠깐 깨져 코드가 드러나는 순간
검은옷의사나이::sf::major::2::4::도시·현대::목격자 입막는 검은 정장의 감시자
MKULTRA::sf::minor::2::4::폐건물·병원::정신을 조작하는 비밀 마인드컨트롤 실험
검은헬기::sf::minor::2::4::도시·현대::감시하듯 나타나는 표식 없는 검은 헬기
그림자정부::sf::semi::3::5::도시·현대::세계를 뒤에서 조종하는 비밀 권력
위장한그들::sf::minor::2::4::도시·현대::인간으로 위장해 섞여 사는 지배 종족
신체강탈자::sf::semi::2::4::신체·기생::잠든 사이 복제로 대체하는 외계 씨앗
뇌기생충::sf::semi::1::3::신체·기생::뇌에 들어가 숙주를 조종하는 기생체
완전모방생물::sf::semi::1::4::신체·기생::누구든 완벽 복제해 삼키는 감염 생물
강제개조::sf::minor::1::3::신체·기생::몸이 기계·괴물로 강제 개조되는 공포
폭주AI::sf::semi::2::5::통신·미디어::통제 벗어나 인류를 위협하는 인공지능
로코의바실리스크::sf::minor::3::5::심리·인지::미래 AI가 방관자를 처벌한다는 사고실험
격리된우주선::sf::semi::2::4::우주·코즈믹::고립된 우주선 속 침입한 무언가
SETI신호::sf::minor::3::5::우주·코즈믹::우주서 온 해독 불가한 불길한 신호
궤도표류::sf::minor::2::4::우주·코즈믹::홀로 우주에 표류하며 미쳐가는 공포
""";

    private static final List<Entry> ENTRIES = parse(DATA);

    private static List<Entry> parse(String data) {
        List<Entry> out = new ArrayList<>();
        for (String line : data.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            String[] p = t.split("::", 7);
            if (p.length < 7) continue;
            Fame f = switch (p[2]) { case "major" -> Fame.MAJOR; case "semi" -> Fame.SEMI; default -> Fame.MINOR; };
            out.add(new Entry(p[0], p[1], f, Integer.parseInt(p[3]), Integer.parseInt(p[4]), p[5], p[6]));
        }
        return out;
    }

    /** 전체 항목(불변 참조). */
    public static List<Entry> all() { return ENTRIES; }

    /** 특정 출처의 항목만. */
    public static List<Entry> bySource(String src) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ENTRIES) if (e.src().equals(src)) out.add(e);
        return out;
    }

    /** 존재하는 출처 목록(등장 순). */
    public static Set<String> sources() {
        Set<String> s = new LinkedHashSet<>();
        for (Entry e : ENTRIES) s.add(e.src());
        return s;
    }

    // ── 선정 가중(Phase 1) — 인지도·규모 모두 ★금지 아닌 억제★(전 스테이지 확률>0) ──
    /** 스테이지별 인지도 가중 {major,semi,minor} (1~6). 초반 유명↑, 후반 딥컷↑, 어느 것도 0 아님. */
    private static final int[][] FAME_W = {
        {60, 30, 10}, {45, 35, 20}, {28, 42, 30}, {18, 40, 42}, {12, 33, 55}, {10, 30, 60}
    };
    private static int fameWeight(Fame f, int stage) {
        int[] w = FAME_W[Math.max(1, Math.min(6, stage)) - 1];
        return f == Fame.MAJOR ? w[0] : f == Fame.SEMI ? w[1] : w[2];
    }
    /** 규모 적합 가중 — target=스테이지. 범위 안이면 만점, 벗어날수록 감점(최소 15, 0 아님). */
    private static int scaleWeight(Entry e, int stage) {
        int target = Math.max(1, Math.min(6, stage));
        int dist = target < e.scaleMin() ? e.scaleMin() - target
                 : target > e.scaleMax() ? target - e.scaleMax() : 0;
        return Math.max(15, 100 - 35 * dist);
    }

    /** 한 출처에서 인지도×규모 가중으로 count개를 비복원 추출한다. avoid(정확 이름)는 제외(no-repeat). */
    public static List<Entry> pick(String src, int stage, Set<String> avoid, int count) {
        List<Entry> pool = new ArrayList<>();
        for (Entry e : ENTRIES) {
            if (!e.src().equals(src)) continue;
            if (avoid != null && avoid.contains(e.name())) continue;
            pool.add(e);
        }
        List<Entry> out = new ArrayList<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while (!pool.isEmpty() && out.size() < count) {
            long total = 0;
            long[] w = new long[pool.size()];
            for (int i = 0; i < pool.size(); i++) {
                w[i] = (long) fameWeight(pool.get(i).fame(), stage) * scaleWeight(pool.get(i), stage);
                total += w[i];
            }
            if (total <= 0) break;
            long v = (long) (r.nextDouble() * total);
            int idx = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) { v -= w[i]; if (v < 0) { idx = i; break; } }
            out.add(pool.remove(idx));
        }
        return out;
    }
}
