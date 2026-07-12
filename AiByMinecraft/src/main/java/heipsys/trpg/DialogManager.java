package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;

/**
 * Paper Dialog API 기반 다이얼로그.
 * 캐릭터 시트, 특성 선택/제거를 Paper 1.21.3+ Dialog UI로 표시한다.
 */
public class DialogManager {

    public enum DialogState { DICE_CONFIRM, TRAIT_SELECTION, TRAIT_REMOVE, TRAIT_ACTIVATION, STAGE_END_TRAIT }

    private final Map<UUID, DialogState>     activeDialog = new HashMap<>();
    private final Map<UUID, List<TraitData>> traitChoices = new HashMap<>();

    /** '중요 정보'(전화번호·능력으로 밝힌 사실) 화면을 여는 콜백 — TRPGGameManager가 주입(교차 플레이어 데이터 필요). */
    private Consumer<Player> importantInfoOpener;
    public void setImportantInfoOpener(Consumer<Player> opener) { this.importantInfoOpener = opener; }

    /** '소통수단 변경'(#177) 다이얼로그를 여는 콜백 — 도구가 없을 때 기록에서 여는 경로. TRPGGameManager가 주입. */
    private Consumer<Player> commMethodOpener;
    public void setCommMethodOpener(Consumer<Player> opener) { this.commMethodOpener = opener; }

    /** '이동'(#190) 목적지 선택기를 여는 콜백 — 지도 도구 없이도 기록에서 여는 경로. TRPGGameManager가 주입. */
    private Consumer<Player> moveOpener;
    public void setMoveOpener(Consumer<Player> opener) { this.moveOpener = opener; }

    // ──────────────────────────────────────────────────────────────
    //  캐릭터 시트 + 주사위 확인
    // ──────────────────────────────────────────────────────────────

