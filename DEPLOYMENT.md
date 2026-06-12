# CTTrains API — Deployment & Configuration Guide

## Overview

The enhanced API now supports:
- **Redis persistence** (optional) for cache recovery across restarts
- **Docker deployment** with automatic health checks
- **OpenAPI/Swagger schema** for API documentation
- **Platform number extraction** from schedule results
- **Enhanced health metrics** (memory, CPU, Redis status)

---

## Quick Start (Local Development)

### Without Redis (In-Memory Cache Only)

```bash
git clone https://github.com/Xhanti-mbasa/cttrains-api.git
cd cttrains-api
npm install
npm start
```

Visit: http://localhost:3000/health

### With Docker Compose (Includes Redis)

```bash
docker-compose up --build
```

This starts:
- **API** on http://localhost:3000
- **Redis** on localhost:6379

Press `Ctrl+C` to stop.

---

## Production Deployment

### Option 1: Vercel (Recommended for Serverless)

Vercel doesn't support long-running processes, so use in-memory cache only (Redis not needed).

```bash
npm install -g vercel
vercel
```

Configure in `vercel.json`:
```json
{
  "buildCommand": "npm install",
  "env": {
    "NODE_ENV": "production",
    "PORT": "3000",
    "RATE_LIMIT_RPM": "60"
  }
}
```

### Option 2: Railway (Recommended for Persistent Services)

Railway supports long-running services and Redis.

1. **Push to GitHub**
   ```bash
   git push origin main
   ```

2. **Link Railway**
   ```bash
   npm install -g @railway/cli
   railway init
   railway up
   ```

3. **Add Redis Plugin**
   - Go to Railway dashboard
   - Add "Redis" plugin
   - It auto-creates `REDIS_URL` environment variable

4. **Configure Environment Variables**
   ```
   NODE_ENV=production
   PORT=3000
   RATE_LIMIT_RPM=60
   ```

### Option 3: Render

1. **Create Render account** at render.com

2. **Create Web Service**
   - Connect GitHub repo
   - Build command: `npm install`
   - Start command: `npm start`
   - Environment:
     ```
     NODE_ENV=production
     PORT=3000
     RATE_LIMIT_RPM=60
     ```

3. **Add Redis (Optional)**
   - Create Redis instance on Render
   - Copy connection string to `REDIS_URL` env var

### Option 4: Self-Hosted (Docker)

#### Prerequisites
- Docker & Docker Compose installed
- Linux server (Ubuntu 20.04+ recommended)

#### Deployment Script

```bash
#!/bin/bash
# deploy.sh

# Clone or pull latest
git clone https://github.com/Xhanti-mbasa/cttrains-api.git /opt/cttrains-api
cd /opt/cttrains-api

# Create .env for production
cat > .env << EOF
PORT=3000
NODE_ENV=production
RATE_LIMIT_RPM=120
REDIS_URL=redis://:$(openssl rand -base64 16)@redis:6379/0
REDIS_PASSWORD=$(openssl rand -base64 16)
EOF

# Start with Docker Compose
docker-compose -p cttrains up -d

# Show logs
docker-compose -p cttrains logs -f api

# Create systemd service for auto-start
sudo tee /etc/systemd/system/cttrains.service << EOF
[Unit]
Description=CTTrains API
After=network.target docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/cttrains-api
ExecStart=/usr/bin/docker-compose -p cttrains up -d
ExecStop=/usr/bin/docker-compose -p cttrains down
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable cttrains
sudo systemctl start cttrains
```

#### Nginx Reverse Proxy (HTTPS)

```nginx
# /etc/nginx/sites-available/cttrains

upstream cttrains_api {
  server localhost:3000;
  keepalive 64;
}

server {
  listen 80;
  server_name api.example.com;

  # Redirect HTTP to HTTPS
  return 301 https://$server_name$request_uri;
}

server {
  listen 443 ssl http2;
  server_name api.example.com;

  # SSL certificates (from Let's Encrypt)
  ssl_certificate /etc/letsencrypt/live/api.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/api.example.com/privkey.pem;

  # Security headers
  add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
  add_header X-Content-Type-Options "nosniff" always;
  add_header X-Frame-Options "DENY" always;

  # Rate limiting at proxy level (optional, in addition to app-level)
  limit_req_zone $binary_remote_addr zone=api_limit:10m rate=100r/m;
  limit_req zone=api_limit burst=20 nodelay;

  location / {
    proxy_pass http://cttrains_api;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection 'upgrade';
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_cache_bypass $http_upgrade;
    
    # Timeouts
    proxy_connect_timeout 60s;
    proxy_send_timeout 60s;
    proxy_read_timeout 60s;
  }

  # Health check (no rate limiting)
  location = /health {
    proxy_pass http://cttrains_api;
    limit_req off;
  }
}
```

Enable:
```bash
sudo ln -s /etc/nginx/sites-available/cttrains /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

Setup HTTPS with Let's Encrypt:
```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot certonly --nginx -d api.example.com
```

---

## Configuration Reference

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3000` | HTTP server port |
| `NODE_ENV` | `development` | Environment (development/production) |
| `RATE_LIMIT_RPM` | `60` | Rate limit: requests per minute per IP |
| `REDIS_URL` | (empty) | Redis connection string (optional) |

