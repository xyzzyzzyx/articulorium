package com.articulorium.discovery.solr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class SolrSyntaxTest {

    @Test
    void escapesEverythingButLettersAndDigitsInRegularExpressions() {
        assertThat(SolrSyntax.regex("a.b/c d-é9")).isEqualTo("a\\.b\\/c\\ d\\-é9");
    }

    @Test
    void matchesDirectChildrenOfEveryParent() {
        final String query = SolrSyntax.childrenOf("id", List.of("http://h/a", "http://i/a/"));

        assertThat(query).isEqualTo("(id:/http\\:\\/\\/h\\/a\\/[^\\/]+/ OR id:/http\\:\\/\\/i\\/a\\/[^\\/]+/)");
        // the expressions mean the same in Java as in Lucene for this subset
        final Pattern children = Pattern.compile(query.substring("(id:/".length(), query.indexOf("/ OR")));
        assertThat(children.matcher("http://h/a/b").matches()).isTrue();
        assertThat(children.matcher("http://h/a/b/c").matches()).as("grandchildren").isFalse();
        assertThat(children.matcher("http://h/a").matches()).as("the parent").isFalse();
        assertThat(children.matcher("http://h/ab/c").matches()).as("siblings sharing a prefix").isFalse();
    }

}
