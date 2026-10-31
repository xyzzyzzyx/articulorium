/** A notification in an inbox or outbox listing. */
export interface NotificationSummary {
  readonly id: string;
  readonly uri?: string | null;
  readonly notificationId?: string | null;
  readonly types: readonly string[];
  readonly pattern?: string | null;
  readonly origin?: string | null;
  readonly target?: string | null;
  readonly object?: string | null;
  readonly actor?: string | null;
  readonly status: 'RECEIVED' | 'SENT' | 'FAILED';
  readonly statusDetail?: string | null;
  readonly agent?: string | null;
  readonly received: string;
}

/** An inbox or outbox. */
export interface NotificationList {
  readonly resource: string;
  readonly inbox: string;
  readonly notifications: readonly NotificationSummary[];
}

/** A COAR Notify pattern the send form offers. */
export interface NotifyPattern {
  readonly id: string;
  readonly label: string;
  readonly types: readonly string[];
}

/** The COAR Notify patterns offered for sending. */
export const NOTIFY_PATTERNS: readonly NotifyPattern[] = [
  { id: 'offer-review', label: 'Request review (Offer Review)', types: ['Offer', 'coar-notify:ReviewAction'] },
  { id: 'offer-endorsement', label: 'Request endorsement (Offer Endorsement)', types: ['Offer', 'coar-notify:EndorsementAction'] },
  { id: 'offer-ingest', label: 'Request ingest (Offer Ingest)', types: ['Offer', 'coar-notify:IngestAction'] },
  { id: 'announce-review', label: 'Announce review', types: ['Announce', 'coar-notify:ReviewAction'] },
  { id: 'announce-endorsement', label: 'Announce endorsement', types: ['Announce', 'coar-notify:EndorsementAction'] },
  { id: 'announce-relationship', label: 'Announce relationship', types: ['Announce', 'coar-notify:RelationshipAction'] },
  { id: 'announce-ingest', label: 'Announce ingest', types: ['Announce', 'coar-notify:IngestAction'] },
  { id: 'accept', label: 'Accept', types: ['Accept'] },
  { id: 'reject', label: 'Reject', types: ['Reject'] },
  { id: 'undo', label: 'Undo (retract)', types: ['Undo'] },
];

/** What the send form collects. */
export interface NotificationDraft {
  readonly pattern: string;
  readonly targetId: string;
  readonly targetInbox: string;
  readonly objectId: string;
  /** Persistent identifier of the object (`ietf:cite-as`), optional. */
  readonly citeAs?: string;
  /** The notification an Accept, Reject or Undo answers (`inReplyTo`), optional. */
  readonly inReplyTo?: string;
  readonly summary?: string;
}

/**
 * Builds a COAR Notify notification. The platform adds the `id`, the contexts and the `origin`
 * (the sending container as a service with its inbox).
 *
 * @param draft - the form values
 * @param actorName - the sender's display name
 * @returns the notification as JSON
 */
export function buildNotification(draft: NotificationDraft, actorName: string): Record<string, unknown> {
  const pattern = NOTIFY_PATTERNS.find((candidate) => candidate.id === draft.pattern) ?? NOTIFY_PATTERNS[0];
  const object: Record<string, unknown> = { id: draft.objectId.trim() };
  if (draft.citeAs?.trim()) {
    object['ietf:cite-as'] = draft.citeAs.trim();
  }
  const notification: Record<string, unknown> = {
    '@context': ['https://www.w3.org/ns/activitystreams', 'https://coar-repositories.org/notify'],
    type: [...pattern.types],
    actor: { type: 'Service', name: actorName },
    target: { id: draft.targetId.trim(), inbox: draft.targetInbox.trim(), type: 'Service' },
    object,
  };
  if (draft.inReplyTo?.trim()) {
    notification['inReplyTo'] = draft.inReplyTo.trim();
  }
  if (draft.summary?.trim()) {
    notification['summary'] = draft.summary.trim();
  }
  return notification;
}
