package com.articulorium.harvest;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.harvest.config.HarvestConfiguration;
import com.articulorium.harvest.service.HarvestService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Starts the service next to other task schedulers, as with Spring Cloud Consul enabled (its
 * configuration and catalog watches each define one).
 */
@SpringBootTest
class HarvestApplicationTest {

    @Autowired
    private HarvestService harvests;

    @Autowired
    @Qualifier(HarvestConfiguration.SCHEDULER)
    private TaskScheduler harvestScheduler;

    @Test
    void startsWithOtherTaskSchedulersAround() {
        assertThat(harvests.statuses()).hasSize(2);
        assertThat(harvestScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
    }

    /** Task schedulers like the ones Spring Cloud Consul defines. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ConsulLikeSchedulers {

        @Bean
        ThreadPoolTaskScheduler configWatchTaskScheduler() {
            return new ThreadPoolTaskScheduler();
        }

        @Bean
        ThreadPoolTaskScheduler catalogWatchTaskScheduler() {
            return new ThreadPoolTaskScheduler();
        }

    }

}
