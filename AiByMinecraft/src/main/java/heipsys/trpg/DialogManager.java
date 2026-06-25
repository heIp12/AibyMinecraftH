package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
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

        List<DialogInput> inputs = new ArrayList<>();

        // 나이·직업
        String ageJob = pd.age + "세  ·  " + pd.job;
        inputs.add(DialogInput.text("profile", Component.text("나이 · 직업"))
            .initial(ageJob).width(320).build());

        // HP, SAN — 백분율/100(스탯수치) 형식
        String hpSan = "체력 " + hpDisplay(pd.hp) + "    정신력 " + hpDisplay(pd.san);
        inputs.add(DialogInput.text("hp_san", Component.text("체력 / 정신력"))
            .initial(hpSan).width(320).build());

        // 나머지 스탯
        String other = "근력 " + pd.str + "   매력 " + pd.cha + "   행운 " + pd.luk + "   영감 " + pd.spr;
        inputs.add(DialogInput.text("other_stats", Component.text("근력 · 매력 · 행운 · 영감"))
            .initial(other).width(320).build());

        // 특성 (있을 때만)
        if (!pd.traits.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (TraitData t : pd.traits) {
                if (sb.length() > 0) sb.append("  |  ");
                sb.append("(").append(t.grade).append(") ").append(t.name);
            }
            inputs.add(DialogInput.text("traits", Component.text("특성"))
                .initial(sb.toString()).width(320).build());
        }

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
                .inputs(inputs)
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
    //  형식: currentHP*10 / 100 (rawStat)
    //  예: stat=7 → "70/100(7)", 데미지 후 stat[0]=3 → "30/100(7)"
    // ──────────────────────────────────────────────────────────────

    public static String hpDisplay(int[] stat) {
        int current = stat[0] * 10;
        int maxVal  = Math.max(100, stat[1] * 10);
        return current + "/" + maxVal + "(" + stat[1] + ")";
    }

    // ──────────────────────────────────────────────────────────────
    //  상태 조회 / 초기화
    // ──────────────────────────────────────────────────────────────

    public boolean hasActiveDialog(Player player)           { return activeDialog.containsKey(player.getUniqueId()); }
    public DialogState getDialogState(Player player)        { return activeDialog.get(player.getUniqueId()); }
    public void clearDialog(Player player)                  { activeDialog.remove(player.getUniqueId()); traitChoices.remove(player.getUniqueId()); }
    public List<TraitData> getTraitChoices(Player player)  { return traitChoices.getOrDefault(player.getUniqueId(), Collections.emptyList()); }
}
