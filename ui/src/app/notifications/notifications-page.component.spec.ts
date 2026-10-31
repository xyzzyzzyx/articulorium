import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { TEST_BASE, provideTestConfig } from '../testing/test-config';
import { NotificationsPageComponent } from './notifications-page.component';

describe('NotificationsPageComponent', () => {
  let fixture: ComponentFixture<NotificationsPageComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NotificationsPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTestConfig()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NotificationsPageComponent);
    fixture.componentRef.setInput('path', 'communities/c');
    fixture.detectChanges();
    TestBed.tick();
  });

  afterEach(() => http.verify());

  it('lists received and sent notifications and sends new ones', async () => {
    http.expectOne(`${TEST_BASE}communities/c/fcr:inbox`).flush({
      resource: `${TEST_BASE}communities/c`,
      inbox: `${TEST_BASE}communities/c/fcr:inbox`,
      notifications: [
        {
          id: '1',
          uri: `${TEST_BASE}communities/c/fcr:inbox/1`,
          types: ['Announce', 'coar-notify:ReviewAction'],
          pattern: 'Announce Review',
          origin: 'https://review.example/',
          object: 'https://review.example/r/1',
          status: 'RECEIVED',
          received: '2026-10-31T12:00:00Z',
        },
      ],
    });
    http.expectOne(`${TEST_BASE}communities/c/fcr:outbox`).flush({ resource: '', inbox: '', notifications: [] });
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('.ui-inbox')?.textContent).toContain('Announce Review');
    expect(element.querySelector('.ui-outbox')?.textContent).toContain('No notifications sent');

    const component = fixture.componentInstance as unknown as { targetId: string; targetInbox: string; send(): void };
    component.targetId = 'https://review.example/';
    component.targetInbox = 'https://review.example/inbox';
    component.send();
    const request = http.expectOne({ method: 'POST', url: `${TEST_BASE}communities/c/fcr:outbox` });
    expect(request.request.headers.get('Content-Type')).toBe('application/ld+json');
    expect((request.request.body as { type: string[] }).type).toEqual(['Offer', 'coar-notify:ReviewAction']);
    expect((request.request.body as { object: { id: string } }).object.id).toBe(`${TEST_BASE}communities/c`);
    request.flush({ id: '2', types: [], status: 'SENT', received: '2026-10-31T12:01:00Z' }, { status: 201, statusText: 'Created' });
    TestBed.tick();
    http.expectOne(`${TEST_BASE}communities/c/fcr:outbox`).flush({ resource: '', inbox: '', notifications: [] });
  });
});
