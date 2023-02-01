package com.articulorum.domain;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
@Table(name = "containers", indexes = { @Index(name = "path_index", columnList = "path", unique = true) })
public class Container {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String path;

    @ElementCollection
    private Set<Element> elements = new HashSet<>();

    public Optional<Element> getElementByAttribute(String attribute) {
        return elements.stream()
            .filter(element -> element.getAttribute().equals(attribute))
            .findAny();
    }

    public Set<Element> getElementsByAttribute(String attribute) {
        return elements.stream()
            .filter(element -> element.getAttribute().equals(attribute))
            .collect(Collectors.toSet());
    }

}
