package org.dynmap.markers.impl;

import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Marker commands accept files inside the import folder and plain icon identifiers only. */
final class MarkerFileAccess {
    static File resolve(File folder, String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid marker filename");
        Path file = folder.toPath().toAbsolutePath().normalize().resolve(name);
        if (Files.isSymbolicLink(file)) throw new IOException("Linked marker file");
        return file.toFile();
    }
    static InputStream readImport(File folder, String name) throws IOException {
        Path file = resolve(folder, name).toPath();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid import file");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Import file exceeds 1 MiB");
            return new ByteArrayInputStream(bytes);
        }
    }
    static byte[] readIcon(InputStream source) throws IOException {
        byte[] bytes = source.readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) throw new IOException("Marker icon exceeds 1 MiB");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid marker image");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!reader.getFormatName().equalsIgnoreCase("png") || width < 1 || height < 1 || width > 1024 || height > 1024)
                    throw new IOException("Marker icon must be a PNG no larger than 1024 by 1024");
                reader.read(0); // Validate the image before replacing the previous icon.
            } finally { reader.dispose(); }
        }
        return bytes;
    }
}
