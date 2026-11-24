package org.dynmap;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;

/** Private, bounded, atomic data-only checkpoints. A bad save never replaces the last good one. */
public final class ProgressCheckpoint {
    private static final int LIMIT=64*1024*1024;
    private final Path root,file;
    private long interval=60000;
    public ProgressCheckpoint(DynmapCore core,String key) throws IOException {
        this(core.getDataFolder().toPath().resolve("progress"),core.getFile(core.getWebPath()).toPath(),key);
        interval=intervalMillis(core.configuration);
    }
    public ProgressCheckpoint(Path directory,Path web,String key) throws IOException {
        root=directory.toAbsolutePath().normalize();
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Linked checkpoint directory");
        Files.createDirectories(root);
        if(root.toRealPath().startsWith((Files.exists(web)?web.toRealPath():web.toAbsolutePath().normalize())))throw new IOException("Public checkpoint directory");
        try{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
        try{file=root.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))+".progress");}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    private void safe()throws IOException {
        if(Files.isSymbolicLink(root)||Files.isSymbolicLink(file))throw new IOException("Linked checkpoint path");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid checkpoint file");
            try{if(((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)throw new IOException("Linked checkpoint file");}
            catch(UnsupportedOperationException ignored){}
        }
    }
    @SuppressWarnings("unchecked") public synchronized ConfigurationNode read() throws IOException {
        safe();if(!Files.exists(file))return null;
        if(Files.size(file)>LIMIT)throw new IOException("Checkpoint byte limit");
        try(var stream=new GZIPInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS))) {
            byte[] bytes=stream.readNBytes(LIMIT+1);if(bytes.length>LIMIT)throw new IOException("Checkpoint byte limit");
            String json=new String(bytes,StandardCharsets.UTF_8);validateDepth(json);
            Object parsed=new JSONParser().parse(json);
            if(!(parsed instanceof Map<?,?> map)||!Long.valueOf(1).equals(map.get("schema"))||!(map.get("data") instanceof Map<?,?> data))throw new IOException("Unsupported checkpoint schema");
            return new ConfigurationNode((Map<String,Object>)data);
        }catch(org.json.simple.parser.ParseException|RuntimeException invalid){throw new IOException("Invalid checkpoint",invalid);}
    }
    private static void validateDepth(String text)throws IOException {
        boolean string=false,escape=false;int depth=0;
        for(char ch:text.toCharArray()) {
            if(string){if(escape)escape=false;else if(ch=='\\')escape=true;else if(ch=='"')string=false;}
            else if(ch=='"')string=true;else if(ch=='{'||ch=='['){if(++depth>64)throw new IOException("Checkpoint nesting limit");}else if(ch=='}'||ch==']')depth--;
        }
    }
    private static Object plain(Object value) {
        if(value instanceof ConfigurationNode node)return plain(node.entries);
        if(value instanceof Map<?,?> map){var copy=new LinkedHashMap<String,Object>();map.forEach((k,v)->copy.put(String.valueOf(k),plain(v)));return copy;}
        if(value instanceof Collection<?> list)return list.stream().map(ProgressCheckpoint::plain).toList();
        return value;
    }
    public synchronized void write(ConfigurationNode data)throws IOException {
        boolean interrupted=Thread.interrupted();try{writeUninterrupted(data);}finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private void writeUninterrupted(ConfigurationNode data)throws IOException {
        safe();Path pending=Files.createTempFile(root,".checkpoint-",".tmp");
        try {
            try{Files.setPosixFilePermissions(pending,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            try(var gzip=new GZIPOutputStream(Files.newOutputStream(pending));var writer=new OutputStreamWriter(new FilterOutputStream(gzip){
                long bytes;@Override public void write(byte[] b,int off,int len)throws IOException{if((bytes+=len)>LIMIT)throw new IOException("Checkpoint byte limit");out.write(b,off,len);}
                @Override public void write(int b)throws IOException{if(++bytes>LIMIT)throw new IOException("Checkpoint byte limit");out.write(b);}
            },StandardCharsets.UTF_8)){JSONValue.writeJSONString(Map.of("schema",1,"data",plain(data)),writer);}
            try(var channel=FileChannel.open(pending,StandardOpenOption.WRITE)){channel.force(true);}
            safe();try{Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(pending,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(pending);}
    }
    public synchronized void delete()throws IOException{safe();Files.deleteIfExists(file);}
    public static ProgressCheckpoint optional(DynmapCore core,String key){if(!enabled(core.configuration))return null;try{return new ProgressCheckpoint(core,key);}catch(IOException failure){Log.warning("Progress checkpoint unavailable; normal work remains enabled");return null;}}
    public long intervalMillis(){return interval;}
    public static long intervalMillis(ConfigurationNode config){return Math.max(5,Math.min(3600,config.getInteger("progress-checkpoints/interval-seconds",60)))*1000L;}
    public static boolean enabled(ConfigurationNode config){return config.getBoolean("progress-checkpoints/enabled",true);}
    public static String layout(DynmapWorld world){return world.maps.stream().map(m->m.getName()+":"+m.getPrefix()).sorted().toList().toString()+":"+world.minY+":"+world.worldheight;}
}
