package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 道路プリセットの管理を行うマネージャークラス
 *
 * 道路プリセットの保存・読み込み・一覧取得などの機能を提供します。
 * プリセットデータはYAML形式でファイルシステムに保存され、
 * メモリ内でキャッシュして高速アクセスを実現します。
 *
 * @author Mochidsuki
 * @version 1.0.0
 * @since 1.0.0
 */
public class PresetManager {

    private final AutoRoadGeneratorPluginMain plugin;
    private final File presetsFolder;
    private final Map<String, RoadPreset> loadedPresets = new HashMap<>();

    public PresetManager(AutoRoadGeneratorPluginMain plugin) {
        this.plugin = plugin;
        // preset/road/フォルダに変更
        File presetFolder = new File(plugin.getDataFolder(), "preset");
        this.presetsFolder = new File(presetFolder, "road");
        if (!presetsFolder.exists()) {
            presetsFolder.mkdirs();
        }
    }

    public List<String> getPresetNames() {
        List<String> presetNames = new ArrayList<>();
        File[] files = presetsFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                presetNames.add(file.getName().substring(0, file.getName().length() - 4));
            }
        }
        return presetNames;
    }

    public void savePreset(RoadPreset preset) {
        plugin.getLogger().info(plugin.getMessageManager().getMessage("log.preset_save_call", preset.getName(), preset.getSlices().size()));
        // Convert legacy format to slice-based format before saving
        RoadPreset sliceBasedPreset = convertToSliceBased(preset);
        saveSliceBasedPreset(sliceBasedPreset);
    }

    private void saveSliceBasedPreset(RoadPreset preset) {
        plugin.getLogger().info(plugin.getMessageManager().getMessage("log.save_slice_preset_start", preset.getName(), preset.getSlices().size()));
        File presetFile = new File(presetsFolder, preset.getName() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        config.set("name", preset.getName());
        config.set("format", "slice-based");
        config.set("lengthX", preset.getLengthX());
        config.set("widthZ", preset.getWidthZ());
        config.set("heightY", preset.getHeightY());
        config.set("axisZOffset", preset.getAxisZOffset());
        config.set("axisYOffset", preset.getAxisYOffset());

        // Serialize slices
        if (preset.getSlices() != null) {
            for (int x = 0; x < preset.getSlices().size(); x++) {
                RoadPreset.PresetSlice slice = preset.getSlices().get(x);
                String sliceKey = "slices." + x;

                config.set(sliceKey + ".xPosition", slice.getXPosition());

                // Serialize YZ grid
                for (int z = 0; z < slice.getWidthZ(); z++) {
                    for (int y = 0; y < slice.getHeightY(); y++) {
                        BlockData blockData = slice.getBlock(z, y);
                        if (blockData != null) {
                            config.set(sliceKey + ".blocks." + z + "," + y, blockData.getAsString());
                        }
                    }
                }
            }
        }

        try {
            config.save(presetFile);

            // キャッシュ保存前のプリセット詳細情報
            plugin.getLogger().info(plugin.getMessageManager().getMessage("log.cache_save_check", preset.getName(), preset.getSlices().size()) +
                " lengthX=" + preset.getLengthX() +
                " widthZ=" + preset.getWidthZ() +
                " heightY=" + preset.getHeightY());

            // 各スライスの詳細もチェック
            for (int i = 0; i < Math.min(3, preset.getSlices().size()); i++) {
                RoadPreset.PresetSlice slice = preset.getSlices().get(i);
                int blockCount = 0;
                for (int z = 0; z < slice.getWidthZ(); z++) {
                    for (int y = 0; y < slice.getHeightY(); y++) {
                        if (slice.getBlock(z, y) != null) {
                            blockCount++;
                        }
                    }
                }
                plugin.getLogger().info(plugin.getMessageManager().getMessage("log.slice_details", i, slice.getXPosition(), blockCount) +
                    " size=" + slice.getWidthZ() + "x" + slice.getHeightY());
            }

            loadedPresets.put(preset.getName(), preset);
            plugin.getLogger().info(plugin.getMessageManager().getMessage("log.preset_saved_cache", preset.getName()));
        } catch (IOException e) {
            plugin.getLogger().severe(plugin.getMessageManager().getMessage("error.save_failed", preset.getName(), e.getMessage()));
        }
    }

    private RoadPreset convertToSliceBased(RoadPreset legacyPreset) {
        // This method is no longer needed since we removed legacy support
        // Return the preset as-is since it's already slice-based
        return legacyPreset;
    }


    public RoadPreset loadPreset(String name) {
        if (loadedPresets.containsKey(name)) {
            RoadPreset cachedPreset = loadedPresets.get(name);
            plugin.getLogger().info(plugin.getMessageManager().getMessage("log.preset_loaded_cache", name, cachedPreset.getSlices().size()));

            // キャッシュされたプリセットの詳細をチェック
            if (cachedPreset.getSlices().size() > 0) {
                RoadPreset.PresetSlice firstSlice = cachedPreset.getSlices().get(0);
                int blockCount = 0;
                for (int z = 0; z < firstSlice.getWidthZ(); z++) {
                    for (int y = 0; y < firstSlice.getHeightY(); y++) {
                        if (firstSlice.getBlockDataString(z, y) != null) {
                            blockCount++;
                        }
                    }
                }
                plugin.getLogger().info(plugin.getMessageManager().getMessage("log.cached_slice_details", firstSlice.getWidthZ(), firstSlice.getHeightY(), blockCount));
            }

            return cachedPreset;
        }

        File presetFile = new File(presetsFolder, name + ".yml");
        if (!presetFile.exists()) {
            plugin.getLogger().warning(plugin.getMessageManager().getMessage("road.preset_not_found", name));
            return null;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(presetFile);
        String format = config.getString("format", "legacy");

        RoadPreset preset = loadSliceBasedPreset(config);

        if (preset != null) {
            loadedPresets.put(name, preset);
            plugin.getLogger().info(plugin.getMessageManager().getMessage("log.preset_loaded_file", name, format, preset.getSlices().size()));

            // ファイルから読み込んだプリセットの詳細をチェック
            if (preset.getSlices().size() > 0) {
                RoadPreset.PresetSlice firstSlice = preset.getSlices().get(0);
                int blockCount = 0;
                for (int z = 0; z < firstSlice.getWidthZ(); z++) {
                    for (int y = 0; y < firstSlice.getHeightY(); y++) {
                        if (firstSlice.getBlockDataString(z, y) != null) {
                            blockCount++;
                        }
                    }
                }
                plugin.getLogger().info(plugin.getMessageManager().getMessage("log.file_slice_details", firstSlice.getWidthZ(), firstSlice.getHeightY(), blockCount));
            }
        }

        return preset;
    }

    private RoadPreset loadSliceBasedPreset(YamlConfiguration config) {
        String presetName = config.getString("name");
        int lengthX = config.getInt("lengthX");
        int widthZ = config.getInt("widthZ");
        int heightY = config.getInt("heightY");
        int axisZOffset = config.getInt("axisZOffset", widthZ / 2);  // Default to center for legacy files
        int axisYOffset = config.getInt("axisYOffset", heightY / 2);

        List<RoadPreset.PresetSlice> slices = new ArrayList<>();

        for (int x = 0; x < lengthX; x++) {
            String sliceKey = "slices." + x;
            int xPosition = config.getInt(sliceKey + ".xPosition", x);

            RoadPreset.PresetSlice slice = new RoadPreset.PresetSlice(xPosition, widthZ, heightY);

            // Load blocks for this slice
            if (config.contains(sliceKey + ".blocks")) {
                Map<String, Object> blockData = config.getConfigurationSection(sliceKey + ".blocks").getValues(false);
                for (Map.Entry<String, Object> entry : blockData.entrySet()) {
                    String[] coords = entry.getKey().split(",");
                    if (coords.length == 2) {
                        try {
                            int z = Integer.parseInt(coords[0]);
                            int y = Integer.parseInt(coords[1]);
                            String blockDataString = String.valueOf(entry.getValue());

                            // BlockDataとStringの両方を保存
                            BlockData block = Bukkit.createBlockData(blockDataString);
                            slice.setBlock(z, y, block);
                            slice.setBlockString(z, y, blockDataString);
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning(plugin.getMessageManager().getMessage("error.invalid_coordinates") + ": " + entry.getKey());
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning(plugin.getMessageManager().getMessage("error.invalid_block_data", presetName, entry.getValue()));
                        }
                    }
                }
            }

            slices.add(slice);
        }

        return new RoadPreset(presetName, slices, lengthX, widthZ, heightY, axisZOffset, axisYOffset);
    }

}
