package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.PlayerMessageUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;

public class ObjectBrushListener implements Listener {

    private final AutoRoadGeneratorPluginMain plugin;
    private final Map<UUID, ObjectCreationSession> creationSessions;

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
        if (meta == null) {
            return;
        }

        PersistentDataContainer data = meta.getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(plugin, "brush_type");

        if (!data.has(key, PersistentDataType.STRING)) {
            return;
        }

        String brushType = data.get(key, PersistentDataType.STRING);
        if (!"object_creation_brush".equals(brushType)) {
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
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.start_point_set", formatLocation(player, clickedBlockLocation));
            } else {
                session.setEndLocation(clickedBlockLocation);
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.end_point_set", formatLocation(player, clickedBlockLocation));
            }
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            // Use right click to set the origin point
            session.setOriginLocation(clickedBlockLocation);
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.origin_point_set", formatLocation(player, clickedBlockLocation));
        }

        if (session.isReady()) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.all_points_set");
        }
    }

    private String formatLocation(Player player, Location loc) {
        return plugin.getMessageManager().getMessage(player, "location.format", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
