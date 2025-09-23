package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.roadObjects;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class ObjectPresetManager {

    private final AutoRoadGeneratorPluginMain plugin;
    private final File objectPresetsFolder;
    private final Map<String, ObjectPreset> loadedPresets = new HashMap<>();

    public ObjectPresetManager(AutoRoadGeneratorPluginMain plugin) {
        this.plugin = plugin;
        this.objectPresetsFolder = new File(plugin.getDataFolder(), "presets/objects");
        if (!objectPresetsFolder.exists()) {
            objectPresetsFolder.mkdirs();
        }
    }

    public List<String> getPresetNames() {
        List<String> presetNames = new ArrayList<>();
        File[] files = objectPresetsFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                presetNames.add(file.getName().substring(0, file.getName().length() - 4));
            }
        }
        return presetNames;
    }

    public void savePreset(ObjectPreset preset) {
        File presetFile = new File(objectPresetsFolder, preset.getName() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        config.set("name", preset.getName());
        config.set("initialYaw", preset.getInitialYaw());
        config.set("dimensions.x", preset.getDimensions().getX());
        config.set("dimensions.y", preset.getDimensions().getY());
        config.set("dimensions.z", preset.getDimensions().getZ());

        ConfigurationSection blocksSection = config.createSection("blocks");
        for (Map.Entry<Vector, BlockData> entry : preset.getBlocks().entrySet()) {
            Vector pos = entry.getKey();
            String key = pos.getBlockX() + "," + pos.getBlockY() + "," + pos.getBlockZ();
            blocksSection.set(key, entry.getValue().getAsString());
        }

        try {
            config.save(presetFile);
            loadedPresets.put(preset.getName(), preset);
            plugin.getLogger().info("Object preset '" + preset.getName() + "' saved successfully.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save object preset '" + preset.getName() + "'.", e);
        }
    }

    public ObjectPreset loadPreset(String name) {
        if (loadedPresets.containsKey(name)) {
            return loadedPresets.get(name);
        }

        File presetFile = new File(objectPresetsFolder, name + ".yml");
        if (!presetFile.exists()) {
            return null;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(presetFile);

        String presetName = config.getString("name", name);
        int initialYaw = config.getInt("initialYaw");
        Vector dimensions = new Vector(
                config.getDouble("dimensions.x"),
                config.getDouble("dimensions.y"),
                config.getDouble("dimensions.z")
        );

        Map<Vector, BlockData> blocks = new HashMap<>();
        ConfigurationSection blocksSection = config.getConfigurationSection("blocks");
        if (blocksSection != null) {
            for (String key : blocksSection.getValues(false).keySet()) {
                String[] coords = key.split(",");
                if (coords.length == 3) {
                    try {
                        Vector pos = new Vector(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
                        BlockData blockData = Bukkit.createBlockData(blocksSection.getString(key));
                        blocks.put(pos, blockData);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid block data in object preset '" + name + "' at key '" + key + "'.");
                    }
                }
            }
        }

        ObjectPreset preset = new ObjectPreset(presetName, blocks, initialYaw, dimensions);
        loadedPresets.put(presetName, preset);
        plugin.getLogger().info("Object preset '" + presetName + "' loaded successfully.");
        return preset;
    }
}
