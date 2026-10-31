package com.articulorum.topology.model;

/**
 * How many children of a kind a topology node has, and what they look like.
 *
 * @param min         the minimum number of children
 * @param max         the maximum number, {@code -1} for no limit
 * @param initial     how many children a build creates unless the request says otherwise
 * @param slugPattern name of each child's resource; {@code {n}} is replaced with its position
 *                    (1-based), for example {@code page-{n}}
 * @param node        the children's node
 */
public record ChildRule(int min, int max, int initial, String slugPattern, TopologyNode node) {

    /** Value of {@link #max()} for unlimited children. */
    public static final int UNLIMITED = -1;

    /**
     * Clamps a requested number of children to {@link #min()} and {@link #max()}.
     *
     * @param requested the requested number, {@code null} for {@link #initial()}
     * @return the number of children to create
     */
    public int count(final Integer requested) {
        int count = requested == null ? initial : requested;
        if (count < min) {
            count = min;
        }
        if (max != UNLIMITED && count > max) {
            count = max;
        }
        return Math.max(count, 0);
    }

    /**
     * Returns the slug of the child at a position.
     *
     * @param position the 1-based position
     * @return the slug
     */
    public String slug(final int position) {
        final String pattern = slugPattern == null || slugPattern.isBlank() ? node.key() + "-{n}" : slugPattern;
        return pattern.replace("{n}", Integer.toString(position));
    }

}
