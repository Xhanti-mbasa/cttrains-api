# CTTrains API

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Node.js](https://img.shields.io/badge/Node.js-18+-green.svg)](https://nodejs.org/)

An unofficial REST API and Model Context Protocol (MCP) server for Cape Town Metrorail train schedules and service updates. Data is scraped live from [cttrains.co.za](https://cttrains.co.za).

> **Disclaimer:** This project is a community-built tool and is **not affiliated with or endorsed by Metrorail or PRASA**. 

---

## ⚡ Quick Start (Automated Setup)

The easiest way to get the server running on any Linux machine (Arch, CachyOS, Ubuntu, Debian) is using the automated setup script. It will install all requirements (Node, Redis, Docker), start the necessary services, and launch the API for you!

```bash
git clone git@github.com:Xhanti-mbasa/cttrains-api.git
cd cttrains-api
./setup.sh
```

---

## 🐳 Docker Setup (Manual)

If you prefer to run it manually using Docker (recommended for production servers as it includes the Redis cache automatically):

1. **Copy environment variables:**
   ```bash
   cp .env.example .env
   ```
2. **Start the containers in the background:**
   ```bash
   sudo docker-compose up -d
   ```
3. **Check the logs:**
   ```bash
   sudo docker-compose logs -f
   ```

---

## 💻 Native Setup (Manual)

To run the API directly on your host machine without Docker:

1. **Install requirements:** Ensure you have Node.js and Redis installed.
   - Arch/CachyOS: `sudo pacman -S nodejs npm redis`
   - Ubuntu/Debian: `sudo apt install nodejs npm redis-server`
2. **Enable Redis:**
   ```bash
   sudo systemctl enable --now redis
   ```
3. **Install project dependencies:**
   ```bash
   npm install
   ```
4. **Start the server:**
   ```bash
   npm start
   ```

---

## 🤖 AI / MCP Integration (Gemini, Claude, etc.)

This repository comes with native **Model Context Protocol (MCP)** support, allowing AI agents to look up train schedules directly!

### Connect your AI via Stdio Transport
You can connect an MCP-compatible AI client (like Claude Desktop) to the `src/mcp-server.js` script. Add this to your AI client's configuration:

```json
{
  "mcpServers": {
    "cttrains": {
      "command": "node",
      "args": ["/absolute/path/to/cttrains-api/src/mcp-server.js"]
    }
  }
}
```
*(If your API is running on a remote server like Tailscale, you can change the command to `ssh` and pass the server IP!)*

### Terminal Chat with Gemini
If you want to test the MCP server using Gemini right now in your terminal:
1. Add your Gemini API key to `.env` (`GEMINI_API_KEY=your_key_here`).
2. Run the included bridge script:
   ```bash
   node src/gemini-client.js
   ```
3. Ask it questions like: *"When is the next train from Bellville to Cape Town?"*

---

## 📖 API Documentation
Once the server is running (default port `3000`), visit `http://localhost:3000/` or `http://<your-server-ip>:3000/` in your browser to see the full list of available REST endpoints.
