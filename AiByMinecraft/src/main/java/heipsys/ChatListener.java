package heipsys;

import heipsys.trpg.TRPGGameManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class ChatListener implements Listener {

    private final Plugin            plugin;
    private final GameManager       gameManager;
    private final TRPGGameManager   trpgManager;

    public ChatListener(Plugin plugin, GameManager gameManager, TRPGGameManager trpgManager) {
        this.plugin      = plugin;
        this.gameManager = gameManager;
        this.trpgManager = trpgManager;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player  = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // TRPG 세션이 활성화된 경우 — TRPG가 우선
        if (trpgManager.isActive()) {
            event.setCancelled(true);
            // 메인 스레드에서 처리 (Bukkit API 사용 위해)
            Bukkit.getScheduler().runTask(plugin, () ->
                trpgManager.handleChat(player, message));
            return;
        }

        // 기존 배틀 게임
        if (!gameManager.isGameRunning()) return;
        BattleSession session = gameManager.getSessionByPlayer(player);
        if (session == null) return;

        event.setCancelled(true);
        session.onPlayerActionDeclare(player, message);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        event.getDrops().removeIf(item -> item != null && item.getType() == Material.WRITTEN_BOOK);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isDead()) player.spigot().respawn();
        });
    }

    @EventHandler
    public void onSpectatorClickBook(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.SPECTATOR) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked != null && clicked.getType() == Material.WRITTEN_BOOK) {
            event.setCancelled(true);
            player.openBook(clicked);
        }
    }
}
