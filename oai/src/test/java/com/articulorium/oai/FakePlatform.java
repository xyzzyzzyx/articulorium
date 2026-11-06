package com.articulorium.oai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.oai.config.OaiProperties;
import com.articulorium.oai.platform.PlatformClient;
import com.articulorium.oai.platform.PlatformView;
import com.articulorium.oai.platform.SearchHit;
import com.articulorium.oai.platform.SearchPage;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.springframework.web.client.RestClient;

/**
 * An in-memory platform with the search semantics the OAI-PMH service relies on: conditions on
 * {@code rdf_type}, {@code fedora_id} (exact or {@code /*}) and {@code modified}; results ordered by
 * modification time then path; paging by multiples of the (capped) page size, where an offset
 * inside a page only returns the rest of it; and hits the harvester may not read left out but
 * still counted.
 */
public class FakePlatform extends PlatformClient {

    private final Map<String, Resource> resources = new LinkedHashMap<>();

    private final Map<String, Instant> modified = new LinkedHashMap<>();

    private final Map<String, Set<String>> types = new LinkedHashMap<>();

    private final Set<String> hidden = new HashSet<>();

    private final List<List<String>> searches = new ArrayList<>();

    private int maxResults = 1000;

    private RuntimeException failure;

    /**
     * Makes every following call fail (or succeed again).
     *
     * @param failure the exception to throw, {@code null} to recover
     */
    public void fail(final RuntimeException failure) {
        this.failure = failure;
    }

    /**
     * Creates an empty platform.
     */
    public FakePlatform() {
        super(RestClient.builder(), new OaiProperties());
    }

    /**
     * Adds or replaces a resource.
     *
     * @param path        its path
     * @param when        its modification time
     * @param description its RDF description; the subject {@code <>} stands for the resource
     * @param typeIris    its types
     * @return this platform
     */
    public FakePlatform put(final String path, final Instant when, final Map<String, String> description,
            final String... typeIris) {
        final Model model = ModelFactory.createDefaultModel();
        final Resource subject = model.createResource("urn:fake:" + path);
        description.forEach((property, value) -> subject.addProperty(model.createProperty(property), value));
        resources.put(path, subject);
        modified.put(path, when);
        types.put(path, new LinkedHashSet<>(List.of(typeIris)));
        return this;
    }

    /**
     * Adds statements to the description of a resource.
     *
     * @param path        its path
     * @param description adds statements about the resource (subject of its stored description)
     */
    public void describe(final String path, final java.util.function.Consumer<Resource> description) {
        description.accept(resources.get(path));
    }

    /**
     * Changes the modification time of a resource.
     *
     * @param path the path
     * @param when the new time
     */
    public void touch(final String path, final Instant when) {
        modified.put(path, when);
    }

    /**
     * Removes a resource.
     *
     * @param path the path
     */
    public void remove(final String path) {
        resources.remove(path);
        modified.remove(path);
        types.remove(path);
    }

    /**
     * Hides a resource from the harvester (as Web Access Control would).
     *
     * @param path the path
     */
    public void hide(final String path) {
        hidden.add(path);
    }

    /**
     * Caps the page size, as the platform's {@code max-search-results} does.
     *
     * @param max the cap
     */
    public void capResults(final int max) {
        this.maxResults = max;
    }

    /** @return the conditions of every search so far */
    public List<List<String>> searches() {
        return searches;
    }

    @Override
    public SearchPage search(final PlatformView view, final List<String> conditions, final int max,
            final int offset) {
        if (failure != null) {
            throw failure;
        }
        searches.add(List.copyOf(conditions));
        Predicate<String> filter = path -> true;
        for (final String condition : conditions) {
            filter = filter.and(predicate(condition));
        }
        final List<String> matches = modified.keySet().stream().filter(filter)
            .sorted(Comparator.comparing((String path) -> modified.get(path)).thenComparing(path -> path))
            .toList();
        final int size = Math.max(1, Math.min(max, maxResults));
        final int page = offset / size;
        final List<SearchHit> hits = matches.stream()
            .skip((long) page * size).limit(size).skip(offset % size)
            .filter(path -> !hidden.contains(path))
            .map(path -> new SearchHit(path, modified.get(path), types.get(path)))
            .toList();
        return new SearchPage(hits, matches.size(), size);
    }

    @Override
    public Optional<Model> rdf(final PlatformView view, final String path) {
        if (failure != null) {
            throw failure;
        }
        final Resource stored = resources.get(path);
        if (stored == null || hidden.contains(path)) {
            return Optional.empty();
        }
        final Model model = ModelFactory.createDefaultModel();
        final Resource subject = model.createResource(view.iri(path));
        stored.listProperties().forEach(statement -> subject.addProperty(statement.getPredicate(),
            statement.getObject()));
        types.get(path).forEach(type -> subject.addProperty(org.apache.jena.vocabulary.RDF.type,
            model.createResource(type)));
        return Optional.of(model);
    }

    private Predicate<String> predicate(final String condition) {
        if (condition.startsWith("rdf_type=")) {
            final String type = unescape(condition.substring("rdf_type=".length()));
            return path -> types.get(path).contains(type);
        }
        if (condition.startsWith("fedora_id<=info:fedora/")) {
            final String bound = unescape(condition.substring("fedora_id<=info:fedora/".length()));
            return path -> path.compareTo(bound) <= 0;
        }
        if (condition.startsWith("modified=")) {
            final Instant at = Instant.parse(condition.substring("modified=".length()));
            return path -> modified.get(path).equals(at);
        }
        if (condition.startsWith("fedora_id=info:fedora/")) {
            final String value = condition.substring("fedora_id=info:fedora/".length());
            if (value.endsWith("/*") && !value.endsWith("\\*")) {
                final String prefix = unescape(value.substring(0, value.length() - 1));
                return path -> path.startsWith(prefix);
            }
            final String exact = ResourcePaths.normalize(unescape(value));
            return path -> path.equals(exact);
        }
        if (condition.startsWith("modified>=")) {
            final Instant bound = Instant.parse(condition.substring("modified>=".length()));
            return path -> !modified.get(path).isBefore(bound);
        }
        if (condition.startsWith("modified<")) {
            final Instant bound = Instant.parse(condition.substring("modified<".length()));
            return path -> modified.get(path).isBefore(bound);
        }
        throw new IllegalArgumentException("Unsupported condition: " + condition);
    }

    private static String unescape(final String value) {
        return value.replaceAll("\\\\(.)", "$1");
    }

}
