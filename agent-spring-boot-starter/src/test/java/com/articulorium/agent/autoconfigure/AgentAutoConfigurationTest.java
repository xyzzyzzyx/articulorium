package com.articulorium.agent.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorium.agent.Agent;
import com.articulorium.agent.AgentContributor;
import com.articulorium.agent.AgentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Tests of {@link AgentAutoConfiguration} against real application contexts.
 */
class AgentAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class));

    @Test
    void isRegisteredAsAnAutoConfiguration() {
        final List<String> candidates = ImportCandidates
            .load(AutoConfiguration.class, getClass().getClassLoader())
            .getCandidates();
        assertThat(candidates).contains(AgentAutoConfiguration.class.getName());
    }

    @Test
    void providesAnEmptyRegistryByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AgentRegistry.class);
            assertThat(context.getBean(AgentRegistry.class).size()).isZero();
        });
    }

    @Test
    void registersAgentBeansInOrder() {
        runner.withUserConfiguration(AgentBeans.class).run(context -> {
            final AgentRegistry registry = context.getBean(AgentRegistry.class);
            assertThat(registry.getNames()).containsExactly("lark", "owl");
        });
    }

    @Test
    void registersAgentsDeclaredInConfiguration() {
        runner
            .withPropertyValues(
                "articulorium.agents.definitions.owl.description=read owl",
                "articulorium.agents.definitions.lark.description=sing",
                "articulorium.agents.definitions[ocr.v2].description=read scans")
            .run(context -> {
                final AgentRegistry registry = context.getBean(AgentRegistry.class);
                assertThat(registry.getAgents()).containsExactly(
                    new Agent("owl", "read owl"), new Agent("lark", "sing"),
                    new Agent("ocr.v2", "read scans"));
            });
    }

    @Test
    void listsBeansBeforeConfiguredAgents() {
        runner.withUserConfiguration(AgentBeans.class)
            .withPropertyValues("articulorium.agents.definitions.crow.description=count")
            .run(context -> assertThat(context.getBean(AgentRegistry.class).getNames())
                .containsExactly("lark", "owl", "crow"));
    }

    @Test
    void registersTheAgentsOfContributorsThatAreTurnedOn() {
        runner.withUserConfiguration(AgentBeans.class, Contributors.class)
            .withPropertyValues("articulorium.agents.definitions.crow.description=count")
            .run(context -> assertThat(context.getBean(AgentRegistry.class).getNames())
                .containsExactly("lark", "owl", "wren", "crow"));
    }

    @Test
    void failsToStartWhenAContributorRepeatsAName() {
        runner.withUserConfiguration(Contributors.class)
            .withPropertyValues("articulorium.agents.definitions.wren.description=again")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("'wren'"));
    }

    @Test
    void failsToStartWhenTwoAgentsShareAName() {
        runner.withUserConfiguration(AgentBeans.class)
            .withPropertyValues("articulorium.agents.definitions.owl.description=again")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("'owl'"));
    }

    @Test
    void keepsARegistryTheApplicationDefines() {
        runner.withUserConfiguration(CustomRegistry.class)
            .withPropertyValues("articulorium.agents.definitions.crow.description=count")
            .run(context -> {
                assertThat(context).hasSingleBean(AgentRegistry.class);
                assertThat(context.getBean(AgentRegistry.class).getNames())
                    .containsExactly("custom");
            });
    }

    @Test
    void canBeTurnedOff() {
        runner.withUserConfiguration(AgentBeans.class)
            .withPropertyValues("articulorium.agents.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(AgentRegistry.class);
                assertThat(context).doesNotHaveBean(AgentInfoContributor.class);
                // the application's own agent beans are untouched
                assertThat(context.getBeansOfType(Agent.class)).hasSize(2);
            });
    }

    @Test
    void reportsAgentsInTheActuatorInfo() {
        runner.withUserConfiguration(AgentBeans.class).run(context -> {
            final Info.Builder builder = new Info.Builder();
            context.getBean(InfoContributor.class).contribute(builder);
            assertThat(builder.build().getDetails())
                .containsEntry("agents", Map.of("count", 2, "names", List.of("lark", "owl")));
        });
    }

    @Test
    void infoContributionCanBeTurnedOff() {
        runner.withPropertyValues("management.info.agents.enabled=false")
            .run(context -> {
                assertThat(context).hasSingleBean(AgentRegistry.class);
                assertThat(context).doesNotHaveBean(AgentInfoContributor.class);
            });
    }

    @Test
    void worksWithoutActuator() {
        runner.withClassLoader(new FilteredClassLoader(InfoContributor.class))
            .withUserConfiguration(AgentBeans.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AgentRegistry.class).size()).isEqualTo(2);
                assertThat(context).doesNotHaveBean(AgentInfoContributor.class);
            });
    }

    @Test
    void backsOffWithoutTheAgentLibrary() {
        runner.withClassLoader(new FilteredClassLoader(AgentRegistry.class))
            .run(context -> assertThat(context).doesNotHaveBean("agentRegistry"));
    }

    /** Agents an application defines as beans, deliberately declared out of order. */
    @Configuration(proxyBeanMethods = false)
    static class AgentBeans {

        @Bean
        @Order(2)
        Agent owl() {
            return new Agent("owl", "read owl");
        }

        @Bean
        @Order(1)
        Agent lark() {
            return new Agent("lark", "sing");
        }

    }

    /** Components acting as agents, one of them turned off. */
    @Configuration(proxyBeanMethods = false)
    static class Contributors {

        @Bean
        AgentContributor wren() {
            return () -> Optional.of(new Agent("wren", "wake early"));
        }

        @Bean
        AgentContributor dormouse() {
            return Optional::empty;
        }

    }

    /** An application's own registry. */
    @Configuration(proxyBeanMethods = false)
    static class CustomRegistry {

        @Bean
        AgentRegistry customRegistry() {
            return AgentRegistry.of(new Agent("custom", null));
        }

    }

}
