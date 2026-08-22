// Local stand-in for the remediation executor agent.
//
// The platform never runs a shell. An approved script is POSTed here as
// {script, language, target, connection} (see RemediationToolRegistry.dispatchToExecutor)
// and this process decides what to do with it. In production that is an agent on the
// target network holding the host credentials.
//
// This one executes NOTHING. It logs the script and answers 200, which is enough to
// exercise the whole chain locally — approve -> dry run -> execute -> ActionExecution
// SUCCEEDED -> precedent -> unattended re-run — without a real host to break.
//
// It also answers POST /probe {target, connection}: 200 if it believes it could reach
// that host, 409 if not. That is the "try without a token first" step — an empty
// connection means "use whatever path you already have", and only a 409 sends the
// incident back to a human to name the server and the method.
//
// ponytail: no auth check and no allow-list. A real agent MUST verify the
// Authorization bearer token (mcp.autonomy.executor-token) and refuse targets outside
// its remit; a localhost stub with nothing to protect must not pretend it does. Do not
// grow this file into that agent — write it in the target's own repo.
//
// Run: node scripts/dev-executor.mjs   (or the 'executor' entry in .claude/launch.json)
//   node scripts/dev-executor.mjs store-0042-pos-01,store-0042-bo-01
//   EXECUTOR_KNOWN_HOSTS=store-0042-pos-01 node scripts/dev-executor.mjs

import { createServer } from 'node:http';
import { promises as dns } from 'node:dns';

const PORT = Number(process.env.PORT ?? 9099);
const MAX_BODY = 1_000_000;

// Hosts this stub pretends it can reach, so a realistic store hostname works in a demo
// without editing /etc/hosts. Anything resolvable in DNS is reachable too; everything
// else answers 409, which is the interesting half of the demo.
const KNOWN = (process.env.EXECUTOR_KNOWN_HOSTS ?? process.argv[2] ?? '')
    .split(',').map((h) => h.trim().toLowerCase()).filter(Boolean);

const readJson = (req, res, done) => {
    let body = '';
    req.on('data', (chunk) => {
        body += chunk;
        if (body.length > MAX_BODY) req.destroy();
    });
    req.on('end', () => {
        try {
            done(JSON.parse(body));
        } catch {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            res.end('malformed json\n');
        }
    });
};

const probe = async (req, res) =>
    readJson(req, res, async (job) => {
        const target = String(job.target ?? '').toLowerCase();
        const via = job.connection ? String(job.connection) : 'default path';
        if (!target) {
            res.writeHead(409, { 'Content-Type': 'text/plain' });
            res.end('no target given\n');
            return;
        }
        let reachable = KNOWN.includes(target);
        if (!reachable) {
            // Real resolution, not a coin flip: a name that resolves is one this agent could
            // plausibly reach, and a typo'd hostname fails here exactly as it would in prod.
            try {
                await dns.lookup(target);
                reachable = true;
            } catch {
                reachable = false;
            }
        }
        console.log(`[PROBE] target='${target}' via ${via} -> ${reachable ? 'REACHABLE' : 'UNREACHABLE'}`);
        res.writeHead(reachable ? 200 : 409, { 'Content-Type': 'text/plain' });
        res.end(reachable
            ? `Reached '${target}' over ${via}.\n`
            : `Cannot reach '${target}' over ${via}: name does not resolve and it is not in this agent's known hosts.\n`);
    });

const execute = (req, res) =>
    readJson(req, res, (job) => {
        const script = job.script ?? '';
        const lines = script ? script.split('\n').length : 0;
        const via = job.connection ? String(job.connection) : 'default path';
        console.log(`[EXEC] target='${job.target ?? ''}' via ${via} language=${job.language ?? '?'} lines=${lines}`);
        console.log(script);
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end(
            `Accepted ${lines} line(s) of ${job.language ?? 'shell'} for target '${job.target ?? ''}' over ${via}.\n` +
            `NOT EXECUTED: this is the local stand-in for the executor agent.\n`
        );
    });

createServer((req, res) => {
    if (req.method === 'POST' && req.url.startsWith('/execute')) return execute(req, res);
    if (req.method === 'POST' && req.url.startsWith('/probe')) return probe(req, res);
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('dev executor: POST /execute {"script","language","target","connection"}'
        + ' | POST /probe {"target","connection"}\n');
}).listen(PORT, () => console.log(
    `dev executor listening on http://localhost:${PORT}`
    + (KNOWN.length ? ` (known hosts: ${KNOWN.join(', ')})` : '')));
