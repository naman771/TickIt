import { existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
for (const name of ['.env.local', '.env']) {
  const path = root + name;
  if (existsSync(path)) process.loadEnvFile(path);
}
const jar = root + 'target/tickit-1.0.0-SNAPSHOT.jar';
if (!existsSync(jar)) throw new Error('Run npm run build before npm start');
const child = spawn('java', ['-jar', jar, ...process.argv.slice(2)], {
  cwd: root, env: process.env, stdio: 'inherit',
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('error', () => { console.error('Unable to start Java; install JDK 21.'); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
