package com.articulorum.transform.iiif;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Reads the width and height of an image from its leading bytes, without decoding pixels.
 * Works for the formats the JDK reads (JPEG, PNG, GIF, BMP, WBMP and TIFF with leading
 * directories); other formats (JPEG 2000, for example) yield nothing.
 */
public final class ImageDimensions {

    /**
     * Width and height in pixels.
     *
     * @param width  the width
     * @param height the height
     */
    public record Size(int width, int height) {
    }

    private ImageDimensions() {
        // utility class
    }

    /**
     * Reads the dimensions of an image.
     *
     * @param header the leading bytes of the image (or all of it)
     * @return the dimensions, or empty when they cannot be determined
     */
    public static Optional<Size> read(final byte[] header) {
        if (header == null || header.length == 0) {
            return Optional.empty();
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(header))) {
            if (in == null) {
                return Optional.empty();
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            while (readers.hasNext()) {
                final ImageReader reader = readers.next();
                try {
                    reader.setInput(in, true, true);
                    final int width = reader.getWidth(0);
                    final int height = reader.getHeight(0);
                    if (width > 0 && height > 0) {
                        return Optional.of(new Size(width, height));
                    }
                } catch (IOException | RuntimeException e) {
                    in.seek(0);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

}
