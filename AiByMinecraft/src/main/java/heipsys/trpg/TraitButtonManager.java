package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.TraitData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * 능동적 특성 사용 버튼 (STEP 5-2).
 * Adventure API 클릭 가능한 채팅 텍스트로 구현.
 * active=true인 특성만 버튼 표시.
 */
public class TraitButtonManager {

    /**
     * 플레이어의 능동적 특성 목록을 클릭 가능 버튼으로 전송.
     * 특성이 없으면 아무것도 보내지 않음.
     */
    public void sendTraitButtons(Player player, PlayerData pd) {
        if (pd == null) return;
        boolean hasActive = pd.traits.stream().anyMatch(t -> t.active);
        if (!hasActive) return;

        player.sendMessage(Component.text("§8[능동 특성]", NamedTextColor.DARK_GRAY));

        for (TraitData t : pd.traits) {
            if (!t.active) continue;
            boolean onCooldown = t.remainingCooldown > 0
                || (t.cooldownTurns == -1 && t.usedThisStage > 0);
            String cdText = t.remainingCooldown > 0 ? " [" + t.remainingCooldown + "턴]"
                : (t.cooldownTurns == -1 && t.usedThisStage > 0 ? " [스테이지 소진]" : "");
            String rapidWarn = (t.usedThisStage >= 2) ? " ⚠연속" : "";

            if (onCooldown) {
                Component btn = Component.text("[" + t.name + cdText + "]", NamedTextColor.GRAY)
                    .hoverEvent(HoverEvent.showText(
                        Component.text("쿨다운 중입니다" + cdText, NamedTextColor.RED)));
                player.sendMessage(btn);
            } else {
                Component btn = Component.text("[" + t.name + rapidWarn + "]",
                        t.usedThisStage >= 2 ? NamedTextColor.YELLOW : NamedTextColor.AQUA,
                        TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(
                        Component.text("(" + t.grade + ") " + t.description
                            + "\n효과: " + t.effect
                            + (t.cooldownTurns > 0 ? "\n쿨다운: " + t.cooldownTurns + "턴" : "")
                            + (t.cooldownTurns == -1 ? "\n쿨다운: 스테이지당 1회" : "")
                            + (t.usedThisStage >= 2 ? "\n⚠ 이번 스테이지 " + t.usedThisStage + "회 사용 — 효과 감소" : "")
                            + "\n§7클릭하여 발동 준비", NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.runCommand("/trpg _use_trait " + t.id));
                player.sendMessage(btn);
            }
        }
    }

    /**
     * 특성 사용 버튼 클릭 → TRPGGameManager로 전달할 메시지 반환.
     * "/trpg _use_trait <traitId>" 커맨드에서 호출.
     */
    public String buildTraitUseMessage(PlayerData pd, String traitId) {
        return pd.traits.stream()
            .filter(t -> t.id.equals(traitId))
            .findFirst()
            .map(t -> {
                String rapid = t.usedThisStage >= 2
                    ? " (이번 스테이지 " + t.usedThisStage + "번째 사용 — 효과 감소 및 역효과 가능)"
                    : "";
                return "[특성 발동] " + pd.name + "이(가) 특성 '" + t.name + "'"
                    + (t.effect != null && !t.effect.isBlank() ? "(" + t.effect + ")" : "")
                    + "을(를) 사용한다." + rapid + " 현재 상황에 적절하면 효과를 반영하고, "
                    + "부적절하거나 무리한 사용이면 역효과가 날 수도 있다.";
            })
            .orElse(null);
    }
}
