package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class RenderQueueCheckpointTest {
 @Test void snapshotsIncludeInFlightWorkWithoutDrainingTheLiveQueue()throws Exception{
  var started=new CountDownLatch(1);var release=new CountDownLatch(1);
  var queue=new AsynchronousQueue<String>(item->{started.countDown();try{release.await();}catch(InterruptedException stop){Thread.currentThread().interrupt();}},1,100,1,1,false);
  queue.push("in-flight");queue.start();try{assertTrue(started.await(2,TimeUnit.SECONDS));assertEquals(0,queue.size());assertTrue(queue.hasWork());assertFalse(queue.hasWork(item->!item.equals("in-flight")));queue.push("waiting");assertTrue(queue.hasWork(item->!item.equals("in-flight")));var snapshot=queue.snapshot();assertTrue(snapshot.contains("in-flight"));assertTrue(snapshot.contains("waiting"));assertEquals(1,queue.size());queue.done("in-flight");assertFalse(queue.snapshot().contains("in-flight"));}finally{release.countDown();queue.stop();}
 }
}
