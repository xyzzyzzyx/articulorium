package com.articulorium.oai.protocol;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A syntactically valid OAI-PMH request: a known verb with legal arguments (section 3.1).
 *
 * @param verb      the verb
 * @param arguments the arguments other than the verb, by name
 */
public record OaiRequest(OaiVerb verb, Map<String, String> arguments) {

    /**
     * Copies the arguments.
     *
     * @param verb      the verb
     * @param arguments the arguments
     */
    public OaiRequest {
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * Returns an argument.
     *
     * @param name the argument name
     * @return its value, or {@code null} when absent
     */
    public String argument(final String name) {
        return arguments.get(name);
    }

    /**
     * Parses request parameters (query string or form body).
     *
     * @param parameters the parameters, each with every value sent
     * @return the request
     * @throws OaiException {@link OaiError#BAD_VERB} for a missing, repeated or unknown verb and
     *                      {@link OaiError#BAD_ARGUMENT} for repeated, unknown, missing or empty
     *                      arguments
     */
    public static OaiRequest parse(final Map<String, String[]> parameters) {
        final String[] verbs = parameters.get(OaiArguments.VERB);
        if (verbs == null || verbs.length == 0) {
            throw new OaiException(OaiError.BAD_VERB, "The verb argument is missing");
        }
        if (verbs.length > 1) {
            throw new OaiException(OaiError.BAD_VERB, "The verb argument is repeated");
        }
        final OaiVerb verb = OaiVerb.of(verbs[0])
            .orElseThrow(() -> new OaiException(OaiError.BAD_VERB, "Illegal OAI verb: " + verbs[0]));
        final Map<String, String> arguments = new LinkedHashMap<>();
        for (final Map.Entry<String, String[]> parameter : parameters.entrySet()) {
            final String name = parameter.getKey();
            if (OaiArguments.VERB.equals(name)) {
                continue;
            }
            if (!accepts(verb, name)) {
                throw new OaiException(OaiError.BAD_ARGUMENT, "Illegal argument for " + verb.value() + ": " + name);
            }
            if (parameter.getValue().length != 1) {
                throw new OaiException(OaiError.BAD_ARGUMENT, "The " + name + " argument is repeated");
            }
            final String value = parameter.getValue()[0];
            if (value == null || value.isBlank()) {
                throw new OaiException(OaiError.BAD_ARGUMENT, "The " + name + " argument is empty");
            }
            arguments.put(name, value);
        }
        if (arguments.containsKey(OaiArguments.RESUMPTION_TOKEN)) {
            if (arguments.size() > 1) {
                throw new OaiException(OaiError.BAD_ARGUMENT,
                    "The resumptionToken argument is exclusive: no other argument may accompany it");
            }
        } else {
            final Set<String> missing = new HashSet<>(verb.required());
            missing.removeAll(arguments.keySet());
            if (!missing.isEmpty()) {
                throw new OaiException(OaiError.BAD_ARGUMENT, verb.value() + " requires the "
                    + String.join(", ", missing.stream().sorted().toList()) + " argument");
            }
        }
        return new OaiRequest(verb, arguments);
    }

    private static boolean accepts(final OaiVerb verb, final String name) {
        return verb.required().contains(name) || verb.optional().contains(name)
            || verb.resumable() && OaiArguments.RESUMPTION_TOKEN.equals(name);
    }

}
