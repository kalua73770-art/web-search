# Web Search Backend

Minimal backend-only service for Render.

## Endpoints

- `GET /health`
- `GET /api/search?q=latest+news&limit=10`
- `POST /api/search`

## Deploy on Render

Use the repo as a **Web Service** with the Blueprint in `render.yaml`.

- Build Command: `npm install && npm run build`
- Start Command: `npm start`
- Health Check Path: `/health`
- Environment: `NODE_ENV=production`

No database or UI setup is required.
