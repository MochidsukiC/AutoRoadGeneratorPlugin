package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

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
        this.presetsFolder = new File(plugin.getDataFolder(), "presets");
        if (!presetsFolder.exists()) {
            presetsFolder.mkdirs();
        }
    }

    public void savePreset(RoadPreset preset) {
        // Convert legacy format to slice-based format before saving
        RoadPreset sliceBasedPreset = convertToSliceBased(preset);
        saveSliceBasedPreset(sliceBasedPreset);
    }

    private void saveSliceBasedPreset(RoadPreset preset) {
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
            loadedPresets.put(preset.getName(), preset);
            plugin.getLogger().info("Preset '" + preset.getName() + "' saved successfully (slice-based format).");
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
            return loadedPresets.get(name);
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
            plugin.getLogger().info("Preset '" + name + "' loaded successfully (" + format + " format).");
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
                            BlockData block = Bukkit.createBlockData(String.valueOf(entry.getValue()));
                            slice.setBlock(z, y, block);
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning("Invalid coordinate format in preset: " + entry.getKey());
                        }
                    }
                }
            }

            slices.add(slice);
        }

        return new RoadPreset(presetName, slices, lengthX, widthZ, heightY, axisZOffset, axisYOffset);
    }

}
