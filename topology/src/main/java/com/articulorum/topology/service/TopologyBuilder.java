package com.articulorum.topology.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorum.domain.rdf.NTriplesSerializer;
import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Articulorum;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Pcdm;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.topology.config.TopologyProperties;
import com.articulorum.topology.model.AddMemberRequest;
import com.articulorum.topology.model.BuildRequest;
import com.articulorum.topology.model.BuildResult;
import com.articulorum.topology.model.ChildRule;
import com.articulorum.topology.model.MetadataField;
import com.articulorum.topology.model.Topology;
import com.articulorum.topology.model.TopologyNode;
import com.articulorum.topology.platform.PlatformClient;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds the PCDM resources of a topology in the repository, following the PCDM-in-LDP pattern
 * used by Fedora and Samvera:
 *
 * <pre>
 * book                    ldp:BasicContainer   a pcdm:Object, bibo:Book ; dcterms:title "..." ;
 *                                              art:topology "book" ; art:topologyNode "book"
 * book/members            ldp:DirectContainer  ldp:membershipResource &lt;book&gt; ;
 *                                              ldp:hasMemberRelation pcdm:hasMember
 * book/members/page-1     ldp:BasicContainer   a pcdm:Object ; schema:position 1 ; ...
 * book/members/page-1/files  ldp:DirectContainer  ldp:hasMemberRelation pcdm:hasFile
 * </pre>
 *
 * <p>Every resource records the topology and node it instantiates ({@code art:topology},
 * {@code art:topologyNode}), so the user interface can offer the right children, files and
 * metadata fields later. Members are ordered with {@code schema:position}.</p>
 */
@Slf4j
@Service
public class TopologyBuilder {

    /** Name of the container of a resource's PCDM members. */
    public static final String MEMBERS = "members";

    /** Name of the container of a resource's PCDM files. */
    public static final String FILES = "files";

    /** {@code dcterms:title}. */
    static final String TITLE = "http://purl.org/dc/terms/title";

    /** {@code schema:position}, the order of a member among its siblings. */
    static final String POSITION = "http://schema.org/position";

    /** {@code xsd:integer}. */
    static final String XSD_INTEGER = Rdf.XSD_NAMESPACE + "integer";

    private final PlatformClient platform;

    private final TopologyProperties properties;

    /**
     * Creates the builder.
     *
     * @param platform   the platform client
     * @param properties topology settings
     */
    public TopologyBuilder(final PlatformClient platform, final TopologyProperties properties) {
        this.platform = platform;
        this.properties = properties;
    }

    /**
     * Builds a topology instance.
     *
     * @param topology      the topology
     * @param request       where and what to build
     * @param authorization the caller's {@code Authorization} header
     * @return the created resources
     */
    public BuildResult build(final Topology topology, final BuildRequest request, final String authorization) {
        validateCounts(topology.root(), request.counts());
        final List<String> created = new ArrayList<>();
        final String path = createNode(topology, topology.root(), ResourcePaths.normalize(request.parent()),
            request.slug() == null || request.slug().isBlank() ? slugOf(request.title()) : request.slug(),
            request.title(), request.metadata(), null, request.counts(), created, authorization);
        log.info("Built {} instance /{} ({} resources)", topology.id(), path, created.size());
        return new BuildResult(path, topology.id(), topology.root().key(), created);
    }

