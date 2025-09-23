package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

/**
 * A simple data class to hold information for placing a single block.
 */
public class BlockPlacementInfo {
    private final Location position;
    private final BlockData data;

    public BlockPlacementInfo(Location position, BlockData data) {
        this.position = position;
        this.data = data;
    }

    public Location position() {
        return position;
    }

    public BlockData data() {
        return data;
    }
}
