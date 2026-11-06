import { PLATFORM, pathOf, unique } from '../support/e2e';

const VIVO = 'http://vivoweb.org/ontology/core#';

describe('Academic profiles', () => {
  it('creates, validates, edits and deletes a VIVO profile', () => {
    const family = unique('Curie');
    cy.visitUi('profiles');
    cy.get('h1').should('have.text', 'Academic profiles');
    cy.get('.ui-new-profile').click();
    cy.location('pathname').should('eq', '/ui/profiles/new');

    // incomplete or invalid data is explained and nothing is saved
    cy.get('input[name=email]').type('marie-at-example');
    cy.get('input[name=orcid]').type('0000-0002-1825-0098');
    cy.get('.ui-save-profile').click();
    cy.get('.ui-profile-issues')
      .should('contain.text', 'Given name: This field is required')
      .and('contain.text', 'Family name: This field is required')
      .and('contain.text', 'Email: Enter an email address')
      .and('contain.text', 'ORCID iD: Enter an ORCID iD');
    cy.focused().should('have.attr', 'name', 'givenName');
    cy.location('pathname').should('eq', '/ui/profiles/new');

    cy.get('input[name=givenName]').type('Marie');
    cy.get('input[name=familyName]').type(family);
    cy.get('input[name=preferredTitle]').type('Professor of Physics');
    cy.get('input[name=email]').clear().type('marie@example.org');
    cy.get('input[name=orcid]').clear().type('https://orcid.org/0000-0002-1825-0097');
    cy.get('textarea[name=overview]').type('Pioneer of radioactivity.');
    cy.get('.ui-profile-areas-field input').type('Radioactivity{enter}');
    cy.get('.ui-profile-keywords-field input').type('radium{enter}polonium{enter}');
    cy.get('.ui-add-position').click();
    cy.get('input[name=position-title-0]').type('Professor');
    cy.get('input[name=position-organization-0]').type('University of Paris');
    cy.get('input[name=position-start-0]').type('1934');
    cy.get('input[name=position-end-0]').type('1906');
    cy.get('.ui-save-profile').click();
    cy.get('.ui-profile-issues').should('contain.text', 'Position 1 · End year: The end year is before the start year');
    cy.get('input[name=position-start-0]').clear().type('1906');
    cy.get('input[name=position-end-0]').clear().type('1934');
    cy.get('.ui-add-degree').click();
    cy.get('input[name=education-degree-0]').type('Doctor of Science');
    cy.get('input[name=education-field-0]').type('Physics');
    cy.get('input[name=education-organization-0]').type('University of Paris');
    cy.get('input[name=education-end-0]').type('1903');
    cy.get('.ui-add-publication').click();
    cy.get('input[name=publication-title-0]').type('Sur une nouvelle substance radio-active');
    cy.get('input[name=publication-venue-0]').type('Comptes rendus');
    cy.get('input[name=publication-year-0]').type('1898');
    cy.get('input[name=publication-doi-0]').type('https://doi.org/10.1000/182');

    cy.intercept('POST', '**/platform/profiles').as('create');
    cy.get('.ui-save-profile').click();
    cy.wait('@create').its('response.statusCode').should('eq', 201);
    cy.notified('Profile created');
    cy.location('pathname').should('match', /^\/ui\/profiles\/marie-curie/);

    // shown consistently after a reload
    cy.reload();
    cy.get('h1').should('have.text', `Marie ${family}`);
    cy.get('.ui-profile-title').should('have.text', 'Professor of Physics');
    cy.get('.ui-profile-orcid').should('have.attr', 'href', 'https://orcid.org/0000-0002-1825-0097');
    cy.get('.ui-profile-keywords mat-chip').should('have.length', 2).first().should('contain.text', 'polonium');
    cy.get('.ui-profile-positions').should('contain.text', 'Professor').and('contain.text', '1906 – 1934');
    cy.get('.ui-profile-education').should('contain.text', 'Doctor of Science (Physics), University of Paris');
    cy.get('.ui-profile-publications a').should('have.attr', 'href', 'https://doi.org/10.1000/182');

    // stored as VIVO in one LDP resource
    cy.location('pathname').then((pathname) => {
      const id = pathname.substring('/ui/profiles/'.length);
      cy.request({ url: `${PLATFORM}profiles/${id}`, headers: { Accept: 'application/n-triples' } })
        .its('body')
        .should('contain', 'http://xmlns.com/foaf/0.1/Person')
        .and('contain', `${VIVO}FacultyPosition`)
        .and('contain', `${VIVO}Authorship`)
        .and('contain', '<http://purl.obolibrary.org/obo/ARG_2000028>');
    });

    // edit: remove the position, change the title
    cy.get('.ui-edit-profile').click();
    cy.get('input[name=familyName]').should('have.value', family);
    cy.get('input[name=preferredTitle]').clear().type('Director of the Radium Institute');
    cy.get('.ui-profile-position').contains('button', 'delete').click();
    cy.intercept('PUT', '**/platform/profiles/*').as('replace');
    cy.get('.ui-save-profile').click();
    cy.wait('@replace').its('request.headers').should('have.property', 'if-match');
    cy.notified('Profile saved');
    cy.reload();
    cy.get('.ui-profile-title').should('have.text', 'Director of the Radium Institute');
    cy.get('.ui-profile-positions').should('not.exist');

    // the list shows it; deleting returns there
    cy.visitUi('profiles');
    cy.get('.ui-profile-filter input').type(family);
    cy.get('.ui-profiles-list').contains('a', `Marie ${family}`).click();
    cy.get('.ui-delete-profile').click();
    cy.get('mat-dialog-actions').contains('button', 'Delete').click();
    cy.notified('Profile deleted');
    cy.location('pathname').should('eq', '/ui/profiles');
  });

  it('refuses to overwrite a profile changed by someone else', () => {
    const name = unique('ada');
    cy.request({
      method: 'POST',
      url: `${PLATFORM}profiles`,
      headers: { 'Content-Type': 'text/turtle', Slug: name },
      body:
        '<> a <http://xmlns.com/foaf/0.1/Person> ; <http://purl.obolibrary.org/obo/ARG_2000028> <#vcard> .\n' +
        '<#vcard> <http://www.w3.org/2006/vcard/ns#hasName> <#n> .\n' +
        '<#n> <http://www.w3.org/2006/vcard/ns#givenName> "Ada" ; <http://www.w3.org/2006/vcard/ns#familyName> "Lovelace" .',
    }).then((response) => {
      const path = pathOf(String(response.headers['location']));
      cy.visitUi(`${path}/edit`);
      cy.get('input[name=familyName]').should('have.value', 'Lovelace');
      // someone else changes the profile meanwhile
      cy.request({
        method: 'PATCH',
        url: `${PLATFORM}${path}`,
        headers: { 'Content-Type': 'application/sparql-update' },
        body: 'INSERT DATA { <> <http://vivoweb.org/ontology/core#overview> "Analyst" }',
      });
      cy.get('input[name=preferredTitle]').type('Countess');
      cy.get('.ui-save-profile').click();
      cy.get('mat-snack-bar-container').should('contain.text', 'Someone changed this profile after you opened it.');
    });
  });
});
