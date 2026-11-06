package com.articulorium.topology.service;

import java.util.Locale;

import com.articulorium.i18n.Messages;
import com.articulorium.topology.model.ChildRule;
import com.articulorium.topology.model.FileUse;
import com.articulorium.topology.model.MetadataField;
import com.articulorium.topology.model.Topology;
import com.articulorium.topology.model.TopologyNode;

import org.springframework.stereotype.Component;

/**
 * Translates the built-in topologies into the request's locale for display.
 *
 * <p>Names and descriptions are looked up by topology id ({@code topology.<id>.name}), labels of
 * levels, metadata fields and file kinds by the English label ({@code label.<slug>}, see
 * {@link #slug(String)}). Custom topologies are the users' own data and keep their labels, as do
 * labels without a translation.</p>
 *
 * <p>Only the representations returned to clients are translated: building instances uses the
 * canonical topology, so the repository's resources do not depend on who created them.</p>
 */
@Component
public class TopologyLocalizer {

    private final Messages messages;

    /**
     * Creates the localizer.
     *
     * @param messages the texts in the request's locale
     */
    public TopologyLocalizer(final Messages messages) {
        this.messages = messages;
    }

    /**
     * Translates a topology into the current locale.
     *
     * @param topology the topology
     * @return the translated copy for built-in topologies, otherwise the topology itself
     */
    public Topology localize(final Topology topology) {
        if (topology == null || !topology.builtIn()) {
            return topology;
        }
        final Locale locale = messages.locale();
        return new Topology(topology.id(),
            text(locale, "topology." + topology.id() + ".name", topology.name()),
            text(locale, "topology." + topology.id() + ".description", topology.description()),
            true, node(locale, topology.root()));
    }

    private TopologyNode node(final Locale locale, final TopologyNode node) {
        if (node == null) {
            return null;
        }
        return new TopologyNode(node.key(), label(locale, node.label()), node.pcdmType(), node.types(),
            node.metadata().stream().map(field -> new MetadataField(field.predicate(), label(locale, field.label()),
                field.required(), field.repeatable(), field.defaultValue(), field.datatype())).toList(),
            node.files().stream().map(file -> new FileUse(file.use(), label(locale, file.label()), file.mediaTypes()))
                .toList(),
            node.children().stream().map(rule -> new ChildRule(rule.min(), rule.max(), rule.initial(),
                rule.slugPattern(), node(locale, rule.node()))).toList());
    }

    private String label(final Locale locale, final String label) {
        return label == null ? null : text(locale, "label." + slug(label), label);
    }

    private String text(final Locale locale, final String code, final String fallback) {
        if (fallback == null) {
            return null;
        }
        final String text = messages.get(locale, code);
        return code.equals(text) ? fallback : text;
    }

    /**
     * The message key of a label: lower case, every run of other characters replaced by {@code -}.
     *
     * @param label for example {@code ISBN-13} or {@code Issue date}
     * @return for example {@code isbn-13} or {@code issue-date}
     */
    static String slug(final String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

}
