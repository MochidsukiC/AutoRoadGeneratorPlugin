package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects.ObjectPreset;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Road と Object プリセットを統一管理するマネージャー
 */
public class UnifiedPresetManager {

    private final AutoRoadGeneratorPluginMain plugin;
    private final File presetsFolder;
    private final Map<String, PresetData> loadedPresets = new HashMap<>();

    public UnifiedPresetManager(AutoRoadGeneratorPluginMain plugin) {
        this.plugin = plugin;
        this.presetsFolder = new File(plugin.getDataFolder(), "presets");
        if (!presetsFolder.exists()) {
            presetsFolder.mkdirs();
        }
    }

    /**
     * プリセットタイプを自動判別して読み込み
     */
    public PresetData loadPreset(String name) {
        if (loadedPresets.containsKey(name)) {
            return loadedPresets.get(name);
        }

        File presetFile = new File(presetsFolder, name + ".yml");
        if (!presetFile.exists()) {
            plugin.getLogger().warning(plugin.getMessageManager().getMessage("road.preset_not_found", name));
            return null;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(presetFile);
        PresetData preset = null;

        try {
            // プリセットタイプを判別
            if (config.contains("blocks") && config.contains("initialYaw")) {
                // Object preset
                preset = loadObjectPreset(config);
            } else if (config.contains("lengthX") || config.contains("slices")) {
                // Road preset
                preset = loadRoadPreset(config);
            } else {
                plugin.getLogger().warning(plugin.getMessageManager().getMessage("log.unknown_preset_format", name));
                return null;
            }

            if (preset != null) {
                loadedPresets.put(name, preset);
                plugin.getLogger().info(plugin.getMessageManager().getMessage("log.preset_loaded_as", name, preset.getType()));
            }

        } catch (Exception e) {
            plugin.getLogger().severe(plugin.getMessageManager().getMessage("error.load_failed", name, e.getMessage()));
            e.printStackTrace();
        }

        return preset;
    }

    /**
     * RoadPreset読み込み（従来のPresetManagerロジックを使用）
     */
    private RoadPreset loadRoadPreset(YamlConfiguration config) {
        String presetName = config.getString("name");
        int lengthX = config.getInt("lengthX");
        int widthZ = config.getInt("widthZ");
        int heightY = config.getInt("heightY");
        int axisZOffset = config.getInt("axisZOffset", widthZ / 2);
        int axisYOffset = config.getInt("axisYOffset", heightY / 2);

        List<RoadPreset.PresetSlice> slices = new ArrayList<>();

        for (int x = 0; x < lengthX; x++) {
            String sliceKey = "slices." + x;
            int xPosition = config.getInt(sliceKey + ".xPosition", x);

            RoadPreset.PresetSlice slice = new RoadPreset.PresetSlice(xPosition, widthZ, heightY);

            if (config.contains(sliceKey + ".blocks")) {
                Map<String, Object> blockData = config.getConfigurationSection(sliceKey + ".blocks").getValues(false);
                for (Map.Entry<String, Object> entry : blockData.entrySet()) {
                    String[] coords = entry.getKey().split(",");
                    if (coords.length == 2) {
                        try {
                            int z = Integer.parseInt(coords[0]);
                            int y = Integer.parseInt(coords[1]);
                            String blockDataString = String.valueOf(entry.getValue());

                            // String版を先に保存、BlockDataは後で変換
                            slice.setBlockString(z, y, blockDataString);
                            slice.setBlock(z, y, Bukkit.createBlockData(blockDataString));
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning(plugin.getMessageManager().getMessage("error.invalid_coordinates") + ": " + entry.getKey());
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning(plugin.getMessageManager().getMessage("error.invalid_block_data", "unknown", entry.getValue()));
                        }
                    }
                }
            }

            slices.add(slice);
        }

        return new RoadPreset(presetName, slices, lengthX, widthZ, heightY, axisZOffset, axisYOffset);
    }

    /**
     * ObjectPreset読み込み
     */
    private ObjectPreset loadObjectPreset(YamlConfiguration config) {
        String presetName = config.getString("name");
        int initialYaw = config.getInt("initialYaw", 0);

        Map<Vector, BlockData> blocks = new HashMap<>();
        Vector dimensions = new Vector(0, 0, 0);

        if (config.contains("blocks")) {
            Map<String, Object> blockData = config.getConfigurationSection("blocks").getValues(false);
            for (Map.Entry<String, Object> entry : blockData.entrySet()) {
                String[] coords = entry.getKey().split(",");
                if (coords.length == 3) {
                    try {
                        double x = Double.parseDouble(coords[0]);
                        double y = Double.parseDouble(coords[1]);
                        double z = Double.parseDouble(coords[2]);
                        String blockDataString = String.valueOf(entry.getValue());

                        Vector position = new Vector(x, y, z);
                        BlockData blockDataObj = Bukkit.createBlockData(blockDataString);
                        blocks.put(position, blockDataObj);

                        // dimensions を更新
                        dimensions = new Vector(
                            Math.max(dimensions.getX(), Math.abs(x)),
                            Math.max(dimensions.getY(), Math.abs(y)),
                            Math.max(dimensions.getZ(), Math.abs(z))
                        );
                    } catch (Exception e) {
                        plugin.getLogger().warning(plugin.getMessageManager().getMessage("error.invalid_coordinates") + ": " + entry.getKey());
                    }
                }
            }
        }

        return new ObjectPreset(presetName, blocks, initialYaw, dimensions);
    }

    /**
     * プリセット保存（タイプ別に自動振り分け）
     */
    public void savePreset(PresetData preset) {
        if (preset instanceof RoadPreset) {
            saveRoadPreset((RoadPreset) preset);
        } else if (preset instanceof ObjectPreset) {
            saveObjectPreset((ObjectPreset) preset);
        } else {
            plugin.getLogger().warning(plugin.getMessageManager().getMessage("log.unknown_preset_type", preset.getClass().getSimpleName()));
        }
    }

    /**
     * RoadPreset保存
     */
    private void saveRoadPreset(RoadPreset preset) {
        File presetFile = new File(presetsFolder, preset.getName() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        config.set("name", preset.getName());
        config.set("type", "ROAD");
        config.set("lengthX", preset.getLengthX());
        config.set("widthZ", preset.getWidthZ());
        config.set("heightY", preset.getHeightY());
        config.set("axisZOffset", preset.getAxisZOffset());
        config.set("axisYOffset", preset.getAxisYOffset());

        // スライス保存
        for (int x = 0; x < preset.getSlices().size(); x++) {
            RoadPreset.PresetSlice slice = preset.getSlices().get(x);
            String sliceKey = "slices." + x;

            config.set(sliceKey + ".xPosition", slice.getXPosition());

            for (int z = 0; z < slice.getWidthZ(); z++) {
                for (int y = 0; y < slice.getHeightY(); y++) {
                    String blockDataString = slice.getBlockDataString(z, y);
                    if (blockDataString != null) {
                        config.set(sliceKey + ".blocks." + z + "," + y, blockDataString);
                    }
                }
            }
        }

        try {
            config.save(presetFile);
            loadedPresets.put(preset.getName(), preset);
            plugin.getLogger().info(plugin.getMessageManager().getMessage("log.road_preset_saved", preset.getName()));
        } catch (IOException e) {
            plugin.getLogger().severe(plugin.getMessageManager().getMessage("error.save_failed", "RoadPreset", e.getMessage()));
        }
    }

    /**
     * ObjectPreset保存
     */
    private void saveObjectPreset(ObjectPreset preset) {
        File presetFile = new File(presetsFolder, preset.getName() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        config.set("name", preset.getName());
        config.set("type", "OBJECT");
        config.set("initialYaw", preset.getInitialYaw());

        // blocksセクションにブロック情報を保存
        for (Map.Entry<Vector, BlockData> entry : preset.getBlocks().entrySet()) {
            Vector pos = entry.getKey();
            BlockData blockData = entry.getValue();
            String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            config.set("blocks." + key, blockData.getAsString());
        }

        try {
            config.save(presetFile);
            loadedPresets.put(preset.getName(), preset);
            plugin.getLogger().info(plugin.getMessageManager().getMessage("log.object_preset_saved", preset.getName()));
        } catch (IOException e) {
            plugin.getLogger().severe(plugin.getMessageManager().getMessage("error.save_failed", "ObjectPreset", e.getMessage()));
        }
    }

    /**
     * 利用可能なプリセット名一覧
     */
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

    /**
     * タイプ別プリセット名取得
     */
    public List<String> getPresetNamesByType(PresetData.PresetType type) {
        List<String> names = new ArrayList<>();
        for (String name : getPresetNames()) {
            PresetData preset = loadPreset(name);
            if (preset != null && preset.getType() == type) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * キャッシュクリア
     */
    public void clearCache() {
        loadedPresets.clear();
    }
}