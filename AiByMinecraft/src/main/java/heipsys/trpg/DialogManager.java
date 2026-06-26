package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

    // ──────────────────────────────────────────────────────────────
    //  캐릭터 시트 + 주사위 확인
    // ──────────────────────────────────────────────────────────────

    public void showCharacterSheet(Player player, PlayerData pd, int roomNumber, int attempt,
                                    Runnable onConfirm, Runnable onReroll) {
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
            .append(Component.text(pd.age + "세  ·  " + pd.job, NamedTextColor.WHITE))
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

        ActionButton cancelBtn = ActionButton.create(
            Component.text("닫기", TextColor.color(0xAAAAAA)),
            Component.text("확정하지 않으면 게임이 진행되지 않습니다."),
            100,
            null
        );

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("캐릭터 생성  |  스테이지 " + roomNumber + " · " + attempt + "회차"))
                .body(List.of(DialogBody.plainMessage(body)))
                .build())
            .type(DialogType.multiAction(buttons, cancelBtn, 2))
        );
        player.showDialog(dialog);
    }

    // ──────────────────────────────────────────────────────────────
    //  세션 시작 — AI 품질 선택
    // ──────────────────────────────────────────────────────────────

    /** 게임 시작 전 GM AI 품질(표준/고품질)을 선택하는 다이얼로그 */
    public void showQualityChoice(Player player, Runnable onStandard, Runnable onHigh) {
        Component body = Component.text()
            .append(Component.text("GM AI 품질을 선택하세요.", NamedTextColor.WHITE))
            .appendNewline().appendNewline()
            .append(Component.text("표준  ", NamedTextColor.YELLOW))
            .append(Component.text("빠르고 저렴 · 일반 플레이용", NamedTextColor.GRAY))
            .appendNewline()
            .append(Component.text("고품질  ", NamedTextColor.AQUA))
            .append(Component.text("더 풍부하고 일관된 서술 · 응답이 느리고 비쌈", NamedTextColor.GRAY))
            .build();

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(
            Component.text("표준 모드", NamedTextColor.YELLOW),
            Component.text("빠르고 저렴한 기본 모델로 진행합니다."),
            150,
            DialogAction.customClick((v, a) -> onStandard.run(),
                ClickCallback.Options.builder().uses(1).build())
        ));
        buttons.add(ActionButton.create(
            Component.text("고품질 모드", NamedTextColor.AQUA),
            Component.text("더 똑똑한 모델로 진행합니다.\n응답이 느리고 토큰 비용이 큽니다."),
            150,
            DialogAction.customClick((v, a) -> onHigh.run(),
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
    public void showCharacterInfo(Player player, PlayerData pd, Consumer<String> onUseTrait) {
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
            .append(Component.text(pd.age + "세  ·  " + pd.job, NamedTextColor.WHITE))
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
        // 능동 특성이 없으면 비활성 안내 버튼 (multiAction이 빈 목록을 받지 않도록)
        if (buttons.isEmpty()) {
            buttons.add(ActionButton.create(
                Component.text("발동 가능한 능동 특성 없음", NamedTextColor.DARK_GRAY),
                Component.text("능동(⚡) 특성을 보유하면 여기서 발동할 수 있습니다."),
                200,
                null
            ));
        }

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

            String label = (isEnhance ? "⬆ 강화 " : "✦ ") + "(" + t.grade + ") " + t.name;
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
     * 능동 특성 발동 시 2-선택지 다이얼로그.
     * onCommit: 특성에 모든걸 맡기기 (무난)
     * onInput: 행동 직접 입력 (리스크·리턴)
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
                + cooldownLine(choices.srcMyTrait(), t)
                + "\n효과: " + t.effect;
            buttons.add(ActionButton.create(
                Component.text("⬆ 내 특성 강화  [" + t.grade + "] " + t.name, NamedTextColor.AQUA, TextDecoration.BOLD),
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
                + cooldownLine(choices.srcMapTrait(), t)
                + "\n효과: " + t.effect;
            buttons.add(ActionButton.create(
                Component.text("✦ 맵 특성 가져가기  [" + t.grade + "] " + t.name, NamedTextColor.GOLD, TextDecoration.BOLD),
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
                + "\n효과: " + t.effect;
            buttons.add(ActionButton.create(
                Component.text("✨ 새로운 특성  [" + t.grade + "] " + t.name, NamedTextColor.GREEN, TextDecoration.BOLD),
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
    //  기록 다이얼로그 (전체 대화 / 수집 정보 — 위치 이동 기준 페이지 넘김)
    // ──────────────────────────────────────────────────────────────

    private static final int LOG_LINES_PER_PAGE  = 10;
    private static final int INFO_LINES_PER_PAGE  = 12;

    private record RecordPage(String header, List<String> lines) {}

    /** 전체 대화 / 정보만 보기 선택 화면 */
    public void showRecordChoice(Player player, PlayerData pd) {
        List<String> logSnap, infoSnap;
        synchronized (pd.narrativeLog) { logSnap  = new ArrayList<>(pd.narrativeLog); }
        synchronized (pd.infoItems)    { infoSnap = new ArrayList<>(pd.infoItems); }
        long logCount = logSnap.stream().filter(l -> !l.startsWith(PlayerData.MOVE_TAG)).count();

        Component body = Component.text()
            .append(Component.text("무엇을 확인할까요?", NamedTextColor.WHITE)).appendNewline().appendNewline()
            .append(Component.text("전체 대화  ", NamedTextColor.YELLOW))
            .append(Component.text("지나간 모든 서술·행동 (" + logCount + "줄)", NamedTextColor.GRAY)).appendNewline()
            .append(Component.text("수집 정보  ", NamedTextColor.AQUA))
            .append(Component.text("정보가 담긴 내용만 추린 목록 (" + infoSnap.size() + "건)", NamedTextColor.GRAY))
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
        List<String> snap; synchronized (pd.infoItems) { snap = new ArrayList<>(pd.infoItems); }
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

    /** 정보 목록을 줄 수 한도로 단순 페이지 분할 */
    private static List<RecordPage> paginateInfo(List<String> items) {
        List<RecordPage> pages = new ArrayList<>();
        for (int i = 0; i < items.size(); i += INFO_LINES_PER_PAGE) {
            pages.add(new RecordPage("",
                new ArrayList<>(items.subList(i, Math.min(items.size(), i + INFO_LINES_PER_PAGE)))));
        }
        return pages;
    }

    private static Component colorRecordLine(String line) {
        if (line.startsWith("[행동")) return Component.text(line, NamedTextColor.YELLOW);
        if (line.startsWith("•"))     return Component.text(line, NamedTextColor.AQUA);
        return Component.text(line, NamedTextColor.WHITE);
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
    //  상태 조회 / 초기화
    // ──────────────────────────────────────────────────────────────

    public boolean hasActiveDialog(Player player)           { return activeDialog.containsKey(player.getUniqueId()); }
    public DialogState getDialogState(Player player)        { return activeDialog.get(player.getUniqueId()); }
    public void clearDialog(Player player)                  { activeDialog.remove(player.getUniqueId()); traitChoices.remove(player.getUniqueId()); }
    public List<TraitData> getTraitChoices(Player player)  { return traitChoices.getOrDefault(player.getUniqueId(), Collections.emptyList()); }
}
