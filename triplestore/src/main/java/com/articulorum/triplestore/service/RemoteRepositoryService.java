package com.articulorum.triplestore.service;

import com.bigdata.rdf.sail.remote.BigdataSailRemoteRepository;
import com.bigdata.rdf.sail.remote.BigdataSailRemoteRepositoryConnection;
import com.bigdata.rdf.sail.webapp.client.RemoteRepositoryManager;

import org.openrdf.repository.RepositoryException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RemoteRepositoryService {

    private final BigdataSailRemoteRepository remoteRepository;

    @Autowired
    public RemoteRepositoryService(RemoteRepositoryManager remoteRepositoryManager) throws RepositoryException {
        this.remoteRepository = remoteRepositoryManager.getRepositoryForNamespace("test").getBigdataSailRemoteRepository();
        this.remoteRepository.initialize();
    }

    public BigdataSailRemoteRepositoryConnection getConnection() throws RepositoryException {
        return this.remoteRepository.getConnection();
    }

}