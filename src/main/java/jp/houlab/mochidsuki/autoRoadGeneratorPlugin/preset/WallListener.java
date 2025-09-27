package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;

public class WallListener implements Listener {

    private final AutoRoadGeneratorPluginMain plugin;
    private final Map<UUID, WallCreationSession> wallSessions;

    public WallListener(AutoRoadGeneratorPluginMain plugin, Map<UUID, WallCreationSession> wallSessions) {
        this.plugin = plugin;
        this.wallSessions = wallSessions;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // Check if the item is the Wall Brush using PersistentDataContainer
        if (item.getType() != Material.STONE_AXE || !item.hasItemMeta()) {
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
        if (!"wall_brush".equals(brushType)) {
            return;
        }

        event.setCancelled(true); // Prevent block breaking/placement with the brush

        WallCreationSession session = wallSessions.computeIfAbsent(player.getUniqueId(), k -> new WallCreationSession());
        Location clickedBlockLocation = event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null;

        if (clickedBlockLocation == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "preset.target_block");
            return;
        }

        if (player.isSneaking()) {
            // Shift + Click - Set axis line
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                session.setAxisStart(clickedBlockLocation);
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.axis_start_set", formatLocation(player, clickedBlockLocation));
            } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                session.setAxisEnd(clickedBlockLocation);
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.axis_end_set", formatLocation(player, clickedBlockLocation));
            }
        } else {
            // Normal Click - Set 3D selection area
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                session.setPos1(clickedBlockLocation);
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.pos1_set", formatLocation(player, clickedBlockLocation));
            } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                session.setPos2(clickedBlockLocation);
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.pos2_set", formatLocation(player, clickedBlockLocation));
            }
        }
    }

    private String formatLocation(Player player, Location loc) {
        return plugin.getMessageManager().getMessage(player, "location.format", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
