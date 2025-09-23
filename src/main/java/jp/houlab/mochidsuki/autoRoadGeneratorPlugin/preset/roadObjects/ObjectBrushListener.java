package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.UUID;

public class ObjectBrushListener implements Listener {

    private final AutoRoadGeneratorPluginMain plugin;
    private final Map<UUID, ObjectCreationSession> creationSessions;

    public static final String BRUSH_NAME = ChatColor.GOLD + "Object Preset Brush";

    public ObjectBrushListener(AutoRoadGeneratorPluginMain plugin, Map<UUID, ObjectCreationSession> creationSessions) {
        this.plugin = plugin;
        this.creationSessions = creationSessions;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() != Material.IRON_AXE || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || !meta.getDisplayName().equals(BRUSH_NAME)) {
            return;
        }

        // It's the brush, so cancel the default axe behavior
        event.setCancelled(true);

        ObjectCreationSession session = creationSessions.computeIfAbsent(player.getUniqueId(), k -> new ObjectCreationSession(player.getUniqueId()));

        Action action = event.getAction();
        Location clickedBlockLocation = event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null;

        if (clickedBlockLocation == null) {
            return; // Must click a block
        }

        if (action == Action.LEFT_CLICK_BLOCK) {
            // Use left click to set start and end points
            if (session.getStartLocation() == null || session.getEndLocation() != null) {
                session.setEndLocation(null); // Reset end location to set start point again
                session.setStartLocation(clickedBlockLocation);
                player.sendMessage(ChatColor.AQUA + "[1/3] Start point set.");
            } else {
                session.setEndLocation(clickedBlockLocation);
                player.sendMessage(ChatColor.AQUA + "[2/3] End point set.");
            }
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            // Use right click to set the origin point
            session.setOriginLocation(clickedBlockLocation);
            player.sendMessage(ChatColor.AQUA + "[3/3] Origin point set.");
        }

        if (session.isReady()) {
            player.sendMessage(ChatColor.GREEN + "All points have been set! Use " + ChatColor.YELLOW + "/ro create <preset_name>" + ChatColor.GREEN + " to save the object preset.");
        }
    }
}
