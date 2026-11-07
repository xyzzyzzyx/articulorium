/**
 * The discovery API's internal address (`DISCOVERY_API_URL`): the same path as visitors use
 * (`<origin>/discover/api/`) on an address reachable from this server, the gateway inside the
 * deployment's network (`http://gateway:9000/discover/api/`) or, in development, the public one.
 *
 * @param env - the environment
 */
export function discoveryApiUrl(env: Record<string, string | undefined> = process.env): string {
  const url = env['DISCOVERY_API_URL'] || 'http://localhost:8000/discover/api/';
  return url.endsWith('/') ? url : `${url}/`;
}
