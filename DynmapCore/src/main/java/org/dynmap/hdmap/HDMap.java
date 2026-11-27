// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.hdmap;

import java.util.ArrayList;
import java.util.List;

import org.dynmap.Client;
import org.dynmap.ConfigurationNode;
import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import static org.dynmap.JSONUtils.a;
import static org.dynmap.JSONUtils.s;
import org.dynmap.Log;
import org.dynmap.MapManager;
import org.dynmap.MapTile;
import org.dynmap.MapType;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.utils.TileFlags;
import org.json.simple.JSONObject;

public class HDMap extends MapType {

    private String name;
    private String prefix;
    private String rasterPrefix;
    private boolean versionedTiles;
    private int legacyScaleShift = -1;
    private int legacyPngScaleShift = -1;
    private java.util.List<TilePreviewSource> previewSources;

    private HDPerspective perspective;
    private HDShader shader;
    private HDLighting lighting;
//    private ConfigurationNode configuration;
    private int mapzoomout;
    private String imgfmtstring;
    private MapType.ImageFormat imgformat;
    private int bgcolornight;
    private int bgcolorday;
    private int tilescale;
    private String title;
    private String icon;
    private String bg_cfg;
    private String bg_day_cfg;
    private String bg_night_cfg;
    private String append_to_world;
    private int mapzoomin;
    private int boostzoom;
    public DynmapCore core;

    public static final String IMGFORMAT_PNG = "png";
    public static final String IMGFORMAT_JPG = "jpg";


