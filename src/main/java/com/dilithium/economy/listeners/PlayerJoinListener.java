package com.dilithium.economy.listeners;

import com.dilithium.economy.economy.BalanceCache;
import com.dilithium.economy.wallet.WalletManager;
import net.alloymc.api.event.EventHandler;
import net.alloymc.api.event.Listener;
import net.alloymc.api.event.player.PlayerJoinEvent;

/**
 * Auto-generates a wallet for players on first join.
 */
public final class PlayerJoinListener implements Listener {

    private final WalletManager walletManager;
    private final BalanceCache balanceCache;

    public PlayerJoinListener(WalletManager walletManager, BalanceCache balanceCache) {
        this.walletManager = walletManager;
        this.balanceCache = balanceCache;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.player();
        var playerId = player.uniqueId();

        if (!walletManager.hasWallet(playerId)) {
            String address = walletManager.createWallet(playerId);
            player.sendMessage("Wallet created! Your address: " + address);
            System.out.println("[DilithiumEconomy] Created wallet for " + player.name() + ": " + address);
        } else {
            // Sync balance on join
            String address = walletManager.getAddress(playerId);
            if (address != null) {
                // Run async to avoid blocking the join event
                Thread.ofVirtual().name("DilithiumEconomy-JoinSync").start(() -> {
                    balanceCache.syncAddress(address);
                });
            }
        }
    }
}
