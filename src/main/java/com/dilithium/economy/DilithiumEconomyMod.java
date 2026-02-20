package com.dilithium.economy;

import com.dilithium.economy.blockchain.BlockchainClient;
import com.dilithium.economy.blockchain.TransactionBuilder;
import com.dilithium.economy.commands.BalanceOverrideCommand;
import com.dilithium.economy.commands.BankReserveCommand;
import com.dilithium.economy.commands.DilithiumCommand;
import com.dilithium.economy.commands.PayAddressCommand;
import com.dilithium.economy.commands.PayCommand;
import com.dilithium.economy.commands.SetMoneyOverrideCommand;
import com.dilithium.economy.economy.BalanceCache;
import com.dilithium.economy.economy.DilithiumEconomyProvider;
import com.dilithium.economy.listeners.PlayerJoinListener;
import com.dilithium.economy.wallet.ReserveWallet;
import com.dilithium.economy.wallet.WalletManager;
import net.alloymc.api.AlloyAPI;
import net.alloymc.api.entity.Player;
import net.alloymc.api.permission.PermissionRegistry;
import net.alloymc.loader.api.ModInitializer;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Dilithium Economy mod entry point.
 * Replaces the built-in FileEconomyProvider with a Dilithium blockchain-backed economy.
 */
public final class DilithiumEconomyMod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[DilithiumEconomy] Initializing Dilithium Economy...");

        // Load config
        Path dataDir = AlloyAPI.server().dataDirectory().resolve("dilithium-economy");
        DilithiumConfig.LoadResult loadResult = DilithiumConfig.load(dataDir);
        DilithiumConfig config = loadResult.config();
        System.out.println("[DilithiumEconomy] Config: " + loadResult.configPath().toAbsolutePath());
        System.out.println("[DilithiumEconomy]   node_url: " + config.nodeUrl());
        System.out.println("[DilithiumEconomy]   network_name: " + config.networkName());
        System.out.println("[DilithiumEconomy]   sync_interval: " + config.syncIntervalSeconds() + "s");
        System.out.println("[DilithiumEconomy]   max_pending: " + config.maxPendingSeconds() + "s");
        System.out.println("[DilithiumEconomy]   fee: " + config.defaultFeeBaseUnits() + " base units");

        // Initialize blockchain client
        BlockchainClient client = new BlockchainClient(config.nodeUrl());
        boolean nodeReachable = client.isReachable();
        if (nodeReachable) {
            System.out.println("[DilithiumEconomy] Connected to blockchain node at " + config.nodeUrl());
        } else {
            System.err.println("[DilithiumEconomy] WARNING: Blockchain node at " + config.nodeUrl()
                    + " is not reachable. Economy will use cached balances.");
        }

        // Initialize wallet manager
        WalletManager walletManager = new WalletManager(dataDir, config.encryptionPassphrase());

        // Initialize reserve wallet
        ReserveWallet reserveWallet = ReserveWallet.loadOrCreate(dataDir);
        System.out.println("[DilithiumEconomy] Reserve wallet: " + reserveWallet.address());

        // Initialize balance cache with per-TX pending tracking
        // Include the reserve wallet address in the sync loop alongside player wallets
        java.util.function.Supplier<java.util.Set<String>> allAddresses = () -> {
            java.util.Set<String> addresses = new java.util.HashSet<>(walletManager.allAddresses());
            addresses.add(reserveWallet.address());
            return addresses;
        };
        BalanceCache balanceCache = new BalanceCache(client, allAddresses,
                config.syncIntervalSeconds(), config.maxPendingSeconds());

        // Configure incoming transaction notifications
        balanceCache.setReserveAddress(reserveWallet.address());
        balanceCache.setTransactionListener((toAddress, fromAddress, amount) -> {
            // Look up the recipient player
            UUID recipientId = walletManager.getPlayerByAddress(toAddress);
            if (recipientId == null) return; // not a known player wallet

            Optional<? extends Player> recipientOpt = AlloyAPI.server().player(recipientId);
            if (recipientOpt.isEmpty()) return; // player is offline

            Player recipient = recipientOpt.get();
            String sym = AlloyAPI.economy().currencySymbol();
            String formattedAmount = TransactionBuilder.formatDLT(amount);

            String senderLabel;
            if (fromAddress == null) {
                senderLabel = "an unknown source";
            } else {
                // Look up sender in our address-to-player LUT
                UUID senderId = walletManager.getPlayerByAddress(fromAddress);
                if (senderId != null) {
                    senderLabel = AlloyAPI.server().player(senderId)
                            .map(Player::displayName)
                            .orElse("Player " + senderId.toString().substring(0, 8));
                } else {
                    // Unknown address — show shortened address
                    String shortAddr = fromAddress.length() > 12
                            ? fromAddress.substring(0, 6) + "..." + fromAddress.substring(fromAddress.length() - 6)
                            : fromAddress;
                    senderLabel = "external " + shortAddr;
                }
            }

            recipient.sendMessage("Received " + sym + formattedAmount + " from " + senderLabel
                    + ". Transaction will settle soon.");
        });

        // Sync reserve wallet balance on startup
        if (nodeReachable) {
            balanceCache.syncAddress(reserveWallet.address());
        }

        // Register economy provider (replaces FileEconomyProvider)
        DilithiumEconomyProvider economyProvider = new DilithiumEconomyProvider(
                walletManager, reserveWallet, client, balanceCache,
                config.networkName(), config.defaultFeeBaseUnits());
        AlloyAPI.economy().setProvider(economyProvider);
        AlloyAPI.economy().setCurrencySymbol("\u00D0"); // Ð — Dilithium currency symbol
        System.out.println("[DilithiumEconomy] Dilithium economy provider registered (replaces built-in)");

        // Register permissions
        PermissionRegistry perms = AlloyAPI.permissionRegistry();
        perms.register("dilithium.wallet", "Dilithium wallet commands", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("dilithium.import", "Import private keys", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("dilithium.pay", "Send DLT to players or addresses", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("dilithium.reserve", "View reserve wallet", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("dilithium.export", "Export wallet keys", PermissionRegistry.PermissionDefault.TRUE);

        // Register commands — overrides alloy-core's /balance, /pay, /setmoney
        var cmdRegistry = AlloyAPI.commandRegistry();
        cmdRegistry.register(new DilithiumCommand(walletManager, balanceCache, dataDir));
        cmdRegistry.register(new PayCommand(walletManager, reserveWallet, balanceCache, client,
                config.networkName(), config.defaultFeeBaseUnits()));
        cmdRegistry.register(new PayAddressCommand(walletManager, balanceCache, client,
                config.networkName(), config.defaultFeeBaseUnits()));
        cmdRegistry.register(new BankReserveCommand(reserveWallet, balanceCache));
        cmdRegistry.register(new BalanceOverrideCommand(walletManager, balanceCache));
        cmdRegistry.register(new SetMoneyOverrideCommand());
        System.out.println("[DilithiumEconomy] Registered 6 commands: /dilithium, /pay, /payaddress, /bankreserve, /balance, /setmoney (disabled)");

        // Register event listener
        AlloyAPI.eventBus().register(new PlayerJoinListener(walletManager, balanceCache));

        System.out.println("[DilithiumEconomy] Dilithium Economy loaded. Reserve: " + reserveWallet.address());
    }
}
