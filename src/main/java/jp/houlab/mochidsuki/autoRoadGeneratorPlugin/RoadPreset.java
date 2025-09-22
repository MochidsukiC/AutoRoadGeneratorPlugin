package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.block.data.BlockData;

import java.util.List;

public class RoadPreset {
    private final String name;
    private final List<PresetSlice> slices; // X-axis slices
    private final int lengthX; // Number of slices along X-axis
    private final int widthZ; // Width along Z-axis
    private final int heightY; // Height along Y-axis
    private final int axisZOffset; // Z position of axis within the array bounds
    private final int axisYOffset; // Y position of axis within the array bounds

    public RoadPreset(String name, List<PresetSlice> slices, int lengthX, int widthZ, int heightY, int axisZOffset, int axisYOffset) {
        this.name = name;
        this.slices = slices;
        this.lengthX = lengthX;
        this.widthZ = widthZ;
        this.heightY = heightY;
        this.axisZOffset = axisZOffset;
        this.axisYOffset = axisYOffset;
    }

    public String getName() {
        return name;
    }

    public List<PresetSlice> getSlices() {
        return slices;
    }

    public int getLengthX() {
        return lengthX;
    }

    public int getWidthZ() {
        return widthZ;
    }

    public int getHeightY() {
        return heightY;
    }

    public int getAxisZOffset() {
        return axisZOffset;
    }

    public int getAxisYOffset() {
        return axisYOffset;
    }

    // 軸からの相対座標での範囲を取得するメソッド
    public int getMinZ() {
        return -axisZOffset;
    }

    public int getMaxZ() {
        return widthZ - axisZOffset - 1;
    }

    public int getMinY() {
        return -axisYOffset;
    }

    public int getMaxY() {
        return heightY - axisYOffset - 1;
    }

    // Inner class for 2D slice data
    public static class PresetSlice {
        private final int xPosition;
        private final BlockData[][] yzGrid; // [z][y] grid

        public PresetSlice(int xPosition, int widthZ, int heightY) {
            this.xPosition = xPosition;
            this.yzGrid = new BlockData[widthZ][heightY];
        }

        public int getXPosition() {
            return xPosition;
        }

        public BlockData getBlock(int z, int y) {
            if (z >= 0 && z < yzGrid.length && y >= 0 && y < yzGrid[0].length) {
                return yzGrid[z][y];
            }
            return null;
        }

        // 軸からの相対座標でブロックを取得（負の座標にも対応）
        public BlockData getBlockRelativeToAxis(int relativeZ, int relativeY, int axisZOffset, int axisYOffset) {
            // 軸位置からarray indexに変換
            int arrayZ = relativeZ + axisZOffset;
            int arrayY = relativeY + axisYOffset;

            if (arrayZ >= 0 && arrayZ < yzGrid.length && arrayY >= 0 && arrayY < yzGrid[0].length) {
                return yzGrid[arrayZ][arrayY];
            }
            return null;
        }

        public void setBlock(int z, int y, BlockData blockData) {
            if (z >= 0 && z < yzGrid.length && y >= 0 && y < yzGrid[0].length) {
                yzGrid[z][y] = blockData;
            }
        }

        public int getWidthZ() {
            return yzGrid.length;
        }

        public int getHeightY() {
            return yzGrid[0].length;
        }

        public BlockData[][] getYZGrid() {
            return yzGrid;
        }
    }
}
