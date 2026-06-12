#!/usr/bin/env bash
set -e

echo "=========================================="
echo "    CTTrains API - Automated Setup        "
echo "=========================================="
echo ""

# 1. System OS Detection
echo "🔎 Detecting Operating System..."
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$ID
    OS_LIKE=$ID_LIKE
else
    echo "❌ Cannot determine OS. /etc/os-release not found."
    exit 1
fi

echo "✅ OS Detected: $PRETTY_NAME ($OS)"

# 2. Package Installation
echo "📦 Installing system dependencies (Node.js, Redis, Docker)..."
if [[ "$OS" == "arch" || "$OS_LIKE" == *"arch"* || "$OS" == "cachyos" ]]; then
    sudo pacman -Sy --needed --noconfirm nodejs npm redis docker docker-compose
elif [[ "$OS" == "ubuntu" || "$OS" == "debian" || "$OS_LIKE" == *"debian"* ]]; then
    sudo apt-get update
    sudo apt-get install -y nodejs npm redis-server docker.io docker-compose
else
    echo "⚠️ Unsupported package manager. Please install Node, Redis, and Docker manually."
fi

# 3. Enable and Start Services
echo "⚙️ Enabling background services..."
if command -v systemctl &> /dev/null; then
    sudo systemctl enable --now redis || sudo systemctl enable --now redis-server || true
    sudo systemctl enable --now docker || true
else
    echo "⚠️ systemd not found. Skipping service enablement."
fi

# 4. Project Configuration
echo "⚙️ Configuring project..."
if [ ! -f ".env" ]; then
    echo "Creating .env file from .env.example..."
    cp .env.example .env
fi

echo "📦 Installing Node dependencies..."
npm install

echo ""
echo "=========================================="
echo "  Setup Complete! 🎉"
echo "=========================================="
echo ""
echo "How would you like to run the server?"
echo "  1) Docker (Recommended for production)"
echo "  2) Native Node.js (Recommended for development)"
echo "  3) Exit and start later"
read -p "Select an option [1-3]: " choice

case $choice in
    1)
        echo "🐳 Starting via Docker Compose..."
        sudo docker-compose up -d
        echo "✅ API is running! View logs with: sudo docker-compose logs -f"
        ;;
    2)
        echo "🟢 Starting natively via Node..."
        npm start
        ;;
    3)
        echo "Bye! Start it later using 'npm start' or 'sudo docker-compose up -d'."
        ;;
    *)
        echo "Invalid option. Exiting."
        ;;
esac
