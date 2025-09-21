package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

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

    private RouteCalculator calculator;
    private RouteVisualizer visualizer;
    private RouteEditListener routeEditListener; // フィールドとして宣言
    private BukkitTask routeEditTask;

    @Override
    public void onEnable() {
        this.calculator = new RouteCalculator();
        this.visualizer = new RouteVisualizer(this);
        this.routeEditListener = new RouteEditListener(this, calculator, visualizer); // フィールドを初期化

        getCommand("roadbrush").setExecutor(new BrushCommand(this));
        getCommand("roadedit").setExecutor(new RoadEditCommand(this, visualizer));
        getServer().getPluginManager().registerEvents(routeEditListener, this);

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
