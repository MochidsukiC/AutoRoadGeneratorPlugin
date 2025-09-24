package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

/**
 * 全プリセットの共通インターフェース
 */
public interface PresetData {
    String getName();
    PresetType getType();

    enum PresetType {
        ROAD,
        OBJECT,
        WALL
    }
}