package com.dilithium.economy;

import com.dilithium.economy.blockchain.BlockchainClient;
import com.dilithium.economy.commands.BankReserveCommand;
import com.dilithium.economy.commands.DilithiumCommand;
import com.dilithium.economy.commands.PayCommand;
import com.dilithium.economy.economy.BalanceCache;
import com.dilithium.economy.economy.DilithiumEconomyProvider;
import com.dilithium.economy.listeners.PlayerJoinListener;
import com.dilithium.economy.wallet.ReserveWallet;
import com.dilithium.economy.wallet.WalletManager;
import net.alloymc.api.AlloyAPI;
import net.alloymc.api.permission.PermissionRegistry;
import net.alloymc.loader.api.ModInitializer;

import java.nio.file.Path;

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
        DilithiumConfig config = DilithiumConfig.load(dataDir);

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

        // Initialize balance cache with periodic sync
        BalanceCache balanceCache = new BalanceCache(client, walletManager::allAddresses, config.syncIntervalSeconds());

        // Sync reserve wallet balance on startup
        if (nodeReachable) {
            balanceCache.syncAddress(reserveWallet.address());
        }

        // Register economy provider (replaces FileEconomyProvider)
        DilithiumEconomyProvider economyProvider = new DilithiumEconomyProvider(
                walletManager, reserveWallet, client, balanceCache,
                config.networkName(), config.defaultFeeBaseUnits());
        AlloyAPI.economy().setProvider(economyProvider);
        System.out.println("[DilithiumEconomy] Dilithium economy provider registered (replaces built-in)");

        // Register permissions
        PermissionRegistry perms = AlloyAPI.permissionRegistry();
        perms.register("dilithium.wallet", "Dilithium wallet commands", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("dilithium.import", "Import private keys", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("dilithium.pay", "Send DLT to players", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("dilithium.reserve", "View reserve wallet", PermissionRegistry.PermissionDefault.TRUE);

        // Register commands
        var cmdRegistry = AlloyAPI.commandRegistry();
        cmdRegistry.register(new DilithiumCommand(walletManager, balanceCache));
        cmdRegistry.register(new PayCommand(walletManager, balanceCache, client, config.networkName(), config.defaultFeeBaseUnits()));
        cmdRegistry.register(new BankReserveCommand(reserveWallet, balanceCache));
        System.out.println("[DilithiumEconomy] Registered 3 commands: /dilithium, /pay, /bankreserve");

        // Register event listener
        AlloyAPI.eventBus().register(new PlayerJoinListener(walletManager, balanceCache));

        System.out.println("[DilithiumEconomy] Dilithium Economy loaded. Reserve: " + reserveWallet.address());
    }
}
