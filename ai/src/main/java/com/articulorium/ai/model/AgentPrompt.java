package com.articulorium.ai.model;

import java.util.List;

import org.springframework.ai.content.Media;

/**
 * What the model is asked.
 *
 * @param system the instructions (the role, the rules, the language of the answer)
 * @param user   the material: repository content, always presented as data to describe and never
 *               as instructions
 * @param media  images shown to the model, may be empty
 */
public record AgentPrompt(String system, String user, List<Media> media) {

    /**
     * Creates the prompt.
     *
     * @param system the instructions
     * @param user   the material
     * @param media  images
     */
    public AgentPrompt {
        media = media == null ? List.of() : List.copyOf(media);
    }

    /**
     * A prompt without images.
     *
     * @param system the instructions
     * @param user   the material
     * @return the prompt
     */
    public static AgentPrompt of(final String system, final String user) {
        return new AgentPrompt(system, user, List.of());
    }

    /**
     * Returns the length of the text sent (for logs; the text itself is never logged).
     *
     * @return the number of characters
     */
    public int size() {
        return system.length() + user.length();
    }

}
