package com.articulorum.domain;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;

import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
@JsonInclude(NON_EMPTY)
@Table(name = "schema", indexes = { @Index(name = "prefix_index", columnList = "prefix", unique = true) })
public class Schema {

    @Id
    @GeneratedValue
    private UUID id;

    @NotNull
    @Column(nullable = false, unique = true)
    private String prefix;

    @NotNull
    @Column(nullable = false, unique = true)
    private String namespace;

    @Column(columnDefinition = "TEXT")
    private String description;

}
