package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
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

        // 스탯을 수정 불가 body Component로 구성
        var bodyBuilder = Component.text()
            .append(Component.text("나이 · 직업  ", NamedTextColor.GOLD))
            .append(Component.text(pd.age + "세  ·  " + pd.job, NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("체력  ", NamedTextColor.RED))
            .append(Component.text(hpDisplay(pd.hp), NamedTextColor.WHITE))
            .append(Component.text("    정신력  ", NamedTextColor.AQUA))
            .append(Component.text(hpDisplay(pd.san), NamedTextColor.WHITE))
            .appendNewline()
            .append(Component.text("근력 ", NamedTextColor.YELLOW))
            .append(Component.text(String.valueOf(pd.str), NamedTextColor.WHITE))
            .append(Component.text("   매력 ", NamedTextColor.YELLOW))
            .append(Component.text(String.valueOf(pd.cha), NamedTextColor.WHITE))
            .append(Component.text("   행운 ", NamedTextColor.YELLOW))
            .append(Component.text(String.valueOf(pd.luk), NamedTextColor.WHITE))
            .append(Component.text("   영감 ", NamedTextColor.YELLOW))
            .append(Component.text(String.valueOf(pd.spr), NamedTextColor.WHITE));

        if (!pd.traits.isEmpty()) {
            bodyBuilder.appendNewline()
                .append(Component.text("특성:", NamedTextColor.LIGHT_PURPLE));
            for (TraitData t : pd.traits) {
                bodyBuilder.appendNewline()
                    .append(Component.text("  ▸ (" + t.grade + ") " + t.name, NamedTextColor.GRAY));
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
                    Component.text("캐릭터 생성  |  방 " + roomNumber + " · " + attempt + "회차"))
                .body(body)
                .build())
            .type(DialogType.multiAction(buttons, cancelBtn, 2))
        );
        player.showDialog(dialog);
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
