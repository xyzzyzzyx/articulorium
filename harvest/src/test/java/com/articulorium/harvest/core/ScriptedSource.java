package com.articulorium.harvest.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * A source answering scripted pages or failures, recording what it was asked.
 */
final class ScriptedSource implements HarvestSource {

    private final String id;

    private final SourceCapabilities capabilities;

    private final Deque<Supplier<HarvestPage>> script = new ArrayDeque<>();

    private final List<HarvestWindow> windows = new ArrayList<>();

    private final List<String> cursors = new ArrayList<>();

    ScriptedSource(final String id, final SourceCapabilities capabilities) {
        this.id = id;
        this.capabilities = capabilities;
    }

    ScriptedSource then(final HarvestPage page) {
        script.add(() -> page);
        return this;
    }

    ScriptedSource thenFail(final HarvestException failure) {
        script.add(() -> {
            throw failure;
        });
        return this;
    }

    List<HarvestWindow> windows() {
        return windows;
    }

    List<String> cursors() {
        return cursors;
    }

    static HarvestedRecord record(final String identifier) {
        final Model model = ModelFactory.createDefaultModel();
        model.createResource("https://source.test/" + identifier)
            .addProperty(model.createProperty("http://purl.org/dc/terms/title"), "Title of " + identifier);
        return HarvestedRecord.of(identifier, "https://source.test/" + identifier, null, model);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return "scripted";
    }

    @Override
    public SourceCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public HarvestPage fetch(final HarvestWindow window, final String cursor) {
        windows.add(window);
        cursors.add(cursor);
        if (script.isEmpty()) {
            throw new IllegalStateException("Unexpected fetch with cursor " + cursor);
        }
        return script.poll().get();
    }

}
