package com.articulorum.platform.service;

import java.util.Optional;

import com.articulorum.domain.Container;
import com.articulorum.event.EventAction;

public interface ContainerService {

    boolean existsByPath(String path);

    Optional<Container> findByPath(String path);

    String create(Container parent, Container container, String uri);

    void delete(Container parent, Container container, String uri);

    void emit(Container container, EventAction action);

}