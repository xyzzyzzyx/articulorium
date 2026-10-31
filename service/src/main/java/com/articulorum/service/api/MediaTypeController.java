package com.articulorum.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Detects the media type of a file with Apache Tika, so the user interface can propose an
 * accurate {@code Content-Type} before it uploads a binary to the Linked Data Platform.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Media types", description = "Media type detection with Apache Tika")
public class MediaTypeController {

    private final Tika tika;

    /**
     * Creates the controller.
     *
     * @param tika the Tika facade
     */
    public MediaTypeController(final Tika tika) {
        this.tika = tika;
    }

    /**
     * The result of a detection.
     *
     * @param filename  the uploaded file name, may be {@code null}
     * @param mediaType the detected media type
     * @param size      the size of the uploaded file in bytes
     */
    public record Detection(String filename, String mediaType, long size) {
    }

    /**
     * Detects the media type of an uploaded file from its leading bytes and its name.
     *
     * @param file the file ({@code multipart/form-data} part {@code file})
     * @return the detection result
     */
    @PostMapping(path = "/media-type", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "detectMediaType", summary = "Detect the media type of a file")
    public Detection detect(@RequestParam("file") final MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Select a file to analyse");
        }
        final Metadata metadata = new Metadata();
        if (file.getOriginalFilename() != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
        }
        try (InputStream in = file.getInputStream()) {
            final String mediaType = tika.detect(in, metadata);
            return new Detection(file.getOriginalFilename(), mediaType, file.getSize());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                "Unable to read the file: " + e.getMessage(), e);
        }
    }

}
