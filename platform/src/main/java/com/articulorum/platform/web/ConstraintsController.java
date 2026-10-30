package com.articulorum.platform.web;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Publishes the server's constraints document, the target of every
 * {@code Link: rel="http://www.w3.org/ns/ldp#constrainedBy"} header (LDP 4.2.1.6).
 */
@RestController
@Tag(name = "Linked Data Platform")
public class ConstraintsController {

    /** Path of the constraints document, relative to the server base. */
    public static final String PATH = "/.well-known/ldp-constraints";

    /**
     * Returns the constraints document.
     *
     * @return the plain text document
     */
    @GetMapping(PATH)
    @Operation(operationId = "constraints", summary = "Describe the constraints the server imposes on clients",
        description = "Target of every `Link: rel=\"http://www.w3.org/ns/ldp#constrainedBy\"` header.")
    @ApiResponse(responseCode = "200", description = "The constraints document",
        content = @Content(mediaType = "text/plain"))
    public ResponseEntity<Resource> constraints() {
        return ResponseEntity.ok()
            .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
            .body(new ClassPathResource("ldp-constraints.txt"));
    }

}
