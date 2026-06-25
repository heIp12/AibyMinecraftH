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
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;

/**
 * Paper Dialog API 기반 다이얼로그.
 * 캐릭터 시트, 특성 선택/제거를 Paper 1.21.3+ Dialog UI로 표시한다.
 */
public class DialogManager {

    public enum DialogState { DICE_CONFIRM, TRAIT_SELECTION, TRAIT_REMOVE }

    private final Map<UUID, DialogState>     activeDialog = new HashMap<>();
    private final Map<UUID, List<TraitData>> traitChoices = new HashMap<>();

    // ──────────────────────────────────────────────────────────────
    //  캐릭터 시트 + 주사위 확인
    // ──────────────────────────────────────────────────────────────

    public void showCharacterSheet(Player player, PlayerData pd, int roomNumber, int attempt,
                                    Runnable onConfirm, Runnable onReroll) {
        activeDialog.put(player.getUniqueId(), DialogState.DICE_CONFIRM);

        // 스탯을 수정 불가 body Component로 구성 (마우스 오버레이 포함)
        var bodyBuilder = Component.text()
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
                    + "  (100 기준 환산 표시)", NamedTextColor.GRAY)))
            .append(Component.text("    ", NamedTextColor.WHITE))
            // 정신력 — 라벨: 설명, 값: 원본 수치
            .append(Component.text("정신력  ", NamedTextColor.AQUA)
                .hoverEvent(Component.text(
                    "정신력 (SAN)\n현재: " + pd.san[0] + " / 최대: " + pd.san[1]
                    + "\n공포·충격으로 감소. 0이 되면 이성을 잃습니다.", NamedTextColor.GRAY)))
            .append(Component.text(hpDisplay(pd.san), NamedTextColor.WHITE)
                .hoverEvent(Component.text(
                    "현재: " + pd.san[0] + " / 최대: " + pd.san[1]
                    + "  (100 기준 환산 표시)", NamedTextColor.GRAY)))
            .appendNewline()
            // 2차 스탯 — 라벨에 판정 설명
            .append(Component.text("근력 ", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("근력 (STR)\n물리 행동·격투·이동 판정에 영향", NamedTextColor.GRAY)))
            .append(Component.text(String.valueOf(pd.str), NamedTextColor.WHITE)
                .hoverEvent(Component.text("근력: " + pd.str, NamedTextColor.YELLOW)))
            .append(Component.text("   매력 ", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("매력 (CHA)\n설득·협박·사교 판정에 영향", NamedTextColor.GRAY)))
            .append(Component.text(String.valueOf(pd.cha), NamedTextColor.WHITE)
                .hoverEvent(Component.text("매력: " + pd.cha, NamedTextColor.YELLOW)))
            .append(Component.text("   행운 ", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("행운 (LUK)\n위기 탈출·우연한 발견 판정에 영향", NamedTextColor.GRAY)))
            .append(Component.text(String.valueOf(pd.luk), NamedTextColor.WHITE)
                .hoverEvent(Component.text("행운: " + pd.luk, NamedTextColor.YELLOW)))
            .append(Component.text("   영감 ", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("영감 (SPR)\n직감·예지·정신 방어 판정에 영향", NamedTextColor.GRAY)))
            .append(Component.text(String.valueOf(pd.spr), NamedTextColor.WHITE)
                .hoverEvent(Component.text("영감: " + pd.spr, NamedTextColor.YELLOW)));

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
        var bodyBuilder = Component.text()
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

        Component title = Component.text()
            .append(Component.text(pd.name, NamedTextColor.YELLOW))
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
        c.hoverEvent(Component.text(label + "\n기본 " + base + (d != 0 ? " → 현재 " + current + " (배역 보정)" : ""), NamedTextColor.GRAY));
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

            String tooltip = t.description != null ? t.description : "";
            if (t.active && t.effect != null && !t.effect.isBlank()) {
                tooltip += (tooltip.isBlank() ? "" : "\n\n") + "§e[사용 효과] §f" + t.effect;
            }

            buttons.add(ActionButton.create(
                Component.text("(" + t.grade + ") " + t.name, NamedTextColor.WHITE),
                tooltip.isBlank() ? null : Component.text(tooltip),
                200,
                DialogAction.customClick((v, a) -> onSelect.accept(idx),
                    ClickCallback.Options.builder().uses(1).build())
            ));
        }

        if (canRemove) {
            buttons.add(ActionButton.create(
                Component.text("✖ 기존 특성 제거", NamedTextColor.RED),
                Component.text("기존 특성 1개를 제거하고 새 특성을 받을 수 있습니다."),
                150,
                DialogAction.customClick((v, a) -> onSelect.accept(4),
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
