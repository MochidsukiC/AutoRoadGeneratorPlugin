package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.ReditCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.RobjCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.RroadCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands.RundoCommand;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.roadObjects.ObjectBrushListener;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.roadObjects.ObjectCreationSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.roadObjects.ObjectPresetManager;
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
 * プラグインのメインクラスです。
 */
public class AutoRoadGeneratorPluginMain extends JavaPlugin {

    private final Map<UUID, RouteSession> routeSessions = new ConcurrentHashMap<>();
    private final Set<UUID> editModePlayers = Collections.synchronizedSet(new HashSet<>());
    private final Map<UUID, PresetCreationSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ObjectCreationSession> objectCreationSessions = new ConcurrentHashMap<>();

    private RouteCalculator calculator;
    private RouteVisualizer visualizer;
    private RouteEditListener routeEditListener;
    private BukkitTask routeEditTask;

    private PresetManager presetManager;
    private ObjectPresetManager objectPresetManager;

    @Override
    public void onEnable() {
        this.calculator = new RouteCalculator();
        this.visualizer = new RouteVisualizer(this);
        this.routeEditListener = new RouteEditListener(this, calculator, visualizer);

        this.presetManager = new PresetManager(this);
        this.objectPresetManager = new ObjectPresetManager(this);

        RroadCommand rroadCommand = new RroadCommand(this, presetManager, playerSessions);
        getCommand("rroad").setExecutor(rroadCommand);
        getCommand("rroad").setTabCompleter(rroadCommand);

        RobjCommand robjCommand = new RobjCommand(this, objectCreationSessions, objectPresetManager);
        getCommand("robj").setExecutor(robjCommand);
        getCommand("robj").setTabCompleter(robjCommand);

        ReditCommand reditCommand = new ReditCommand(this, visualizer);
        getCommand("redit").setExecutor(reditCommand);
        getCommand("redit").setTabCompleter(reditCommand);

        getCommand("rundo").setExecutor(new RundoCommand(this));

        getServer().getPluginManager().registerEvents(routeEditListener, this);
        getServer().getPluginManager().registerEvents(new PresetListener(playerSessions), this);
        getServer().getPluginManager().registerEvents(new ObjectBrushListener(this, objectCreationSessions), this);

        this.routeEditTask = routeEditListener.runTaskTimerAsynchronously(this, 0L, 5L);

        getLogger().info("RoadEditor plugin has been enabled.");
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
        getLogger().info("RoadEditor plugin has been disabled.");
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
}
