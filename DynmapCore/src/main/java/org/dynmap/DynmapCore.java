// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.dynmap.MapType.ImageEncoding;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapListenerManager;
import org.dynmap.common.DynmapListenerManager.EventType;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.common.DynmapServerInterface;
import org.dynmap.debug.Debug;
import org.dynmap.debug.Debugger;
import org.dynmap.exporter.DynmapExpCommands;
import org.dynmap.hdmap.HDBlockModels;
import org.dynmap.hdmap.HDBlockStateTextureMap;
import org.dynmap.hdmap.TexturePack;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.impl.MarkerAPIImpl;
import org.dynmap.modsupport.ModSupportImpl;
import org.dynmap.renderer.DynmapBlockState;
import org.dynmap.servlet.*;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.filetree.FileTreeMapStorage;
import org.dynmap.utils.BlockStep;
import org.dynmap.utils.BufferOutputStream;
import org.dynmap.utils.ImageIOManager;
import org.dynmap.web.BanIPFilter;
import org.dynmap.web.CustomHeaderFilter;
import org.dynmap.web.FileNameFilter;
import org.dynmap.web.FilterHandler;
import org.dynmap.web.HandlerRouter;
import org.eclipse.jetty.ee8.nested.ResourceHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee8.nested.ContextHandler;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.yaml.snakeyaml.Yaml;

import javax.servlet.*;
import javax.servlet.http.HttpServlet;

public class DynmapCore implements DynmapCommonAPI {
    /**
     * Callbacks for core initialization - subclassed by platform plugins
     */
    public static abstract class EnableCoreCallbacks {
        /**
         * Called during enableCore to report that configuration.txt is loaded
         */
        public abstract void configurationLoaded();
    }
    private File jarfile;
    private DynmapServerInterface server;
    private String version;
    private String platform = null;
    private String platformVersion = null;
    private Server webServer = null;
    private String webhostname = null;
    private int webport = 0;
    private HandlerRouter router = null;
    public MapManager mapManager = null;
    public PlayerList playerList;
    public ConfigurationNode configuration;
    public ConfigurationNode world_config;
    public ComponentManager componentManager = new ComponentManager();
    public DynmapListenerManager listenerManager = new DynmapListenerManager(this);
    public PlayerFaces playerfacemgr;
    public SkinUrlProvider skinUrlProvider;
    public Events events = new Events();
    public String deftemplatesuffix = "";
    private DynmapMapCommands dmapcmds = new DynmapMapCommands();
    private DynmapExpCommands dynmapexpcmds = new DynmapExpCommands();
    boolean bettergrass = false;
    boolean smoothlighting = false;
    private boolean ctmsupport = false;
    private boolean customcolorssupport = false;
    private String def_image_format = "png";
    private HashSet<String> enabledTriggers = new HashSet<String>();
    public boolean disable_chat_to_web = false;
    public boolean player_info_protected;
    private boolean transparentLeaves = true;
    private List<String> sortPermissionNodes;
    private int perTickLimit = 50;   // 50 ms
    private boolean dumpMissing = false;
    private static boolean migrate_chunks = false;
    public boolean isInternalWebServerDisabled = false;

    private int     config_hashcode;    /* Used to signal need to reload web configuration (world changes, config update, etc) */
    private int fullrenderplayerlimit;  /* Number of online players that will cause fullrender processing to pause */
    private int updateplayerlimit;  /* Number of online players that will cause update processing to pause */
    private String publicURL;	// If set, public HRL for accessing dynmap (declared by administrator)
    private String noPermissionMsg;
    private boolean manualFullPause;
    private boolean manualUpdatePause;
    private int snapshotcachesize;
    private boolean snapshotsoftref;
    private String[] biomenames = new String[0];
    private Map<String, Integer> blockmap = null;
    private Map<String, Integer> itemmap = null;


    private String hackAttemptSub = "(IaM5uchA1337Haxr-Ban Me!)";
    // WEBP support
    private String cwebpPath;
    private String dwebpPath;
    private boolean did_cwebpPath_warn = false;
    private boolean did_dwebpPath_warn = false;

    /* Flag to let code know that we're doing reload - make sure we don't double-register event handlers */
    public boolean is_reload = false;
    public static boolean ignore_chunk_loads = false; /* Flag keep us from processing our own chunk loads */

    private MarkerAPIImpl   markerapi;

    private File dataDirectory;
    private File tilesDirectory;
    private File exportDirectory;
    private File importDirectory;
    private String plugin_ver;
    private MapStorage defaultStorage;
    private StorageUsageMonitor storageUsage;
    public StorageUsageMonitor getStorageUsage() { return storageUsage; }

    // Read web path
    private String webpath;
    // And whether to disable web file update
    private boolean updatewebpathfiles = true;

    private String[] deftriggers = { };

    private Boolean webserverCompConfigWarn = false;
    private final String CompConfigWiki = "https://github.com/webbukkit/dynmap/wiki/Component-Configuration";

    private final String[] defaultTemplates = {"vlowres", "lowres", "medres", "hires", "low_boost_hi",
            "hi_boost_vhi", "hi_boost_xhi"};
    /* Constructor for core */
    public DynmapCore() {
    }

    public void setSkinUrlProvider(SkinUrlProvider skinUrlProvider) {
        this.skinUrlProvider = skinUrlProvider;
    }

    /* Cleanup method */
    public void cleanup() {
        server = null;
        markerapi = null;
    }
    public void restartMarkerSaveJob(){
        this.markerapi.scheduleWriteJob();
    }
    // Set plugin jar file
    public void setPluginJarFile(File f) {
        jarfile = f;
    }
    // Get plugin jar file
    public File getPluginJarFile() {
        return jarfile;
    }
    /* Dependencies - need to be supplied by plugin wrapper */
    public void setPluginVersion(String pluginver, String platform) {
        this.plugin_ver = pluginver;
        this.platform = platform;
    }
    /* Default platform to forge... */
    public void setPluginVersion(String pluginver) {
        setPluginVersion(pluginver, "Forge");
    }
    public void setDataFolder(File dir) {
        dataDirectory = dir;
    }
    public final File getDataFolder() {
        return dataDirectory;
    }
    public final File getTilesFolder() {
        return tilesDirectory;
    }
    public final File getExportFolder() {
        return exportDirectory;
    }
    public final File getImportFolder() {
        return importDirectory;
    }
    public void setMinecraftVersion(String mcver) {
        this.platformVersion = mcver;
    }
    public void setServer(DynmapServerInterface srv) {
        server = srv;
    }
    public final DynmapServerInterface getServer() { return server; }

    public final void setBiomeNames(String[] names) {
        biomenames = names;
    }

    public static final boolean migrateChunks() {
        return migrate_chunks;
    }

    public String getCWEBPPath() {
        if ((cwebpPath == null) && (!did_cwebpPath_warn)) {
            Log.severe("ERROR: trying to use WEBP without cwebp tool installed or cwebpPath set properly");
            did_cwebpPath_warn = true;
        }
        return cwebpPath;
    }
    public String getDWEBPPath() {
        if ((dwebpPath == null) && (!did_dwebpPath_warn)) {
            Log.severe("ERROR: trying to use WEBP without dwebp tool installed or dwebpPath set properly");
            did_dwebpPath_warn = true;
        }
        return dwebpPath;
    }

    public final String getBiomeName(int biomeid) {
        String n = null;
        if ((biomeid >= 0) && (biomeid < biomenames.length)) {
            n = biomenames[biomeid];
        }
        if(n == null) n = "biome" + biomeid;
        return n;
    }
    public final String[] getBiomeNames() {
        return biomenames;
    }

    public final MapManager getMapManager() {
        return mapManager;
    }

    public final void setTriggerDefault(String[] triggers) {
        deftriggers = triggers;
    }

    public final void setLeafTransparency(boolean trans) {
        transparentLeaves = trans;
    }
    public final boolean getLeafTransparency() {
        return transparentLeaves;
    }

    /* Add/Replace branches in configuration tree with contribution from a separate file */
    private void mergeConfigurationBranch(ConfigurationNode cfgnode, String branch, boolean replace_existing, boolean islist) {
        Object srcbranch = cfgnode.getObject(branch);
        if(srcbranch == null)
            return;
        /* See if top branch is in configuration - if not, just add whole thing */
        Object destbranch = configuration.getObject(branch);
        if(destbranch == null) {    /* Not found */
            configuration.put(branch, srcbranch);   /* Add new tree to configuration */
            return;
        }
        /* If list, merge by "name" attribute */
        if(islist) {
            List<ConfigurationNode> dest = configuration.getNodes(branch);
            List<ConfigurationNode> src = cfgnode.getNodes(branch);
            /* Go through new records : see what to do with each */
            for(ConfigurationNode node : src) {
                String name = node.getString("name", null);
                if(name == null) continue;
                /* Walk destination - see if match */
                boolean matched = false;
                for(ConfigurationNode dnode : dest) {
                    String dname = dnode.getString("name", null);
                    if(dname == null) continue;
                    if(dname.equals(name)) {    /* Match? */
                        if(replace_existing) {
                            dnode.clear();
                            dnode.putAll(node);
                        }
                        matched = true;
                        break;
                    }
                }
                /* If no match, add to end */
                if(!matched) {
                    dest.add(node);
                }
            }
            configuration.put(branch,dest);
        }
        /* If configuration node, merge by key */
        else {
            ConfigurationNode src = cfgnode.getNode(branch);
            ConfigurationNode dest = configuration.getNode(branch);
            for(String key : src.keySet()) {    /* Check each contribution */
                if(dest.containsKey(key)) { /* Exists? */
                    if(replace_existing) {  /* If replacing, do so */
                        dest.put(key, src.getObject(key));
                    }
                }
                else {  /* Else, always add if not there */
                    dest.put(key, src.getObject(key));
                }
            }
        }
    }
    /* Table of default templates - all are resources in mccmap.jar unnder templates/, and go in templates directory when needed */
    private static final String[] stdtemplates = { "normal.txt", "nether.txt", "normal-lowres.txt",
        "nether-lowres.txt", "normal-hires.txt", "nether-hires.txt",
        "normal-vlowres.txt", "nether-vlowres.txt", "the_end.txt", "the_end-vlowres.txt",
        "the_end-lowres.txt", "the_end-hires.txt",
        "normal-low_boost_hi.txt", "normal-hi_boost_vhi.txt", "normal-hi_boost_xhi.txt",
        "nether-low_boost_hi.txt", "nether-hi_boost_vhi.txt", "nether-hi_boost_xhi.txt",
        "the_end-low_boost_hi.txt", "the_end-hi_boost_vhi.txt", "the_end-hi_boost_xhi.txt"
    };

    private static final String CUSTOM_PREFIX = "custom-";
    /* Load templates from template folder */
    private void loadTemplates() {
        File templatedir = new File(dataDirectory, "templates");
        templatedir.mkdirs();
        /* First, prime the templates directory with default standard templates, if needed */
        for(String stdtemplate : stdtemplates) {
            File f = new File(templatedir, stdtemplate);
            updateVersionUsingDefaultResource("/templates/" + stdtemplate, f);
        }
        /* Now process files */
        String[] templates = templatedir.list();
        /* Go through list - process all ones not starting with 'custom' first */
        for(String tname: templates) {
            /* If matches naming convention */
            if(tname.endsWith(".txt") && (!tname.startsWith(CUSTOM_PREFIX))) {
                File tf = new File(templatedir, tname);
                ConfigurationNode cn = new ConfigurationNode(tf);
                cn.load();
                /* Supplement existing values (don't replace), since configuration.txt is more custom than these */
                mergeConfigurationBranch(cn, "templates", false, false);
            }
        }
        /* Go through list again - this time do custom- ones */
        for(String tname: templates) {
            /* If matches naming convention */
            if(tname.endsWith(".txt") && tname.startsWith(CUSTOM_PREFIX)) {
                File tf = new File(templatedir, tname);
                ConfigurationNode cn = new ConfigurationNode(tf);
                cn.load();
                /* This are overrides - replace even configuration.txt content */
                mergeConfigurationBranch(cn, "templates", true, false);
            }
        }
    }

    public boolean enableCore() {
        boolean rslt = initConfiguration(null);
        if (rslt)
            rslt = enableCore(null);
        return rslt;
    }