    /**
     * Adds one child (with its own default children) to a resource built from a topology.
     *
     * @param topology      the topology
     * @param request       the parent and the child's node
     * @param authorization the caller's {@code Authorization} header
     * @return the created resources
     * @throws InvalidTopologyException when the parent was not built from the topology, or the
     *                                  child node is not a child of the parent's node, or the
     *                                  maximum number of such children is reached
     */
    public BuildResult addMember(final Topology topology, final AddMemberRequest request, final String authorization) {
        final String parent = ResourcePaths.normalize(request.parent());
        final Model model = platform.rdf(parent, authorization)
            .orElseThrow(() -> new InvalidTopologyException(List.of("/" + parent + " does not exist")));
        final Resource subject = model.createResource(platform.url(parent));
        final String topologyId = literal(subject, Articulorum.TOPOLOGY).orElse(null);
        final String nodeKey = literal(subject, Articulorum.TOPOLOGY_NODE).orElse(null);
        if (!topology.id().equals(topologyId) || nodeKey == null) {
            throw new InvalidTopologyException(List.of("/" + parent + " was not built from the topology " + topology.id()));
        }
        final TopologyNode parentNode = topology.root().find(nodeKey)
            .orElseThrow(() -> new InvalidTopologyException(List.of("The topology " + topology.id()
                + " has no node " + nodeKey)));
        final ChildRule rule = parentNode.childRule(request.nodeKey())
            .orElseThrow(() -> new InvalidTopologyException(List.of(request.nodeKey() + " is not a child of "
                + parentNode.key() + " in " + topology.id())));
        final String members = ResourcePaths.child(parent, MEMBERS);
        final Optional<Model> membersModel = platform.rdf(members, authorization);
        if (membersModel.isEmpty()) {
            createMembersContainer(parent, authorization);
        }
        final int existing = membersModel.map(m -> m.listObjectsOfProperty(m.createProperty(Ldp.CONTAINS)).toList().size())
            .orElse(0);
        if (rule.max() != ChildRule.UNLIMITED && existing >= rule.max()) {
            throw new InvalidTopologyException(List.of(parentNode.label() + " already has the maximum of " + rule.max()
                + " " + rule.node().label()));
        }
        final int position = existing + 1;
        final String title = request.title() == null || request.title().isBlank()
            ? rule.node().label() + " " + position
            : request.title();
        final List<String> created = new ArrayList<>();
        final String path = createNode(topology, rule.node(), members, rule.slug(position), title, request.metadata(),
            position, Map.of(), created, authorization);
        return new BuildResult(path, topology.id(), rule.node().key(), created);
    }

    private String createNode(final Topology topology, final TopologyNode node, final String parent, final String slug,
            final String title, final Map<String, List<String>> metadata, final Integer position,
            final Map<String, Integer> counts, final List<String> created, final String authorization) {
        final String path = platform.create(parent, slug, turtle(topology, node, title, metadata, position),
            List.of(Ldp.BASIC_CONTAINER), authorization);
        created.add(path);
        if (!node.children().isEmpty()) {
            final String members = createMembersContainer(path, authorization);
            created.add(members);
            for (final ChildRule rule : node.children()) {
                final int count = rule.count(counts.get(rule.node().key()));
                for (int i = 1; i <= count; i++) {
                    createNode(topology, rule.node(), members, rule.slug(i), rule.node().label() + " " + i, Map.of(), i,
                        counts, created, authorization);
                }
            }
        }
        if (!node.files().isEmpty()) {
            final String files = ResourcePaths.child(path, FILES);
            platform.put(files, directContainer(path, Pcdm.HAS_FILE, "Files").getBytes(StandardCharsets.UTF_8),
                "text/turtle", List.of(Ldp.DIRECT_CONTAINER), authorization);
            created.add(files);
        }
        return path;
    }

    private String createMembersContainer(final String path, final String authorization) {
        final String members = ResourcePaths.child(path, MEMBERS);
        platform.put(members, directContainer(path, Pcdm.HAS_MEMBER, "Members").getBytes(StandardCharsets.UTF_8),
            "text/turtle", List.of(Ldp.DIRECT_CONTAINER), authorization);
        return members;
    }

