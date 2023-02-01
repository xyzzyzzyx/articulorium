package com.articulorium.ai.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.articulorium.ai.platform.Descriptions;
import com.articulorium.domain.vocabulary.Premis;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.avi.AviDirectory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads technical and embedded metadata from the bytes of a binary, without a model: dimensions,
 * durations and dates of images and videos (EXIF, IPTC, QuickTime/MP4, AVI), and the document
 * information and text of PDFs.
 *
 * <p>Embedded GPS positions are deliberately not read: a photograph's location can identify where
 * people live, and suggesting it as metadata would publish it.</p>
 */
public final class MediaFacts {

    /** EBUCore: width in pixels. */
    static final String WIDTH = Premis.EBUCORE_NAMESPACE + "width";

    /** EBUCore: height in pixels. */
    static final String HEIGHT = Premis.EBUCORE_NAMESPACE + "height";

    /** EBUCore: duration. */
    static final String DURATION = Premis.EBUCORE_NAMESPACE + "duration";

    /** EBUCore: frame rate. */
    static final String FRAME_RATE = Premis.EBUCORE_NAMESPACE + "frameRate";

    static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private MediaFacts() {
    }

    /**
     * Reads the metadata of an image or a video.
     *
     * @param bytes the file
     * @param kind  image or video
     * @return the facts
     */
    public static Facts ofImageOrVideo(final byte[] bytes, final MediaKind kind) {
        final List<MetadataStatement> statements = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            notes.add("UNREADABLE_METADATA");
            return new Facts(statements, "", notes, 0);
        }
        if (kind == MediaKind.IMAGE) {
            dimensions(metadata, statements);
            final ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                text(ifd0.getString(ExifIFD0Directory.TAG_ARTIST), Descriptions.DCTERMS + "creator", statements);
                text(ifd0.getString(ExifIFD0Directory.TAG_COPYRIGHT), Descriptions.DCTERMS + "rights", statements);
                text(ifd0.getString(ExifIFD0Directory.TAG_IMAGE_DESCRIPTION), Descriptions.DCTERMS + "description",
                    statements);
            }
            final ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exif != null) {
                date(exif.getDateOriginal(), statements);
            }
            final IptcDirectory iptc = metadata.getFirstDirectoryOfType(IptcDirectory.class);
            if (iptc != null) {
                text(iptc.getString(IptcDirectory.TAG_OBJECT_NAME), Descriptions.DCTERMS + "title", statements);
                text(iptc.getString(IptcDirectory.TAG_CAPTION), Descriptions.DCTERMS + "description", statements);
                text(iptc.getString(IptcDirectory.TAG_BY_LINE), Descriptions.DCTERMS + "creator", statements);
                final String[] keywords = iptc.getStringArray(IptcDirectory.TAG_KEYWORDS);
                if (keywords != null) {
                    for (final String keyword : keywords) {
                        text(keyword, Descriptions.DCTERMS + "subject", statements);
                    }
                }
            }
        } else {
            video(metadata, statements);
        }
        return new Facts(dedupe(statements), "", notes, 0);
    }

    /**
     * Reads the document information and the text of a PDF.
     *
     * @param bytes    the file
     * @param maxPages pages whose text is read
     * @param maxText  characters of text kept
     * @return the facts (with the text)
     */
    public static Facts ofPdf(final byte[] bytes, final int maxPages, final int maxText) {
        final List<MetadataStatement> statements = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            final int pages = document.getNumberOfPages();
            statements.add(MetadataStatement.literal(Descriptions.DCTERMS + "extent", pages + " p.", null, null,
                MetadataStatement.Origin.FILE));
            final PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                text(info.getTitle(), Descriptions.DCTERMS + "title", statements);
                text(info.getAuthor(), Descriptions.DCTERMS + "creator", statements);
                text(info.getSubject(), Descriptions.DCTERMS + "description", statements);
                if (info.getKeywords() != null) {
                    for (final String keyword : info.getKeywords().split("[,;]")) {
                        text(keyword, Descriptions.DCTERMS + "subject", statements);
                    }
                }
                final Calendar created = info.getCreationDate();
                if (created != null) {
                    date(created.getTime(), statements);
                }
            }
            String text = "";
            if (document.isEncrypted() && !document.getCurrentAccessPermission().canExtractContent()) {
                notes.add("TEXT_NOT_EXTRACTABLE");
            } else {
                final PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(Math.min(pages, Math.max(1, maxPages)));
                text = stripper.getText(document).replaceAll("[ \\t\\x0B\\f]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
                if (pages > maxPages) {
                    notes.add("TEXT_FROM_FIRST_PAGES");
                }
                if (text.length() > maxText) {
                    text = text.substring(0, maxText);
                    notes.add("TEXT_SHORTENED");
                }
                if (text.isBlank()) {
                    notes.add("NO_TEXT");
                }
            }
            return new Facts(dedupe(statements), text, notes, pages);
        } catch (InvalidPasswordException e) {
            notes.add("ENCRYPTED");
            return new Facts(statements, "", notes, 0);
        } catch (IOException | RuntimeException e) {
            notes.add("UNREADABLE_METADATA");
            return new Facts(statements, "", notes, 0);
        }
    }

    private static void dimensions(final Metadata metadata, final List<MetadataStatement> statements) {
        for (final Directory directory : metadata.getDirectories()) {
            if (directory instanceof ExifThumbnailDirectory) {
                continue;
            }
            Integer width = null;
            Integer height = null;
            for (final Tag tag : directory.getTags()) {
                if ("Image Width".equals(tag.getTagName())) {
                    width = integer(directory, tag.getTagType());
                } else if ("Image Height".equals(tag.getTagName())) {
                    height = integer(directory, tag.getTagType());
                }
            }
            if (width != null && height != null && width > 0 && height > 0) {
                statements.add(integerStatement(WIDTH, width));
                statements.add(integerStatement(HEIGHT, height));
                return;
            }
        }
    }

    private static void video(final Metadata metadata, final List<MetadataStatement> statements) {
        Double seconds = null;
        Integer width = null;
        Integer height = null;
        Double frameRate = null;
        Date created = null;
        final Mp4Directory mp4 = metadata.getFirstDirectoryOfType(Mp4Directory.class);
        if (mp4 != null) {
            seconds = decimal(mp4, Mp4Directory.TAG_DURATION_SECONDS);
            created = mp4.getDate(Mp4Directory.TAG_CREATION_TIME);
        }
        final Mp4VideoDirectory mp4Video = metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class);
        if (mp4Video != null) {
            width = integer(mp4Video, Mp4VideoDirectory.TAG_WIDTH);
            height = integer(mp4Video, Mp4VideoDirectory.TAG_HEIGHT);
            frameRate = decimal(mp4Video, Mp4VideoDirectory.TAG_FRAME_RATE);
        }
        final QuickTimeDirectory mov = metadata.getFirstDirectoryOfType(QuickTimeDirectory.class);
        if (mov != null) {
            seconds = seconds == null ? decimal(mov, QuickTimeDirectory.TAG_DURATION_SECONDS) : seconds;
            created = created == null ? mov.getDate(QuickTimeDirectory.TAG_CREATION_TIME) : created;
        }
        final QuickTimeVideoDirectory movVideo = metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class);
        if (movVideo != null) {
            width = width == null ? integer(movVideo, QuickTimeVideoDirectory.TAG_WIDTH) : width;
            height = height == null ? integer(movVideo, QuickTimeVideoDirectory.TAG_HEIGHT) : height;
        }
        final AviDirectory avi = metadata.getFirstDirectoryOfType(AviDirectory.class);
        if (avi != null) {
            width = width == null ? integer(avi, AviDirectory.TAG_WIDTH) : width;
            height = height == null ? integer(avi, AviDirectory.TAG_HEIGHT) : height;
            frameRate = frameRate == null ? decimal(avi, AviDirectory.TAG_FRAMES_PER_SECOND) : frameRate;
        }
        if (width != null && height != null && width > 0 && height > 0) {
            statements.add(integerStatement(WIDTH, width));
            statements.add(integerStatement(HEIGHT, height));
        }
        if (seconds != null && seconds > 0) {
            statements.add(MetadataStatement.literal(DURATION, duration(seconds), null, XSD + "duration",
                MetadataStatement.Origin.FILE));
        }
        if (frameRate != null && frameRate > 0) {
            statements.add(MetadataStatement.literal(FRAME_RATE, String.format(Locale.ROOT, "%.3f", frameRate)
                .replaceAll("\\.?0+$", ""), null, XSD + "decimal", MetadataStatement.Origin.FILE));
        }
        // QuickTime and MP4 count from 1904: a zero time stamp is no creation date
        if (created != null && created.toInstant().isAfter(Instant.parse("1971-01-01T00:00:00Z"))) {
            date(created, statements);
        }
    }

    /**
     * Writes seconds as an {@code xsd:duration}.
     *
     * @param seconds the duration
     * @return for example {@code PT1H2M3.5S}
     */
    static String duration(final double seconds) {
        final long whole = (long) seconds;
        final long hours = whole / 3600;
        final long minutes = whole % 3600 / 60;
        final double rest = seconds - hours * 3600 - minutes * 60;
        final StringBuilder out = new StringBuilder("PT");
        if (hours > 0) {
            out.append(hours).append('H');
        }
        if (minutes > 0) {
            out.append(minutes).append('M');
        }
        out.append(String.format(Locale.ROOT, "%.3f", rest).replaceAll("\\.?0+$", "")).append('S');
        return out.toString();
    }

    private static Integer integer(final Directory directory, final int tag) {
        try {
            return directory.containsTag(tag) ? directory.getInteger(tag) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double decimal(final Directory directory, final int tag) {
        try {
            return directory.containsTag(tag) ? directory.getDoubleObject(tag) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static MetadataStatement integerStatement(final String predicate, final int value) {
        return MetadataStatement.literal(predicate, Integer.toString(value), null, XSD + "integer",
            MetadataStatement.Origin.FILE);
    }

    private static void text(final String value, final String predicate, final List<MetadataStatement> statements) {
        if (value != null && !value.isBlank() && value.strip().length() <= 2000) {
            statements.add(MetadataStatement.literal(predicate, value.strip(), null, null, MetadataStatement.Origin.FILE));
        }
    }

    private static void date(final Date date, final List<MetadataStatement> statements) {
        if (date != null) {
            statements.add(MetadataStatement.literal(Descriptions.DCTERMS + "created",
                DateTimeFormatter.ISO_INSTANT.format(date.toInstant().atOffset(ZoneOffset.UTC).withNano(0)),
                null, XSD + "dateTime", MetadataStatement.Origin.FILE));
        }
    }

    private static List<MetadataStatement> dedupe(final List<MetadataStatement> statements) {
        final List<MetadataStatement> unique = new ArrayList<>();
        for (final MetadataStatement statement : statements) {
            if (unique.stream().noneMatch(other -> other.sameAs(statement))) {
                unique.add(statement);
            }
        }
        return unique;
    }

    /**
     * What was read from a file.
     *
     * @param statements metadata statements (origin {@code FILE})
     * @param text       extracted text (PDFs), may be empty
     * @param notes      codes of what could not be read ({@code ENCRYPTED}, {@code TEXT_SHORTENED} ...)
     * @param pages      pages of a PDF, 0 otherwise
     */
    public record Facts(List<MetadataStatement> statements, String text, List<String> notes, int pages) {
    }

}
