package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PresetManager {

    private final JavaPlugin plugin;
    private final File presetsFolder;
    private final Map<String, RoadPreset> loadedPresets = new HashMap<>();

    public PresetManager(JavaPlugin plugin) {
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
        plugin.getLogger().info("savePreset呼び出し: " + preset.getName() + " (スライス数: " + preset.getSlices().size() + ")");
        // Convert legacy format to slice-based format before saving
        RoadPreset sliceBasedPreset = convertToSliceBased(preset);
        saveSliceBasedPreset(sliceBasedPreset);
    }

    private void saveSliceBasedPreset(RoadPreset preset) {
        plugin.getLogger().info("saveSliceBasedPreset開始: " + preset.getName() + " (スライス数: " + preset.getSlices().size() + ")");
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
            plugin.getLogger().info("キャッシュ保存前チェック: " + preset.getName() +
                " lengthX=" + preset.getLengthX() +
                " widthZ=" + preset.getWidthZ() +
                " heightY=" + preset.getHeightY() +
                " スライス数=" + preset.getSlices().size());

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
                plugin.getLogger().info("  スライス[" + i + "]: xPos=" + slice.getXPosition() +
                    " size=" + slice.getWidthZ() + "x" + slice.getHeightY() +
                    " ブロック数=" + blockCount);
            }

            loadedPresets.put(preset.getName(), preset);
            plugin.getLogger().info("Preset '" + preset.getName() + "' saved successfully (slice-based format). キャッシュに保存完了.");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save preset '" + preset.getName() + "': " + e.getMessage());
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
            plugin.getLogger().info("loadPreset: キャッシュから読み込み '" + name + "' (スライス数: " + cachedPreset.getSlices().size() + ") [CACHED]");

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
                plugin.getLogger().info("  [CACHED] 最初のスライス詳細: widthZ=" + firstSlice.getWidthZ() +
                    ", heightY=" + firstSlice.getHeightY() + ", ブロック数=" + blockCount);
            }

            return cachedPreset;
        }

        File presetFile = new File(presetsFolder, name + ".yml");
        if (!presetFile.exists()) {
            plugin.getLogger().warning("Preset '" + name + "' not found.");
            return null;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(presetFile);
        String format = config.getString("format", "legacy");

        RoadPreset preset = loadSliceBasedPreset(config);

        if (preset != null) {
            loadedPresets.put(name, preset);
            plugin.getLogger().info("Preset '" + name + "' loaded successfully (" + format + " format). ファイルから読み込みキャッシュに保存. スライス数: " + preset.getSlices().size() + " [FILE-LOADED]");

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
                plugin.getLogger().info("  [FILE-LOADED] 最初のスライス詳細: widthZ=" + firstSlice.getWidthZ() +
                    ", heightY=" + firstSlice.getHeightY() + ", ブロック数=" + blockCount);
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
                            plugin.getLogger().warning("Invalid coordinate format in preset: " + entry.getKey());
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Invalid block data in preset '"+presetName+"': " + entry.getValue());
                        }
                    }
                }
            }

            slices.add(slice);
        }

        return new RoadPreset(presetName, slices, lengthX, widthZ, heightY, axisZOffset, axisYOffset);
    }

}