    /**
     * Builds the Turtle of a direct container below a resource. The membership resource is
     * written relative to the container ({@code <../name>}), so it resolves to the parent however
     * the platform is addressed.
     *
     * @param owner    path of the resource owning the container
     * @param relation the membership predicate
     * @param title    the container's title
     * @return the Turtle
     */
    static String directContainer(final String owner, final String relation, final String title) {
        return "<> " + NTriplesSerializer.iri(Ldp.MEMBERSHIP_RESOURCE) + " <../" + ResourcePaths.nameOf(owner) + "> ;\n"
            + "   " + NTriplesSerializer.iri(Ldp.HAS_MEMBER_RELATION) + " " + NTriplesSerializer.iri(relation) + " ;\n"
            + "   " + NTriplesSerializer.iri(TITLE) + " " + NTriplesSerializer.literal(title, null, null) + " .\n";
    }

    /**
     * Builds the Turtle of a topology node's resource.
     *
     * @param topology the topology
     * @param node     the node
     * @param title    the title
     * @param metadata values by predicate (default values fill missing fields)
     * @param position the position among siblings, {@code null} for the root
     * @return the Turtle ({@code <>} is the resource)
     */
    static String turtle(final Topology topology, final TopologyNode node, final String title,
            final Map<String, List<String>> metadata, final Integer position) {
        final StringBuilder out = new StringBuilder();
        final String pcdmType = TopologyNode.COLLECTION.equals(node.pcdmType()) ? Pcdm.COLLECTION : Pcdm.OBJECT;
        statement(out, Rdf.TYPE, NTriplesSerializer.iri(pcdmType));
        for (final String type : node.types()) {
            statement(out, Rdf.TYPE, NTriplesSerializer.iri(type));
        }
        statement(out, TITLE, NTriplesSerializer.literal(title, null, null));
        statement(out, Articulorum.TOPOLOGY, NTriplesSerializer.literal(topology.id(), null, null));
        statement(out, Articulorum.TOPOLOGY_NODE, NTriplesSerializer.literal(node.key(), null, null));
        if (position != null) {
            statement(out, POSITION, NTriplesSerializer.literal(position.toString(), XSD_INTEGER, null));
        }
        for (final MetadataField field : node.metadata()) {
            if (TITLE.equals(field.predicate())) {
                continue;
            }
            final List<String> values = metadata.getOrDefault(field.predicate(),
                field.defaultValue() == null || field.defaultValue().isBlank() ? List.of() : List.of(field.defaultValue()));
            for (final String value : values) {
                if (value != null && !value.isBlank()) {
                    statement(out, field.predicate(), NTriplesSerializer.literal(value,
                        field.datatype() == null || field.datatype().isBlank() ? null : field.datatype(), null));
                }
            }
        }
        return out.toString();
    }

    private static void statement(final StringBuilder out, final String predicate, final String object) {
        out.append("<> ").append(NTriplesSerializer.iri(predicate)).append(' ').append(object).append(" .\n");
    }

    private void validateCounts(final TopologyNode node, final Map<String, Integer> counts) {
        final List<String> errors = new ArrayList<>();
        for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (node.find(entry.getKey()).isEmpty()) {
                errors.add("Unknown node " + entry.getKey());
            } else if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > properties.getMaxChildren()) {
                errors.add("The count of " + entry.getKey() + " must be between 0 and " + properties.getMaxChildren());
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidTopologyException(errors);
        }
    }

    /**
     * Derives a resource name from a title: lower case, runs of other characters replaced with
     * {@code -}, for example {@code moby-dick} for {@code Moby Dick}.
     *
     * @param title the title
     * @return the name, or the title itself when nothing remains (the platform then mints one)
     */
    static String slugOf(final String title) {
        final String slug = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? title : slug.substring(0, Math.min(slug.length(), 80));
    }

    private static Optional<String> literal(final Resource subject, final String predicate) {
        final Statement statement = subject.getProperty(subject.getModel().createProperty(predicate));
        return statement == null || !statement.getObject().isLiteral()
            ? Optional.empty()
            : Optional.of(statement.getObject().asLiteral().getLexicalForm());
    }

}
