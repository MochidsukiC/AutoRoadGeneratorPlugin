package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

/**
 * A simple data record to hold information for placing a single block.
 * @param position The world position (as a Location) where the block should be placed.
 * @param data The BlockData for the block.
 */
public record BlockPlacementInfo(Location position, BlockData data) {
}
