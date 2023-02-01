package com.articulorum.triplestore.listener;

import java.util.Properties;

import com.bigdata.rdf.sail.webapp.SD;
import com.bigdata.rdf.sail.webapp.client.RemoteRepositoryManager;

import org.openrdf.model.Statement;
import org.openrdf.query.GraphQueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ApplicationReadyEventListener implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    private RemoteRepositoryManager remoteRepositoryManager;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        final String namespace = "test";
        final Properties properties = new Properties();
        properties.setProperty("com.bigdata.rdf.sail.namespace", namespace);
        try {
            if (!namespaceExists(namespace)) {
                remoteRepositoryManager.createRepository(namespace, properties);
            }
        } catch (Exception e) {
            log.warn("Failed to create store {}", "test");
            if (log.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }

    private boolean namespaceExists(final String namespace) throws Exception {
        final GraphQueryResult res = remoteRepositoryManager.getRepositoryDescriptions();
        try {
            while (res.hasNext()) {
                final Statement stmt = res.next();
                if (stmt.getPredicate().toString().equals(SD.KB_NAMESPACE.stringValue())
                    && namespace.equals(stmt.getObject().stringValue())) {
                    return true;
                }
            }
        } finally {
            res.close();
        }
        return false;
    }

}