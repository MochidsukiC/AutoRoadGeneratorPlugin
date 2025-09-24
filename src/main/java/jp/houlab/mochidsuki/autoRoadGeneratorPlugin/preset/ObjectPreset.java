package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

import java.util.List;

/**
 * オブジェクト設置用のプリセット
 */
public class ObjectPreset implements PresetData {
    private final String name;
    private final List<BlockPlacement> placements;

    public ObjectPreset(String name, List<BlockPlacement> placements) {
        this.name = name;
        this.placements = placements;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public PresetType getType() {
        return PresetType.OBJECT;
    }

    public List<BlockPlacement> getPlacements() {
        return placements;
    }

    /**
     * ブロック設置情報
     */
    public static class BlockPlacement {
        private final int x, y, z;
        private final String blockDataString;
        private final PlacementMode mode;

        public BlockPlacement(int x, int y, int z, String blockDataString, PlacementMode mode) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockDataString = blockDataString;
            this.mode = mode;
        }

        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public String getBlockDataString() { return blockDataString; }
        public PlacementMode getMode() { return mode; }
    }

    public enum PlacementMode {
        REPLACE,    // ブロックを置き換え
        ONLY_AIR,   // 空気ブロックのみに設置
        PRESERVE    // 既存ブロックを保持
    }
}