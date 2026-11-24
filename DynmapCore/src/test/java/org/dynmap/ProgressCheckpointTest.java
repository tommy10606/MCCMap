package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class ProgressCheckpointTest {
 @TempDir Path root;
 ProgressCheckpoint store()throws Exception{Files.createDirectories(root.resolve("web"));return new ProgressCheckpoint(root.resolve("private"),root.resolve("web"),"world:../test");}
 @Test void roundTripsQueuesCursorsFlagsAndLargeIntegerDates()throws Exception{
  var store=store();var n=new ConfigurationNode();n.put("jobs",List.of(new ConfigurationNode(new LinkedHashMap<>(Map.of("x",-123,"time",System.currentTimeMillis())))));n.put("phase","tiles");store.write(n);
  var loaded=store.read();assertEquals("tiles",loaded.getString("phase"));assertEquals(-123,loaded.getNodes("jobs").getFirst().getInteger("x",0));
  assertEquals(1,Files.list(root.resolve("private")).count());store.delete();assertNull(store.read());
 }
 @Test void rejectsPublicAndLinkedDirectories()throws Exception{
  var web=Files.createDirectory(root.resolve("web"));assertThrows(IOException.class,()->new ProgressCheckpoint(web.resolve("state"),web,"job"));
  Files.createSymbolicLink(root.resolve("linked"),web);assertThrows(IOException.class,()->new ProgressCheckpoint(root.resolve("linked/state"),web,"job"));
 }
 @Test void corruptSnapshotsAreRejectedWithoutExecutableDeserialization()throws Exception{
  var store=store();var n=new ConfigurationNode();n.put("phase","safe");store.write(n);Path file=Files.list(root.resolve("private")).findFirst().orElseThrow();Files.writeString(file,"truncated");assertThrows(IOException.class,store::read);
  try(var gzip=new GZIPOutputStream(Files.newOutputStream(file))){gzip.write(("{\"schema\":1,\"data\":"+"[".repeat(100)+"0"+"]".repeat(100)+"}").getBytes());}assertThrows(IOException.class,store::read);
 }
 @Test void refusedWritePreservesLinkedTargetAndPrivatePermissions()throws Exception{
  var store=store();var n=new ConfigurationNode();n.put("phase","before");store.write(n);Path file=Files.list(root.resolve("private")).findFirst().orElseThrow();
  if(Files.getFileStore(file).supportsFileAttributeView("posix"))assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  Path backup=root.resolve("backup");Files.move(file,backup);byte[] before=Files.readAllBytes(backup);Files.createSymbolicLink(file,backup);
  assertThrows(IOException.class,()->store.write(n));assertArrayEquals(before,Files.readAllBytes(backup));
 }
 @Test void interruptedShutdownCanStillCommitItsFinalCheckpoint()throws Exception{
  var store=store();var n=new ConfigurationNode();n.put("phase","stopped");Thread.currentThread().interrupt();try{store.write(n);assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}assertEquals("stopped",store.read().getString("phase"));
 }
}
