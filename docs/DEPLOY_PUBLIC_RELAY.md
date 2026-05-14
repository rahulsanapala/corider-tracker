# Deploy Public Relay

To use the Android app outside your local Wi-Fi, the relay must run on a public HTTPS URL.

Local URLs do not work for real-world rides:

- `http://10.0.2.2:8080` works only inside the Android emulator.
- `http://192.168.x.x:8080` works only on the same Wi-Fi network.
- A real app needs something like `https://corider-relay.example.com`.

## Easiest Test Deployment

Use a Docker-capable host such as Render, Railway, Fly.io, or a small VPS.

This repo includes:

```text
Dockerfile
render.yaml
```

The relay reads the hosting provider's `PORT` environment variable and binds to `0.0.0.0`, which is what cloud hosts expect.

## Render Flow

1. Push this repo to GitHub.
2. Create a new Render web service from the repo.
3. Choose Docker deployment.
4. Use the default Dockerfile.
5. Deploy.
6. Open the service URL. `/healthz` should return:

```json
{"ok":true,"service":"corider-relay"}
```

Use the Render HTTPS URL in the Android app, for example:

```text
https://corider-relay.onrender.com
```

## Railway Flow

1. Push this repo to GitHub.
2. Create a Railway project from the repo.
3. Railway should detect the Dockerfile.
4. Deploy.
5. Use the generated public HTTPS domain in the Android app.

## VPS Flow

On a server with Docker:

```bash
docker build -t corider-relay .
docker run -p 8080:8080 --restart unless-stopped corider-relay
```

Then put Nginx or Caddy in front of it for HTTPS.

## Production Notes

The current relay is fine for a private MVP, but not a finished production backend.

Before public launch, add:

- HTTPS only.
- Authenticated ride join codes.
- Short-lived rider tokens.
- Rate limiting.
- Spoof protection so one rider cannot send another rider's ID.
- Redis or another shared store if you run more than one server instance.
- Better transport such as MQTT over TLS for larger groups.

