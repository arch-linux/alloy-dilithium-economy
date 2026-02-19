# Dilithium Economy

A cryptocurrency economy mod powered by the Dilithium blockchain.

## What It Does

Dilithium Economy replaces the standard Minecraft economy with **real cryptocurrency**. Every player's balance lives on the blockchain, and every transaction is cryptographically signed and verified. No more flat files — your economy runs on decentralized, tamper-proof technology.

### Key Features

- **Real Blockchain Money** — Player balances exist on-chain, not in server configs
- **Cryptographic Security** — All transactions signed with Dilithium post-quantum keys
- **Send to Anyone** — Pay other players by name, or any Dilithium wallet address worldwide
- **Reserve System** — Server-provided seed money for new players
- **Instant Sync** — Balances automatically sync from the blockchain

## Getting Started

### For Players

1. **Join the server** — Your wallet is created automatically on first join
2. **Check your balance** — Run `/balance` to see your DLT balance
3. **Receive money** — Other players can pay you by name
4. **Send money** — Use `/pay <player> <amount>` to send DLT to others

### For Server Owners

1. Drop `DilithiumEconomy-1.0.0.jar` into your `mods/` folder
2. Start the server — a `config.json` will be generated
3. Configure your Dilithium node URL (or run your own)
4. Set an encryption passphrase for wallet storage

## Commands

| Command | Usage |
|---------|-------|
| `/balance` | View your balance |
| `/pay <player> <amount>` | Send DLT to a player |
| `/payaddress <address> <amount>` | Send DLT to any address |
| `/dilithium` | Open wallet management |
| `/bankreserve` | View server reserve info |

## Configuration

```json
{
  "nodeUrl": "http://localhost:8080",
  "networkName": "dilithium",
  "syncIntervalSeconds": 30,
  "defaultFeeBaseUnits": 1000
}
```

- **nodeUrl** — Your Dilithium node (run your own or use a public one)
- **networkName** — Network identifier
- **syncIntervalSeconds** — How often to refresh balances
- **defaultFeeBaseUnits** — Transaction fee

## Security

- **Post-Quantum Crypto** — Dilithium signatures are resistant to quantum attacks
- **Encrypted Wallets** — Private keys stored with AES-256 encryption
- **No Middleman** — Direct peer-to-peer transactions on the blockchain

## Requirements

- Alloy mod loader
- Dilithium blockchain node (HTTP)
- Java 21+

---

*Part of the AlloyMC modding ecosystem*