    public boolean initConfiguration(EnableCoreCallbacks cb) {
        /* Start with clean events */
        events = new Events();
        /* Default to being unprotected - set to protected by update components */
        player_info_protected = false;

        /* Load plugin version info */
        loadVersion();

        /* Initialize confguration.txt if needed */
        File f = new File(dataDirectory, "configuration.txt");
        if(!createDefaultFileFromResource("/configuration.txt", f)) {
            return false;
        }

        /* Load configuration.txt */
        configuration = new ConfigurationNode(f);
        configuration.load();
        if (PublicMapSettings.migrate(configuration) && !configuration.save()) Log.warning("Could not save public map settings upgrade");
        if (TpsSettings.migrate(configuration) && !configuration.save()) Log.warning("Could not save TPS policy migration");
        if (LegacyWebSettings.removeFrom(configuration.entries) && !configuration.save()) {
            Log.warning("Could not save removal of obsolete web settings");
        }

        // Read web path
        webpath = configuration.getString("webpath", "web");
        removeObsoleteWebFiles();
        // And whether to disable web file update
        updatewebpathfiles = configuration.getBoolean("update-webpath-files", true);

        // Check if we are disabling the internal web server (implies external)
        isInternalWebServerDisabled = configuration.getBoolean("disable-webserver", false);

        /* Prime the tiles directory */
        tilesDirectory = getFile(configuration.getString("tilespath", "web/tiles"));
        if (!tilesDirectory.isDirectory() && !tilesDirectory.mkdirs()) {
            Log.warning("Could not create directory for tiles ('" + tilesDirectory + "').");
        }
        // Prime the exports directory
        exportDirectory = getFile(configuration.getString("exportpath", "export"));
        if (!exportDirectory.isDirectory() && !exportDirectory.mkdirs()) {
            Log.warning("Could not create directory for exports ('" + exportDirectory + "').");
        }
        // Prime the imports directory
        importDirectory = getFile(configuration.getString("importpath", "import"));
        if (!importDirectory.isDirectory() && !importDirectory.mkdirs()) {
            Log.warning("Could not create directory for imports ('" + importDirectory + "').");
        }
        // Local map files are the only storage backend.
        if (!configuration.getString("storage/type", "filetree").equalsIgnoreCase("filetree")) {
            Log.severe("MCCMap supports local filetree storage only. Export existing database/object-storage maps before switching storage.type to filetree; existing data is left untouched.");
            return false;
        }
        defaultStorage = new FileTreeMapStorage();
        if (!defaultStorage.init(this)) {
            Log.severe("Map storage initialization failure");
            return false;
        }

        /* Register API with plugin, if needed */
        if(!markerAPIInitialized()) {
            MarkerAPIImpl api = MarkerAPIImpl.initializeMarkerAPI(this);
            this.registerMarkerAPI(api);
        }
        /* Call back to plugin to report that configuration is available */
        if(cb != null)
            cb.configurationLoaded();
        return true;
    }