### Redis Connection Strings

**Format:** `redis://[:password@]host[:port][/db]`

**Examples:**
- Local development: `redis://localhost:6379`
- With password: `redis://:mypassword@localhost:6379`
- Docker Compose: `redis://:cttrains-dev@redis:6379/0`
- Remote (Render): `redis://default:xxxx@dpg-xxx.render.com:6379/0`
- Remote (Railway): `redis://:password@redis.railway.internal:6379`

---

## Monitoring & Maintenance

### Health Endpoint

```bash
curl http://localhost:3000/health
```

Response includes:
- API uptime
- Cache size and Redis status
- Memory usage (MB)
- CPU cores available
- Platform info

### Logs

**Local (Terminal):**
```bash
npm start
```

**Docker Compose:**
```bash
docker-compose logs -f api
docker-compose logs -f redis
```

**Self-Hosted:**
```bash
systemctl status cttrains
journalctl -u cttrains -f
```

### Cache Monitoring

Redis stats available at:
```bash
redis-cli
> INFO memory
> KEYS *
> TTL schedule:*
```

### Updating the API

**Git-based deployment:**
```bash
cd /opt/cttrains-api
git pull origin main
docker-compose -p cttrains up -d --build
```

---

## API Documentation

### OpenAPI/Swagger

The API exposes a complete OpenAPI 3.0 schema at `/openapi.yaml`

**View in Swagger UI:**
```bash
# Host openapi.yaml on web server or use public host:
https://editor.swagger.io/?url=https://api.example.com/openapi.yaml
```

### Endpoints Quick Reference

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/` | GET | API root (metadata) |
| `/health` | GET | Health check + metrics |
| `/lines` | GET | All lines |
| `/lines/:lineId` | GET | Line detail |
| `/lines/:lineId/stations` | GET | Stations on line |
| `/stations` | GET | Search all stations |
| `/stations/:stationId` | GET | Station detail |
| `/schedules` | GET | Cross-line schedule search |
| `/schedules/lines/:lineId` | GET | Line-specific timetable |
| `/updates` | GET | Service disruptions |

---

## Troubleshooting

### API Won't Start

**Symptom:** `Error: listen EADDRINUSE :::3000`
**Fix:** Port already in use
```bash
# Find process on port 3000
lsof -i :3000
# Kill it
kill -9 <PID>
# Or change PORT env var
PORT=3001 npm start
```

### Redis Connection Fails

**Symptom:** `[cache] Redis connection failed`
**Fix:** Redis not running or wrong connection string
```bash
# Check Redis running
redis-cli ping
# Verify REDIS_URL format
echo $REDIS_URL
# Test connection manually
redis-cli -u $REDIS_URL ping
```

### High Memory Usage

**Symptom:** API using >500MB RAM
**Fix:** Clear cache or enable Redis persistence
```bash
# Clear in-memory cache
curl -X DELETE http://localhost:3000/cache
# Or check Redis memory
redis-cli INFO memory
```

### 502 Gateway Error from cttrains.co.za

**Symptom:** `Failed to fetch schedule from cttrains.co.za`
**Cause:** Target site down or network issue
**Fix:** Wait for site recovery; check status:
```bash
curl -I https://cttrains.co.za
```

---

## Performance Tuning

### Cache TTL Values

Adjust in `src/routes/schedules.js`:
```javascript
const SCHEDULE_TTL = 60 * 60;  // 1 hour — schedules stable, change infrequently
const UPDATES_TTL = 5 * 60;    // 5 minutes — updates time-sensitive
```

For high traffic, increase TTLs:
```javascript
const SCHEDULE_TTL = 3 * 60 * 60;  // 3 hours — more aggressive caching
const UPDATES_TTL = 10 * 60;       // 10 minutes
```

### Rate Limiting

Default: 60 requests/min per IP

For high traffic, increase:
```bash
RATE_LIMIT_RPM=300 npm start
```

Or reduce for strict limits:
```bash
RATE_LIMIT_RPM=30 npm start
```

### Redis Configuration

For large deployments, tune Redis:
```bash
# In docker-compose.yml, add to redis command:
redis-server \
  --maxmemory 256mb \
  --maxmemory-policy allkeys-lru \
  --appendonly yes
```

---

## Security Best Practices

1. **Use HTTPS in production** — Nginx with Let's Encrypt (see above)
2. **Secure Redis** — Require authentication, restrict network access
3. **Rate limiting** — Default 60 req/min; adjust as needed
4. **Firewall** — Allow only necessary ports (80, 443)
5. **Keep updated** — Pull latest from GitHub regularly
6. **Monitor logs** — Watch for unusual request patterns
7. **Backup Redis data** — Enable AOF (Append-Only File) in Redis

---

## Support & Development

**Issues/Requests:** https://github.com/Xhanti-mbasa/cttrains-api/issues
**Discord:** (community channel if available)
**Email:** Contact repository owner

---

## License

MIT — See LICENSE file for details
