package com.articulorium.ai.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A chat model answering from a script: each call takes the next answer (a text, or an exception
 * to throw) and records the prompt it was given.
 */
public class ScriptedChatModel implements ChatModel {

    private final Deque<Object> answers = new ArrayDeque<>();

    private final List<Prompt> prompts = new ArrayList<>();

    /**
     * Adds answers.
     *
     * @param next texts or runtime exceptions
     * @return this model
     */
    public synchronized ScriptedChatModel answer(final Object... next) {
        answers.addAll(List.of(next));
        return this;
    }

    /**
     * Returns the prompts received.
     *
     * @return the prompts, in order
     */
    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    /**
     * Forgets answers and prompts.
     */
    public synchronized void reset() {
        answers.clear();
        prompts.clear();
    }

    @Override
    public synchronized ChatResponse call(final Prompt prompt) {
        prompts.add(prompt);
        final Object next = answers.poll();
        if (next == null) {
            throw new IllegalStateException("The scripted chat model has no answer left");
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage((String) next))));
    }

}