    private String findExecutableOnPath(String fname) {
        String path = System.getenv("PATH");
        // Fast-fail if path is null.
        if (path == null)
            return null;

        for (String dirname : path.split(File.pathSeparator)) {
            File file = new File(dirname, fname);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
            file = new File(dirname, fname + ".exe");
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    public boolean enableCore(EnableCoreCallbacks cb) {
        /* Update extracted files, if needed */
        updateExtractedFiles();
        /* Load control for leaf transparency (spout lighting bug workaround) */
        transparentLeaves = configuration.getBoolean("transparent-leaves", true);

        // Inject core instance
        ImageIOManager.core = this;
        // Check for webp support
        cwebpPath = configuration.getString("cwebpPath", null);
        dwebpPath = configuration.getString("dwebpPath", null);
        if (cwebpPath == null) {
            cwebpPath = findExecutableOnPath("cwebp");
        }
        if (dwebpPath == null) {
            dwebpPath = findExecutableOnPath("dwebp");
        }
        if (cwebpPath != null) {
            File file = new File(cwebpPath);
            if (!file.isFile() || !file.canExecute()) {
                cwebpPath = null;
            }
        }
        if (dwebpPath != null) {
            File file = new File(dwebpPath);
            if (!file.isFile() || !file.canExecute()) {
                dwebpPath = null;
            }
        }
        if ((cwebpPath != null) && (dwebpPath != null)) {
            Log.info("Found cwebp at " + cwebpPath + " and dwebp at " + dwebpPath + ": webp format enabled");
        }
        else {
            cwebpPath = dwebpPath = null;
        }
        /* Get default image format */
        def_image_format = configuration.getString("image-format", "png");
        MapType.ImageFormat fmt = MapType.ImageFormat.fromID(def_image_format);
        if ((fmt == null) || ((fmt.enc == ImageEncoding.WEBP) && (cwebpPath == null))) {
            Log.severe("Invalid image-format: " + def_image_format);
            def_image_format = "png";
            fmt = MapType.ImageFormat.fromID(def_image_format);
        }


        DynmapWorld.doInitialScan(configuration.getBoolean("initial-zoomout-validate", true));

        smoothlighting = configuration.getBoolean("smooth-lighting", false);
        ctmsupport = configuration.getBoolean("ctm-support", true);
        customcolorssupport = configuration.getBoolean("custom-colors-support", true);
        Log.verbose = configuration.getBoolean("verbose", true);
        deftemplatesuffix = configuration.getString("deftemplatesuffix", "");
        /* Get snapshot cache size */
        snapshotcachesize = configuration.getInteger("snapshotcachesize", 500);
        /* Get soft ref flag for cache (weak=false, soft=true) */
        snapshotsoftref = configuration.getBoolean("soft-ref-cache", true);
        /* Default better-grass */
        bettergrass = configuration.getBoolean("better-grass", false);
        /* Load full render processing player limit */
        fullrenderplayerlimit = configuration.getInteger("fullrenderplayerlimit", 0);
        /* Load update render processing player limit */
        updateplayerlimit = configuration.getInteger("updateplayerlimit", 0);
        /* Load sort permission nodes */
        sortPermissionNodes = configuration.getStrings("player-sort-permission-nodes", null);

        perTickLimit = configuration.getInteger("per-tick-time-limit", 50);
        if (perTickLimit < 5) perTickLimit = 5;

        dumpMissing = configuration.getBoolean("dump-missing-blocks", false);

        migrate_chunks = configuration.getBoolean("migrate-chunks",  false);
        if (migrate_chunks)
            Log.info("EXPERIMENTAL: chunk migration enabled");

        publicURL = configuration.getString("publicURL", "");

        /* Send this message if the player does not have permission to use the command */
        noPermissionMsg = configuration.getString("noPermissionMsg", "You don't have permission to use this command!");

        /* Load preupdate/postupdate commands */
        ImageIOManager.preUpdateCommand = configuration.getString("custom-commands/image-updates/preupdatecommand", "");
        ImageIOManager.postUpdateCommand = configuration.getString("custom-commands/image-updates/postupdatecommand", "");

        /* Get block and item maps */
        blockmap = server.getBlockUniqueIDMap();
        itemmap = server.getItemUniqueIDMap();

        /* Process mod support */
        ModSupportImpl.complete(this.dataDirectory);
        // Finalize block state
        DynmapBlockState.finalizeBlockStates();
        /* Load block models */
        Log.verboseinfo("Loading models...");
        HDBlockModels.loadModels(this, configuration);
        /* Load texture mappings */
        Log.verboseinfo("Loading texture mappings...");
        TexturePack.loadTextureMapping(this, configuration);

        /* Now, process worlds.txt - merge it in as an override of existing values (since it is only user supplied values) */
        File f = new File(dataDirectory, "worlds.txt");
        if(!createDefaultFileFromResource("/worlds.txt", f)) {
            return false;
        }
        world_config = new ConfigurationNode(f);
        world_config.load();
        if (LegacyWebSettings.removeWorldAccess(world_config.entries)) world_config.save();

        /* Now, process templates */
        Log.verboseinfo("Loading templates...");
        loadTemplates();

        loadDebuggers();

        playerList = new PlayerList(getServer(), getFile("hiddenplayers.txt"), configuration);
        playerList.load();

        try {
            storageUsage = new StorageUsageMonitor(java.util.List.of(getDataFolder().toPath(),
                getFile(getWebPath()).toPath(), getTilesFolder().toPath(), getExportFolder().toPath(), getImportFolder().toPath()),
                StorageUsageMonitor.parseLimitGiB(configuration.getString("storage/max-usage-gib", "0")),
                configuration.getInteger("storage/usage-scan-interval-seconds", 60),
                configuration.getDouble("storage/minimum-free-disk-percent", 10),
                StorageUsageMonitor.parseLimitGB(configuration.getString("storage/free-space-failure-max-gb", "10")),
                DiskSpaceReserve::readSpace,ProgressCheckpoint.optional(this,"storage-inventory"));
        } catch (IOException | IllegalArgumentException failure) {
            Log.severe("Could not initialize local storage accounting. Check storage limits, minimum-free-disk-percent (0 to less than 100), and directory access.");
            return false;
        }
        mapManager = new MapManager(this, configuration);
        mapManager.startRendering();

        if (markerapi != null) {
            MarkerAPIImpl.completeInitializeMarkerAPI(markerapi);
        }

        playerfacemgr = new PlayerFaces(this);

        updateConfigHashcode(); /* Initialize/update config hashcode */

        hackAttemptSub = configuration.getString("hackAttemptBlurb", "(IaM5uchA1337Haxr-Ban Me!)");

        // If not disabled, load and initialize the internal web server
        if (!isInternalWebServerDisabled) {
            loadWebserver();
        }


        enabledTriggers.clear();
        List<String> triggers = configuration.getStrings("render-triggers", new ArrayList<String>());
        if ((triggers != null) && (triggers.size() > 0))
        {
            for (Object trigger : triggers) {
                enabledTriggers.add((String) trigger);
            }
        }
        else {
            for (String def : deftriggers) {
                enabledTriggers.add(def);
            }
        }

        // Load components.
        for(Component component : configuration.<Component>createInstances("components", new Class<?>[] { DynmapCore.class }, new Object[] { this })) {
            componentManager.add(component);
        }
        Log.verboseinfo("Loaded " + componentManager.components.size() + " components.");

        if (!isInternalWebServerDisabled) {	// If internal not disabled, we should be using it and not external
            startWebserver();
            if (!componentManager.isLoaded(InternalClientUpdateComponent.class)) {
                Log.warning("Using internal server, but " + InternalClientUpdateComponent.class.toString() + " is DISABLED!");
                webserverCompConfigWarn = true;
            }
            if (componentManager.isLoaded(JsonFileClientUpdateComponent.class)) {
                Log.warning("Using internal server, but " + JsonFileClientUpdateComponent.class.toString() + " is ENABLED!");
            }
        }
        else {
            if (componentManager.isLoaded(InternalClientUpdateComponent.class)) {
                Log.warning("Using external server, but " + InternalClientUpdateComponent.class.toString() + " is ENABLED!");
            }
            if (!componentManager.isLoaded(JsonFileClientUpdateComponent.class)) {
                Log.warning("Using external server, but " + JsonFileClientUpdateComponent.class.toString() + " is DISABLED!");
                webserverCompConfigWarn = true;
            }
        }
        if (webserverCompConfigWarn) {
            Log.warning("If the website is missing files or not loading/updating, this might be why.");
            Log.warning("For more info, read this: " + CompConfigWiki);
            webserverCompConfigWarn = false;
        }

        /* Track Minecraft player presence */
        listenerManager.addListener(EventType.PLAYER_JOIN, new DynmapListenerManager.PlayerEventListener() {
            @Override
            public void playerEvent(DynmapPlayer p) {
                playerJoined(p);
            }
        });
        listenerManager.addListener(EventType.PLAYER_QUIT, new DynmapListenerManager.PlayerEventListener() {
            @Override
            public void playerEvent(DynmapPlayer p) {
                playerQuit(p);
            }
        });

        /* Print version info */
        Log.info("MCCMap " + plugin_ver + " enabled — fork of Dynmap 3.9-SNAPSHOT (93b454e); core " + version);
        Log.info("MCCMap: independent Paper-only fork. Contact your MCCMap maintainer for support.");

        events.<Object>trigger("initialized", null);

        if (configuration.getBoolean("dumpColorMaps", false)) {
            dumpColorMap("standard.txt", "standard");
            dumpColorMap("default.txt", "standard");
            dumpColorMap("dokudark.txt", "dokudark.zip");
            dumpColorMap("dokulight.txt", "dokulight.zip");
            dumpColorMap("dokuhigh.txt", "dokuhigh.zip");
            dumpColorMap("misa.txt", "misa.zip");
            dumpColorMap("sphax.txt", "sphax.zip");
            dumpColorMap("ovocean.txt", "ovocean.zip");
            dumpColorMap("flames.txt", "standard");	// No TP around for this
            dumpColorMap("sk89q.txt", "standard");	// No TP around for this
            dumpColorMap("amidst.txt", "standard");	// No TP around for this
        }

        if (configuration.getBoolean("dumpBlockState", false)) {
            Log.info("Block State Dump");
            Log.info("----------------");
            for (int i = 0; i < DynmapBlockState.getGlobalIndexMax(); i++) {
                DynmapBlockState bs = DynmapBlockState.getStateByGlobalIndex(i);
                if (bs != null) {
                    Log.info(String.format("%d: %s (index %d)", i, bs.toString(), bs.stateIndex));
                }
            }
            Log.info("----------------");
        }
        if (configuration.getBoolean("dumpBlockNames", false)) {
            Log.info("Block Name dump");
            Log.info("---------------");
            for (int i = 0; i < DynmapBlockState.getGlobalIndexMax(); ) {
                DynmapBlockState bs = DynmapBlockState.getStateByGlobalIndex(i);
                if (bs != null) {
                    Log.info(String.format("%d,%s,%d", i, bs.blockName, bs.getStateCount()));
                    i += bs.getStateCount();
                }
                else {
                    i++;
                }
            }
            Log.info("---------------");
        }
        return true;
    }

    void dumpColorMap(String id, String name) {
        int[] sides = new int[] { BlockStep.Y_MINUS.ordinal(), BlockStep.X_PLUS.ordinal(), BlockStep.Z_PLUS.ordinal(),
                BlockStep.Y_PLUS.ordinal(), BlockStep.X_MINUS.ordinal(), BlockStep.Z_MINUS.ordinal() };
        FileWriter fw = null;
        try {
            fw = new FileWriter(new File(new File(getDataFolder(), "colorschemes"), id));
            TexturePack tp = TexturePack.getTexturePack(this, name);
            if (tp == null) return;
            tp = tp.resampleTexturePack(1);
            if (tp == null) return;
            Color c = new Color();
            for (int gidx = 0; gidx < DynmapBlockState.getGlobalIndexMax(); gidx++) {
                DynmapBlockState blk = DynmapBlockState.getStateByGlobalIndex(gidx);
                if (blk.isAir()) continue;
                int meta0color = 0;
                HDBlockStateTextureMap map = HDBlockStateTextureMap.getByBlockState(blk);
                boolean done = false;
                for (int i = 0; (!done) && (i < sides.length); i++) {
                    int idx = map.getIndexForFace(sides[i]);
                    if (idx < 0) continue;
                    int rgb[] = tp.getTileARGB(idx % 1000000);
                    if (rgb == null) continue;
                    if (rgb[0] == 0) continue;
                    c.setARGB(rgb[0]);
                    idx = (idx / 1000000);
                    switch(idx) {
                        case 1: // grass
                        case 18: // grass
                            Log.verboseinfo("Used grass for " + blk);
                            c.blendColor(tp.getTrivialGrassMultiplier() | 0xFF000000);
                            break;
                        case 2: // foliage
                        case 19: // foliage
                        case 22: // foliage
                            Log.verboseinfo("Used foliage for " + blk);
                            c.blendColor(tp.getTrivialFoliageMultiplier() | 0xFF000000);
                            break;
                        case 13: // pine
                            c.blendColor(0x619961 | 0xFF000000);
                            break;
                        case 14: // birch
                            c.blendColor(0x80a755 | 0xFF000000);
                            break;
                        case 15: // lily
                            c.blendColor(0x208030 | 0xFF000000);
                            break;
                        case 3: // water
                        case 20: // water
                            Log.verboseinfo("Used water for " + blk);
                            c.blendColor(tp.getTrivialWaterMultiplier() | 0xFF000000);
                            break;
                        case 12: // clear inside
                            if (blk.isWater()) { // special case for water
                                Log.verboseinfo("Used water for " + blk);
                                c.blendColor(tp.getTrivialWaterMultiplier() | 0xFF000000);
                            }
                            break;
                    }
                    int custmult = tp.getCustomBlockMultiplier(blk);
                    if (custmult != 0xFFFFFF) {
                        Log.info(String.format("Custom color: %06x for %s", custmult, blk));
                        if ((custmult & 0xFF000000) == 0) {
                            custmult |= 0xFF000000;
                        }
                        c.blendColor(custmult);
                    }
                    String ln = "";
                    if (blk.stateIndex == 0) {
                        meta0color = c.getARGB();
                        ln = blk.blockName + " ";
                    }
                    else {
                        ln = blk + " ";
                    }
                    if ((blk.stateIndex == 0) || (meta0color != c.getARGB())) {
                        ln += c.getRed() + " " + c.getGreen() + " " + c.getBlue() + " " + c.getAlpha();
                        ln += " " + (c.getRed()*4/5) + " " + (c.getGreen()*4/5) + " " + (c.getBlue()*4/5) + " " + c.getAlpha();
                        ln += " " + (c.getRed()/2) + " " + (c.getGreen()/2) + " " + (c.getBlue()/2) + " " + c.getAlpha();
                        ln += " " + (c.getRed()*2/5) + " " + (c.getGreen()*2/5) + " " + (c.getBlue()*2/5) + " " + c.getAlpha() + "\n";
                        fw.write(ln);
                    }
                    done = true;
                }
            }
        } catch (IOException iox) {
        } finally {
            if (fw != null) { try { fw.close(); } catch (IOException x) {} }
            Log.info("Dumped RP=" + name + " to " + id);
        }
    }

    private void playerJoined(DynmapPlayer p) {
        playerList.updateOnlinePlayers(null);
        applyRenderingPauses(getServer().getOnlinePlayers().length);
        /* Check sort weight permissions list */
        if ((sortPermissionNodes != null) && (sortPermissionNodes.size() > 0)) {
            int ord;
            for (ord = 0; ord < sortPermissionNodes.size(); ord++) {
                if (p.hasPermissionNode(sortPermissionNodes.get(ord))) {
                    break;
                }
            }
            p.setSortWeight(ord);
        }
        else {
            p.setSortWeight(0); // Initialize to zero
        }
        /* And re-attach to active jobs */
        if(mapManager != null)
            mapManager.connectTasksToPlayer(p);
    }

    /* Called by plugin each time a player quits the server */
    private void playerQuit(DynmapPlayer p) {
        playerList.updateOnlinePlayers(p.getName());
        applyRenderingPauses(Math.max(0, getServer().getOnlinePlayers().length - 1));
    }

    void applyRenderingPauses(int players) {
        if (mapManager == null) return;
        mapManager.updateRenderPlayerCount(players);
        mapManager.setPauseFullRadiusRenders(manualFullPause || (fullrenderplayerlimit > 0 && players >= fullrenderplayerlimit));
        mapManager.setPauseUpdateRenders(manualUpdatePause || (updateplayerlimit > 0 && players >= updateplayerlimit));
    }

    public void updateConfigHashcode() {
        config_hashcode = (int)System.currentTimeMillis();
    }

    public int getConfigHashcode() {
        return config_hashcode;
    }

    public void loadWebserver() {
        String ip = server.getServerIP();
        if ((ip == null) || (ip.trim().length() == 0)) {
            ip = "0.0.0.0";
        }
        webhostname = configuration.getString("webserver-bindaddress", ip);
        webport = configuration.getInteger("webserver-port", 8123);

        int maxconnections = configuration.getInteger("max-connections", 30);
        if(maxconnections < 2) maxconnections = 2;
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
        ExecutorThreadPool pool = new ExecutorThreadPool(maxconnections, 2, queue);

        webServer = new Server(pool);

        NetworkTrafficServerConnector connector = new NetworkTrafficServerConnector(webServer);
        if (configuration.getBoolean("webserver-connection-log/enabled", false)) {
            try {
                java.nio.file.Path logFolder = getDataFolder().toPath().toAbsolutePath().normalize().resolve("logs");
                java.nio.file.Path siteFolder = getFile(getWebPath()).toPath().toRealPath();
                java.nio.file.Path canonicalLog = getDataFolder().toPath().toRealPath().resolve("logs");
                if (canonicalLog.startsWith(siteFolder)) throw new IOException("Log directory would be publicly served");
                var connectionLog = new org.dynmap.web.WebConnectionLog(logFolder,
                    1024L * 1024 * Math.max(1, Math.min(100, configuration.getInteger("webserver-connection-log/max-file-size-mb", 10))),
                    configuration.getInteger("webserver-connection-log/retained-files", 5),
                    configuration.getString("webserver-connection-log/timezone", "system"));
                connectionLog.start();
                webServer.addBean(connectionLog, true);
                connector.setNetworkTrafficListener(connectionLog);
            } catch (Exception failure) {
                Log.warning("Web connection logging disabled: could not safely initialize its private log files");
            }
        }
        connector.setIdleTimeout(5000);
        connector.setAcceptQueueSize(50);
        int maxOpenConnections = Math.max(16, Math.min(10000, configuration.getInteger("max-open-connections", 256)));
        webServer.addBean(new org.eclipse.jetty.server.ConnectionLimit(maxOpenConnections, connector));
        if(webhostname.equals("0.0.0.0") == false)
            connector.setHost(webhostname);
        connector.setPort(webport);
        webServer.setConnectors(new Connector[]{connector});

        webServer.setStopAtShutdown(true);
        //webServer.setGracefulShutdown(1000);
        final boolean allow_symlinks = configuration.getBoolean("allow-symlinks", false);
        ResourceHandler fileResources = new ResourceHandler();
        fileResources.setWelcomeFiles(new String[] { "index.html" });
        fileResources.setRedirectWelcome(false);
        fileResources.setDirectoriesListed(false);
        fileResources.setBaseResource(getFile(getWebPath()).toPath());
        router = new HandlerRouter();
        router.addHandler("/", fileResources);
        MapStorageResourceHandler tiles = new MapStorageResourceHandler();
        tiles.setCore(this);
        router.addHandler("/tiles/*", tiles);

        if(allow_symlinks)
            Log.verboseinfo("Web server is permitting symbolic links");
        else
            Log.verboseinfo("Web server is not permitting symbolic links");

        List<Filter> filters = new LinkedList<Filter>();

        /* Check for banned IPs */
        boolean checkbannedips = configuration.getBoolean("check-banned-ips", true);
        if (checkbannedips) {
            filters.add(new BanIPFilter(this));
        }
        filters.add(new FileNameFilter(this));


        /* Load customized response headers, if any */
        filters.add(new CustomHeaderFilter(configuration.getNode("http-response-headers")));

        FilterHandler fh = new FilterHandler(router, filters);
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.setHandler(fh);
        contextHandler.clearAliasChecks();
        if (allow_symlinks) contextHandler.addAliasCheck(
            new org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker(contextHandler.getCoreContextHandler()));
        webServer.setHandler(contextHandler.getCoreContextHandler());

        addServlet("/up/configuration", new ClientConfigurationServlet(this));
        addServlet("/standalone/config.js", new ConfigJSServlet(this));
    }

    public boolean isCTMSupportEnabled() {
        return ctmsupport;
    }

    public boolean isCustomColorsSupportEnabled() {
        return customcolorssupport;
    }

    public Set<String> getIPBans() {
        return getServer().getIPBans();
    }

    public void addServlet(String path, HttpServlet servlet) {
        router.addServlet(path, servlet);
     }


    public void startWebserver() {
        try {
            if(webServer != null) {
                webServer.start();
                Log.info("Web server started on address " + webhostname + ":" + webport);
            }
        } catch (Exception e) {
            Log.severe("Failed to start WebServer on address " + webhostname + ":" + webport + " : " + e.getMessage());
        }
    }

    public void disableCore() {

        if (webServer != null) {
            try {
                webServer.stop();
                for(int i = 0; i < 100; i++) {	/* Limit wait to 10 seconds */
                    if(webServer.isStopping())
                        Thread.sleep(100);
                }
                if(webServer.isStopping()) {
                    Log.warning("Graceful shutdown timed out - continuing to terminate");
                }
            } catch (Exception e) {
                Log.severe("Failed to stop WebServer!", e);
            }
            webServer = null;
        }

        if (componentManager != null) {
            int componentCount = componentManager.components.size();
            for(Component component : componentManager.components) {
                component.dispose();
            }
            componentManager.clear();
            Log.info("Unloaded " + componentCount + " components.");
        }

        if (playerfacemgr != null) playerfacemgr.close();
        if (mapManager != null) {
            mapManager.stopRendering();
            mapManager = null;
        }
        if (storageUsage != null) { storageUsage.close(); storageUsage = null; }
        if (defaultStorage != null) {
            defaultStorage.shutdownStorage();
        }
        playerfacemgr = null;
        /* Clean up registered listeners */
        listenerManager.cleanup();

        /* Don't clean up markerAPI - other plugins may still be accessing it */


        Debug.clearDebuggers();
    }

    private static File combinePaths(File parent, String path) {
        return combinePaths(parent, new File(path));
    }

    private static File combinePaths(File parent, File path) {
        if (path.isAbsolute())
            return path;
        return new File(parent, path.getPath());
    }

    public File getFile(String path) {
        return combinePaths(getDataFolder(), path);
    }

    protected void loadDebuggers() {
        List<ConfigurationNode> debuggersConfiguration = configuration.getNodes("debuggers");
        Debug.clearDebuggers();
        for (ConfigurationNode debuggerConfiguration : debuggersConfiguration) {
            try {
                Class<?> debuggerClass = Class.forName((String) debuggerConfiguration.getString("class"));
                Constructor<?> constructor = debuggerClass.getConstructor(DynmapCore.class, ConfigurationNode.class);
                Debugger debugger = (Debugger) constructor.newInstance(this, debuggerConfiguration);
                Debug.addDebugger(debugger);
            } catch (Exception e) {
                Log.severe("Error loading debugger: " + e);
                e.printStackTrace();
                continue;
            }
        }
    }

    /* Parse argument strings : handle quoted strings */
    public static String[] parseArgs(String[] args, DynmapCommandSender snd) {
        return parseArgs(args, snd, false);
    }

    /* Parse argument strings : handle quoted strings */
    public static String[] parseArgs(String[] args, DynmapCommandSender snd, boolean allowUnclosedQuotes) {
        ArrayList<String> rslt = new ArrayList<String>();
        /* Build command line, so we can parse our way - make sure there is trailing space */
        String cmdline = "";
        for(int i = 0; i < args.length; i++) {
            cmdline += args[i] + " ";
        }
        boolean inquote = false;
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < cmdline.length(); i++) {
            char c = cmdline.charAt(i);
            if(inquote) {   /* If in quote, accumulate until end or another quote */
                if(c == '\"') { /* End quote */
                    inquote = false;
                }
                else {
                    sb.append(c);
                }
            }
            else if(c == '\"') {    /* Start of quote? */
                inquote = true;
            }
            else if(c == ' ') { /* Ending space? */
                rslt.add(sb.toString());
                sb.setLength(0);
            }
            else {
                sb.append(c);
            }
        }
        if(inquote) {  // If still in quote
            if(allowUnclosedQuotes) {
                if(sb.length() > 1) { // Add remaining input without trailing space
                    rslt.add(sb.substring(0, sb.length() - 1));
                }
            } else { // Syntax error
                snd.sendMessage("Error: unclosed doublequote");
                return null;
            }
        }
        return rslt.toArray(new String[rslt.size()]);
    }

    private static final Set<String> commands = new HashSet<String>(Arrays.asList(new String[] {
        "render",
        "hide",
        "show",
        "version",
        "fullrender",
        "cancelrender",
        "radiusrender",
        "updaterender",
        "stats",
        "progress",
        "naturalrender",
        "tpscheck",
        "triggerstats",
        "resetstats",
        "pause",
        "purgequeue",
        "purgemap",
        "purgeworld",
        "quiet",
        "dumpmemory",
        "url",
        "help"}));

    private static class CommandInfo {
        final String cmd;
        final String subcmd;
        final String args;
        final String helptext;
        public CommandInfo(String cmd, String subcmd, String helptxt) {
            this.cmd = cmd;
            this.subcmd = subcmd;
            this.helptext = helptxt;
            this.args = "";
        }
        public CommandInfo(String cmd, String subcmd, String args, String helptxt) {
            this.cmd = cmd;
            this.subcmd = subcmd;
            this.args = args;
            this.helptext = helptxt;
        }
        public boolean matches(String c, String sc) {
            return (cmd.equals(c) && subcmd.equals(sc));
        }
        public boolean matches(String c) {
            return cmd.equals(c);
        }
    };

    private static final CommandInfo[] commandinfo = {
        new CommandInfo("mccmap", "", "Control execution of mccmap."),
        new CommandInfo("mccmap", "hide", "Hides the current player from the map."),
        new CommandInfo("mccmap", "hide", "<player>", "Hides <player> on the map."),
        new CommandInfo("mccmap", "show", "Shows the current player on the map."),
        new CommandInfo("mccmap", "show", "<player>", "Shows <player> on the map."),
        new CommandInfo("mccmap", "render", "Renders the tile at your location."),
        new CommandInfo("mccmap", "fullrender", "Render all maps for entire world from your location."),
        new CommandInfo("mccmap", "fullrender", "<world>", "Render all maps for world <world>."),
        new CommandInfo("mccmap", "fullrender", "<world>:<map>", "Render map <map> of world <world>."),
        new CommandInfo("mccmap", "fullrender", "resume <world>", "Resume render of all maps for world <world>. Skip already rendered tiles."),
        new CommandInfo("mccmap", "fullrender", "resume <world>:<map>", "Resume render of map <map> of world <world>. Skip already rendered tiles."),
        new CommandInfo("mccmap", "radiusrender", "<radius>", "Render at least <radius> block radius from your location on all maps."),
        new CommandInfo("mccmap", "radiusrender", "<radius> <mapname>", "Render at least <radius> block radius from your location on map <mapname>."),
        new CommandInfo("mccmap", "radiusrender", "<world> <x> <z> <radius>", "Render at least <radius> block radius from location <x>,<z> on world <world>."),
        new CommandInfo("mccmap", "radiusrender", "<world> <x> <z> <radius> <map>", "Render at least <radius> block radius from location <x>,<z> on world <world> on map <map>."),
        new CommandInfo("mccmap", "updaterender", "Render updates starting at your location on all maps."),
        new CommandInfo("mccmap", "updaterender", "<map>", "Render updates starting at your location on map <map>."),
        new CommandInfo("mccmap", "updaterender", "<world> <x> <z> <map>", "Render updates starting at location <x>,<z> on world <world> for map <map>."),
        new CommandInfo("mccmap", "cancelrender", "Cancels any active renders on current world."),
        new CommandInfo("mccmap", "cancelrender", "<world>", "Cancels any active renders of world <world>."),
        new CommandInfo("mccmap", "stats", "Show render statistics."),
        new CommandInfo("mccmap", "progress", "[world]", "Show per-map render percentage and estimated time remaining."),
        new CommandInfo("mccmap", "naturalrender", "[status|on|off|resume]", "Control automatic background map rendering."),
        new CommandInfo("mccmap", "tpscheck", "off|on|status", "Bypass TPS checks for 24 hours, restore them, or show status."),
        new CommandInfo("mccmap", "triggerstats", "Show render update trigger statistics."),
        new CommandInfo("mccmap", "resetstats", "Reset render statistics."),
        new CommandInfo("mccmap", "purgequeue", "Empty all pending tile updates from update queue."),
        new CommandInfo("mccmap", "purgequeue", "<world>", "Empty all pending tile updates from update queue for world <world>."),
        new CommandInfo("mccmap", "purgemap", "<world> <map>", "Delete all existing tiles for map <map> on world <world>."),
        new CommandInfo("mccmap", "purgeworld", "<world>", "Delete all existing directories for world <world>."),
        new CommandInfo("mccmap", "pause", "Show render pause state."),
        new CommandInfo("mccmap", "pause", "<all|none|full|update>", "Set render pause state."),
        new CommandInfo("mccmap", "quiet", "Stop output from active jobs."),
        new CommandInfo("mccmap", "version", "Return version information"),
        new CommandInfo("mccmap", "dumpmemory", "Return mempry use information"),
        new CommandInfo("mccmap", "url", "Return confgured URL for Dynmap web"),
        new CommandInfo("mccmarker", "", "Manipulate map markers."),
        new CommandInfo("mccmarker", "add", "<label>", "Add new marker with label <label> at current location (use double-quotes if spaces needed)."),
        new CommandInfo("mccmarker", "add", "id:<id> <label>", "Add new marker with ID <id> at current location (use double-quotes if spaces needed)."),
        new CommandInfo("mccmarker", "movehere", "<label>", "Move marker with label <label> to current location."),
        new CommandInfo("mccmarker", "movehere", "id:<id>", "Move marker with ID <id> to current location."),
        new CommandInfo("mccmarker", "update", "<label> icon:<icon> newlabel:<newlabel>", "Update marker with ID <id> with new label <newlabel> and new icon <icon>."),
        new CommandInfo("mccmarker", "delete", "<label>", "Delete marker with label of <label>."),
        new CommandInfo("mccmarker", "delete", "id:<id>", "Delete marker with ID of <id>."),
        new CommandInfo("mccmarker", "list", "List details of all markers."),
        new CommandInfo("mccmarker", "icons", "List details of all icons."),
        new CommandInfo("mccmarker", "addset", "<label>", "Add marker set with label <label>."),
        new CommandInfo("mccmarker", "addset", "id:<id> <label>", "Add marker set with ID <id> and label <label>."),
        new CommandInfo("mccmarker", "updateset", "id:<id> newlabel:<newlabel>", "Update marker set with ID <id> with new label <newlabel>."),
        new CommandInfo("mccmarker", "updateset", "<label> newlabel:<newlabel>", "Update marker set with label <label> to new label <newlabel>."),
        new CommandInfo("mccmarker", "deleteset", "<label>", "Delete marker set with label <label>."),
        new CommandInfo("mccmarker", "deleteset", "id:<id>", "Delete marker set with ID of <id>."),
        new CommandInfo("mccmarker", "listsets", "List all marker sets."),
        new CommandInfo("mccmarker", "addicon", "id:<id> <label> file:<filename>", "Install new icon with ID <id> using image file <filename>"),
        new CommandInfo("mccmarker", "updateicon", "id:<id> newlabel:<newlabel> file:<filename>", "Update existing icon with ID of <id> with new label or file."),
        new CommandInfo("mccmarker", "updateicon", "<label> newlabel:<newlabel> file:<filename>", "Update existing icon with label of <label> with new label or file."),
        new CommandInfo("mccmarker", "deleteicon", "id:<id>", "Remove icon with ID of <id>."),
        new CommandInfo("mccmarker", "deleteicon", "<label>", "Remove icon with label of <label>."),
        new CommandInfo("mccmarker", "addcorner", "Add corner to corner list using current location."),
        new CommandInfo("mccmarker", "addcorner", "<x> <y> <z> <world>", "Add corner with coordinate <x>, <y>, <z> on world <world> to corner list."),
        new CommandInfo("mccmarker", "clearcorners", "Clear corner list."),
        new CommandInfo("mccmarker", "addarea", "<label>", "Add new area marker with label of <label> using current corner list."),
        new CommandInfo("mccmarker", "addarea", "id:<id> <label>", "Add new area marker with ID of <id> using current corner list."),
        new CommandInfo("mccmarker", "deletearea", "<label>", "Delete area marker with label of <label>."),
        new CommandInfo("mccmarker", "deletearea", "id:<id> <label>", "Delete area marker with ID of <id>."),
        new CommandInfo("mccmarker", "listareas", "List details of all area markers."),
        new CommandInfo("mccmarker", "updatearea", "<label> <arg>:<value> ...", "Update attributes of area marker with label of <label>."),
        new CommandInfo("mccmarker", "updatearea", "id:<id> <arg>:<value> ...", "Update attributes of area marker with ID of <id>."),
        new CommandInfo("mccmarker", "addline", "<label>", "Add new poly-line marker with label of <label> using current corner list."),
        new CommandInfo("mccmarker", "addline", "id:<id> <label>", "Add new poly-line marker with ID of <id> using current corner list."),
        new CommandInfo("mccmarker", "deleteline", "<label>", "Delete poly-line marker with label of <label>."),
        new CommandInfo("mccmarker", "deleteline", "id:<id>", "Delete poly-line marker with ID of <id>."),
        new CommandInfo("mccmarker", "listlines", "List details of all poly-line markers."),
        new CommandInfo("mccmarker", "updateline", "<label> <arg>:<value> ...", "Update attributes of poly-line marker with label of <label>."),
        new CommandInfo("mccmarker", "updateline", "id:<id> <arg>:<value> ...", "Update attributes of poly-line marker with ID of <id>."),
        new CommandInfo("mccmarker", "addcircle", "<label> radius:<rad>", "Add new circle centered at current location with radius of <radius> and label of <label>."),
        new CommandInfo("mccmarker", "addcircle", "id:<id> <label> radius:<rad>", "Add new circle centered at current location with radius of <radius> and ID of <id>."),
        new CommandInfo("mccmarker", "addcircle", "<label> radius:<rad> x:<x> y:<y> z:<z> world:<world>", "Add new circle centered at <x>,<y>,<z> on world <world> with radius of <rad> and label of <label>."),
        new CommandInfo("mccmarker", "deletecircle", "<label>", "Delete circle with label of <label>."),
        new CommandInfo("mccmarker", "deletecircle", "id:<id>", "Delete circle with ID of <id>."),
        new CommandInfo("mccmarker", "listcircles", "List details of all circles."),
        new CommandInfo("mccmarker", "updatecircle", "<label> <arg>:<value> ...", "Update attributes of circle with label of <label>."),
        new CommandInfo("mccmarker", "updatecircle", "id:<id> <arg>:<value> ...", "Update attributes of circle with ID of <id>."),
        new CommandInfo("mccmapconfig", "", "List and modify dynmap configuration."),
        new CommandInfo("mccmapconfig", "worldlist", "List all worlds configured (enabled or disabled)."),
        new CommandInfo("mccmapconfig", "worldset", "<world> enabled:<true|false>", "Enable or disable world named <world>."),
        new CommandInfo("mccmapconfig", "worldset", "<world> center:<x/y/z|here|default>", "Set map center for world <world> to ccoordinates <x>,<y>,<z>."),
        new CommandInfo("mccmapconfig", "worldset", "<world> extrazoomout:<N>", "Set extra zoom out levels for world <world>."),
        new CommandInfo("mccmapconfig", "maplist", "<world>", "List all maps for world <world>"),
        new CommandInfo("mccmapconfig", "mapdelete", "<world>:<map>", "Delete map <map> from world <world>."),
        new CommandInfo("mccmapconfig", "mapadd", "<world>:<map> <attrib>:<value> <attrib>:<value>", "Create map for world <world> with name <map> using provided attributes."),
        new CommandInfo("mccmapconfig", "mapset", "<world>:<map> <attrib>:<value> <attrib>:<value>", "Update map <map> of world <world> with new attribute values."),
        new CommandInfo("mccmapconfig", "worldreset", "<world>", "Reset world <world> to default template for world type"),
        new CommandInfo("mccmapconfig", "worldreset", "<world> <templatename>", "Reset world <world> to template <templatename>."),
        new CommandInfo("mccmapconfig", "worldgetlimits", "<world>", "List visibity and hidden limits for world"),
        new CommandInfo("mccmapconfig", "worldaddlimit", "<world> corner1:<x>/<z> corner2:<x>/<z>", "Add rectangular visibilty limit"),
        new CommandInfo("mccmapconfig", "worldaddlimit", "<world> type:round center:<x>/<z> radius:<radius>", "Add round visibilty limit"),
        new CommandInfo("mccmapconfig", "worldaddlimit", "<world> limittype:hidden corner1:<x>/<z> corner2:<x>/<z>", "Add rectangular hidden limit"),
        new CommandInfo("mccmapconfig", "worldaddlimit", "<world> limittype:hidden hitype:round center:<x>/<z> radius:<radius>", "Add round hidden limit"),
        new CommandInfo("mccmapconfig", "worldremovelimit", "<world> <limit-index>", "Remove world limit with index limit-index"),
        new CommandInfo("mccmapexport", "", "Set and execute exports in OBJ format."),
        new CommandInfo("mccmapexport", "set", "<attrib> <value> ...", "Set bounds attributes for OBJ export."),
        new CommandInfo("mccmapexport", "reset", "Reset all bounds for OBJ export."),
        new CommandInfo("mccmapexport", "pos0", "Set first corner of bounds to player's position."),
        new CommandInfo("mccmapexport", "pos1", "Set second corner of bounds to player's position."),
        new CommandInfo("mccmapexport", "radius", "<radius>", "Set bounds to radius <radius> around player's position."),
        new CommandInfo("mccmapexport", "export", "<name>", "Export map data to <name>.zip in export path."),
        new CommandInfo("mccmapexport", "purge", "<name>", "Purge exported map data <name>.zip from export path.")
    };

    public void printCommandHelp(DynmapCommandSender sender, String cmd, String subcmd) {
        boolean matched = false;
        if((subcmd != null) && (!subcmd.equals(""))) {
            for(CommandInfo ci : commandinfo) {
                if(ci.matches(cmd, subcmd)) {
                    sender.sendMessage(String.format("/%s %s %s - %s", ci.cmd, ci.subcmd, ci.args, ci.helptext));
                    matched = true;
                }
            }
            if(!matched) {
                sender.sendMessage("Invalid subcommand: " + subcmd);
            }
            else {
                return;
            }
        }
        for(CommandInfo ci : commandinfo) {
            if(ci.matches(cmd, "")) {
                sender.sendMessage(String.format("/%s - %s", ci.cmd, ci.helptext));
            }
        }
        String subcmdlist = " Valid subcommands:";
        TreeSet<String> ts = new TreeSet<String>();
        for(CommandInfo ci : commandinfo) {
            if(ci.matches(cmd)) {
                ts.add(ci.subcmd);
            }
        }
        for(String sc : ts) {
            subcmdlist += " " + sc;
        }
        sender.sendMessage(subcmdlist);
    }

    /**
     * Returns tab completion suggestions for subcommands
     *
     * @param sender - The command sender requesting the tab completion suggestions
     * @param cmd    - The top level command to suggest for
     * @param arg    - Optional partial subcommand name to filter by
     * @return List of tab completion suggestions
     */
    List<String> getSubcommandSuggestions(DynmapCommandSender sender, String cmd, String arg) {
        List<String> suggestions = new ArrayList<>();

        for (CommandInfo ci : commandinfo) {
            //TODO: Permission checks
            if (ci.matches(cmd) && ci.subcmd.startsWith(arg) && !suggestions.contains(ci.subcmd)) {
                suggestions.add(ci.subcmd);
            }
        }

        return suggestions;
    }

    /**
     * Returns tab completion suggestions for world names
     *
     * @param arg - Partial world name to filter by
     * @return List of tab completion suggestions
     */
    public List<String> getWorldSuggestions(String arg) {
        return mapManager.getWorlds().stream()
                .map(DynmapWorld::getName)
                .filter(name -> name.startsWith(arg))
                .collect(Collectors.toList());
    }

    /**
     * Returns tab completion suggestions for map names of a specific world
     *
     * @param worldName      - Name of the world
     * @param mapArg         - Partial map name to filter by
     * @param colonSeparated - Whether to return suggestions in world:map format
     * @return List of tab completion suggestions
     */
    List<String> getMapSuggestions(String worldName, String mapArg, boolean colonSeparated) {
        DynmapWorld world = mapManager.getWorld(worldName);

        if (world != null) {
            //Don't suggest anything if the argument contains a space as the client doesn't handle this well
            if(mapArg.contains(" ")) {
                return Collections.emptyList();
            }

            return world.maps.stream()
                    .filter(map -> map.getName().startsWith(mapArg))
                    .map(map -> {
                        if (map.getName().contains(" ")) { //Quote if map name contains a space
                            return "\"" + (colonSeparated ? worldName + ":" + map.getName() : map.getName()) + "\"";
                        } else {
                            return colonSeparated ? worldName + ":" + map.getName() : map.getName();
                        }
                    })
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    /**
     * Returns tab completion suggestions for map names without a world name, in world:map format
     *
     * @param arg - Partial world:map name to filter by
     * @return List of tab completion suggestions
     */
    List<String> getMapSuggestions(String arg) {
        int colon = arg.indexOf(":");
        final String worldName = (colon >= 0) ? arg.substring(0, colon) : arg;
        String mapArg = (colon >= 0) ? arg.substring(colon + 1) : null;

        //Don't suggest anything if the argument contains a space as the client doesn't handle this well
        if(arg.contains(" ")) {
            return Collections.emptyList();
        }

        if (mapArg != null) {
            return getMapSuggestions(worldName, mapArg, true);
        }

        List<String> suggestions = new ArrayList<>();

        mapManager.getWorlds().stream()
                .filter(world -> world.getName().startsWith(worldName))
                .forEach(world -> {
                    List<String> maps = world.maps.stream()
                            .map(map -> {
                                if (map.getName().contains(" ")) { //Quote if map name contains a space
                                    return "\"" + world.getName() + ":" + map.getName() + "\"";
                                } else {
                                    return world.getName() + ":" + map.getName();
                                }
                            })
                            .collect(Collectors.toList());
                    suggestions.addAll(maps);
                });

        return suggestions;
    }

    /**
     * Returns tab completion suggestions for field:value args based on the provided arguments
     * If the last provided argument contains a ":", values for the field will be suggested if present
     * Otherwise fields will be suggested if they do not already exist with a value in the provided arguments
     *
     * @param args - Array of already provided command arguments
     * @param fields - Map of possible field names and suppliers for values
     * @return List of tab completion suggestions
     */
    public List<String> getFieldValueSuggestions(String[] args, Map<String, Supplier<String[]>> fields) {
        if (args.length == 0 || fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> suggestions = new ArrayList<>(fields.keySet());
        String[] lastArgument = args[args.length - 1].split(":", 2);

        //If last argument is an incomplete field value, suggest matching values for that field.
        if (lastArgument.length == 2) {
            if (fields.containsKey(lastArgument[0])) {
                //Don't suggest anything if the value contains a space as the client doesn't handle this well
                if(lastArgument[1].contains(" ")) {
                    return Collections.emptyList();
                }

                return Arrays.stream(fields.get(lastArgument[0]).get())
                        .filter(value -> value.startsWith(lastArgument[1]))
                        //Format suggestions as field:value, quoting the value if it contains a space
                        .map(value -> lastArgument[0] + ":" + (value.contains(" ") ? "\"" + value + "\"" : value))
                        .collect(Collectors.toList());
            } else {
                return Collections.emptyList();
            }
        }

        //Remove fields with values in previous args from suggestions
        for (String arg : args) {
            String[] value = arg.split(":");

            if (suggestions.contains(value[0]) && value.length == 2) {
                suggestions.remove(value[0]);
            }
        }

        //Suggest remaining fields
        return suggestions.stream().
                filter(field -> field.startsWith(args[args.length - 1]))
                .map(field -> field + ":")
                .collect(Collectors.toList());
    }

    public boolean processCommand(DynmapCommandSender sender, String cmd, String commandLabel, String[] args) {
        if (mapManager == null) { // Initialization faulure
            sender.sendMessage("MCCMap failed to initialize properly: commands not available");
            return true;
        }
        if(cmd.equalsIgnoreCase("mccmarker")) {
            if(!MarkerAPIImpl.onCommand(this, sender, cmd, commandLabel, args)) {
                printCommandHelp(sender, cmd, (args.length > 0)?args[0]:"");
            }
            return true;
        }
        if (cmd.equalsIgnoreCase("mccmapconfig")) {
            if(!dmapcmds.processCommand(sender, cmd, commandLabel, args, this)) {
                printCommandHelp(sender, cmd, (args.length > 0)?args[0]:"");
            }
            return true;
        }
        if (cmd.equalsIgnoreCase("mccmapexport")) {
            if(!dynmapexpcmds.processCommand(sender, cmd, commandLabel, args, this)) {
                printCommandHelp(sender, cmd, (args.length > 0)?args[0]:"");
            }
            return true;
        }
        if (!cmd.equalsIgnoreCase("mccmap"))
            return false;
        DynmapPlayer player = null;
        if (sender instanceof DynmapPlayer)
            player = (DynmapPlayer) sender;
        /* Re-parse args - handle doublequotes */
        args = parseArgs(args, sender);

        if(args == null) {
            printCommandHelp(sender, cmd, "");
            return true;
        }

        if (args.length > 0) {
            String c = args[0];
            if (!commands.contains(c)) {
                printCommandHelp(sender, cmd, "");
                return true;
            }

            if (c.equals("render") && checkPlayerPermission(sender,"render")) {
                if (player != null) {
                    DynmapLocation loc = player.getLocation();
                    if (loc != null) {
                        mapManager.touch(loc.world, (int)loc.x, (int)loc.y, (int)loc.z, "render");
                        sender.sendMessage("Tile render queued.");
                    }
                }
                else {
                    sender.sendMessage("Command can only be issued by player.");
                }
            }
            else if(c.equals("radiusrender") && checkPlayerPermission(sender,"radiusrender")) {
                int radius = 0;
                String mapname = null;
                DynmapLocation loc = null;
                if((args.length == 2) || (args.length == 3)) {  /* Just radius, or <radius> <map> */
                    try {
                        radius = Integer.parseInt(args[1]); /* Parse radius */
                    } catch (NumberFormatException nfe) {
                        sender.sendMessage("Invalid radius: " + args[1]);
                        return true;
                    }
                    if(radius < 0)
                        radius = 0;
                    if(args.length > 2)
                        mapname = args[2];
                    if (player != null)
                        loc = player.getLocation();
                    else
                        sender.sendMessage("Command require <world> <x> <z> <radius> if issued from console.");
                }
                else if(args.length > 3) {  /* <world> <x> <z> */
                    DynmapWorld w = mapManager.worldsLookup.get(args[1]);   /* Look up world */
                    if(w == null) {
                        sender.sendMessage("World '" + args[1] + "' not defined/loaded");
                    }
                    int x = 0, z = 0;
                    try {
                        x = Integer.parseInt(args[2]);
                    } catch (NumberFormatException nfe) {
                        sender.sendMessage("Invalid x coord: " + args[2]);
                        return true;
                    }
                    try {
                        z = Integer.parseInt(args[3]);
                    } catch (NumberFormatException nfe) {
                        sender.sendMessage("Invalid z coord: " + args[3]);
                        return true;
                    }
                    if(args.length > 4) {
                        try {
                            radius = Integer.parseInt(args[4]);
                        } catch (NumberFormatException nfe) {
                            sender.sendMessage("Invalid radius: " + args[4]);
                            return true;
                        }
                    }
                    if(args.length > 5)
                        mapname = args[5];
                    if(w != null)
                        loc = new DynmapLocation(w.getName(), x, 64, z);
                }
                if(loc != null)
                    mapManager.renderWorldRadius(loc, sender, mapname, radius);
            } else if(c.equals("updaterender") && checkPlayerPermission(sender,"updaterender")) {
                String mapname = null;
                DynmapLocation loc = null;
                if(args.length <= 3) {  /* Just command, or command plus map */
                    if(args.length > 1)
                        mapname = args[1];
                    if (player != null)
                        loc = player.getLocation();
                    else
                        sender.sendMessage("Command require <world> <x> <z> <mapname> if issued from console.");
                }
                else {  /* <world> <x> <z> */
                    DynmapWorld w = mapManager.worldsLookup.get(args[1]);   /* Look up world */
                    if(w == null) {
                        sender.sendMessage("World '" + args[1] + "' not defined/loaded");
                    }
                    int x = 0, z = 0;
                    try {
                        x = Integer.parseInt(args[2]);
                    } catch (NumberFormatException nfe) {
                        sender.sendMessage("Invalid x coord: " + args[2]);
                        return true;
                    }
                    try {
                        z = Integer.parseInt(args[3]);
                    } catch (NumberFormatException nfe) {
                        sender.sendMessage("Invalid z coord: " + args[3]);
                        return true;
                    }
                    if(args.length > 4)
                        mapname = args[4];
                    if(w != null)
                        loc = new DynmapLocation(w.getName(), x, 64, z);
                }
                if(loc != null)
                    mapManager.renderFullWorld(loc, sender, mapname, true, false);
            } else if (c.equals("hide")) {
                if (args.length == 1) {
                    if(player != null && checkPlayerPermission(sender,"hide.self")) {
                        playerList.setVisible(player.getName(),false);
                        sender.sendMessage("You are now hidden on MCCMap.");
                    }
                } else if (checkPlayerPermission(sender,"hide.others")) {
                    for (int i = 1; i < args.length; i++) {
                        playerList.setVisible(args[i],false);
                        sender.sendMessage(args[i] + " is now hidden on MCCMap.");
                    }
                }
            } else if (c.equals("show")) {
                if (args.length == 1) {
                    if(player != null && checkPlayerPermission(sender,"show.self")) {
                        playerList.setVisible(player.getName(),true);
                        sender.sendMessage("You are now visible on MCCMap.");
                    }
                } else if (checkPlayerPermission(sender,"show.others")) {
                    for (int i = 1; i < args.length; i++) {
                        playerList.setVisible(args[i],true);
                        sender.sendMessage(args[i] + " is now visible on MCCMap.");
                    }
                }
            } else if (c.equals("fullrender") && checkPlayerPermission(sender,"fullrender")) {
                String map = null;
                if (args.length > 1) {
                    boolean resume = false;
                    for (int i = 1; i < args.length; i++) {
                        if (args[i].equalsIgnoreCase("resume")) {
                             resume = true;
                             continue;
                        }
                        int dot = args[i].indexOf(":");
                        DynmapWorld w;
                        String wname = args[i];
                        if(dot >= 0) {
                            wname = args[i].substring(0, dot);
                            map = args[i].substring(dot+1);
                        }
                        w = mapManager.getWorld(wname);
                        if(w != null) {
                            DynmapLocation loc;
                            if(w.center != null)
                                loc = w.center;
                            else
                                loc = w.getSpawnLocation();
                            mapManager.renderFullWorld(loc,sender, map, false, resume);
                        }
                        else
                            sender.sendMessage("World '" + wname + "' not defined/loaded");
                    }
                } else if (player != null) {
                    DynmapLocation loc = player.getLocation();
                    if(args.length > 1)
                        map = args[1];
                    if(loc != null)
                        mapManager.renderFullWorld(loc, sender, map, false, false);
                } else {
                    sender.sendMessage("World name is required");
                }
            } else if (c.equals("cancelrender") && checkPlayerPermission(sender,"cancelrender")) {
                if (args.length > 1) {
                    for (int i = 1; i < args.length; i++) {
                        DynmapWorld w = mapManager.getWorld(args[i]);
                        if(w != null)
                            mapManager.cancelRender(w.getName(), sender);
                        else
                            sender.sendMessage("World '" + args[i] + "' not defined/loaded");
                    }
                } else if (player != null) {
                    DynmapLocation loc = player.getLocation();
                    if(loc != null)
                        mapManager.cancelRender(loc.world, sender);
                } else {
                    sender.sendMessage("World name is required");
                }
            } else if (c.equals("purgequeue") && checkPlayerPermission(sender, "purgequeue")) {
                if (args.length > 1) {
                    for (int i = 1; i < args.length; i++) {
                        mapManager.purgeQueue(sender, args[i]);
                    }
                }
                else {
                    mapManager.purgeQueue(sender, null);
                }
            } else if (c.equals("purgemap") && checkPlayerPermission(sender,"purgemap")) {
                if (args.length > 2) {
                    mapManager.purgeMap(sender, args[1], args[2]);
                } else {
                    sender.sendMessage("World name and map name values are required");
                }
            } else if (c.equals("purgeworld") && checkPlayerPermission(sender,"purgeworld")) {
                if (args.length > 1) {
                    mapManager.purgeWorld(sender, args[1]);
                } else {
                    sender.sendMessage("World name is required");
                }
            } /*else if (c.equals("reload") && checkPlayerPermission(sender, "reload")) {
                sender.sendMessage("Reloading MCCMap...");
                getServer().reload();
                sender.sendMessage("MCCMap reloaded");
            } */else if (c.equals("stats") && checkPlayerPermission(sender, "stats")) {
                if(args.length == 1)
                    mapManager.printStats(sender, null);
                else
                    mapManager.printStats(sender, args[1]);
            } else if (c.equals("naturalrender") && checkPlayerPermission(sender, "fullrender")) {
                mapManager.naturalRenderControl(sender, args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status");
            } else if (c.equals("progress") && checkPlayerPermission(sender, "stats")) {
                mapManager.printProgress(sender, args.length > 1 ? args[1] : null);
            } else if (c.equals("triggerstats") && checkPlayerPermission(sender, "stats")) {
                mapManager.printTriggerStats(sender);
            } else if (c.equals("pause") && checkPlayerPermission(sender, "pause")) {
                if(args.length == 1) {
                }
                else if(args[1].equals("full")) {
                    setPauseFullRadiusRenders(true);
                    setPauseUpdateRenders(false);
                }
                else if(args[1].equals("update")) {
                    setPauseFullRadiusRenders(false);
                    setPauseUpdateRenders(true);
                }
                else if(args[1].equals("all")) {
                    setPauseFullRadiusRenders(true);
                    setPauseUpdateRenders(true);
                }
                else {
                    setPauseFullRadiusRenders(false);
                    setPauseUpdateRenders(false);
                }
                if(getPauseFullRadiusRenders())
                    sender.sendMessage("Full/Radius renders are PAUSED");
                else if (mapManager.getTPSFullRenderPause())
                    sender.sendMessage("Full/Radius renders are TPS PAUSED");
                else
                    sender.sendMessage("Full/Radius renders are ACTIVE");
                if(getPauseUpdateRenders())
                    sender.sendMessage("Update renders are PAUSED");
                else if (mapManager.getTPSUpdateRenderPause())
                    sender.sendMessage("Update renders are TPS PAUSED");
                else
                    sender.sendMessage("Update renders are ACTIVE");
                if (mapManager.getTPSZoomOutPause())
                    sender.sendMessage("Zoom out processing is TPS PAUSED");
                else
                    sender.sendMessage("Zoom out processing is ACTIVE");
            } else if (c.equals("resetstats") && checkPlayerPermission(sender, "resetstats")) {
                if(args.length == 1)
                    mapManager.resetStats(sender, null);
                else
                    mapManager.resetStats(sender, args[1]);
            } else if (c.equals("quiet") && checkPlayerPermission(sender, "quiet")) {
                mapManager.setJobsQuiet(sender);
            }
            else if(c.equals("help")) {
                printCommandHelp(sender, cmd, (args.length > 1)?args[1]:"");
            }
            else if(c.equals("dumpmemory") && checkPlayerPermission(sender, "dumpmemory")) {
                TexturePack.tallyMemory(sender);
            }
            else if(c.equals("version")) {
                sender.sendMessage("MCCMap " + this.getDynmapPluginVersion() + " — fork of Dynmap 3.9-SNAPSHOT (93b454e); core=" + this.getDynmapCoreVersion());
                sender.sendMessage("MCCMap development: Tommy10606. Original Dynmap: Mike Primm (mikeprimm) and the Dynmap contributors.");
            }
            else if(c.equals("tpscheck") && checkPlayerPermission(sender,"tpscheck")) {
                String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
                if (action.equals("off")) { mapManager.setTpsBypass(true); sender.sendMessage("TPS render checks disabled for 24 hours; storage and manual pauses remain active."); }
                else if (action.equals("on")) { mapManager.setTpsBypass(false); sender.sendMessage("TPS render checks enabled."); }
                else if (action.equals("status")) sender.sendMessage(mapManager.getTpsBypassUntil() > System.currentTimeMillis()
                    ? "TPS checks bypassed for " + ((mapManager.getTpsBypassUntil() - System.currentTimeMillis()) / 60000) + " more minutes."
                    : "TPS checks enabled; ignored when no players are online.");
                else sender.sendMessage("Usage: /mccmap tpscheck off|on|status");
            }
            else if (c.equals("url") && checkPlayerPermission(sender, "url")) {
                if (publicURL.length() > 0) {
                    sender.sendMessage("MCCMap URL for this server is: " + publicURL);
                }
                else {
                    sender.sendMessage("URL of MCCMap not configured");
                }
            }
            return true;
        }
        printCommandHelp(sender, cmd, (args.length > 0)?args[0]:"");

        return true;
    }

    /**
     * Returns a list of tab completion suggestions for the given sender, command and command arguments.
     *
     * @param sender - The sender of the tab completion, used for permission checks
     * @param cmd - The top level command being tab completed
     * @param args - Array of extra command arguments
     * @return List of tab completion suggestions
     */
    public List<String> getTabCompletions(DynmapCommandSender sender, String cmd, String[] args) {
        if (mapManager == null || args.length == 0) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return getSubcommandSuggestions(sender, cmd, args[0]);
        }

        if (cmd.equalsIgnoreCase("mccmapconfig")) {
            return dmapcmds.getTabCompletions(sender, args, this);
        }

        if (cmd.equalsIgnoreCase("mccmarker")) {
            return markerapi.getTabCompletions(sender, args, this);
        }

        if (cmd.equalsIgnoreCase("mccmapexport")) {
            return dynmapexpcmds.getTabCompletions(sender, args, this);
        }

        if (!cmd.equalsIgnoreCase("mccmap")) {
            return Collections.emptyList();
        }

        /* Re-parse args - handle double quotes */
        args = parseArgs(args, sender, true);

        if (args == null || args.length <= 1) {
            return Collections.emptyList();
        }

        String subcommand = args[0];
        DynmapPlayer player = null;
        if (sender instanceof DynmapPlayer) {
            player = (DynmapPlayer) sender;
        }

        if (subcommand.equals("radiusrender") && checkPlayerPermission(sender, "radiusrender")) {
            if(args.length == 2) { // /mccmap radiusrender *<world>* <x> <z> <radius> <map>
                return getWorldSuggestions(args[1]);
            } if(args.length == 3 && player != null) { // /mccmap radiusrender <radius> *<mapname>*
                try (Scanner sc = new Scanner(args[1])) {
                    if(sc.hasNextInt(10)) { //Only show map suggestions if a number was entered before
                        return getMapSuggestions(player.getLocation().world, args[2], false);
                    }
                }
            } else if(args.length == 6) { // /mccmap radiusrender <world> <x> <z> <radius> *<map>*
                return getMapSuggestions(args[1], args[5], false);
            }
        } else if (subcommand.equals("updaterender") && checkPlayerPermission(sender, "updaterender")) {
            if(args.length == 2) { // /mccmap updaterender *<world>* <x> <z> <map>/*<map>*
                List<String> suggestions = getWorldSuggestions(args[1]);

                if(player != null) {
                    suggestions.addAll(getMapSuggestions(player.getLocation().world, args[1], false));
                }

                return suggestions;
            } else if(args.length == 5) { // /mccmap updaterender <world> <x> <z> *<map>*
                return getMapSuggestions(args[1], args[4], false);
            }
        } else if (subcommand.equals("hide") && checkPlayerPermission(sender, "hide.others")) {
            if(args.length == 2) { // /mccmap hide *<player>*
                final String arg = args[1];
                return playerList.getVisiblePlayers().stream()
                        .map(DynmapPlayer::getName)
                        .filter(name -> name.startsWith(arg))
                        .collect(Collectors.toList());
            }
        } else if (subcommand.equals("show") && checkPlayerPermission(sender, "show.others")) {
            if(args.length == 2) { // /mccmap show *<player>*
                final String arg = args[1];
                return playerList.getHiddenPlayers().stream()
                        .map(DynmapPlayer::getName)
                        .filter(name -> name.startsWith(arg))
                        .collect(Collectors.toList());
            }
        } else if (subcommand.equals("fullrender") && checkPlayerPermission(sender, "fullrender")) {
            List<String> suggestions = getWorldSuggestions(args[args.length - 1]); //World suggestions
            suggestions.addAll(getMapSuggestions(args[args.length - 1])); //world:map suggestions

            //Remove suggestions present in other arguments
            for (String arg : args) {
                suggestions.remove(arg.contains(" ") ? "\"" + arg + "\"" : arg);
            }

            //Add resume if previous argument wasn't resume
            if ("resume".startsWith(args[args.length - 1])
                    && (args.length == 2 || !args[args.length - 2].equals("resume"))) {
                suggestions.add("resume");
            }

            return suggestions;
        } else if ((subcommand.equals("cancelrender") && checkPlayerPermission(sender, "cancelrender"))
                || (subcommand.equals("purgequeue") && checkPlayerPermission(sender, "purgequeue"))) {
            List<String> suggestions = getWorldSuggestions(args[args.length - 1]);
            suggestions.removeAll(Arrays.asList(args)); //Remove worlds present in other arguments

            return suggestions;
        } else if (subcommand.equals("purgemap") && checkPlayerPermission(sender, "purgemap")) {
            if (args.length == 2) { // /mccmap purgemap *<world>* <map>
                return getWorldSuggestions(args[1]);
            } else if (args.length == 3) { // /mccmap purgemap <world> *<map>*
                return getMapSuggestions(args[1], args[2], false);
            }
        } else if ((subcommand.equals("purgeworld") && checkPlayerPermission(sender, "purgeworld"))
                || (subcommand.equals("stats") && checkPlayerPermission(sender, "stats"))
                || (subcommand.equals("progress") && checkPlayerPermission(sender, "stats"))
                || (subcommand.equals("resetstats") && checkPlayerPermission(sender, "resetstats"))) {
            if (args.length == 2) {
                return getWorldSuggestions(args[1]);
            }
        } else if (subcommand.equals("naturalrender") && checkPlayerPermission(sender, "fullrender")) {
            if (args.length == 2) { String prefix = args[1]; return Arrays.asList("status", "on", "off", "resume").stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList()); }
        } else if (subcommand.equals("pause") && checkPlayerPermission(sender, "pause")) {
            List<String> suggestions = Arrays.asList("full", "update", "all", "none");

            if (args.length == 2) {
                final String arg = args[1];
                return suggestions.stream().filter(suggestion -> suggestion.startsWith(arg))
                        .collect(Collectors.toList());
            }
        } else if(subcommand.equals("help")) {
            if(args.length == 2) {
                return getSubcommandSuggestions(sender, "mccmap", args[1]);
            }
        }

        return Collections.emptyList();
    }

    public boolean checkPlayerPermission(DynmapCommandSender sender, String permission) {
        if (!(sender instanceof DynmapPlayer) || sender.isOp()) {
            return true;
        } else if (!sender.hasPrivilege(permission.toLowerCase())) {
            sender.sendMessage(noPermissionMsg);
            return false;
        }
        return true;
    }

    public boolean checkPermission(String player, String permission) {
        return getServer().checkPlayerPermission(player, permission);
    }

    public ConfigurationNode getWorldConfiguration(DynmapWorld world) {
        String wname = world.getName();
        ConfigurationNode finalConfiguration = new ConfigurationNode();
        finalConfiguration.put("name", wname);
        finalConfiguration.put("title", world.getTitle());

        ConfigurationNode worldConfiguration = getWorldConfigurationNode(wname);

        // Get the template.
        ConfigurationNode templateConfiguration = null;
        if (worldConfiguration != null) {
            String templateName = worldConfiguration.getString("template");
            if (templateName != null) {
                templateConfiguration = getTemplateConfigurationNode(templateName);
            }
        }

        // Template not found, using default template.
        if (templateConfiguration == null) {
            templateConfiguration = getDefaultTemplateConfigurationNode(world);
        }

        // Merge the finalConfiguration, templateConfiguration and worldConfiguration.
        finalConfiguration.extend(templateConfiguration);
        finalConfiguration.extend(worldConfiguration);
        if ((worldConfiguration == null || worldConfiguration.getString("name", null) == null) && !configuration.getBoolean("auto-enable-new-worlds", true)) finalConfiguration.put("enabled", false);

        Log.verboseinfo("Configuration of world " + world.getName());
        for(Map.Entry<String, Object> e : finalConfiguration.entrySet()) {
            Log.verboseinfo(e.getKey() + ": " + e.getValue());
        }
        /* Update world_config with final */
        List<Map<String,Object>> worlds = world_config.getMapList("worlds");
        if(worlds == null) {
            worlds = new ArrayList<Map<String,Object>>();
            world_config.put("worlds", worlds);
        }
        boolean did_upd = false;
        for(int idx = 0; idx < worlds.size(); idx++) {
            Map<String,Object> m = worlds.get(idx);
            if(wname.equals(m.get("name"))) {
                worlds.set(idx, finalConfiguration);
                did_upd = true;
                break;
            }
        }
        if(!did_upd)
            worlds.add(finalConfiguration);

        return finalConfiguration;
    }

    ConfigurationNode getDefaultTemplateConfigurationNode(DynmapWorld world) {
        String environmentName = world.getEnvironment();
        if(deftemplatesuffix.length() > 0) {
            if(!Arrays.asList(defaultTemplates).contains(deftemplatesuffix)) {
                Log.warning("Not using a default defined template, worlds might not be accessible.");
            }
            environmentName += "-" + deftemplatesuffix;
        }
        Log.verboseinfo("Using environment as template: " + environmentName);
        return getTemplateConfigurationNode(environmentName);
    }

    private ConfigurationNode getWorldConfigurationNode(String worldName) {
        worldName = DynmapWorld.normalizeWorldName(worldName);
        for(ConfigurationNode worldNode : world_config.getNodes("worlds")) {
            if (worldName.equals(worldNode.getString("name"))) {
                return worldNode;
            }
        }
        return new ConfigurationNode();
    }

    ConfigurationNode getTemplateConfigurationNode(String templateName) {
        ConfigurationNode templatesNode = configuration.getNode("templates");
        if (templatesNode != null) {
            return templatesNode.getNode(templateName);
        }
        return null;
    }


    public String getWebPath() {
        return webpath;
    }

    public static void setIgnoreChunkLoads(boolean ignore) {
        ignore_chunk_loads = ignore;
    }
    /* Uses resource to create default file, if file does not yet exist */
    public boolean createDefaultFileFromResource(String resourcename, File deffile) {
        if(deffile.canRead())
            return true;
        Debug.debug(deffile.getPath() + " not found - creating default");
        InputStream in = getClass().getResourceAsStream(resourcename);
        if(in == null) {
            Log.severe("Unable to find default resource - " + resourcename);
            return false;
        }
        else {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(deffile);
                byte[] buf = new byte[512];
                int len;
                while((len = in.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            } catch (IOException iox) {
                Log.severe("ERROR creatomg default for " + deffile.getPath());
                return false;
            } finally {
                if(fos != null)
                    try { fos.close(); } catch (IOException iox) {}
                if(in != null)
                    try { in.close(); } catch (IOException iox) {}
            }
            return true;
        }
    }

    /*
     * Update if new version
     */
    public boolean updateVersionUsingDefaultResource(String resourcename, File deffile) {
        InputStream in = getClass().getResourceAsStream(resourcename);
        if(in == null) {
            Log.severe("Unable to find resource - " + resourcename);
            return false;
        }
        if(deffile.canRead() == false) {    /* Doesn't exist? */
            return createDefaultFileFromResource(resourcename, deffile);
        }
        /* Load default from resource */
        ConfigurationNode def_fc = new ConfigurationNode(in);
        /* Load existing from file */
        ConfigurationNode fc = new ConfigurationNode(deffile);
        fc.load();
        if (fc.getString("version", "").equals(def_fc.getString("version", ""))) {
            return true;
        }
        deffile.delete();
        return createDefaultFileFromResource(resourcename, deffile);
    }
    /*
     * Add in any missing sections to existing file, using resource
     */
    public boolean updateUsingDefaultResource(String resourcename, File deffile, String basenode) {
        InputStream in = getClass().getResourceAsStream(resourcename);
        if(in == null) {
            Log.severe("Unable to find resource - " + resourcename);
            return false;
        }
        if(deffile.canRead() == false) {    /* Doesn't exist? */
            return createDefaultFileFromResource(resourcename, deffile);
        }
        /* Load default from resource */
        ConfigurationNode def_fc = new ConfigurationNode(in);
        /* Load existing from file */
        ConfigurationNode fc = new ConfigurationNode(deffile);
        fc.load();
        /* Now, get the list associated with the base node default */
        List<Map<String,Object>> existing = fc.getMapList(basenode);
        Set<String> existing_names = new HashSet<String>();
        /* Make map, indexed by 'name' in map */
        if(existing != null) {
            for(Map<String,Object> m : existing) {
                Object name = m.get("name");
                if(name instanceof String)
                    existing_names.add((String)name);
            }
        }
        boolean did_update = false;
        /* Now, loop through defaults, and see if any are missing */
        List<Map<String,Object>> defmaps = def_fc.getMapList(basenode);
        if(defmaps != null) {
            for(Map<String,Object> m : defmaps) {
                Object name = m.get("name");
                if(name instanceof String) {
                    /* If not an existing one, need to add it */
                    if(existing_names.contains((String)name) == false) {
                        existing.add(m);
                        did_update = true;
                    }
                }
            }
        }
        /* If we did update, save existing */
        if(did_update) {
            fc.put(basenode, existing);
            fc.save(deffile);
            Log.info("Updated file " + deffile.getPath());
        }
        return true;
    }


    /**
     * ** This is the public API for other plugins to use for accessing the Marker API **
     * This method can return null if the 'markers' component has not been configured -
     * a warning message will be issued to the server.log in this event.
     *
     * @return MarkerAPI, or null if not configured
     */
    public MarkerAPI getMarkerAPI() {
        if(markerapi == null) {
            Log.warning("Marker API has been requested, but is not enabled.  Uncomment or add 'markers' component to configuration.txt.");
        }
        return markerapi;
    }
    public boolean markerAPIInitialized() {
        return (markerapi != null);
    }

    /**
     * Register markers API - used by component to supply marker API to plugin
     * @param api - marker API
     */
    public void registerMarkerAPI(MarkerAPIImpl api) {
        markerapi = api;
    }
    /*
     * Pause full/radius render processing
     * @param dopause - true to pause, false to unpause
     */
    public void setPauseFullRadiusRenders(boolean dopause) {
        manualFullPause = dopause;
        applyRenderingPauses(getServer().getOnlinePlayers().length);
    }
    /*
     * Test if full renders are paused
     * @return true if paused
     */
    public boolean getPauseFullRadiusRenders() {
        return mapManager.getPauseFullRadiusRenders();
    }
    /*
     * Pause update render processing
     * @param dopause - true to pause, false to unpause
     */
    public void setPauseUpdateRenders(boolean dopause) {
        manualUpdatePause = dopause;
        applyRenderingPauses(getServer().getOnlinePlayers().length);
    }
    /*
     * Test if update renders are paused
     * @return true if paused
     */
    public boolean getPauseUpdateRenders() {
        return mapManager.getPauseUpdateRenders();
    }






    public void setPlayerVisiblity(String player, boolean is_visible) {
        playerList.setVisible(player, is_visible);
    }

    public boolean getPlayerVisbility(String player) {
        return playerList.isVisiblePlayer(player);
    }

    public void assertPlayerInvisibility(String player, boolean is_invisible, String plugin_id) {
        playerList.assertInvisiblilty(player, is_invisible, plugin_id);
    }

    public void assertPlayerVisibility(String player, boolean is_visible, String plugin_id) {
        playerList.assertVisiblilty(player, is_visible, plugin_id);
    }





    public String getDynmapCoreVersion() {
        return version;
    }

    public String getDynmapPluginVersion() {
        return plugin_ver;
    }

    public int triggerRenderOfBlock(String wid, int x, int y, int z) {
        if(mapManager != null)
            mapManager.touch(wid, x, y, z, "api");
        return 0;
    }

    public int triggerRenderOfVolume(String wid, int minx, int miny, int minz, int maxx, int maxy, int maxz) {
        if(mapManager != null) {
            if((minx == maxx) && (miny == maxy) && (minz == maxz))
                mapManager.touch(wid, minx, miny, minz, "api");
            else
                mapManager.touchVolume(wid, minx, miny, minz, maxx, maxy, maxz, "api");
        }
        return 0;
    }

    public boolean isTrigger(String s) {
        return enabledTriggers.contains(s);
    }

    public DynmapWorld getWorld(String wid) {
        if(mapManager != null)
            return mapManager.getWorld(wid);
        return null;
    }

    /* Called by plugin when world loaded */
    public boolean processWorldLoad(DynmapWorld w) {
        boolean activated = true;
        if(mapManager.getWorld(w.getName()) == null) {
            updateConfigHashcode();
            activated = mapManager.activateWorld(w);
        }
        else {
            mapManager.loadWorld(w);
        }
        return activated;
    }

    /* Called by plugin when world unloaded */
    public boolean processWorldUnload(DynmapWorld w) {
        boolean done = false;
        if(mapManager.getWorld(w.getName()) != null) {
           mapManager.unloadWorld(w);
           done = true;
        }
        return done;
    }

    /* Enable/disable world */
    public boolean setWorldEnable(String wname, boolean isenab) {
        wname = DynmapWorld.normalizeWorldName(wname);
        List<Map<String,Object>> worlds = world_config.getMapList("worlds");
        for(Map<String,Object> m : worlds) {
            String wn = (String)m.get("name");
            if((wn != null) && (wn.equals(wname))) {
                m.put("enabled", isenab);
                return true;
            }
        }
        /* Keep an explicit choice even for a world that has not been configured yet. */
        {
            Map<String,Object> newworld = new LinkedHashMap<String,Object>();
            newworld.put("name", wname);
            newworld.put("enabled", isenab);
            worlds.add(newworld);
            world_config.put("worlds", worlds);
        }
        return true;
    }
    public boolean setWorldZoomOut(String wname, int xzoomout) {
        wname = DynmapWorld.normalizeWorldName(wname);
        List<Map<String,Object>> worlds = world_config.getMapList("worlds");
        for(Map<String,Object> m : worlds) {
            String wn = (String)m.get("name");
            if((wn != null) && (wn.equals(wname))) {
                m.put("extrazoomout", xzoomout);
                return true;
            }
        }
        return false;
    }
    public boolean setWorldTileUpdateDelay(String wname, int tud) {
        wname = DynmapWorld.normalizeWorldName(wname);
        List<Map<String,Object>> worlds = world_config.getMapList("worlds");
        for(Map<String,Object> m : worlds) {
            String wn = (String)m.get("name");
            if((wn != null) && (wn.equals(wname))) {
                if(tud > 0)
                    m.put("tileupdatedelay", tud);
                else
                    m.remove("tileupdatedelay");
                return true;
            }
        }
        return false;
    }
    public boolean setWorldCenter(String wname, DynmapLocation loc) {
        wname = DynmapWorld.normalizeWorldName(wname);
        List<Map<String,Object>> worlds = world_config.getMapList("worlds");
        for(Map<String,Object> m : worlds) {
            String wn = (String)m.get("name");
            if((wn != null) && (wn.equals(wname))) {
                if(loc != null) {
                    Map<String,Object> c = new LinkedHashMap<String,Object>();
                    c.put("x", loc.x);
                    c.put("y", loc.y);
                    c.put("z", loc.z);
                    m.put("center", c);
                }
                else {
                    m.remove("center");
                }
                return true;
            }
        }
        return false;
    }
    public boolean setWorldOrder(String wname, int order) {
        wname = DynmapWorld.normalizeWorldName(wname);
        List<Map<String,Object>> worlds = world_config.getMapList("worlds");
        ArrayList<Map<String,Object>> newworlds = new ArrayList<Map<String,Object>>(worlds);

        Map<String,Object> w = null;
        for(Map<String,Object> m : worlds) {
            String wn = (String)m.get("name");
            if((wn != null) && (wn.equals(wname))) {
                w = m;
                newworlds.remove(m);   /* Remove from list */
                break;
            }
        }
        if(w != null) { /* If found it, add back at right pount */
            if(order >= newworlds.size()) {    /* At end? */
                newworlds.add(w);
            }
            else {
                newworlds.add(order, w);
            }
            world_config.put("worlds", newworlds);
            return true;
        }
        return false;
    }

    public boolean updateWorldConfig(DynmapWorld w) {
        ConfigurationNode cn = w.saveConfiguration();
        return replaceWorldConfig(w.getName(), cn);
    }

    public boolean replaceWorldConfig(String wname, ConfigurationNode cn) {
        wname = DynmapWorld.normalizeWorldName(wname);
        List<Map<String,Object>> worlds = world_config.getMapList("worlds");
        if(worlds == null) {
            worlds = new ArrayList<Map<String,Object>>();
            world_config.put("worlds", worlds);
        }
        for(int i = 0; i < worlds.size(); i++) {
            Map<String,Object> m = worlds.get(i);
            String wn = (String)m.get("name");
            if((wn != null) && (wn.equals(wname))) {
                worlds.set(i, cn.entries);  /* Replace */
                return true;
            }
        }
        return false;
    }

    public boolean saveWorldConfig() {
        boolean rslt = world_config.save();    /* Save world config */
        updateConfigHashcode(); /* Update config hashcode */
        return rslt;
    }

    /* Refresh world config */
    public boolean refreshWorld(String wname) {
        wname = DynmapWorld.normalizeWorldName(wname);
        saveWorldConfig();
        if(mapManager != null) {
            mapManager.deactivateWorld(wname);  /* Clean it up */
            DynmapWorld w = getServer().getWorldByName(wname);  /* Get new instance */
            if(w != null)
                mapManager.activateWorld(w);    /* And activate it again */
        }
        return true;
    }

    /* Load core version */
    private void loadVersion() {
        InputStream in = getClass().getResourceAsStream("/core.yml");
        if(in == null)
            return;
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        Map<String,Object> val = (Map<String,Object>)yaml.load(in);
        if(val != null)
            version = (String)val.get("version");
    }

    public int getSnapShotCacheSize() { return snapshotcachesize; }

    public boolean useSoftRefInSnapShotCache() { return snapshotsoftref; }

    public String getDefImageFormat() { return def_image_format; }

    public String scanAndReplaceLog4JMacro(String msg) {
        int nestcnt = 0;
        int off = 0;
        int firsthit = -1;
        boolean done = false;
        while (!done) {
            int idx = msg.indexOf("${", off);	// Look for next ${
            if (idx >= 0) {	// Hit
                if (nestcnt == 0) firsthit = idx;	// Record start of hit
                nestcnt++;
                off = idx + 2;
            }
            else {
                idx = msg.indexOf("}", off);	// Next }
                if (idx >= 0) {
                    if (nestcnt > 0) {
                        nestcnt--;
                        if ((nestcnt == 0) && (firsthit >= 0)) {	// If back to zero, time to strip it
                            String newmsg = msg.substring(0, firsthit) + hackAttemptSub + msg.substring(idx+1);
                            msg = newmsg;	// Switch to new version, and restart
                            off = 0;
                            firsthit = -1;	// And restart scan
                        }
                    }
                    off = idx + 1;
                }
                else {	// At end without a close
                    if (firsthit >= 0) {	// Open strip?
                        String newmsg = msg.substring(0, firsthit) + hackAttemptSub;	// Replace rest
                        msg = newmsg;	// Switch to new version, and restart
                    }
                    done = true;
                }
            }
        }
        return msg;
    }

    /**
     * Enable or disable forwarding of player chat to the website.
     * @param disable - if true, suppress internal chat-to-web messages
     */
    public boolean setDisableChatToWebProcessing(boolean disable) {
        boolean prev = disable_chat_to_web;
        disable_chat_to_web = disable;
        return prev;
    }



    /**
     * Test if player position/information is protected on map view
     * @return true if protected, false if visible to guests and all players
     */
    public boolean testIfPlayerInfoProtected() {
        return player_info_protected;
    }

    public int getMaxPlayers() {
        return server.getMaxPlayers();
    }
    public int getCurrentPlayers() {
        return server.getCurrentPlayers();
    }

    public String getDynmapPluginPlatform() {
        return platform;
    }
    public String getDynmapPluginPlatformVersion() {
        return platformVersion;
    }

    public static boolean deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().equals(".") || f.getName().equals("..")) continue;
                if (f.isDirectory()) {
                    deleteDirectory(f);
                }
                else if(f.isFile()) {
                    f.delete();
                }
            }
        }
        return dir.delete();
    }

