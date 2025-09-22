package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        File presetFile = new File(presetsFolder, preset.getName() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        config.set("name", preset.getName());
        config.set("dimensions.x", preset.getDimensions().getX());
        config.set("dimensions.y", preset.getDimensions().getY());
        config.set("dimensions.z", preset.getDimensions().getZ());

        // Serialize blocks map
        Map<String, String> serializedBlocks = preset.getBlocks().entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> vectorToString(entry.getKey()),
                        entry -> entry.getValue().getAsString()
                ));
        config.set("blocks", serializedBlocks);

        // Serialize axis path
        List<String> serializedAxisPath = preset.getAxisPath().stream()
                .map(this::vectorToString)
                .collect(Collectors.toList());
        config.set("axisPath", serializedAxisPath);

        try {
            config.save(presetFile);
            loadedPresets.put(preset.getName(), preset); // Cache the saved preset
            plugin.getLogger().info("Preset '" + preset.getName() + "' saved successfully.");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save preset '" + preset.getName() + "': " + e.getMessage());
        }
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

        String presetName = config.getString("name");
        Vector dimensions = new Vector(
                config.getDouble("dimensions.x"),
                config.getDouble("dimensions.y"),
                config.getDouble("dimensions.z")
        );

        // Deserialize blocks map
        Map<String, Object> rawBlocks = config.getConfigurationSection("blocks").getValues(false);
        Map<Vector, BlockData> blocks = rawBlocks.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> stringToVector(entry.getKey()),
                        entry -> Bukkit.createBlockData(String.valueOf(entry.getValue())) // Safely convert Object to String
                ));

        // Deserialize axis path
        List<String> serializedAxisPath = config.getStringList("axisPath");
        List<Vector> axisPath = serializedAxisPath.stream()
                .map(this::stringToVector)
                .collect(Collectors.toList());

        RoadPreset preset = new RoadPreset(presetName, dimensions, blocks, axisPath);
        loadedPresets.put(name, preset); // Cache the loaded preset
        plugin.getLogger().info("Preset '" + name + "' loaded successfully.");
        return preset;
    }

    private String vectorToString(Vector vector) {
        return vector.getBlockX() + "," + vector.getBlockY() + "," + vector.getBlockZ();
    }

    private Vector stringToVector(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid vector string format: " + s);
        }
        return new Vector(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])
        );
    }
}
