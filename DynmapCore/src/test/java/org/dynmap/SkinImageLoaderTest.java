package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
class SkinImageLoaderTest {
 private byte[] png(int width,int height)throws IOException {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB),"png",out);return out.toByteArray();}
 @Test void acceptsStandardLegacyAndHighResolutionSkins()throws Exception {for(int[] size:new int[][]{{64,32},{64,64},{128,128},{1024,512}}){var image=SkinImageLoader.decode(png(size[0],size[1]));assertEquals(size[0],image.getWidth());}}
 @Test void rejectsInvalidLargeDimensionsAndOversizedPayloadsBeforePixelDecoding()throws Exception {
  for(int[] size:new int[][]{{32,32},{2048,2048},{64,33}}){byte[] data=png(size[0],size[1]);assertThrows(IOException.class,()->SkinImageLoader.decode(data));}
  assertThrows(IOException.class,()->SkinImageLoader.decode(new byte[SkinImageLoader.MAX_BYTES+1]));assertThrows(IOException.class,()->SkinImageLoader.decode(new byte[]{1,2,3}));
 }
 @Test void fileAndOtherNonHttpProtocolsAreRejected()throws Exception {assertThrows(IOException.class,()->SkinImageLoader.load(new URL("file:/etc/passwd")));}
 @Test void httpRetrievalUsesBoundedTimeoutsAndDisconnects()throws Exception {
  byte[] data=png(64,64);var closed=new java.util.concurrent.atomic.AtomicBoolean();
  URL url=new URL(null,"https://textures.minecraft.net/test",new URLStreamHandler(){protected URLConnection openConnection(URL requested){return new HttpURLConnection(requested){
   public void connect(){} public boolean usingProxy(){return false;}public void disconnect(){closed.set(true);}
   public int getResponseCode(){assertEquals(5000,getConnectTimeout());assertEquals(5000,getReadTimeout());assertFalse(getInstanceFollowRedirects());return 200;}
   public long getContentLengthLong(){return data.length;}public InputStream getInputStream(){return new ByteArrayInputStream(data);}
  };}});
  assertEquals(64,SkinImageLoader.load(url).getWidth());assertTrue(closed.get());
 }
 @Test void redirectsCannotEscapeToLocalFiles()throws Exception {
  URL url=new URL(null,"https://textures.minecraft.net/test",new URLStreamHandler(){protected URLConnection openConnection(URL requested){return new HttpURLConnection(requested){
   public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}
   public int getResponseCode(){return 302;}public String getHeaderField(String name){return "file:/etc/passwd";}
  };}});
  assertThrows(IOException.class,()->SkinImageLoader.load(url));
 }
 @Test void unapprovedProfileHostsAreRejectedBeforeOpeningConnections()throws Exception {
  for(String address:new String[]{"https://127.0.0.1/secret","http://169.254.169.254/latest/meta-data/","https://localhost/","https://attacker.example/","https://textures.minecraft.net.attacker.example/","https://textures.minecraft.net:8443/","https://user:password@textures.minecraft.net/"}) {
   var opened=new java.util.concurrent.atomic.AtomicBoolean();
   URL url=new URL(null,address,new URLStreamHandler(){protected URLConnection openConnection(URL u){opened.set(true);throw new AssertionError("Network was accessed");}});
   assertThrows(IOException.class,()->SkinImageLoader.load(url));assertFalse(opened.get());
  }
 }
 @Test void officialHttpSkinsAreUpgradedAndCustomProvidersRequireExplicitHttpsApproval()throws Exception {
  assertEquals("https",SkinImageLoader.approvedUrl(new URL("http://textures.minecraft.net/texture/id"),java.util.List.of()).getProtocol());
  var custom=new URL("https://skin.example/skin.png");
  assertEquals(custom,SkinImageLoader.approvedUrl(custom,java.util.List.of("skin.example")));
  assertThrows(IOException.class,()->SkinImageLoader.approvedUrl(new URL("https://sub.skin.example/skin"),java.util.List.of("skin.example")));
  assertThrows(IOException.class,()->SkinImageLoader.approvedUrl(new URL("http://skin.example/skin"),java.util.List.of("skin.example")));
 }
 @Test void redirectsCannotContactPrivateOrUnapprovedHttpServices()throws Exception {
  for(String destination:new String[]{"http://127.0.0.1:8080/","https://169.254.169.254/","https://attacker.example/","https://textures.minecraft.net.attacker.example/"}) {
   var opened=new java.util.concurrent.atomic.AtomicInteger();
   URL url=new URL(null,"https://textures.minecraft.net/test",new URLStreamHandler(){protected URLConnection openConnection(URL u){opened.incrementAndGet();return new HttpURLConnection(u){
    public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}
    public int getResponseCode(){return 302;}public String getHeaderField(String name){return destination;}
   };}});
   assertThrows(IOException.class,()->SkinImageLoader.load(url));assertEquals(1,opened.get());
  }
 }
}
