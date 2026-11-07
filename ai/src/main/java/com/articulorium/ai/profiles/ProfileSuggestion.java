package com.articulorium.ai.profiles;

import java.util.List;

/**
 * Suggestions for an academic profile, drawn from what the profile itself records (positions,
 * degrees, publications). Nothing is saved: the caller keeps what is right and saves the profile
 * as the profile editor does.
 *
 * @param path          the profile
 * @param overview      a suggested overview ({@code vivo:overview}), empty when the model has none
 * @param hasOverview   whether the profile has an overview already (keeping the suggestion replaces it)
 * @param researchAreas suggested research areas ({@code vivo:hasResearchArea}), none the profile has
 * @param keywords      suggested keywords ({@code vivo:freetextKeyword}), none the profile has
 * @param basedOn       what the suggestions were drawn from
 * @param language      the language of the overview, research areas and keywords (BCP 47), may be
 *                      {@code null}
 */
public record ProfileSuggestion(String path, String overview, boolean hasOverview, List<String> researchAreas,
        List<String> keywords, BasedOn basedOn, String language) {

    /**
     * What the suggestions were drawn from.
     *
     * @param positions    positions read
     * @param education    degrees read
     * @param publications publications read
     */
    public record BasedOn(int positions, int education, int publications) {
    }

}
