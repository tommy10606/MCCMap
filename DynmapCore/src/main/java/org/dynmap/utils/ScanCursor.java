package org.dynmap.utils;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.dynmap.ConfigurationNode;

/** Acknowledged directory cursor. Reopening may replay one file, but never skips unacknowledged work. */
public final class ScanCursor {
    private final ArrayDeque<String> directories=new ArrayDeque<>();
    private String current,after="";
    private transient List<String> listing;
    private transient int index;
    private transient Path offered;
    public ScanCursor(){directories.add("");}
    public ScanCursor(ConfigurationNode saved)throws IOException {
        for(String dir:saved.getStrings("directories",List.of())){valid(dir);directories.add(dir);if(directories.size()>100000)throw new IOException("Scan cursor limit");}
        current=saved.getString("current",null);if(current!=null)valid(current);
        after=saved.getString("after","");if(after.contains("/")||after.contains("\\"))throw new IOException("Invalid cursor entry");
    }
    private static void valid(String name)throws IOException {
        if(name.length()>1024||name.startsWith("/")||name.contains("\\")||name.indexOf(':')>=0)throw new IOException("Invalid scan cursor");
        if(!name.isEmpty())for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.chars().anyMatch(Character::isISOControl))throw new IOException("Invalid scan cursor");
    }
    public synchronized ConfigurationNode save(){var node=new ConfigurationNode();node.put("directories",new ArrayList<>(directories));if(current!=null)node.put("current",current);node.put("after",after);return node;}
    public synchronized Path next(Path root)throws IOException {
        if(offered!=null)return offered;
        root=root.toAbsolutePath().normalize();
        while(true){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
            if(current==null){if(directories.isEmpty())return null;current=directories.removeFirst();after="";listing=null;}
            Path dir=root.resolve(current).normalize();boolean linked=false;
            for(Path p=dir;p!=null&&p.startsWith(root);p=p.getParent())if(Files.isSymbolicLink(p)){linked=true;break;}
            if(linked||!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)){current=null;listing=null;continue;}
            if(listing==null){try(var stream=Files.newDirectoryStream(dir)){listing=new ArrayList<>();for(Path p:stream)listing.add(p.getFileName().toString());}listing.sort(String::compareTo);index=0;while(index<listing.size()&&listing.get(index).compareTo(after)<=0)index++;}
            if(index>=listing.size()){current=null;listing=null;continue;}
            String name=listing.get(index);Path path=dir.resolve(name);
            if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path)){
                String child=root.relativize(path).toString().replace(java.io.File.separatorChar,'/');valid(child);directories.addLast(child);after=name;index++;continue;
            }
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){after=name;index++;continue;}
            offered=path;return path;
        }
    }
    public synchronized void acknowledge(){if(offered!=null){after=offered.getFileName().toString();index++;offered=null;}}
}
