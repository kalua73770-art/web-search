# Web Search Backend (Render Ready)

This project now runs as a backend-only web search API (no UI, no history/database storage).

## API

1. `GET /health`
2. `GET /api/search?q=latest+ai+news&limit=10`
3. `POST /api/search`

Example POST body:

```json
{
  "query": "open source vector databases",
  "limit": 10
}
```

Response shape:

```json
{
  "query": "open source vector databases",
  "provider": "bing",
  "count": 10,
  "results": [
    {
      "title": "...",
      "url": "https://...",
      "snippet": "..."
    }
  ]
}
```

## Local Run

1. `npm install`
2. `npm run build`
3. `npm start`

Server listens on `PORT` (default `3000`).

## Render.com (Free Tier)

Use a **Web Service** with:

1. Build Command: `npm install && npm run build`
2. Start Command: `npm start`
3. Environment: `NODE_ENV=production`

No database env vars are required.
