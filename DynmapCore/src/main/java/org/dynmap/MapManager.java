// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.common.DynmapListenerManager.EventType;
import org.dynmap.debug.Debug;
import org.dynmap.exporter.OBJExport;
import org.dynmap.hdmap.HDMapManager;
import org.dynmap.markers.EnterExitMarker;
import org.dynmap.markers.EnterExitMarker.EnterExitText;
import org.dynmap.markers.impl.MarkerAPIImpl;
import org.dynmap.renderer.DynmapBlockState;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.MapStorageBaseTileEnumCB;
import org.dynmap.storage.MapStorageTileSearchEndCB;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.Polygon;
import org.dynmap.utils.TileFlags;

public class MapManager {
    public AsynchronousQueue<MapTile> tileQueue;

    private static final int DEFAULT_CHUNKS_PER_TICK = 200;
    private static final int DEFAULT_ZOOMOUT_PERIOD = 60;
    public List<DynmapWorld> worlds = new CopyOnWriteArrayList<DynmapWorld>();
    private List<String> disabled_worlds = new ArrayList<String>();
    public Map<String, DynmapWorld> worldsLookup = new HashMap<String, DynmapWorld>();
    private DynmapCore core;
    private long timeslice_int = 0; /* In milliseconds */
    private int max_chunk_loads_per_tick = DEFAULT_CHUNKS_PER_TICK;
    private int parallelrendercnt = 0;
    private double fullRenderOfflinePercent = 125, fullRenderOnlinePercent = 25;
    private volatile int fullRenderPlayers = 1;
    private int fullRenderOnlineWorkers = 1;
    private int progressinterval = 100;
    private long progressLogIntervalNanos=java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    private int tileupdatedelay = 30;
    private int savependingperiod = 15 * 60; // every 15 minutes, by default
    private int defaulttilescale = 0;
    private boolean saverestorepending = true;
    private final Object checkpointWriteLock = new Object();
    private ScheduledExecutorService checkpointWorker;
    private volatile Set<MapTile> staleOwnedAtStop = Set.of();
    private final Map<String,HashMap<String,Object>> stoppingJobs=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean pauseupdaterenders = false;
    private boolean hideores = false;
    private boolean useBrightnessTable = false;
    private boolean usenormalpriority = false;
    private HashMap<String, String> blockalias = new HashMap<String, String>();

    private boolean pausefullrenders = false;

    // TPS based render pauses
    private double tpslimit_updaterenders = 15.0;
    private double tpslimit_fullrenders = 15.0;
    private double tpslimit_zoomout = 15.0;
    private volatile boolean tpspauseupdaterenders = false;
    private volatile boolean tpspausefullrenders = false;
    private volatile boolean tpspausezoomout = false;
    private final TpsRenderGate updateTpsGate = new TpsRenderGate(), fullTpsGate = new TpsRenderGate(), zoomTpsGate = new TpsRenderGate();
    private volatile long tpsBypassUntil;
    private final RenderTpsNotifications updateTpsNotifications = new RenderTpsNotifications();
    private final RenderTpsNotifications zoomTpsNotifications = new RenderTpsNotifications();

    // User enter/exit processing
    private static final int DEFAULT_ENTEREXIT_PERIOD = 1000;	// 1 second
    private static final int DEFAULT_TITLE_FADEIN = 10;	// 10 ticks = 1/2 second
    private static final int DEFAULT_TITLE_STAY = 70;	// 70 ticks = 3 1/2 second
    private static final int DEFAULT_TITLE_FADEOUT = 20;	// 20 ticks = 1 second
    private static final boolean DEFAULT_ENTEREXIT_USETITLE = true;
    private static final boolean DEFAULT_ENTEREPLACESEXITS = false;
    private int enterexitperiod = DEFAULT_ENTEREXIT_PERIOD;	// Enter/exit processing period
    private int titleFadeIn = DEFAULT_TITLE_FADEIN;
    private int titleStay = DEFAULT_TITLE_STAY;
    private int titleFadeOut = DEFAULT_TITLE_FADEOUT;
    private boolean enterexitUseTitle = DEFAULT_ENTEREXIT_USETITLE;
    private boolean enterReplacesExits = DEFAULT_ENTEREPLACESEXITS;

    private HashMap<UUID, HashSet<EnterExitMarker>> entersetstate = new HashMap<UUID, HashSet<EnterExitMarker>>();

    private static class TextQueueRec {
        EnterExitText txt;
        boolean isEnter;
    }
    private static class SendQueueRec {
        DynmapPlayer player;
        ArrayList<TextQueueRec> queue = new ArrayList<TextQueueRec>();
        int tickdelay;
    };
    private HashMap<UUID, SendQueueRec> entersetsendqueue = new HashMap<UUID, SendQueueRec>();

    private boolean did_start = false;

    private int zoomout_period = DEFAULT_ZOOMOUT_PERIOD;	/* Zoom-out tile processing period, in seconds */
    /* Which fullrenders are active */
    private HashMap<String, FullWorldRenderState> active_renders = new HashMap<String, FullWorldRenderState>();

    /* Chunk load performance numbers */
    AtomicInteger chunk_caches_created = new AtomicInteger(0);
    AtomicInteger chunks_read[];
    AtomicLong chunks_read_times[];

    /* lock for our data structures */
    public static final Object lock = new Object();

    public static MapManager mapman;    /* Our singleton */
    public HDMapManager hdmapman;

    /* Thread pool for processing renders */
    private DynmapScheduledThreadPoolExecutor render_pool;
    private ExecutorService fullRenderBoostPool;
    private static final int POOL_SIZE = 3;

    /* Touch event queues */
    private static class TouchEvent {
        int x, y, z;
        String world;
        String reason;
        @Override
        public int hashCode() {
            return (x << 16) ^ (y << 24) ^ z;
        }
        @Override
        public boolean equals(Object o) {
            if(this == o) return true;
            TouchEvent te = (TouchEvent)o;
            if((x != te.x) || (y != te.y) || (z != te.z) || (world.equals(te.world) == false))
                return false;
            return true;
        }
    }
    private static class TouchVolumeEvent {
        int xmin, ymin, zmin;
        int xmax, ymax, zmax;
        String world;
        String reason;
    }
    private ConcurrentHashMap<TouchEvent, Object> touch_events = new ConcurrentHashMap<TouchEvent, Object>();
    private LinkedList<TouchVolumeEvent> touch_volume_events = new LinkedList<TouchVolumeEvent>();
    private Object touch_lock = new Object();

    private HashMap<String, MapStats> mapstats = new HashMap<String, MapStats>();

    private static class MapStats {
        int loggedcnt;
        int renderedcnt;
        int updatedcnt;
        int transparentcnt;
    }
    /* synchronized using 'lock' */
    private HashMap<String, TriggerStats> trigstats = new HashMap<String, TriggerStats>();


    private static class TriggerStats {
        long callsmade;
        long callswithtiles;
        long tilesqueued;
    }

    public DynmapWorld getWorld(String name) {
        DynmapWorld world = worldsLookup.get(name);
        if(world == null)
            world = worldsLookup.get(DynmapWorld.normalizeWorldName(name));
        return world;
    }

    public Collection<DynmapWorld> getWorlds() {
        return worlds;
    }

    public Collection<String> getDisabledWorlds() {
        return disabled_worlds;
    }

    public int getDefTileUpdateDelay() {
        return tileupdatedelay;
    }

    public int getDefaultTileScale() {
        return defaulttilescale;
    }

