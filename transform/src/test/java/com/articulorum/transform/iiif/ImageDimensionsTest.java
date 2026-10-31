package com.articulorum.transform.iiif;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link ImageDimensions}.
 */
class ImageDimensionsTest {

    static byte[] image(final String format, final int width, final int height) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    @Test
    void readsDimensionsFromHeaders() throws Exception {
        assertThat(ImageDimensions.read(image("jpg", 300, 200))).contains(new ImageDimensions.Size(300, 200));
        // the leading bytes of a PNG (signature and IHDR chunk) are enough
        assertThat(ImageDimensions.read(Arrays.copyOf(image("png", 640, 480), 64)))
            .contains(new ImageDimensions.Size(640, 480));
    }

    @Test
    void unknownFormatsYieldNothing() {
        assertThat(ImageDimensions.read(new byte[0])).isEmpty();
        assertThat(ImageDimensions.read(null)).isEmpty();
        assertThat(ImageDimensions.read("not an image".getBytes())).isEmpty();
    }

}
