import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { BINARY_NT, CONTAINER_NT, TEST_BASE, provideTestConfig } from '../testing/test-config';
import { ResourcePageComponent } from './resource-page.component';

describe('ResourcePageComponent', () => {
  let fixture: ComponentFixture<ResourcePageComponent>;
  let http: HttpTestingController;
  const dialog = { open: vi.fn() };

  const open = async (path: string): Promise<HTMLElement> => {
    fixture = TestBed.createComponent(ResourcePageComponent);
    fixture.componentRef.setInput('path', path);
    fixture.detectChanges();
    // run the resource's effect so the request is sent (the fixture is not stable until answered)
    TestBed.tick();
    return fixture.nativeElement as HTMLElement;
  };

  const settle = async (): Promise<HTMLElement> => {
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(() => {
    dialog.open.mockReset();
    TestBed.configureTestingModule({
      imports: [ResourcePageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig(),
        { provide: MatDialog, useValue: dialog },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows a container: title, metadata, children, properties and actions', async () => {
    await open('books');
    http
      .expectOne(`${TEST_BASE}books`)
      .flush(CONTAINER_NT, { headers: { Allow: 'GET, HEAD, OPTIONS, POST, PUT, PATCH, DELETE' } });
    const element = await settle();

    expect(element.querySelector('h1')?.textContent).toBe('Books');
    expect(element.textContent).toContain('by alice');
    const children = [...element.querySelectorAll('.ui-children a')].map((a) => a.textContent);
    expect(children).toEqual(['cover.png', 'moby-dick']);
    expect(element.querySelector('ui-property-table')?.textContent).toContain('dcterms:title');
    expect(element.textContent).toContain('Other resources');
    expect(element.textContent).toContain('Librarian');
    expect(element.querySelector('ui-create-child-form')).not.toBeNull();
    expect(element.querySelector('ui-sparql-update-form textarea')).not.toBeNull();
    expect(element.textContent).toContain('Create version');
    expect(element.textContent).toContain('Delete');
  });

  it('shows a binary with its content actions', async () => {
    await open('books/cover.png');
    http.expectOne({ method: 'GET', url: `${TEST_BASE}books/cover.png` }).flush(BINARY_NT);
    http.expectOne({ method: 'HEAD', url: `${TEST_BASE}books/cover.png` }).flush(null, { headers: { Allow: 'GET, HEAD, PUT, DELETE' } });
    const element = await settle();

    expect(element.querySelector('h1')?.textContent).toBe('cover.png');
    expect(element.querySelector('ui-binary-content')?.textContent).toContain('image/png');
    expect(element.querySelector(`a[href="${TEST_BASE}books/cover.png"][download]`)).not.toBeNull();
    expect(element.querySelector('a[href="/fixity/books/cover.png"]')).not.toBeNull();
    expect(element.querySelector('ui-create-child-form')).toBeNull();
    expect(element.textContent).toContain('Update content');
  });

  it('reports load failures', async () => {
    await open('missing');
    http.expectOne(`${TEST_BASE}missing`).flush({ detail: 'Not found: /missing' }, { status: 404, statusText: 'Not Found' });
    const element = await settle();

    expect(element.querySelector('.ui-error')?.textContent).toContain('Not found: /missing');
  });

  it('hides write actions the platform does not allow and versioning on the root', async () => {
    await open('');
    http.expectOne(TEST_BASE).flush(
      `<${TEST_BASE}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#BasicContainer> .`,
      { headers: { Allow: 'GET, HEAD, OPTIONS' } },
    );
    const element = await settle();

    expect(element.querySelector('h1')?.textContent).toBe('Root');
    expect(element.querySelector('ui-create-child-form')).toBeNull();
    expect(element.querySelector('ui-sparql-update-form')).toBeNull();
    expect(element.textContent).not.toContain('Create version');
  });

  it('deletes after confirmation and opens the parent', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });
    await open('books');
    http.expectOne(`${TEST_BASE}books`).flush(CONTAINER_NT, { headers: { Allow: 'GET, DELETE' } });
    const element = await settle();

    (element.querySelector('button.ui-danger') as HTMLButtonElement).click();
    http.expectOne({ method: 'DELETE', url: `${TEST_BASE}books` }).flush('', { status: 204, statusText: 'No Content' });

    expect(dialog.open).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/', 'browse']);
  });
});
