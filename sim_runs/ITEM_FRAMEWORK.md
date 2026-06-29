# ITEM_FRAMEWORK.md — 아이템 구조 확장 설계

목표: 시작 시 주는 "소품"에 그치는 아이템을 **사용·상태·변형·조합**이 있는 실제 게임 요소로.
설계 철학은 특성/능력과 동일 — **Java가 기계 효과(item_type)를 정의 / AI가 이름·설명·수치·발현을 변주.**
(작성 2026-06-27. 설계만 — 코드 미변경. ABILITY_FRAMEWORK.md와 짝.)

---

## 0. 핵심 결론 — 특성과 똑같은 패턴을 아이템에 적용

특성은 `effect_type`+`effect_params`+런타임 상태(쿨다운/usedThisStage)로 "기계 효과"를 갖는데,
**아이템은 그게 전혀 없다**(그냥 `Set<String> heldItemIds`로 보유 여부만 추적).
→ 아이템에도 `item_type`+`item_params`+**런타임 상태(ItemInstance)**+**USE 프로토콜**을 부여한다.

---

## 1. 현재 구조 (소품 전용) — 토대

```
.gdam 정의
  key_items[]   {id,name,type,description,lore_info,content/pages,location,clue_value}
                type = written_book / map / paper / physical:<MATERIAL>
  common_items[]  시대 기본 소지품 ID 배열(현대=스마트폰 등)
  roles[].start_item[]  배역 시작 소지품(한국어 이름)

ItemManager (지급/회수 전용 — 순수 외형)
  processGrant(<ITEM_GRANT>) → giveItem → 책/쪽지/지도/물건 ItemStack 생성
  정보는 lore/책장(마우스오버)으로만 전달. lore_info=강조 단서.
  resolveMaterial(): 키워드→마인크래프트 머터리얼(열쇠→TRIPWIRE_HOOK, 손전등→LANTERN …)
  chapter_bound PDC → 챕터 종료 시 reclaimChapterItems로 회수

런타임
  PlayerData.heldItemIds : Set<String>   ← ★보유 여부만. 상태 없음.
  item_remove(업데이트) → heldItemIds.remove (이진 제거뿐)
  ★유일한 기계 효과: hasCommDevice() — heldItemIds에 전화/무전 키워드 있으면 원격통화 허용
```

**즉, 아이템으로 할 수 있는 일이 사실상 0.** "열쇠로 문 연다/손전등 켠다/총 쏜다/약 먹는다"가 전부 GM 서술 즉흥이고, 상태(배터리·탄·소모·고장)가 없어 변형이 불가능하다.

---

## 2. 부족한 4축

| 축 | 현재 | 필요 |
|---|---|---|
| ① 사용(USE) | 구조화된 사용 행위 없음(서술 즉흥) | **`<ITEM_USE>` 프로토콜** — 사용→기계 결과 |
| ② 상태(state) | `Set<String>` 보유여부뿐 | **ItemInstance** — charges/on-off/broken/소모 |
| ③ 기계 효과(item_type) | 없음(hasCommDevice 키워드뿐) | **프리미티브** — key/light/weapon/consumable… |
| ④ 조합·변형 | 없음 | **combine/component** — 부품→신규 아이템 |

---

## 3. 축③ item_type 프리미티브 (소설·호러 TRPG 수요 → 키)

`(key, active, whatItDoes, paramHint)` — SystemTraitRegistry.Effect와 동일한 형태로 `ItemEffectRegistry`에 둔다.

### 사용형 (USE로 발동)
| item_type | 효과 | 파라미터 | 비고 |
|---|---|---|---|
| `key` | 특정 gated_zone 잠금 해제(requires 충족) | `unlocks`(zone id), `consumed`(0/1) | 기존 gated_zones와 직결 |
| `tool` | 특정 행위 가능(절단·지렛대·굴착·해체)→bypass 충족 | `action`(kind), `uses` | bypass_dc 우회 |
| `light` | 어둠 구역 조명: 숨은 단서·위협 발견, 배터리 소모 | `charges`, `scope`(1~3) | area_scan과 시너지 |
| `weapon` | 장애물 파괴·개체 타격, 탄/내구, **소음→위협↑** | `power`, `ammo`, `noise`(0~2) | 소음=오염/위협 비용 |
| `consumable` | HP/SAN/상태 회복, 횟수 소모 | `hp`,`san`,`status`,`uses` | 약·음식(POTION/BREAD) |
| `comm` | 전화·무전(원격연락 게이트) — 기존 hasCommDevice 구조화 | `battery`, `monitored`(0/1) | comms_monitored 연동 |
| `protective` | 위해 경감(방독면·부적·결계) | `hazard`,`power`,`uses` | PROTECT 특성과 동형 |
| `ritual` | 의례 절차에서 소모(괴담 약화·봉인) | `ritual_id` | collapse_condition 연동 |
| `evidence` | NPC에게 제시→반응 변화(사진·증거) | `target_npc`,`reveals` | social/NPC 연동 |

