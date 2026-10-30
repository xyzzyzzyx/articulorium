import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { BINARY_NT, CONTAINER_NT, TEST_BASE, provideTestConfig } from '../../testing/test-config';
import { LDP } from '../rdf/vocabulary';
import { LdpService, N_TRIPLES, SPARQL_UPDATE, contentDisposition, downloadName } from './ldp.service';

describe('LdpService', () => {
  let service: LdpService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()] });
    service = TestBed.inject(LdpService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads RDF sources as N-Triples', async () => {
    const loading = firstValueFrom(service.load('/books/'));
    const request = http.expectOne(`${TEST_BASE}books`);
    expect(request.request.headers.get('Accept')).toBe(N_TRIPLES);
    request.flush(CONTAINER_NT, { headers: { Allow: 'GET, HEAD, POST, PATCH, DELETE', ETag: 'W/"1"', Link: `<${LDP.basicContainer}>; rel="type"` } });

    const view = await loading;
    expect(view.path).toBe('books');
    expect(view.subject.value).toBe(`${TEST_BASE}books`);
    expect(view.graph.title(view.subject)).toBe('Books');
    expect(view.allow.has('PATCH')).toBe(true);
    expect(view.etag).toBe('W/"1"');
    expect(view.linkTypes).toEqual([LDP.basicContainer]);
    expect(view.binary).toBeUndefined();
  });

  it('recognises binaries and reads their headers', async () => {
    const loading = firstValueFrom(service.load('books/cover.png'));
    http.expectOne({ method: 'GET', url: `${TEST_BASE}books/cover.png` }).flush(BINARY_NT);
    http.expectOne({ method: 'HEAD', url: `${TEST_BASE}books/cover.png` }).flush(null, { headers: { Allow: 'GET, PUT, DELETE' } });

    const view = await loading;
    expect(view.binary?.mimeType).toBe('image/png');
    expect(view.binary?.size).toBe(2048);
    expect(view.binary?.descriptionUrl).toBe(`${TEST_BASE}books/cover.png/description`);
    expect(view.allow.has('PUT')).toBe(true);
  });

  it('creates containers by POST with a type link and Turtle', async () => {
    const creating = firstValueFrom(service.createChild('books', { kind: 'direct', turtle: '<> a <urn:x> .' }));
    const request = http.expectOne({ method: 'POST', url: `${TEST_BASE}books` });
    expect(request.request.headers.get('Link')).toBe(`<${LDP.directContainer}>; rel="type"`);
    expect(request.request.headers.get('Content-Type')).toBe('text/turtle');
    request.flush('', { status: 201, statusText: 'Created', headers: { Location: `${TEST_BASE}books/abc` } });

    expect(await creating).toBe(`${TEST_BASE}books/abc`);
  });

  it('creates children with an identifier by PUT without overwriting', async () => {
    const file = new Blob(['bytes'], { type: 'text/plain' });
    const creating = firstValueFrom(service.createChild('books', { kind: 'binary', slug: 'notes.txt', file, filename: 'notes é.txt' }));
    const request = http.expectOne({ method: 'PUT', url: `${TEST_BASE}books/notes.txt` });
    expect(request.request.headers.get('If-None-Match')).toBe('*');
    expect(request.request.headers.get('Content-Type')).toBe('text/plain');
    expect(request.request.headers.get('Content-Disposition')).toContain("filename*=UTF-8''notes%20%C3%A9.txt");
    request.flush('', { status: 201, statusText: 'Created' });

    expect(await creating).toBe(`${TEST_BASE}books/notes.txt`);
  });

  it('refuses binaries without a file', () => {
    expect(() => service.createChild('books', { kind: 'binary' })).toThrowError(/file/);
  });

  it('patches with SPARQL Update and If-Match', async () => {
    const patching = firstValueFrom(service.patch(`${TEST_BASE}books`, 'INSERT DATA {}', 'W/"1"'));
    const request = http.expectOne({ method: 'PATCH', url: `${TEST_BASE}books` });
    expect(request.request.headers.get('Content-Type')).toBe(SPARQL_UPDATE);
    expect(request.request.headers.get('If-Match')).toBe('W/"1"');
    request.flush('', { status: 204, statusText: 'No Content' });
    await patching;
  });

  it('replaces binaries, deletes, versions and checks fixity', async () => {
    const replacing = firstValueFrom(service.replaceBinary('a.txt', new Blob(['x']), 'text/plain'));
    http.expectOne({ method: 'PUT', url: `${TEST_BASE}a.txt` }).flush('', { status: 204, statusText: 'No Content' });
    await replacing;

    const deleting = firstValueFrom(service.delete('a.txt'));
    http.expectOne({ method: 'DELETE', url: `${TEST_BASE}a.txt` }).flush('', { status: 204, statusText: 'No Content' });
    await deleting;

    const versioning = firstValueFrom(service.createVersion('a'));
    http.expectOne({ method: 'POST', url: `${TEST_BASE}a/fcr:versions` }).flush('', {
      status: 201,
      statusText: 'Created',
      headers: { Location: `${TEST_BASE}a/fcr:versions/v2` },
    });
    expect(await versioning).toBe(`${TEST_BASE}a/fcr:versions/v2`);

    const listing = firstValueFrom(service.versions('a'));
    http.expectOne(`${TEST_BASE}a/fcr:versions`).flush({ original: '', timemap: '', versions: [{ id: 'v1', uri: 'u', hasContent: false }] });
    expect((await listing).versions[0].id).toBe('v1');

    const checking = firstValueFrom(service.fixity('a.txt'));
    http.expectOne(`${TEST_BASE}a.txt/fcr:fixity`).flush({ status: 'SUCCESS' });
    expect((await checking).status).toBe('SUCCESS');
  });

  it('reads mementos and links the bytes of binary versions', async () => {
    const reading = firstValueFrom(service.memento('books/cover.png', 'v1'));
    http
      .expectOne(`${TEST_BASE}books/cover.png/fcr:versions/v1`)
      .flush(BINARY_NT, { headers: { 'Memento-Datetime': 'Fri, 30 Oct 2026 12:00:00 GMT' } });

    const memento = await reading;
    expect(memento.datetime).toBe('Fri, 30 Oct 2026 12:00:00 GMT');
    expect(memento.contentUrl).toBe(`${TEST_BASE}books/cover.png/fcr:versions/v1`);
  });

  it('searches with repeated conditions', async () => {
    const searching = firstValueFrom(
      service.search({
        conditions: [
          { field: 'fedora_id', operator: '=', value: 'books/*' },
          { field: 'content_size', operator: '>', value: '10' },
        ],
        maxResults: 20,
        offset: 40,
        orderBy: 'created',
        order: 'desc',
      }),
    );
    const request = http.expectOne((req) => req.url === `${TEST_BASE}fcr:search`);
    expect(request.request.params.getAll('condition')).toEqual(['fedora_id=books/*', 'content_size>10']);
    expect(request.request.params.get('offset')).toBe('40');
    expect(request.request.params.get('order')).toBe('desc');
    request.flush({ items: [], pagination: { maxResults: 20, offset: 40, totalResults: 0, pageNumber: 2, numberOfPages: 0 } });
    expect((await searching).items).toEqual([]);
  });

  it('asks the service to detect media types', async () => {
    const detecting = firstValueFrom(service.detectMediaType(new File(['%PDF'], 'doc')));
    const request = http.expectOne({ method: 'POST', url: 'api/media-type' });
    expect(request.request.body instanceof FormData).toBe(true);
    request.flush({ filename: 'doc', mediaType: 'application/pdf', size: 4 });
    expect((await detecting).mediaType).toBe('application/pdf');
  });

  it('downloads binaries under their recorded file name', () => {
    expect(downloadName({ url: `${TEST_BASE}books/b1`, filename: 'notes.txt' })).toBe('notes.txt');
  });

  it('falls back to the last path segment when no file name was recorded', () => {
    expect(downloadName({ url: `${TEST_BASE}books/data.csv` })).toBe('data.csv');
    expect(downloadName({ url: `${TEST_BASE}books/caf%C3%A9.txt/` })).toBe('café.txt');
    expect(downloadName({ url: `${TEST_BASE}books/bad%E0` })).toBe('bad%E0');
  });

  it('encodes file names in Content-Disposition', () => {
    expect(contentDisposition('a "b".txt')).toBe('attachment; filename="a _b_.txt"; filename*=UTF-8\'\'a%20%22b%22.txt');
  });
});
