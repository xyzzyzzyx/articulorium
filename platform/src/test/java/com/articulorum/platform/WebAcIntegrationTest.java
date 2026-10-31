package com.articulorum.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of Web Access Control: ACL documents at {@code fcr:acl}, enforcement,
 * inheritance through {@code acl:default}, local {@code vcard:Group}s and the WebAC information
 * endpoint. Agents are identified by a trusted header in these tests.
 */
@SpringBootTest(properties = "articulorum.webac.trusted-agent-header=X-Test-Agent")
@AutoConfigureMockMvc
class WebAcIntegrationTest {

    private static final String BASE = "http://localhost";

    private static final String TURTLE = "text/turtle";

    private static final String AGENT = "X-Test-Agent";

    private static final String ALICE = "https://example.org/alice#me";

    private static final String BOB = "https://example.org/bob#me";

    private static final String PREFIXES = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
        + "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n";

    @Autowired
    private MockMvc mvc;

    private String container() throws Exception {
        return mvc.perform(post("/").header("Slug", "acl-" + UUID.randomUUID().toString().substring(0, 8))
                .contentType(TURTLE).content(""))
            .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION)
            .substring(BASE.length());
    }

    /** Public read, Alice full control; inherited by children through acl:default. */
    private String restrict(final String container) throws Exception {
        final String acl = PREFIXES
            + "<#public> a acl:Authorization ; acl:agentClass foaf:Agent ; acl:accessTo <" + BASE + container + "> ;"
            + " acl:default <" + BASE + container + "> ; acl:mode acl:Read .\n"
            + "<#alice> a acl:Authorization ; acl:agent <" + ALICE + "> ; acl:accessTo <" + BASE + container + "> ;"
            + " acl:default <" + BASE + container + "> ; acl:mode acl:Read, acl:Write, acl:Control .\n";
        mvc.perform(put(container + "/fcr:acl").contentType(TURTLE).content(acl)).andExpect(status().isCreated());
        return acl;
    }

    /** The default root ACL lets everyone do everything and every resource links its ACL. */
    @Test
    void defaultRootAclAllowsEverything() throws Exception {
        final String container = container();
        mvc.perform(get(container))
            .andExpect(status().isOk())
            .andExpect(header().string("WAC-Allow", "user=\"read write append control\",public=\"read write append control\""))
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItem(containsString(container + "/fcr:acl>; rel=\"acl\""))));
        mvc.perform(get(container + "/fcr:acl")).andExpect(status().isNotFound());
    }

    /** Restricted resources answer 401 to anonymous writers and 403 to authenticated ones. */
    @Test
    void aclsAreEnforcedAndInherited() throws Exception {
        final String container = container();
        restrict(container);

        mvc.perform(get(container)).andExpect(status().isOk())
            .andExpect(header().string("WAC-Allow", "user=\"read\",public=\"read\""));
        mvc.perform(post(container).contentType(TURTLE).content(""))
            .andExpect(status().isUnauthorized())
            .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
        mvc.perform(post(container).header(AGENT, BOB).contentType(TURTLE).content(""))
            .andExpect(status().isForbidden());
        final String child = mvc.perform(post(container).header(AGENT, ALICE).contentType(TURTLE).content(""))
            .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION)
            .substring(BASE.length());

        // the child inherits; Alice is recorded as creator
        mvc.perform(get(child).accept("application/n-triples"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(ALICE)));
        mvc.perform(delete(child)).andExpect(status().isUnauthorized());
        mvc.perform(delete(child).header(AGENT, ALICE)).andExpect(status().isNoContent());
    }

    /** acl:Control is needed to read and change an ACL; deleting it restores inheritance. */
    @Test
    void aclDocumentsRequireControl() throws Exception {
        final String container = container();
        restrict(container);

        mvc.perform(get(container + "/fcr:acl")).andExpect(status().isUnauthorized());
        mvc.perform(get(container + "/fcr:acl").header(AGENT, ALICE).accept(TURTLE))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(ALICE)));
        mvc.perform(patch(container + "/fcr:acl").header(AGENT, ALICE).contentType("application/sparql-update")
                .content("PREFIX acl: <http://www.w3.org/ns/auth/acl#> INSERT DATA { <" + BASE + container
                    + "/fcr:acl#public> acl:mode acl:Append }"))
            .andExpect(status().isNoContent());
        mvc.perform(post(container).contentType(TURTLE).content("")).andExpect(status().isCreated());
        mvc.perform(delete(container + "/fcr:acl").header(AGENT, ALICE)).andExpect(status().isNoContent());
        mvc.perform(delete(container)).andExpect(status().isNoContent());
    }

    /** Incomplete authorizations are rejected. */
    @Test
    void invalidAclsAreRejected() throws Exception {
        final String container = container();
        mvc.perform(put(container + "/fcr:acl").contentType(TURTLE)
                .content(PREFIXES + "<#x> a acl:Authorization ; acl:accessTo <" + BASE + container + "> .\n"))
            .andExpect(status().isConflict());
        mvc.perform(put(container + "/fcr:acl").contentType("text/plain").content("x"))
            .andExpect(status().isUnsupportedMediaType());
    }

    /** acl:agentGroup naming a vcard:Group resource grants its members. */
    @Test
    void localGroupsGrantTheirMembers() throws Exception {
        final String container = container();
        final String group = mvc.perform(post(container).contentType(TURTLE)
                .content("@prefix vcard: <http://www.w3.org/2006/vcard/ns#> .\n"
                    + "<#editors> a vcard:Group ; vcard:hasMember <" + BOB + "> ."))
            .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        mvc.perform(put(container + "/fcr:acl").contentType(TURTLE).content(PREFIXES
                + "<#editors> a acl:Authorization ; acl:agentGroup <" + group + "#editors> ;"
                + " acl:accessTo <" + BASE + container + "> ; acl:default <" + BASE + container + "> ;"
                + " acl:mode acl:Read, acl:Write .\n"))
            .andExpect(status().isCreated());

        mvc.perform(get(container)).andExpect(status().isUnauthorized());
        mvc.perform(post(container).header(AGENT, BOB).contentType(TURTLE).content("")).andExpect(status().isCreated());
        mvc.perform(get(container + "/fcr:acl").header(AGENT, BOB)).andExpect(status().isForbidden());
    }

    /** The WebAC endpoint reports the agent, its modes and (with control) the authorizations. */
    @Test
    void accessInformationIsReported() throws Exception {
        final String container = container();
        restrict(container);

        mvc.perform(get("/.well-known/webac").param("resource", container))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agent.authenticated").value(false))
            .andExpect(jsonPath("$.userModes[0]").value("read"))
            .andExpect(jsonPath("$.aclResource").value(container))
            .andExpect(jsonPath("$.authorizations").isEmpty());
        mvc.perform(get("/.well-known/webac").param("resource", BASE + container + "/child").header(AGENT, ALICE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agent.webId").value(ALICE))
            .andExpect(jsonPath("$.inherited").value(true))
            .andExpect(jsonPath("$.userModes", hasItem("control")))
            .andExpect(jsonPath("$.authorizations.length()").value(2));
    }

}
