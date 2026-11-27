package org.dynmap;

import java.io.IOException;
import java.util.*;
import java.util.function.BiPredicate;
import org.dynmap.utils.TileFlags;

/** Two bounded passes: missing native coverage on every map, then pre-existing tiles. */
final class FullRenderOrder {
    private boolean refresh;
    private String active = "";
    private final Map<String,TileFlags> deferred = new LinkedHashMap<>(), filled = new LinkedHashMap<>();
    private final Map<String,TileFlags.Iterator> iterators = new HashMap<>();
    private final Map<String,Long> totals = new LinkedHashMap<>();
    FullRenderOrder() {}
    FullRenderOrder(ConfigurationNode saved) throws IOException {
        if(saved==null)return;
        String phase=saved.getString("phase","missing");
        if(!List.of("missing","refresh").contains(phase))throw new IOException("Invalid full-render ordering phase");
        refresh=phase.equals("refresh");active=saved.getString("active","");
        var maps=saved.getNodes("maps");if(maps!=null)for(var map:maps) {
            String name=map.getString("name","");if(name.isEmpty())throw new IOException("Invalid ordered map");
            var pending=new TileFlags();pending.load(map.getStrings("deferred",List.of()));deferred.put(name,pending);
            var completed=new TileFlags();completed.load(map.getStrings("filled",List.of()));filled.put(name,completed);
            totals.put(name,Math.max(0L,(long)map.getLong("total",pending.countFlags())));
        }
    }
    synchronized boolean refresh() {return refresh;}
    synchronized boolean known(String map) {return deferred.containsKey(map);}
    synchronized boolean anyMaps() {return !deferred.isEmpty();}
    synchronized boolean start(String map) {
        if(active.equals(map))return false;
        active=map;deferred.computeIfAbsent(map,k->new TileFlags());return true;
    }
    synchronized void finishMissing(String map,TileFlags rendered) {
        var flags=new TileFlags();flags.load(rendered.save());filled.put(map,flags);active="";
    }
    synchronized List<String> filled(String map) {var flags=filled.get(map);return flags==null?List.of():flags.save();}
    synchronized void beginRefresh() {
        refresh=true;active="";deferred.forEach((name,flags)->totals.put(name,(long)flags.countFlags()));
    }
    synchronized long deferredCount(String map) {var flags=deferred.get(map);return flags==null?0:flags.countFlags();}
    synchronized long total(String map) {return totals.getOrDefault(map,0L);}
    synchronized boolean pending(String map,RenderInventory inventory) {return refresh ? deferredCount(map)>0 : inventory.hasSeeds();}
    synchronized TileFlags.TileCoord next(String map,RenderInventory inventory,BiPredicate<Integer,Integer> exists,int budget) {
        var flags=deferred.computeIfAbsent(map,k->new TileFlags());
        if(refresh) {
            var coordinate=new TileFlags.TileCoord();if(!iterators.computeIfAbsent(map,k->flags.getIterator()).next(coordinate))return null;
            flags.setFlag(coordinate.x,coordinate.y,false);return coordinate;
        }
        for(int i=0;i<Math.max(1,budget);i++) {
            var coordinate=inventory.nextUnseenSeed(null);if(coordinate==null)return null;
            if(exists.test(coordinate.x,coordinate.y))flags.setFlag(coordinate.x,coordinate.y,true);
            else return coordinate;
        }
        return null;
    }
    synchronized ConfigurationNode save() {
        var saved=new ConfigurationNode();saved.put("phase",refresh?"refresh":"missing");saved.put("active",active);
        var maps=new ArrayList<ConfigurationNode>();deferred.forEach((name,flags)->{
            var map=new ConfigurationNode();map.put("name",name);map.put("deferred",flags.save());map.put("filled",filled(name));map.put("total",total(name));maps.add(map);
        });saved.put("maps",maps);return saved;
    }
}
