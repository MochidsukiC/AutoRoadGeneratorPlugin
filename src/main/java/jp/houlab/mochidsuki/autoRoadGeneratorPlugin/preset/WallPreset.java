package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

import org.bukkit.block.data.BlockData;
import java.util.List;

public class WallPreset implements PresetData {
    private final String name;
    private final List<WallSlice> slices; // X-axis slices (same as RoadPreset structure)
    private final int lengthX; // Length along X-axis (wall thickness/depth)
    private final int widthZ; // Width along Z-axis (perpendicular to wall direction)
    private final int heightY; // Height along Y-axis
    private final int axisZOffset; // Z position of axis within the array bounds
    private final int axisYOffset; // Y position of axis within the array bounds
    private final int axisXOffset; // X position of axis within the array bounds (new for 3D)

    public WallPreset(String name, List<WallSlice> slices, int lengthX, int widthZ, int heightY,
                     int axisXOffset, int axisZOffset, int axisYOffset) {
        this.name = name;
        this.slices = slices;
        this.lengthX = lengthX;
        this.widthZ = widthZ;
        this.heightY = heightY;
        this.axisXOffset = axisXOffset;
        this.axisZOffset = axisZOffset;
        this.axisYOffset = axisYOffset;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public PresetType getType() {
        return PresetType.WALL;
    }

    public List<WallSlice> getSlices() {
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

    public int getAxisXOffset() {
        return axisXOffset;
    }

    public int getAxisZOffset() {
        return axisZOffset;
    }

    public int getAxisYOffset() {
        return axisYOffset;
    }

    // 軸からの相対座標での範囲を取得するメソッド
    public int getMinX() {
        return -axisXOffset;
    }

    public int getMaxX() {
        return lengthX - axisXOffset - 1;
    }

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

    // Inner class for wall slice data (same structure as RoadPreset.PresetSlice)
    public static class WallSlice implements BlockDataProvider {
        private final int xPosition;
        private final BlockData[][] yzGrid; // [z][y] grid
        private final String[][] stringGrid; // [z][y] grid for thread-safe string data

        public WallSlice(int xPosition, int widthZ, int heightY) {
            this.xPosition = xPosition;
            this.yzGrid = new BlockData[widthZ][heightY];
            this.stringGrid = new String[widthZ][heightY];
        }

        public int getXPosition() {
            return xPosition;
        }

        // インターフェース実装 - メインスレッド用
        @Override
        public BlockData getBlockData(int z, int y) {
            if (z >= 0 && z < yzGrid.length && y >= 0 && y < yzGrid[0].length) {
                return yzGrid[z][y];
            }
            return null;
        }

        @Override
        public BlockData getBlockDataRelativeToAxis(int relativeZ, int relativeY, int axisZOffset, int axisYOffset) {
            int arrayZ = relativeZ + axisZOffset;
            int arrayY = relativeY + axisYOffset;

            if (arrayZ >= 0 && arrayZ < yzGrid.length && arrayY >= 0 && arrayY < yzGrid[0].length) {
                return yzGrid[arrayZ][arrayY];
            }
            return null;
        }

        // インターフェース実装 - 並列処理用
        @Override
        public String getBlockDataString(int z, int y) {
            if (z >= 0 && z < stringGrid.length && y >= 0 && y < stringGrid[0].length) {
                return stringGrid[z][y];
            }
            return null;
        }

        @Override
        public String getBlockDataStringRelativeToAxis(int relativeZ, int relativeY, int axisZOffset, int axisYOffset) {
            int arrayZ = relativeZ + axisZOffset;
            int arrayY = relativeY + axisYOffset;

            if (arrayZ >= 0 && arrayZ < stringGrid.length && arrayY >= 0 && arrayY < stringGrid[0].length) {
                return stringGrid[arrayZ][arrayY];
            }
            return null;
        }

        // 従来の互換性メソッド
        public BlockData getBlock(int z, int y) {
            return getBlockData(z, y);
        }

        public BlockData getBlockRelativeToAxis(int relativeZ, int relativeY, int axisZOffset, int axisYOffset) {
            return getBlockDataRelativeToAxis(relativeZ, relativeY, axisZOffset, axisYOffset);
        }

        // ブロック設定メソッド
        public void setBlock(int z, int y, BlockData blockData) {
            if (z >= 0 && z < yzGrid.length && y >= 0 && y < yzGrid[0].length) {
                yzGrid[z][y] = blockData;
            }
        }

        public void setBlockString(int z, int y, String blockDataString) {
            if (z >= 0 && z < stringGrid.length && y >= 0 && y < stringGrid[0].length) {
                stringGrid[z][y] = blockDataString;
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

        public String[][] getStringGrid() {
            return stringGrid;
        }
    }
}