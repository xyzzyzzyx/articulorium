package com.articulorum.platform.config;

import java.io.IOException;
import java.util.UUID;

import com.articulorum.domain.Container;
import com.articulorum.domain.Schema;
import com.articulorum.domain.repo.ContainerRepo;
import com.articulorum.domain.repo.SchemaRepo;
import com.articulorum.platform.utility.ContainerUtility;
import com.articulorum.platform.utility.PrefixUtility;

import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Component;

@Component
@EntityScan(basePackages = { "com.articulorum.domain" })
@EnableJpaRepositories(basePackages = { "com.articulorum.domain" })
public class ApplicationStartup implements ApplicationListener<ApplicationReadyEvent> {

    @Value("classpath:namespaces.csv")
    private Resource namespaces;

    @Value("classpath:root.ttl")
    private Resource root;

    @Autowired
    private SchemaRepo schemaRepo;

    @Autowired
    private ContainerRepo containerRepo;

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        try {
            initSchema();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            initRoot();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initRoot() throws IOException {
        if (!containerRepo.existsByPath(StringUtils.EMPTY)) {
            final Model model = ModelFactory.createDefaultModel();
            model.read(root.getInputStream(), StringUtils.EMPTY, "TURTLE");
            Container container = ContainerUtility.fromModel(model);
            container.setId(UUID.randomUUID());
            container.setPath(StringUtils.EMPTY);
            containerRepo.save(container);
        }
    }

    private void initSchema() throws IOException {
        PrefixUtility.readNamespaceCSV(namespaces.getInputStream())
            .peek(PrefixUtility::putPrefix)
            .filter(this::notExists)
            .forEach(schemaRepo::save);
    }

    private boolean notExists(Schema schema) {
        return !schemaRepo.existsByPrefix(schema.getPrefix());
    }

}