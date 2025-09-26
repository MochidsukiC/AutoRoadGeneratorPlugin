package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.i18n;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class MessageManager {

    private final Plugin plugin;
    private final Map<String, FileConfiguration> languageConfigs;
    private String currentLanguage;

    public MessageManager(Plugin plugin) {
        this.plugin = plugin;
        this.languageConfigs = new HashMap<>();
        this.currentLanguage = "ja"; // デフォルトは日本語

        loadLanguageFiles();
        loadCurrentLanguage();
    }

    private void loadLanguageFiles() {
        String[] languages = {"ja", "en"};

        // lang/フォルダを作成
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        for (String lang : languages) {
            String fileName = "messages_" + lang + ".yml";
            File langFile = new File(langFolder, fileName);

            // リソースからファイルをコピー（存在しない場合）
            if (!langFile.exists()) {
                plugin.saveResource("lang/" + fileName, false);
            }

            try {
                FileConfiguration config = YamlConfiguration.loadConfiguration(langFile);
                languageConfigs.put(lang, config);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load language file: " + fileName);
                e.printStackTrace();
            }
        }
    }

    private void loadCurrentLanguage() {
        FileConfiguration config = plugin.getConfig();
        this.currentLanguage = config.getString("language", "ja");

        // サポートされていない言語の場合は日本語にフォールバック
        if (!languageConfigs.containsKey(currentLanguage)) {
            this.currentLanguage = "ja";
        }
    }

    public String getMessage(String key, Object... args) {
        FileConfiguration langConfig = languageConfigs.get(currentLanguage);

        String message = null;
        if (langConfig != null) {
            message = langConfig.getString(key);
        }

        // 現在の言語で見つからない場合は日本語にフォールバック
        if (message == null && !currentLanguage.equals("ja")) {
            FileConfiguration jaConfig = languageConfigs.get("ja");
            if (jaConfig != null) {
                message = jaConfig.getString(key);
            }
        }

        // それでも見つからない場合はキー自体を返す
        if (message == null) {
            return "[Missing: " + key + "]";
        }

        // プレースホルダーの置換
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                message = message.replace("{" + i + "}", String.valueOf(args[i]));
            }
        }

        return message;
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }

    public void setLanguage(String language) {
        if (languageConfigs.containsKey(language)) {
            this.currentLanguage = language;

            // config.ymlも更新
            plugin.getConfig().set("language", language);
            plugin.saveConfig();
        }
    }

    public void reloadMessages() {
        languageConfigs.clear();
        loadLanguageFiles();
        loadCurrentLanguage();
    }
}