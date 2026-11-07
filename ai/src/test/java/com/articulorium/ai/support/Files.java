package com.articulorium.ai.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Binaries made for tests.
 */
public final class Files {

    private Files() {
    }

    /**
     * An image.
     *
     * @param format {@code jpeg}, {@code png} or {@code bmp}
     * @param width  its width
     * @param height its height
     * @return the bytes
     */
    public static byte[] image(final String format, final int width, final int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height / 2);
        graphics.dispose();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * A PDF with document information and one line of text per page.
     *
     * @param pages the number of pages
     * @return the bytes
     */
    public static byte[] pdf(final int pages) {
        try (PDDocument document = new PDDocument()) {
            final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= pages; i++) {
                final PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 700);
                    content.showText("Page " + i + " of the annual report on harbour shipping");
                    content.endText();
                }
            }
            final PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Annual Report 1912");
            info.setAuthor("Harbour Board");
            info.setKeywords("shipping; harbours");
            final Calendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            created.clear();
            created.set(2001, Calendar.MARCH, 4, 5, 6, 7);
            info.setCreationDate(created);
            document.setDocumentInformation(info);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
