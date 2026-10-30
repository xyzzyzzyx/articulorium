// Waits until every URL answers 200 (the gateway routes through Consul, so its routes become
// available a little after the services report healthy).
//
//   node wait.mjs [--timeout=<seconds>] <url> ...
const args = process.argv.slice(2);
const timeoutArg = args.find((arg) => arg.startsWith('--timeout='));
const timeout = Number(timeoutArg?.split('=')[1] ?? 180) * 1000;
const urls = args.filter((arg) => !arg.startsWith('--'));
const deadline = Date.now() + timeout;

for (const url of urls) {
  let last = 'no answer';
  for (;;) {
    try {
      const response = await fetch(url, { redirect: 'manual' });
      if (response.status === 200) {
        console.log(`ready: ${url}`);
        break;
      }
      last = `HTTP ${response.status}`;
    } catch (error) {
      last = error.cause?.code ?? error.message;
    }
    if (Date.now() > deadline) {
      console.error(`timed out waiting for ${url} (${last})`);
      process.exit(1);
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
}
