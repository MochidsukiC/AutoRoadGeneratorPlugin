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

public class WallPresetManager {

    private final JavaPlugin plugin;
    private final File presetsFolder;
    private final Map<String, WallPreset> loadedPresets = new HashMap<>();

    public WallPresetManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.presetsFolder = new File(plugin.getDataFolder(), "wallpresets");
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

    public void savePreset(WallPreset preset) {
        File presetFile = new File(presetsFolder, preset.getName() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        config.set("name", preset.getName());
        config.set("type", "WALL");
        config.set("format", "slice-based");
        config.set("lengthX", preset.getLengthX());
        config.set("widthZ", preset.getWidthZ());
        config.set("heightY", preset.getHeightY());
        config.set("axisXOffset", preset.getAxisXOffset());
        config.set("axisZOffset", preset.getAxisZOffset());
        config.set("axisYOffset", preset.getAxisYOffset());

        // Serialize slices (same structure as RoadPreset)
        if (preset.getSlices() != null) {
            for (int x = 0; x < preset.getSlices().size(); x++) {
                WallPreset.WallSlice slice = preset.getSlices().get(x);
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
            loadedPresets.put(preset.getName(), preset);
            plugin.getLogger().info("Wall preset '" + preset.getName() + "' saved successfully.");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save wall preset '" + preset.getName() + "': " + e.getMessage());
        }
    }

    public WallPreset loadPreset(String name) {
        if (loadedPresets.containsKey(name)) {
            return loadedPresets.get(name);
        }

        File presetFile = new File(presetsFolder, name + ".yml");
        if (!presetFile.exists()) {
            plugin.getLogger().warning("Wall preset '" + name + "' not found.");
            return null;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(presetFile);
        String type = config.getString("type");

        if (!"WALL".equals(type)) {
            plugin.getLogger().warning("Invalid preset type for wall preset '" + name + "': " + type);
            return null;
        }

        WallPreset preset = loadWallPreset(config);

        if (preset != null) {
            loadedPresets.put(name, preset);
            plugin.getLogger().info("Wall preset '" + name + "' loaded successfully.");
        }

        return preset;
    }

    private WallPreset loadWallPreset(YamlConfiguration config) {
        String presetName = config.getString("name");
        String format = config.getString("format", "legacy");

        // Handle both legacy (single slice) and new (multi-slice) formats
        if ("slice-based".equals(format) || config.contains("lengthX")) {
            // New 3D format
            int lengthX = config.getInt("lengthX");
            int widthZ = config.getInt("widthZ");
            int heightY = config.getInt("heightY");
            int axisXOffset = config.getInt("axisXOffset", lengthX / 2);
            int axisZOffset = config.getInt("axisZOffset", widthZ / 2);
            int axisYOffset = config.getInt("axisYOffset", heightY / 2);

            List<WallPreset.WallSlice> slices = new ArrayList<>();

            for (int x = 0; x < lengthX; x++) {
                String sliceKey = "slices." + x;
                int xPosition = config.getInt(sliceKey + ".xPosition", x);

                WallPreset.WallSlice slice = new WallPreset.WallSlice(xPosition, widthZ, heightY);

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
                                plugin.getLogger().warning("Invalid coordinate format in wall preset: " + entry.getKey());
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Invalid block data in wall preset '"+presetName+"': " + entry.getValue());
                            }
                        }
                    }
                }

                slices.add(slice);
            }

            return new WallPreset(presetName, slices, lengthX, widthZ, heightY, axisXOffset, axisZOffset, axisYOffset);

        } else {
            // Legacy format - convert to new format
            int widthZ = config.getInt("widthZ");
            int heightY = config.getInt("heightY");
            int axisZOffset = config.getInt("axisZOffset", widthZ / 2);
            int axisYOffset = config.getInt("axisYOffset", heightY / 2);

            // Create single slice
            WallPreset.WallSlice slice = new WallPreset.WallSlice(0, widthZ, heightY);

            if (config.contains("slice.blocks")) {
                Map<String, Object> blockData = config.getConfigurationSection("slice.blocks").getValues(false);
                for (Map.Entry<String, Object> entry : blockData.entrySet()) {
                    String[] coords = entry.getKey().split(",");
                    if (coords.length == 2) {
                        try {
                            int z = Integer.parseInt(coords[0]);
                            int y = Integer.parseInt(coords[1]);
                            String blockDataString = String.valueOf(entry.getValue());

                            BlockData block = Bukkit.createBlockData(blockDataString);
                            slice.setBlock(z, y, block);
                            slice.setBlockString(z, y, blockDataString);
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning("Invalid coordinate format in wall preset: " + entry.getKey());
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Invalid block data in wall preset '"+presetName+"': " + entry.getValue());
                        }
                    }
                }
            }

            List<WallPreset.WallSlice> slices = new ArrayList<>();
            slices.add(slice);

            return new WallPreset(presetName, slices, 1, widthZ, heightY, 0, axisZOffset, axisYOffset);
        }
    }
}