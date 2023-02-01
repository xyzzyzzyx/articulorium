package com.articulorum.domain.repo;

import java.util.UUID;

import com.articulorum.domain.Schema;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SchemaRepo extends JpaRepository<Schema, UUID> {

    public boolean existsByPrefix(String prefix);

}
