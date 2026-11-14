# FocusQuest server

Cloud service for FocusQuest classes. Teachers sign in from any browser; student phones
join a class with a 6-letter code and stream live data over an encrypted WebSocket.

- **Accounts**: schools (organisations), admins and teachers. The first visit sets up the
  school and its admin; admins add teachers, who get a one-time password.
- **Classes**: each has a join code (change it any time), a roster, and archive/delete.
- **Live dashboard**: every student's attention, calm, signal, game, time on goal and alerts,
  updated every second; group sessions, cheers, and a projector mode.
- **Reports**: student, class and live-session PDFs, plus CSV, generated on the server.
- **Phones reconnect automatically** and resend results until the server confirms them, so a
  dropped connection or app restart doesn't lose data.

## Run locally

```bash
cd server
npm install
npm run dev
```

Open http://localhost:4000 and create the school account. In the Android app (debug build):
**Settings → Join a class → Online class**, server `http://10.0.2.2:4000` from the emulator,
or `http://<your PC's IP>:4000` from a phone on the same network.

```bash
npm test        # API and report tests
npm run build   # compile to dist/
npm start       # run the compiled server
```

## Deploy with HTTPS (Docker)

On any Linux server with Docker and a DNS name pointing at it:

```bash
cd server
cp .env.example .env    # set FOCUSQUEST_DOMAIN=focus.yourschool.org
docker compose up -d
```

Caddy gets and renews the HTTPS certificate automatically. Teachers open
`https://focus.yourschool.org`; students enter that address and the class code in the app.

Other hosts (Render, Railway, Fly.io, a VM with nginx): build the `Dockerfile`, mount a
persistent volume at `/data`, set `TRUST_PROXY=true`, and let the platform terminate HTTPS.
Release builds of the app only accept `https://` server addresses.

**Back up** the `/data` volume (`focusquest.db`); it holds all accounts and session history.
Run a single instance, because live class state is kept in memory.

## Security

- Passwords hashed with scrypt; constant-time checks; unknown emails take as long as wrong
  passwords. Sign-in and join endpoints are rate-limited.
- Teacher sessions use an HttpOnly, SameSite=Strict cookie (Secure in production). Every
  write needs a custom header, so other websites can't send commands.
- Strict Content-Security-Policy (no inline scripts), HSTS over HTTPS, no framing.
- Each school only sees its own classes and students. Student devices use per-device tokens
  (stored hashed); a teacher can remove a student, which disconnects that phone at once.
- All inputs are validated and size-limited; student names are always rendered as text.

## Data and privacy

Stored per student: name, age, avatar colour, device model, and session results
(time on goal, averages, attention timeline). No raw EEG is uploaded. Teachers can delete a
student or a whole class with all its data. Agree data-handling terms with each school before use.
