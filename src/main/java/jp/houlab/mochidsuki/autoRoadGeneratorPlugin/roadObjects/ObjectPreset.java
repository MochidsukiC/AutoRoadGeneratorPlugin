package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.roadObjects;

import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

import java.util.Map;

/**
 * 道路に設置するオブジェクトの構造を保持するプリセットクラス。
 */
public class ObjectPreset {

    private final String name;
    private final Map<Vector, BlockData> blocks;
    private final int initialYaw;
    private final Vector dimensions;

    /**
     * @param name プリセット名
     * @param blocks 原点を基準としたブロックの相対座標とBlockDataのマップ
     * @param initialYaw 保存時のプレイヤーの向き（0, 90, 180, 270）
     * @param dimensions プリセットのサイズ（幅、高さ、奥行き）
     */
    public ObjectPreset(String name, Map<Vector, BlockData> blocks, int initialYaw, Vector dimensions) {
        this.name = name;
        this.blocks = blocks;
        this.initialYaw = initialYaw;
        this.dimensions = dimensions;
    }

    public String getName() {
        return name;
    }

    public Map<Vector, BlockData> getBlocks() {
        return blocks;
    }

    public int getInitialYaw() {
        return initialYaw;
    }

    public Vector getDimensions() {
        return dimensions;
    }
}
