package com.articulorum.triplestore.handler;

import java.util.UUID;

import com.articulorum.domain.Container;
import com.articulorum.domain.Element;
import com.articulorum.event.ContainerEvent;
import com.articulorum.event.handler.ContainerEventHandler;
import com.articulorum.triplestore.service.RemoteRepositoryService;
import com.bigdata.rdf.sail.remote.BigdataSailRemoteRepositoryConnection;

import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.model.impl.StatementImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.repository.RepositoryException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TriplestoreContainerEventHandler implements ContainerEventHandler {

    @Autowired
    private RemoteRepositoryService remoteRepositoryService;

    @Override
    public void handle(ContainerEvent event) {
        Container container = event.getContainer();
        UUID id = container.getId();
        try {
            BigdataSailRemoteRepositoryConnection connection = remoteRepositoryService.getConnection();
            connection.begin();
            switch (event.getAction()) {
                case CREATED:
                    log.info("INDEX {}", id);
                    addStatements(connection, container);
                    break;
                case UPDATED:
                    log.info("UPDATE {}", id);
                    addStatements(connection, container, true);
                    break;
                case DELETED:
                    log.info("DELETE {}", id);
                    connection.clear(new URIImpl("http://localhost:8080/" + container.getPath()));
                    break;
            }
            connection.commit();
            connection.close();
        } catch (RepositoryException e) {
            log.warn("Failed to process container {}", id);
            if (log.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }

    private void addStatements(BigdataSailRemoteRepositoryConnection connection, Container container)
            throws RepositoryException {
        addStatements(connection, container, false);
    }

    private void addStatements(BigdataSailRemoteRepositoryConnection connection, Container container, boolean clear)
            throws RepositoryException {
        Resource s = new URIImpl("http://localhost:8080/" + container.getPath());
        if (clear) {
            connection.clear(s);
        }
        for (Element element : container.getElements()) {
            URI p = new URIImpl(element.getAttribute());
            Value o = new LiteralImpl(element.getValue());
            Statement stmt = new StatementImpl(s, p, o);
            connection.add(stmt);
        }
    }

}