    /** Remove obsolete bundled web entry points when upgrading an existing install. */
    private void removeObsoleteWebFiles() {
        File root = getFile(getWebPath());
        List<String> removed = new ArrayList<>();
        removed.add("login.html");
        removed.add("js/jquery-3.5.1.js");
        removed.add("js/jquery.json.js");
        removed.add("js/dynmaputils.js");
        removed.add("css/dynmap_style.css");
        removed.add("standalone/dynmap_config.json");
        removed.add("up.php");
        removed.add("gettiles.php");
        for (String prefix : new String[] { "MySQL_", "PostgreSQL_", "SQLite_", "MSSQL_" }) {
            for (String suffix : new String[] { "funcs.php", "config.php", "configuration.php", "markers.php", "tiles.php", "update.php" }) removed.add("standalone/" + prefix + suffix);
        }
        removed.add("standalone/storage_config.php");
        removed.add("standalone/dynmap_config.php");
        removed.add("up.aspx");
        removed.add("js/leaflet-src.js");
        removed.add("js/leaflet-src.js.map");
        removed.add("js/leaflet.js.map");
        for (String name : new String[] { "webauth.txt", "ids-by-ip.txt" }) {
            File file = new File(getDataFolder(), name);
            if (file.isFile() && !file.delete()) Log.warning("Could not remove obsolete account data: " + name);
        }
        for (String prefix : new String[] { "", "MySQL_", "PostgreSQL_" }) {
            for (String name : new String[] { "login.php", "getlogin.php", "register.php", "sendmessage.php" }) {
                removed.add("standalone/" + prefix + name);
            }
        }
        for (String name : new String[] { "dynmap_login.php", "dynmap_access.php", "dynmap_reg.php", "dynmap_webchat.json",
                "configuration.php", "update.php", "tiles.php", "markers.php", "MySQL_access.php", "PostgreSQL_access.php" }) {
            removed.add("standalone/" + name);
        }
        for (String relative : removed) {
            File file = new File(root, relative);
            if (file.isFile() && !file.delete()) Log.warning("Could not remove obsolete web file: " + relative);
        }
    }

