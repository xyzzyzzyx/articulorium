import { unique } from '../support/e2e';

// Internationalization across the stack: the service renders the page in the negotiated locale
// (Accept-Language, ?lang=, the articulorium-locale cookie) with its texts inlined, the client
// starts in that locale and switches language, and every service answers in the chosen locale.
describe('Internationalization', () => {
  it('renders the page in the browser language, with the texts inlined', () => {
    cy.request({ url: '/ui/browse', headers: { Accept: 'text/html', 'Accept-Language': 'de-AT, en;q=0.5' } }).then(
      (response) => {
        expect(response.headers['content-language']).to.eq('de');
        expect(response.headers['vary']).to.contain('accept-language');
        expect(response.body).to.contain('<html lang="de" dir="ltr"');
        // the client starts from this state: no request, no flash of English text
        expect(response.body).to.match(/<script id="ui-i18n" type="application\/json">\{"locale":"de"/);
        expect(response.body).to.contain('Durchsuchen');
      },
    );
    cy.visit('/ui/browse', { headers: { 'Accept-Language': 'de' } });
    cy.get('html').should('have.attr', 'lang', 'de');
    cy.get('.ui-nav').should('contain.text', 'Durchsuchen').and('contain.text', 'Gemeinschaften');
    cy.get('h1').should('have.text', 'Wurzel');
    cy.title().should('match', /^Durchsuchen · /);
  });

  it('writes right-to-left locales right to left', () => {
    cy.visit('/ui/browse?lang=ar');
    cy.get('html').should('have.attr', 'lang', 'ar').and('have.attr', 'dir', 'rtl');
    cy.get('.ui-nav').should('contain.text', 'تصفّح');
  });

  it('falls back to the default locale for unsupported languages', () => {
    cy.visit('/ui/browse', { headers: { 'Accept-Language': 'sv-SE, fi;q=0.8' } });
    cy.get('html').should('have.attr', 'lang', 'en');
    cy.get('.ui-nav').should('contain.text', 'Browse');
  });

  it('switches language from the menu and remembers the choice', () => {
    cy.visitUi('topologies');
    cy.get('.ui-language').click();
    cy.get('.mat-mdc-menu-panel').contains('button', 'Français').click();
    cy.get('html').should('have.attr', 'lang', 'fr');
    cy.get('h1').should('have.text', 'Topologies');
    cy.get('.ui-nav').should('contain.text', 'Parcourir');
    // services answer in the chosen language too: built-in topologies are translated
    cy.get('.ui-topology-list').should('contain.text', 'Livre').and('contain.text', 'Cartes postales');
    cy.getCookie('articulorium-locale').should('have.property', 'value', 'fr');
    cy.reload();
    cy.get('html').should('have.attr', 'lang', 'fr');
    cy.get('.ui-nav').should('contain.text', 'Recherche');
  });

  it('formats dates and numbers for the locale', () => {
    cy.createContainer(unique('Datum')).then((path) => {
      cy.visit(`/ui/browse/${path}?lang=de`);
      // German dates: day.month.year
      cy.get('.ui-facts').should('contain.text', 'Erstellt').invoke('text').should('match', /\d{2}\.\d{2}\.\d{4}/);
    });
  });

  it('asks the identity provider for the same language', () => {
    cy.visit('/ui/browse?lang=de');
    cy.get('.ui-account').contains('button', 'Anmelden').click();
    cy.location('pathname').should('eq', '/auth/realms/articulorium/protocol/openid-connect/auth');
    cy.location('search').should('contain', 'ui_locales=de');
    cy.get('html').should('have.attr', 'lang', 'de');
  });

  it('serves every enabled locale and refuses others', () => {
    cy.request('/ui/api/i18n/ja').then((response) => {
      expect(response.headers['content-language']).to.eq('ja');
      expect(response.headers['etag']).to.match(/^"/);
      expect(response.body.locale).to.eq('ja');
      expect(response.body.messages.nav.browse).to.eq('閲覧');
      expect(response.body.locales.map((locale: { tag: string }) => locale.tag)).to.include.members([
        'en',
        'ar',
        'de',
        'es',
        'fr',
        'it',
        'ja',
        'ko',
        'nl',
        'pl',
        'pt-BR',
        'zh-CN',
      ]);
    });
    cy.request({ url: '/ui/api/i18n/xx', failOnStatusCode: false }).its('status').should('eq', 404);
  });

  it('localizes the platform HTML view and its errors', () => {
    cy.request({ url: '/platform/', headers: { Accept: 'text/html', 'Accept-Language': 'de' } }).then((response) => {
      expect(response.headers['content-language']).to.eq('de');
      expect(response.body).to.contain('<html lang="de" dir="ltr">').and.to.contain('Datei hochladen');
    });
    const missing = unique('missing');
    cy.request({ url: `/platform/${missing}`, headers: { 'Accept-Language': 'fr' }, failOnStatusCode: false }).then(
      (response) => {
        expect(response.status).to.eq(404);
        expect(response.headers['content-language']).to.eq('fr');
        expect(response.body).to.eq(`La ressource /${missing} est introuvable`);
      },
    );
  });

  it('passes the locale to the topology and IIIF services', () => {
    cy.request({ url: '/topology/topologies/book', headers: { 'Accept-Language': 'ja' } })
      .its('body')
      .should('have.property', 'name', '書籍');
    // Articulorium's own IIIF labels follow Accept-Language (the community root always exists)
    cy.request({ url: '/transform/iiif/communities', headers: { 'Accept-Language': 'de' } }).then((response) => {
      expect(response.headers['vary']).to.contain('Accept-Language');
      expect(response.body.homepage[0].label).to.deep.eq({ de: ['In Articulorium ansehen'] });
    });
  });
});
