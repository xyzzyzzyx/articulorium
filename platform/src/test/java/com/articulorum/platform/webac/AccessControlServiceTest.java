package com.articulorum.platform.webac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.rdf.NTriplesSerializer;
import com.articulorum.domain.vocabulary.Acl;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.ldp.exception.ConstraintViolationException;
import com.articulorum.platform.repository.LdpResourceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Tests {@link AccessControlService} with mocked repositories.
 */
class AccessControlServiceTest {

    private AclRepository acls;

    private LdpResourceRepository resources;

    private AccessControlService service(final String rootAcl) {
        final WebAcProperties properties = new WebAcProperties();
        properties.setRootAcl(new ClassPathResource(rootAcl));
        return new AccessControlService(acls, resources, properties);
    }

    @BeforeEach
    void setUp() {
        acls = mock(AclRepository.class);
        resources = mock(LdpResourceRepository.class);
        when(acls.findByPathIn(any())).thenReturn(List.of());
        when(resources.findByPath(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void consulRootAclGrantsByRole() {
        final AccessControlService service = service("webac/root-acl-consul.ttl");

        assertThat(service.modes(Agent.ANONYMOUS, "books")).containsExactly(Acl.READ);
        final Agent editor = new Agent("info:consul/agent/jane", Set.of("info:consul/role/editors"), true);
        assertThat(service.modes(editor, "books")).containsExactly(Acl.READ, Acl.WRITE, Acl.APPEND);
        final Agent admin = new Agent("info:consul/agent/root", Set.of("info:consul/role/administrators"), true);
        assertThat(service.modes(admin, "")).contains(Acl.CONTROL);
    }

    @Test
    void ownAclsApplyToTheResourceAndDefaultsToDescendants() {
        final AccessControlService service = service("webac/root-acl.ttl");
        final List<Triple> acl = List.of(
            Triple.iri("/a/fcr:acl#own", Rdf.TYPE, Acl.AUTHORIZATION),
            Triple.iri("/a/fcr:acl#own", Acl.AGENT_CLASS, Acl.AUTHENTICATED_AGENT),
            Triple.iri("/a/fcr:acl#own", Acl.ACCESS_TO, "/a"),
            Triple.iri("/a/fcr:acl#own", Acl.MODE, Acl.READ),
            Triple.iri("/a/fcr:acl#kids", Rdf.TYPE, Acl.AUTHORIZATION),
            Triple.iri("/a/fcr:acl#kids", Acl.AGENT_CLASS, Acl.FOAF_AGENT),
            Triple.iri("/a/fcr:acl#kids", Acl.DEFAULT, "/a"),
            Triple.iri("/a/fcr:acl#kids", Acl.MODE, Acl.APPEND));
        when(acls.findByPathIn(any())).thenReturn(List.of(new AclEntity("a",
            NTriplesSerializer.serialize(acl, AccessControlService.STORAGE_BASE), "test")));

        assertThat(service.modes(Agent.ANONYMOUS, "a")).isEmpty();
        assertThat(service.modes(Agent.of("x"), "a")).containsExactly(Acl.READ);
        assertThat(service.modes(Agent.ANONYMOUS, "a/b/c")).containsExactly(Acl.APPEND);
        final AccessControlService.EffectiveAcl effective = service.effective("a/b");
        assertThat(effective.aclPath()).isEqualTo("a");
        assertThat(effective.inherited()).isTrue();
    }

    @Test
    void classAuthorizationsMatchResourceTypes() {
        final AccessControlService service = service("webac/root-acl.ttl");
        final LdpResource secret = new LdpResource("vault/doc", InteractionModel.RDF_SOURCE);
        secret.setTriples(List.of(Triple.iri("/vault/doc", Rdf.TYPE, "urn:x:Secret")));
        when(resources.findByPath("vault/doc")).thenReturn(Optional.of(secret));
        final List<Triple> acl = List.of(
            Triple.iri("/vault/fcr:acl#c", Rdf.TYPE, Acl.AUTHORIZATION),
            Triple.iri("/vault/fcr:acl#c", Acl.AGENT, "urn:agent:spy"),
            Triple.iri("/vault/fcr:acl#c", Acl.ACCESS_TO_CLASS, "urn:x:Secret"),
            Triple.iri("/vault/fcr:acl#c", Acl.MODE, Acl.READ));
        when(acls.findByPathIn(any())).thenReturn(List.of(new AclEntity("vault",
            NTriplesSerializer.serialize(acl, AccessControlService.STORAGE_BASE), "test")));

        assertThat(service.modes(Agent.of("urn:agent:spy"), "vault/doc")).containsExactly(Acl.READ);
        assertThat(service.modes(Agent.of("urn:agent:other"), "vault/doc")).isEmpty();
    }

    @Test
    void validatesAuthorizationsAndFormatsModes() {
        assertThatThrownBy(() -> AccessControlService.validate(List.of(
            Triple.iri("#x", Rdf.TYPE, Acl.AUTHORIZATION),
            Triple.iri("#x", Acl.AGENT_CLASS, Acl.FOAF_AGENT),
            Triple.iri("#x", Acl.MODE, "urn:mode:Fly"))))
            .isInstanceOf(ConstraintViolationException.class)
            .hasMessageContaining("unknown mode");
        assertThat(AccessControlService.wacAllowTokens(Set.of(Acl.CONTROL, Acl.READ))).isEqualTo("read control");
        assertThat(AccessControlService.selfAndAncestors("a/b")).containsExactly("a/b", "a", "");
        assertThat(AccessControlService.aclIri("http://h/p", "a")).isEqualTo("http://h/p/a/fcr:acl");
    }

}
