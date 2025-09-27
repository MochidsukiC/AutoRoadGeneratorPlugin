package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.i18n;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 多言語対応メッセージの管理を行うマネージャークラス
 *
 * 言語ファイル（YAML）の読み込み・管理、プレイヤー固有の言語設定、
 * メッセージの変数置換機能を提供します。
 * サポート言語：日本語（ja）、英語（en）
 *
 * @author Mochidsuki
 * @version 1.0.0
 * @since 1.0.0
 */
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

    public String getMessage(Player player, String key, Object... args) {
        String playerLang = currentLanguage; // Default to server language
        if (player != null) {
            playerLang = player.getLocale().split("_")[0].toLowerCase();
        }

        String message = null;
        FileConfiguration langConfig = languageConfigs.get(playerLang);

        // 1. Try player's language
        if (langConfig != null) {
            message = langConfig.getString(key);
        }

        // 2. If not found, fall back to server's default language
        if (message == null && !playerLang.equals(currentLanguage)) {
            FileConfiguration serverLangConfig = languageConfigs.get(currentLanguage);
            if (serverLangConfig != null) {
                message = serverLangConfig.getString(key);
            }
        }

        // 3. If still not found, fall back to Japanese ("ja") as a final resort
        if (message == null && !currentLanguage.equals("ja") && !playerLang.equals("ja")) {
            FileConfiguration jaConfig = languageConfigs.get("ja");
            if (jaConfig != null) {
                message = jaConfig.getString(key);
            }
        }

        // 4. If still not found, return the key
        if (message == null) {
            return "[Missing: " + key + "]";
        }

        // 5. Replace placeholders
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
