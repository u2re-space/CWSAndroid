# Automata-JS

A multi-device synchronization system with encrypted communication, clipboard sharing, and command forwarding capabilities.

## Architecture

The system consists of three main components:

1. **Client Deno Module** (`client/deno-proxy`) - Connection forwarder with encryption
2. **Client NativeScript Daemon** (`client/native-script`) - Background service for platform APIs
3. **Host Server** (`server`) - Deno server for synchronization and routing

## Components

### 1. Client Deno Module (`client/deno-proxy`)

A Deno module that acts as a connection forwarder between NativeScript and the backend server. It handles encrypted data/payload forwarding with proper authentication.

**Features:**
- WebSocket connection forwarding
- AES-256-GCM encryption with RSA signatures
- Automatic reconnection
- Error handling and logging

**Usage:**
```bash
cd client/deno-proxy
deno run -A main.ts <serverURL> <serverPort> <localPort> [deviceId]
```

**Example:**
```bash
deno run -A main.ts ws://localhost:9000 9000 8080 device-001
```

**Environment Variables:**
- `CLIPBOARD_MASTER_KEY` - Master key for AES encryption
- `PRIV_KEY_PEM` - Private key for signing (PEM format)
- `PUBKEY_<deviceId>` - Public keys for other devices

### 2. Client NativeScript Daemon (`client/native-script`)

A background daemon that runs on Android/mobile platforms, providing:
- Clipboard access and monitoring
- Android/Platform API integration
- Connection to Deno module (via Termux or similar)
- Connection to backend server through Deno proxy

**Features:**
- Clipboard read/write operations
- Platform-specific API access
- WebSocket server for Deno module communication
- Automatic Deno module process management
- Background service capabilities

**Setup:**
```bash
cd client/native-script
npm install
npm run build
npm start
```

**Configuration:**
- `BACKEND_SERVER_URL` - Backend server WebSocket URL
- `DEVICE_ID` - Unique device identifier

**Termux Integration:**
The daemon can launch Deno processes via Termux on Android:
```bash
# In Termux
pkg install deno
# Then the daemon will automatically spawn Deno processes
```

### 3. Host Server (`server`)

A Deno-based server that provides:
- WebSocket communication for device synchronization
- File system access for data persistence
- Network access for multi-device communication
- Multi-cast messaging (broadcast to all devices except sender)
- Message routing, hooking, and translation
- Latest/next/nightly NodeJS compatibility

**Features:**
- Socket.IO WebSocket server
- Blind relay mode (signature verification without decryption)
- Inspect mode (full decryption and logging)
- Message hooks for translation/routing
- Multi-cast messaging
- Device connection management
- Clipboard history tracking

**Usage:**
```bash
cd server
deno run -A server.ts [--port=9000] [--host=0.0.0.0]
```

**Environment Variables:**
- `CLIPBOARD_MASTER_KEY` - Master key for AES decryption
- `PUBKEY_<deviceId>` - Public keys for all devices

## Message Format

Messages follow this structure:

```typescript
{
  type: "clip" | "command" | ...,
  from: "deviceA",
  to: "broadcast" | "deviceB",
  mode: "blind" | "inspect",
  action: "setClipboard" | "runScript" | ...,
  payload: "BASE64(...)" // encrypted payload
}
```

### Message Types

- **clip** - Clipboard synchronization messages
- **command** - Command execution requests

### Modes

- **blind** - Server verifies signature but doesn't decrypt (privacy-preserving)
- **inspect** - Server decrypts and can log/process content

### Routing

- **broadcast** - Send to all connected devices except sender
- **deviceId** - Send to specific device

## Security

- **AES-256-GCM** encryption for payload data
- **RSA signatures** for message authentication
- **Blind relay mode** for privacy-preserving forwarding
- **Public key verification** on server

## Setup Instructions

### 1. Generate Keys

```bash
# Generate RSA key pair for a device
openssl genrsa -out device-private.pem 2048
openssl rsa -in device-private.pem -pubout -out device-public.pem

# Set environment variables
export PRIV_KEY_PEM="$(cat device-private.pem)"
export PUBKEY_device1="$(cat device-public.pem)"
```

### 2. Start Server

```bash
cd server
export CLIPBOARD_MASTER_KEY="your-secret-key"
export PUBKEY_device1="$(cat device1-public.pem)"
export PUBKEY_device2="$(cat device2-public.pem)"
deno run -A server.ts
```

### 3. Start Deno Proxy (on client)

```bash
cd client/deno-proxy
export CLIPBOARD_MASTER_KEY="your-secret-key"
export PRIV_KEY_PEM="$(cat device1-private.pem)"
deno run -A main.ts ws://server-ip:9000 9000 8080 device1
```

### 4. Start NativeScript Daemon (on mobile)

```bash
cd client/native-script
export BACKEND_SERVER_URL="ws://server-ip:9000"
export DEVICE_ID="device1"
npm start
```

## Development

### Client Deno Module
```bash
cd client/deno-proxy
deno task dev  # Watch mode
```

### NativeScript Daemon
```bash
cd client/native-script
npm run dev  # Watch mode
```

### Server
```bash
cd server
deno task dev  # Watch mode
```

## Project Structure

```
Automata-JS/
├── client/
│   ├── deno-proxy/          # Deno connection forwarder
│   │   ├── main.ts          # Entry point
│   │   ├── connection.ts    # Connection logic
│   │   ├── crypto-utils.ts  # Encryption utilities
│   │   └── deno.json        # Deno configuration
│   └── native-script/       # NativeScript daemon
│       ├── host-daemon.ts   # Main daemon
│       ├── package.json     # NPM configuration
│       └── tsconfig.json    # TypeScript configuration
└── server/                  # Deno server
    ├── server.ts            # Main server
    ├── crypto-utils.ts      # Crypto utilities
    └── deno.json            # Deno configuration
```

## License

[Your License Here]
