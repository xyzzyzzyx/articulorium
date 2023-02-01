package com.articulorium.ai.topics;

import java.util.List;

/**
 * The suggested topics and summary of a community or collection. Nothing is saved: the caller adds
 * what it keeps to the community or collection.
 *
 * @param path      the community or collection
 * @param summary   what it holds, in a few sentences
 * @param topics    the topics, the largest first
 * @param analysed  how many items the model saw
 * @param truncated whether there were more items than were read
 * @param language  the language of the labels and the summary (BCP 47), may be {@code null}
 */
public record TopicModel(String path, String summary, List<Topic> topics, int analysed, boolean truncated,
        String language) {

    /**
     * A topic.
     *
     * @param label       its label (a subject heading)
     * @param keywords    keywords
     * @param description one sentence
     * @param members     the items about it
     * @param share       the share of the items analysed that are about it, 0 to 1
     */
    public record Topic(String label, List<String> keywords, String description, List<Member> members, double share) {
    }

    /**
     * An item of a topic.
     *
     * @param path  its path
     * @param title its title
     */
    public record Member(String path, String title) {
    }

}
