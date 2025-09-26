package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.PlayerMessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LanguageCommand implements CommandExecutor, TabCompleter {

    private final AutoRoadGeneratorPluginMain plugin;
    private static final List<String> SUPPORTED_LANGUAGES = Arrays.asList("ja", "en");

    public LanguageCommand(AutoRoadGeneratorPluginMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("roadadmin.language")) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "command.no_permission");
            return true;
        }

        if (args.length == 0) {
            // 現在の言語を表示
            String currentLang = plugin.getMessageManager().getCurrentLanguage();
            PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "language.current", currentLang);
            PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "language.available", String.join(", ", SUPPORTED_LANGUAGES));
            return true;
        }

        if (args.length == 1) {
            String newLanguage = args[0].toLowerCase();

            if (!SUPPORTED_LANGUAGES.contains(newLanguage)) {
                PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "language.unsupported", newLanguage);
                PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "language.available", String.join(", ", SUPPORTED_LANGUAGES));
                return true;
            }

            String oldLanguage = plugin.getMessageManager().getCurrentLanguage();
            plugin.getMessageManager().setLanguage(newLanguage);

            PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "language.changed", oldLanguage, newLanguage);
            return true;
        }

        PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "language.usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUPPORTED_LANGUAGES, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}