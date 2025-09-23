package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class PresetListener implements Listener {

    private final Map<UUID, PresetCreationSession> playerSessions;

    public PresetListener(Map<UUID, PresetCreationSession> playerSessions) {
        this.playerSessions = playerSessions;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if(event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // Check if the item is the Preset Brush
        if (item.getType() == Material.GOLDEN_AXE && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && Objects.equals(meta.getDisplayName(), ChatColor.GOLD + "プリセットブラシ")) {
                List<String> lore = meta.getLore();
                if (lore != null && lore.contains(ChatColor.YELLOW + "左クリック: 始点を設定")) {
                    event.setCancelled(true); // Prevent block breaking/placement with the brush

                    PresetCreationSession session = playerSessions.computeIfAbsent(player.getUniqueId(), k -> new PresetCreationSession());
                    Location clickedBlockLocation = event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null;

                    if (clickedBlockLocation == null) {
                        player.sendMessage(ChatColor.RED + "ブロックをターゲットしてください。");
                        return;
                    }

                    if (player.isSneaking()) {
                        // Shift + Click
                        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                            session.setAxisStart(clickedBlockLocation);
                            player.sendMessage(ChatColor.YELLOW + "中心軸の始点を設定しました: " + formatLocation(clickedBlockLocation));
                        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                            session.setAxisEnd(clickedBlockLocation);
                            player.sendMessage(ChatColor.YELLOW + "中心軸の終点を設定しました: " + formatLocation(clickedBlockLocation));
                        }
                    } else {
                        // Normal Click
                        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                            session.setPos1(clickedBlockLocation);
                            player.sendMessage(ChatColor.YELLOW + "始点を設定しました: " + formatLocation(clickedBlockLocation));
                        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                            session.setPos2(clickedBlockLocation);
                            player.sendMessage(ChatColor.YELLOW + "終点を設定しました: " + formatLocation(clickedBlockLocation));
                        }
                    }
                }
            }
        }
    }

    private String formatLocation(Location loc) {
        return "(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }
}