    public HDMap(DynmapCore core, ConfigurationNode configuration) {
        this(core,configuration,core.configuration,true);
    }
    private HDMap(DynmapCore core,ConfigurationNode configuration,ConfigurationNode quality,boolean buildSources) {
        this.core = core;
        ConfigurationNode sourceConfiguration = new ConfigurationNode(new java.util.LinkedHashMap<>(configuration.entries));
        name = configuration.getString("name", null);
        if(name == null) {
            Log.severe("HDMap missing required attribute 'name' - disabled");
            return;
        }
        versionedTiles = !configuration.getBoolean("readonly", false) || configuration.getInteger("raster-layout-version", 0) >= 1;
        HDPerspective previousPerspective = MapManager.mapman.hdmapman.perspectives.get(configuration.getString("perspective", "default"));
        boolean defaultFormat = "default".equals(configuration.getString("image-format", "default"));
        String previousFormat = configuration.getString("image-format", "default");
        if(previousFormat.equals("default")) previousFormat = core.getDefImageFormat();
        RenderQualitySettings.apply(quality, configuration, MapManager.mapman.hdmapman.perspectives.keySet());
        String perspectiveid = configuration.getString("perspective", "default");
        perspective = MapManager.mapman.hdmapman.perspectives.get(perspectiveid);
        if(perspective == null) {
            /* Try to use default */
            perspective = MapManager.mapman.hdmapman.perspectives.get("default");
            if(perspective == null) {
                Log.severe("HDMap '"+name+"' loaded invalid perspective '" + perspectiveid + "' - map disabled");
                name = null;
                return;
            }
            else {
                Log.severe("HDMap '"+name+"' loaded invalid perspective '" + perspectiveid + "' - using 'default' perspective");
            }
        }
        String shaderid = configuration.getString("shader", "default");
        shader = MapManager.mapman.hdmapman.shaders.get(shaderid);
        if(shader == null) {
            shader = MapManager.mapman.hdmapman.shaders.get("default");
            if(shader == null) {
                Log.severe("HDMap '"+name+"' loading invalid shader '" + shaderid + "' - map disabled");
                name = null;
                return;
            }
            else {
                Log.severe("HDMap '"+name+"' loading invalid shader '" + shaderid + "' - using 'default' shader");
            }
        }
        String lightingid = configuration.getString("lighting", "default");
        lighting = MapManager.mapman.hdmapman.lightings.get(lightingid);
        if(lighting == null) {
            lighting = MapManager.mapman.hdmapman.lightings.get("default");
            if(lighting == null) {
                Log.severe("HDMap '"+name+"' loading invalid lighting '" + lighting + "' - map disabled");
                name = null;
                return;
            }
            else {
                Log.severe("HDMap '"+name+"' loading invalid lighting '" + lighting + "' - using 'default' lighting");
            }
        }
        prefix = configuration.getString("prefix", name);

        /* Compute extra zoom outs needed for this map */
        double scale = perspective.getScale();
        mapzoomout = 0;
        while(scale >= 1.0) {
            mapzoomout++;
            scale = scale / 2.0;
        }
        imgfmtstring = configuration.getString("image-format", "default");
        if(imgfmtstring.equals("default")) {
            imgformat = ImageFormat.fromID(core.getDefImageFormat());
        }
        else {
            imgformat = ImageFormat.fromID(imgfmtstring);
        }
        if(imgformat == null) {
            Log.severe("HDMap '"+name+"' set invalid image-format: " + imgfmtstring);
            imgformat = ImageFormat.FORMAT_PNG;
        }
        /* Get color info */
        String c = configuration.getString("background");
        if(c != null) {
            bgcolorday = bgcolornight = parseColor(c);
        }
        c = configuration.getString("backgroundday");
        if(c != null) {
            bgcolorday = parseColor(c);
        }
        c = configuration.getString("backgroundnight");
        if(c != null) {
            bgcolornight = parseColor(c);
        }
        if(imgformat != ImageFormat.FORMAT_PNG) {   /* If JPG, set background color opacity */
            bgcolorday |= 0xFF000000;
            bgcolornight |= 0xFF000000;
        }
        this.title = configuration.getString("title", name);
        this.icon = configuration.getString("icon");
        this.bg_cfg = configuration.getString("background");
        this.bg_day_cfg = configuration.getString("backgroundday");
        this.bg_night_cfg = configuration.getString("backgroundnight");
        this.mapzoomin = configuration.getInteger("mapzoomin", 2);
        this.mapzoomout = configuration.getInteger("mapzoomout", this.mapzoomout);
        this.boostzoom = configuration.getInteger("boostzoom", 0);
        this.tilescale = configuration.getInteger("tilescale", core.getMapManager().getDefaultTileScale());	// 0 = 128, 1 = 256, ...
        if (this.tilescale <= 0) this.tilescale = 0;
        if (this.tilescale >= 4) this.tilescale = 4;	// Limit to 2k x 2k
        if(this.boostzoom < 0) this.boostzoom = 0;
        if(this.boostzoom > 3) this.boostzoom = 3;
        // Map zoom in must be at least as big as boost zoom
        if (this.boostzoom > this.mapzoomin) {
            this.mapzoomin = this.boostzoom;
        }
        if (tilescale == 0 && imgformat.getEncoding() == ImageEncoding.PNG) {
            if (configuration.containsKey("legacy-preview-layout")) {
                // A saved source mapping takes precedence after the upgraded perspective
                // has been saved. Never reinterpret it as a new zero-shift migration.
                if (TileLayout.namespace(prefix,perspective,tilescale,boostzoom,imgformat).equals(configuration.getString("legacy-preview-layout", ""))) {
                    int saved = configuration.getInteger("legacy-preview-scale-shift", -1);
                    if(saved >= 0 && saved <= 4) legacyScaleShift = saved;
                    // Earlier hints described the configured grid, not the effective PNG grid.
                    int png = configuration.getInteger("legacy-preview-png-scale-shift", TileLayout.compatibleShift(perspective, perspective));
                    if(png >= 0 && png <= 4) legacyPngScaleShift = png;
                }
            } else if (previousFormat.startsWith("jpg") || previousFormat.equals("png")) {
                legacyScaleShift = TileLayout.compatibleShift(previousPerspective, perspective);
                // The default-format quality upgrade already produced high-detail PNGs
                // in the unversioned directory. JPEGs still use the original grid.
                legacyPngScaleShift = (defaultFormat || previousFormat.startsWith("jpg")) && legacyScaleShift >= 0
                    ? TileLayout.compatibleShift(perspective, perspective) : legacyScaleShift;
                int png = configuration.getInteger("legacy-preview-png-scale-shift", legacyPngScaleShift);
                if(png >= -1 && png <= 4) legacyPngScaleShift = png;
            }
        }
        if(buildSources && tilescale == 0 && imgformat.getEncoding() == ImageEncoding.PNG && !configuration.getBoolean("readonly",false)) {
            if(getPrefix().equals(configuration.getString("raster-preview-layout",""))) {
                var saved=configuration.getNodes("raster-preview-sources");
                if(saved!=null && saved.size()<=8) {
                    var sources=new java.util.ArrayList<TilePreviewSource>();
                    for(var item:saved) {
                        String source=item.getString("prefix","");int shift=item.getInteger("scale-shift",100);
                        String encoding=item.getString("encoding","");
                        if(!source.matches("[A-Za-z0-9_.-]{1,128}") || source.equals(".") || source.equals("..") || source.equals(getPrefix()) || shift < -4 || shift > 4)continue;
                        if(encoding.equals("PNG")||encoding.equals("JPG"))sources.add(new TilePreviewSource(source,shift,ImageEncoding.valueOf(encoding)));
                    }
                    previewSources=java.util.List.copyOf(sources);
                }
            }
            if(previewSources==null && quality!=null && "balanced".equalsIgnoreCase(quality.getString("render-quality",""))) {
                var oldQuality=new ConfigurationNode(new java.util.LinkedHashMap<>(quality.entries));oldQuality.put("render-quality","high");
                var old=new HDMap(core,sourceConfiguration,oldQuality,false);
                int delta=TileLayout.signedCompatibleShift(old.perspective,perspective);
                if(delta>=-4 && delta<=4 && !old.getPrefix().equals(getPrefix())) {
                    var sources=new java.util.ArrayList<TilePreviewSource>();
                    for(var source:old.getTilePreviewSources()) {
                        int shift=source.scaleShift()+delta;
                        if(shift>=-4 && shift<=4)sources.add(new TilePreviewSource(source.prefix(),shift,source.encoding()));
                    }
                    sources.add(new TilePreviewSource(old.getPrefix(),delta,old.getImageFormat().getEncoding()));
                    previewSources=java.util.List.copyOf(sources);
                }
            }
        }
        this.append_to_world = configuration.getString("append_to_world", "");
        setTileUpdateDelay(configuration.getInteger("tileupdatedelay", -1));
        setReadOnly(configuration.getBoolean("readonly", false));
    }

