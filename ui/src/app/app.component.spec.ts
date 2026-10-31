import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { provideTestConfig } from './testing/test-config';
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
    expect(links).toEqual(['/browse', '/communities', '/topologies', '/search', 'http://host/swagger-ui.html', '/about']);
    expect(element.querySelector('.ui-account')?.textContent).toContain('Sign in');
  });
});
