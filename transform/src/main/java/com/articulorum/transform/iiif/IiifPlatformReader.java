package com.articulorum.transform.iiif;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Optional;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads resources from the platform on behalf of an IIIF export.
 *
 * <p>Requests go to the configured (internal) platform URL but carry {@code X-Forwarded-*}
 * headers describing the caller's public platform base, so every IRI in the returned RDF is the
 * public IRI the manifest must link to. The caller's {@code Authorization} header is passed on, so
 * Web Access Control applies.</p>
 */
@Component
public class IiifPlatformReader {

    private final RestClient client;

    private final String platformUrl;

    /**
     * Creates the reader.
     *
     * @param builder    HTTP client builder (load-balanced when configured)
     * @param properties IIIF settings
     */
    public IiifPlatformReader(final RestClient.Builder builder, final IiifProperties properties) {
        this.client = builder.build();
        this.platformUrl = properties.getPlatformUrl().endsWith("/")
            ? properties.getPlatformUrl()
            : properties.getPlatformUrl() + "/";
    }

    /**
     * Reads the RDF of a resource.
     *
     * @param path    the resource path
     * @param context the caller's view
     * @return the model (public IRIs), or empty when the resource does not exist
     * @throws IiifException when the platform refuses the request
     */
    public Optional<Model> rdf(final String path, final PublicContext context) {
        final byte[] body;
        try {
            body = client.get()
                .uri(URI.create(platformUrl + path))
                .headers(headers -> headers(headers, context))
                .accept(MediaType.parseMediaType("application/n-triples"))
                .retrieve()
                .body(byte[].class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw new IiifException(e.getStatusCode().value(), "The platform refused to read /" + path, e);
        } catch (RestClientException e) {
            throw new IiifException(502, "The platform is not reachable: " + e.getMessage(), e);
        }
        final Model model = ModelFactory.createDefaultModel();
        if (body != null) {
            RDFDataMgr.read(model, new ByteArrayInputStream(body), context.resourceIri(path), Lang.NTRIPLES);
        }
        return Optional.of(model);
    }

    /**
     * Reads the leading bytes of a binary.
     *
     * @param path    the binary's path
     * @param bytes   how many bytes to read
     * @param context the caller's view
     * @return the bytes, or empty when they cannot be read
     */
    public Optional<byte[]> head(final String path, final int bytes, final PublicContext context) {
        try {
            return Optional.ofNullable(client.get()
                .uri(URI.create(platformUrl + path))
                .headers(headers -> {
                    headers(headers, context);
                    headers.set(HttpHeaders.RANGE, "bytes=0-" + (bytes - 1));
                })
                .retrieve()
                .body(byte[].class));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    /**
     * Adds the forwarded headers and credentials of a request.
     *
     * @param headers the request headers
     * @param context the caller's view
     */
    static void headers(final HttpHeaders headers, final PublicContext context) {
        final URI base = URI.create(context.platformBase());
        final String scheme = base.getScheme() == null ? "http" : base.getScheme();
        headers.set("X-Forwarded-Proto", scheme);
        headers.set("X-Forwarded-Host", base.getHost());
        headers.set("X-Forwarded-Port", Integer.toString(base.getPort() >= 0 ? base.getPort()
            : "https".equals(scheme) ? 443 : 80));
        final String prefix = base.getRawPath();
        if (prefix != null && prefix.length() > 1) {
            headers.set("X-Forwarded-Prefix", prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        }
        if (StringUtils.hasText(context.authorization())) {
            headers.set(HttpHeaders.AUTHORIZATION, context.authorization());
        }
    }

}