    @Override
    public ConfigurationNode saveConfiguration() {
        ConfigurationNode cn = super.saveConfiguration();
        cn.put("title", title);
        if(icon != null)
            cn.put("icon", icon);
        cn.put("prefix", prefix);
        if(versionedTiles) cn.put("raster-layout-version", 1);
        if(legacyScaleShift >= 0 || legacyPngScaleShift >= 0) {cn.put("legacy-preview-layout", getPrefix());cn.put("legacy-preview-scale-shift", legacyScaleShift);cn.put("legacy-preview-png-scale-shift", legacyPngScaleShift);}
        if(previewSources!=null) {
            cn.put("raster-preview-layout",getPrefix());
            cn.put("raster-preview-sources",previewSources.stream().map(source -> java.util.Map.of("prefix",source.prefix(),"scale-shift",source.scaleShift(),"encoding",source.encoding().name())).toList());
        }
        if(perspective != null)
            cn.put("perspective", perspective.getName());
        if(shader != null)
            cn.put("shader", shader.getName());
        if(lighting != null)
            cn.put("lighting", lighting.getName());
        cn.put("image-format", imgfmtstring);
        cn.put("mapzoomin", mapzoomin);
        cn.put("mapzoomout", mapzoomout);
        cn.put("boostzoom", boostzoom);
        cn.put("tilescale", tilescale);
        if(bg_cfg != null)
            cn.put("background", bg_cfg);
        if(bg_day_cfg != null)
            cn.put("backgroundday", bg_day_cfg);
        if(bg_night_cfg != null)
            cn.put("backgroundnight", bg_night_cfg);
        cn.put("append_to_world", append_to_world);
        cn.put("readonly", isReadOnly());
        if(this.tileupdatedelay > 0) {
            cn.put("tileupdatedelay", this.tileupdatedelay);
        }
        return cn;
    }

