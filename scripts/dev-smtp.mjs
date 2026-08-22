// Local stand-in for the mail relay.
//
// NotificationService composes a message and hands it to whatever SMTP host the admin
// configured in the UI (AI configuration -> Notifications, stored as notify_smtp_host /
// notify_smtp_port / notify_from / notify_enabled). Without a relay it logs the recipient
// count and returns false, which is honest but invisible: a demo cannot show the email that
// says "this incident was fixed automatically".
//
// This process is that relay. It accepts the message, prints it, and delivers it nowhere.
// Enough to prove the last step of the automatic lane — precedent -> execute -> notify —
// with no outbound network and no real mailbox.
//
// Speaks only the subset of SMTP that Spring's JavaMailSender uses on a plain connection:
// EHLO/HELO, MAIL FROM, RCPT TO, DATA, QUIT. No AUTH, no STARTTLS — advertising neither is
// what keeps the client on the plain path.
//
// ponytail: not an SMTP server. No AUTH, no TLS, no queueing, no size limit beyond the
// per-message cap, and it accepts every recipient. That is the point — it is a sink for a
// demo. Upgrade path: point the UI at a real relay (MailHog, Mailpit, the corporate smart
// host); nothing in the platform changes, because the host and port are already DB config.
//
// Run: node scripts/dev-smtp.mjs        (or the 'smtp' entry in .claude/launch.json)
//   PORT=2525 node scripts/dev-smtp.mjs
//
// Then, in the UI: AI configuration -> Notifications -> host localhost, port 1025, enable.

import { createServer } from 'node:net';

const PORT = Number(process.env.PORT ?? 1025);
const MAX_MESSAGE = 1_000_000;

createServer((socket) => {
    let buffer = '';
    let inData = false;
    let message = '';
    let envelope = { from: '', to: [] };

    const say = (line) => socket.write(line + '\r\n');

    const header = (name) => (message.match(new RegExp(`^${name}:\\s*(.+)$`, 'im')) ?? [, ''])[1].trim();

    const deliver = () => {
        // The envelope recipients are what the relay was actually asked to reach; the To:
        // header is only what the message claims. Print both, because "who would this have
        // reached" is the question a demo is answering.
        console.log('─'.repeat(72));
        console.log(`[MAIL] from=${envelope.from} rcpt=${envelope.to.join(', ')}`);
        console.log(`[MAIL] subject=${header('Subject')}`);
        const body = message.split(/\r?\n\r?\n/).slice(1).join('\n\n').trimEnd();
        console.log(body || '(no body)');
        console.log('─'.repeat(72));
        message = '';
        envelope = { from: '', to: [] };
    };

    const handle = (line) => {
        if (inData) {
            if (line === '.') {
                inData = false;
                deliver();
                return say('250 2.0.0 Ok: queued to nowhere');
            }
            // Dot-stuffing, per RFC 5321: a leading '..' in the stream is a literal '.'.
            message += (line.startsWith('..') ? line.slice(1) : line) + '\n';
            if (message.length > MAX_MESSAGE) return socket.destroy();
            return;
        }
        const verb = line.slice(0, 4).toUpperCase();
        if (verb === 'EHLO' || verb === 'HELO') return say('250 dev-smtp');
        if (verb === 'MAIL') {
            envelope.from = (line.match(/<(.*)>/) ?? [, ''])[1];
            return say('250 2.1.0 Ok');
        }
        if (verb === 'RCPT') {
            envelope.to.push((line.match(/<(.*)>/) ?? [, ''])[1]);
            return say('250 2.1.5 Ok');
        }
        if (verb === 'DATA') {
            inData = true;
            return say('354 End data with <CR><LF>.<CR><LF>');
        }
        if (verb === 'RSET') {
            envelope = { from: '', to: [] };
            message = '';
            return say('250 2.0.0 Ok');
        }
        if (verb === 'NOOP') return say('250 2.0.0 Ok');
        if (verb === 'QUIT') {
            say('221 2.0.0 Bye');
            return socket.end();
        }
        say('502 5.5.2 Not implemented');
    };

    say('220 dev-smtp ready');
    socket.on('data', (chunk) => {
        buffer += chunk.toString('utf8');
        // Split on LF and drop a trailing CR. Looking for CRLF first would swallow a bare LF
        // that arrived earlier in the same chunk into the middle of a "line".
        let cut;
        while ((cut = buffer.indexOf('\n')) !== -1) {
            const line = buffer.slice(0, cut).replace(/\r$/, '');
            buffer = buffer.slice(cut + 1);
            handle(line);
        }
    });
    socket.on('error', () => socket.destroy());
}).listen(PORT, () => console.log(`dev smtp sink listening on localhost:${PORT} — messages are printed, never delivered`));
