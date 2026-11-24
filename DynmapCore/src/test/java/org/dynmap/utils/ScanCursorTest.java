package org.dynmap.utils;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.dynmap.ConfigurationNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ScanCursorTest {
 @TempDir Path root;
 @Test void acknowledgedFilesDoNotRepeatButUnacknowledgedWorkReplays()throws Exception{
  Files.writeString(root.resolve("a"),"a");Files.writeString(root.resolve("b"),"b");Files.createDirectories(root.resolve("sub"));Files.writeString(root.resolve("sub/c"),"c");
  var cursor=new ScanCursor();assertEquals("a",cursor.next(root).getFileName().toString());
  var replay=new ScanCursor(cursor.save());assertEquals("a",replay.next(root).getFileName().toString());replay.acknowledge();
  var restored=new ScanCursor(replay.save());assertEquals("b",restored.next(root).getFileName().toString());restored.acknowledge();assertEquals("c",restored.next(root).getFileName().toString());restored.acknowledge();assertNull(restored.next(root));assertNull(new ScanCursor(restored.save()).next(root));
 }
 @Test void linksAndTraversalCannotEscapeTheScanRoot()throws Exception{
  Path external=Files.createTempDirectory("scan-external");try{Files.writeString(external.resolve("secret"),"secret");Files.createSymbolicLink(root.resolve("link"),external);assertNull(new ScanCursor().next(root));var n=new ConfigurationNode();n.put("directories",List.of("../outside"));assertThrows(IOException.class,()->new ScanCursor(n));}finally{Files.deleteIfExists(external.resolve("secret"));Files.delete(external);}
 }
 @Test void deletionBetweenRunsDoesNotInvalidateTheRemainingCursor()throws Exception{
  Files.writeString(root.resolve("a"),"a");Files.writeString(root.resolve("b"),"b");var cursor=new ScanCursor();cursor.next(root);cursor.acknowledge();var saved=cursor.save();Files.delete(root.resolve("a"));assertEquals("b",new ScanCursor(saved).next(root).getFileName().toString());
 }
}