    private void updateExtractedFiles() {
        if(jarfile == null) return;
        File df = this.getDataFolder();
        if(df.exists() == false) df.mkdirs();
        File ver = new File(df, "version.txt");
        File wpath = this.getFile(this.getWebPath());
        File webver = new File(wpath, "version.txt");
        String prevver = "1.6";
        String prevwebver = "1.6";
        if (ver.exists()) {
            Reader ir = null;
            try {
                ir = new FileReader(ver);
                prevver = "";
                int c;
                while((c = ir.read()) >= 0) {
                    prevver += (char)c;
                }
            } catch (IOException iox) {
            } finally {
                if(ir != null) {
                    try { ir.close(); } catch (IOException iox) {}
                }
            }
        }
        if (webver.exists()) {
            Reader ir = null;
            try {
                ir = new FileReader(webver);
                prevwebver = "";
                int c;
                while((c = ir.read()) >= 0) {
                    prevwebver += (char)c;
                }
            } catch (IOException iox) {
            } finally {
                if(ir != null) {
                    try { ir.close(); } catch (IOException iox) {}
                }
            }
        }
        String buildFingerprint = null;
        try { buildFingerprint = AssetBuildStamp.fingerprint(jarfile.toPath()); }
        catch (IOException failure) { Log.warning("Could not fingerprint bundled assets: " + failure.getMessage()); }
        String curver = this.getDynmapCoreVersion();
        /* If matched, we're good */
        if (prevver.equals(curver) && prevwebver.equals(curver) && AssetBuildStamp.matches(df.toPath(), buildFingerprint) && AssetBuildStamp.matches(wpath.toPath(), buildFingerprint) && (!curver.endsWith(("-Dev")))) {
            return;
        }
        // If doing update and web path update is disabled, send warning
        if (!this.updatewebpathfiles) {
            Log.warning("Update of web interface is disabled, and update is available - UI may not function without updates");
        }
        /* Get deleted file list */
        InputStream in = getClass().getResourceAsStream("/deleted.txt");
        if (in != null) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(in));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.length() == 0) continue;
                    if (line.startsWith("#")) continue;
                    File newfile = new File(df, line);
                    newfile.delete();
                }
            } catch (IOException iox) {
                Log.warning("Exception while processing deleted files - " + iox.getMessage());
            } finally {
                try { in.close(); } catch (IOException x) {}
            }
        }
        /* Open JAR as ZIP */
        ZipFile zf = null;
        FileOutputStream fos = null;
        InputStream ins = null;
        byte[] buf = new byte[2048];
        String n = null;
        boolean extractedSuccessfully = true;
        try {
            File f;
            zf = new ZipFile(jarfile);
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                n = ze.getName();
                if (!n.startsWith("extracted/")) {
                    continue;
                }
                n = n.substring("extracted/".length());
                // If file is going to web path, redirect it to the configured web
                if (n.startsWith("web/")) {
                    // Don't update unless we are allowed to
                    if (!updatewebpathfiles) {
                        continue;
                    }
                    f = new File(wpath, n.substring("web/".length()));
                }
                else {
                    f = new File(df, n);
                }
                if(ze.isDirectory()) {
                    f.mkdirs();
                }
                else {
                    try {
                        f.getParentFile().mkdirs();
                        fos = new FileOutputStream(f);
                        ins = zf.getInputStream(ze);
                        int len;
                        while ((len = ins.read(buf)) >= 0) {
                            fos.write(buf,  0,  len);
                        }
                    } catch(IOException io) {
                        extractedSuccessfully = false;
                        Log.severe("Error updating file - " + f.getPath(), io);
                    } finally {
                        if (ins != null) {
                            ins.close();
                            ins = null;
                        }
                        if (fos != null) {
                            fos.close();
                            fos = null;
                        }
                    }
                }
            }
        } catch (IOException iox) {
            extractedSuccessfully = false;
            Log.severe("Error extracting file - " + n);
        } finally {
            if (ins != null) {
                try { ins.close(); } catch (IOException iox) {}
                ins = null;
            }
            if (fos != null) {
                try { fos.close(); } catch (IOException iox) {}
                fos = null;
            }
            if (zf != null) {
                try { zf.close(); } catch (IOException iox) {}
                zf = null;
            }
        }

        /* Finally, write new version cookie to both data folder and web folder*/
        Writer out = null;
        try {
            out = new FileWriter(ver);
            out.write(this.getDynmapCoreVersion());
        } catch (IOException iox) {
        } finally {
            if(out != null) {
                try { out.close(); } catch (IOException iox) {}
            }
        }
        if (this.updatewebpathfiles) {
            try {
                out = new FileWriter(webver);
                out.write(this.getDynmapCoreVersion());
            } catch (IOException iox) {
            } finally {
                if(out != null) {
                    try { out.close(); } catch (IOException iox) {}
                }
            }
            Log.info("Extracted files upgraded");
        }
        else {
            Log.info("Extracted files upgraded (excluding webpath files)");
        }
        if (extractedSuccessfully && buildFingerprint != null) {
            try {
                AssetBuildStamp.save(df.toPath(), buildFingerprint);
                if (updatewebpathfiles) AssetBuildStamp.save(wpath.toPath(), buildFingerprint);
            } catch (IOException failure) { Log.warning("Could not save asset build fingerprint: " + failure.getMessage()); }
        }

    }
    // Server thread tick : nominally, once per 20 Hz tick
    public void serverTick(double tps) {
        if (this.mapManager != null) {
            this.mapManager.updateTPS(tps);
        }
    }

    public int getMaxTickUseMS() {
        return perTickLimit;
    }
    public boolean dumpMissingBlocks() {
        return dumpMissing;
    }
    // Notice that server has finished starting (needed for forge, which starts dynmap before full server is running)
    public void serverStarted() {
        events.<Object>trigger("server-started", null);
    }
    // Normalize ID (strip out submods)
    public String getNormalizedModID(String mod) {
        int idx = mod.indexOf('|');
        if (idx > 0) mod = mod.substring(0, idx);
        return mod;
    }
    // Add mod block IDs to value map
    public void addModBlockItemIDs(String mod, Map<String, Integer> modvals) {
        mod = getNormalizedModID(mod);
        for (String k : blockmap.keySet()) {
            String[] ks = k.split(":", 2);
            if (ks.length != 2) continue;
            int id = blockmap.get(k);
            ks[0] = getNormalizedModID(ks[0]);
            if (mod.equals(ks[0])) {
                modvals.put("%" + ks[1], id);
            }
        }
        for (String k : itemmap.keySet()) {
            String[] ks = k.split(":", 2);
            if (ks.length != 2) continue;
            int id = itemmap.get(k);
            ks[0] = getNormalizedModID(ks[0]);
            if (mod.equals(ks[0])) {
                modvals.put("&" + ks[1], id);
            }
        }
    }

    @Override
    public void processSignChange(String material, String world, int x, int y, int z,
            String[] lines, String playerid) {
        DynmapPlayer dp = server.getPlayer(playerid);
        listenerManager.processSignChangeEvent(EventType.SIGN_CHANGE, material, world, x, y, z, lines, dp);
    }

    public MapStorage getDefaultMapStorage() {
        return defaultStorage;
    }
}

