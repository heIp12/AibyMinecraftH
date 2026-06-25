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
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;

public class ChatListener implements Listener {

    private final AICraft plugin;

    public ChatListener(AICraft plugin) {
        this.plugin = plugin;
    }

    /** 리로드로 매니저가 교체되어도 항상 최신 인스턴스를 참조한다 */
    private TRPGGameManager trpg() {
        return plugin.trpgManager;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;

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
    public void onSneak(PlayerToggleSneakEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        if (!event.isSneaking()) return;
        trpgManager.getNarrativeDelivery().onSneak(event.getPlayer());
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
