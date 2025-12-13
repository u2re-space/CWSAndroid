# Setup Guide

## Quick Start

### 1. Server Setup

```bash
cd server

# Set environment variables
export CLIPBOARD_MASTER_KEY="your-secret-master-key"
export PUBKEY_device1="$(cat ../keys/device1-public.pem)"
export PUBKEY_device2="$(cat ../keys/device2-public.pem)"

# Start server
deno run -A server.ts --port=9000 --host=0.0.0.0
```

### 2. Client Deno Proxy Setup

```bash
cd client/deno-proxy

# Set environment variables
export CLIPBOARD_MASTER_KEY="your-secret-master-key"
export PRIV_KEY_PEM="$(cat ../../keys/device1-private.pem)"

# Start proxy
deno run -A main.ts ws://server-ip:9000 9000 8080 device1
```

### 3. NativeScript Daemon Setup

```bash
cd client/native-script

# Install dependencies
npm install

# Build TypeScript
npm run build

# Set environment variables
export BACKEND_SERVER_URL="ws://server-ip:9000"
export DEVICE_ID="device1"

# Start daemon
npm start
```

## Key Generation

Generate RSA key pairs for each device:

```bash
# Create keys directory
mkdir -p keys

# Generate key pair for device1
openssl genrsa -out keys/device1-private.pem 2048
openssl rsa -in keys/device1-private.pem -pubout -out keys/device1-public.pem

# Generate key pair for device2
openssl genrsa -out keys/device2-private.pem 2048
openssl rsa -in keys/device2-private.pem -pubout -out keys/device2-public.pem
```

## Termux Integration (Android)

On Android devices, use Termux to run the Deno proxy:

```bash
# In Termux
pkg update
pkg install deno

# Navigate to project
cd /path/to/Automata-JS/client/deno-proxy

# Set environment variables
export CLIPBOARD_MASTER_KEY="your-secret-master-key"
export PRIV_KEY_PEM="$(cat ../../keys/device1-private.pem)"

# Start proxy
deno run -A main.ts ws://server-ip:9000 9000 8080 device1
```

The NativeScript daemon will automatically detect and connect to the Deno proxy running in Termux.

## Architecture Flow

```
┌─────────────────┐
│ NativeScript    │
│ Daemon          │
│ (Android/Mobile)│
└────────┬────────┘
         │ WebSocket (plain)
         │
         ▼
┌─────────────────┐
│ Deno Proxy      │
│ (Termux/Client) │
└────────┬────────┘
         │ SocketIO (encrypted)
         │
         ▼
┌─────────────────┐
│ Backend Server  │
│ (Deno)          │
└─────────────────┘
```

## Message Flow

1. **NativeScript → Backend:**
   - NativeScript sends plain message to Deno proxy
   - Deno proxy encrypts with AES-256-GCM and signs with RSA
   - Deno proxy forwards encrypted message to backend
   - Backend verifies signature and routes message

2. **Backend → NativeScript:**
   - Backend sends encrypted message to Deno proxy
   - Deno proxy verifies signature and decrypts
   - Deno proxy forwards plain message to NativeScript
   - NativeScript processes message (e.g., updates clipboard)

## Troubleshooting

### Deno proxy can't connect to backend
- Check backend server is running
- Verify server URL and port
- Check firewall settings

### NativeScript can't connect to Deno proxy
- Verify Deno proxy is running in Termux
- Check local port (default: 8080)
- Ensure WebSocket server is listening on 127.0.0.1

### Encryption errors
- Verify CLIPBOARD_MASTER_KEY matches on all components
- Check PRIV_KEY_PEM is set correctly
- Ensure PUBKEY_* environment variables are set on server

### Clipboard not syncing
- Check NativeScript has clipboard permissions
- Verify message routing (check "to" field)
- Check backend logs for errors
