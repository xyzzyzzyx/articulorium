package com.articulorum.domain;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_EMPTY)
public class Element {

    @NotNull
    @Column(nullable = false)
    private String attribute;

    @NotNull
    @Column(nullable = false)
    private String value;

}
