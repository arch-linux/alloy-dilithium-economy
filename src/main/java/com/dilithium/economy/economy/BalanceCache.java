package com.dilithium.economy.economy;

import com.dilithium.economy.blockchain.BlockchainClient;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Thread-safe balance cache with per-transaction pending tracking.
 *
 * <p>Prevents double-spending by tracking individual pending debits. Each outgoing
 * transaction gets a unique ID so it can be rolled back immediately if the node
 * rejects it, or expired after a configurable timeout.
 *
 * <p>Effective balance = on-chain balance + pending credits - sum(pending debits)
 *
 * <p>Key design:
 * <ul>
 *   <li>{@link #tryDebit} atomically checks balance and reserves funds</li>
 *   <li>{@link #removePendingDebit} rolls back a failed TX immediately</li>
 *   <li>Pending debits expire after {@code maxPendingMs} (default 5 min)</li>
 *   <li>Sync updates on-chain balances and clears pending credits (they appear on-chain)</li>
 *   <li>Sync detects confirmed TXs by comparing on-chain balance changes and clears
 *       corresponding pending debits (oldest first, up to the decrease amount)</li>
 * </ul>
 */
public final class BalanceCache {

    /**
     * A single pending outgoing transaction.
     */
    public record PendingDebit(String id, long amount, long createdAt) {}

    /**
     * Result of a {@link #tryDebit} call.
     */
    public record DebitResult(boolean success, String txId, long remainingBalance, String failReason) {
        public static DebitResult ok(String txId, long remaining) {
            return new DebitResult(true, txId, remaining, null);
        }

        public static DebitResult fail(String reason, long currentBalance) {
            return new DebitResult(false, null, currentBalance, reason);
        }
    }

    /** On-chain balances in base units (updated by sync) */
    private final ConcurrentHashMap<String, Long> onChainBalances = new ConcurrentHashMap<>();

    /** Pending outgoing debits per address — each TX tracked individually */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<PendingDebit>> pendingDebits = new ConcurrentHashMap<>();

    /** Pending incoming credits per address (cleared when confirmed on-chain or expired) */
    private final ConcurrentHashMap<String, Long> pendingCredits = new ConcurrentHashMap<>();

    /** Timestamp of most recent credit addition per address (for expiry) */
    private final ConcurrentHashMap<String, Long> creditTimestamps = new ConcurrentHashMap<>();

    /** Lock object for atomic check-and-debit */
    private final Object debitLock = new Object();

    private final BlockchainClient client;
    private final Supplier<Set<String>> addressSupplier;
    private final ScheduledExecutorService syncScheduler;
    private final AtomicBoolean nodeOnline = new AtomicBoolean(true);
    private final long maxPendingMs;

    /**
     * @param client           blockchain REST client
     * @param addressSupplier  supplies the set of all known addresses to sync
     * @param syncIntervalSec  how often to sync with the blockchain (seconds)
     * @param maxPendingSeconds max time before a pending TX is considered expired
     */
    public BalanceCache(BlockchainClient client, Supplier<Set<String>> addressSupplier,
                        int syncIntervalSec, int maxPendingSeconds) {
        this.client = client;
        this.addressSupplier = addressSupplier;
        this.maxPendingMs = maxPendingSeconds * 1000L;
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DilithiumEconomy-BalanceSync");
            t.setDaemon(true);
            return t;
        });

        syncScheduler.scheduleAtFixedRate(this::syncAll, syncIntervalSec, syncIntervalSec, TimeUnit.SECONDS);
    }

    /**
     * Returns the effective balance for an address.
     * Effective = on-chain + pending credits - sum(pending debits)
     */
    public long getBalance(String address) {
        long onChain = onChainBalances.getOrDefault(address, 0L);
        long credits = pendingCredits.getOrDefault(address, 0L);
        long debits = sumPendingDebits(address);
        return Math.max(0, onChain + credits - debits);
    }

    /**
     * Atomically checks if the address has sufficient balance and reserves funds.
     * This prevents TOCTOU races where two concurrent withdrawals both pass.
     *
     * @param address the sender's address
     * @param amount  the total amount to reserve (including fee)
     * @return DebitResult with success/failure and a TX ID for rollback
     */
    public DebitResult tryDebit(String address, long amount) {
        synchronized (debitLock) {
            long effective = getBalance(address);
            if (effective < amount) {
                return DebitResult.fail("Insufficient balance", effective);
            }

            String txId = UUID.randomUUID().toString().substring(0, 8);
            pendingDebits.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>())
                    .add(new PendingDebit(txId, amount, System.currentTimeMillis()));

            return DebitResult.ok(txId, effective - amount);
        }
    }

    /**
     * Removes a specific pending debit by ID. Used for immediate rollback
     * when the blockchain node rejects a transaction.
     */
    public void removePendingDebit(String txId) {
        for (var list : pendingDebits.values()) {
            list.removeIf(d -> d.id().equals(txId));
        }
    }

    /**
     * Records a pending credit (optimistic, for deposits before TX is mined).
     * Cleared on next sync when on-chain balance reflects the credit.
     */
    public void addPendingCredit(String address, long amount) {
        pendingCredits.merge(address, amount, Long::sum);
        creditTimestamps.put(address, System.currentTimeMillis());
    }

    /**
     * Returns all pending debits for a given address (for display to the player).
     */
    public List<PendingDebit> getPendingDebits(String address) {
        var list = pendingDebits.get(address);
        return list != null ? new ArrayList<>(list) : List.of();
    }

    /**
     * Returns the total amount locked in pending debits for an address.
     */
    public long getTotalPendingDebits(String address) {
        return sumPendingDebits(address);
    }

    /**
     * Checks if the node was reachable during the last sync.
     */
    public boolean isNodeOnline() {
        return nodeOnline.get();
    }

    /**
     * Fetches the on-chain balance for a single address and updates the cache.
     * Clears pending credits (now reflected on-chain), detects confirmed outgoing
     * transactions by balance decrease, and expires old debits.
     */
    public void syncAddress(String address) {
        try {
            long onChainBalance = client.getBalance(address);
            Long previousBalance = onChainBalances.put(address, onChainBalance);

            // Clear pending credits only when on-chain balance increased (deposit confirmed)
            // or when credits have expired (TX probably failed)
            reconcileCredits(address, previousBalance, onChainBalance);

            // If on-chain balance decreased, outgoing TXs were confirmed.
            // Clear pending debits (oldest first) up to the decrease amount.
            if (previousBalance != null && previousBalance > onChainBalance) {
                long confirmedAmount = previousBalance - onChainBalance;
                clearConfirmedDebits(address, confirmedAmount);
            }

            expirePendingDebits(address);
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
                    Long previousBalance = onChainBalances.put(address, onChainBalance);
                    reconcileCredits(address, previousBalance, onChainBalance);

                    // Detect confirmed outgoing TXs by balance decrease
                    if (previousBalance != null && previousBalance > onChainBalance) {
                        long confirmedAmount = previousBalance - onChainBalance;
                        clearConfirmedDebits(address, confirmedAmount);
                    }

                    expirePendingDebits(address);
                } catch (Exception e) {
                    // Individual address failure, continue with others
                }
            }
        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Balance sync failed: " + e.getMessage());
        }
    }

    /**
     * Reconciles pending credits against on-chain balance changes.
     * <ul>
     *   <li>If on-chain balance increased: the deposit TX was mined. Reduce pending credits
     *       by the increase amount (or clear entirely if increase >= credits).</li>
     *   <li>If on-chain balance unchanged or decreased: keep pending credits alive — the TX
     *       hasn't been mined yet.</li>
     *   <li>If credits are older than maxPendingMs: expire them (TX probably failed).</li>
     * </ul>
     */
    private void reconcileCredits(String address, Long previousBalance, long onChainBalance) {
        Long credits = pendingCredits.get(address);
        if (credits == null || credits <= 0) return;

        // If we have a previous balance and on-chain increased, the deposit was confirmed
        if (previousBalance != null && onChainBalance > previousBalance) {
            long increase = onChainBalance - previousBalance;
            if (increase >= credits) {
                // All pending credits accounted for on-chain
                pendingCredits.remove(address);
                creditTimestamps.remove(address);
            } else {
                // Partial confirmation — reduce pending credits by the confirmed amount
                pendingCredits.put(address, credits - increase);
            }
            return;
        }

        // No on-chain increase — check if credits have expired
        Long ts = creditTimestamps.get(address);
        if (ts != null && (System.currentTimeMillis() - ts) > maxPendingMs) {
            pendingCredits.remove(address);
            creditTimestamps.remove(address);
        }
        // Otherwise keep the pending credit — TX is still in flight
    }

    /**
     * Clears pending debits that have been confirmed on-chain.
     * Removes oldest debits first until the confirmed amount is accounted for.
     * Called when the on-chain balance decreases, indicating outgoing TXs were mined.
     */
    private void clearConfirmedDebits(String address, long confirmedAmount) {
        var list = pendingDebits.get(address);
        if (list == null || list.isEmpty()) return;

        long totalPending = list.stream().mapToLong(PendingDebit::amount).sum();
        if (confirmedAmount >= totalPending) {
            // All pending debits accounted for by on-chain decrease
            pendingDebits.remove(address);
            return;
        }

        // Remove oldest debits first until we've covered the confirmed amount
        long remaining = confirmedAmount;
        var toRemove = new ArrayList<PendingDebit>();
        for (PendingDebit d : list) {
            if (remaining <= 0) break;
            toRemove.add(d);
            remaining -= d.amount();
        }
        list.removeAll(toRemove);
        if (list.isEmpty()) pendingDebits.remove(address);
    }

    /**
     * Removes pending debits older than maxPendingMs.
     * After expiry, the on-chain balance is the source of truth — either the TX
     * was mined (balance decreased) or it was dropped (balance unchanged).
     * Either way, keeping the pending debit longer would be incorrect.
     */
    private void expirePendingDebits(String address) {
        var list = pendingDebits.get(address);
        if (list == null) return;
        long now = System.currentTimeMillis();
        list.removeIf(d -> (now - d.createdAt()) > maxPendingMs);
        if (list.isEmpty()) pendingDebits.remove(address);
    }

    private long sumPendingDebits(String address) {
        var list = pendingDebits.get(address);
        if (list == null || list.isEmpty()) return 0;
        return list.stream().mapToLong(PendingDebit::amount).sum();
    }

    /**
     * Shuts down the sync scheduler.
     */
    public void shutdown() {
        syncScheduler.shutdownNow();
    }
}