    public void showCharacterSheet(Player player, PlayerData pd, int roomNumber, int attempt,
                                    String jobDesc, Runnable onConfirm, Runnable onReroll) {
        activeDialog.put(player.getUniqueId(), DialogState.DICE_CONFIRM);

        // 스탯을 수정 불가 body Component로 구성 (마우스 오버레이 포함)
        var bodyBuilder = Component.text();
        // 이름·성별이 있을 때만 표시
        if (!pd.charName.isEmpty() || !pd.gender.isEmpty()) {
            bodyBuilder.append(Component.text("이름 · 성별  ", NamedTextColor.AQUA))
                       .append(Component.text(
                           (pd.charName.isEmpty() ? "미정" : pd.charName)
                           + "  ·  "
                           + (pd.gender.isEmpty() ? "미상" : pd.gender),
                           NamedTextColor.WHITE))
                       .appendNewline();
        }
        bodyBuilder
            .append(Component.text("나이 · 직업  ", NamedTextColor.GOLD))
            .append(Component.text(pd.age + "세  ·  ", NamedTextColor.WHITE))
            .append(Component.text(pd.job, NamedTextColor.WHITE)
                .hoverEvent(Component.text(
                    (jobDesc != null && !jobDesc.isBlank()) ? pd.job + "\n" + jobDesc
                                                            : pd.job + "\n이 인물의 직업입니다.",
                    NamedTextColor.GRAY)))
            .appendNewline()
            // 체력 — 라벨: 설명, 값: 원본 수치
            .append(Component.text("체력  ", NamedTextColor.RED)
                .hoverEvent(Component.text(
                    "체력 (HP)\n현재: " + pd.hp[0] + " / 최대: " + pd.hp[1]
                    + "\n피해를 받으면 감소하며, 0이 되면 사망합니다.", NamedTextColor.GRAY)))
            .append(Component.text(hpDisplay(pd.hp), NamedTextColor.WHITE)
                .hoverEvent(Component.text(
                    "현재: " + pd.hp[0] + " / 최대: " + pd.hp[1]
                    + "  (100 기준 환산 표시)\n" + heipsys.trpg.model.PlayerData.statDesc("hp", pd.hp[1]),
                    NamedTextColor.GRAY)))
            .append(Component.text("    ", NamedTextColor.WHITE))
            // 정신력 — 라벨: 설명, 값: 원본 수치
            .append(Component.text("정신력  ", NamedTextColor.AQUA)
                .hoverEvent(Component.text(
                    "정신력 (SAN)\n현재: " + pd.san[0] + " / 최대: " + pd.san[1]
                    + "\n공포·충격으로 감소. 0이 되면 이성을 잃습니다.", NamedTextColor.GRAY)))
            .append(Component.text(hpDisplay(pd.san), NamedTextColor.WHITE)
                .hoverEvent(Component.text(
                    "현재: " + pd.san[0] + " / 최대: " + pd.san[1]
                    + "  (100 기준 환산 표시)\n" + heipsys.trpg.model.PlayerData.statDesc("san", pd.san[1]),
                    NamedTextColor.GRAY)))
            .appendNewline()
            // 2차 스탯 — 라벨에 판정 설명
            .append(Component.text("근력 ", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("근력 (STR)\n물리 행동·격투·이동 판정에 영향", NamedTextColor.GRAY)))
            .append(Component.text(String.valueOf(pd.str), NamedTextColor.WHITE)
                .hoverEvent(Component.text("근력: " + pd.str + "\n" + heipsys.trpg.model.PlayerData.statDesc("str", pd.str), NamedTextColor.YELLOW)))
            .append(Component.text("   매력 ", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("매력 (CHA)\n설득·협박·사교 판정에 영향", NamedTextColor.GRAY)))
            .append(Component.text(String.valueOf(pd.cha), NamedTextColor.WHITE)
                .hoverEvent(Component.text("매력: " + pd.cha + "\n" + heipsys.trpg.model.PlayerData.statDesc("cha", pd.cha), NamedTextColor.YELLOW)))
            .append(Component.text("   행운 ", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("행운 (LUK)\n위기 탈출·우연한 발견 판정에 영향", NamedTextColor.GRAY)))
            .append(Component.text(String.valueOf(pd.luk), NamedTextColor.WHITE)
                .hoverEvent(Component.text("행운: " + pd.luk + "\n" + heipsys.trpg.model.PlayerData.statDesc("luk", pd.luk), NamedTextColor.YELLOW)))
            .append(Component.text("   영감 ", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("영감 (SPR)\n직감·예지·정신 방어 판정에 영향", NamedTextColor.GRAY)))
            .append(Component.text(String.valueOf(pd.spr), NamedTextColor.WHITE)
                .hoverEvent(Component.text("영감: " + pd.spr + "\n" + heipsys.trpg.model.PlayerData.statDesc("spr", pd.spr), NamedTextColor.YELLOW)));

        // 능력 성향: 수치를 자연어로 가볍게 해설
        bodyBuilder.appendNewline().appendNewline()
            .append(Component.text("[능력 성향] ", NamedTextColor.GREEN))
            .append(Component.text(pd.getStatNarrative(), NamedTextColor.GRAY));

        if (!pd.traits.isEmpty()) {
            bodyBuilder.appendNewline()
                .append(Component.text("특성:", NamedTextColor.LIGHT_PURPLE));
            for (TraitData t : pd.traits) {
                // 특성 — 마우스 오버레이로 설명 + 사용 효과 표시
                Component traitHover = buildTraitHover(t);
                bodyBuilder.appendNewline()
                    .append(Component.text("  ▸ (" + t.grade + ") " + t.name, NamedTextColor.GRAY)
                        .hoverEvent(traitHover));
            }
        }
        Component body = bodyBuilder.build();

        List<ActionButton> buttons = new ArrayList<>();

        // 확정 버튼
        buttons.add(ActionButton.create(
            Component.text("✔ 확정", NamedTextColor.GREEN),
            Component.text("이 스탯으로 캐릭터를 확정합니다."),
            150,
            DialogAction.customClick((v, a) -> onConfirm.run(),
                ClickCallback.Options.builder().uses(1).build())
        ));

        // 재굴림 버튼
        if (pd.diceRollsRemaining > 0) {
            int rem = pd.diceRollsRemaining;
            buttons.add(ActionButton.create(
                Component.text("🎲 재굴림 (" + rem + "회)", NamedTextColor.YELLOW),
                Component.text("스탯을 다시 굴립니다.\n남은 재굴림: " + rem + "회"),
                150,
                DialogAction.customClick((v, a) -> onReroll.run(),
                    ClickCallback.Options.builder().uses(1).build())
            ));
        } else {
            buttons.add(ActionButton.create(
                Component.text("재굴림 불가", NamedTextColor.DARK_GRAY),
                Component.text("재굴림 횟수를 모두 소진했습니다."),
                150,
                null
            ));
        }

        // 닫기 버튼·ESC로 종료 불가 — 확정/재굴림만 가능(닫아서 진행 불가가 되던 문제 방지).
        // 그래도 닫혔다면 /trpg me 로 다시 열 수 있다(2중 안전장치).
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("캐릭터 생성  |  스테이지 " + roomNumber + " · " + attempt + "회차"))
                .body(List.of(DialogBody.plainMessage(body)))
                .canCloseWithEscape(false)
                .build())
            .type(DialogType.multiAction(buttons, null, 2))
        );
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  세션 시작 — AI 품질 선택
    // ──────────────────────────────────────────────────────────────

    /** 게임 시작 전 GM AI 품질(표준/고품질)을 선택하는 다이얼로그 */
    public void showQualityChoice(Player player, String provider, String lowCost, String medCost, String highCost, String effCost,
                                  Runnable onLow, Runnable onMedium, Runnable onHigh, Runnable onEfficient) {
        Component body = Component.text()
            .append(Component.text("GM AI 품질을 선택하세요.", NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("(" + provider + " · 아래 시간당 비용은 거친 추정치입니다)", NamedTextColor.DARK_GRAY))
            .appendNewline().appendNewline()
            .append(Component.text("저품질  ", NamedTextColor.GRAY))
            .append(Component.text("빠르고 저렴 · " + lowCost, NamedTextColor.DARK_GRAY))
            .appendNewline()
            .append(Component.text("중품질  ", NamedTextColor.YELLOW))
            .append(Component.text("균형·권장 · " + medCost, NamedTextColor.GRAY))
            .appendNewline()
            .append(Component.text("고품질  ", NamedTextColor.AQUA))
            .append(Component.text("가장 풍부·일관 · " + highCost, NamedTextColor.GRAY))
            .appendNewline()
            .append(Component.text("효율  ", NamedTextColor.GREEN))
            .append(Component.text("평시 중품질·절정만 고품질(적응형) · " + effCost, NamedTextColor.GRAY))
            .build();

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(
            Component.text("저품질 모드  " + lowCost, NamedTextColor.GRAY),
            Component.text("가장 빠르고 저렴한 모델로 진행합니다.\n서술이 단순할 수 있습니다.\n" + lowCost),
            150,
            DialogAction.customClick((v, a) -> onLow.run(),
                ClickCallback.Options.builder().uses(1).build())
        ));
        buttons.add(ActionButton.create(
            Component.text("중품질 모드 (권장)  " + medCost, NamedTextColor.YELLOW),
            Component.text("균형 잡힌 기본 모델로 진행합니다.\n품질과 비용의 절충.\n" + medCost),
            150,
            DialogAction.customClick((v, a) -> onMedium.run(),
                ClickCallback.Options.builder().uses(1).build())
        ));
        buttons.add(ActionButton.create(
            Component.text("고품질 모드  " + highCost, NamedTextColor.AQUA),
            Component.text("가장 똑똑한 모델로 진행합니다.\n응답이 느리고 토큰 비용이 큽니다.\n" + highCost),
            150,
            DialogAction.customClick((v, a) -> onHigh.run(),
                ClickCallback.Options.builder().uses(1).build())
        ));
        buttons.add(ActionButton.create(
            Component.text("효율 모드 (적응형)  " + effCost, NamedTextColor.GREEN),
            Component.text("평소엔 중품질로 저렴하게, 전투·절정에만 자동으로 고품질(Opus 등).\n비용/품질을 자동 균형.\n" + effCost),
            150,
            DialogAction.customClick((v, a) -> onEfficient.run(),
                ClickCallback.Options.builder().uses(1).build())
        ));

        ActionButton cancel = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)),
            Component.text("세션을 시작하지 않습니다."),
            100, null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("세션 시작  —  AI 품질 선택"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, cancel, 1))
        );
        player.showDialog(dialog);
    }

    public void showModeChoice(Player player, Runnable onCreative, Runnable onFamiliar) {
        Component body = Component.text()
            .append(Component.text("게임 모드를 선택하세요.", NamedTextColor.WHITE))
            .appendNewline().appendNewline()
            .append(Component.text("AI 창작  ", NamedTextColor.YELLOW))
            .append(Component.text("AI가 매 스테이지 새로운 괴담을 창작합니다.", NamedTextColor.GRAY))
            .appendNewline()
            .append(Component.text("친숙한 친구들  ", NamedTextColor.LIGHT_PURPLE))
            .append(Component.text("실제 괴담·SCP·크리피파스타를 무작위로 사용합니다.", NamedTextColor.GRAY))
            .build();

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(
            Component.text("AI 창작", NamedTextColor.YELLOW),
            Component.text("AI가 매번 새로운 독창적 괴담을 만들어냅니다."),
            150,
            DialogAction.customClick((v, a) -> onCreative.run(),
                ClickCallback.Options.builder().uses(1).build())
        ));
        buttons.add(ActionButton.create(
            Component.text("친숙한 친구들", NamedTextColor.LIGHT_PURPLE),
            Component.text("슬렌더맨·SCP·크리피파스타 등 유명 괴담이 등장합니다.\n원작 패턴·공략법을 따르되 스테이지에 맞춰 조정됩니다."),
            150,
            DialogAction.customClick((v, a) -> onFamiliar.run(),
                ClickCallback.Options.builder().uses(1).build())
        ));

        ActionButton cancel = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)),
            Component.text("세션을 시작하지 않습니다."),
            100, null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("세션 시작  —  게임 모드 선택"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, cancel, 2))
        );
        player.showDialog(dialog);
    }

    /** 친숙한 친구들 모드 — 괴담 범위(필터) 선택. onPick에 필터 키 전달. */
    public void showFamiliarFilter(Player player, java.util.function.Consumer<String> onPick) {
        // 인지도(흔한/들어는본/마이너)는 이 화면에서 뺐다 — /trpg setting의 ★인지도 풀 토글★로 분리됨(중복 제거).
        //   여기선 '출처·계열'만 고르고, 유명/덜유명/마이너 비중은 인지도 토글이 담당한다.
        String[][] opts = {
            {"urban",       "도시 전설만",    "실제 도시전설·민간전승만 (SCP 제외)"},
            {"korean",      "한국 괴담만",    "한국 전설·괴담만"},
            {"japan",       "일본 괴담만",    "일본 요괴·유령·학교괴담·도시전설만"},
            {"western",     "서양 괴담만",    "서양권 유령·도시전설·민담·크립티드"},
            {"scp",         "SCP만",         "SCP 재단 항목만"},
            {"creepypasta", "크리피파스타만", "인터넷 창작 괴담(크리피파스타)"},
            {"backrooms",   "백룸·이계만",   "백룸·리미널 스페이스·이계 공간"},
            {"internet",    "인터넷 괴담만",  "온라인 유래 괴담·아날로그 호러·ARG"},
            {"projectmoon", "환상체만",      "프로젝트 문(로보토미·라오루) 환상체"},
            {"game",        "게임 괴담만",   "게임 도시전설·게임 속 괴물·공포게임 주인공"},
            {"cosmic",      "코즈믹 호러만",  "크툴루 신화·우주적·외우주 공포 (러브크래프트 계열)"},
            {"real",        "실화·미제사건만","실존 미제사건·심리 증후군"},
            {"sf",          "SF 공포만",      "외계·지저·시뮬·음모·기생·폭주 AI (SF 공포)"},
            {"rule",        "규칙 괴담만",    "규칙을 어기면 화를 입는 류"},
            {"random",      "모두 무작위",    "위 전부를 섞어 무작위"}
        };
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] o : opts) {
            final String key = o[0];
            buttons.add(ActionButton.create(
                Component.text(o[1], NamedTextColor.LIGHT_PURPLE),
                Component.text(o[2]),
                150,
                DialogAction.customClick((v, a) -> onPick.accept(key),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton cancel = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)),
            Component.text("세션을 시작하지 않습니다."), 100, null);
        Component body = Component.text()
            .append(Component.text("친숙한 친구들 — 어떤 괴담을 부를까요?", NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("선택한 범위 안에서 괴담이 생성됩니다.", NamedTextColor.GRAY))
            .build();
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("친숙한 친구들  —  괴담 범위 선택"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, cancel, 2))
        );
        player.showDialog(dialog);
    }

    /** '모두 무작위' 시작 전 — 카테고리별 포함/제외 토글(서버 영속). 로보토미·코즈믹·SCP 기본 제외.
     *  isExcluded=현재 제외 여부, onToggle=키 토글 후 이 창 재표시(호출측이 처리), onStart=이대로 시작. */
    public void showRandomExcludeChoice(Player player, java.util.function.Predicate<String> isExcluded,
                                        java.util.function.Consumer<String> onToggle, Runnable onStart) {
        String[][] cats = {
            {"projectmoon", "환상체(로보토미)"}, {"cosmic", "코즈믹 호러"}, {"scp", "SCP"},
            {"korean", "한국 괴담"}, {"japan", "일본 괴담"}, {"western", "서양 괴담"},
            {"game", "게임 괴담"}, {"creepypasta", "크리피파스타"}, {"backrooms", "백룸·이계"},
            {"internet", "인터넷 괴담"}, {"real", "실화·미제사건"}, {"sf", "SF 공포"}
        };
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(
            Component.text("▶ 이대로 시작", NamedTextColor.GOLD),
            Component.text("포함(✓)된 종류만 섞어 무작위로 생성합니다."), 200,
            DialogAction.customClick((v, a) -> onStart.run(), ClickCallback.Options.builder().uses(1).build())));
        for (String[] c : cats) {
            final String key = c[0];
            boolean ex = isExcluded.test(key);
            buttons.add(ActionButton.create(
                Component.text((ex ? "✗ " : "✓ ") + c[1], ex ? TextColor.color(0x888888) : NamedTextColor.GREEN),
                Component.text(ex ? "제외됨 — 누르면 포함" : "포함됨 — 누르면 제외"), 150,
                DialogAction.customClick((v, a) -> onToggle.accept(key), ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton cancel = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)),
            Component.text("세션을 시작하지 않습니다."), 100, null);
        Component body = Component.text()
            .append(Component.text("모두 무작위 — 어떤 종류를 섞을까요?", NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("✓=포함 / ✗=제외. 항목을 누르면 바뀌고 이 창이 다시 열립니다. 설정은 서버에 저장됩니다.", NamedTextColor.GRAY))
            .build();
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("모두 무작위  —  포함할 종류 선택"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, cancel, 2))
        );
        player.showDialog(dialog);
    }

    /** 시작 설정 — 괴담 유형/성격 선택(종류별 테스트용). onPick에 컨셉 제약 문구 전달(""=무작위). */
    public void showEntityTypeChoice(Player player, java.util.function.Consumer<String> onPick) {
        String[][] opts = {
            {"", "무작위 (기본)", "유형 제약 없이 매번 자유 생성"},
            {"추격·살인마형 — 물리적으로 쫓고 해치는 위협 중심", "추격·살인마형", "물리 위협·도주·제압 중심"},
            {"규칙·금기형 — 지켜야 할 규칙/금기가 있고 어기면 화를 입는", "규칙·금기형", "규칙 파악과 준수가 생존의 열쇠"},
            {"인지·정신형 — 보거나 알수록 위험해지는 인지 기반 위협", "인지·정신형", "시선·관심·인지가 매개 (예: 보라색 여인)"},
            {"사물·저주물건형 — 특정 물건에 깃든 저주가 중심", "사물·저주물건형", "물건의 내력·처분이 핵심"},
            {"장소·공간형 — 공간 자체가 뒤틀리는(루프·이계) 유형", "장소·공간형", "이상 공간·무한 복도·백룸류"},
            {"정보격리·통신형 — 통신을 끊거나 왜곡하는 유형", "정보격리·통신형", "연락 두절·변조가 공포의 축"},
            {"빙의·정체차용형 — 사람 몸이나 정체를 빼앗는 유형", "빙의·정체차용형", "누가 진짜인지 의심하게 되는"},
            {"시간·인과형 — 시간루프·인과 역전을 다루는 유형", "시간·인과형", "루프·예언·역행"},
            {"집단·감염형 — 사람 사이로 번지는 전파형 위협", "집단·감염형", "감염·소문·집단 환각"},
            {"코즈믹·우주적형 — 인간 이해를 넘어선 외우주·차원 너머 존재, 알수록·볼수록 미쳐가는 유형", "코즈믹·우주적형", "우주적 공포·크툴루 신화류"},
            {"자동 해결형(비개입형) — 두면 해결되고 개입하면 악화된다(가만히 있으면 클리어)", "자동 해결형(비개입)", "안 건드리면 지나감 — 개입할수록 위험"},
            {"소문 실체화형 — 소문·관찰·집단 인지가 퍼질수록 실체를 얻어 강해진다", "소문 실체화형", "말할수록·믿을수록 실체가 됨"},
            {"유희형 성격 — 사람을 갖고 노는 장난스러운 괴담", "성격: 유희형", "낄낄대며 판을 짜는 유형"},
            {"심판자형 성격 — 죄·위선을 심판하려 드는 괴담", "성격: 심판자형", "죄책감·고해를 파고드는 유형"}
        };
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] o : opts) {
            final String hint = o[0];
            final boolean isRule = o[1].equals("규칙·금기형");
            buttons.add(ActionButton.create(
                Component.text(o[1] + (isRule ? " ▸" : ""), hint.isEmpty() ? NamedTextColor.GRAY : NamedTextColor.LIGHT_PURPLE),
                Component.text(isRule ? "세부 금기(감정·행동·응답 등) 고르기" : o[2]),
                150,
                DialogAction.customClick((v, a) -> {
                    if (isRule) showRuleSubtypeChoice(player, onPick); // ▸ 세부 RULE 유형 선택으로
                    else onPick.accept(hint);
                }, ClickCallback.Options.builder().uses(1).build())));
        }
        buttons.add(ActionButton.create(
            Component.text("▸ 더 많은 사건 구조 유형", NamedTextColor.YELLOW),
            Component.text("정보격리·통신유인·교환대가·도덕딜레마·인과역전·함정지시 등 직접 고르기"), 150,
            DialogAction.customClick((v, a) -> showSpecialTypeChoice(player, onPick),
                ClickCallback.Options.builder().uses(1).build())));
        ActionButton cancel = ActionButton.create(
            Component.text("닫기", TextColor.color(0xAAAAAA)),
            Component.text("설정을 바꾸지 않습니다."), 100, null);
        Component body = Component.text()
            .append(Component.text("다음에 생성될 괴담의 유형/성격을 고정합니다. (종류별 테스트용)", NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("선택은 다음 생성부터 계속 적용 — '무작위'로 되돌릴 수 있습니다. ▸ 표시는 세부 선택.", NamedTextColor.GRAY))
            .build();
        // 3열로 배치해 항목이 잘리지 않게(2열에선 목록이 길어 하단이 안 보이던 문제).
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("시작 설정  —  괴담 유형/성격 선택"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, cancel, 3))
        );
        player.showDialog(dialog);
    }

    /**
     * ★이동 목적지 선택(#190 이동 뒤집기)★ — 아는(방문) 구역을 버튼으로. dests=[{zoneId, 표시명, 경로/거리 요약}].
     * 아는 곳이면 먼 구역도 고를 수 있고(도중 구역을 거쳐 감), onPick에 선택 zoneId를 넘긴다.
     */
    public void showMoveDestChoice(Player player, java.util.List<String[]> dests, java.util.function.Consumer<String> onPick) {
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] d : dests) {
            final String zid = d[0];
            buttons.add(ActionButton.create(
                Component.text(d[1], NamedTextColor.AQUA),
                Component.text(d.length > 2 && d[2] != null ? d[2] : ""),
                220,
                DialogAction.customClick((v, a) -> onPick.accept(zid),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton cancel = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)),
            Component.text("이동하지 않습니다."), 100, null);
        Component body = Component.text()
            .append(Component.text("어디로 이동할까요?", NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("각 목적지에 「대분류」·경유 구역·이동시간·도착 턴이 표시됩니다. 대분류가 「?」면 아직 모르는 구역이에요.", NamedTextColor.GRAY))
            .build();
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("이동  —  목적지 선택"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, cancel, 2))
        );
        player.showDialog(dialog);
    }

    /** 특수·사건구조 유형 선택 — 생성기가 지원하는 구체 아키타입을 직접 골라 종류별로 테스트한다. onPick에 유형 힌트 전달. */
    public void showSpecialTypeChoice(Player player, java.util.function.Consumer<String> onPick) {
        String[][] opts = {
            {"정보 격리형 — 아는 자가 표적·모르는 자가 안전", "정보 격리형", "아는 순간 표적이 됨"},
            {"통신 유인형 — 전화·무전·통신을 쓰는 행위 자체가 괴담을 부른다", "통신 유인형", "연락하면 위험해짐"},
            {"교환·대가형 — 통과·안전·정보에 대가가 따른다(동료·기억·신체)", "교환·대가형", "무언가를 바쳐야 얻음"},
            {"도덕 딜레마형 — 정답 없는 선택을 강요한다", "도덕 딜레마형", "누굴 살리고 뭘 포기할지"},
            {"시한 의식형 — 마감(자정·만조·의식 완성)이 다가온다", "시한 의식형", "마감 전에 막거나 완수"},
            {"순번·연쇄형 — 정해진 순서로 표적이 된다(이름·자리·나이)", "순번·연쇄형", "순번 고리를 끊기"},
            {"규칙 목록 준수형 — 지킬 규칙 목록 중 일부는 가짜·함정", "규칙 목록 준수형", "진짜 규칙만 골라 지키기"},
            {"집단환각형 — 모두가 뒤바뀐 상식·기억을 진짜로 믿는다", "집단환각형", "거짓 현실을 깨닫기"},
            {"인과 역전형 — 치료가 독·도움이 해(개념·인과가 뒤집힘)", "인과 역전형", "상식대로 하면 당함"},
            {"환경 침식형 — 공간의 물리적 현실 자체가 변질(중력·거리·시간)", "환경 침식형", "세계의 물성이 뒤틀림"},
            {"함정 지시형 — 따르면 클리어 불가인 거짓 지시가 주어진다", "함정 지시형", "지시를 의심하고 거스르기"},
            {"역할극 강제형 — 부여된 배역대로 연기해야 진실에 닿는다", "역할극 강제형", "극본 완수 또는 모순 짚기"},
            {"수수께끼·추리형 — 기괴한 상황의 숨은 진상을 추리로 푼다", "수수께끼·추리형", "반직관적 진상 꿰뚫기"},
            {"진영 대립형 — NPC 세력과 플레이어팀이 목적 충돌로 대립", "진영 대립형", "협상·기만·전투·이간"},
            {"조건부 안전지대형 — 특정 조건에서만 안전하고 그 조건이 계속 변한다", "조건부 안전지대형", "위치·타이밍 싸움"},
            {"사후 정보형 — 죽거나 끝나야 해결법이 드러난다(다회차 연계)", "사후 정보형", "한 번 죽어본 지식이 다음 판에"},
            {"기억·망각형 — 시간·조건마다 기억이 실제로 지워진다", "기억·망각형", "기록·표식으로 진상 보존"},
            {"금지워드형 — 특정 단어를 입에 올리면 표적이 된다", "금지워드형", "말해선 안 될 단어"},
            {"감정 금기형 — 특정 감정 표출·자극이 괴담을 부른다", "감정 금기형", "울음·분노·공포 절제"},
            {"금기 행동형 — 특정 행동(소리·뒤돌기·불 켜기)이 괴담을 부른다", "금기 행동형", "행동을 절제하는 긴장"},
            {"시간·인과율형(루프) — 특정 시간대가 반복되거나 시간선이 엉킨다", "시간·인과율형(루프)", "루프의 고리를 끊기"},
            {"공간 왜곡형 — 방과 방의 연결이 고정되지 않고 바뀐다(비유클리드)", "공간 왜곡형", "표식으로 공간을 고정"},
            {"전원 괴이형 — 플레이어 외 모든 NPC가 사람인 척하는 괴이", "전원 괴이형", "누가 사람인지 의심"},
            {"비지구 무대형 — 무대가 지구 일상이 아니다(우주·이세계·지옥·사후)", "비지구 무대형", "이질적 세계의 규칙"},
            {"신체 기생·교환형 — 괴담 일부가 몸에 기생하거나 시점마다 배역이 뒤바뀐다", "신체 기생·교환형", "이득과 잠식의 딜레마"}
        };
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] o : opts) {
            final String hint = o[0];
            buttons.add(ActionButton.create(
                Component.text(o[1], NamedTextColor.LIGHT_PURPLE),
                Component.text(o[2]), 150,
                DialogAction.customClick((v, a) -> onPick.accept(hint),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton back = ActionButton.create(
            Component.text("◀ 뒤로(유형 전체)", TextColor.color(0xAAAAAA)),
            Component.text("괴담 유형 전체 목록으로"), 120,
            DialogAction.customClick((v, a) -> showEntityTypeChoice(player, onPick),
                ClickCallback.Options.builder().uses(1).build()));
        Component body = Component.text()
            .append(Component.text("생성기가 지원하는 구체 사건 구조 유형입니다. (종류별 테스트용)", NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("고르면 다음 생성부터 그 구조로 고정 — 목록에 없는 유형도 §f/trpg setting type <이름>§7으로 직접 지정 가능. '무작위'로 되돌릴 수 있습니다.", NamedTextColor.GRAY))
            .build();
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("시작 설정  —  특수·사건구조 유형"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, back, 3))
        );
        player.showDialog(dialog);
    }

    /** 규칙·금기형 세부 유형 선택 — 감정/행동/응답/시선/소리/시간/순서/접촉 금기. onPick에 구체 힌트 전달. */
    public void showRuleSubtypeChoice(Player player, java.util.function.Consumer<String> onPick) {
        String[][] opts = {
            {"규칙·금기형(감정 금기) — 특정 감정 표출(울음·웃음·공포 등)이 금지되고 어기면 화를 입는", "감정 금기", "울면/웃으면/무서워하면 당함"},
            {"규칙·금기형(행동 금기) — 특정 행동(뒤돌아보기·뛰기·불 켜기 등)이 금지되는", "행동 금기", "뒤돌아보면/뛰면/불 켜면"},
            {"규칙·금기형(응답 금기) — 부름·질문에 대답하거나 반응하면 안 되는", "응답 금기", "이름을 불러도 대답 금지"},
            {"규칙·금기형(시선 금기) — 쳐다보거나 눈을 마주치면 안 되는", "시선 금기", "직시·주시가 방아쇠"},
            {"규칙·금기형(소리 금기) — 소리를 내거나 말하면 안 되는", "소리 금기", "침묵 강요, 소리에 반응"},
            {"규칙·금기형(시간 금기) — 특정 시각·시간대에 정해진 규칙을 지켜야 하는", "시간 금기", "정각·자정 등 시각 규칙"},
            {"규칙·금기형(순서·절차 금기) — 정해진 순서·절차를 어기면 안 되는", "순서 금기", "절차·순서 위반이 화근"},
            {"규칙·금기형(접촉·소지 금기) — 특정 물건을 만지거나 지니면 안 되는", "접촉 금기", "만지면/가지면 저주"}
        };
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] o : opts) {
            final String hint = o[0];
            buttons.add(ActionButton.create(
                Component.text(o[1], NamedTextColor.LIGHT_PURPLE),
                Component.text(o[2]), 150,
                DialogAction.customClick((v, a) -> onPick.accept(hint),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton back = ActionButton.create(
            Component.text("◀ 뒤로(유형 전체)", TextColor.color(0xAAAAAA)),
            Component.text("괴담 유형 전체 목록으로"), 120,
            DialogAction.customClick((v, a) -> showEntityTypeChoice(player, onPick),
                ClickCallback.Options.builder().uses(1).build()));
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("규칙·금기형  —  세부 금기 선택"))
                .body(List.of(DialogBody.plainMessage(
                    Component.text("어떤 종류의 금기가 중심인 괴담을 만들까요?", NamedTextColor.WHITE))))
                .build())
            .type(DialogType.multiAction(buttons, back, 2))
        );
        player.showDialog(dialog);
    }

    /** 시작 설정 다이얼로그 — 자동생성·시작 스테이지·괴담 유형을 버튼으로 고른다. 항목을 누르면 바뀌고 이 창이 다시 열린다. */
    public void showStartSettings(Player player, boolean pregen, int startStage, String typeHint, String famePool,
                                  String reservedEntity, boolean groupTurn, boolean swearAllowed,
                                  Runnable onTogglePregen, Runnable onPickStage, Runnable onPickType, Runnable onPickFame,
                                  Runnable onToggleGroupTurn, Runnable onPickEntity, Runnable onToggleSwear) {
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(
            Component.text("자동 사전생성:  " + (pregen ? "켜짐" : "꺼짐"), pregen ? NamedTextColor.GREEN : NamedTextColor.RED),
            Component.text("다음 시나리오를 미리 만들어 둘지 (끄면 즉석 생성)"), 180,
            DialogAction.customClick((v, a) -> onTogglePregen.run(), ClickCallback.Options.builder().uses(1).build())));
        buttons.add(ActionButton.create(
            Component.text("턴 처리:  " + (groupTurn ? "단체턴" : "개별턴"), groupTurn ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
            Component.text(groupTurn ? "행동가능 전원 행동 후 GM 1회 통합 처리 (일관성↑·비용↓)" : "행동마다 즉시 GM 호출 (응답 빠름·비용↑)"), 180,
            DialogAction.customClick((v, a) -> onToggleGroupTurn.run(), ClickCallback.Options.builder().uses(1).build())));
        buttons.add(ActionButton.create(
            Component.text("시작 스테이지:  " + startStage + (startStage > 1 ? "  (레벨 보정 " + (startStage - 1) + "단계)" : ""), NamedTextColor.AQUA),
            Component.text("새 게임을 몇 스테이지부터 — 높을수록 시작 캐릭터가 강함 (1~6)"), 180,
            DialogAction.customClick((v, a) -> onPickStage.run(), ClickCallback.Options.builder().uses(1).build())));
        buttons.add(ActionButton.create(
            Component.text("괴담 유형/성격:  " + (typeHint == null || typeHint.isEmpty() ? "무작위" : typeHint), NamedTextColor.LIGHT_PURPLE),
            Component.text("다음 생성 괴담의 유형/성격 고정 (테스트용)"), 180,
            DialogAction.customClick((v, a) -> onPickType.run(), ClickCallback.Options.builder().uses(1).build())));
        buttons.add(ActionButton.create(
            Component.text("인지도 풀:  " + famePoolLabel(famePool), NamedTextColor.GOLD),
            Component.text("등장 괴담 인지도 고정 (유명/덜유명/마이너만, 또는 난이도별)"), 180,
            DialogAction.customClick((v, a) -> onPickFame.run(), ClickCallback.Options.builder().uses(1).build())));
        buttons.add(ActionButton.create(
            Component.text("다음 괴담 지정:  " + (reservedEntity == null || reservedEntity.isEmpty() ? "없음(무작위)" : reservedEntity),
                (reservedEntity == null || reservedEntity.isEmpty()) ? NamedTextColor.GRAY : NamedTextColor.LIGHT_PURPLE),
            Component.text("다음에 생성될 괴담을 특정 이름으로 지정 (예: 쿠네쿠네) — 1회 적용. 누르면 채팅으로 이름 입력"), 180,
            DialogAction.customClick((v, a) -> onPickEntity.run(), ClickCallback.Options.builder().uses(1).build())));
        buttons.add(ActionButton.create(
            Component.text("NPC 욕설:  " + (swearAllowed ? "허용" : "금지"), swearAllowed ? NamedTextColor.RED : NamedTextColor.GREEN),
            Component.text(swearAllowed ? "거친 인물이 성격대로 욕설·비속어 사용 (혐오·차별·성적모욕은 여전히 금지)" : "기본 — 욕설 없이 순화된 표현만 (거친 감정은 어조로)"), 180,
            DialogAction.customClick((v, a) -> onToggleSwear.run(), ClickCallback.Options.builder().uses(1).build())));
        ActionButton close = ActionButton.create(
            Component.text("닫기", TextColor.color(0xAAAAAA)),
            Component.text("설정 완료 — 다음 /trpg start 부터 적용"), 120, null);
        Component body = Component.text()
            .append(Component.text("새 게임 시작 옵션을 고릅니다. 항목을 누르면 값이 바뀌고 이 창이 다시 열립니다.", NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("설정은 다음 /trpg start 부터 적용됩니다.", NamedTextColor.GRAY))
            .build();
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("TRPG 시작 설정"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, close, 1))
        );
        player.showDialog(dialog);
    }

    /** 시작 스테이지(1~6) 선택 다이얼로그. onPick에 고른 스테이지 번호 전달. */
    public void showStageChoice(Player player, int cur, java.util.function.IntConsumer onPick) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            final int st = i;
            String label = st + "스테이지" + (st == cur ? "  (현재)" : "");
            String desc = st == 1 ? "보정 없음 — 처음부터 시작" : "레벨 보정 " + (st - 1) + "단계 (올스탯 +" + ((st - 1) * 2) + " & 특성 추가/등급↑)";
            buttons.add(ActionButton.create(
                Component.text(label, st == cur ? NamedTextColor.YELLOW : NamedTextColor.AQUA),
                Component.text(desc), 130,
                DialogAction.customClick((v, a) -> onPick.accept(st), ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton cancel = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)), Component.text("스테이지를 바꾸지 않습니다."), 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("시작 스테이지 선택"))
                .body(List.of(DialogBody.plainMessage(
                    Component.text("새 게임을 몇 스테이지부터 시작할까요? (높을수록 시작 캐릭터가 강함)", NamedTextColor.WHITE))))
                .build())
            .type(DialogType.multiAction(buttons, cancel, 3))
        );
        player.showDialog(dialog);
    }

    /** 인지도 풀 설정의 표시 라벨. */
    private static String famePoolLabel(String p) {
        return switch (p == null ? "" : p) {
            case "major" -> "유명한 것만";
            case "semi"  -> "덜 유명한 것만";
            case "minor" -> "마이너한 것만";
            default -> "난이도별(기본)";
        };
    }

    /** 인지도 풀 선택 다이얼로그 — 난이도별(기본)/유명/덜유명/마이너. onPick에 키("" / major / semi / minor) 전달. */
    public void showFamePoolChoice(Player player, String cur, Consumer<String> onPick) {
        String c = cur == null ? "" : cur;
        String[][] opts = {
            {"",      "난이도별 (기본)", "초반 유명 → 후반 딥컷으로 자동 이동"},
            {"major", "유명한 것만",     "누구나 아는 대표작 위주"},
            {"semi",  "덜 유명한 것만",  "준메이저·팬덤 위주"},
            {"minor", "마이너한 것만",   "딥컷·전통·심리 위주"},
        };
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] o : opts) {
            boolean sel = o[0].equals(c);
            buttons.add(ActionButton.create(
                Component.text(o[1] + (sel ? "  (현재)" : ""), sel ? NamedTextColor.YELLOW : NamedTextColor.GOLD),
                Component.text(o[2]), 140,
                DialogAction.customClick((v, a) -> onPick.accept(o[0]), ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton cancel = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)), Component.text("바꾸지 않습니다."), 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("인지도 풀 선택"))
                .body(List.of(DialogBody.plainMessage(
                    Component.text("등장 괴담의 인지도를 고정할까요? (유명작도 완전 배제는 아님 — 강한 편향)", NamedTextColor.WHITE))))
                .build())
            .type(DialogType.multiAction(buttons, cancel, 2))
        );
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  캐릭터 정보 GUI (게임 중 열람 — 기본/배역 분리, 능동 특성 사용)
    // ──────────────────────────────────────────────────────────────

    /**
     * 게임 중 캐릭터 정보 다이얼로그.
     * 기본 능력치 + 배역 보정(증감)을 분리 표기하고, 특성을 기본/배역으로 나눠 표시한다.
     * 능동(active) 특성은 버튼으로 눌러 발동할 수 있다.
     */
    public void showCharacterInfo(Player player, PlayerData pd, String jobDesc, Consumer<String> onUseTrait) {
        var bodyBuilder = Component.text();
        // 이름·성별이 있을 때만 표시
        if (!pd.charName.isEmpty() || !pd.gender.isEmpty()) {
            bodyBuilder.append(Component.text("이름 · 성별  ", NamedTextColor.AQUA))
                       .append(Component.text(
                           (pd.charName.isEmpty() ? "미정" : pd.charName)
                           + "  ·  "
                           + (pd.gender.isEmpty() ? "미상" : pd.gender),
                           NamedTextColor.WHITE))
                       .appendNewline();
        }
        bodyBuilder
            .append(Component.text("나이 · 직업  ", NamedTextColor.GOLD))
            .append(Component.text(pd.age + "세  ·  ", NamedTextColor.WHITE))
            .append(Component.text(pd.job, NamedTextColor.WHITE)
                .hoverEvent(Component.text(
                    (jobDesc != null && !jobDesc.isBlank()) ? pd.job + "\n" + jobDesc
                                                            : pd.job + "\n이 인물의 직업입니다.",
                    NamedTextColor.GRAY)))
            .appendNewline()
            .append(Component.text("체력  ", NamedTextColor.RED)
                .hoverEvent(Component.text("체력 (HP)\n현재: " + pd.hp[0] + " / 최대: " + pd.hp[1], NamedTextColor.GRAY)))
            .append(Component.text(hpDisplay(pd.hp), NamedTextColor.WHITE))
            .append(Component.text("    정신력  ", NamedTextColor.AQUA)
                .hoverEvent(Component.text("정신력 (SAN)\n현재: " + pd.san[0] + " / 최대: " + pd.san[1], NamedTextColor.GRAY)))
            .append(Component.text(hpDisplay(pd.san), NamedTextColor.WHITE))
            .appendNewline()
            // 2차 스탯: 기본값 대비 배역 보정 표기
            .append(statCell("근력", pd.str, pd.baseStr))
            .append(Component.text("   "))
            .append(statCell("매력", pd.cha, pd.baseCha))
            .append(Component.text("   "))
            .append(statCell("행운", pd.luk, pd.baseLuk))
            .append(Component.text("   "))
            .append(statCell("영감", pd.spr, pd.baseSpr));

        // 능력 성향: 수치를 자연어로 가볍게 해설
        bodyBuilder.appendNewline().appendNewline()
            .append(Component.text("[능력 성향]", NamedTextColor.GREEN))
            .appendNewline()
            .append(Component.text(pd.getStatNarrative(), NamedTextColor.GRAY));

        // 기본 특성 (영구)
        boolean hasBase = pd.traits.stream().anyMatch(t -> !t.roleSpecific);
        if (hasBase) {
            bodyBuilder.appendNewline().appendNewline()
                .append(Component.text("[특성]", NamedTextColor.LIGHT_PURPLE));
            for (TraitData t : pd.traits) {
                if (t.roleSpecific) continue;
                bodyBuilder.appendNewline()
                    .append(traitLine(t));
            }
        }
        // 배역 특성 (이번 스테이지 한정)
        boolean hasRole = pd.traits.stream().anyMatch(t -> t.roleSpecific);
        if (hasRole) {
            bodyBuilder.appendNewline().appendNewline()
                .append(Component.text("[배역 특성] ", NamedTextColor.GOLD))
                .append(Component.text("(스테이지 종료 시 사라짐)", NamedTextColor.DARK_GRAY));
            for (TraitData t : pd.traits) {
                if (!t.roleSpecific) continue;
                bodyBuilder.appendNewline()
                    .append(traitLine(t));
            }
        }
        Component body = bodyBuilder.build();

        // 능동 특성 → 발동 버튼
        List<ActionButton> buttons = new ArrayList<>();
        for (TraitData t : pd.traits) {
            if (!t.active) continue;
            final String traitId = t.id;
            buttons.add(ActionButton.create(
                Component.text("⚡ " + t.name + " 발동", NamedTextColor.AQUA),
                buildTraitHover(t),
                200,
                DialogAction.customClick((v, a) -> onUseTrait.accept(traitId),
                    ClickCallback.Options.builder().uses(1).build())
            ));
        }
        // 능력치 안내 버튼 — 각 스탯이 무엇을 하는지 플레이어가 직접 확인
        buttons.add(ActionButton.create(
            Component.text("📖 능력치 안내", NamedTextColor.GREEN),
            Component.text("각 능력치가 무엇을 하는지 봅니다."),
            200,
            DialogAction.customClick((v, a) -> showStatGuide(player, pd),
                ClickCallback.Options.builder().uses(1).build())
        ));
        // (능동 특성이 없어도 위 안내 버튼이 있으므로 multiAction 빈 목록 문제 없음)

        ActionButton closeBtn = ActionButton.create(
            Component.text("닫기", TextColor.color(0xAAAAAA)),
            null, 100, null
        );

        // 시나리오 중 배역 이름(charName)이 있으면 우선 표시, 없으면 마인크래프트 이름
        String displayName = pd.charName.isEmpty() ? pd.name : pd.charName;
        Component title = Component.text()
            .append(Component.text(displayName, NamedTextColor.YELLOW))
            .append(pd.charName.isEmpty() ? Component.empty()
                : Component.text(" (" + pd.name + ")", NamedTextColor.DARK_GRAY))
            .append(Component.text("  —  캐릭터 정보", NamedTextColor.GRAY))
            .build();

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(title)
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, closeBtn, 1))
        );
        player.showDialog(dialog);
    }

    /** 능력치 안내 — 각 스탯이 무엇을 하는지 + 내 현재 수준 (플레이어 공개용; 내부 수치·확률·스포일러 없음). */
    public void showStatGuide(Player player, PlayerData pd) {
        var gb = Component.text()
            .append(Component.text("각 능력치가 무엇을 하는지 — 높을수록 그 분야에 강합니다.\n\n", NamedTextColor.GRAY))
            .append(statGuideLine("체력", "버티는 힘. 다치면 줄고 0이 되면 쓰러진다(쓰러진 채 또 맞으면 사망).", "hp", pd.hp[1]))
            .append(statGuideLine("정신력", "공포를 버티는 힘. 0이 되면 정신을 잠식당해 홀린다.", "san", pd.san[1]))
            .append(statGuideLine("근력", "물리적 힘 — 부수기·제압·버티기 등 힘쓰는 행동에 유리.", "str", pd.str))
            .append(statGuideLine("매력", "대인 영향력 — 높을수록 NPC가 더 쉽게 협조하고 설득이 잘 통한다.", "cha", pd.cha))
            .append(statGuideLine("행운", "운 — 결정적 위기에서 가까스로 살아남거나 유리한 우연이 따른다.", "luk", pd.luk))
            .append(statGuideLine("영감", "직감 — 위험·함정·거짓을 남보다 먼저 알아챈다.", "spr", pd.spr))
            .build();
        ActionButton closeBtn = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
        Dialog dialog = Dialog.create(d -> d.empty()
            .base(DialogBase.builder(Component.text("능력치 안내")).body(List.of(DialogBody.plainMessage(gb))).build())
            .type(DialogType.multiAction(List.of(closeBtn), null, 1)));
        player.showDialog(dialog);
    }

    private static Component statGuideLine(String name, String what, String key, int v) {
        return Component.text()
            .append(Component.text("● " + name + "  ", NamedTextColor.YELLOW))
            .append(Component.text(what + "\n", NamedTextColor.WHITE))
            .append(Component.text("   나의 수준: " + heipsys.trpg.model.PlayerData.statDesc(key, v) + "\n\n", NamedTextColor.GRAY))
            .build();
    }

    /** 기본값 대비 배역 보정을 표기한 스탯 컴포넌트 ("근력 4 §c(-1)") */
    private static Component statCell(String label, int current, int base) {
        var c = Component.text()
            .append(Component.text(label + " ", NamedTextColor.YELLOW))
            .append(Component.text(String.valueOf(current), NamedTextColor.WHITE));
        int d = current - base;
        if (d > 0) {
            c.append(Component.text(" (+" + d + ")", NamedTextColor.GREEN));
        } else if (d < 0) {
            c.append(Component.text(" (" + d + ")", NamedTextColor.RED));
        }
        String key = switch (label) {
            case "근력" -> "str"; case "매력" -> "cha";
            case "행운" -> "luk"; case "영감" -> "spr";
            default -> "";
        };
        String desc = key.isEmpty() ? "" : heipsys.trpg.model.PlayerData.statDesc(key, current);
        String hover = label + "\n기본 " + base + (d != 0 ? " → 현재 " + current + " (배역 보정)" : "")
                       + (desc.isEmpty() ? "" : "\n" + desc);
        c.hoverEvent(Component.text(hover, NamedTextColor.GRAY));
        return c.build();
    }

    /** 특성 한 줄 (등급+이름, 마우스 오버레이로 설명) */
    private static Component traitLine(TraitData t) {
        String prefix = t.active ? "  ⚡ " : "  ▸ ";
        return Component.text(prefix + "(" + t.grade + ") " + t.name,
                t.active ? NamedTextColor.AQUA : NamedTextColor.GRAY)
            .hoverEvent(buildTraitHover(t));
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 선택 다이얼로그
    // ──────────────────────────────────────────────────────────────

    public void showTraitSelection(Player player, List<TraitData> choices, boolean canRemove,
                                    Consumer<Integer> onSelect) {
        activeDialog.put(player.getUniqueId(), DialogState.TRAIT_SELECTION);
        traitChoices.put(player.getUniqueId(), new ArrayList<>(choices));

        List<ActionButton> buttons = new ArrayList<>();

        for (int i = 0; i < choices.size(); i++) {
            TraitData t = choices.get(i);
            final int idx = i + 1;
            boolean isEnhance = t.replacesId != null;

            String tooltip = (isEnhance ? "기존 특성을 강화하여 영구 획득합니다.\n\n" : "")
                + (t.description != null ? t.description : "");
            if (t.active && t.effect != null && !t.effect.isBlank()) {
                tooltip += (tooltip.isBlank() ? "" : "\n\n") + "§e[사용 효과] §f" + t.effect;
            }
            String sd = t.statDeltaPlain();
            if (!sd.isBlank()) tooltip += (tooltip.isBlank() ? "" : "\n\n") + "능력치 변화: " + sd;

            String label = (isEnhance ? "⬆ 강화 " : "✦ ") + "(" + t.grade + ") " + t.name
                + (sd.isBlank() ? "" : "  [" + sd + "]");
            buttons.add(ActionButton.create(
                Component.text(label, isEnhance ? NamedTextColor.GOLD : NamedTextColor.WHITE),
                tooltip.isBlank() ? null : Component.text(tooltip),
                200,
                DialogAction.customClick((v, a) -> onSelect.accept(idx),
                    ClickCallback.Options.builder().uses(1).build())
            ));
        }

        if (canRemove) {
            buttons.add(ActionButton.create(
                Component.text("✖ 기존 특성 제거", NamedTextColor.RED),
                Component.text("기존 특성 1개를 제거합니다."),
                150,
                DialogAction.customClick((v, a) -> onSelect.accept(0), // 0 = 제거 (강화 선택지로 인덱스가 늘어날 수 있어 0으로 분리)
                    ClickCallback.Options.builder().uses(1).build())
            ));
        }

        ActionButton cancelBtn = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)),
            Component.text("특성을 선택하지 않습니다."),
            80,
            null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("클리어 보상  —  특성 선택")).build())
            .type(DialogType.multiAction(buttons, cancelBtn, 1))
        );
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 제거 다이얼로그
    // ──────────────────────────────────────────────────────────────

    public void showTraitRemove(Player player, PlayerData pd, Consumer<Integer> onRemove) {
        activeDialog.put(player.getUniqueId(), DialogState.TRAIT_REMOVE);

        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < pd.traits.size(); i++) {
            TraitData t = pd.traits.get(i);
            final int idx = i;
            buttons.add(ActionButton.create(
                Component.text("✖ " + t.name + "  (" + t.grade + ")", NamedTextColor.RED),
                t.description != null && !t.description.isBlank()
                    ? Component.text(t.description)
                    : null,
                200,
                DialogAction.customClick((v, a) -> onRemove.accept(idx),
                    ClickCallback.Options.builder().uses(1).build())
            ));
        }

        ActionButton cancelBtn = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)),
            null, 80, null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("제거할 특성 선택")).build())
            .type(DialogType.multiAction(buttons, cancelBtn, 1))
        );
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  능동 특성 발동 다이얼로그
    // ──────────────────────────────────────────────────────────────

    /**
     * 능동 특성 발동 시 2-선택지 다이얼로그(순수 서술형 특성 전용).
     * onCommit: 특성에 모든걸 맡기기 (무난) / onInput: 행동 직접 입력 (리스크·리턴).
     * ★시스템 효과 능력(행운 주사위·탐색·버프·순간이동 등)은 이 창을 거치지 않고 즉시 발동한다(handleTraitUse가 분기).
     */
    public void showTraitActivation(Player player, TraitData trait, String zone,
                                     Runnable onCommit, Runnable onInput) {
        activeDialog.put(player.getUniqueId(), DialogState.TRAIT_ACTIVATION);

        String situationText = (zone != null && !zone.isBlank())
            ? zone + "에서 " + trait.name + " 특성을 발동합니다."
            : trait.name + " 특성을 발동합니다.";

        Component body = Component.text()
            .append(Component.text(situationText + "\n\n", NamedTextColor.WHITE))
            .append(Component.text("효과: ", NamedTextColor.GRAY))
            .append(Component.text(trait.effect != null ? trait.effect : "", NamedTextColor.AQUA))
            .append(Component.newline()).append(Component.newline())
            .append(Component.text("• 특성에 맡기기", NamedTextColor.GREEN))
            .append(Component.text(": 무난하게 효과가 발휘됩니다.\n", NamedTextColor.GRAY))
            .append(Component.text("• 행동 직접 입력", NamedTextColor.GOLD))
            .append(Component.text(": 행동에 따라 결과가 더 좋거나 나쁠 수 있습니다.", NamedTextColor.GRAY))
            .build();

        ActionButton commitBtn = ActionButton.create(
            Component.text("특성에 모든걸 맡기기", NamedTextColor.GREEN, TextDecoration.BOLD),
            Component.text("추가 행동 없이 특성 효과만으로 진행합니다.\n무난한 결과가 보장됩니다.", NamedTextColor.GRAY),
            200,
            DialogAction.customClick((v, a) -> {
                activeDialog.remove(player.getUniqueId());
                onCommit.run();
            }, ClickCallback.Options.builder().uses(1).build())
        );

        ActionButton inputBtn = ActionButton.create(
            Component.text("행동을 직접 입력하기", NamedTextColor.GOLD, TextDecoration.BOLD),
            Component.text("채팅으로 행동을 입력하면 특성과 함께 처리됩니다.\n더 좋은 결과를 노릴 수 있지만 역효과 위험도 있습니다.\n[리스크·리턴]", NamedTextColor.GRAY),
            200,
            DialogAction.customClick((v, a) -> {
                activeDialog.remove(player.getUniqueId());
                onInput.run();
            }, ClickCallback.Options.builder().uses(1).build())
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("[" + trait.name + "] 발동"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(List.of(commitBtn, inputBtn), null, 1))
        );
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  스테이지 종료 특성 성장 3선택지 다이얼로그
    // ──────────────────────────────────────────────────────────────

    /**
     * 스테이지 클리어 후 특성 성장 3선택지 다이얼로그.
     * 1: 내 특성 강화  2: 맵 특성 영구 획득  3: 신규 특성
     */
    public void showStageEndTraitChoice(Player player, TraitManager.StageEndChoices choices,
                                         String srcMyName, String srcMapName,
                                         Consumer<Integer> onSelect) {
        activeDialog.put(player.getUniqueId(), DialogState.STAGE_END_TRAIT);

        List<ActionButton> buttons = new ArrayList<>();

        if (choices.myUpgrade() != null) {
            TraitData t = choices.myUpgrade();
            String tooltip = "내 특성 강화\n"
                + (srcMyName != null && !srcMyName.isBlank() ? "기존: " + srcMyName + "\n" : "")
                + "강화 후: (" + t.grade + ") " + t.name + "\n"
                + (t.description != null && !t.description.isBlank() ? t.description + "\n" : "")
                + statLine(t)
                + cooldownLine(choices.srcMyTrait(), t)
                + "\n효과: " + t.effect;
            buttons.add(ActionButton.create(
                Component.text("⬆ [" + t.grade + "] " + t.name + statSuffix(t), NamedTextColor.AQUA, TextDecoration.BOLD),
                Component.text(tooltip, NamedTextColor.GRAY),
                200,
                DialogAction.customClick((v, a) -> {
                    activeDialog.remove(player.getUniqueId());
                    onSelect.accept(1);
                }, ClickCallback.Options.builder().uses(1).build())
            ));
        }

        if (choices.mapUpgrade() != null) {
            TraitData t = choices.mapUpgrade();
            String tooltip = "맵 특성 → 영구 획득\n"
                + (srcMapName != null && !srcMapName.isBlank() ? "기존: " + srcMapName + "\n" : "")
                + "강화 후: (" + t.grade + ") " + t.name + "\n"
                + (t.description != null && !t.description.isBlank() ? t.description + "\n" : "")
                + statLine(t)
                + cooldownLine(choices.srcMapTrait(), t)
                + "\n효과: " + t.effect;
            buttons.add(ActionButton.create(
                Component.text("✦ [" + t.grade + "] " + t.name + statSuffix(t), NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(tooltip, NamedTextColor.GRAY),
                200,
                DialogAction.customClick((v, a) -> {
                    activeDialog.remove(player.getUniqueId());
                    onSelect.accept(2);
                }, ClickCallback.Options.builder().uses(1).build())
            ));
        }

        if (choices.newTrait() != null) {
            TraitData t = choices.newTrait();
            String tooltip = "새로운 특성 획득\n"
                + "(" + t.grade + ") " + t.name + "\n"
                + (t.description != null && !t.description.isBlank() ? t.description + "\n" : "")
                + statLine(t)
                + "\n효과: " + t.effect;
            buttons.add(ActionButton.create(
                Component.text("✨ [" + t.grade + "] " + t.name + statSuffix(t), NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text(tooltip, NamedTextColor.GRAY),
                200,
                DialogAction.customClick((v, a) -> {
                    activeDialog.remove(player.getUniqueId());
                    onSelect.accept(3);
                }, ClickCallback.Options.builder().uses(1).build())
            ));
        }

        if (buttons.isEmpty()) return; // 선택지가 없으면 다이얼로그 표시 생략

        ActionButton cancelBtn = ActionButton.create(
            Component.text("나중에 결정", TextColor.color(0xAAAAAA)),
            Component.text("특성을 선택하지 않습니다."),
            80, null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("스테이지 클리어  —  특성 성장")).build())
            .type(DialogType.multiAction(buttons, cancelBtn, 1))
        );
        player.showDialog(dialog);
    }

    /** 툴팁용 능력치 변화 한 줄 (없으면 빈 문자열) */
    private static String statLine(TraitData t) {
        String sd = t.statDeltaPlain();
        return sd.isBlank() ? "" : "능력치 변화: " + sd + "\n";
    }

    /** 버튼 라벨 접미용 능력치 변화 (없으면 빈 문자열) */
    private static String statSuffix(TraitData t) {
        String sd = t.statDeltaPlain();
        return sd.isBlank() ? "" : "  [" + sd + "]";
    }

    /** 쿨다운 변화 한 줄 — 원본이 없으면 새 값만, 있으면 before→after 형식 */
    private static String cooldownLine(TraitData src, TraitData upgraded) {
        int newCd = upgraded != null ? upgraded.cooldownTurns : 0;
        if (src == null) {
            return newCd == 0 ? "" : "쿨다운: " + cdLabel(newCd) + "\n";
        }
        int oldCd = src.cooldownTurns;
        if (oldCd == newCd) return newCd == 0 ? "" : "쿨다운: " + cdLabel(newCd) + "\n";
        return "쿨다운: " + cdLabel(oldCd) + " → " + cdLabel(newCd) + "\n";
    }

    private static String cdLabel(int cd) {
        if (cd == 0)  return "없음";
        if (cd == -1) return "스테이지당 1회";
        return cd + "턴";
    }

    // ──────────────────────────────────────────────────────────────
    //  약도 구역 선택 다이얼로그
    // ──────────────────────────────────────────────────────────────

    /**
     * 구역 선택 다이얼로그.
     * 버튼: "전체 지도" (overview) + 구역별 버튼.
     * 선택 시 onSelect.accept(area) 호출 — area=null이면 전체/overview.
     */
    public void showMapSelector(Player player, List<String> areaNames, java.util.function.Consumer<String> onSelect) {
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(
            Component.text("전체 지도", NamedTextColor.GOLD),
            Component.text("모든 구역이 표시된 개요"), 200,
            DialogAction.customClick((v, a) -> onSelect.accept(null),
                ClickCallback.Options.builder().uses(1).build())));
        for (String area : areaNames) {
            buttons.add(ActionButton.create(
                Component.text(area, NamedTextColor.YELLOW),
                Component.text(area + " 구역 상세 지도"), 200,
                DialogAction.customClick((v, a) -> onSelect.accept(area),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton closeBtn = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("약도 구역 선택"))
                .body(List.of(DialogBody.plainMessage(
                    Component.text("볼 구역을 선택하세요.", NamedTextColor.GRAY))))
                .build())
            .type(DialogType.multiAction(buttons, closeBtn, 1)));
        player.showDialog(dialog);
    }

    /**
     * 소통수단 선언 다이얼로그(#177) — 도구가 없어 기록에서 여는 경로. 기본 4종은 필드로 즉시 확정된다.
     * @param currentLabel 현재 선언된 방식 라벨(없으면 "자동")
     * @param onPick 선택한 방식 키(""=자동, voice/text/signal/electronic)를 전달
     */
    public void showCommMethodPicker(Player player, String currentLabel, java.util.function.Consumer<String> onPick) {
        // ★'자동' 제거(#243)★ — 플레이어가 직접 고른다(대화=음성/전화=전자통신/필담/수신호). 엔진 자동선택 없음.
        String[][] opts = {
            {"voice",      "🗣 대화(말하기)",       "소리 내어 말합니다(대면). 소리가 위험한 곳이면 위험을 감수하는 선언입니다."},
            {"electronic", "📱 전화·전자통신",      "전화·무전·메신저 등 기기로 전합니다(기기·신호 필요)."},
            {"text",       "✍ 필담·글",           "종이·바닥 등에 글로 조용히 전합니다(소리 안 냄)."},
            {"signal",     "✋ 수신호·몸짓",        "손짓·몸짓으로 소리 없이 전합니다(상대가 볼 수 있어야 함)."},
        };
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] o : opts) {
            buttons.add(ActionButton.create(
                Component.text(o[1], NamedTextColor.GREEN),
                Component.text(o[2]), 220,
                DialogAction.customClick((v, a) -> onPick.accept(o[0]),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton closeBtn = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("소통수단 선언"))
                .body(List.of(DialogBody.plainMessage(Component.text()
                    .append(Component.text("현재: ", NamedTextColor.GRAY))
                    .append(Component.text(currentLabel == null || currentLabel.isEmpty() ? "자동" : currentLabel, NamedTextColor.WHITE))
                    .appendNewline()
                    .append(Component.text("원하는 소통 방식을 고르세요. 고르면 바로 적용됩니다(쓸 수 없는 방식만 막힘).", NamedTextColor.GRAY))
                    .build())))
                .build())
            .type(DialogType.multiAction(buttons, closeBtn, 1)));
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  기록 다이얼로그 (전체 대화 / 수집 정보 — 위치 이동 기준 페이지 넘김)
    // ──────────────────────────────────────────────────────────────

    private static final int LOG_LINES_PER_PAGE  = 10;
    private static final int INFO_LINES_PER_PAGE  = 12;

    private record RecordPage(String header, List<String> lines) {}

    /** 들여쓰기된 단서 줄을 식별하기 위한 접두사 (그룹 렌더 전용 내부 마커) */
    private static final String INFO_BULLET = "  • ";

    /**
     * 수집 정보 스냅샷을 '대상별 그룹' 형태의 평탄 줄 목록으로 만든다.
     * - infoGroups가 비어있지 않으면: 각 대상마다 헤더 줄 "[<대상>]" + 그 아래 "  • <단서>".
     *   대상 순서는 LinkedHashMap 삽입순(스냅샷 복사).
     * - infoGroups가 비어있으면(구버전 데이터 폴백): 기존 infoItems 평탄 목록.
     */
    private static List<String> buildInfoLines(PlayerData pd) {
        // 그룹 스냅샷 (삽입순 보존). infoGroups는 PlayerData.addInfo에서 자기 자신을 락으로 보호하므로
        // 동일 모니터(pd.infoGroups)로 스냅샷을 떠 맵·내부 리스트의 동시 변경으로부터 보호한다.
        Map<String, List<String>> groupSnap = new LinkedHashMap<>();
        synchronized (pd.infoGroups) {
            for (Map.Entry<String, List<String>> e : pd.infoGroups.entrySet()) {
                List<String> v = e.getValue();
                groupSnap.put(e.getKey(), v == null ? new ArrayList<>() : new ArrayList<>(v));
            }
        }
        if (groupSnap.isEmpty()) {
            // 폴백: 구버전 평탄 데이터
            synchronized (pd.infoItems) { return new ArrayList<>(pd.infoItems); }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : groupSnap.entrySet()) {
            out.add("[" + e.getKey() + "]"); // 대상 헤더는 짧게 (예: [카메라])
            for (String clue : e.getValue()) {
                out.add(INFO_BULLET + clue);
            }
        }
        return out;
    }

    /** 전체 대화 / 정보만 보기 선택 화면 */
    public void showRecordChoice(Player player, PlayerData pd) {
        List<String> logSnap, infoSnap;
        synchronized (pd.narrativeLog) { logSnap  = new ArrayList<>(pd.narrativeLog); }
        infoSnap = buildInfoLines(pd);
        // 헤더 줄([...])을 제외한 실제 단서 건수
        long infoCount = infoSnap.stream().filter(l -> l.startsWith(INFO_BULLET)).count();
        if (infoCount == 0) infoCount = infoSnap.size(); // 폴백(평탄 목록)일 때는 전체 줄 수
        long logCount = logSnap.stream().filter(l -> !l.startsWith(PlayerData.MOVE_TAG)).count();

        Component body = Component.text()
            .append(Component.text("무엇을 확인할까요?", NamedTextColor.WHITE)).appendNewline().appendNewline()
            .append(Component.text("전체 대화  ", NamedTextColor.YELLOW))
            .append(Component.text("지나간 모든 서술·행동 (" + logCount + "줄)", NamedTextColor.GRAY)).appendNewline()
            .append(Component.text("수집 정보  ", NamedTextColor.AQUA))
            .append(Component.text("정보가 담긴 내용만 추린 목록 (" + infoCount + "건)", NamedTextColor.GRAY))
            .build();

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(
            Component.text("📖 전체 대화 보기", NamedTextColor.YELLOW),
            Component.text("지나간 모든 기록을 페이지로 봅니다."), 160,
            DialogAction.customClick((v, a) -> showRecordPages(player, pd, false, logSnap, 0),
                ClickCallback.Options.builder().uses(1).build())));
        buttons.add(ActionButton.create(
            Component.text("🔍 정보만 보기", NamedTextColor.AQUA),
            Component.text("정보가 포함된 내용만 모아 봅니다."), 160,
            DialogAction.customClick((v, a) -> showRecordPages(player, pd, true, infoSnap, 0),
                ClickCallback.Options.builder().uses(1).build())));
        if (importantInfoOpener != null) {
            buttons.add(ActionButton.create(
                Component.text("⭐ 중요 정보", NamedTextColor.GOLD),
                Component.text("전화번호 · 능력으로 밝혀낸 사실을 모아 봅니다."), 160,
                DialogAction.customClick((v, a) -> importantInfoOpener.accept(player),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        if (commMethodOpener != null) {
            buttons.add(ActionButton.create(
                Component.text("＠ 소통수단 변경", NamedTextColor.GREEN),
                Component.text("말하기 · 필담 · 수신호 등 소통 방식을 선언합니다(바로 적용)."), 160,
                DialogAction.customClick((v, a) -> commMethodOpener.accept(player),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        if (moveOpener != null) {
            buttons.add(ActionButton.create(
                Component.text("🚶 이동", NamedTextColor.BLUE),
                Component.text("아는 곳으로 이동을 선언합니다(먼 곳도 경유해 감, 이동마다 한 턴 소모)."), 160,
                DialogAction.customClick((v, a) -> moveOpener.accept(player),
                    ClickCallback.Options.builder().uses(1).build())));
        }

        ActionButton closeBtn = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("기록 열람"))
                .body(List.of(DialogBody.plainMessage(body))).build())
            .type(DialogType.multiAction(buttons, closeBtn, 1)));
        player.showDialog(dialog);
    }

    /** 전체 대화 기록 바로 열기 */
    public void showRecordLog(Player player, PlayerData pd) {
        List<String> snap; synchronized (pd.narrativeLog) { snap = new ArrayList<>(pd.narrativeLog); }
        showRecordPages(player, pd, false, snap, 0);
    }

    /** 수집 정보 바로 열기 */
    public void showRecordInfo(Player player, PlayerData pd) {
        List<String> snap = buildInfoLines(pd);
        showRecordPages(player, pd, true, snap, 0);
    }

    private void showRecordPages(Player player, PlayerData pd, boolean infoMode, List<String> data, int page) {
        List<RecordPage> pages = infoMode ? paginateInfo(data) : paginateLog(data);

        if (pages.isEmpty()) {
            ActionButton back = ActionButton.create(Component.text("◀ 목록", NamedTextColor.WHITE), null, 100,
                DialogAction.customClick((v, a) -> showRecordChoice(player, pd),
                    ClickCallback.Options.builder().uses(1).build()));
            ActionButton close = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
            Dialog empty = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(infoMode ? "수집 정보" : "전체 대화"))
                    .body(List.of(DialogBody.plainMessage(Component.text(
                        infoMode ? "아직 수집된 정보가 없습니다." : "아직 기록된 내용이 없습니다.", NamedTextColor.GRAY))))
                    .build())
                .type(DialogType.multiAction(List.of(back), close, 1)));
            player.showDialog(empty);
            return;
        }

        final int p = Math.max(0, Math.min(page, pages.size() - 1));
        RecordPage pg = pages.get(p);

        var bodyB = Component.text();
        if (!pg.header().isBlank())
            bodyB.append(Component.text("▸ " + pg.header(), NamedTextColor.GOLD)).appendNewline().appendNewline();
        if (pg.lines().isEmpty()) {
            bodyB.append(Component.text("(내용 없음)", NamedTextColor.DARK_GRAY));
        } else {
            boolean first = true;
            for (String line : pg.lines()) {
                if (!first) bodyB.appendNewline();
                first = false;
                bodyB.append(colorRecordLine(line));
            }
        }
        Component body = bodyB.build();
        String title = (infoMode ? "수집 정보" : "전체 대화") + "  " + (p + 1) + "/" + pages.size();

        List<ActionButton> nav = new ArrayList<>();
        if (p > 0) nav.add(ActionButton.create(Component.text("◀ 이전", NamedTextColor.WHITE), null, 70,
            DialogAction.customClick((v, a) -> showRecordPages(player, pd, infoMode, data, p - 1),
                ClickCallback.Options.builder().uses(1).build())));
        nav.add(ActionButton.create(Component.text("목록", NamedTextColor.GRAY), null, 70,
            DialogAction.customClick((v, a) -> showRecordChoice(player, pd),
                ClickCallback.Options.builder().uses(1).build())));
        if (p < pages.size() - 1) nav.add(ActionButton.create(Component.text("다음 ▶", NamedTextColor.WHITE), null, 70,
            DialogAction.customClick((v, a) -> showRecordPages(player, pd, infoMode, data, p + 1),
                ClickCallback.Options.builder().uses(1).build())));

        ActionButton closeBtn = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text(title))
                .body(List.of(DialogBody.plainMessage(body))).build())
            .type(DialogType.multiAction(nav, closeBtn, 3)));
        player.showDialog(dialog);
    }

    /**
     * 중요 정보 화면: 전화번호(내/아는 사람·NPC) + 능력으로 밝혀낸 사실(keyFacts).
     * 내용은 TRPGGameManager가 합성해 넘긴다(교차 플레이어 데이터 필요). 뒤로가기는 기록 열람 메뉴로.
     */
    public void showImportantInfo(Player player, PlayerData pd, String myNumber,
                                   List<String> phoneLines, List<String> factLines) {
        final int FACTS_SHOWN = 18;
        var bb = Component.text();
        bb.append(Component.text("📞 내 연락처: ", NamedTextColor.GOLD))
          .append(Component.text((myNumber == null || myNumber.isBlank()) ? "미발급" : myNumber, NamedTextColor.WHITE))
          .appendNewline().appendNewline();

        bb.append(Component.text("── 아는 번호 ──", NamedTextColor.YELLOW)).appendNewline();
        if (phoneLines == null || phoneLines.isEmpty()) {
            bb.append(Component.text("(아직 아는 번호가 없습니다)", NamedTextColor.DARK_GRAY)).appendNewline();
        } else {
            for (String ln : phoneLines)
                bb.append(Component.text("• ", NamedTextColor.GRAY))
                  .append(Component.text(ln, NamedTextColor.WHITE)).appendNewline();
        }
        bb.appendNewline();

        bb.append(Component.text("── 능력으로 밝혀낸 사실 ──", NamedTextColor.AQUA)).appendNewline();
        if (factLines == null || factLines.isEmpty()) {
            bb.append(Component.text("(아직 능력으로 알아낸 정보가 없습니다)", NamedTextColor.DARK_GRAY)).appendNewline();
        } else {
            int from = Math.max(0, factLines.size() - FACTS_SHOWN); // 최근 것 우선
            if (from > 0)
                bb.append(Component.text("(이전 " + from + "건 생략, 최근 " + FACTS_SHOWN + "건)", NamedTextColor.DARK_GRAY)).appendNewline();
            for (int i = from; i < factLines.size(); i++)
                bb.append(Component.text("• ", NamedTextColor.GRAY))
                  .append(Component.text(factLines.get(i), NamedTextColor.WHITE)).appendNewline();
        }

        ActionButton back = ActionButton.create(Component.text("◀ 목록", NamedTextColor.WHITE), null, 100,
            DialogAction.customClick((v, a) -> showRecordChoice(player, pd),
                ClickCallback.Options.builder().uses(1).build()));
        ActionButton close = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("⭐ 중요 정보"))
                .body(List.of(DialogBody.plainMessage(bb.build()))).build())
            .type(DialogType.multiAction(List.of(back), close, 1)));
        player.showDialog(dialog);
    }

    /** 대화 로그를 위치 이동(MOVE_TAG) 지점·줄 수 한도로 페이지 분할 */
    private static List<RecordPage> paginateLog(List<String> log) {
        List<RecordPage> pages = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        String header = "";
        for (String line : log) {
            if (line.startsWith(PlayerData.MOVE_TAG)) {
                if (!cur.isEmpty()) { pages.add(new RecordPage(header, cur)); cur = new ArrayList<>(); }
                header = line.substring(PlayerData.MOVE_TAG.length());
                continue;
            }
            cur.add(line);
            if (cur.size() >= LOG_LINES_PER_PAGE) { pages.add(new RecordPage(header, cur)); cur = new ArrayList<>(); }
        }
        if (!cur.isEmpty()) pages.add(new RecordPage(header, cur));
        return pages;
    }

    /**
     * 정보 목록을 줄 수 한도로 페이지 분할.
     * 그룹 헤더 줄("[...]")이 페이지 맨 끝에 홀로 남지 않도록,
     * 한도 도달 시점의 줄이 헤더이면 다음 페이지로 넘긴다.
     */
    private static List<RecordPage> paginateInfo(List<String> items) {
        List<RecordPage> pages = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String line : items) {
            // 한도에 도달했는데 다음 줄이 헤더(들여쓰기 아님 = 그룹 시작)면 여기서 페이지를 끊는다.
            if (cur.size() >= INFO_LINES_PER_PAGE && !line.startsWith(INFO_BULLET)) {
                pages.add(new RecordPage("", cur));
                cur = new ArrayList<>();
            }
            cur.add(line);
            // 들여쓰기 단서로 한도를 넘긴 경우엔 그대로 끊는다(헤더가 끝줄이 되지 않음).
            if (cur.size() >= INFO_LINES_PER_PAGE && line.startsWith(INFO_BULLET)) {
                pages.add(new RecordPage("", cur));
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) pages.add(new RecordPage("", cur));
        return pages;
    }

    private static Component colorRecordLine(String line) {
        String t = line.strip();
        // CODE-18: '*'로 시작하는 주석 줄은 회색
        if (t.startsWith("*"))            return Component.text(line, NamedTextColor.GRAY);
        // CODE-13: 대상별 그룹 헤더 / 들여쓴 단서
        if (line.startsWith(INFO_BULLET)) return Component.text(line, NamedTextColor.WHITE);
        if (t.startsWith("[") && t.endsWith("]") && !t.startsWith("[행동"))
            return Component.text(line, NamedTextColor.GOLD, TextDecoration.BOLD);
        if (line.startsWith("[행동"))      return Component.text(line, NamedTextColor.YELLOW);
        if (line.startsWith("•"))          return Component.text(line, NamedTextColor.AQUA);
        return Component.text(line, NamedTextColor.WHITE);
    }

    // ──────────────────────────────────────────────────────────────
    //  채팅 입력 유도 다이얼로그 (ai_query / area_scan / link_ally)
    // ──────────────────────────────────────────────────────────────

    /** ai_query — 질문 입력 안내 다이얼로그. 확인 클릭 시 onConfirm 호출(이후 채팅 대기). */
    public void showQueryInput(Player player, TraitData trait, int remaining, Runnable onConfirm) {
        Component body = Component.text()
            .append(Component.text("GM에게 직접 질문할 수 있습니다.\n\n", NamedTextColor.WHITE))
            .append(Component.text("효과: ", NamedTextColor.GRAY))
            .append(Component.text(trait.effect != null && !trait.effect.isBlank() ? trait.effect : "GM에게 질문", NamedTextColor.AQUA))
            .appendNewline().appendNewline()
            .append(Component.text("남은 횟수: ", NamedTextColor.YELLOW))
            .append(Component.text(remaining + "회", NamedTextColor.WHITE))
            .appendNewline().appendNewline()
            .append(Component.text("확인을 누르면 채팅창에 질문을 입력할 수 있습니다.", NamedTextColor.GRAY))
            .append(Component.text("\n질문이 구체적일수록 더 명확한 답을 받습니다.", NamedTextColor.DARK_GRAY))
            .build();

        ActionButton confirmBtn = ActionButton.create(
            Component.text("✎ 질문 입력하기", NamedTextColor.AQUA, TextDecoration.BOLD),
            Component.text("확인 후 채팅창에 질문을 입력하세요.", NamedTextColor.GRAY),
            200,
            DialogAction.customClick((v, a) -> onConfirm.run(),
                ClickCallback.Options.builder().uses(1).build())
        );
        ActionButton cancelBtn = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)), null, 100, null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("[" + trait.name + "] 질문하기"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(List.of(confirmBtn), cancelBtn, 1))
        );
        player.showDialog(dialog);
    }

    /** area_scan — 탐색 목표 입력 안내 다이얼로그. */
    public void showScanInput(Player player, TraitData trait, String scopeStr, int remaining, Runnable onConfirm) {
        Component body = Component.text()
            .append(Component.text("탐색 범위: ", NamedTextColor.GRAY))
            .append(Component.text(scopeStr, NamedTextColor.AQUA))
            .appendNewline()
            .append(Component.text("남은 횟수: ", NamedTextColor.YELLOW))
            .append(Component.text(remaining + "회", NamedTextColor.WHITE))
            .appendNewline().appendNewline()
            .append(Component.text("효과: ", NamedTextColor.GRAY))
            .append(Component.text(trait.effect != null && !trait.effect.isBlank() ? trait.effect : "구역 탐색", NamedTextColor.WHITE))
            .appendNewline().appendNewline()
            .append(Component.text("확인 후 채팅창에 탐색 목표를 입력하세요.", NamedTextColor.GRAY))
            .append(Component.text("\n예: '수상한 냄새', '숨겨진 출구', '다른 사람의 흔적'", NamedTextColor.DARK_GRAY))
            .build();

        ActionButton confirmBtn = ActionButton.create(
            Component.text("🔍 탐색 시작", NamedTextColor.AQUA, TextDecoration.BOLD),
            Component.text("확인 후 채팅창에 탐색 목표를 입력하세요.", NamedTextColor.GRAY),
            200,
            DialogAction.customClick((v, a) -> onConfirm.run(),
                ClickCallback.Options.builder().uses(1).build())
        );
        ActionButton cancelBtn = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)), null, 100, null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("[" + trait.name + "] 탐색"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(List.of(confirmBtn), cancelBtn, 1))
        );
        player.showDialog(dialog);
    }

    /** link_ally (depth≥2) — 감지 목표 입력 안내 다이얼로그. */
    public void showLinkAllyInput(Player player, TraitData trait, String depthStr, Runnable onConfirm) {
        Component body = Component.text()
            .append(Component.text("감지 범위: ", NamedTextColor.GRAY))
            .append(Component.text(depthStr, NamedTextColor.GREEN))
            .appendNewline().appendNewline()
            .append(Component.text("효과: ", NamedTextColor.GRAY))
            .append(Component.text(trait.effect != null && !trait.effect.isBlank() ? trait.effect : "아군 감지", NamedTextColor.WHITE))
            .appendNewline().appendNewline()
            .append(Component.text("확인 후 채팅창에 감지 목표를 입력하세요.", NamedTextColor.GRAY))
            .append(Component.text("\n예: '가장 가까운 아군의 위치', '다친 아군이 있는지'", NamedTextColor.DARK_GRAY))
            .build();

        ActionButton confirmBtn = ActionButton.create(
            Component.text("👁 감지 시작", NamedTextColor.GREEN, TextDecoration.BOLD),
            Component.text("확인 후 채팅창에 감지 목표를 입력하세요.", NamedTextColor.GRAY),
            200,
            DialogAction.customClick((v, a) -> onConfirm.run(),
                ClickCallback.Options.builder().uses(1).build())
        );
        ActionButton cancelBtn = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)), null, 100, null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text("[" + trait.name + "] 아군 감지"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(List.of(confirmBtn), cancelBtn, 1))
        );
        player.showDialog(dialog);
    }

    /**
     * 범용 텍스트 입력 다이얼로그 — 다이얼로그 안의 입력칸에 직접 입력하고 확인하면 onSubmit(입력값)을 호출한다.
     * 채팅창 입력(+탭 자동완성)을 대체한다. 취소하거나 빈 값이면 onSubmit은 호출되지 않는다.
     */
    public void showTextInput(Player player, Component title, Component body,
                              String inputLabel, Component confirmLabel, Consumer<String> onSubmit) {
        final String KEY = "v";
        DialogInput input = DialogInput.text(KEY, Component.text(inputLabel))
            .labelVisible(true).width(300).maxLength(150).build();
        ActionButton confirmBtn = ActionButton.create(
            confirmLabel, null, 250,
            DialogAction.customClick((view, audience) -> {
                String val = (view == null) ? null : view.getText(KEY);
                if (val != null && !val.isBlank()) onSubmit.accept(val.trim());
            }, ClickCallback.Options.builder().uses(1).build())
        );
        ActionButton cancelBtn = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)), null, 100, null
        );
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(title)
                .body(List.of(DialogBody.plainMessage(body)))
                .inputs(List.of(input))
                .build())
            .type(DialogType.multiAction(List.of(confirmBtn), cancelBtn, 1))
        );
        player.showDialog(dialog);
    }

    /** 선택지 특성용: 후보 선택지를 ★다이얼로그 버튼★으로 제시하고 고른 인덱스를 콜백한다. */
    public void showActionChoices(Player player, Component title, Component body,
                                  List<String> choiceTexts, java.util.function.IntConsumer onPick) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < choiceTexts.size(); i++) {
            final int idx = i;
            buttons.add(ActionButton.create(
                Component.text(choiceTexts.get(i), NamedTextColor.AQUA), null, 300,
                DialogAction.customClick((v, a) -> onPick.accept(idx),
                    ClickCallback.Options.builder().uses(1).build())));
        }
        ActionButton cancelBtn = ActionButton.create(
            Component.text("취소", TextColor.color(0xAAAAAA)), null, 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(title)
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, cancelBtn, 1))
        );
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 오버레이 컴포넌트 빌더
    // ──────────────────────────────────────────────────────────────

    public static Component buildTraitHover(TraitData t) {
        var builder = Component.text()
            .append(Component.text("(" + t.grade + ") " + t.name + "\n", NamedTextColor.WHITE));

        if (t.description != null && !t.description.isBlank()) {
            builder.append(Component.text(t.description, NamedTextColor.GRAY));
        }

        if (t.active && t.effect != null && !t.effect.isBlank()) {
            builder.append(Component.newline())
                .append(Component.newline())
                .append(Component.text("[사용 효과]  ", NamedTextColor.YELLOW))
                .append(Component.text(t.effect, NamedTextColor.WHITE));
        }

        // 능동 특성: 쿨다운·제약·남은 사용 횟수 표시 (내 정보에서 확인)
        if (t.active) {
            StringBuilder meta = new StringBuilder();
            if (t.cooldownTurns == -1) {
                meta.append(t.usedThisStage > 0 ? "이번 스테이지 사용 완료" : "스테이지당 1회");
            } else {
                if (t.remainingCooldown > 0 && t.remainingCooldown != Integer.MAX_VALUE)
                    meta.append("쿨다운 ").append(t.remainingCooldown).append("턴 남음");
                else if (t.cooldownTurns > 0)
                    meta.append("쿨다운 ").append(t.cooldownTurns).append("턴");
                int maxUses = SystemTraitRegistry.maxUsesPerStage(t);
                if (maxUses > 0) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append("남은 사용 ").append(Math.max(0, maxUses - t.usedThisStage))
                        .append("/").append(maxUses).append("회");
                }
            }
            if (meta.length() > 0) {
                builder.append(Component.newline())
                    .append(Component.text("[제약]  ", NamedTextColor.GOLD))
                    .append(Component.text(meta.toString(), NamedTextColor.GRAY));
            }
        }

        return builder.build();
    }

    // ──────────────────────────────────────────────────────────────
    //  HP / SAN 백분율 표시 헬퍼
    //  굴린 최대 스탯이 항상 100을 기준으로 환산된다.
    //  형식: round(현재/최대 * 100) / 100 (원본 스탯)
    //  예: 최대=3 → 만피 "100/100(3)", 1피해 후 "67/100(3)", 2피해 후 "33/100(3)"
    // ──────────────────────────────────────────────────────────────

    public static String hpDisplay(int[] stat) {
        int max = Math.max(1, stat[1]);
        int pct = (int) Math.round((double) stat[0] / max * 100.0);
        return pct + "/100(" + stat[1] + ")";
    }

    /** 0-100 환산 퍼센트 값만 반환 (피해량 계산 등에 사용) */
    public static int toPercent(int current, int max) {
        if (max <= 0) return 0;
        return (int) Math.round((double) current / max * 100.0);
    }

    // ──────────────────────────────────────────────────────────────
    //  엔딩 해설 다이얼로그
    // ──────────────────────────────────────────────────────────────

    public record EndingSection(String title, List<String> lines) {}

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /**
     * 엔딩 해설 한 줄에 색을 입힌다.
     * - § 색코드가 포함된 줄은 그대로 파싱(buildEndingPages에서 색을 직접 지정 가능).
     * - 그 외에는 구조(구분선·불릿·단계·라벨)에 따라 자동으로 색을 부여해 단색을 피한다.
     */
    private static Component colorizeEndingLine(String line) {
        if (line == null || line.isEmpty()) return Component.empty();
        if (line.indexOf('§') >= 0) return LEGACY.deserialize(line); // § 코드 우선
        String t = line.strip();
        if (t.startsWith("*"))                                                           // CODE-18: '*' 주석 줄 = 회색
            return Component.text(line, NamedTextColor.GRAY);
        if (t.startsWith("──") || t.endsWith("──"))
            return Component.text(line, TextColor.color(0xFFCC66), TextDecoration.BOLD); // 구분선 = 금색
        if (t.startsWith("▸") || t.startsWith("•"))
            return Component.text(line, NamedTextColor.AQUA);                            // 불릿 = 청록
        if (t.startsWith("·") || t.startsWith("- "))
            return Component.text(line, NamedTextColor.GRAY);                            // 하위 항목 = 회색
        if (t.matches("^\\[\\d+단계\\].*"))
            return Component.text(line, TextColor.color(0xFFB347));                      // 타임라인 단계 = 주황
        int colon = t.indexOf(':');
        if (colon > 0 && colon <= 6)
            return Component.text(line, NamedTextColor.YELLOW);                          // 짧은 라벨: 값
        // 본문(후일담 등 서사 줄): 인게임 스토리와 동일한 서식 적용
        // (화자[이름]=주황, "대사"=청록, <연출>=노랑, *주석*=회색) → 단색 벽글 가독성 개선
        return LEGACY.deserialize(NarrativeDelivery.format(line));
    }

    public void showEndingDialog(Player player, List<EndingSection> sections, int page) {
        if (sections.isEmpty()) return;
        final int p = Math.max(0, Math.min(page, sections.size() - 1));
        EndingSection sec = sections.get(p);

        var bodyB = Component.text();
        boolean first = true;
        for (String line : sec.lines()) {
            if (!first) bodyB.appendNewline();
            first = false;
            bodyB.append(colorizeEndingLine(line));
        }
        Component body = bodyB.build();
        String title = "엔딩 해설  " + (p + 1) + "/" + sections.size() + "  [" + sec.title() + "]";

        List<ActionButton> nav = new ArrayList<>();
        if (p > 0) nav.add(ActionButton.create(
            Component.text("◀ 이전", NamedTextColor.WHITE), null, 70,
            DialogAction.customClick((v, a) -> showEndingDialog(player, sections, p - 1),
                ClickCallback.Options.builder().uses(1).build())));
        if (p < sections.size() - 1) nav.add(ActionButton.create(
            Component.text("다음 ▶", NamedTextColor.WHITE), null, 70,
            DialogAction.customClick((v, a) -> showEndingDialog(player, sections, p + 1),
                ClickCallback.Options.builder().uses(1).build())));

        ActionButton closeBtn = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text(title))
                .body(List.of(DialogBody.plainMessage(body))).build())
            .type(DialogType.multiAction(nav, closeBtn, 3)));
        player.showDialog(dialog);
    }

    /**
     * 단일 페이지 정보 다이얼로그(닫기 버튼만). /trpg status 등 짧은 정보 표시용.
     * 각 줄은 colorizeEndingLine으로 서식 적용(§ 코드가 있으면 그대로 렌더).
     */
    public void showStatusDialog(Player player, String title, List<String> lines) {
        var bodyB = Component.text();
        boolean first = true;
        for (String line : lines) {
            if (!first) bodyB.appendNewline();
            first = false;
            bodyB.append(colorizeEndingLine(line));
        }
        Component body = bodyB.build();
        ActionButton closeBtn = ActionButton.create(Component.text("닫기", TextColor.color(0xAAAAAA)), null, 100, null);
        // multiAction은 actions가 비면 예외 → 닫기 버튼을 actions에 넣고 exit는 null(클릭 시 기본 동작으로 닫힘).
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(Component.text(title))
                .body(List.of(DialogBody.plainMessage(body))).build())
            .type(DialogType.multiAction(List.of(closeBtn), null, 1)));
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  상태 조회 / 초기화
    // ──────────────────────────────────────────────────────────────

    public boolean hasActiveDialog(Player player)           { return activeDialog.containsKey(player.getUniqueId()); }
    public DialogState getDialogState(Player player)        { return activeDialog.get(player.getUniqueId()); }
    public void clearDialog(Player player)                  { activeDialog.remove(player.getUniqueId()); traitChoices.remove(player.getUniqueId()); }
    public List<TraitData> getTraitChoices(Player player)  { return traitChoices.getOrDefault(player.getUniqueId(), Collections.emptyList()); }
}
