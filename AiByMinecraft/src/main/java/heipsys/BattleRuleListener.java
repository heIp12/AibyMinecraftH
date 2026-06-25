package heipsys;

import heipsys.GameManager;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

public class BattleRuleListener implements Listener {

    private final GameManager gameManager;

    public BattleRuleListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    private boolean isBattling(Player p) {
        return gameManager.isGameRunning() && gameManager.getSessionByPlayer(p) != null;
    }
    
    // --- [추가된 부분] 몬스터 스폰 차단 ---
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (gameManager.isTournamentPhase()) {
            // 적대적 몹(몬스터, 슬라임, 가스트, 팬텀 등)의 스폰만 막습니다.
            if (event.getEntity() instanceof Monster || 
                event.getEntity() instanceof Slime || 
                event.getEntity() instanceof Ghast || 
                event.getEntity() instanceof Phantom) {
                
                event.setCancelled(true);
            }
        }
    }
    // ------------------------------------

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (isBattling(p)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isBattling(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c[경고] 전투 중에는 채팅으로만 아이템을 사용할 수 있습니다!");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isBattling(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isBattling(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isBattling(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player) {
            if (isBattling((Player) event.getEntity())) {
                event.setCancelled(true);
            }
        }
    }
}