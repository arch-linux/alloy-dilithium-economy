package com.dilithium.economy.economy;

import com.dilithium.economy.blockchain.BlockchainClient;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Thread-safe balance cache with pending debits and periodic blockchain sync.
 *
 * Flow:
 * 1. On withdraw/transfer: debit locally (cache - amount), submit TX async
 * 2. Every sync interval: fetch on-chain balances, overwrite cache, clear pending debits
 * 3. Node offline: stale cache used, warning logged
 */
public final class BalanceCache {

    /** Cached on-chain balances in base units */
    private final ConcurrentHashMap<String, Long> balances = new ConcurrentHashMap<>();

    /** Pending debits that haven't been confirmed yet */
    private final ConcurrentHashMap<String, Long> pendingDebits = new ConcurrentHashMap<>();

    private final BlockchainClient client;
    private final Supplier<Set<String>> addressSupplier;
    private final ScheduledExecutorService syncScheduler;
    private final AtomicBoolean nodeOnline = new AtomicBoolean(true);

    /**
     * @param client          blockchain REST client
     * @param addressSupplier supplies the set of all known addresses to sync
     * @param syncIntervalSec how often to sync with the blockchain (seconds)
     */
    public BalanceCache(BlockchainClient client, Supplier<Set<String>> addressSupplier, int syncIntervalSec) {
        this.client = client;
        this.addressSupplier = addressSupplier;
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DilithiumEconomy-BalanceSync");
            t.setDaemon(true);
            return t;
        });

        // Start periodic sync
        syncScheduler.scheduleAtFixedRate(this::syncAll, syncIntervalSec, syncIntervalSec, TimeUnit.SECONDS);
    }

    /**
     * Returns the effective balance for an address (cached balance minus pending debits).
     */
    public long getBalance(String address) {
        long cached = balances.getOrDefault(address, 0L);
        long pending = pendingDebits.getOrDefault(address, 0L);
        return Math.max(0, cached - pending);
    }

    /**
     * Records a pending debit (optimistic local deduction before TX is mined).
     */
    public void recordPendingDebit(String address, long amount) {
        pendingDebits.merge(address, amount, Long::sum);
    }

    /**
     * Records a pending credit to the cache (for deposits, before TX is mined).
     */
    public void recordPendingCredit(String address, long amount) {
        balances.merge(address, amount, Long::sum);
    }

    /**
     * Checks if the node was reachable during the last sync.
     */
    public boolean isNodeOnline() {
        return nodeOnline.get();
    }

    /**
     * Fetches the on-chain balance for a single address and updates the cache.
     */
    public void syncAddress(String address) {
        try {
            long onChainBalance = client.getBalance(address);
            balances.put(address, onChainBalance);
            // Clear pending debits since we now have the truth
            pendingDebits.remove(address);
        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Failed to sync address " + address + ": " + e.getMessage());
        }
    }

    /**
     * Syncs all known addresses with the blockchain.
     */
    private void syncAll() {
        try {
            if (!client.isReachable()) {
                if (nodeOnline.getAndSet(false)) {
                    System.err.println("[DilithiumEconomy] WARNING: Blockchain node is offline. Using stale cache.");
                }
                return;
            }

            if (!nodeOnline.getAndSet(true)) {
                System.out.println("[DilithiumEconomy] Blockchain node is back online.");
            }

            Set<String> addresses = addressSupplier.get();
            for (String address : addresses) {
                try {
                    long onChainBalance = client.getBalance(address);
                    balances.put(address, onChainBalance);
                    pendingDebits.remove(address);
                } catch (Exception e) {
                    // Individual address failure, continue with others
                }
            }
        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Balance sync failed: " + e.getMessage());
        }
    }

    /**
     * Shuts down the sync scheduler.
     */
    public void shutdown() {
        syncScheduler.shutdownNow();
    }
}
