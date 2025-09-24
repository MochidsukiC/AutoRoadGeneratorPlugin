package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

import org.bukkit.block.data.BlockData;

/**
 * ブロックデータの提供方法を統一するインターフェース
 */
public interface BlockDataProvider {
    /**
     * 並列処理で安全な文字列形式でブロックデータを取得
     */
    String getBlockDataString(int z, int y);

    /**
     * メインスレッドでのみ使用するBlockData形式
     */
    BlockData getBlockData(int z, int y);

    /**
     * 軸からの相対座標での文字列取得
     */
    String getBlockDataStringRelativeToAxis(int relativeZ, int relativeY, int axisZOffset, int axisYOffset);

    /**
     * 軸からの相対座標でのBlockData取得
     */
    BlockData getBlockDataRelativeToAxis(int relativeZ, int relativeY, int axisZOffset, int axisYOffset);
}