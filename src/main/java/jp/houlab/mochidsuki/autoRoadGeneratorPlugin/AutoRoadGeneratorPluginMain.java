package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.LanguageCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.ReditCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.RobjCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.RroadCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.RundoCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.WallPresetCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.i18n.MessageManager;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects.ObjectBrushListener;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects.ObjectCreationSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects.ObjectPresetManager;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.WallCreationSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.WallListener;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.WallPresetManager;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.PresetCreationSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.PresetListener;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.PresetManager;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteCalculator;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteEditListener;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteVisualizer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoRoadGeneratorPluginのメインクラス
 *
 * 道路生成・編集機能を提供するプラグインのエントリーポイントです。
 * 以下の機能を統合管理します：
 * - 道路プリセットの作成・管理・建設
 * - オブジェクトプリセットの配置システム
 * - 壁建設システム
 * - ルート編集・可視化システム
 * - 多言語対応システム
 * - 取り消し機能
 *
 * @author Mochidsuki
 * @version 1.0.0
 * @since 1.0.0
 */
public class AutoRoadGeneratorPluginMain extends JavaPlugin {

    private final Map<UUID, RouteSession> routeSessions = new ConcurrentHashMap<>();
    private final Set<UUID> editModePlayers = Collections.synchronizedSet(new HashSet<>());
    private final Map<UUID, PresetCreationSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ObjectCreationSession> objectCreationSessions = new ConcurrentHashMap<>();
    private final Map<UUID, WallCreationSession> wallCreationSessions = new ConcurrentHashMap<>();

    private RouteCalculator calculator;
    private RouteVisualizer visualizer;
    private RouteEditListener routeEditListener;
    private BukkitTask routeEditTask;

    private PresetManager presetManager;
    private ObjectPresetManager objectPresetManager;
    private WallPresetManager wallPresetManager;
    private MessageManager messageManager;

    @Override
    public void onEnable() {
        // 設定ファイルの初期化
        saveDefaultConfig();

        // MessageManagerの初期化
        this.messageManager = new MessageManager(this);

        this.calculator = new RouteCalculator();
        this.visualizer = new RouteVisualizer(this);
        this.routeEditListener = new RouteEditListener(this, calculator, visualizer);

        this.presetManager = new PresetManager(this);
        this.objectPresetManager = new ObjectPresetManager(this);
        this.wallPresetManager = new WallPresetManager(this);

        RroadCommand rroadCommand = new RroadCommand(this, presetManager, playerSessions);
        getCommand("rroad").setExecutor(rroadCommand);
        getCommand("rroad").setTabCompleter(rroadCommand);

        RobjCommand robjCommand = new RobjCommand(this, objectCreationSessions, objectPresetManager);
        getCommand("robj").setExecutor(robjCommand);
        getCommand("robj").setTabCompleter(robjCommand);

        WallPresetCommand wallCommand = new WallPresetCommand(this, wallPresetManager, wallCreationSessions);
        getCommand("rwall").setExecutor(wallCommand);
        getCommand("rwall").setTabCompleter(wallCommand);

        ReditCommand reditCommand = new ReditCommand(this, visualizer);
        getCommand("redit").setExecutor(reditCommand);
        getCommand("redit").setTabCompleter(reditCommand);

        getCommand("rundo").setExecutor(new RundoCommand(this));

        LanguageCommand langCommand = new LanguageCommand(this);
        getCommand("lang").setExecutor(langCommand);
        getCommand("lang").setTabCompleter(langCommand);

        getServer().getPluginManager().registerEvents(routeEditListener, this);
        getServer().getPluginManager().registerEvents(new PresetListener(this,playerSessions), this);
        getServer().getPluginManager().registerEvents(new ObjectBrushListener(this, objectCreationSessions), this);
        getServer().getPluginManager().registerEvents(new WallListener(this, wallCreationSessions), this);

        this.routeEditTask = routeEditListener.runTaskTimer(this, 0L, 20L);

        getLogger().info(messageManager.getMessage("plugin.enabled"));
    }

    @Override
    public void onDisable() {
        if (this.routeEditTask != null && !this.routeEditTask.isCancelled()) {
            this.routeEditTask.cancel();
        }
        for(Player player : getServer().getOnlinePlayers()){
            RouteSession session = routeSessions.get(player.getUniqueId());
            if(session != null){
                visualizer.hideAll(player, session);
            }
        }
        routeSessions.clear();
        editModePlayers.clear();
        getLogger().info(messageManager.getMessage("plugin.disabled"));
    }

    public RouteSession getRouteSession(UUID uuid) {
        return routeSessions.computeIfAbsent(uuid, k -> new RouteSession());
    }

    /**
     * 編集モードのプレイヤーのセットを取得します。
     * @return 編集モードのプレイヤーのUUIDセット
     */
    public Set<UUID> getEditModePlayers() {
        return editModePlayers;
    }

    /**
     * RouteEditListenerのインスタンスを取得します。
     * @return RouteEditListenerのインスタンス
     */
    public RouteEditListener getRouteEditListener() {
        return routeEditListener;
    }

    /**
     * MessageManagerのインスタンスを取得します。
     * @return MessageManagerのインスタンス
     */
    public MessageManager getMessageManager() {
        return messageManager;
    }
}
