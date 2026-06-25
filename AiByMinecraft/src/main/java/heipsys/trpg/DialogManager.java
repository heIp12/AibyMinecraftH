package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 채팅 클릭 기반 다이얼로그.
 * 캐릭터 시트, 주사위 확정, 특성 선택 등을 Adventure Components로 출력한다.
 */
public class DialogManager {

    public enum DialogType { DICE_CONFIRM, TRAIT_SELECTION, TRAIT_REMOVE }

    private final Map<UUID, DialogType>      activeDialog = new HashMap<>();
    private final Map<UUID, List<TraitData>> traitChoices = new HashMap<>();

    // ──────────────────────────────────────────────────────────────
    //  캐릭터 시트 + 주사위 확인 (통합 다이얼로그)
    // ──────────────────────────────────────────────────────────────

    public void showCharacterSheet(Player player, PlayerData pd, int roomNumber, int attempt) {
        activeDialog.put(player.getUniqueId(), DialogType.DICE_CONFIRM);

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("━━━━━━━━━ 캐릭터 생성 ━━━━━━━━━",
            NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("방 " + roomNumber + " / " + attempt + "회차",
            NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.empty());

        // 나이·직업
        player.sendMessage(
            Component.text("나이 ", NamedTextColor.GRAY)
            .append(Component.text(pd.age + "세", NamedTextColor.WHITE))
            .append(Component.text("  직업 ", NamedTextColor.GRAY))
            .append(Component.text(pd.job, NamedTextColor.WHITE))
        );
        player.sendMessage(Component.empty());

        // 스탯 (호버로 설명 표시)
        player.sendMessage(statRow(
            "§c체력", pd.hp[0] + "/" + pd.hp[1], "생존 가능한 피해량",
            "§9근력", String.valueOf(pd.str), "물리 행동·전투 판정"
        ));
        player.sendMessage(statRow(
            "§b정신력", pd.san[0] + "/" + pd.san[1], "공포·충격 저항력",
            "§a매력", String.valueOf(pd.cha), "설득·사교 판정"
        ));
        player.sendMessage(statRow(
            "§6행운", String.valueOf(pd.luk), "우연한 행운 발동",
            "§d영감", String.valueOf(pd.spr), "직감·초감각 판정"
        ));

        // 특성
        if (!pd.traits.isEmpty()) {
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("[ 특성 ]", NamedTextColor.YELLOW, TextDecoration.BOLD));
            pd.traits.forEach(t -> player.sendMessage(
                Component.text("  ▸ ", NamedTextColor.DARK_GRAY)
                .append(Component.text("(" + t.grade + ") ", NamedTextColor.GRAY))
                .append(Component.text(t.name, NamedTextColor.WHITE)
                    .hoverEvent(HoverEvent.showText(
                        Component.text(t.description + "\n효과: " + t.effect, NamedTextColor.GRAY))))
            ));
        }

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));

        // 확정 / 재굴림 버튼
        String rollLabel = pd.diceRollsRemaining > 0
            ? "재굴림 (" + pd.diceRollsRemaining + "회 남음)"
            : "재굴림 불가";
        Component rerollBtn = pd.diceRollsRemaining > 0
            ? Component.text("[ " + rollLabel + " ]", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/trpg _reroll"))
                .hoverEvent(HoverEvent.showText(Component.text("스탯을 다시 굴립니다", NamedTextColor.GRAY)))
            : Component.text("[ " + rollLabel + " ]", NamedTextColor.DARK_GRAY);

        player.sendMessage(
            Component.text("  ")
            .append(Component.text("[ 확정 ]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/trpg _confirm"))
                .hoverEvent(HoverEvent.showText(Component.text("이 스탯으로 확정합니다", NamedTextColor.GRAY))))
            .append(Component.text("  "))
            .append(rerollBtn)
        );
        player.sendMessage(Component.empty());
    }

    private Component statRow(String labelA, String valA, String descA,
                              String labelB, String valB, String descB) {
        return Component.text("  ")
            .append(Component.text(labelA + " ").toBuilder().build())
            .append(Component.text(valA, NamedTextColor.WHITE)
                .hoverEvent(HoverEvent.showText(Component.text(descA, NamedTextColor.GRAY))))
            .append(Component.text("   "))
            .append(Component.text(labelB + " ").toBuilder().build())
            .append(Component.text(valB, NamedTextColor.WHITE)
                .hoverEvent(HoverEvent.showText(Component.text(descB, NamedTextColor.GRAY))));
    }

    // ──────────────────────────────────────────────────────────────
    //  특성 선택 다이얼로그
    // ──────────────────────────────────────────────────────────────

    public void showTraitSelection(Player player, List<TraitData> choices, boolean canRemove) {
        activeDialog.put(player.getUniqueId(), DialogType.TRAIT_SELECTION);
        traitChoices.put(player.getUniqueId(), new ArrayList<>(choices));

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("══════════ 특성 선택 ══════════",
            NamedTextColor.GOLD, TextDecoration.BOLD));

        for (int i = 0; i < choices.size(); i++) {
            TraitData t = choices.get(i);
            player.sendMessage(
                Component.text("  [" + (i + 1) + "] ", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/trpg _trait " + (i + 1)))
                    .append(Component.text("(" + t.grade + ") " + t.name, NamedTextColor.WHITE)
                        .hoverEvent(HoverEvent.showText(
                            Component.text(t.description + "\n§7효과: " + t.effect, NamedTextColor.GRAY)
                        )))
            );
        }

        if (canRemove) {
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  [4] 기존 특성 1개 제거", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/trpg _trait 4")));
        }
        player.sendMessage(Component.text("══════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text("클릭하거나 번호를 채팅에 입력하세요.", NamedTextColor.GRAY));
        player.sendMessage(Component.empty());
    }

    public void showTraitRemove(Player player, PlayerData pd) {
        activeDialog.put(player.getUniqueId(), DialogType.TRAIT_REMOVE);
        player.sendMessage(Component.text("제거할 특성 번호를 클릭 또는 입력하세요:", NamedTextColor.RED));
        for (int i = 0; i < pd.traits.size(); i++) {
            TraitData t = pd.traits.get(i);
            player.sendMessage(
                Component.text("  [" + i + "] " + t.name + " (" + t.grade + ")", NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand("/trpg _trait_remove " + i))
                    .hoverEvent(HoverEvent.showText(Component.text(t.description, NamedTextColor.DARK_GRAY)))
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

    // 하위 호환 — 이전 showDiceConfirm 호출부 대응
    public void showDiceConfirm(Player player, PlayerData pd) {
        showCharacterSheet(player, pd, 1, 1);
    }
}
