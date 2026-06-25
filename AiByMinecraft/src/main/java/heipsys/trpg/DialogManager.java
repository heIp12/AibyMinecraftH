package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 채팅 클릭 기반 다이얼로그 (STEP 5-3).
 * - 주사위 확인 화면: 확정 / 재굴림 선택
 * - 특성 선택 화면: 3개 특성 중 선택
 *
 * 선택 결과는 ChatListener를 통해 TRPGGameManager로 전달된다.
 * 이중 선택 방지를 위해 대기 중인 다이얼로그를 Map으로 추적.
 */
public class DialogManager {

    public enum DialogType { DICE_CONFIRM, TRAIT_SELECTION, TRAIT_REMOVE }

    /** 현재 플레이어가 기다리고 있는 다이얼로그 타입 */
    private final Map<UUID, DialogType>      activeDialog  = new HashMap<>();
    /** 특성 선택용: playerUUID → 선택지 목록 */
    private final Map<UUID, List<TraitData>> traitChoices  = new HashMap<>();

    // ──────────────────────────────────────────────────────────────
    //  주사위 확인 다이얼로그
    // ──────────────────────────────────────────────────────────────

    public void showDiceConfirm(Player player, PlayerData pd) {
        activeDialog.put(player.getUniqueId(), DialogType.DICE_CONFIRM);

        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("─────────────── 주사위 결과 ───────────────", NamedTextColor.GRAY));
        player.sendMessage(Component.text(pd.getStatsSummary(), NamedTextColor.WHITE));
        if (!pd.traits.isEmpty()) {
            pd.traits.forEach(t -> player.sendMessage(Component.text("  " + t.toDisplayLine(), NamedTextColor.GRAY)));
        }
        player.sendMessage(Component.text("────────────────────────────────────────", NamedTextColor.GRAY));

        // 확정 버튼
        Component confirm = Component.text("[확정]", NamedTextColor.GREEN, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/trpg _confirm"));
        // 재굴림 버튼
        String rollsLeft = "재굴림 (" + pd.diceRollsRemaining + "회 남음)";
        Component reroll = pd.diceRollsRemaining > 0
            ? Component.text("[" + rollsLeft + "]", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/trpg _reroll"))
            : Component.text("[재굴림 불가]", NamedTextColor.DARK_GRAY);

        player.sendMessage(Component.text("  ").append(confirm).append(Component.text("  ")).append(reroll));
        player.sendMessage(Component.text(""));
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 선택 다이얼로그
    // ──────────────────────────────────────────────────────────────

    public void showTraitSelection(Player player, List<TraitData> choices, boolean canRemove) {
        activeDialog.put(player.getUniqueId(), DialogType.TRAIT_SELECTION);
        traitChoices.put(player.getUniqueId(), new ArrayList<>(choices));

        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("══════════════ 특성 선택 ══════════════", NamedTextColor.GOLD, TextDecoration.BOLD));

        for (int i = 0; i < choices.size(); i++) {
            TraitData t = choices.get(i);
            String label = "[" + (i+1) + "] (" + t.grade + ") " + t.name;
            player.sendMessage(
                Component.text(label, NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/trpg _trait " + (i+1)))
                    .append(Component.text(": " + t.description, NamedTextColor.WHITE))
            );
        }

        if (canRemove) {
            player.sendMessage(Component.text("[4] 기존 특성 1개 제거", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/trpg _trait 4")));
        }
        player.sendMessage(Component.text("════════════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text("클릭하거나 번호를 채팅에 입력하세요.", NamedTextColor.GRAY));
        player.sendMessage(Component.text(""));
    }

    /** 기존 특성 제거 선택 화면 */
    public void showTraitRemove(Player player, PlayerData pd) {
        activeDialog.put(player.getUniqueId(), DialogType.TRAIT_REMOVE);
        player.sendMessage(Component.text("제거할 특성 번호를 입력하세요:", NamedTextColor.RED));
        for (int i = 0; i < pd.traits.size(); i++) {
            TraitData t = pd.traits.get(i);
            player.sendMessage(
                Component.text("[" + i + "] " + t.name + " (" + t.grade + ")", NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand("/trpg _trait_remove " + i))
            );
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  다이얼로그 상태
    // ──────────────────────────────────────────────────────────────

    public boolean hasActiveDialog(Player player)          { return activeDialog.containsKey(player.getUniqueId()); }
    public DialogType getDialogType(Player player)         { return activeDialog.get(player.getUniqueId()); }
    public void clearDialog(Player player)                 { activeDialog.remove(player.getUniqueId()); traitChoices.remove(player.getUniqueId()); }
    public List<TraitData> getTraitChoices(Player player) { return traitChoices.getOrDefault(player.getUniqueId(), Collections.emptyList()); }
}
