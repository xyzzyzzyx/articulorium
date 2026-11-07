package com.articulorium.ai.support;

import com.articulorium.ai.config.AiProperties;
import com.articulorium.ai.model.AgentModel;
import com.articulorium.ai.platform.AgentAccess;
import com.articulorium.ai.platform.PlatformReader;
import com.articulorium.ai.topology.TopologyClient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The agent's parts wired by hand for unit tests: a scripted chat model, a mocked platform and
 * topology service.
 */
public final class Agents {

    /** The settings. */
    public final AiProperties properties = new AiProperties();

    /** The scripted model. */
    public final ScriptedChatModel chat = new ScriptedChatModel();

    /** The meters. */
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** The mocked platform and topology service. */
    public final Platform platform;

    /** The model access. */
    public final AgentModel model;

    /** The platform reader. */
    public final PlatformReader reader;

    /** The access check. */
    public final AgentAccess access;

    /** The topology client. */
    public final TopologyClient topologies;

    /**
     * Wires the parts, with a chat model.
     */
    public Agents() {
        this(true);
    }

    /**
     * Wires the parts.
     *
     * @param withModel whether a chat model is configured
     */
    public Agents(final boolean withModel) {
        properties.setEnabled(true);
        properties.setPlatformUrl(Platform.BASE);
        properties.setTopologyUrl(Platform.TOPOLOGY);
        final RestClient.Builder builder = RestClient.builder();
        platform = Platform.bindTo(builder);
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (withModel) {
            beans.registerSingleton("chatModel", chat);
            beans.registerSingleton("chatClientBuilder", ChatClient.builder(chat));
        }
        model = new AgentModel(beans.getBeanProvider(ChatClient.Builder.class), beans.getBeanProvider(ChatModel.class),
            properties, meters);
        reader = new PlatformReader(builder, properties);
        access = new AgentAccess(reader);
        topologies = new TopologyClient(builder, properties);
    }

}
