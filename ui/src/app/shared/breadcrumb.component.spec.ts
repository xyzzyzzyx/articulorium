import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { BreadcrumbComponent } from './breadcrumb.component';

describe('BreadcrumbComponent', () => {
  it('links every ancestor and marks the current resource', () => {
    TestBed.configureTestingModule({ imports: [BreadcrumbComponent], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(BreadcrumbComponent);
    fixture.componentRef.setInput('path', 'books/moby dick');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    const links = [...element.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/browse', '/browse/books']);
    expect(element.querySelector('[aria-current]')?.textContent).toBe('moby dick');
  });

  it('adds a trailing label', () => {
    TestBed.configureTestingModule({ imports: [BreadcrumbComponent], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(BreadcrumbComponent);
    fixture.componentRef.setInput('path', 'books');
    fixture.componentRef.setInput('suffix', 'Versions');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('a[href="/browse/books"]')).not.toBeNull();
    expect(element.querySelector('[aria-current]')?.textContent).toBe('Versions');
  });
});
