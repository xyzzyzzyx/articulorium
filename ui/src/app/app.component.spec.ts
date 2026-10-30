import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { TEST_BASE, provideTestConfig } from './testing/test-config';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideRouter([]), provideTestConfig()],
    }).compileComponents();
  });

  it('shows the configured title and the navigation', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('.ui-brand')?.textContent).toContain('Test repository');
    const links = [...element.querySelectorAll('.ui-nav a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/browse', '/search', `${TEST_BASE}.well-known/swagger-ui.html`, '/about']);
  });
});
