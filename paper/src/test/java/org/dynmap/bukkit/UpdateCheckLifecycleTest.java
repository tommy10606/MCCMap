package org.dynmap.bukkit;
import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
class UpdateCheckLifecycleTest {
 static class Connection extends HttpURLConnection {
  boolean disconnected;
  Connection()throws Exception { super(new java.net.URL("https://example.test")); }
  public void disconnect(){disconnected=true;} public void connect(){} public boolean usingProxy(){return false;}
 }
 @Test void closedCheckDoesNotParseResponsesOrPublishMessagesAndDisconnectsNetwork()throws Exception {
  var session=new GitHubUpdateChecker.Session();var connection=new Connection();assertTrue(session.attach(connection));session.close();
  assertTrue(connection.disconnected);assertFalse(session.active());assertFalse(session.attach(new Connection()));
  AtomicInteger messages=new AtomicInteger();session.publish("1.0.0","invalid-json",false,msg->messages.incrementAndGet());session.message("ignored",msg->messages.incrementAndGet());assertEquals(0,messages.get());
 }
 @Test void closeWaitsForAnAlreadyStartedPublication()throws Exception {
  var session=new GitHubUpdateChecker.Session();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var stopped=new AtomicBoolean();
  var worker=new Thread(()->session.message("status",msg->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));
  var closer=new Thread(()->{session.close();stopped.set(true);});closer.start();Thread.sleep(30);assertFalse(stopped.get());release.countDown();worker.join(3000);closer.join(3000);assertTrue(stopped.get());
 }
}
