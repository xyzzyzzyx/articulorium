package com.articulorium.ai.web;

import java.util.Map;

/**
 * What the agent offers now, for the user interface (which shows the AI actions only when they are
 * offered).
 *
 * @param enabled      whether the agent is turned on
 * @param model        whether a chat model is configured
 * @param modelName    the provider and model, for example {@code ollama:llama3.2-vision}, may be
 *                     {@code null}
 * @param capabilities each capability by key ({@code topology}, {@code media}, {@code topics},
 *                     {@code profiles}, {@code classification}): whether it can be used now
 */
public record Capabilities(boolean enabled, boolean model, String modelName, Map<String, Boolean> capabilities) {
}
