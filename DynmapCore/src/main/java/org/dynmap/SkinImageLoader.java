package org.dynmap;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.Iterator;
import javax.imageio.*;

/** Bounded HTTP skin retrieval; inspect dimensions before allocating decoded pixels. */
final class SkinImageLoader {
    static final int MAX_BYTES = 1024 * 1024;
    static BufferedImage load(URL original) throws IOException {
        return load(original, java.util.List.of());
    }
    static BufferedImage load(URL original, java.util.Collection<String> additionalHosts) throws IOException {
        URL url = original; long deadline = System.nanoTime() + 15_000_000_000L;
        for (int redirect = 0; redirect <= 3; redirect++) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("Skin download deadline");
            url = approvedUrl(url, additionalHosts);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "MCCMap-Skins");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status <= 399) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Invalid skin redirect");
                    url = new URL(url, location); continue;
                }
                if (status != 200 || connection.getContentLengthLong() > MAX_BYTES) throw new IOException("Invalid skin response");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline || bytes.size() + length > MAX_BYTES)
                            throw new IOException("Skin download limit");
                        bytes.write(buffer, 0, length);
                    }
                    return decode(bytes.toByteArray());
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many skin redirects");
    }
    /** Exact administrator-approved hosts only; player profiles cannot grant network access. */
    static URL approvedUrl(URL url, java.util.Collection<String> additionalHosts) throws IOException {
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        if (url.getUserInfo() != null || url.getRef() != null || url.getPort() != -1 && url.getPort() != 443)
            throw new IOException("Unsupported skin URL authority");
        boolean mojang = host.equals("textures.minecraft.net");
        boolean approved = mojang || additionalHosts.stream().anyMatch(allowed -> allowed != null && host.equals(allowed.trim().toLowerCase(java.util.Locale.ROOT)));
        if (!approved || !host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}"))
            throw new IOException("Skin host is not approved");
        // Mojang profiles historically contain HTTP URLs. Fetch those using TLS instead.
        if (mojang && url.getProtocol().equals("http"))
            return new URL("https", host, url.getFile());
        if (!url.getProtocol().equals("https")) throw new IOException("Skin downloads require HTTPS");
        return url;
    }
    static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Skin byte limit");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unknown skin image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input); int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 64 || width > 1024 || width % 64 != 0 || (height != width && height * 2 != width))
                    throw new IOException("Invalid skin dimensions");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
}
