package com.articulorium.activitystreams;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The types of the Activity Vocabulary: core types, activity types, actor types, object types
 * and link types.
 *
 * <p>Types are written as bare terms in documents ({@code "Note"}) but are also recognised as
 * compact ({@code "as:Note"}) and full IRIs
 * ({@code "https://www.w3.org/ns/activitystreams#Note"}).</p>
 *
 * @see <a href="https://www.w3.org/TR/activitystreams-vocabulary/#types">Activity Vocabulary</a>
 */
public enum AsType {

    // core types
    OBJECT("Object", Kind.OBJECT),
    LINK("Link", Kind.LINK),
    ACTIVITY("Activity", Kind.ACTIVITY),
    INTRANSITIVE_ACTIVITY("IntransitiveActivity", Kind.INTRANSITIVE_ACTIVITY),
    COLLECTION("Collection", Kind.COLLECTION),
    ORDERED_COLLECTION("OrderedCollection", Kind.COLLECTION),
    COLLECTION_PAGE("CollectionPage", Kind.COLLECTION),
    ORDERED_COLLECTION_PAGE("OrderedCollectionPage", Kind.COLLECTION),

    // activity types
    ACCEPT("Accept", Kind.ACTIVITY),
    ADD("Add", Kind.ACTIVITY),
    ANNOUNCE("Announce", Kind.ACTIVITY),
    ARRIVE("Arrive", Kind.INTRANSITIVE_ACTIVITY),
    BLOCK("Block", Kind.ACTIVITY),
    CREATE("Create", Kind.ACTIVITY),
    DELETE("Delete", Kind.ACTIVITY),
    DISLIKE("Dislike", Kind.ACTIVITY),
    FLAG("Flag", Kind.ACTIVITY),
    FOLLOW("Follow", Kind.ACTIVITY),
    IGNORE("Ignore", Kind.ACTIVITY),
    INVITE("Invite", Kind.ACTIVITY),
    JOIN("Join", Kind.ACTIVITY),
    LEAVE("Leave", Kind.ACTIVITY),
    LIKE("Like", Kind.ACTIVITY),
    LISTEN("Listen", Kind.ACTIVITY),
    MOVE("Move", Kind.ACTIVITY),
    OFFER("Offer", Kind.ACTIVITY),
    QUESTION("Question", Kind.INTRANSITIVE_ACTIVITY),
    REJECT("Reject", Kind.ACTIVITY),
    READ("Read", Kind.ACTIVITY),
    REMOVE("Remove", Kind.ACTIVITY),
    TENTATIVE_REJECT("TentativeReject", Kind.ACTIVITY),
    TENTATIVE_ACCEPT("TentativeAccept", Kind.ACTIVITY),
    TRAVEL("Travel", Kind.INTRANSITIVE_ACTIVITY),
    UNDO("Undo", Kind.ACTIVITY),
    UPDATE("Update", Kind.ACTIVITY),
    VIEW("View", Kind.ACTIVITY),

    // actor types
    APPLICATION("Application", Kind.ACTOR),
    GROUP("Group", Kind.ACTOR),
    ORGANIZATION("Organization", Kind.ACTOR),
    PERSON("Person", Kind.ACTOR),
    SERVICE("Service", Kind.ACTOR),

    // object types
    ARTICLE("Article", Kind.OBJECT),
    AUDIO("Audio", Kind.OBJECT),
    DOCUMENT("Document", Kind.OBJECT),
    EVENT("Event", Kind.OBJECT),
    IMAGE("Image", Kind.OBJECT),
    NOTE("Note", Kind.OBJECT),
    PAGE("Page", Kind.OBJECT),
    PLACE("Place", Kind.OBJECT),
    PROFILE("Profile", Kind.OBJECT),
    RELATIONSHIP("Relationship", Kind.OBJECT),
    TOMBSTONE("Tombstone", Kind.OBJECT),
    VIDEO("Video", Kind.OBJECT),

    // link types
    MENTION("Mention", Kind.LINK);

    /** What a type describes. */
    public enum Kind {

        /** An object (core {@code Object} and the object types). */
        OBJECT,

        /** A link ({@code Link}, {@code Mention}). */
        LINK,

        /** An activity that has an {@code object}. */
        ACTIVITY,

        /** An activity without an {@code object} ({@code Arrive}, {@code Question}, ...). */
        INTRANSITIVE_ACTIVITY,

        /** An actor ({@code Person}, {@code Service}, ...). */
        ACTOR,

        /** A collection or a collection page. */
        COLLECTION

    }

    private static final Map<String, AsType> BY_TERM = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(AsType::term, Function.identity()));

    private final String term;

    private final Kind kind;

    AsType(final String term, final Kind kind) {
        this.term = term;
        this.kind = kind;
    }

    /**
     * Returns the term, as written in documents.
     *
     * @return for example {@code Note}
     */
    public String term() {
        return term;
    }

    /**
     * Returns the full IRI of the type.
     *
     * @return for example {@code https://www.w3.org/ns/activitystreams#Note}
     */
    public String iri() {
        return ActivityStreams.NAMESPACE + term;
    }

    /**
     * Returns what the type describes.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Tells whether the type is an activity, transitive or not.
     *
     * @return {@code true} for activity types
     */
    public boolean isActivity() {
        return kind == Kind.ACTIVITY || kind == Kind.INTRANSITIVE_ACTIVITY;
    }

    /**
     * Looks a type up by term, compact IRI or full IRI.
     *
     * @param value {@code Note}, {@code as:Note} or the full IRI; may be {@code null}
     * @return the type, or empty when the value names no Activity Vocabulary type
     */
    public static Optional<AsType> of(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        final String term;
        if (value.startsWith(ActivityStreams.NAMESPACE)) {
            term = value.substring(ActivityStreams.NAMESPACE.length());
        } else if (value.startsWith(ActivityStreams.PREFIX)) {
            term = value.substring(ActivityStreams.PREFIX.length());
        } else {
            term = value;
        }
        return Optional.ofNullable(BY_TERM.get(term));
    }

}
