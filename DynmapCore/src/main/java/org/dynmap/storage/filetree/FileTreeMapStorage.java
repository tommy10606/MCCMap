// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.storage.filetree;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapType;
import org.dynmap.MapType.ImageEncoding;
import org.dynmap.MapType.ImageVariant;
import org.dynmap.PlayerFaces.FaceType;
import org.dynmap.debug.Debug;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.utils.ImageIOManager;
import org.dynmap.storage.MapStorageTileEnumCB;
import org.dynmap.storage.MapStorageBaseTileEnumCB;
import org.dynmap.storage.MapStorageTileSearchEndCB;
import org.dynmap.utils.BufferInputStream;
import org.dynmap.utils.BufferOutputStream;

public class FileTreeMapStorage extends MapStorage {
    private File baseTileDir;
    private DynmapCore core;
    private TileHashManager hashmap;
    private final java.util.concurrent.locks.ReentrantReadWriteLock cleanupMutation=new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final java.util.concurrent.atomic.AtomicLong cleanupEpoch=new java.util.concurrent.atomic.AtomicLong();
    private static final int MAX_WRITE_RETRIES = 6;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    // Configured storage roots may themselves be links; never follow links below that boundary.
    private boolean safePath(File file) {
        var root = baseTileDir.toPath().toAbsolutePath().normalize();
        var path = file.toPath().toAbsolutePath().normalize();
        if (!path.startsWith(root)) return false;
        for (var part = path; !part.equals(root); part = part.getParent())
            if (java.nio.file.Files.isSymbolicLink(part)) return false;
        return true;
    }
    private static boolean safeId(String id, boolean nested) {
        if (id == null || id.isEmpty() || id.length() > 512 || id.indexOf('\\') >= 0 || id.startsWith("/")) return false;
        for (int i = 0; i < id.length(); i++) if (Character.isISOControl(id.charAt(i))) return false;
        String[] parts = id.split("/", -1);
        if (!nested && parts.length != 1) return false;
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf(':') >= 0) return false;
        return true;
    }
    private byte[] readFile(File file, int limit) {
        if (!safePath(file) || !java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        try (var channel = java.nio.file.Files.newByteChannel(file.toPath(), java.util.Set.of(
                java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
            if (!java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) || channel.size() > limit) return null;
            try (var input = java.nio.channels.Channels.newInputStream(channel)) {
                byte[] bytes = input.readNBytes(limit + 1);
                return bytes.length > limit ? null : bytes;
            }
        } catch (IOException | RuntimeException failure) { return null; }
    }

    public class StorageTile extends MapStorageTile {
        private final String baseFilename;
        private final String uri;
        private File f; // cached file
        private ImageEncoding f_fmt;

        StorageTile(DynmapWorld world, MapType map, int x, int y,
                int zoom, ImageVariant var) {
            super(world, map, x, y, zoom, var);
            String baseURI;
            if (zoom > 0) {
                baseURI = map.getPrefix() + var.variantSuffix + "/"+ (x >> 5) + "_" + (y >> 5) + "/" + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz".substring(0, zoom) + "_" + x + "_" + y;
            }
            else {
                baseURI = map.getPrefix() + var.variantSuffix + "/"+ (x >> 5) + "_" + (y >> 5) + "/" + x + "_" + y;
            }
            baseFilename = world.getName() + "/" + baseURI;
            uri = baseURI + "." + map.getImageFormat().getFileExt();
        }
        private File getTileFile(ImageEncoding fmt) {
            if ((f == null) || (fmt != f_fmt)) {
                f = new File(baseTileDir, baseFilename + "." + fmt.getFileExt());
                f_fmt = fmt;
            }
            return f;
        }
        private File getTileFile() {
            // A previous format can belong to a different projection/resolution.
            // Never present those pixels under the current map's coordinates.
            return getTileFile(map.getImageFormat().getEncoding());
        }
        private List<File> getTileFilesAltFormats() {
            ImageEncoding fmt = map.getImageFormat().getEncoding();

            List<File> files = new ArrayList<File>();
            for (ImageEncoding ie: ImageEncoding.values()) {
                if (ie != fmt) {
                    files.add(getTileFile(ie));
                }
            }

            return files;
        }
        @Override
        public boolean exists() {
            File ff = getTileFile();
            return safePath(ff) && ff.isFile() && ff.canRead();
        }

        @Override
        public long getLastModified() {
            File ff = getTileFile(); return safePath(ff) ? ff.lastModified() : 0;
        }

        @Override
        public boolean matchesHashCode(long hash) {
            File ff = getTileFile(map.getImageFormat().getEncoding());
            return safePath(ff) && ff.isFile() && ff.canRead() && (hash == hashmap.getImageHashCode(world.getName() + "." + map.getPrefix(), x, y));
        }

        @Override
        public TileRead read() {
            ImageEncoding fmt = map.getImageFormat().getEncoding();
            File ff = getTileFile(fmt);
            byte[] buf = readFile(ff, 32 * 1024 * 1024);
            if (buf != null) {
                TileRead tr = new TileRead();
                tr.image = new BufferInputStream(buf);
                tr.format = fmt;
                tr.hashCode = hashmap.getImageHashCode(world.getName() + "." + map.getPrefix(), x, y);
                tr.lastModified = ff.lastModified();
                return tr;
            }
            return null;
        }

        @Override
        public TileRead readForDisplay() {
            TileRead nativeTile = read();
            return nativeTile == null ? readLegacyPreview() : zoom > 0 ? mergeLegacyOverview(nativeTile) : nativeTile;
        }

        private TileRead readLegacyPreview() {
            if(zoom<0||zoom>30)return null;
            long[] modified={0};
            java.awt.image.BufferedImage image = null;
            try {
                // Each format can come from a different render generation and grid.
                // Draw older JPEG coverage first, then the high-detail PNG coverage.
                for (var sourceLayout : map.getTilePreviewSources()) {
                    String legacy=sourceLayout.prefix();int shift=sourceLayout.scaleShift();ImageEncoding encoding=sourceLayout.encoding();
                    if(legacy.equals(map.getPrefix()) || shift < -4 || shift > 4)continue;
                    String extension = encoding == ImageEncoding.PNG ? "png" : "jpg";
                    var layer=LegacyTilePreview.render(x,y,zoom,shift,source->{
                        String zoomPrefix=source.zoom()==0?"":"z".repeat(source.zoom())+"_";
                        File old=new File(baseTileDir,world.getName()+"/"+legacy+var.variantSuffix+"/"+(source.x()>>5)+"_"+(source.y()>>5)+"/"+zoomPrefix+source.x()+"_"+source.y()+"."+extension);
                        byte[] data=readFile(old,32*1024*1024);
                        if(data!=null)modified[0]=Math.max(modified[0],old.lastModified());
                        return data;
                    });
                    if(layer==null)continue;
                    if(image==null)image=layer;
                    else {
                        var graphics=image.createGraphics();
                        try {graphics.drawImage(layer,0,0,null);}
                        finally {graphics.dispose();layer.flush();}
                    }
                }
                if(image==null)return null;
                var encoded=ImageIOManager.imageIOEncode(image,MapType.ImageFormat.FORMAT_PNG);
                if(encoded==null)return null;
                var result=new TileRead();result.image=new BufferInputStream(encoded.buf,encoded.len);result.format=ImageEncoding.PNG;
                var checksum=new java.util.zip.CRC32();checksum.update(encoded.buf,0,encoded.len);result.hashCode=checksum.getValue();
                result.lastModified=modified[0];return result;
            }catch(IOException|RuntimeException invalid){return null;}
            finally {if(image!=null)image.flush();}
        }

        /** A partially generated overview is not proof that its whole area was rendered. */
        private TileRead mergeLegacyOverview(TileRead current) {
            if (current.format != ImageEncoding.PNG) return current;
            boolean available=false;
            for(var source:map.getTilePreviewSources()) {
                File root=new File(baseTileDir,world.getName()+"/"+source.prefix()+var.variantSuffix);
                if(!source.prefix().equals(map.getPrefix()) && safePath(root) && root.isDirectory()) {available=true;break;}
            }
            if(!available)return current;
            java.awt.image.BufferedImage foreground = null, background = null;
            try {
                var copy = new TileRead(); copy.format = current.format;
                copy.image = new BufferInputStream(current.image.buffer(), current.image.length());
                foreground = ImageIOManager.imageIODecode(copy);
                if (foreground == null || foreground.getWidth() != 128 || foreground.getHeight() != 128) return current;
                boolean transparent = false;
                for (int y=0; y<128 && !transparent; y++) for (int x=0; x<128; x++)
                    if ((foreground.getRGB(x,y) >>> 24) < 255) { transparent = true; break; }
                if (!transparent) return current;
                TileRead legacy = readLegacyPreview();
                if (legacy == null) return current;
                background = ImageIOManager.imageIODecode(legacy);
                if (background == null) return current;
                var graphics = background.createGraphics();
                try { graphics.drawImage(foreground, 0, 0, null); }
                finally { graphics.dispose(); }
                var encoded = ImageIOManager.imageIOEncode(background, MapType.ImageFormat.FORMAT_PNG);
                if (encoded == null) return current;
                var result = new TileRead(); result.image = new BufferInputStream(encoded.buf, encoded.len);
                result.format = ImageEncoding.PNG; result.lastModified = Math.max(current.lastModified, legacy.lastModified);
                var checksum = new java.util.zip.CRC32(); checksum.update(encoded.buf, 0, encoded.len); result.hashCode = checksum.getValue();
                return result;
            } catch (IOException | RuntimeException invalid) { return current; }
            finally { if(foreground != null) foreground.flush(); if(background != null) background.flush(); }
        }

        @Override
        public boolean write(long hash, BufferOutputStream encImage, long timestamp) {
            cleanupMutation.readLock().lock();
            try {return writeTile(hash,encImage,timestamp);}
            finally {cleanupEpoch.incrementAndGet();cleanupMutation.readLock().unlock();}
        }
        private boolean writeTile(long hash, BufferOutputStream encImage, long timestamp) {
            File ff = getTileFile(map.getImageFormat().getEncoding());
            List<File> ffalt = getTileFilesAltFormats();
            File ffpar = ff.getParentFile();
            if (!safePath(ff) || ffalt.stream().anyMatch(file -> !safePath(file))) return false;
            if (encImage == null) { // Delete?
                for (File file : ffalt) file.delete();
                ff.delete();
                hashmap.updateHashCode(world.getName() + "." + map.getPrefix(), x, y, -1);
                // Signal update for zoom out
                if (zoom == 0) {
                    world.enqueueZoomOutUpdate(this);
                }
                return true;
            }
            if (ffpar.exists() == false) {
                ffpar.mkdirs();
            }
            if (!replaceFile(ff, encImage.buf, encImage.len, timestamp)) {
                if (core.getStorageUsage() != null && core.getStorageUsage().pauseReason() != null) {
                    var state = world.getMapState(map);
                    if (state != null) {
                        if (zoom == 0) state.invalidateTile(x, y);
                        else state.setZoomOutInv(x, y, zoom - 1);
                    }
                }
                return false;
            }
            for (File file : ffalt) file.delete();
            hashmap.updateHashCode(world.getName() + "." + map.getPrefix(), x, y, hash);
            retireLegacyTiles();
            // Signal update for zoom out
            if (zoom == 0) {
                world.enqueueZoomOutUpdate(this);
            }
            return true;
        }

        /** Retire only known compatible sources after every overlapping native replacement exists. */
        private void retireLegacyTiles() {
            if(zoom!=0)return;
            for (var sourceLayout : map.getTilePreviewSources()) {
                String legacy=sourceLayout.prefix();int shift=sourceLayout.scaleShift();ImageEncoding encoding=sourceLayout.encoding();
                if(legacy.equals(map.getPrefix()) || shift < -4 || shift > 0 || activePrefix(world,legacy+var.variantSuffix))continue;
                double factor=Math.scalb(1.0,shift);
                String extension=encoding==ImageEncoding.PNG?"png":"jpg";
                for(var source:LegacyTilePreview.sources(x,y,0,shift)) {
                    File old=new File(baseTileDir,world.getName()+"/"+legacy+var.variantSuffix+"/"+(source.x()>>5)+"_"+(source.y()>>5)+"/"+source.x()+"_"+source.y()+"."+extension);
                    if(!safePath(old)||!java.nio.file.Files.isRegularFile(old.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))continue;
                    double left=factor*source.x(),top=factor*((long)source.y()+1);
                    boolean complete=true;
                    for(long tx=(long)Math.floor(left);complete&&tx<left+factor;tx++)for(long ty=(long)Math.ceil(top-1);ty+1>top-factor;ty--) {
                        if(tx<Integer.MIN_VALUE||tx>Integer.MAX_VALUE||ty<Integer.MIN_VALUE||ty>Integer.MAX_VALUE
                            ||!new StorageTile(world,map,(int)tx,(int)ty,0,var).exists()) {complete=false;break;}
                    }
                    if(complete)try {java.nio.file.Files.deleteIfExists(old.toPath());}
                    catch(IOException failure){Log.warning("Cannot remove superseded map tile: "+old.getName());}
                }
            }
        }

        @Override
        public boolean getWriteLock() {
            return FileTreeMapStorage.this.getWriteLock(baseFilename);
        }

        @Override
        public void releaseWriteLock() {
            FileTreeMapStorage.this.releaseWriteLock(baseFilename);
        }

        @Override
        public boolean getReadLock(long timeout) {
            return FileTreeMapStorage.this.getReadLock(baseFilename, timeout);
        }

        @Override
        public void releaseReadLock() {
            FileTreeMapStorage.this.releaseReadLock(baseFilename);
        }

        @Override
        public void cleanup() {
        }

        @Override
        public String getURI() {
            return uri;
        }

        @Override
        public void enqueueZoomOutUpdate() {
            world.enqueueZoomOutUpdate(this);
        }
        @Override
        public MapStorageTile getZoomOutTile() {
            int xx, yy;
            int step = 1 << zoom;
            if(x >= 0)
                xx = x - (x % (2*step));
            else
                xx = x + (x % (2*step));
            yy = -y;
            if(yy >= 0)
                yy = yy - (yy % (2*step));
            else
                yy = yy + (yy % (2*step));
            yy = -yy;
            return new StorageTile(world, map, xx, yy, zoom+1, var);
        }
        @Override
        public boolean equals(Object o) {
            if (o instanceof StorageTile) {
                StorageTile st = (StorageTile) o;
                return baseFilename.equals(st.baseFilename);
            }
            return false;
        }
        @Override
        public int hashCode() {
            return baseFilename.hashCode();
        }
        @Override
        public String toString() {
            return baseFilename;
        }
    }

    /** Bounded, restartable metadata walk over one map's native tile layout. */
    public boolean scanTiles(DynmapWorld world,MapType map,TileScanCursor cursor,int limit,java.util.function.Consumer<MapStorageTile> visitor)throws IOException {
        var variants=map.getVariants();int visited=0;
        while(cursor.variant<variants.length) {
            var variant=variants[cursor.variant];
            var root=new File(baseTileDir,world.getName()+"/"+map.getPrefix()+variant.variantSuffix);
            if(!safePath(root))throw new IOException("Unsafe tile scan root");
            java.nio.file.Path path=cursor.files.next(root.toPath());
            if(path==null){synchronized(cursor){cursor.variant++;cursor.files=new org.dynmap.utils.ScanCursor();}continue;}
            var match=java.util.regex.Pattern.compile("(?:(z{1,30})_)?(-?[0-9]+)_(-?[0-9]+)[.](png|jpg|webp)").matcher(path.getFileName().toString());
            if(match.matches()&&match.group(4).equals(map.getImageFormat().getFileExt())) {
                try {
                    int x=Integer.parseInt(match.group(2)),y=Integer.parseInt(match.group(3)),zoom=match.group(1)==null?0:match.group(1).length();
                    var tile=new StorageTile(world,map,x,y,zoom,variant);
                    if(safePath(path.toFile())&&path.toAbsolutePath().normalize().equals(tile.getTileFile().toPath().toAbsolutePath().normalize())) {
                        tile.lastModified=java.nio.file.Files.getLastModifiedTime(path,java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis();
                        try{visitor.accept(tile);}finally{tile.cleanup();}
                    }
                }catch(NumberFormatException ignored){}catch(java.nio.file.NoSuchFileException removed){}
            }
            cursor.files.acknowledge();if(++visited>=Math.max(1,limit))return false;
        }
        return true;
    }


    static final class LegacyCandidate {
        final java.nio.file.Path path;
        final java.nio.file.attribute.BasicFileAttributes attributes;
        final List<LegacyCleanupPlan.Check> checks;
        final long epoch;
        int index;
        LegacyCandidate(java.nio.file.Path path,java.nio.file.attribute.BasicFileAttributes attributes,List<LegacyCleanupPlan.Check> checks,long epoch) {
            this.path=path;this.attributes=attributes;this.checks=checks;this.epoch=epoch;
        }
    }
    private boolean activePrefix(DynmapWorld world,String prefix) {
        for(var active:world.maps)for(var variant:active.getVariants())if((active.getPrefix()+variant.variantSuffix).equals(prefix))return true;
        return false;
    }
    private boolean verifiedReplacement(DynmapWorld world,MapType map,ImageVariant variant,LegacyCleanupPlan.Check check) {
        var tile=new StorageTile(world,map,check.x(),check.y(),check.zoom(),variant);
        if(!tile.getReadLock(1))return false;
        try {
            byte[] data=readFile(tile.getTileFile(),32*1024*1024);if(data==null)return false;
            try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
                var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())return false;
                var reader=readers.next();
                try {
                    reader.setInput(input,true,true);
                    if(!reader.getFormatName().equalsIgnoreCase("PNG")||reader.getWidth(0)!=128||reader.getHeight(0)!=128)return false;
                    var image=reader.read(0);
                    try {
                        if(check.zoom()>0)for(int py=check.top();py<check.bottom();py++)for(int px=check.left();px<check.right();px++)
                            if((image.getRGB(px,py)>>>24)!=255)return false;
                        return true;
                    }finally{image.flush();}
                }finally{reader.dispose();}
            }
        }catch(IOException|RuntimeException invalid){return false;}
        finally{tile.releaseReadLock();tile.cleanup();}
    }
    /** Bounded deletion of known compatible aliases. Unknown layouts and incomplete coverage are retained. */
    public boolean cleanupLegacyStep(DynmapWorld world,MapType map,LegacyCleanupCursor cursor,int budget)throws IOException {
        if(!world.maps.contains(map)||map.isReadOnly()||map.getImageFormat().getEncoding()!=ImageEncoding.PNG
                ||!safeId(world.getName(),false)||!safeId(map.getPrefix(),false))return true;
        var sources=map.getTilePreviewSources();var variants=map.getVariants();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(25);
        int work=0;
        while(cursor.source<sources.size() && work<Math.max(1,budget) && System.nanoTime()<deadline) {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
            if(cursor.variant>=variants.length){cursor.source++;cursor.variant=0;cursor.files=new org.dynmap.utils.ScanCursor();cursor.candidate=null;continue;}
            var source=sources.get(cursor.source);var variant=variants[cursor.variant];String oldPrefix=source.prefix()+variant.variantSuffix;
            if(!safeId(source.prefix(),false)||!source.prefix().matches("[A-Za-z0-9_.-]{1,128}")||activePrefix(world,oldPrefix)){cursor.variant++;cursor.files=new org.dynmap.utils.ScanCursor();cursor.candidate=null;continue;}
            File oldRoot=new File(baseTileDir,world.getName()+"/"+oldPrefix);
            if(!safePath(oldRoot)){cursor.variant++;cursor.files=new org.dynmap.utils.ScanCursor();cursor.candidate=null;continue;}
            var path=cursor.files.next(oldRoot.toPath());
            if(path==null){cursor.variant++;cursor.files=new org.dynmap.utils.ScanCursor();cursor.candidate=null;continue;}
            if(cursor.candidate==null) {
                work++;cursor.examined++;
                var match=java.util.regex.Pattern.compile("(?:(z{1,30})_)?(-?[0-9]+)_(-?[0-9]+)[.](png|jpg)").matcher(path.getFileName().toString());
                try {
                    if(!match.matches()||!match.group(4).equals(source.encoding().getFileExt())){cursor.retained++;cursor.files.acknowledge();continue;}
                    int x=Integer.parseInt(match.group(2)),y=Integer.parseInt(match.group(3)),zoom=match.group(1)==null?0:match.group(1).length();
                    var expected=oldRoot.toPath().resolve((x>>5)+"_"+(y>>5)).resolve(path.getFileName()).toAbsolutePath().normalize();
                    var checks=LegacyCleanupPlan.create(x,y,zoom,source.scaleShift());
                    if(checks==null||!safePath(path.toFile())||!path.toAbsolutePath().normalize().equals(expected)){cursor.retained++;cursor.files.acknowledge();continue;}
                    var attributes=java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if(!attributes.isRegularFile()){cursor.retained++;cursor.files.acknowledge();continue;}
                    cursor.candidate=new LegacyCandidate(path,attributes,checks,cleanupEpoch.get());
                }catch(NumberFormatException|java.nio.file.NoSuchFileException invalid){cursor.retained++;cursor.files.acknowledge();continue;}
            }
            var candidate=cursor.candidate;
            boolean valid=true;
            while(candidate.index<candidate.checks.size()&&work<Math.max(1,budget)&&System.nanoTime()<deadline) {
                work++;if(!verifiedReplacement(world,map,variant,candidate.checks.get(candidate.index++))){valid=false;break;}
            }
            if(valid&&candidate.index<candidate.checks.size())return false;
            if(valid&&cleanupMutation.writeLock().tryLock())try {
                if(candidate.epoch!=cleanupEpoch.get()||!world.maps.contains(map)||map.isReadOnly()||activePrefix(world,oldPrefix)||!safePath(path.toFile()))valid=false;
                else {
                    var current=java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if(!current.isRegularFile()||current.size()!=candidate.attributes.size()||!current.lastModifiedTime().equals(candidate.attributes.lastModifiedTime())
                            ||!java.util.Objects.equals(current.fileKey(),candidate.attributes.fileKey()))valid=false;
                    else if(java.nio.file.Files.deleteIfExists(path)){
                        cursor.deleted++;cursor.bytes+=current.size();
                        for(var directory:List.of(path.getParent(),oldRoot.toPath()))if(safePath(directory.toFile())&&!activePrefix(world,oldPrefix))
                            try{java.nio.file.Files.delete(directory);}catch(java.nio.file.DirectoryNotEmptyException|java.nio.file.NoSuchFileException notEmpty){}catch(IOException ignored){}
                    }
                }
            }catch(java.nio.file.NoSuchFileException removed){valid=false;}
            finally{cleanupMutation.writeLock().unlock();}
            else valid=false;
            if(!valid)cursor.retained++;
            cursor.candidate=null;cursor.files.acknowledge();
        }
        return cursor.source>=sources.size();
    }

    public FileTreeMapStorage() {
    }

    @Override
    public boolean init(DynmapCore core) {
        if (!super.init(core)) {
            return false;
        }
        this.core = core;
        baseTileDir = core.getTilesFolder();
        hashmap = new TileHashManager(baseTileDir, true);
        return true;
    }

    @Override
    public MapStorageTile getTile(DynmapWorld world, MapType map, int x, int y,
            int zoom, ImageVariant var) {
        return new StorageTile(world, map, x, y, zoom, var);
    }

    @Override
    public MapStorageTile getTile(DynmapWorld world, String uri) {
        String[] suri = uri.split("/");
        if (suri.length < 2) return null;
        String mname = suri[0]; // Map URI - might include variant
        MapType mt = null;
        ImageVariant imgvar = null;
        // Find matching map type and image variant
        for (int mti = 0; (mt == null) && (mti < world.maps.size()); mti++) {
            MapType type = world.maps.get(mti);
            ImageVariant[] var = type.getVariants();
            for (int ivi = 0; (imgvar == null) && (ivi < var.length); ivi++) {
                if (mname.equals(type.getPrefix() + var[ivi].variantSuffix)) {
                    mt = type;
                    imgvar = var[ivi];
                }
            }
        }
        if (mt == null) {   // Not found?
            return null;
        }
        // URI input never controls the underlying filesystem path.
        if (suri.length > 3 || uri.indexOf('\\') >= 0) return null;
        String fname = suri[suri.length - 1];
        var coords = java.util.regex.Pattern.compile("(?:(z{1,32})_)?(-?[0-9]+)_(-?[0-9]+)\\.(?:png|jpg|webp)").matcher(fname);
        if (!coords.matches()) return null;
        try {
            int zoom = coords.group(1) == null ? 0 : coords.group(1).length();
            return getTile(world, mt, Integer.parseInt(coords.group(2)), Integer.parseInt(coords.group(3)), zoom, imgvar);
        } catch (NumberFormatException invalid) { return null; }
    }


    private void processEnumMapTiles(DynmapWorld world, MapType map, File base, ImageVariant var, MapStorageTileEnumCB cb, MapStorageBaseTileEnumCB cbBase, MapStorageTileSearchEndCB cbEnd) {
        File bdir = new File(base, map.getPrefix() + var.variantSuffix);
        if (java.nio.file.Files.isSymbolicLink(base.toPath()) || java.nio.file.Files.isSymbolicLink(bdir.toPath()) || bdir.isDirectory() == false) {
            if(cbEnd != null)
                cbEnd.searchEnded();
            return;
        }

        LinkedList<File> dirs = new LinkedList<File>(); // List to traverse
        dirs.add(bdir);   // Directory for map
        // While more paths to handle
        while (dirs.isEmpty() == false) {
            File dir = dirs.pop();
            String[] dirlst = dir.list();
            if (dirlst == null) continue;
            for(String fn : dirlst) {
                if (fn.equals(".") || fn.equals(".."))
                    continue;
                File f = new File(dir, fn);
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue;
                if (f.isDirectory()) {   /* If directory, add to list to process */
                    dirs.add(f);
                }
                else {  /* Else, file - see if tile */
                    String ext = null;
                    int extoff = fn.lastIndexOf('.');
                    if (extoff >= 0) {
                        ext = fn.substring(extoff+1);
                        fn = fn.substring(0, extoff);
                    }
                    ImageEncoding fmt = ImageEncoding.fromExt(ext);
                    if (fmt == null) {
                        continue;
                    }
                    // See if zoom tile
                    int zoom = 0;
                    if (fn.startsWith("z")) {
                        while (fn.startsWith("z")) {
                            fn = fn.substring(1);
                            zoom++;
                        }
                        if (fn.startsWith("_")) {
                            fn = fn.substring(1);
                        }
                    }
                    // Split remainder to get coords
                    String[] coord = fn.split("_");
                    if (coord.length == 2) {    // Must be 2 to be a tile
                        try {
                            int x = Integer.parseInt(coord[0]);
                            int y = Integer.parseInt(coord[1]);
                            // Invoke callback
                            MapStorageTile t = new StorageTile(world, map, x, y, zoom, var);
                            t.lastModified = f.lastModified();
                            if(cb != null)
                                cb.tileFound(t, fmt);
                            if(cbBase != null && t.zoom == 0)
                                cbBase.tileFound(t, fmt);
                            t.cleanup();
                        } catch (NumberFormatException nfx) {
                        }
                    }
                }
            }
        }
        if(cbEnd != null) {
            cbEnd.searchEnded();
        }
    }

    @Override
    public void enumMapTiles(DynmapWorld world, MapType map, MapStorageTileEnumCB cb) {
        File base = new File(baseTileDir, world.getName()); // Get base directory for world
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        }
        else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processEnumMapTiles(world, mt, base, var, cb, null, null);
            }
        }
    }

    @Override
    public void enumMapBaseTiles(DynmapWorld world, MapType map, MapStorageBaseTileEnumCB cbBase, MapStorageTileSearchEndCB cbEnd) {
        File base = new File(baseTileDir, world.getName()); // Get base directory for world
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        }
        else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processEnumMapTiles(world, mt, base, var, null, cbBase, cbEnd);
            }
        }
    }

    private void processPurgeMapTiles(DynmapWorld world, MapType map, File base, ImageVariant var) {
        String mname = map.getPrefix() + var.variantSuffix;
        if (!safePath(base) || !safePath(new File(base, mname))) return;
        // Clean up hash files
        String[] hlist = base.list();
        if (hlist != null) {
            for (String h : hlist) {
                if (h.endsWith(".hash") == false) continue;
                if (h.startsWith(mname + "_")) continue;
                File f = new File(base, h);
                f.delete();
            }
        }
        File bdir = new File(base, mname);
        if (bdir.isDirectory() == false) return;

        LinkedList<File> dirs = new LinkedList<File>(); // List to traverse
        LinkedList<File> dirsdone = new LinkedList<File>();
        dirs.add(bdir);   // Directory for map
        // While more paths to handle
        while (dirs.isEmpty() == false) {
            File dir = dirs.pop();
            if (!safePath(dir)) continue;
            dirsdone.add(dir);
            String[] dirlst = dir.list();
            if (dirlst == null) continue;
            for(String fn : dirlst) {
                if (fn.equals(".") || fn.equals(".."))
                    continue;
                File f = new File(dir, fn);
                if (!safePath(f)) continue;
                if (f.isDirectory()) {   /* If directory, add to list to process */
                    dirs.add(f);
                }
                else {  /* Else, file - cleanup */
                    f.delete();
                }
            }
        }
        // Clean up directories, in reverse order of traverse
        int cnt = dirsdone.size();
        for (int i = cnt-1; i >= 0; i--) {
            File f = dirsdone.get(i);
            f.delete();
        }
    }

    @Override
    public void purgeMapTiles(DynmapWorld world, MapType map) {
        cleanupMutation.readLock().lock();
        try {purgeMapTilesUnprotected(world,map);}
        finally {cleanupEpoch.incrementAndGet();cleanupMutation.readLock().unlock();}
    }
    private void purgeMapTilesUnprotected(DynmapWorld world, MapType map) {
        File base = new File(baseTileDir, world.getName()); // Get base directory for world
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        }
        else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processPurgeMapTiles(world, mt, base, var);
            }
        }
    }

    @Override
    public boolean setPlayerFaceImage(String playername, FaceType facetype,
            BufferOutputStream encImage) {
        if (!safeId(playername, false)) return false;
        String baseFilename = "faces/" + facetype.id + "/" + playername + ".png";
        File ff = new File(baseTileDir, baseFilename);
        if (!safePath(ff)) return false;
        File ffpar = ff.getParentFile();
        if (encImage == null) { // Delete?
            ff.delete();
            return true;
        }
        if (ffpar.exists() == false) {
            ffpar.mkdirs();
        }
        getWriteLock(baseFilename);
        boolean done = replaceFile(ff, encImage.buf, encImage.len);
        releaseWriteLock(baseFilename);
        return done;
    }

    @Override
    public BufferInputStream getPlayerFaceImage(String playername,
            FaceType facetype) {
        if (!safeId(playername, false)) return null;
        String baseFilename = "faces/" + facetype.id + "/" + playername + ".png";
        File ff = new File(baseTileDir, baseFilename);
        if (safePath(ff) && getReadLock(baseFilename, 5000)) {
            try {
                byte[] buf = readFile(ff, 32 * 1024 * 1024);
                return buf == null ? null : new BufferInputStream(buf);
            } finally { releaseReadLock(baseFilename); }
        }
        return null;
    }


    @Override
    public boolean hasPlayerFaceImage(String playername, FaceType facetype) {
        if (!safeId(playername, false)) return false;
        String baseFilename = "faces/" + facetype.id + "/" + playername + ".png";
        File ff = new File(baseTileDir, baseFilename);
        return safePath(ff) && ff.isFile();
    }

    @Override
    public boolean setMarkerImage(String markerid, BufferOutputStream encImage) {
        if (!safeId(markerid, true)) return false;
        String baseFilename = "_markers_/" + markerid + ".png";
        File ff = new File(baseTileDir, baseFilename);
        if (!safePath(ff)) return false;
        File ffpar = ff.getParentFile();
        if (encImage == null) { // Delete?
            ff.delete();
            return true;
        }
        if (ffpar.exists() == false) {
            ffpar.mkdirs();
        }
        getWriteLock(baseFilename);
        boolean done = replaceFile(ff, encImage.buf, encImage.len);
        releaseWriteLock(baseFilename);
        return done;
    }

    @Override
    public BufferInputStream getMarkerImage(String markerid) {
        if (!safeId(markerid, true)) return null;
        String baseFilename = "_markers_/" + markerid + ".png";
        File ff = new File(baseTileDir, baseFilename);
        if (safePath(ff) && getReadLock(baseFilename, 5000)) {
            try {
                byte[] buf = readFile(ff, 32 * 1024 * 1024);
                return buf == null ? null : new BufferInputStream(buf);
            } finally { releaseReadLock(baseFilename); }
        }
        return null;
    }

    @Override
    public boolean setMarkerFile(String world, String content) {
        if (!safeId(world, false)) return false;
        String baseFilename = "_markers_/marker_" + world + ".json";
        File ff = new File(baseTileDir, baseFilename);
        if (!safePath(ff)) return false;
        File ffpar = ff.getParentFile();
        if (content == null) { // Delete?
            ff.delete();
            return true;
        }
        if (ffpar.exists() == false) {
            ffpar.mkdirs();
        }
        getWriteLock(baseFilename);
        byte[] buf = content.getBytes(UTF8);
        boolean done = replaceFile(ff, buf, buf.length);
        releaseWriteLock(baseFilename);
        return done;
    }

    @Override
    public String getMarkerFile(String world) {
        if (!safeId(world, false)) return null;
        String baseFilename = "_markers_/marker_" + world + ".json";
        File ff = new File(baseTileDir, baseFilename);
        if (safePath(ff) && getReadLock(baseFilename, 5000)) {
            try {
                byte[] buf = readFile(ff, 64 * 1024 * 1024);
                return buf == null ? null : new String(buf, UTF8);
            } finally { releaseReadLock(baseFilename); }
        }
        return null;
    }

    @Override
    // For external web server only
    public String getMarkersURI() {
        return "tiles/";
    }

    @Override
    // For external web server only
    public String getTilesURI() {
        return "tiles/";
    }

    private boolean replaceFile(File f, byte[] b, int len) {
        return replaceFile(f, b, len, System.currentTimeMillis(), false);
    }

    private boolean replaceFile(File f, byte[] b, int len, long timestamp) {
        return replaceFile(f, b, len, timestamp, true);
    }

    private boolean replaceFile(File f, byte[] b, int len, long timestamp, boolean rendererWrite) {
        if (!safePath(f)) return false;
        var usage = rendererWrite ? core.getStorageUsage() : null;
        long oldBytes = 0;
        try {
            if (java.nio.file.Files.isRegularFile(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                oldBytes = java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS).size();
        } catch (IOException failure) { return false; }
        var reservation = usage == null ? null : usage.reserve(f.toPath(), Math.max(0L, (long)len - oldBytes), len);
        if (usage != null && reservation == null) return false;
        File pending = new File(f.getPath() + ".new");
        try {
            for (int retry = 0; retry <= MAX_WRITE_RETRIES; retry++) {
                try {
                    try (var stream = java.nio.file.Files.newOutputStream(pending.toPath(),
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                            java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        stream.write(b, 0, len);
                    }
                    try { java.nio.file.Files.move(pending.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                    catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        java.nio.file.Files.move(pending.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    f.setLastModified(timestamp);
                    if (reservation != null) reservation.commit();
                    return true;
                } catch (IOException failure) {
                    if (retry == MAX_WRITE_RETRIES) { Log.warning("Could not write local map image"); return false; }
                    try { Thread.sleep(50L << retry); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
                }
            }
            return false;
        } finally {
            if (reservation != null) reservation.close();
            // Do not follow or remove someone else's symlink.
            if (!java.nio.file.Files.isSymbolicLink(pending.toPath())) pending.delete();
        }
    }
}
