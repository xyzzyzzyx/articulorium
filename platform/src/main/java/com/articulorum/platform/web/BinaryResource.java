package com.articulorum.platform.web;

import java.io.IOException;
import java.io.InputStream;

import com.articulorum.domain.LdpResource;
import com.articulorum.platform.service.LdpService;

import org.springframework.core.io.AbstractResource;

/**
 * Spring {@link org.springframework.core.io.Resource} view of a non-RDF source's bytes.
 *
 * <p>Returning it from a controller lets Spring stream the bytes and serve {@code Range}
 * requests (206 Partial Content) without loading the binary into memory. The length comes from
 * the stored metadata, so the binary store is only contacted when the body is written.</p>
 */
public class BinaryResource extends AbstractResource {

    private final LdpService service;

    private final LdpResource resource;

    /**
     * Creates the view.
     *
     * @param service  the service used to open the bytes
     * @param resource the non-RDF source
     */
    public BinaryResource(final LdpService service, final LdpResource resource) {
        this.service = service;
        this.resource = resource;
    }

    @Override
    public String getDescription() {
        return "LDP non-RDF source " + resource.getRelativeIri();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return service.openBinary(resource);
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public boolean isReadable() {
        return true;
    }

    @Override
    public long contentLength() {
        return resource.getBinary() == null || resource.getBinary().getSize() == null ? 0L : resource.getBinary().getSize();
    }

    @Override
    public long lastModified() {
        return resource.getModified() == null ? 0L : resource.getModified().toEpochMilli();
    }

    @Override
    public String getFilename() {
        return resource.getBinary() == null ? null : resource.getBinary().getFilename();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof BinaryResource that && that.resource.equals(resource);
    }

    @Override
    public int hashCode() {
        return resource.hashCode();
    }

}
