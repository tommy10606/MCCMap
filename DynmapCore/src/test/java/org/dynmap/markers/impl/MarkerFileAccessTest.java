package org.dynmap.markers.impl;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkerFileAccessTest {
    @TempDir Path root;
    @Test void rejectsTraversalAndLinkedImports() throws Exception {
        Path imports=Files.createDirectories(root.resolve("import"));
        Path secret=Files.writeString(root.resolve("secret"),"private");
        for(String name:new String[]{"../secret",secret.toString(),"a\\secret","..",".","a\n","C:secret"})
            assertThrows(IOException.class,()->MarkerFileAccess.readImport(imports.toFile(),name));
        Files.createSymbolicLink(imports.resolve("linked"),secret);
        assertThrows(IOException.class,()->MarkerFileAccess.readImport(imports.toFile(),"linked"));
        Files.writeString(imports.resolve("description.txt"),"public");
        try(var input=MarkerFileAccess.readImport(imports.toFile(),"description.txt")) {
            assertEquals("public",new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test void boundsImportedFileSize() throws Exception {
        Files.write(root.resolve("big"),new byte[1024*1024+1]);
        assertThrows(IOException.class,()->MarkerFileAccess.readImport(root.toFile(),"big"));
    }
    @Test void validatesIconsBeforeTheyArePublished() throws Exception {
        var bytes=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB),"png",bytes);
        assertArrayEquals(bytes.toByteArray(),MarkerFileAccess.readIcon(new ByteArrayInputStream(bytes.toByteArray())));
        assertThrows(IOException.class,()->MarkerFileAccess.readIcon(new ByteArrayInputStream(new byte[]{1,2,3})));
        assertThrows(IOException.class,()->MarkerFileAccess.readIcon(new ByteArrayInputStream(new byte[1024*1024+1])));
        bytes.reset();ImageIO.write(new BufferedImage(1025,1,BufferedImage.TYPE_INT_ARGB),"png",bytes);
        byte[] wide=bytes.toByteArray();assertThrows(IOException.class,()->MarkerFileAccess.readIcon(new ByteArrayInputStream(wide)));
    }
    @Test void iconImportCannotWriteOutsideItsFolderOrReplaceWithInvalidData() throws Exception {
        var api=new MarkerAPIImpl();Path icons=Files.createDirectories(root.resolve("markers"));
        var folder=MarkerAPIImpl.class.getDeclaredField("markerdir");folder.setAccessible(true);folder.set(api,icons.toFile());
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"png",bytes);
        assertFalse(api.loadMarkerIconStream("../escaped",new ByteArrayInputStream(bytes.toByteArray())));
        assertFalse(Files.exists(root.resolve("escaped.png")));
        assertTrue(api.loadMarkerIconStream("safe",new ByteArrayInputStream(bytes.toByteArray())));
        assertFalse(api.loadMarkerIconStream("safe",new ByteArrayInputStream(new byte[]{1,2,3})));
        assertArrayEquals(bytes.toByteArray(),Files.readAllBytes(icons.resolve("safe.png")));
    }
}
