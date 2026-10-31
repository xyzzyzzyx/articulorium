package com.articulorum.platform.search;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.TermKind;
import com.articulorum.domain.Triple;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.config.LdpProperties;
import com.articulorum.platform.ldp.exception.BadRequestException;
import com.articulorum.platform.repository.LdpResourceRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Implements the Fedora 6 search API ({@code GET /fcr:search}) over the platform database.
 *
 * <p>Conditions on {@code fedora_id}, {@code created}, {@code modified}, {@code content_size},
 * {@code mime_type} and {@code rdf_type} are combined with AND. {@code fedora_id} accepts absolute
 * IRIs below the request base, server-relative IRIs, plain paths and Fedora style
 * {@code info:fedora/...} identifiers.</p>
 */
@Service
public class SearchService {

    /** Default page size. */
    public static final int DEFAULT_MAX_RESULTS = 100;

    private static final String INFO_FEDORA = "info:fedora/";

    private final LdpResourceRepository repository;

    private final LdpProperties properties;

    /**
     * A search request.
     *
     * @param conditions the conditions, combined with AND
     * @param maxResults the page size
     * @param offset     the number of results to skip
     * @param orderBy    the field to sort by, may be {@code null} for {@code fedora_id}
     * @param ascending  the sort direction
     */
    public record SearchRequest(List<SearchCondition> conditions, int maxResults, int offset, SearchField orderBy,
            boolean ascending) {
    }

    /**
     * A page of search results, shaped like Fedora's search response.
     *
     * @param items      the matching resources, each a map of field name to value
     * @param pagination paging information
     */
    public record SearchResult(List<Map<String, Object>> items, Pagination pagination) {
    }

    /**
     * Paging information of a search result.
     *
     * @param maxResults    the page size
     * @param offset        the number of skipped results
     * @param totalResults  the number of matching resources
     * @param pageNumber    the zero-based page number
     * @param numberOfPages the number of pages
     */
    public record Pagination(int maxResults, int offset, long totalResults, int pageNumber, int numberOfPages) {
    }