    public final HDShader getShader() { return shader; }
    public final HDPerspective getPerspective() { return perspective; }
    public final HDLighting getLighting() { return lighting; }
    public final int getBoostZoom() { return boostzoom; }
    @Override
    public final int getTileSize() { return 128 << tilescale; }
    public final int getTileScale() { return tilescale; }

    @Override
    public List<TileFlags.TileCoord> getTileCoords(DynmapWorld w, int x, int y, int z) {
        return perspective.getTileCoords(w, x, y, z, tilescale);
    }

    @Override
    public List<TileFlags.TileCoord> getTileCoords(DynmapWorld w, int minx, int miny, int minz, int maxx, int maxy, int maxz) {
        return perspective.getTileCoords(w, minx, miny, minz, maxx, maxy, maxz, tilescale);
    }

    @Override
    public MapTile[] getAdjecentTiles(MapTile tile) {
        return perspective.getAdjecentTiles(tile);
    }

    @Override
    public List<DynmapChunk> getRequiredChunks(MapTile tile) {
        return perspective.getRequiredChunks(tile);
    }

    /* Return number of zoom levels needed by this map (before extra levels from extrazoomout) */
    @Override
    public int getMapZoomOutLevels() {
        return mapzoomout;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getPrefix() {
        if (!versionedTiles) return prefix;
        if(rasterPrefix == null) rasterPrefix = TileLayout.namespace(prefix,perspective,tilescale,boostzoom,imgformat);
        return rasterPrefix;
    }
    @Override public java.util.List<TilePreviewSource> getTilePreviewSources() {return previewSources==null ? super.getTilePreviewSources() : previewSources;}
    @Override public String getLegacyTilePrefix() { return legacyScaleShift >= 0 || legacyPngScaleShift >= 0 ? prefix : null; }
    @Override public int getLegacyTileScaleShift() {return legacyScaleShift;}
    @Override public int getLegacyTileScaleShift(ImageEncoding encoding) {return encoding == ImageEncoding.PNG ? legacyPngScaleShift : legacyScaleShift;}

    /* Get maps rendered concurrently with this map in this world */
    @Override
    public List<MapType> getMapsSharingRender(DynmapWorld w) {
        ArrayList<MapType> maps = new ArrayList<>();
        for(MapType mt : w.maps) {
            if(mt instanceof HDMap) {
                HDMap hdmt = (HDMap)mt;
                if((hdmt.perspective == this.perspective) && (hdmt.boostzoom == this.boostzoom)) {  /* Same perspective */
                    maps.add(hdmt);
                }
            }
        }
        return maps;
    }

    /* Get names of maps rendered concurrently with this map type in this world */
    @Override
    public List<String> getMapNamesSharingRender(DynmapWorld w) {
        ArrayList<String> lst = new ArrayList<>();
        for(MapType mt : w.maps) {
            if(mt instanceof HDMap) {
                HDMap hdmt = (HDMap)mt;
                if((hdmt.perspective == this.perspective)  && (hdmt.boostzoom == this.boostzoom)) {  /* Same perspective */
                    if(hdmt.lighting.isNightAndDayEnabled())
                        lst.add(hdmt.getName() + "(night/day)");
                    else
                        lst.add(hdmt.getName());
                }
            }
        }
        return lst;
    }

    @Override
    public ImageFormat getImageFormat() { return imgformat; }

    @Override
    public void buildClientConfiguration(JSONObject worldObject, DynmapWorld world) {
        JSONObject o = new JSONObject();
        s(o, "type", "HDMapType");
        s(o, "name", name);
        s(o, "title", title);
        s(o, "icon", icon);
        s(o, "prefix", getPrefix());
        s(o, "background", bg_cfg);
        s(o, "backgroundday", bg_day_cfg);
        s(o, "backgroundnight", bg_night_cfg);
        s(o, "bigmap", true);
        s(o, "mapzoomout", (world.getExtraZoomOutLevels()+mapzoomout));
        s(o, "mapzoomin", mapzoomin);
        s(o, "boostzoom", boostzoom);
        s(o, "tilescale", tilescale);
        s(o, "image-format", imgformat.getFileExt());
        if(append_to_world.length() > 0)
            s(o, "append_to_world", append_to_world);
        perspective.addClientConfiguration(o);
        shader.addClientConfiguration(o);
        lighting.addClientConfiguration(o);

        a(worldObject, "maps", o);

    }

    private static int parseColor(String c) {
        int v = 0;
        if(c.startsWith("#")) {
            c = c.substring(1);
            if(c.length() == 3) {   /* #rgb */
                try {
                    v = Integer.parseInt(c, 16);
                } catch (NumberFormatException nfx) {
                    return 0;
                }
                v = 0xFF000000 | ((v & 0xF00) << 12) | ((v & 0x0F0) << 8) | ((v & 0x00F) << 4);
            }
            else if(c.length() == 6) {  /* #rrggbb */
                try {
                    v = Integer.parseInt(c, 16);
                } catch (NumberFormatException nfx) {
                    return 0;
                }
                v = 0xFF000000 | (v & 0xFFFFFF);
            }
        }

        return v;
    }

    @Override
    public int getBackgroundARGBDay() {
        return bgcolorday;
    }

    @Override
    public int getBackgroundARGBNight() {
        return bgcolornight;
    }

    @Override
    public void purgeOldTiles(final DynmapWorld world, final TileFlags rendered) {purgeOldTiles(world,rendered,0);}
    @Override
    public void purgeOldTiles(final DynmapWorld world,final TileFlags rendered,final long preserveAfter) {
        final MapStorage ms = world.getMapStorage();
        try {org.dynmap.TileScanJournal.run(world,this,"purge-"+Integer.toHexString(rendered.save().hashCode()),tile -> {
            ImageEncoding fmt=getImageFormat().getEncoding();
            if (fmt != getImageFormat().getEncoding()) { // Wrong format?  toss it
                /* Otherwise, delete tile */
                tile.delete();
            }
            else if (tile.zoom == 1) {   // First tier zoom?  sensitive to newly rendered tiles
                // If any were rendered, already triggered (and still needed
                if (rendered.getFlag(tile.x, tile.y) || rendered.getFlag(tile.x+1, tile.y) ||
                        rendered.getFlag(tile.x, tile.y-1) || rendered.getFlag(tile.x+1, tile.y-1)) {
                    return;
                }
                tile.enqueueZoomOutUpdate();
            }
            else if (tile.zoom == 0) {
                if (rendered.getFlag(tile.x, tile.y)) {  /* If we rendered this tile, its good */
                    return;
                }
                // New player updates can land outside the initial inventory while the
                // missing pass is running. Recheck their date under the write lock.
                if(!tile.getWriteLock())return;
                try {
                    if(preserveAfter>0 && tile.getLastModified()>=preserveAfter)return;
                    tile.delete();
                    MapManager.mapman.pushUpdate(world, new Client.Tile(tile.getURI()));
                    tile.enqueueZoomOutUpdate();
                } finally {tile.releaseWriteLock();}
            }
        },()->{var n=new org.dynmap.ConfigurationNode();var state=world.getMapState(this);if(state!=null)n.put("zoom",state.saveZoomOut());return n;},saved->{var state=world.getMapState(this);if(state!=null)state.mergeZoomOut(saved.getList("zoom"));},()->{if(!MapManager.mapman.checkpointWorld(world))throw new IllegalStateException("Zoom queue checkpoint failed");});}
        catch(java.io.IOException failure){org.dynmap.Log.warning("Tile cleanup paused; previous scan checkpoint retained");}
    }

    public String getTitle() {
        return title;
    }
    public int getMapZoomIn() {
        return mapzoomin;
    }
    public String getIcon() {
        return (icon == null)?"":icon;
    }

    public boolean setPrefix(String s) {
        if(!s.equals(prefix)) {
            prefix = s; rasterPrefix = null; legacyScaleShift = legacyPngScaleShift = -1; previewSources = null;
            return true;
        }
        return false;
    }

    public boolean setTitle(String s) {
        if(!s.equals(title)) {
            title = s;
            return true;
        }
        return false;
    }
    public boolean setAppendToWorld(String s) {
        if(!s.equals(append_to_world)) {
            append_to_world = s;
            return true;
        }
        return false;
    }
    public String getAppendToWorld() {
        return append_to_world;
    }

    public boolean setMapZoomIn(int mzi) {
        if(mzi != mapzoomin) {
            mapzoomin = mzi;
            return true;
        }
        return false;
    }
    public boolean setMapZoomOut(int mzi) {
        if(mzi != mapzoomout) {
            mapzoomout = mzi;
            return true;
        }
        return false;
    }
    public boolean setBoostZoom(int mzi) {
        if(mzi != this.boostzoom) {
            this.boostzoom = mzi; rasterPrefix = null; legacyScaleShift = legacyPngScaleShift = -1; previewSources = null;
            return true;
        }
        return false;
    }
    public boolean setTileScale(int mzi) {
        if(mzi != this.tilescale) {
            this.tilescale = mzi; rasterPrefix = null; legacyScaleShift = legacyPngScaleShift = -1; previewSources = null;
            return true;
        }
        return false;
    }
    public boolean setPerspective(HDPerspective p) {
        if(perspective != p) {
            perspective = p; rasterPrefix = null; legacyScaleShift = legacyPngScaleShift = -1; previewSources = null;
            return true;
        }
        return false;
    }
    public boolean setShader(HDShader p) {
        if(shader != p) {
            shader = p;
            return true;
        }
        return false;
    }
    public boolean setLighting(HDLighting p) {
        if(lighting != p) {
            lighting = p;
            return true;
        }
        return false;
    }
    public boolean setImageFormatSetting(String f) {
        if(imgfmtstring.equals(f) == false) {
            MapType.ImageFormat newfmt;
            if(f.equals("default"))
                newfmt = MapType.ImageFormat.fromID(core.getDefImageFormat());
            else
                newfmt = MapType.ImageFormat.fromID(f);
            if(newfmt != null) {
                imgformat = newfmt; rasterPrefix = null; legacyScaleShift = legacyPngScaleShift = -1; previewSources = null;
                imgfmtstring = f;
                return true;
            }
        }
        return false;
    }
    public String getImageFormatSetting() {
        return imgfmtstring;
    }
    public boolean setIcon(String v) {
        if("".equals(v)) v = null;
        icon = v;
        return true;
    }

    @Override
    public void addMapTiles(List<MapTile> list, DynmapWorld w, int tx, int ty) {
        list.add(new HDMapTile(w, this.perspective, tx, ty, boostzoom, tilescale));
    }

    private static final ImageVariant[] dayVariant = { ImageVariant.STANDARD, ImageVariant.DAY };

    @Override
    public ImageVariant[] getVariants() {
        if (lighting.isNightAndDayEnabled())
            return dayVariant;
        return super.getVariants();
    }

}
