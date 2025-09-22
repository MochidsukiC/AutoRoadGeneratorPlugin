package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;

public class RoadPreset {
    private final String name;
    private final Vector dimensions; // Width, Height, Depth
    private final Map<Vector, BlockData> blocks; // Relative coordinates to BlockData
    private final List<Vector> axisPath; // Relative coordinates of the central axis path

    public RoadPreset(String name, Vector dimensions, Map<Vector, BlockData> blocks, List<Vector> axisPath) {
        this.name = name;
        this.dimensions = dimensions;
        this.blocks = blocks;
        this.axisPath = axisPath;
    }

    public String getName() {
        return name;
    }

    public Vector getDimensions() {
        return dimensions;
    }

    public Map<Vector, BlockData> getBlocks() {
        return blocks;
    }

    public List<Vector> getAxisPath() {
        return axisPath;
    }
}
