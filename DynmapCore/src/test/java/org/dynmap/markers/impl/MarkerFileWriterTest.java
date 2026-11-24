package org.dynmap.markers.impl;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
class MarkerFileWriterTest {
 @Test void serializesAndCoalescesSnapshotsWhileRetryingFailedWrites() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
  List<Integer> writes=Collections.synchronizedList(new ArrayList<>());AtomicInteger attempts=new AtomicInteger();
  try(var writer=new MarkerFileWriter()){
   writer.submit("world",()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}writes.add(1);return true;});
   assertTrue(entered.await(3,TimeUnit.SECONDS));writer.submit("world",()->{writes.add(2);return true;});
   writer.submit("world",()->{if(attempts.incrementAndGet()==1)return false;writes.add(3);finished.countDown();return true;});release.countDown();
   assertTrue(finished.await(5,TimeUnit.SECONDS));assertEquals(List.of(1,3),writes);assertEquals(2,attempts.get());
  }
 }
 @Test void oneFailedWorldDoesNotBlockOtherWorldsAndShutdownFlushesPendingWork() throws Exception {
  AtomicInteger attempts=new AtomicInteger();var other=new CountDownLatch(1);
  try(var writer=new MarkerFileWriter()){
   writer.submit("failed",()->attempts.incrementAndGet()>1);writer.submit("other",()->{other.countDown();return true;});
   assertTrue(other.await(4,TimeUnit.SECONDS));
  }assertTrue(attempts.get()>=2);
 }
}
