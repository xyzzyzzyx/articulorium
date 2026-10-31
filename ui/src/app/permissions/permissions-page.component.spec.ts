import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ACL } from '../core/rdf/vocabulary';
import { AccessInfo } from '../core/webac/webac';
import { TEST_BASE, provideTestConfig } from '../testing/test-config';
import { PermissionsPageComponent } from './permissions-page.component';

const ACCESS: AccessInfo = {
  agent: { webId: 'info:consul/agent/jane', groups: [], authenticated: true },
  enforced: true,
  resource: '/books',
  aclResource: '/',
  inherited: true,
  userModes: ['read', 'write', 'control'],
  publicModes: ['read'],
  authorizations: [
    {
      id: '/fcr:acl#public',
      agents: [],
      agentClasses: [ACL.foafAgent],
      agentGroups: [],
      modes: [`${ACL.ns}Read`],
      accessTo: ['/'],
      defaults: ['/'],
      accessToClass: [],
    },
  ],
};

describe('PermissionsPageComponent', () => {
  let fixture: ComponentFixture<PermissionsPageComponent>;
  let http: HttpTestingController;

  const render = async (): Promise<HTMLElement> => {
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PermissionsPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTestConfig()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PermissionsPageComponent);
    fixture.componentRef.setInput('path', 'books');
    fixture.detectChanges();
    TestBed.tick();
  });

  afterEach(() => http.verify());

  it('shows the agent, its access and the inherited authorizations', async () => {
    http.expectOne(`${TEST_BASE}.well-known/webac?resource=books`).flush(ACCESS);
    const element = await render();

    expect(element.querySelector('.ui-permissions-agent')?.textContent).toContain('info:consul/agent/jane');
    expect(element.querySelector('.ui-permissions-user')?.textContent).toContain('read, write, control');
    expect(element.querySelector('.ui-permissions-source')?.textContent).toContain('Inherited from');
    expect(element.querySelector('.ui-authorization')?.textContent).toContain('Everyone');
  });

  it('saves the resource own access control list', async () => {
    http.expectOne(`${TEST_BASE}.well-known/webac?resource=books`).flush(ACCESS);
    const element = await render();

    const component = fixture.componentInstance as unknown as { iri: string; add(): void; save(): void };
    component.iri = 'info:consul/role/editors';
    (fixture.componentInstance as unknown as { kind: string }).kind = 'group';
    component.add();
    component.save();
    const request = http.expectOne({ method: 'PUT', url: `${TEST_BASE}books/fcr:acl` });
    expect(request.request.headers.get('Content-Type')).toBe('text/turtle');
    expect(request.request.body).toContain('acl:agentClass foaf:Agent');
    expect(request.request.body).toContain('acl:agentGroup <info:consul/role/editors>');
    expect(request.request.body).toContain(`acl:accessTo <${TEST_BASE}books>`);
    request.flush('', { status: 201, statusText: 'Created' });
    TestBed.tick();
    http.expectOne(`${TEST_BASE}.well-known/webac?resource=books`).flush({ ...ACCESS, inherited: false, aclResource: '/books' });
    expect(element).toBeTruthy();
  });

  it('explains when the agent lacks control', async () => {
    http.expectOne(`${TEST_BASE}.well-known/webac?resource=books`).flush({ ...ACCESS, userModes: ['read'], authorizations: [] });
    const element = await render();

    expect(element.textContent).toContain('You need control access');
    expect(element.querySelector('.ui-grant-form')).toBeNull();
  });
});
