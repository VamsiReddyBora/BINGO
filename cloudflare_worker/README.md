# Bingo FCM Push Relay (Cloudflare Worker)

This zero-maintenance, 100% free Cloudflare Worker connects your Bingo Android app to the modern **Google FCM v1 API**. It allows players to wake opponent devices with heads-up game invites and broadcast alerts even when the Bingo app is **completely killed and swiped from RAM**.

---

### How to Deploy (Takes 60 seconds):

#### Method 1: Web Browser (No command line needed)
1. Log into your free [Cloudflare Dashboard](https://dash.cloudflare.com/).
2. In the left sidebar, click **Compute (Workers & Pages)** $\rightarrow$ **Create Application** $\rightarrow$ **Create Worker**.
3. Name it `bingo-fcm-relay` (or whatever you prefer) and click **Deploy**.
4. Click **Edit code**.
5. Paste the entire contents of [`worker.js`](worker.js) into the editor.
6. Click **Deploy**.
7. Copy your worker's live URL (e.g., `https://bingo-fcm-relay.<your-name>.workers.dev`).

---

#### Method 2: Command line with Wrangler
From this directory (`cloudflare_worker/`):
```bash
npx wrangler deploy
```

---

### Endpoints
- `GET /health`: Tests health and verifies project ID.
- `POST /send`: Dispatches high-priority push notification:
```json
{
  "token": "TARGET_DEVICE_FCM_TOKEN",
  "type": "GAME_INVITE",
  "title": "🎮 Game Invite from John",
  "body": "@john invited you to play a match!",
  "data": {
    "roomCode": "482910",
    "fromUsername": "john",
    "fromDisplayName": "John"
  }
}
```
