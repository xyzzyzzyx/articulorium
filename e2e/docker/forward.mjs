// Forwards TCP ports of this container's localhost to services of the compose network, so the
// browser in the e2e container uses the same URLs as a browser on the host (and the platform
// mints the same resource IRIs).
//
//   node forward.mjs 8000:gateway:9000 [9001:platform:9000 ...]
import net from 'node:net';

for (const mapping of process.argv.slice(2)) {
  const [localPort, host, remotePort] = mapping.split(':');
  if (!localPort || !host || !remotePort) {
    console.error(`Invalid mapping "${mapping}", expected <local port>:<host>:<port>`);
    process.exit(2);
  }
  net
    .createServer((client) => {
      const upstream = net.connect(Number(remotePort), host);
      client.pipe(upstream).pipe(client);
      const close = () => {
        client.destroy();
        upstream.destroy();
      };
      client.on('error', close);
      upstream.on('error', close);
    })
    .listen(Number(localPort), '127.0.0.1', () => console.log(`localhost:${localPort} -> ${host}:${remotePort}`));
}
