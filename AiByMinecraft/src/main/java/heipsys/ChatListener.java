package heipsys;

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
    
    private final Plugin plugin;
    private final GameManager gameManager;

    public ChatListener(Plugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!gameManager.isGameRunning()) return;

        Player player = event.getPlayer();
        BattleSession session = gameManager.getSessionByPlayer(player);
        
        // 플레이어가 현재 전투 중인 세션이 없다면 (관전자, 부전승자 등) 일반 채팅 처리
        if (session == null) return;
        
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        event.setCancelled(true);
        session.onPlayerActionDeclare(player, message);
    }
    
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();

        // 플레이어 사망 시 책(WRITTEN_BOOK) 드랍 방지
        event.getDrops().removeIf(item -> item != null && item.getType() == Material.WRITTEN_BOOK);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isDead()) {
                player.spigot().respawn();
            }
        });
    }

    // [변경됨] 인벤토리 내에서 책을 클릭했을 때 책 UI를 열어줌
    @EventHandler
    public void onSpectatorClickBook(InventoryClickEvent event) {
        // 클릭한 주체가 플레이어인지 확인
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) event.getWhoClicked();
        
        // 플레이어가 관전 모드일 때만 작동
        if (player.getGameMode() == GameMode.SPECTATOR) {
            ItemStack clickedItem = event.getCurrentItem();
            
            // 클릭한 아이템이 완성된 책인지 확인
            if (clickedItem != null && clickedItem.getType() == Material.WRITTEN_BOOK) {
                // 클릭 이벤트를 취소하여 아이템이 이동하거나 버려지는 것을 방지
                event.setCancelled(true);
                
                // 플레이어 화면에 책 내용을 즉시 띄움 (인벤토리는 닫히고 책이 열림)
                player.openBook(clickedItem);
            }
        }
    }
}