### 자동·읽기형
| item_type | 효과 | 파라미터 |
|---|---|---|
| `record` | 읽으면 단서 공개(기존 content/lore_info) + **읽음→markFactDiscovered** | `clue` |
| `combine` | 다른 component와 조합→신규 아이템 **생성(변형)** | `combines_with`, `result` |

> 빈 `item_type=""` = 순수 소품(기존과 동일, GM 서술로만). **남용 방지: 기계 타입은 공략에 필요한 것만.**

---

## 4. 축①·② 런타임 상태 + USE 프로토콜

**신규 모델 `model/ItemInstance.java` (TraitData와 평행):**
```
String id; String itemType; Map<String,Integer> params;
int charges;     // 배터리·탄·내구 남은 수 (-1=무한)
boolean on;      // 조명 on/off 등 토글
boolean broken;  // 고장·소진
String transformedFrom; // 조합 출처(되돌리기·로그용)
```
**PlayerData:** `heldItemIds:Set<String>` → `inventory:Map<String,ItemInstance>`로 확장
(하위호환: `heldItemIds`는 `inventory.keySet()` 파생 뷰로 유지 → 기존 호출부·리플레이 안 깨짐).

**USE 흐름 (AI 서술 유지 + 효과는 기계화):**
1. 플레이어 행동문이 아이템 언급 또는 `/trpg use <아이템> [on <대상>]`.
2. 코드가 GM에 **구조화 소지품 컨텍스트** 주입: "X 소지: 손전등(light,배터리2), 열쇠(key→zone_D)".
3. GM이 `<ITEM_USE player=.. item=.. result=.. zone=.. charge=-1 transform=..>` 출력.
4. 코드가 ItemInstance에 적용: charge 차감 / broken 처리 / **gated_zone 해제(해당 파티)** / transform 생성 / markFactDiscovered.

→ 서술의 자유는 GM이, 상태 변화·진행 게이트는 코드가. (특성의 activateX 디스패치와 같은 구조.)

---

## 5. .gdam 스키마 델타

```
key_items[] 에 추가(전부 선택):
  "item_type":"key",            // 프리미티브 키(없으면 순수 소품)
  "item_params":{"unlocks":"zone_D","consumed":1},
  "charges": 3,                 // light/weapon/consumable 초기 충전·탄·횟수
  "combines_with":["item_부품A"], "result":"item_완성품",  // combine
  "noise": 1                    // weapon 소음 등급
gated_zones[].requires : KEY 아이템 id / TOOL action 을 가리키게(현재 free-text → 구조 참조)
```
구버전 .gdam 호환: `item_type` 없으면 기존처럼 순수 소품으로 동작(기본값 처리).

---

## 6. 축④ 능력·구역 게이트와의 통합 (시너지)

아이템 효과와 특성 효과는 **상당 부분 겹친다** → 같은 설계 언어로 묶으면 일관성↑:
| 아이템 | 대응 특성(ABILITY_FRAMEWORK) |
|---|---|
| light | area_scan |
| protective | protect |
| consumable(부활/회복) | revive_ally |
| comm | link_ally |
| key/tool(우회) | mobility(돌파·우회) |
| evidence | social |

**구역 게이트 단일화:** 하나의 gated_zone을 ⓐKEY/TOOL 아이템 ⓑbypass 행동+DC ⓒ관련 특성(mobility/remote_bypass) **셋 중 하나로** 통과 — 해결 경로가 코드 한 곳에서 일관 판정.

---

## 7. 권장 적용 순서

- **Phase I — 스키마·읽기 단서화(저위험, 런타임 거의 무변):** key_items에 item_type/item_params 추가(생성·표시), `record` 읽음→markFactDiscovered. AI가 아이템에 타입 부여 시작. 기존 동작 보존.
- **Phase II — USE 프로토콜 + 상태(핵심):** ItemInstance(charges/on/broken) + `<ITEM_USE>` 파싱·디스패치 + GM 구조화 소지품 컨텍스트. key·tool·light·consumable·comm 먼저(가장 흔함).
- **Phase III — 조합·변형 + 전투/의례:** combine/component(부품→신규), weapon·protective·ritual·evidence.
- **Phase IV — 능력·구역 게이트 통합:** gated_zone 단일 해결 경로, 아이템↔특성 공유 프리미티브 정리.

## 8. 코드 변경 범위·난이도
| 항목 | 파일 | 난이도 | 위험 |
|---|---|---|---|
| item_type 스키마·생성 | GdamGenerator | 낮음 | 낮음 |
| ItemEffectRegistry(enum+catalog+defaults) | 신규 | 중간 | 낮음 |
| ItemInstance + inventory(heldItemIds 파생뷰) | PlayerData,model 신규 | 중간 | 중간(리플레이·복원 연계) |
| `<ITEM_USE>` 파싱·디스패치 | AiManager,TRPGGameManager | 중간 | 중간 |
| 표시(lore에 상태/배터리) | ItemManager | 낮음 | 낮음 |
| gated_zone 단일 게이트 | TRPGGameManager | 중간 | 중간 |
| combine/transform | TRPGGameManager,ItemManager | 중간 | 중간 |

컴파일 불가 환경 — 각 Phase 독립 커밋·정적검증, 사용자 로컬 빌드.
