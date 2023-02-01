package com.articulorum.domain.repo;

import java.util.Optional;
import java.util.UUID;

import com.articulorum.domain.Container;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContainerRepo extends JpaRepository<Container, UUID> {

    public boolean existsByPath(String path);

    @EntityGraph(attributePaths = "elements")
    public Optional<Container> findByPath(String path);

}