    private static class OurThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            if(!mapman.usenormalpriority)
                t.setPriority(Thread.MIN_PRIORITY);
            t.setName("MCCMap Render Thread");
            return t;
        }
    }

    private class DynmapScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
        DynmapScheduledThreadPoolExecutor() {
            super(POOL_SIZE + parallelrendercnt);
            this.setThreadFactory(new OurThreadFactory());
            /* Set shutdown policy to stop everything */
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        protected void afterExecute(Runnable r, Throwable x) {
            if(r instanceof FullWorldRenderState) {
                ((FullWorldRenderState)r).cleanup();
            }
            if(x != null) {
                Log.severe("Exception during render job: " + r);
                x.printStackTrace();
            }
        }
        @Override
        public void execute(final Runnable r) {
            try {
                super.execute(new Runnable() {
                    public void run() {
                        try {
                            r.run();
                        } catch (Exception x) {
                            if (!(x instanceof InterruptedException)) {	// Avoid shutdown noise
                                Log.severe("Exception during render job: " + r);
                                x.printStackTrace();
                            }
                        }
                    }
                });
            } catch (RejectedExecutionException rxe) {  /* Pool shutdown - nominal for reload or unload */
            }
        }
        @Override
        public ScheduledFuture<?> schedule(final Runnable command, long delay, TimeUnit unit) {
            try {
                return super.schedule(new Runnable() {
                    public void run() {
                        try {
                            command.run();
                        } catch (Exception x) {
                            if (!(x instanceof InterruptedException)) {	// Avoid shutdown noise
                                Log.severe("Exception during render job: " + command);
                                x.printStackTrace();
                            }
                        }
                    }
                }, delay, unit);
            } catch (RejectedExecutionException rxe) {
                return null;    /* Pool shut down when we reload or unload */
            }
        }
    }
    private static final String RENDERTYPE_FULLRENDER = "Full render";
    private static final String RENDERTYPE_RADIUSRENDER = "Radius render";
    private static final String RENDERTYPE_UPDATERENDER = "Update render";

    /* This always runs on render pool threads - no bukkit calls from here */
    private class FullWorldRenderState implements Runnable {
        DynmapWorld world;    /* Which world are we rendering */
        DynmapLocation loc;
        int    map_index = -1;    /* Which map are we on */
        MapType map;
        TileFlags found = null;
        TileFlags rendered = null;
        java.util.Deque<MapTile> renderQueue = null;
        MapTile tile0 = null;
        int rendercnt = 0;
        int skipcnt = 0;
        DynmapCommandSender sender;
        String player;
        long timeaccum;
        private long refreshWorkNanos;
        private boolean natural;
        private FullRenderSpeedPolicy speedPolicy = new FullRenderSpeedPolicy(
            parallelrendercnt, timeslice_int, fullRenderOfflinePercent, fullRenderOnlinePercent, fullRenderOnlineWorkers);
        HashSet<MapType> renderedmaps = new HashSet<MapType>();
        String activemaps;
        int activemapcnt;
        /* Min and max limits for chunk coords (for radius limit) */
        int cxmin, cxmax, czmin, czmax;
        String rendertype;
        boolean cancelled;
        boolean shutdown = false;
        boolean pausedforworld = false;
        boolean updaterender = false;
        boolean resume = false;
        boolean resumeInitDone = false;
        boolean quiet = false;
        private final RenderTpsNotifications tpsNotifications = new RenderTpsNotifications();
        String mapname;
        AtomicLong total_render_ns = new AtomicLong(0L);
        AtomicInteger rendercalls = new AtomicInteger(0);
        long lastPendingSaveTS = 0; // Timestamp of last pending state save (msec)
        private final Map<String,TileFlags> storedTileIds = new java.util.concurrent.ConcurrentHashMap<>();
        private final Set<MapTile> checkpointInFlight = new java.util.LinkedHashSet<>();
        private volatile boolean mapTransition;
        private HashMap<String,Object> lastSafeCheckpoint;
        private int resumeScanMap;
        private org.dynmap.storage.filetree.TileScanCursor resumeScanCursor = new org.dynmap.storage.filetree.TileScanCursor();
        private RenderInventory inventory = new RenderInventory();
        private FullRenderOrder fullOrder = new FullRenderOrder();
        private long startedAtMillis=System.currentTimeMillis();
        private final RenderProgress progress = new RenderProgress();
        private final RenderProgressLog progressLog=new RenderProgressLog();
        private volatile MissingTileCheck.Snapshot inventorySnapshot;
        private volatile boolean inventoryRequested;

        /* Full world, all maps render */
        FullWorldRenderState(DynmapWorld dworld, DynmapLocation l, DynmapCommandSender sender, String mapname, boolean updaterender, boolean resume) {
            this(dworld, l, sender, mapname, -1);
            if(updaterender) {
                rendertype = RENDERTYPE_UPDATERENDER;
                this.updaterender = true;
            }
            else {
                rendertype = RENDERTYPE_FULLRENDER;
            }
            this.resume = resume;
            lastSafeCheckpoint=saveState();
        }

        /* Full world, all maps render, with optional render radius */
        FullWorldRenderState(DynmapWorld dworld, DynmapLocation l, DynmapCommandSender sender, String mapname, int radius) {
            world = dworld;
            loc = l;
            found = new TileFlags();
            rendered = new TileFlags();
            renderQueue = new java.util.concurrent.ConcurrentLinkedDeque<MapTile>();
            this.sender = sender;
            if(sender instanceof DynmapPlayer)
                this.player = ((DynmapPlayer)sender).getName();
            else
                this.player = "";
            if(radius < 0) {
                cxmin = czmin = Integer.MIN_VALUE;
                cxmax = czmax = Integer.MAX_VALUE;
                rendertype = RENDERTYPE_FULLRENDER;
            }
            else {
                cxmin = ((int)l.x - radius)>>4;
                czmin = ((int)l.z - radius)>>4;
                cxmax = ((int)l.x + radius + 1)>>4;
                czmax = ((int)l.z + radius + 1)>>4;
                rendertype = RENDERTYPE_RADIUSRENDER;
            }
            this.mapname = mapname;
            lastSafeCheckpoint=saveState();
        }

        /* Single tile render - used for incremental renders */
        FullWorldRenderState(MapTile t) {
            world = getWorld(t.getDynmapWorld().getName());
            tile0 = t;
            cxmin = czmin = Integer.MIN_VALUE;
            cxmax = czmax = Integer.MAX_VALUE;
        }

        FullWorldRenderState(ConfigurationNode n) throws Exception {
            String w = n.getString("world", "");
            world = getWorld(w);
            if(world == null) throw new Exception();
            loc = new DynmapLocation();
            loc.world = world.getName();
            loc.x = (int)n.getDouble("locX", 0.0);
            loc.y = (int)n.getDouble("locY", 0.0);
            loc.z = (int)n.getDouble("locZ", 0.0);
            resume=n.getBoolean("resume",false);resumeInitDone=n.getBoolean("resume-init-done",false);resumeScanMap=n.getInteger("resume-scan-map",0);
            var resumeCursor=n.getNode("resume-scan-cursor");if(resumeCursor!=null)resumeScanCursor=new org.dynmap.storage.filetree.TileScanCursor(resumeCursor);
            var existing=n.getNode("resume-existing");if(existing!=null)for(String key:existing.keySet()){var flags=new TileFlags();flags.load(existing.getStrings(key,List.of()));storedTileIds.put(key,flags);}
            lastSafeCheckpoint=new HashMap<>(n.entries);
            map_index = n.getInteger("mapindex", -1);
            if(map_index >= 0) {
                String m = n.getString("map","");
                map = world.maps.get(map_index);
                if((map == null) || (map.getName().equals(m) == false)) {
                    throw new Exception();
                }
            }
            else {
                map_index = -1;
                map = null;
            }
            found = new TileFlags();
            List<String> sl = n.getStrings("found", null);
            if(sl != null)
                found.load(sl);
            rendered = new TileFlags();
            sl = n.getStrings("rendered", null);
            if(sl != null)
                rendered.load(sl);
            renderQueue = new java.util.concurrent.ConcurrentLinkedDeque<MapTile>();
            List<ConfigurationNode> tl = n.getNodes("queue");
            if(tl != null) {
                for(ConfigurationNode cn : tl) {
                    MapTile mt = MapTile.restoreTile(world, cn);
                    if(mt != null) {
                        renderQueue.add(mt);
                    }
                }
            }
            rendercnt = n.getInteger("count", 0);
            try { inventory = new RenderInventory(n.getNode("progress-inventory")); }
            catch (java.io.IOException | RuntimeException invalid) { inventory = new RenderInventory(); }
            fullOrder = new FullRenderOrder(n.getNode("full-render-order"));
            startedAtMillis=(long)n.getLong("started-at-millis",startedAtMillis);
            timeaccum = (long)n.getLong("timeaccum", 0);
            renderedmaps = new HashSet<MapType>();
            sl = n.getStrings("renderedmaps", null);
            if(sl != null) {
                for(String s : sl) {
                    for(int i = 0; i < world.maps.size(); i++) {
                        MapType mt = world.maps.get(i);
                        if(mt.getName().equals(s)) {
                            renderedmaps.add(mt);
                            break;
                        }
                    }
                }
                if(sl.size() > renderedmaps.size()) {   /* Missed one or more? */
                    throw new Exception();
                }
            }
            activemaps = n.getString("activemaps", "");
            activemapcnt = n.getInteger("activemapcnt", 0);
            cxmin = n.getInteger("cxmin", 0);
            cxmax = n.getInteger("cxmax", 0);
            czmin = n.getInteger("czmin", 0);
            czmax = n.getInteger("czmax", 0);
            rendertype = n.getString("rendertype", "");
            mapname = n.getString("mapname", null);
            player = n.getString("player", "");
            updaterender = rendertype.equals(RENDERTYPE_UPDATERENDER);
            if(rendertype.equals(RENDERTYPE_FULLRENDER) && !resume && n.getNode("full-render-order")==null) {
                inventory=new RenderInventory();renderQueue.clear();found.clear();rendered.clear();
                renderedmaps.clear();map_index=-1;map=null;rendercnt=0;timeaccum=0;
            }
            sender = null;
            if(player.length() > 0) {
                sender = core.getServer().getPlayer(player);
            }
        }

        public HashMap<String,Object> saveState() { synchronized(lock) {
            if(mapTransition)return lastSafeCheckpoint;
            HashMap<String,Object> snapshot=buildCheckpointState();lastSafeCheckpoint=snapshot;return snapshot;
        }}
        private HashMap<String,Object> buildCheckpointState() {
            HashMap<String,Object> v = new HashMap<String,Object>();

            v.put("world", world.getName());
            v.put("locX", loc.x);
            v.put("locY", loc.y);
            v.put("locZ", loc.z);
            if(map != null) {
                v.put("mapindex", map_index);
                v.put("map", map.getName());
            }
            else {
                v.put("mapindex", -1);
                v.put("map", "");
            }
            v.put("found", found.save());
            v.put("rendered", rendered.save());
            LinkedList<ConfigurationNode> queue = new LinkedList<ConfigurationNode>();
            var checkpointTiles=new java.util.LinkedHashSet<MapTile>(renderQueue);checkpointTiles.addAll(checkpointInFlight);
            for(MapTile tq : checkpointTiles) {
                ConfigurationNode n = tq.saveTile();
                if(n != null)
                    queue.add(n);
            }
            v.put("queue", queue);
            v.put("count", rendercnt);
            v.put("progress-inventory", inventory.save());
            v.put("full-render-order",fullOrder.save());
            v.put("started-at-millis",startedAtMillis);
            v.put("timeaccum", timeaccum);
            LinkedList<String> rmaps = new LinkedList<String>();
            for(MapType mt : renderedmaps) {
                rmaps.add(mt.getName());
            }
            v.put("renderedmaps", rmaps);
            v.put("activemaps", activemaps);
            v.put("activemapcnt", activemapcnt);
            v.put("cxmin", cxmin);
            v.put("cxmax", cxmax);
            v.put("czmin", czmin);
            v.put("czmax", czmax);
            v.put("rendertype", rendertype);
            if(mapname != null)
                v.put("mapname", mapname);
            v.put("player", player);
            v.put("resume",resume);v.put("resume-init-done",resumeInitDone);v.put("resume-scan-map",resumeScanMap);v.put("resume-scan-cursor",resumeScanCursor.save());
            var existing=new java.util.LinkedHashMap<String,Object>();storedTileIds.forEach((name,flags)->existing.put(name,flags.save()));v.put("resume-existing",existing);
            return v;
        }

        public String toString() {
            return "world=" + world.getName() + ", map=" + map;
        }

        public void cleanup() {
            if (natural && naturalRenders != null && !shutdown) naturalRenders.finished(this);
            if(tile0 == null) {
                synchronized(lock) {
                    String wn = world.getName();
                    FullWorldRenderState rs = active_renders.get(wn);
                    if (rs == this) {
                        active_renders.remove(wn);
                    }
                }
            }
            else {
                tileQueue.done(tile0);
                var refresh = staleTileRefresh;
                if (refresh != null && tile0 instanceof org.dynmap.hdmap.HDMapTile tile)
                    refresh.recordFinished(tile, refreshWorkNanos,
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeslice_int), parallelrendercnt);
            }
        }

        public void saveRefresh() {
            if (saverestorepending && world.isLoaded() && (savependingperiod > 0)) {
                savePending(this.world, true);    // Save the pending data for the given world
            }
        }

        public void run() {
            try { runWork(); }
            catch (RuntimeException failure) {
                if (!natural) throw failure;
                var automatic = naturalRenders;
                if (automatic != null) automatic.failed(this);
                Log.severe("Natural render stopped for '" + world.getName() + "'; use /mccmap naturalrender resume after correcting the error", failure);
            }
        }

        private void makeNatural() {
            natural = true; quiet = true; sender = null; player = ""; rendertype = "Natural render";
            speedPolicy = new FullRenderSpeedPolicy(parallelrendercnt, timeslice_int,
                fullRenderOfflinePercent,
                FullRenderSpeedPolicy.percent(core.configuration.getDouble("natural-render/players-online-percent", 10), 10), fullRenderOnlineWorkers);
        }

        private boolean fullPass() { return natural || rendertype.equals(RENDERTYPE_FULLRENDER); }
        private boolean orderedFull() {return !natural && !resume && RENDERTYPE_FULLRENDER.equals(rendertype);}
        private boolean orderedMap() {return orderedFull() && map instanceof org.dynmap.hdmap.HDMap && !map.isReadOnly();}
        private String renderLabel() {return rendertype+(orderedMap() ? fullOrder.refresh()?" existing-tile pass":" missing-tile pass" : "");}
        private long progressCompleted() {return rendercnt+(orderedMap()&&!fullOrder.refresh()?fullOrder.deferredCount(map.getName()):0);}

        /** Checks real native files, never display previews, across every sharing map/variant. */
        private boolean nativeCoverage(int x,int y) {
            var group=mapname==null?map.getMapsSharingRender(world):List.of(map);
            if(group.isEmpty())group=List.of(map);
            for(var target:group)if(!target.isReadOnly())for(var variant:target.getVariants()) {
                var tile=world.getMapStorage().getTile(world,target,x,y,0,variant);
                if(tile==null)return false;
                try {if(!tile.exists())return false;} finally {tile.cleanup();}
            }
            return true;
        }
        // -1 means yield/retry; 0 means this map's pass finished; 1 means a batch is ready.
        private int selectOrderedBatch() {
            if(!prepareProgressInventory())return -1;
            if(inventory.phase().equals("unavailable")) {
                sendMessage("Full render stopped: generated-tile inventory unavailable; existing tiles were not refreshed out of order. See console and retry.");
                cancelled=true;return -1;
            }
            var hd=(org.dynmap.hdmap.HDMap)map;
            synchronized(lock) {
                if(fullOrder.start(map.getName())) {
                    renderQueue.clear();found.clear();
                    if(fullOrder.refresh())rendered.load(fullOrder.filled(map.getName()));
                    sendMessage(renderLabel()+" of '"+world.getName()+"' maps ["+activemaps+"] started.");
                }
                int batch=fullRenderPlayers>0 ? Math.min(Math.max(1,parallelrendercnt),fullRenderOnlineWorkers)
                    : FullRenderSpeedPolicy.maximumParallelism(parallelrendercnt,fullRenderOfflinePercent);
                // Bound classification separately from rendering; no world-sized work queue.
                for(int i=renderQueue.size();i<batch;i++) {
                    var coordinate=fullOrder.next(map.getName(),inventory,this::nativeCoverage,Math.max(1,256/batch));
                    if(coordinate==null)break;
                    renderQueue.add(new org.dynmap.hdmap.HDMapTile(world,hd.getPerspective(),coordinate.x,coordinate.y,hd.getBoostZoom(),hd.getTileScale()));
                }
                if(!renderQueue.isEmpty())return 1;
                return fullOrder.pending(map.getName(),inventory)?-1:0;
            }
        }

        private void runWork() {
            long tstart = System.currentTimeMillis();
            MapTile tile = null;
            List<MapTile> tileset = null;

            if(cancelled) {
                cleanup();
                if (!shutdown) {
                    saveRefresh();
                }
                return;
            }
            if (natural && naturalPauseReason() != null) {
                progress.reset(); scheduleDelayedJob(this, 1000); return;
            }
            // If doing resume, load existing tile IDs here (constructor was stupid, and caused timeouts for non-trivial maps - need to check PRs better....
            if (resume && (!resumeInitDone)) { // if resume render AND init not completed
                sendMessage(String.format("Scanning map to find existing tiles for resume..."));
                while(resumeScanMap<world.maps.size()) {
                    var scanMap=world.maps.get(resumeScanMap);boolean done;
                    if (natural && !scanMap.getName().equals(mapname)) {
                        synchronized(lock) { resumeScanMap++; resumeScanCursor = new org.dynmap.storage.filetree.TileScanCursor(); }
                        continue;
                    }
                    try {done=((org.dynmap.storage.filetree.FileTreeMapStorage)world.getMapStorage()).scanTiles(world,scanMap,resumeScanCursor,4096,t->{if(t.zoom==0&&(!natural||NaturalRenderLedger.reusable(t)))synchronized(lock){storedTileIds.computeIfAbsent(scanMap.getName(),key->new TileFlags()).setFlag(t.x,t.y,true);}});}
                    catch(java.io.IOException failure){throw new IllegalStateException("Resume scan failed",failure);}
                    if(done)synchronized(lock){resumeScanMap++;resumeScanCursor=new org.dynmap.storage.filetree.TileScanCursor();}
                    if(System.currentTimeMillis()-lastPendingSaveTS>=savependingperiod*1000L){savePending(world,true);lastPendingSaveTS=System.currentTimeMillis();}
                    if(cancelled||Thread.currentThread().isInterrupted())return;
                    if (natural && naturalPauseReason() != null) { progress.reset(); scheduleDelayedJob(this, 1000); return; }
                }
                sendMessage(String.format("Scan complete - starting render"));
                resumeInitDone = true;	// Only due on first run
            }

            if(tile0 == null) {    /* Not single tile render */
                if (saverestorepending && world.isLoaded() && (savependingperiod > 0) && ((lastPendingSaveTS + (1000 *savependingperiod))  < System.currentTimeMillis())) {
                    savePending(this.world, true);    // Save the pending data for the given world
                    lastPendingSaveTS = System.currentTimeMillis();
                }
                if(pausefullrenders || tpspausefullrenders || storagePauseReason() != null) {
                    progress.reset();
                    scheduleDelayedJob(this, 20*5); /* Delay 5 seconds and retry */
                    return;
                }
                else if(world.isLoaded() == false) {    /* Update renders are paused? */
                    progress.reset();
                    if(!pausedforworld) {
                        pausedforworld = true;
                        logRoutineConsole("Paused " + rendertype + " for world '" + world.getName() + "' - world unloaded");
                    }
                    scheduleDelayedJob(this, 20*5); /* Delay 5 seconds and retry */
                    return;
                }
                else if(pausedforworld) {
                    pausedforworld = false;
                    logRoutineConsole("Unpaused " + rendertype + " for world '" + world.getName() + "' - world reloaded");
                }
                long pacingDelay = speedPolicy.remainingDelayNanos(System.nanoTime(), fullRenderPlayers > 0);
                if (inventory.done()) progress.sample(progressCompleted(), System.nanoTime() / 1000000, fullRenderPlayers > 0);
                if (pacingDelay > 0) {
                    // Recheck presence while waiting, without occupying a render worker.
                    scheduleDelayedJob(this, Math.max(1, Math.min(1000, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(pacingDelay))));
                    return;
                }
                if(orderedMap() && selectOrderedBatch()<0) {scheduleDelayedJob(this,1);return;}
                /* If render queue is empty, start next map */
                if(renderQueue.isEmpty()) {
                    // Natural passes also seed disconnected generated terrain, at the same
                    // paced rate. Keep the bitmap instead of enqueuing a whole world at once.
                    if (natural && map_index >= 0 && map instanceof org.dynmap.hdmap.HDMap hd) {
                        synchronized(lock) {
                            var coordinate = inventory.nextUnseenSeed(found);
                            if (coordinate != null) {
                                found.setFlag(coordinate.x, coordinate.y, true);
                                renderQueue.add(new org.dynmap.hdmap.HDMapTile(world, hd.getPerspective(), coordinate.x, coordinate.y, hd.getBoostZoom(), hd.getTileScale()));
                            }
                            if (!renderQueue.isEmpty() || inventory.hasSeeds()) { scheduleDelayedJob(this, 0); return; }
                        }
                    }
                    synchronized(lock){mapTransition=true;}
                    try {
                    if(map_index >= 0) { /* Finished a map? */
                        double msecpertile = (double)timeaccum / (double)((rendercnt>0)?rendercnt:1)/(double)activemapcnt;
                        int rndcalls = rendercalls.get();
                        if(rndcalls == 0) rndcalls = 1;
                        double rendtime = total_render_ns.doubleValue() * 0.000001 / rndcalls;
                        if(activemapcnt > 1) {
                            if (skipcnt > 1)
                                sendMessage(String.format("%s of maps [%s] of '%s' completed - %d tiles rendered each (%.2f msec/map-tile, %.2f msec per render) (%d tiles skipped)",
                                    renderLabel(), activemaps, world.getName(), rendercnt, msecpertile, rendtime, skipcnt));
                            else
                                sendMessage(String.format("%s of maps [%s] of '%s' completed - %d tiles rendered each (%.2f msec/map-tile, %.2f msec per render)",
                                    renderLabel(), activemaps, world.getName(), rendercnt, msecpertile, rendtime));
                        }
                        else {
                            if (skipcnt > 1)
                                sendMessage(String.format("%s of map '%s' of '%s' completed - %d tiles rendered (%.2f msec/map-tile, %.2f msec per render) (%d tiles skipped)",
                                    renderLabel(), activemaps, world.getName(), rendercnt, msecpertile, rendtime, skipcnt));
                            else
                                sendMessage(String.format("%s of map '%s' of '%s' completed - %d tiles rendered (%.2f msec/map-tile, %.2f msec per render)",
                                    renderLabel(), activemaps, world.getName(), rendercnt, msecpertile, rendtime));
                        }
                        skipcnt = 0;
                        /* Now, if fullrender, use the render bitmap to purge obsolete tiles */
                        if(orderedMap() && !fullOrder.refresh())fullOrder.finishMissing(map.getName(),rendered);
                        if(fullPass() && !natural && (!orderedMap() || fullOrder.refresh())) {
                            if(activemapcnt == 1) {
                                if(orderedMap())map.purgeOldTiles(world,rendered,startedAtMillis);
                                else map.purgeOldTiles(world, rendered);
                            }
                            else {
                                for(MapType mt : map.getMapsSharingRender(world)) {
                                    if(orderedMap())mt.purgeOldTiles(world,rendered,startedAtMillis);
                                    else mt.purgeOldTiles(world, rendered);
                                }
                            }
                        }
                        if (fullPass() && naturalRenders != null && (!orderedMap() || fullOrder.refresh())) {
                            var completed = mapname == null ? map.getMapsSharingRender(world) : List.of(map);
                            naturalRenders.completed(this, completed);
                        }
                    }
                    found.clear();
                    rendered.clear();
                    rendercnt = 0;
                    timeaccum = 0;
                    total_render_ns.set(0);
                    rendercalls.set(0);
                    /* Advance to next unrendered map */
                    while(map_index < world.maps.size()) {
                        map_index++;    /* Move to next one */
                        if(map_index >= world.maps.size()) break;
                        if(orderedFull() && fullOrder.refresh() && !fullOrder.known(world.maps.get(map_index).getName()))continue;
                        /* If single map render, see if this is our target */
                        if(mapname != null) {
                            if(world.maps.get(map_index).getName().equals(mapname)) {
                                break;
                            }
                        }
                        else {
                            if(renderedmaps.contains(world.maps.get(map_index)) == false)
                                break;
                        }
                    }
                    if(map_index >= world.maps.size()) {    /* Last one done? */
                        if(orderedFull() && !fullOrder.refresh() && fullOrder.anyMaps()) {
                            fullOrder.beginRefresh();renderedmaps.clear();map_index=-1;map=null;
                            sendMessage("Missing-tile pass complete for '"+world.getName()+"'; refreshing pre-existing tiles next.");
                            scheduleDelayedJob(this,0);return;
                        }
                        sendMessage(rendertype + " of '" + world.getName() + "' finished - 100%, 0s remaining (base render; cleanup/zoom repairs may continue).");
                        cleanup();
                        saveRefresh();
                        if(fullPass() && !natural && missingTileCheck != null) {
                            // Include completed maps restored from a persisted full render as well.
                            var completed=world.maps.stream().filter(mt -> !mt.isReadOnly() &&
                                (mapname == null ? renderedmaps.contains(mt) : mt.getName().equals(mapname)))
                                .filter(org.dynmap.hdmap.HDMap.class::isInstance)
                                .map(org.dynmap.hdmap.HDMap.class::cast).toList();
                            missingTileCheck.afterFullRender(world, completed);
                        }
                        return;
                    }
                    map = world.maps.get(map_index);
                    inventory = new RenderInventory();
                    inventorySnapshot = null;
                    inventoryRequested = false;
                    if(orderedMap() && fullOrder.refresh())inventory.completed(fullOrder.total(map.getName()));
                    progress.reset();
                    List<String> activemaplist = map.getMapNamesSharingRender(world);
                    /* Build active map list */
                    activemaps = "";
                    if(mapname != null) {
                        activemaps = mapname;
                        activemapcnt = 1;
                    }
                    else {
                        activemapcnt = 0;
                        for(String n : activemaplist) {
                            if(activemaps.length() > 0)
                                activemaps += ",";
                            activemaps += n;
                            activemapcnt++;
                        }
                    }
                    /* Mark all the concurrently rendering maps rendered */
                    renderedmaps.addAll(map.getMapsSharingRender(world));

                    /* Now, prime the render queue */
                    if (map.isReadOnly() == false) {
                        for (MapTile mt : map.getTiles(world, (int)loc.x, (int)loc.y, (int)loc.z)) {
                            if (!found.getFlag(mt.tileOrdinalX(), mt.tileOrdinalY())) {
                                found.setFlag(mt.tileOrdinalX(), mt.tileOrdinalY(), true);
                                renderQueue.add(mt);
                            }
                        }
                    }
                    if(!updaterender) { /* Only add other seed points for fullrender */
                        /* Add spawn location too (helps with some worlds where 0,64,0 may not be generated */
                        DynmapLocation sloc = world.getSpawnLocation();
                        for (MapTile mt : map.getTiles(world, (int)sloc.x, (int)sloc.y, (int)sloc.z)) {
                            if (!found.getFlag(mt.tileOrdinalX(), mt.tileOrdinalY())) {
                                found.setFlag(mt.tileOrdinalX(), mt.tileOrdinalY(), true);
                                renderQueue.add(mt);
                            }
                        }
                        if(world.seedloc != null) {
                            for(DynmapLocation seed : world.seedloc) {
                                for (MapTile mt : map.getTiles(world, (int)seed.x, (int)seed.y, (int)seed.z)) {
                                    if (!found.getFlag(mt.tileOrdinalX(),mt.tileOrdinalY())) {
                                        found.setFlag(mt.tileOrdinalX(),mt.tileOrdinalY(), true);
                                        renderQueue.add(mt);
                                    }
                                }
                            }
                        }
                    }
                    } finally {synchronized(lock){mapTransition=false;}}
                }
                if (!prepareProgressInventory()) {
                    scheduleDelayedJob(this, 1);
                    return;
                }
                if(orderedMap()) {
                    int selection=selectOrderedBatch();
                    if(selection<=0){scheduleDelayedJob(this,selection<0?1:0);return;}
                }
                int batchSize = speedPolicy.nextBatchSize(fullRenderPlayers > 0);
                if(batchSize > 1) { /* Doing parallel renders? */
                    tileset = new ArrayList<MapTile>();
                    for(int i = 0; i < batchSize; i++) {
                        tile = takeCheckpointTile();
                        if(tile != null)
                            tileset.add(tile);
                    }
                }
                else {
                    tile = takeCheckpointTile();
                }
            }
            else {    /* Else, single tile render */
                if(pauseupdaterenders || tpspauseupdaterenders || storagePauseReason() != null) {
                    scheduleDelayedJob(this, 5*20); /* Retry after 5 seconds */
                    return;
                }
                tile = tile0;
            }

            long batchStarted = System.nanoTime();
            boolean notdone = true;

            if(tileset != null) {
                long save_timeaccum = timeaccum;
                List<Future<Boolean>> rslt = new ArrayList<Future<Boolean>>();
                final int cnt = tileset.size();
                for(int i = 1; i < cnt; i++) {   /* Do all but first on other threads */
                    final MapTile mt = tileset.get(i);
                    if((mapman != null) && (mapman.render_pool != null)) {
                        final long ts = tstart;
                        ExecutorService executor = i >= Math.max(1, parallelrendercnt) && mapman.fullRenderBoostPool != null
                            ? mapman.fullRenderBoostPool : mapman.render_pool;
                        Future<Boolean> future = executor.submit(new Callable<Boolean>() {
                            public Boolean call() {
                                return processTile(mt, ts, cnt);
                            }
                        });
                        rslt.add(future);

                    }
                }
                /* Now, do our render (first one) */
                tile = tileset.get(0);
                notdone = processTile(tile, tstart, cnt);
                if ((!notdone) && (tile0 == null)) {    // Not completed? Push back on queue
                    renderQueue.push(tile);
                }
                /* Now, join with others */
                for(int i = 0; i < rslt.size(); i++) {
                    boolean rsltflag = false;
                    try {
                        rsltflag = rslt.get(i).get();
                    } catch (CancellationException cx) {
                        rsltflag = false;
                    } catch (ExecutionException xx) {
                        Log.severe("Execution exception while processing tile: ", xx.getCause());
                        rsltflag = false;
                    } catch (InterruptedException ix) {
                        rsltflag = false;
                    }
                    notdone = notdone && rsltflag;
                    if ((!rsltflag) && (tile0 == null)) {    // Not completed? Push back on queue
                        tile = tileset.get(i+1);
                        renderQueue.push(tile);
                    }
                }
                timeaccum = save_timeaccum + System.currentTimeMillis() - tstart;
            }
            else {
                notdone = processTile(tile, tstart, 1);
                if ((!notdone) && (tile0 == null)) {    // Not completed? Push back on queue
                    renderQueue.push(tile);
                }
            }

            if (storagePauseReason() != null) {
                if (tile0 == null) {
                    if (tileset != null) renderQueue.addAll(tileset);
                    else if (tile != null) renderQueue.addFirst(tile);
                }
                synchronized(lock){checkpointInFlight.clear();}
                scheduleDelayedJob(this, 5000);
                return;
            }
            synchronized(lock){checkpointInFlight.clear();}
            if(notdone) {
                if(tile0 == null) {    /* fullrender */
                    long finished = System.nanoTime();
                    speedPolicy.recordBatch(finished - batchStarted, tileset == null ? 1 : tileset.size(), finished);
                    scheduleDelayedJob(this, 0);
                }
                else {
                    cleanup();
                }
            }
            else {
                //cleanup();
                shutdownRender();
                if (tile0 != null) cleanup();
            }
        }

        private MapTile takeCheckpointTile(){synchronized(lock){MapTile tile=renderQueue.pollFirst();if(tile!=null)checkpointInFlight.add(tile);return tile;}}

        private boolean processTile(MapTile tile, long tstart, int parallelcnt) {
            var refresh = staleTileRefresh;
            var backgroundTile = tile0 != null && tile instanceof org.dynmap.hdmap.HDMapTile hd ? hd : null;
            long started = System.nanoTime();
            if (refresh != null && backgroundTile != null) refresh.recordStarted(backgroundTile);
            boolean threw = true;
            try {
                boolean result = processTileWork(tile, tstart, parallelcnt);
                threw = false;
                return result;
            } finally {
                if (backgroundTile != null) refreshWorkNanos += System.nanoTime() - started;
                if (threw && tile0 != null) cleanup();
            }
        }

        private boolean processTileWork(MapTile tile, long tstart, int parallelcnt) {
            /* Get list of chunks required for tile */
            List<DynmapChunk> requiredChunks = tile.getRequiredChunks();
            /* If we are doing radius limit render, see if any are inside limits */
            if(cxmin != Integer.MIN_VALUE) {
                boolean good = false;
                for(DynmapChunk c : requiredChunks) {
                    if((c.x >= cxmin) && (c.x <= cxmax) && (c.z >= czmin) && (c.z <= czmax)) {
                        good = true;
                        break;
                    }
                }
                if(!good) requiredChunks = Collections.emptyList();
            }
            /* Fetch chunk cache from server thread */
            MapChunkCache cache = core.getServer().createMapChunkCache(world, requiredChunks, tile.isBlockTypeDataNeeded(),
                                                      tile.isHightestBlockYDataNeeded(), tile.isBiomeDataNeeded(),
                                                      tile.isRawBiomeDataNeeded());
            if(cache == null) {
                /* If world unloaded, don't cancel */
                if(world.isLoaded() == false) {
                    if(orderedMap())synchronized(lock){renderQueue.addFirst(tile);}
                    return true;
                }
                return false; /* Cancelled/aborted */
            }
            /* Update stats */
            chunk_caches_created.incrementAndGet();
            for (MapChunkCache.ChunkStats cs : MapChunkCache.ChunkStats.values()) {
                chunks_read[cs.ordinal()].addAndGet(cache.getChunksLoaded(cs));
                chunks_read_times[cs.ordinal()].addAndGet(cache.getTotalRuntimeNanos(cs));
            }

            boolean skipTile = false;
            if (resume) {
                var flags=storedTileIds.get(map.getName());
                skipTile = flags!=null&&flags.getFlag(tile.tileOrdinalX(),tile.tileOrdinalY());
            }

            if(tile0 != null) {    /* Single tile? */
                if(cache.isEmpty() == false) {
                    if (skipTile) {
                        skipcnt++;
                    } else {
                        tile.render(cache, null);
                    }
                }
            }
            else {
                /* Remove tile from tile queue, since we're processing it already */
                tileQueue.remove(tile);
                /* Switch to not checking if rendered tile is blank - breaks us on skylands, where tiles can be nominally blank - just work off chunk cache empty */
                if (cache.isEmpty() == false) {
                    boolean upd;
                    if (skipTile) {
                        upd = false;
                        skipcnt++;
                    } else {
                        long rt0 = System.nanoTime();
                        upd = tile.render(cache, mapname);
                        total_render_ns.addAndGet(System.nanoTime()-rt0);
                        rendercalls.incrementAndGet();
                    }
                    synchronized(lock) {
                        rendered.setFlag(tile.tileOrdinalX(), tile.tileOrdinalY(), true);
                        if(!orderedMap() && (upd || (!updaterender))) {    /* If updated or not an update render */
                            /* Add adjacent unrendered tiles to queue */
                            for (MapTile adjTile : map.getAdjecentTiles(tile)) {
                                if (!found.getFlag(adjTile.tileOrdinalX(),adjTile.tileOrdinalY())) {
                                    found.setFlag(adjTile.tileOrdinalX(), adjTile.tileOrdinalY(), true);
                                    renderQueue.add(adjTile);
                                }
                            }
                        }
                    }
                }
                synchronized(lock) {
                    if(!cache.isEmpty()) {
                        rendercnt++;
                        timeaccum += System.currentTimeMillis() - tstart;
                        if (((rendercnt % progressinterval) == 0) && (!quiet) && progressLog.due(System.nanoTime(),progressLogIntervalNanos)) {
                            progress.sample(progressCompleted(), System.nanoTime() / 1000000, fullRenderPlayers > 0);
                            sendMessage(progressSummary());
                            skipcnt = 0;
                        }
                    }
                }
            }
            /* And unload what we loaded */
            cache.unloadChunks();

            return true;
        }

        private boolean prepareProgressInventory() {
            if (natural || (orderedMap() && !fullOrder.refresh())) inventory.retainSeeds();
            if (inventory.done()) return true;
            if (updaterender || !(map instanceof org.dynmap.hdmap.HDMap hd)) {
                inventory.unavailable();
                return true;
            }
            if (!inventoryRequested) {
                inventoryRequested = true;
                if (!quiet) sendMessage(rendertype + " of '" + world.getName() + "' maps [" + activemaps
                    + "]: counting generated terrain for progress estimate...");
                try {
                    core.getServer().scheduleServerTask(() -> {
                        try {
                            inventorySnapshot = core.getServer().getRenderCheckSnapshot(world);
                            if (inventorySnapshot == null) inventory.unavailable();
                        } catch (RuntimeException unavailable) { inventory.unavailable(); }
                    }, 0);
                } catch (RuntimeException unavailable) {
                    inventory.unavailable();
                    return true;
                }
                return false;
            }
            var snapshot = inventorySnapshot;
            if (snapshot == null) return inventory.done();
            try {
                boolean done = inventory.step(world, hd, snapshot, cxmin, cxmax, czmin, czmax);
                if (done) progress.reset();
                return done;
            } catch (java.io.InterruptedIOException stopped) {
                return false;
            } catch (java.io.IOException | RuntimeException unavailable) {
                Log.warning("Render inventory unavailable for '" + world.getName() + (orderedMap() ? "'; full render will stop safely" : "'; rendering continues"));
                inventory.unavailable();
                return true;
            }
        }

        private String progressSummary() {
            String pause = pausefullrenders ? "manual" : tpspausefullrenders ? "low TPS"
                : !world.isLoaded() ? "world unloaded" : storagePauseReason();
            if (natural && pause == null) pause = naturalPauseReason();
            String prefix = renderLabel() + " of '" + world.getName() + "' maps [" + activemaps + "]: ";
            if (map == null) return rendertype + " of '" + world.getName() + "': preparing first map; ETA "
                + (pause == null ? "calculating" : "paused (" + pause + ")");
            if (!inventory.done() && pause != null) return prefix + "counting terrain; ETA paused (" + pause + ")";
            if (!inventory.done()) return prefix + "counting terrain (" + inventory.phase() + "); ETA calculating";
            return prefix + progress.describe(progressCompleted(), inventory.total(), System.nanoTime() / 1000000, pause);
        }

        public void cancelRender() {
            cancelled = true;
            storedTileIds.clear();
        }

        public void shutdownRender() {
            shutdown = true;
            cancelRender();
        }
        private void logRoutineConsole(String msg) {
            if(natural) Log.verboseinfo(msg);
            else Log.info(msg);
        }
        public void sendMessage(String msg) {
            if(!natural && sender != null) sender.sendMessage(msg);
            else logRoutineConsole(msg);
        }
    }

    private class ProcessOBJExport implements Runnable {
        private OBJExport exp;
        private DynmapCommandSender sender;

        public void run() {
            exp.processExport(sender);
        }
    }

    private class CheckWorldTimes implements Runnable {
        HashMap<String, Polygon> last_worldborder = new HashMap<String, Polygon>();
        public void run() {
            Future<Integer> f = core.getServer().callSyncMethod(new Callable<Integer>() {
                public Integer call() throws Exception {
                    long now_nsec = System.nanoTime();
                    for(DynmapWorld w : worlds) {
                        if(w.isLoaded()) {
                            int new_servertime = (int)(w.getTime() % 24000);
                            /* Check if we went from night to day */
                            boolean wasday = w.servertime >= 0 && w.servertime < 13700;
                            boolean isday = new_servertime >= 0 && new_servertime < 13700;
                            w.servertime = new_servertime;
                            if(wasday != isday) {
                                pushUpdate(w, new Client.DayNight(isday));
                            }
                            // Check world border
                            Polygon wb = w.showborder && core.configuration.getString("world-border/source", "worldborder").equalsIgnoreCase("vanilla")
                                ? w.getWorldBorder() : null;
                            Polygon oldwb = last_worldborder.get(w.getName());
                            if (!java.util.Objects.equals(wb, oldwb)) {
                                if (wb == null) last_worldborder.remove(w.getName());
                                else last_worldborder.put(w.getName(), wb);
                                core.listenerManager.processWorldEvent(EventType.WORLD_SPAWN_CHANGE, w);
                            }
                        }
                        /* Tick invalidated tiles processing */
                        for(MapTypeState mts : w.mapstate) {
                            mts.tickMapTypeState(now_nsec);
                        }
                    }
                    return 0;
                }
            });
            if (f == null) {
                return;
            }
            try {
                f.get();
            } catch (CancellationException cx) {
                return;
            } catch (InterruptedException cx) {
                return;
            } catch (ExecutionException ex) {
                Log.severe("Error while checking world times: ", ex.getCause());
            } catch (Exception ix) {
                Log.severe(ix);
            }
            scheduleDelayedJob(this, 5000);
        }
    }

    private class DoZoomOutProcessing implements Runnable {
        public void run() {
            if (!tpspausezoomout && storagePauseReason() == null) {
                Debug.debug("DoZoomOutProcessing started");
                ArrayList<DynmapWorld> wl = new ArrayList<DynmapWorld>(worlds);
                for(DynmapWorld w : wl) {
                    w.freshenZoomOutFiles();
                }
                Debug.debug("DoZoomOutProcessing finished");
                scheduleDelayedJob(this, zoomout_period*1000);
            }
            else {
                scheduleDelayedJob(this, 5*1000);
            }
        }
    }

    private class DoTouchProcessing implements Runnable {
        public void run() {
            processTouchEvents();
            if (storagePauseReason() != null) {
                scheduleDelayedJob(this, 1000);
                return;
            }
            /* Try to keep update queue above accelerate level, if we have enough */
            int cnt = 2*tileQueue.accelDequeueThresh;
            if(cnt < 100) cnt = 100;
            cnt = cnt - tileQueue.size();
            if(cnt > 0) {
                addNextTilesToUpdate(cnt);
            }
            scheduleDelayedJob(this, 1000); /* Once per second */
        }
    }

    private void sendPlayerEnterExit(DynmapPlayer player, EnterExitText txt) {
        core.getServer().scheduleServerTask(new Runnable() {
            public void run() {
                if (enterexitUseTitle) {
                    player.sendTitleText(txt.title, txt.subtitle, titleFadeIn, titleStay, titleFadeOut);
                }
                else {
                    if (txt.title != null) player.sendMessage(txt.title);
                    if (txt.subtitle != null) player.sendMessage(txt.subtitle);
                }
            }
        }, 0);
    }

    private void enqueueMessage(UUID uuid, DynmapPlayer player, EnterExitText txt, boolean isEnter) {
        SendQueueRec rec = entersetsendqueue.get(uuid);
        if (rec == null) {
            rec = new SendQueueRec();
            rec.player = player;
            rec.tickdelay = 0;
            entersetsendqueue.put(uuid, rec);
        }
        TextQueueRec txtrec = new TextQueueRec();
        txtrec.isEnter = isEnter;
        txtrec.txt = txt;
        rec.queue.add(txtrec);
        // If enter replaces exits, and we just added enter, purge exits
        if (enterReplacesExits && isEnter) {
            ArrayList<TextQueueRec> newlst = new ArrayList<TextQueueRec>();
            for (TextQueueRec r : rec.queue) {
                if (r.isEnter) newlst.add(r);	// Keep the enter records
            }
            rec.queue = newlst;
        }
    }

    private class DoUserMoveProcessing implements Runnable {
        public void run() {
            HashMap<UUID, HashSet<EnterExitMarker>> newstate = new HashMap<UUID, HashSet<EnterExitMarker>>();
            DynmapPlayer[] pl = core.playerList.getOnlinePlayers();
            for (DynmapPlayer player : pl) {
                if (player == null) continue;
                UUID puuid = player.getUUID();
                HashSet<EnterExitMarker> newset = new HashSet<EnterExitMarker>();
                DynmapLocation dl = player.getLocation();
                if (dl != null) {
                    MarkerAPIImpl.getEnteredMarkers(dl.world, dl.x, dl.y, dl.z, newset);
                }
                HashSet<EnterExitMarker> oldset = entersetstate.get(puuid);
                // See which we just left
                if (oldset != null) {
                    for (EnterExitMarker m : oldset) {
                        EnterExitText txt = m.getFarewellText();
                        if ((txt != null) && (newset.contains(m) == false)) {
                            enqueueMessage(puuid, player, txt, false);
                        }
                    }
                }
                // See which we just entered
                for (EnterExitMarker m : newset) {
                    EnterExitText txt = m.getGreetingText();
                    if ((txt != null) && ((oldset == null) || (oldset.contains(m) == false))) {
                        enqueueMessage(puuid, player, txt, true);
                    }
                }
                newstate.put(puuid, newset);
            }
            entersetstate = newstate;	// Replace old with new

            // Go through queues - send pending messages
            List<UUID> keys = new ArrayList<UUID>(entersetsendqueue.keySet());
            for (UUID id : keys) {
                SendQueueRec rec = entersetsendqueue.get(id);
                // Check delay - count down if needed
                if (rec.tickdelay > enterexitperiod) {
                    rec.tickdelay -= enterexitperiod;
                    continue;
                }
                rec.tickdelay = 0;
                // If something to send, send it
                if (rec.queue.size() > 0) {
                    TextQueueRec txt = rec.queue.remove(0);
                    sendPlayerEnterExit(rec.player, txt.txt);	// And send it
                    rec.tickdelay = 50 * (titleFadeIn + 10);	// Delay by fade in time plus 1/2 second
                }
                else {	// Else, if we are empty and exhausted delay, remove it
                    entersetsendqueue.remove(id);
                }
            }
            if (enterexitperiod > 0) {
                scheduleDelayedJob(this, enterexitperiod);
            }
        }
    }

    private void addNextTilesToUpdate(int cnt) {
        ArrayList<MapTile> tiles = new ArrayList<MapTile>();
        TileFlags.TileCoord coord = new TileFlags.TileCoord();
        while(cnt > 0) {
            tiles.clear();
            for(DynmapWorld w : worlds) {
                for(MapTypeState mts : w.mapstate) {
                    if (mts.type.isReadOnly()) {
                        continue;
                    }

                    if(mts.getNextInvalidTileCoord(coord)) {
                        mts.type.addMapTiles(tiles, w, coord.x, coord.y);
                        mts.validateTile(coord.x, coord.y);
                    }
                }
            }
            if(tiles.size() == 0) {
                return;
            }
            for(MapTile mt : tiles) {
                tileQueue.push(mt);
                cnt--;
            }
        }
    }

    public MapManager(DynmapCore core, ConfigurationNode configuration) {
        this.core = core;
        mapman = this;

        chunks_read = new AtomicInteger[MapChunkCache.ChunkStats.values().length];
        chunks_read_times = new AtomicLong[MapChunkCache.ChunkStats.values().length];
        for (int i = 0; i < MapChunkCache.ChunkStats.values().length; i++) {
            chunks_read[i] = new AtomicInteger(0);
            chunks_read_times[i] = new AtomicLong(0L);
        }

        /* Get block hiding data, if any */
        hideores = configuration.getBoolean("hideores", false);
        useBrightnessTable = configuration.getBoolean("use-brightness-table", false);

        blockalias = new HashMap<String, String>();
        if (hideores) {
            setBlockAlias(DynmapBlockState.GOLD_ORE_BLOCK, DynmapBlockState.STONE_BLOCK); // Gold ore
            setBlockAlias(DynmapBlockState.IRON_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Iron ore
            setBlockAlias(DynmapBlockState.COAL_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Coal ore
            setBlockAlias(DynmapBlockState.LAPIS_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Lapis ore
            setBlockAlias(DynmapBlockState.DIAMOND_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Diamond ore
            setBlockAlias(DynmapBlockState.REDSTONE_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Redstone ore
            setBlockAlias(DynmapBlockState.LIT_REDSTONE_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Glowing Redstone ore
            setBlockAlias(DynmapBlockState.EMERALD_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Emerald ore
            setBlockAlias(DynmapBlockState.QUARTZ_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Nether quartz ore
            setBlockAlias(DynmapBlockState.NETHER_GOLD_ORE_BLOCK, DynmapBlockState.NETHERRACK_BLOCK); // Nether Gold ore
            setBlockAlias(DynmapBlockState.DEEPSLATE_GOLD_ORE_BLOCK, DynmapBlockState.DEEPSLATE_BLOCK); // Deepslate gold ore
            setBlockAlias(DynmapBlockState.DEEPSLATE_IRON_ORE_BLOCK, DynmapBlockState.DEEPSLATE_BLOCK); // Deepslate iron ore
            setBlockAlias(DynmapBlockState.DEEPSLATE_COAL_ORE_BLOCK, DynmapBlockState.DEEPSLATE_BLOCK); // Deepslate coal ore
            setBlockAlias(DynmapBlockState.DEEPSLATE_LAPIS_ORE_BLOCK, DynmapBlockState.DEEPSLATE_BLOCK); // Deepslate lapis ore
            setBlockAlias(DynmapBlockState.DEEPSLATE_DIAMOND_ORE_BLOCK, DynmapBlockState.DEEPSLATE_BLOCK); // Deepslate diamond ore
            setBlockAlias(DynmapBlockState.DEEPSLATE_REDSTONE_ORE_BLOCK, DynmapBlockState.DEEPSLATE_BLOCK); // Deepslate redstone ore
            setBlockAlias(DynmapBlockState.DEEPSLATE_EMERALD_ORE_BLOCK, DynmapBlockState.DEEPSLATE_BLOCK); // Deepslate emerald ore
            setBlockAlias(DynmapBlockState.DEEPSLATE_COPPER_ORE_BLOCK, DynmapBlockState.DEEPSLATE_BLOCK); // Deepslate copper ore
            setBlockAlias(DynmapBlockState.COPPER_ORE_BLOCK, DynmapBlockState.STONE_BLOCK);  // Copper ore
        }
        ConfigurationNode ba = configuration.getNode("block-alias");
        if (ba != null) {
            for (String id : ba.keySet()) {
                String srcname = id.trim();
                String newname = ba.getString(id, srcname);
                if (srcname != newname) {
                    setBlockAlias(srcname, newname);
                }
            }
        }

        /* See what priority to use */
        usenormalpriority = configuration.getBoolean("usenormalthreadpriority", false);

        /* Initialize HD map manager */
        hdmapman = new HDMapManager();
        hdmapman.loadHDShaders(core);
        hdmapman.loadHDPerspectives(core);
        hdmapman.loadHDLightings(core);
        parallelrendercnt = configuration.getInteger("parallelrendercnt", 0);
        fullRenderOfflinePercent = FullRenderSpeedPolicy.percent(configuration.getDouble("full-render-speed/no-players-percent", 125), 125);
        fullRenderOnlinePercent = FullRenderSpeedPolicy.percent(configuration.getDouble("full-render-speed/players-online-percent", 25), 25);
        fullRenderOnlineWorkers = Math.max(1,Math.min(64,configuration.getInteger("full-render-speed/players-online-max-workers",1)));
        progressinterval = configuration.getInteger("progressloginterval", 100);
        if(progressinterval < 100) progressinterval = 100;
        progressLogIntervalNanos=RenderProgressLog.interval(configuration);
        saverestorepending = ProgressCheckpoint.enabled(configuration);
        tileupdatedelay = configuration.getInteger("tileupdatedelay", 30);
        defaulttilescale = configuration.getInteger("defaulttilescale", 0);
        if (defaulttilescale < 0) defaulttilescale = 0;
        if (defaulttilescale > 4) defaulttilescale = 4;
        tpsBypassUntil = (long) configuration.getLong("tps-check-bypass-until", 0);
        tpslimit_updaterenders = configuration.getDouble("update-min-tps", 15.0);
        if (tpslimit_updaterenders > 19.5) tpslimit_updaterenders = 19.5;
        tpslimit_fullrenders = configuration.getDouble("fullrender-min-tps", 15.0);
        if (tpslimit_fullrenders > 19.5) tpslimit_fullrenders = 19.5;
        tpslimit_zoomout = configuration.getDouble("zoomout-min-tps", 15.0);
        if (tpslimit_zoomout > 19.5) tpslimit_zoomout = 19.5;
        // Load enter/exit processing settings
        enterexitperiod = configuration.getInteger("enterexitperiod", DEFAULT_ENTEREXIT_PERIOD);
        titleFadeIn = configuration.getInteger("titleFadeIn", DEFAULT_TITLE_FADEIN);
        titleStay = configuration.getInteger("titleStay", DEFAULT_TITLE_STAY);
        titleFadeOut = configuration.getInteger("titleFadeOut", DEFAULT_TITLE_FADEOUT);
        enterexitUseTitle = configuration.getBoolean("enterexitUseTitle", DEFAULT_ENTEREXIT_USETITLE);
        enterReplacesExits = configuration.getBoolean("enterReplacesExits", DEFAULT_ENTEREPLACESEXITS);
        // Load the save pending job period
        savependingperiod = (int)(ProgressCheckpoint.intervalMillis(configuration)/1000);

        this.tileQueue = new AsynchronousQueue<MapTile>(
                new Handler<MapTile>() {
                @Override
                public void handle(MapTile t) {
                    FullWorldRenderState job = new FullWorldRenderState(t);
                    if(!scheduleDelayedJob(job, 0))
                        job.cleanup();
                }
            },
            (int) (configuration.getDouble("renderinterval", 0.5) * 1000),
            configuration.getInteger("renderacceleratethreshold", 30),
            (int)(configuration.getDouble("renderaccelerateinterval", 0.2) * 1000),
            configuration.getInteger("tiles-rendered-at-once", (Runtime.getRuntime().availableProcessors()+1)/2),
            usenormalpriority);

        /* On dedicated thread, so default to no delays */
        timeslice_int = (long)(configuration.getDouble("timesliceinterval", 0.0) * 1000);
        max_chunk_loads_per_tick = configuration.getInteger("maxchunkspertick", DEFAULT_CHUNKS_PER_TICK);
        if(max_chunk_loads_per_tick < 5) max_chunk_loads_per_tick = 5;
        /* Get zoomout processing periond in seconds */
        zoomout_period = configuration.getInteger("zoomoutperiod", DEFAULT_ZOOMOUT_PERIOD);
        if(zoomout_period < 5) zoomout_period = 5;

    }

    void renderFullWorld(DynmapLocation l, DynmapCommandSender sender, String mapname, boolean update, boolean resume) {
        DynmapWorld world = getWorld(l.world);
        if (world == null) {
            sender.sendMessage("Could not render: world '" + l.world + "' not defined in configuration.");
            return;
        }
        String wname = l.world;
        FullWorldRenderState rndr;
        synchronized(lock) {
            rndr = active_renders.get(wname);
            if(rndr != null) {
                sender.sendMessage(rndr.rendertype + " of world '" + wname + "' already active.");
                return;
            }
            rndr = new FullWorldRenderState(world,l,sender, mapname, update, resume);    /* Make new activation record */
            active_renders.put(wname, rndr);    /* Add to active table */
        }
        /* Schedule first tile to be worked */
        scheduleDelayedJob(rndr, 0);

        if(update)
            sender.sendMessage("Update render starting on world '" + wname + "'...");
        else if (resume)
            sender.sendMessage("Full render resuming on world '" + wname + "'...");
        else
            sender.sendMessage("Full render starting on world '" + wname + "'...");
    }

    void renderWorldRadius(DynmapLocation l, DynmapCommandSender sender, String mapname, int radius) {
        DynmapWorld world = getWorld(l.world);
        if (world == null) {
            sender.sendMessage("Could not render: world '" + l.world + "' not defined in configuration.");
            return;
        }
        String wname = l.world;
        FullWorldRenderState rndr;
        synchronized(lock) {
            rndr = active_renders.get(wname);
            if(rndr != null) {
                sender.sendMessage(rndr.rendertype + " of world '" + wname + "' already active.");
                return;
            }
            rndr = new FullWorldRenderState(world,l,sender, mapname, radius);    /* Make new activation record */
            active_renders.put(wname, rndr);    /* Add to active table */
        }
        /* Schedule first tile to be worked */
        scheduleDelayedJob(rndr, 0);
        sender.sendMessage("Render of " + radius + " block radius starting on world '" + wname + "'...");
    }

    public void startOBJExport(OBJExport exp, DynmapCommandSender sender) {
        ProcessOBJExport e = new ProcessOBJExport();
        e.exp = exp;
        e.sender = sender;
        scheduleDelayedJob(e, 0);
    }

    void cancelRender(String w, DynmapCommandSender sender) {
        synchronized(lock) {
            if(w != null) {
                FullWorldRenderState rndr;
                rndr = active_renders.remove(w);
                if(rndr != null) {
                    rndr.cancelRender();	/* Cancel render */
                    if(sender != null) {
                        sender.sendMessage("Cancelled render for '" + w + "'");
                    }
                }
            }
            else {	/* Else, cancel all */
                String[] wids = active_renders.keySet().toArray(new String[0]);
                for(String wid : wids) {
                    FullWorldRenderState rnd = active_renders.remove(wid);
                    rnd.cancelRender();
                    if(sender != null) {
                        sender.sendMessage("Cancelled render for '" + wid + "'");
                    }
                }
            }
        }
        var automatic = naturalRenders; if (automatic != null && sender != null) automatic.cancel(w, sender);
    }

    void purgeQueue(DynmapCommandSender sender, String worldname) {
        DynmapWorld world = null;
        if (worldname != null) {
            world = this.getWorld(worldname);
            if (world == null) {
                sender.sendMessage("World '" + worldname + "' not found.");
                return;
            }
        }
        if(tileQueue != null) {
            int cnt = 0;
            List<MapTile> popped = tileQueue.popAll();
            if(popped != null) {
                cnt = popped.size();
                if (worldname != null) {
                    for (MapTile mt : popped) {
                        if (mt.world != world) {
                            tileQueue.push(mt); // Push tiles for worlds other than the one we're purging
                        }
                    }
                }
                popped.clear();
            }
            for(DynmapWorld dw : this.worlds) {
                if ((worldname != null) && (dw != world)) {
                    continue;
                }
                for(MapTypeState mts : dw.mapstate) {
                    cnt += mts.getInvCount();
                    mts.clear();
                }
            }
            sender.sendMessage("Purged " + cnt + " tiles from queue");
        }
    }

    void purgeMap(final DynmapCommandSender sender, final String worldname, final String mapname) {
        final DynmapWorld world = getWorld(worldname);
        if (world == null) {
            sender.sendMessage("Could not purge map: world '" + worldname + "' not defined in configuration.");
            return;
        }
        MapType mt = null;
        for (MapType mtp : world.maps) {
            if (mtp.getName().equals(mapname)) {
                mt = mtp;
                break;
            }
        }
        if (mt == null) {
            sender.sendMessage("Could not purge map: map '" + mapname + "' not defined in configuration.");
            return;
        }
        final MapType mtf = mt;
        Runnable purgejob = new Runnable() {
            public void run() {
                world.purgeMap(mtf);
                sender.sendMessage("Purge of tiles for map '" + mapname + "' for world '" + worldname + "' completed");
            }
        };
        /* Schedule first tile to be worked */
        scheduleDelayedJob(purgejob, 0);

        sender.sendMessage("Map tile purge starting on map '" + mapname + "' for world '" + worldname + "'...");
    }

    void purgeWorld(final DynmapCommandSender sender, final String worldname) {
        final DynmapWorld world = getWorld(worldname);
        if (world == null) {
            sender.sendMessage("Could not purge world: world '" + worldname + "' not defined in configuration.");
            return;
        }
        // Cancel any pending render
        cancelRender(worldname, sender);
        // And purge update queue for world
        purgeQueue(sender, worldname);

        Runnable purgejob = new Runnable() {
            public void run() {
                world.purgeTree();
                sender.sendMessage("Purge of files for world '" + worldname + "' completed");
            }
        };
        /* Schedule first tile to be worked */
        scheduleDelayedJob(purgejob, 0);

        sender.sendMessage("World purge starting on world '" + worldname + "'...");
    }

    public boolean activateWorld(DynmapWorld dynmapWorld) {
        String worldname = dynmapWorld.getName();
        ConfigurationNode worldconfig = core.getWorldConfiguration(dynmapWorld);
        if(!dynmapWorld.loadConfiguration(core, worldconfig)) {
            Log.info("World '" + worldname + "' disabled");
            disabled_worlds.add(worldname);   /* Add to disabled world list */
            DynmapWorld oldw = worldsLookup.remove(worldname);
            if(oldw != null)
                worlds.remove(oldw);
            return false;
        }
        /* Remove from disabled, if it was there before */
        disabled_worlds.remove(worldname);
        // TODO: Make this less... weird...
        // Insert the world on the same spot as in the configuration.
        HashMap<String, Integer> indexLookup = new HashMap<String, Integer>();
        List<Map<String,Object>> nodes = core.world_config.getMapList("worlds");
        for (int i = 0; i < nodes.size(); i++) {
            Map<String,Object> node = nodes.get(i);
            indexLookup.put((String)node.get("name"), i);
        }
        Integer worldIndex = indexLookup.get(worldname);
        if(worldIndex == null) {
            worlds.add(dynmapWorld);	/* Put at end if no world section */
        }
        else {
            int insertIndex;
            for(insertIndex = 0; insertIndex < worlds.size(); insertIndex++) {
                Integer nextWorldIndex = indexLookup.get(worlds.get(insertIndex).getName());
                if (nextWorldIndex == null || worldIndex < nextWorldIndex.intValue()) {
                    break;
                }
            }
            worlds.add(insertIndex, dynmapWorld);
        }
        worldsLookup.put(worldname, dynmapWorld);
        core.events.trigger("worldactivated", dynmapWorld);
        /* If world is loaded, also handle this */
        if(dynmapWorld.isLoaded()) {
            loadWorld(dynmapWorld);
        }
        dynmapWorld.activateZoomOutFreshen();
        return true;
    }

    public void deactivateWorld(String wname) {
        //cancelRender(wname, null);  /* Cancel any render */
        DynmapWorld w = worldsLookup.get(wname);
        if (w != null) {
            if(saverestorepending)
                savePending(w, false);  // Save any pending jobs
            else
                cancelRender(wname, null);  /* Cancel any render */
        }
        w = worldsLookup.remove(wname); /* Remove from lookup */
        if(w != null) { /* If found, remove from active list */
            worlds.remove(w);
        }
        disabled_worlds.remove(wname);
    }

    public void loadWorld(DynmapWorld dynmapWorld) {
        /* Now, restore any pending renders for this world */
        if(saverestorepending)
            loadPending(dynmapWorld);
        if(missingTileCheck!=null)missingTileCheck.restoreWorld(dynmapWorld);
    }

    public void unloadWorld(DynmapWorld dynmapWorld) {
        if(saverestorepending)
            savePending(dynmapWorld, false);
    }

    private boolean safeLegacyPending(File file){
        var root=core.getDataFolder().toPath().toAbsolutePath().normalize();var path=file.toPath().toAbsolutePath().normalize();
        if(!path.startsWith(root))return false;for(var p=path;!p.equals(root);p=p.getParent())if(java.nio.file.Files.isSymbolicLink(p))return false;return true;
    }
    private void loadPending(DynmapWorld w) {
        String wname = w.getName();
        File f = new File(core.getDataFolder(), wname + ".pending");
        ConfigurationNode cn;
        try {cn=new ProgressCheckpoint(core,"render-world:"+wname).read();}
        catch(java.io.IOException invalid){Log.warning("Render checkpoint unavailable or invalid for world; starting safely");return;}
        if(cn==null&&f.isFile()&&safeLegacyPending(f)){cn=new ConfigurationNode(f);cn.load();}
        if(cn!=null) {
            if(cn.containsKey("layout")&&!ProgressCheckpoint.layout(w).equals(cn.getString("layout",""))){Log.warning("Render checkpoint layout changed; saved work will not be rendered at incorrect coordinates");return;}
            var touches=cn.getNodes("touches");if(touches!=null)for(var touch:touches)touch(wname,touch.getInteger("x",0),touch.getInteger("y",0),touch.getInteger("z",0),touch.getString("reason","recovered update"));
            var volumes=cn.getNodes("touch-volumes");if(volumes!=null)for(var v:volumes)touchVolume(wname,v.getInteger("xmin",0),v.getInteger("ymin",0),v.getInteger("zmin",0),v.getInteger("xmax",0),v.getInteger("ymax",0),v.getInteger("zmax",0),v.getString("reason","recovered update"));
            /* Get the saved tile definitions */
            int cnt = 0;
            List<ConfigurationNode> tiles = cn.getNodes("tiles");
            if(tiles != null) {
                for(ConfigurationNode tile : tiles) {
                    if(tile.getBoolean("stale-refresh",false))continue;
                    MapTile mt = MapTile.restoreTile(w, tile);  /* Restore tile, if possible */
                    if(mt != null) {
                        if(tileQueue.push(mt)) {
                            cnt++;
                        }
                    }
                }
            }
            /* Get invalid tiles */
            ConfigurationNode invmap = cn.getNode("invalid");
            if(invmap != null) {
                for(MapTypeState mts : w.mapstate) {
                    List<String> v = invmap.getStrings(mts.type.getPrefix(), null);
                    if(v != null) {
                        mts.restore(v);
                        cnt += mts.getInvCount();
                    }
                }
            }
            if(cnt > 0) {
                Log.info("Loaded " + cnt + " pending tile renders for world '" + wname + "'");
            }
            /* Get invalidated zoom out tiles pending */
            invmap = cn.getNode("invZoomOut");
            if (invmap != null) {
                for(MapTypeState mts : w.mapstate) {
                    List<List<String>> v = invmap.getList(mts.type.getPrefix());
                    if (v != null) {
                        mts.restoreZoomOut(v);
                    }
                }
            }
            /* Get saved render job, if any */
            ConfigurationNode job = cn.getNode("job");
            if((job != null) && (active_renders.get(wname) == null)) {
                try {
                    FullWorldRenderState j = new FullWorldRenderState(job);
                    active_renders.put(wname, j);
                    if(did_start)   /* Past initial start */
                        scheduleDelayedJob(j, 5000);
                    Log.info(j.rendertype + " for world '" + wname + "' restored");
                } catch (Exception x) {
                    Log.info("Unable to restore render job for world '" + wname + "' - map configuration changed");
                }
            }
        }
    }

    public boolean isRenderJobActive(String wname) {
        return active_renders.containsKey(wname);
    }

    DynmapCore getCoreForCheckpoints(){return core;}
    public boolean checkpointWorld(DynmapWorld world){return !saverestorepending||(world.isLoaded()&&savePending(world,true)); }
    private boolean savePending(DynmapWorld w, boolean keepQueue) { synchronized(checkpointWriteLock) {
        List<MapTile> mt = tileQueue.snapshot();
        File f = new File(core.getDataFolder(), w.getName() + ".pending");
        ConfigurationNode saved = new ConfigurationNode();
        ArrayList<ConfigurationNode> savedtiles = new ArrayList<ConfigurationNode>();
        for(MapTile tile : mt) {
            if(tile.getDynmapWorld() != w) {
                continue;
            }
            ConfigurationNode tilenode = tile.saveTile();
            if(tilenode != null) {
                var refresh=staleTileRefresh;
                if(staleOwnedAtStop.contains(tile)||(refresh!=null&&refresh.owns(tile)))tilenode.put("stale-refresh",true);
                savedtiles.add(tilenode);
            }

        }
        saved.put("touches",touch_events.keySet().stream().filter(t->t.world.equals(w.getName())).map(t->{var n=new ConfigurationNode();n.put("x",t.x);n.put("y",t.y);n.put("z",t.z);n.put("reason",touch_events.getOrDefault(t,"recovered update"));return n;}).toList());
        synchronized(touch_lock){saved.put("touch-volumes",touch_volume_events.stream().filter(t->t.world.equals(w.getName())).map(t->{var n=new ConfigurationNode();n.put("xmin",t.xmin);n.put("ymin",t.ymin);n.put("zmin",t.zmin);n.put("xmax",t.xmax);n.put("ymax",t.ymax);n.put("zmax",t.zmax);n.put("reason",t.reason);return n;}).toList());}
        int cnt = savedtiles.size();
        if(cnt > 0) { /* Something to save? */
            saved.put("tiles", savedtiles);
        }
        /* Save invalidated tiles */
        HashMap<String, Object> invalid = new HashMap<String,Object>();
        for(MapTypeState mts : w.mapstate) {
            invalid.put(mts.type.getPrefix(), mts.save());
            cnt += mts.getInvCount();
        }
        if(cnt > 0 || invalid.values().stream().anyMatch(value -> value instanceof List<?> flags && !flags.isEmpty())) {
            saved.put("invalid", invalid);
            if (!keepQueue) {
                Log.info("Saved " + cnt + " pending tile renders in world '" + w.getName() + "'");
            }
        }
        /* Save invalidated zoom out tiles pending */
        HashMap<String, List<List<String>>> invzooms = new HashMap<String,List<List<String>>>();
        for(MapTypeState mts : w.mapstate) {
            List<List<String>> szo = mts.saveZoomOut();
            if (szo != null) {
                invzooms.put(mts.type.getPrefix(), szo);
            }
        }
        if (!invzooms.isEmpty()) {
            saved.put("invZoomOut", invzooms);
        }

        FullWorldRenderState job = active_renders.get(w.getName());
        if(job==null&&stoppingJobs.containsKey(w.getName()))saved.put("job",stoppingJobs.get(w.getName()));
        if(job != null) {
            saved.put("job", job.saveState());
            if (!keepQueue) {
                active_renders.remove(w.getName());
                job.shutdownRender();
                Log.info(job.rendertype + " job saved for world '" + w.getName() + "'");
            }
        }
        saved.put("layout",ProgressCheckpoint.layout(w));
        try{new ProgressCheckpoint(core,"render-world:"+w.getName()).write(saved);if(safeLegacyPending(f))f.delete();if(!keepQueue)for(MapTile queued:mt)if(queued.getDynmapWorld()==w)tileQueue.remove(queued);return true;}
        catch(java.io.IOException failure){Log.warning("Could not save render progress; the previous checkpoint is retained");return false;}
    }}

    public void touch(String wname, int x, int y, int z, String reason) {
        TouchEvent evt = new TouchEvent();
        evt.world = wname;
        evt.x = x;
        evt.y = y;
        evt.z = z;
        evt.reason = reason;
        touch_events.putIfAbsent(evt, reason);
    }

    public void touchVolume(String wname, int minx, int miny, int minz, int maxx, int maxy, int maxz, String reason) {
        TouchVolumeEvent evt = new TouchVolumeEvent();
        evt.world = wname;
        evt.xmin = minx;
        evt.xmax = maxx;
        evt.ymin = miny;
        evt.ymax = maxy;
        evt.zmin = minz;
        evt.zmax = maxz;
        evt.reason = reason;
        synchronized(touch_lock) {
            touch_volume_events.add(evt);
        }
    }

//    public boolean invalidateTile(MapTile tile) {
//        return tileQueue.push(tile);
//    }

    public static boolean scheduleDelayedJob(Runnable job, long delay_in_msec) {
        if((mapman != null) && (mapman.render_pool != null)) {
            if(delay_in_msec > 0)
                mapman.render_pool.schedule(job, delay_in_msec, TimeUnit.MILLISECONDS);
            else
                mapman.render_pool.execute(job);
            return true;
        }
        else
            return false;
    }

    private volatile NaturalRenders naturalRenders;

    /** One independent low-priority full-pass frontier, never promoted into manual work. */
    private final class NaturalRenders implements Runnable {
        private final Object writeLock = new Object();
        private final ProgressCheckpoint store = ProgressCheckpoint.optional(core, "natural-renders");
        private final NaturalRenderLedger ledger;
        private ConfigurationNode restoredJob, finalState;
        private String restoredLayout;
        private volatile FullWorldRenderState job;
        private volatile boolean closed, enabled, failed;
        private final long startup = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0,
            core.configuration.getInteger("natural-render/startup-delay-seconds", 60)));
        private long quietAfter;
        private final long idleNanos = TimeUnit.SECONDS.toNanos(Math.max(0,
            core.configuration.getInteger("natural-render/idle-seconds", 10)));

        NaturalRenders() {
            ConfigurationNode saved = null;
            if (store != null) try { saved = store.read(); }
            catch (java.io.IOException invalid) { Log.warning("Natural render checkpoint unavailable; starting a fresh queue"); }
            ledger = new NaturalRenderLedger(saved);
            enabled = core.configuration.getBoolean("natural-render/enabled", true) && (saved == null || saved.getBoolean("enabled", true));
            if (saved != null) {
                restoredJob = saved.getNode("job"); restoredLayout = saved.getString("layout", "");
                failed = saved.getBoolean("failed", false);
            }
        }
        String pause() {
            if (closed) return "stopping";
            if (!enabled) return "disabled";
            if (failed) return "stopped after render error; use naturalrender resume";
            if (System.nanoTime() < startup) return "startup delay";
            String busy = naturalForegroundReason();
            synchronized(lock) {
                if (busy != null) {
                    // Still yield to the refresher's executing tile, but its steady
                    // 10% workload must not keep extending the foreground quiet period.
                    var refresh = staleTileRefresh;
                    boolean staleOnly = "queued or in-flight tile updates have priority".equals(busy)
                        && refresh != null && !tileQueue.hasWork(tile -> !refresh.owns(tile));
                    if (!staleOnly) quietAfter = System.nanoTime() + idleNanos;
                    return busy;
                }
                if (System.nanoTime() < quietAfter) return "waiting for foreground work to settle";
            }
            return null;
        }
        public void run() {
            try {
                if (closed || pause() != null) return;
                FullWorldRenderState selected = null;
                synchronized(lock) {
                    if (job != null) {
                        if (job.cancelled) { failed = true; Log.warning("Natural render stopped; use /mccmap naturalrender resume to retry"); }
                        else if (getWorld(job.world.getName()) != job.world) {
                            restoredJob = new ConfigurationNode(job.saveState()); restoredLayout = ProgressCheckpoint.layout(job.world);
                            job.shutdownRender(); job = null;
                        }
                        return;
                    }
                    if (restoredJob != null) {
                        var world = getWorld(restoredJob.getString("world", ""));
                        if (world != null && !world.isLoaded()) return;
                        if (world != null && ProgressCheckpoint.layout(world).equals(restoredLayout)) {
                            String target = restoredJob.getString("mapname", "");
                            var map = world.maps.stream().filter(m -> m.getName().equals(target)).findFirst().orElse(null);
                            if (map != null && ledger.eligible(world, map)) {
                                try { selected = new FullWorldRenderState(restoredJob); }
                                catch (Exception invalid) { Log.warning("Natural render layout changed; restarting its map safely"); }
                            }
                        }
                        restoredJob = null;
                    }
                    if (selected == null) outer: for (var world : worlds) if (world.isLoaded()) {
                        for (var map : world.maps) if (ledger.eligible(world, map)) {
                            selected = new FullWorldRenderState(world, world.getCenterLocation(), null, map.getName(), false, true);
                            break outer;
                        }
                    }
                    if (selected != null) { selected.makeNatural(); job = selected; }
                }
                if (selected != null) {
                    save();
                    Log.verboseinfo("Natural render starting for '" + selected.world.getName() + "' map '" + selected.mapname + "'");
                    if (!scheduleDelayedJob(selected, 0)) failed(selected);
                }
            } catch (RuntimeException failure) {
                failed = true; Log.severe("Natural render queue stopped; use /mccmap naturalrender resume to retry", failure);
            } finally { if (!closed) scheduleDelayedJob(this, 5000); }
        }
        void completed(FullWorldRenderState source, List<MapType> maps) {
            synchronized(lock) {
                for (var map : maps) ledger.complete(source.world, map);
                if (job != null && job != source && job.world == source.world && maps.stream().anyMatch(m -> m.getName().equals(job.mapname))) {
                    job.cancelRender(); job = null;
                }
            }
            save();
        }
        void finished(FullWorldRenderState finished) {
            synchronized(lock) { if (closed || job != finished) return; job = null; }
            save();
        }
        void failed(FullWorldRenderState stopped) { synchronized(lock) { if (job == stopped) failed = true; } save(); }
        void cancel(String world, DynmapCommandSender sender) {
            synchronized(lock) {
                if (world == null) for (var w : worlds) ledger.suspend(w.getName()); else ledger.suspend(world);
                if (job != null && (world == null || job.world.getName().equals(world))) {
                    job.cancelRender(); job = null; failed = false;
                    if (sender != null) sender.sendMessage("Natural render cancelled; this world's automatic queue is suspended until /mccmap naturalrender resume");
                }
            }
            save();
        }
        void control(String action) {
            FullWorldRenderState retry = null;
            synchronized(lock) {
                if (action.equals("off")) enabled = false;
                else { enabled = true; if (action.equals("resume")) { ledger.resume(); if (failed && job != null) { retry = job; retry.cancelled = false; retry.shutdown = false; } failed = false; } }
            }
            save(); if (retry != null) scheduleDelayedJob(retry, 0);
        }
        void save() {
            if (store == null) return;
            synchronized(writeLock) {
                ConfigurationNode saved;
                synchronized(lock) {
                    if (closed && finalState != null) { saved = finalState; }
                    else {
                    saved = ledger.save(); saved.put("enabled", enabled); saved.put("failed", failed);
                    if (job != null) { saved.put("job", job.saveState()); saved.put("layout", ProgressCheckpoint.layout(job.world)); }
                    else if (restoredJob != null) { saved.put("job", restoredJob); saved.put("layout", restoredLayout); }
                    if (closed) finalState = saved;
                    }
                }
                try { store.write(saved); }
                catch (java.io.IOException failure) { Log.warning("Could not save natural renders; previous checkpoint retained"); }
            }
        }
        void stop() { closed = true; save(); synchronized(lock) { if (job != null) job.shutdownRender(); } }
        String describe() {
            String pause = pause();
            return "Natural rendering: " + (pause != null ? pause : job == null ? "idle; completed or suspended maps are skipped" : job.progressSummary())
                + "; " + (fullRenderPlayers > 0 ? FullRenderSpeedPolicy.percent(core.configuration.getDouble("natural-render/players-online-percent", 10), 10)
                    : fullRenderOfflinePercent) + "% of original baseline";
        }
    }

    String naturalForegroundReason() {
        if (pausefullrenders || pauseupdaterenders) return "manual render pause";
        if (tpspausefullrenders || tpspauseupdaterenders) return "low TPS";
        String storage = storagePauseReason(); if (storage != null) return storage;
        synchronized(lock) { if (!active_renders.isEmpty()) return "manual renders have priority"; }
        if (tileQueue.hasWork()) return "queued or in-flight tile updates have priority";
        if (!touch_events.isEmpty()) return "player-triggered block updates have priority";
        synchronized(touch_lock) { if (!touch_volume_events.isEmpty()) return "volume updates have priority"; }
        for (var world : worlds) if (world.isLoaded()) for (var state : world.mapstate)
            if (state.hasPendingUpdates()) return "delayed tile updates have priority";
        return null;
    }
    private String naturalPauseReason() { var automatic = naturalRenders; return automatic == null ? "not started" : automatic.pause(); }

    public void naturalRenderControl(DynmapCommandSender sender, String action) {
        var automatic = naturalRenders;
        if (automatic == null) { statsMessage(sender, "Natural rendering: unavailable"); return; }
        if (List.of("on", "off", "resume").contains(action)) automatic.control(action);
        else if (!action.equals("status")) { sender.sendMessage("Usage: /mccmap naturalrender [status|on|off|resume]"); return; }
        statsMessage(sender, automatic.describe());
    }

    private volatile MissingTileCheck missingTileCheck;
    private volatile LegacyTileCleanup legacyTileCleanup;
    private volatile BackgroundTaskLog backgroundTaskLog;
    String legacyCleanupPauseReason(){return pausefullrenders||pauseupdaterenders?"manual pause":fullRenderPlayers>0&&(tpspausefullrenders||tpspauseupdaterenders)?"low TPS":null;}

    public void startRendering() {
        missingTileCheck = new MissingTileCheck(core, this);
        missingTileCheck.restore();
        render_pool = new DynmapScheduledThreadPoolExecutor();
        if(saverestorepending){checkpointWorker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"MCCMap progress checkpoints");t.setDaemon(true);return t;});checkpointWorker.scheduleWithFixedDelay(()->{for(var world:worlds)if(world.isLoaded())try{savePending(world,true);}catch(RuntimeException failure){Log.warning("Could not checkpoint render work; previous progress retained");}var automatic=naturalRenders;if(automatic!=null)automatic.save();},savependingperiod,savependingperiod,TimeUnit.SECONDS);}
        int boostWorkers = FullRenderSpeedPolicy.maximumParallelism(parallelrendercnt, fullRenderOfflinePercent) - Math.max(1, parallelrendercnt);
        if (boostWorkers > 0) fullRenderBoostPool = Executors.newFixedThreadPool(boostWorkers, r -> {
            Thread t = new OurThreadFactory().newThread(r); t.setName("MCCMap Full Render Boost"); return t;
        });
        legacyTileCleanup=new LegacyTileCleanup(core,this);
        naturalRenders = new NaturalRenders();
        scheduleDelayedJob(naturalRenders, 5000);
        tileQueue.start();
        scheduleDelayedJob(new DoZoomOutProcessing(), 5000);
        scheduleDelayedJob(new CheckWorldTimes(), 5000);
        scheduleDelayedJob(new DoTouchProcessing(), 1000);
        // If enabled, start enter/exit processing
        if (enterexitperiod > 0) {
            Log.info("Starting enter/exit processing");
            scheduleDelayedJob(new DoUserMoveProcessing(), enterexitperiod);
        }
        /* Resume pending jobs */
        for(FullWorldRenderState job : active_renders.values()) {
            scheduleDelayedJob(job, 5000);
            Log.info("Resumed render starting on world '" + job.world.getName() + "'...");
        }
        did_start = true;
        if (core.configuration.getBoolean("stale-tile-refresh/enabled", true)) {
            try { staleTileRefresh = new StaleTileRefresh(core, this); }
            catch (java.io.IOException | RuntimeException failure) { Log.warning("Stale tile refresh could not start; normal rendering remains enabled"); }
        }
        backgroundTaskLog=new BackgroundTaskLog(core.configuration,this::backgroundTaskSummary);
    }

    private volatile StaleTileRefresh staleTileRefresh;
    String storagePauseReason() {
        var usage = core.getStorageUsage(); return usage == null ? null : usage.pauseReason();
    }
    String stalePauseReason(int backlogLimit) {
        if (storagePauseReason() != null) return storagePauseReason();
        if (pauseupdaterenders) return "Manual update-render pause";
        if (tpspauseupdaterenders) return "Low TPS";
        synchronized (lock) { if (!active_renders.isEmpty()) return "Manual full/radius render has priority"; }
        if (tileQueue.size() >= backlogLimit) return "Normal update backlog has priority";
        return null;
    }
    boolean canRefreshStaleTiles(int backlogLimit) {
        return stalePauseReason(backlogLimit) == null;
    }
    public void recordSuccessfulTileRender(org.dynmap.storage.MapStorageTile tile, long timestamp) {
        var refresh = staleTileRefresh;
        if (refresh != null) refresh.recordSuccessful(tile, timestamp);
    }
    public void recordTileRefreshComplete(org.dynmap.hdmap.HDMapTile tile) {
        var refresh = staleTileRefresh;
        if (refresh != null) refresh.recordCompleted(tile);
    }

    public void stopRendering() {
        var backgroundLog=backgroundTaskLog;backgroundTaskLog=null;if(backgroundLog!=null)backgroundLog.close();
        var legacy=legacyTileCleanup;legacyTileCleanup=null;if(legacy!=null)legacy.close();
        var automatic = naturalRenders; if (automatic != null) automatic.stop();
        if(checkpointWorker!=null){checkpointWorker.shutdownNow();try{checkpointWorker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
        var check = missingTileCheck; missingTileCheck = null; if(check != null)check.close();
        var refresh = staleTileRefresh;
        if(refresh!=null)staleOwnedAtStop=refresh.ownedTiles();
        if (refresh != null) refresh.close();
        staleTileRefresh = null;
        // Stop the tile queue
        tileQueue.stop();
        /* Tell all worlds to cancel any zoom out processing */
        for(DynmapWorld w: worlds)
            w.cancelZoomOutFreshen();
        // Preserve a safe frontier, stop workers, then take the final queue/zoom snapshot.
        List<DynmapWorld> stoppingWorlds=new ArrayList<>();
        for(DynmapWorld w:worlds)if(w.isLoaded()) {
            stoppingWorlds.add(w);var job=active_renders.get(w.getName());
            if(job!=null){var snapshot=job.saveState();if(snapshot!=null)stoppingJobs.put(w.getName(),snapshot);job.shutdownRender();}
            w.setWorldUnloaded();
        }
        // Extra workers belong exclusively to full/radius renders.
        var boostPool = fullRenderBoostPool; fullRenderBoostPool = null;
        if (boostPool != null) {
            for (Runnable queued : boostPool.shutdownNow())
                if (queued instanceof Future<?> task) task.cancel(true);
            try { boostPool.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        // Shutdown render pool
        render_pool.shutdown();
        try {
            render_pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ix) {
        }
        render_pool.shutdownNow();	// Force hard shutdown
        try {
            render_pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ix) {
        }

        if(saverestorepending)for(var world:stoppingWorlds)savePending(world,false);
        mapman = null;
        hdmapman = null;
        did_start = false;
    }

    public void pushUpdate(Client.Update update) {
        int sz = worlds.size();
        for(int i = 0; i < sz; i++) {
            worlds.get(i).updates.pushUpdate(update);
        }
    }

    public void pushUpdate(DynmapWorld world, Client.Update update) {
        pushUpdate(world.getName(), update);
    }

    public void pushUpdate(String worldName, Client.Update update) {
        DynmapWorld world = getWorld(worldName);
        if(world != null)
            world.updates.pushUpdate(update);
    }

    public Client.Update[] getWorldUpdates(String worldName, long since) {
        DynmapWorld world = getWorld(worldName);
        if (world == null)
            return new Client.Update[0];
        return world.updates.getUpdatedObjects(since);
    }

    /**
     *  Update map tile statistics
     * @param tile - map tile
     * @param prefix - map prefix
     * @param rendered - true if tile rendered
     * @param updated - true if tile updated
     * @param transparent - true if tile transparent
     */
    public void updateStatistics(MapTile tile, String prefix, boolean rendered, boolean updated, boolean transparent) {
        synchronized(lock) {
            String k = tile.getDynmapWorld().getName() + "." + prefix;
            MapStats ms = mapstats.get(k);
            if(ms == null) {
                ms = new MapStats();
                mapstats.put(k, ms);
            }
            ms.loggedcnt++;
            if(rendered)
                ms.renderedcnt++;
            if(updated)
                ms.updatedcnt++;
            if(transparent)
                ms.transparentcnt++;
        }
    }
    /**
     * Print statistics command
     * @param sender - command sender
     * @param prefix - map ID prefix for stats to print
     */
    private static void statsMessage(DynmapCommandSender sender, String text) { sender.sendMessage(StatsColors.format(text)); }

    String backgroundTaskSummary() {
        var parts=new java.util.ArrayList<String>();
        var automatic=naturalRenders;parts.add(automatic==null?"Natural rendering: not started":automatic.describe());
        var refresh=staleTileRefresh;
        if(refresh==null)parts.add("Stale refresh: disabled");
        else {var state=refresh.describe();parts.add("Stale refresh: "+state.get(1).trim()+"; "+state.get(2).trim());}
        var missing=missingTileCheck;if(missing!=null)parts.add(String.join("; ",missing.describe()));
        var legacy=legacyTileCleanup;if(legacy!=null)parts.add(legacy.describe());
        if(tileQueue!=null)parts.add("Tile updates queued: "+tileQueue.size());
        return String.join(" | ",parts);
    }

    public void printProgress(DynmapCommandSender sender, String world) {
        boolean any = false;
        synchronized (lock) {
            for (var job : active_renders.values()) {
                if (world != null && !job.world.getName().equals(world)) continue;
                statsMessage(sender, job.progressSummary());
                any = true;
            }
        }
        var automatic = naturalRenders;
        if (automatic != null && automatic.job != null && (world == null || automatic.job.world.getName().equals(world))) {
            statsMessage(sender, automatic.job.progressSummary()); any = true;
        }
        if (!any) statsMessage(sender, "No active full/radius/update renders" + (world == null ? "." : " for '" + world + "'."));
    }

    public void printStats(DynmapCommandSender sender, String prefix) {
        var usage = core.getStorageUsage();
        if (usage != null) {
            usage.requestScan();
            for (String line : StorageUsageMonitor.describe(usage.snapshot(), System.currentTimeMillis())) statsMessage(sender, line);
            var jar = core.getPluginJarFile();
            if (jar != null) statsMessage(sender, "  Installed JAR: " + StorageUsageMonitor.bytes(jar.length()) + " (outside data cap)");
        }
        var check = missingTileCheck;
        if(check != null)for(String line : check.describe())statsMessage(sender,line);
        var automatic = naturalRenders; if (automatic != null) statsMessage(sender, automatic.describe());
        statsMessage(sender, "TPS checks: " + (System.currentTimeMillis() < tpsBypassUntil ? "bypassed for " + ((tpsBypassUntil - System.currentTimeMillis()) / 60000) + " more minutes" : "enabled; ten-minute retry hold; ignored with no players"));
        var refresh = staleTileRefresh;
        if (refresh != null) for (String line : refresh.describe()) statsMessage(sender, line);
        else statsMessage(sender, core.configuration.getBoolean("stale-tile-refresh/enabled", true)
            ? "Stale tile refresh: unavailable (startup failed; see console)" : "Stale tile refresh: disabled in configuration");
        statsMessage(sender, "Full/radius render speed: " + (fullRenderPlayers == 0 ? fullRenderOfflinePercent : fullRenderOnlinePercent)
            + "% of original baseline (" + fullRenderPlayers + " players online; online worker limit " + fullRenderOnlineWorkers + "); stale refresh pacing is independent");
        statsMessage(sender, "Render pauses: updates=" + (pauseupdaterenders ? "manual" : tpspauseupdaterenders ? "low TPS" : "none")
            + ", full/radius=" + (pausefullrenders ? "manual" : tpspausefullrenders ? "low TPS" : "none")
            + ", storage=" + (storagePauseReason() == null ? "none" : storagePauseReason()));
        var legacy=legacyTileCleanup;if(legacy!=null)statsMessage(sender,legacy.describe());
        statsMessage(sender,core.getHttpsStatus());
        statsMessage(sender, "Tile Render Statistics:");
        MapStats tot = new MapStats();
        int invcnt = 0;
        for(DynmapWorld dw : this.worlds) {
            for(MapTypeState mts : dw.mapstate) {
                invcnt += mts.getInvCount();
            }
        }
        synchronized(lock) {
            for(String k: new TreeSet<String>(mapstats.keySet())) {
                if((prefix != null) && !k.startsWith(prefix))
                    continue;
                MapStats ms = mapstats.get(k);
                statsMessage(sender, String.format("  %s: processed=%d, rendered=%d, updated=%d, transparent=%d",
                        k, ms.loggedcnt, ms.renderedcnt, ms.updatedcnt, ms.transparentcnt));
                tot.loggedcnt += ms.loggedcnt;
                tot.renderedcnt += ms.renderedcnt;
                tot.updatedcnt += ms.updatedcnt;
                tot.transparentcnt += ms.transparentcnt;
            }
        }
        statsMessage(sender, String.format("  TOTALS: processed=%d, rendered=%d, updated=%d, transparent=%d",
                tot.loggedcnt, tot.renderedcnt, tot.updatedcnt, tot.transparentcnt));
        statsMessage(sender, String.format("  Triggered update queue size: %d + %d", tileQueue.size(), invcnt));
        String act;
        synchronized (lock) { act = String.join(" ", active_renders.keySet()); }
        statsMessage(sender, String.format("  Active render jobs: %s", act));
        synchronized (lock) {
            for (var job : active_renders.values()) statsMessage(sender, "  " + job.progressSummary());
        }
        /* Chunk load stats */
        statsMessage(sender, "Chunk Loading Statistics:");
        statsMessage(sender, String.format("  Cache hit rate: %.2f%%", core.getServer().getCacheHitRate()));
        for (MapChunkCache.ChunkStats cs : MapChunkCache.ChunkStats.values()) {
            int cnt = chunks_read[cs.ordinal()].get();
            if (cnt == 0) cnt = 1;
            long ts = chunks_read_times[cs.ordinal()].get();
            statsMessage(sender, String.format("  Chunks processed: %s: count=%d, %.2f msec/chunk", cs.getLabel(), cnt, 0.000001 * (ts / cnt)));
        }
    }
    /**
     * Print trigger statistics command
     * @param sender - command sender
     */
    public void printTriggerStats(DynmapCommandSender sender) {
        sender.sendMessage("Render Trigger Statistics:");
        synchronized(lock) {
            for(String k: new TreeSet<String>(trigstats.keySet())) {
                TriggerStats ts = trigstats.get(k);
                sender.sendMessage("  " + k + ": calls=" + ts.callsmade + ", calls-adding-tiles=" + ts.callswithtiles + ", tiles-added=" + ts.tilesqueued);
            }
        }
    }

    /**
     * Reset statistics
     * @param sender - command sender
     * @param prefix - prefix of map IDs to be reset
     */
    public void resetStats(DynmapCommandSender sender, String prefix) {
        synchronized(lock) {
            for(String k : mapstats.keySet()) {
                if((prefix != null) && !k.startsWith(prefix))
                    continue;
                MapStats ms = mapstats.get(k);
                ms.loggedcnt = 0;
                ms.renderedcnt = 0;
                ms.updatedcnt = 0;
                ms.transparentcnt = 0;
            }
            for(String k : trigstats.keySet()) {
                TriggerStats ts = trigstats.get(k);
                ts.callsmade = 0;
                ts.callswithtiles = 0;
                ts.tilesqueued = 0;
            }
            chunk_caches_created.set(0);
            for (int i = 0; i < chunks_read.length; i++) {
                chunks_read[i].set(0);
                chunks_read_times[i].set(0);
            }
        }
        core.getServer().resetCacheStats();
        sender.sendMessage("Tile Render Statistics reset");
    }

    public boolean getSmoothLighting() {
        return core.smoothlighting;
    }

    public boolean getBetterGrass() {
        return core.bettergrass;
    }

    public boolean getHideOres() {
        return hideores;
    }
    /* Map block ID to aliased ID - used to hide ores */
    public String getBlockAlias(String blockname) {
        String v = blockalias.get(blockname);
        if (v == null) {
            v = blockname;
        }
        return v;
    }
    /* Get names of aliased blocks */
    public Set<String> getAliasedBlocks() {
        return blockalias.keySet();
    }
    /* Set block ID alias */
    public void setBlockAlias(String blockname, String newblockname) {
        if (blockname.indexOf(':') < 0)
            blockname = "minecraft:" + blockname;
        if (newblockname.indexOf(':') < 0)
            newblockname = "minecraft:" + newblockname;
        blockalias.put(blockname, newblockname);
    }
    /*
     * Pause full/radius render processing
     * @param dopause - true to pause, false to unpause
     */
    void setPauseFullRadiusRenders(boolean dopause) {
        if(dopause != pausefullrenders) {
            pausefullrenders = dopause;
            Log.info("Full/radius render pause set to " + dopause);
        }
    }
    /*
     * Test if full renders are paused
     */
    boolean getPauseFullRadiusRenders() {
        return pausefullrenders;
    }
    /*
     * Pause update render processing
     * @param dopause - true to pause, false to unpause
     */
    void setPauseUpdateRenders(boolean dopause) {
        if(dopause != pauseupdaterenders) {
            pauseupdaterenders = dopause;
            Log.info("Update render pause set to " + dopause);
        }
    }
    /*
     * Test if update renders are paused
     */
    boolean getPauseUpdateRenders() {
        return pauseupdaterenders;
    }

    /* Connect any jobs tied to this player back to the player (resumes output to player) */
    void connectTasksToPlayer(DynmapPlayer p) {
        String pn = p.getName();
        for(FullWorldRenderState job : active_renders.values()) {
            if(pn.equals(job.player)) {
                job.sender = p;
            }
        }
    }

    /**
     * Process touch events
     */
    private void processTouchEvents() {
        ArrayList<TouchEvent> te = null;
        ArrayList<TouchVolumeEvent> tve = null;

        if(touch_events.isEmpty() == false) {
            te = new ArrayList<TouchEvent>(touch_events.keySet());
            for(int i = 0; i < te.size(); i++) {
                touch_events.remove(te.get(i));
            }
        }

        synchronized(touch_lock) {
            if(touch_volume_events.isEmpty() == false) {
                tve = new ArrayList<TouchVolumeEvent>(touch_volume_events);
                touch_volume_events.clear();
            }
        }
        DynmapWorld world = null;
        String wname = "";

        /* If any touch events, process them */
        if(te != null) {
            for(TouchEvent evt : te) {
                int invalidates = 0;
                /* If different world, look it up */
                if(evt.world.equals(wname) == false) {
                    wname = evt.world;
                    world = getWorld(wname);
                }
                if(world == null) continue;
                for (MapTypeState mts : world.mapstate) {
                    if (mts.type.isReadOnly()) {
                        continue;
                    }

                    List<TileFlags.TileCoord> tiles = mts.type.getTileCoords(world, evt.x, evt.y, evt.z);
                    invalidates += mts.invalidateTiles(tiles);
                }
                if(evt.reason != null) {
                    synchronized(lock) {
                        TriggerStats ts = trigstats.get(evt.reason);
                        if(ts == null) {
                            ts = new TriggerStats();
                            trigstats.put(evt.reason, ts);
                        }
                        ts.callsmade++;
                        if(invalidates > 0) {
                            ts.callswithtiles++;
                            ts.tilesqueued += invalidates;
                        }
                    }
                }
            }
            te.clear(); /* Clean up set */
        }

        /* If any volume touches */
        if(tve != null) {
            for(TouchVolumeEvent evt : tve) {
                /* If different world, look it up */
                if(evt.world.equals(wname) == false) {
                    wname = evt.world;
                    world = getWorld(wname);
                }
                if(world == null) continue;
                int invalidates = 0;
                for (MapTypeState mts : world.mapstate) {
                    if (mts.type.isReadOnly()) {
                        continue;
                    }

                    List<TileFlags.TileCoord> tiles = mts.type.getTileCoords(world, evt.xmin, evt.ymin, evt.zmin, evt.xmax, evt.ymax, evt.zmax);
                    invalidates += mts.invalidateTiles(tiles);
                }
                if(evt.reason != null) {
                    synchronized(lock) {
                        TriggerStats ts = trigstats.get(evt.reason);
                        if(ts == null) {
                            ts = new TriggerStats();
                            trigstats.put(evt.reason, ts);
                        }
                        ts.callsmade++;
                        if(invalidates > 0) {
                            ts.callswithtiles++;
                            ts.tilesqueued += invalidates;
                        }
                    }
                }
            }
            /* Clean up */
            tve.clear();
        }
    }

    public int getMaxChunkLoadsPerTick() {
        return max_chunk_loads_per_tick;
    }

    void updateRenderPlayerCount(int players) { fullRenderPlayers = Math.max(0, players); }

    public void updateTPS(double tps) {
        long now = System.nanoTime();
        int players = core.getServer().getOnlinePlayers().length;
        updateRenderPlayerCount(players);
        boolean bypass = System.currentTimeMillis() < tpsBypassUntil;
        tpspauseupdaterenders = updateTpsGate.paused(tps, tpslimit_updaterenders, players, bypass, now);
        tpspausefullrenders = fullTpsGate.paused(tps, tpslimit_fullrenders, players, bypass, now);
        tpspausezoomout = zoomTpsGate.paused(tps, tpslimit_zoomout, players, bypass, now);
        String reason = players == 0 ? "no players online; TPS limits ignored." : bypass ? "24-hour TPS bypass active."
            : String.format(java.util.Locale.ROOT, "TPS %.2f; low-TPS pauses last 10 minutes before the next check. Other pause conditions still apply.", tps);
        // Called from the server tick: command-owner messages stay on the server thread.
        List<FullWorldRenderState> jobs;
        synchronized (lock) { jobs = new ArrayList<>(active_renders.values()); }
        for (FullWorldRenderState job : jobs) {
            if (job.cancelled || job.tile0 != null) continue;
            if (!job.tpsNotifications.wouldChange(tpspausefullrenders)) continue;
            DynmapPlayer owner = job.player == null || job.player.isEmpty() ? null : core.getServer().getPlayer(job.player);
            String render = job.rendertype + " for world '" + job.world.getName() + "'"
                + (job.mapname == null ? "" : " (map '" + job.mapname + "')");
            job.tpsNotifications.update(tpspausefullrenders, render, reason, Log::info,
                owner == null ? null : owner::sendMessage);
        }
        updateTpsNotifications.update(tpspauseupdaterenders, "automatic update renders", reason, Log::info, null);
        zoomTpsNotifications.update(tpspausezoomout, "zoom-out renders", reason, Log::info, null);
    }

    public void setTpsBypass(boolean enabled) {
        tpsBypassUntil = enabled ? System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24) : 0;
        core.configuration.put("tps-check-bypass-until", tpsBypassUntil);
        if (!core.configuration.save()) Log.warning("Could not persist TPS bypass expiry");
        updateTPS(core.getServer().getServerTPS());
    }
    public long getTpsBypassUntil() { return tpsBypassUntil; }

    public boolean getTPSFullRenderPause() {
        return tpspausefullrenders;
    }
    public boolean getTPSUpdateRenderPause() {
        return tpspauseupdaterenders;
    }
    public boolean getTPSZoomOutPause() {
        return tpspausezoomout;
    }
    public void setJobsQuiet(DynmapCommandSender sender) {
        DynmapPlayer player = null;
        if (sender instanceof DynmapPlayer) {
            player = (DynmapPlayer) sender;
        }
        synchronized (lock) {
            for (FullWorldRenderState job : active_renders.values()) {
                if (job.sender instanceof DynmapPlayer) {
                    DynmapPlayer js = (DynmapPlayer) job.sender;
                    if ((player != null) && (player.getName().equals(js.getName()))) {
                        job.quiet = true;
                    }
                }
                else if (player == null) {  // If both are console
                    job.quiet = true;
                }
            }
        }
    }
    public boolean useBrightnessTable() {
        return useBrightnessTable;
    }
}
