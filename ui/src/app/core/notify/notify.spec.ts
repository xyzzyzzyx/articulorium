import { buildNotification } from './notify';

describe('buildNotification', () => {
  it('builds a COAR Notify offer', () => {
    const notification = buildNotification(
      {
        pattern: 'offer-endorsement',
        targetId: ' https://overlay.example/ ',
        targetInbox: 'https://overlay.example/inbox',
        objectId: 'http://h/platform/c/item',
        citeAs: 'https://doi.org/10.5555/1',
        summary: 'Please endorse',
      },
      'Articulorum',
    );
    expect(notification).toEqual({
      '@context': ['https://www.w3.org/ns/activitystreams', 'https://coar-repositories.org/notify'],
      type: ['Offer', 'coar-notify:EndorsementAction'],
      actor: { type: 'Service', name: 'Articulorum' },
      target: { id: 'https://overlay.example/', inbox: 'https://overlay.example/inbox', type: 'Service' },
      object: { id: 'http://h/platform/c/item', 'ietf:cite-as': 'https://doi.org/10.5555/1' },
      summary: 'Please endorse',
    });
  });

  it('answers earlier notifications', () => {
    const notification = buildNotification(
      { pattern: 'accept', targetId: 'https://a/', targetInbox: 'https://a/inbox', objectId: 'urn:x', inReplyTo: 'urn:uuid:1' },
      'Repo',
    );
    expect(notification['type']).toEqual(['Accept']);
    expect(notification['inReplyTo']).toBe('urn:uuid:1');
  });
});
