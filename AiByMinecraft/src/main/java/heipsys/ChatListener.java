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

    private final Plugin           plugin;
    private final TRPGGameManager  trpgManager;

    public ChatListener(Plugin plugin, TRPGGameManager trpgManager) {
        this.plugin      = plugin;
        this.trpgManager = trpgManager;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!trpgManager.isActive()) return;

        Player player  = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () ->
            trpgManager.handleChat(player, message));
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