    /**
     * Creates the service.
     *
     * @param repository the resource repository
     * @param properties LDP settings (search limits)
     */
    public SearchService(final LdpResourceRepository repository, final LdpProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Runs a search.
     *
     * @param request the request
     * @param base    the server base of the HTTP request, used to read and write {@code fedora_id}s
     * @return the result page
     * @throws BadRequestException when a condition value cannot be interpreted
     */
    @Transactional(readOnly = true)
    public SearchResult search(final SearchRequest request, final String base) {
        final int maxResults = Math.max(1, Math.min(request.maxResults(), properties.getMaxSearchResults()));
        final int offset = Math.max(0, request.offset());
        Specification<LdpResource> specification = (root, query, cb) -> cb.conjunction();
        for (final SearchCondition condition : request.conditions()) {
            final Specification<LdpResource> next = toSpecification(condition, base);
            specification = specification.and(next);
        }
        final SearchField orderBy = request.orderBy() == null || request.orderBy() == SearchField.RDF_TYPE
            ? SearchField.FEDORA_ID
            : request.orderBy();
        final Sort sort = Sort.by(request.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, orderBy.attribute());
        final int page = offset / maxResults;
        final Page<LdpResource> results = repository.findAll(specification, PageRequest.of(page, maxResults, sort));
        final List<Map<String, Object>> items = new ArrayList<>();
        // an offset that is not a multiple of the page size skips the start of the page
        results.getContent().stream().skip(offset % maxResults).forEach(resource -> items.add(toItem(resource, base)));
        final long total = results.getTotalElements();
        return new SearchResult(items, new Pagination(maxResults, offset, total, page,
            (int) ((total + maxResults - 1) / maxResults)));
    }

    /**
     * Describes a resource as a search result item.
     *
     * @param resource the resource
     * @param base     the server base
     * @return the item
     */
    static Map<String, Object> toItem(final LdpResource resource, final String base) {
        final Map<String, Object> item = new LinkedHashMap<>();
        item.put(SearchField.FEDORA_ID.parameterName(), Iris.resolve(base, resource.getRelativeIri()));
        item.put(SearchField.CREATED.parameterName(), resource.getCreated());
        item.put(SearchField.MODIFIED.parameterName(), resource.getModified());
        item.put(SearchField.CONTENT_SIZE.parameterName(),
            resource.getBinary() == null ? null : resource.getBinary().getSize());
        item.put(SearchField.MIME_TYPE.parameterName(),
            resource.getBinary() == null ? null : resource.getBinary().getContentType());
        item.put(SearchField.RDF_TYPE.parameterName(), typesOf(resource, base));
        return item;
    }

    /**
     * Lists every {@code rdf:type} of a resource: its LDP and Fedora types and the types among its
     * client-managed triples.
     *
     * @param resource the resource
     * @param base     the server base, to resolve relative type IRIs
     * @return the type IRIs
     */
    static List<String> typesOf(final LdpResource resource, final String base) {
        final List<String> types = new ArrayList<>(serverTypes(resource.getInteractionModel()));
        for (final Triple triple : resource.getTriples()) {
            if (triple.matches(resource.getRelativeIri(), Rdf.TYPE) && triple.getObjectKind() == TermKind.IRI
                    && !types.contains(triple.getObject())) {
                types.add(Iris.resolve(base, triple.getObject()));
            }
        }
        return types;
    }

    /**
     * Lists the server-managed types of every resource with an interaction model.
     *
     * @param model the interaction model
     * @return the LDP and Fedora type IRIs
     */
    static List<String> serverTypes(final InteractionModel model) {
        final List<String> types = new ArrayList<>(model.getTypeIris());
        types.add(Fedora.RESOURCE);
        if (model.isContainer()) {
            types.add(Fedora.CONTAINER);
        }
        if (model == InteractionModel.NON_RDF_SOURCE) {
            types.add(Fedora.BINARY);
        }
        return types;
    }

    private Specification<LdpResource> toSpecification(final SearchCondition condition, final String base) {
        return switch (condition.field()) {
            case FEDORA_ID -> stringCondition(condition, "path", toPathValue(condition.value(), base));
            case MIME_TYPE -> stringCondition(condition, "binary.contentType", condition.value());
            case CREATED, MODIFIED -> comparison(condition, condition.field().attribute(), parseInstant(condition.value()));
            case CONTENT_SIZE -> comparison(condition, "binary.size", parseLong(condition.value()));
            case RDF_TYPE -> rdfType(condition.value());
        };
    }

    /**
     * Maps a {@code fedora_id} condition value to a resource path (pattern).
     *
     * @param value the value as sent
     * @param base  the server base
     * @return the corresponding path, still containing wildcards
     */
    static String toPathValue(final String value, final String base) {
        String path = value;
        if (path.startsWith(INFO_FEDORA)) {
            path = path.substring(INFO_FEDORA.length());
        } else if (Iris.isAbsolute(path)) {
            final String relative = Iris.relativize(base, path);
            path = relative.equals(path) ? path : relative;
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static Specification<LdpResource> stringCondition(final SearchCondition condition, final String attribute,
            final String value) {
        return (root, query, cb) -> {
            final Expression<String> expression = attributePath(root, attribute);
            if (condition.isPattern()) {
                return cb.like(expression, SearchCondition.toLikePattern(value), '\\');
            }
            final String literal = SearchCondition.unescape(value);
            return switch (condition.operator()) {
                case EQUAL -> cb.equal(expression, literal);
                case LESS -> cb.lessThan(expression, literal);
                case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(expression, literal);
                case GREATER -> cb.greaterThan(expression, literal);
                case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(expression, literal);
            };
        };
    }

    private static <C extends Comparable<? super C>> Specification<LdpResource> comparison(
            final SearchCondition condition, final String attribute, final C value) {
        return (root, query, cb) -> {
            final Expression<C> expression = attributePath(root, attribute);
            return switch (condition.operator()) {
                case EQUAL -> cb.equal(expression, value);
                case LESS -> cb.lessThan(expression, value);
                case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(expression, value);
                case GREATER -> cb.greaterThan(expression, value);
                case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(expression, value);
            };
        };
    }

    /**
     * Matches resources with an {@code rdf:type}: either a server-managed type implied by the
     * interaction model, or a stored client type.
     */
    private static Specification<LdpResource> rdfType(final String value) {
        final Pattern pattern = wildcardRegex(value);
        final Set<InteractionModel> models = EnumSet.noneOf(InteractionModel.class);
        for (final InteractionModel model : InteractionModel.values()) {
            if (serverTypes(model).stream().anyMatch(type -> pattern.matcher(type).matches())) {
                models.add(model);
            }
        }
        final boolean wildcard = new SearchCondition(SearchField.RDF_TYPE, SearchCondition.Operator.EQUAL, value)
            .isPattern();
        return (root, query, cb) -> {
            final Subquery<UUID> typed = query.subquery(UUID.class);
            final Root<LdpResource> candidate = typed.from(LdpResource.class);
            final Join<LdpResource, Triple> triple = candidate.join("triples");
            final Expression<String> object = triple.get("object");
            typed.select(candidate.get("id")).where(
                cb.equal(candidate.get("id"), root.get("id")),
                cb.equal(triple.get("predicate"), Rdf.TYPE),
                cb.equal(triple.get("objectKind"), TermKind.IRI),
                wildcard
                    ? cb.like(object, SearchCondition.toLikePattern(value), '\\')
                    : cb.equal(object, SearchCondition.unescape(value)));
            final List<Predicate> alternatives = new ArrayList<>();
            alternatives.add(cb.exists(typed));
            if (!models.isEmpty()) {
                alternatives.add(root.get("interactionModel").in(models));
            }
            return cb.or(alternatives.toArray(Predicate[]::new));
        };
    }

    /**
     * Converts a value with {@code *} wildcards into an anchored regular expression.
     *
     * @param value the value
     * @return the expression
     */
    static Pattern wildcardRegex(final String value) {
        final StringBuilder regex = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                regex.append(Pattern.quote(String.valueOf(value.charAt(++i))));
            } else if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> Path<T> attributePath(final Root<LdpResource> root, final String attribute) {
        Path<?> path = root;
        for (final String part : attribute.split("\\.")) {
            path = path.get(part);
        }
        return (Path<T>) path;
    }

    /**
     * Parses a date condition value: an ISO-8601 instant ({@code 2026-10-30T12:00:00Z}) or a date
     * ({@code 2026-10-30}, meaning its start in UTC).
     *
     * @param value the value
     * @return the instant
     * @throws BadRequestException when the value is not a date
     */
    static Instant parseInstant(final String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                throw new BadRequestException("Not an ISO-8601 date or instant: " + value, e);
            }
        }
    }

    private static Long parseLong(final String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Not a number: " + value, e);
        }
    }

}
