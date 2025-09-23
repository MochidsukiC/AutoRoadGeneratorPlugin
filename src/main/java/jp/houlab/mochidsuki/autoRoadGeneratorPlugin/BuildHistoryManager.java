package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BuildHistoryManager {
    public static HashMap<UUID ,List<List<BlockPlacementInfo>>> buildHistory = new HashMap<>();

    public static void addBuildHistory(UUID uuid,List<BlockPlacementInfo> blockList){
        Collections.reverse(blockList);
        if(!buildHistory.containsKey(uuid)) buildHistory.put(uuid,new ArrayList<>());

        buildHistory.get(uuid).add(blockList);
    }

    public static void rollBack(UUID uuid, AutoRoadGeneratorPluginMain plugin){
        if(!buildHistory.get(uuid).isEmpty()){

            Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>(buildHistory.get(uuid).get(0));
            new BuildPlacementTask(plugin, uuid, placementQueue).runTaskTimer(plugin,1,1);
            buildHistory.get(uuid).remove(0);
        }
    }
}
