import { english, lookup } from './english';

describe('english', () => {
  it('looks up built-in texts and interpolates placeholders', () => {
    expect(english('nav.browse')).toBe('Browse');
    expect(english('panel.add', { label: 'Page' })).toBe('Add Page');
    expect(english('no.such.key')).toBe('no.such.key');
  });

  it('keeps placeholders without a value', () => {
    expect(lookup({ a: 'Hello {{name}}' }, 'a')).toBe('Hello {{name}}');
    expect(lookup({ a: { b: 'x' } }, 'a')).toBe('a');
  });
});
