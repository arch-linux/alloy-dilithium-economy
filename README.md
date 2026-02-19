# Dilithium Economy

A Minecraft server mod that replaces the built-in economy system with a real cryptocurrency economy backed by the Dilithium blockchain.

## Overview

Dilithium Economy integrates the Dilithium blockchain (a post-quantum digital signature algorithm) into Minecraft's economy system. Player balances are stored on-chain, and all transactions are cryptographically signed and verified against a Dilithium node.

## Features

- **Blockchain-Backed Balances**: Player balances are stored on the Dilithium blockchain, not in flat files
- **Cryptographic Transactions**: All payments are signed with Dilithium keys (ML-DSA-65 / CRYSTALS-Dilithium)
- **Real Cryptocurrency**: Send DLT to other players or any Dilithium address
- **Reserve Wallet**: Server-managed reserve wallet for initial player balances
- **Balance Caching**: Local cache with pending transaction tracking for fast performance
- **Automatic Sync**: Periodic balance synchronization with the blockchain node
- **Permission System**: Granular permissions for wallet operations

## Requirements

- Alloy mod loader (modding platform for Minecraft)
- A running Dilithium blockchain node
- Java 21+

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/dilithium` | Open wallet management GUI | `dilithium.wallet` |
| `/pay <player> <amount>` | Send DLT to a player | `dilithium.pay` |
| `/payaddress <address> <amount>` | Send DLT to any address | `dilithium.pay` |
| `/bankreserve` | View reserve wallet info | `dilithium.reserve` |
| `/balance [player]` | Check balance (overrides default) | - |

## Configuration

On first run, a `config.json` is created in the mod's data directory:

```json
{
  "nodeUrl": "http://localhost:8080",
  "networkName": "dilithium",
  "syncIntervalSeconds": 30,
  "maxPendingSeconds": 60,
  "defaultFeeBaseUnits": 1000,
  "encryptionPassphrase": "your-passphrase"
}
```

- `nodeUrl`: HTTP endpoint of your Dilithium node
- `networkName`: Network identifier (must match node)
- `syncIntervalSeconds`: How often to sync balances from chain
- `maxPendingSeconds`: Max time to wait for transaction confirmation
- `defaultFeeBaseUnits`: Transaction fee in base units
- `encryptionPassphrase`: Used to encrypt wallet private keys

## Architecture

```
DilithiumEconomyMod
├── BlockchainClient      — HTTP client for Dilithium node
├── WalletManager        — Player wallet storage & key management
├── ReserveWallet        — Server reserve wallet (initial balances)
├── BalanceCache         — Local balance cache with pending TX tracking
├── DilithiumEconomyProvider — Alloy EconomyProvider implementation
└── Commands             — /dilithium, /pay, /payaddress, /bankreserve
```

### Transaction Flow

1. Player executes `/pay <player> <amount>`
2. WalletManager retrieves sender's Dilithium key pair
3. Transaction built: `network:from:to:amount:fee:timestamp`
4. Transaction signed with sender's Dilithium private key
5. Transaction sent to Dilithium node via `sendrawtransaction`
6. BalanceCache tracks pending TX until confirmed
7. On confirmation, balances sync from chain

### Crypto

- **Algorithm**: Dilithium (ML-DSA-65 / CRYSTALS-Dilithium Round 3)
- **Library**: BouncyCastle 1.76
- **Key Size**: 1952 bytes (public key), ~3293 bytes (signature)
- **PKCS#8**: Uses original Dilithium OID (`1.3.6.1.4.1.2.267.7.6.5`)

## Building

```bash
./gradlew jar
```

Output: `build/libs/DilithiumEconomy-1.0.0.jar`

## Installation

1. Build the JAR or download a release
2. Place in your server's `mods/` folder
3. Ensure `alloy-loader.jar` and `alloy-api.jar` are present
4. Configure a Dilithium node (or use a public one)
5. Start the server

## License

MIT
