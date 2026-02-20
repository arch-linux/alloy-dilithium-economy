package com.dilithium.economy.blockchain;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP REST client for the Dilithium blockchain node.
 * Connects to the node API (default port 8001).
 */
public final class BlockchainClient {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final String baseUrl;

    public BlockchainClient(String nodeUrl) {
        this.baseUrl = nodeUrl.endsWith("/") ? nodeUrl.substring(0, nodeUrl.length() - 1) : nodeUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * Gets the balance for an address in base units.
     * Uses GET /explorer/address?addr=X and reads the "balance" field.
     * @return balance in base units, or 0 if address not found or node unreachable
     */
    public long getBalance(String address) {
        try {
            String url = baseUrl + "/explorer/address?addr=" + URLEncoder.encode(address, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                if (json.has("data")) {
                    JsonObject data = json.getAsJsonObject("data");
                    if (data.has("balance")) {
                        return data.get("balance").getAsLong();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Failed to get balance for " + address + ": " + e.getMessage());
        }
        return 0;
    }

    /**
     * Submits a signed transaction to the node.
     * Uses POST /transaction with JSON body.
     * @return true if the node accepted the transaction, false otherwise
     */
    public boolean submitTransaction(String from, String to, long amount, long fee,
                                     long timestamp, String signature, String publicKeyHex) {
        try {
            JsonObject tx = new JsonObject();
            tx.addProperty("from", from);
            tx.addProperty("to", to);
            tx.addProperty("amount", amount);
            tx.addProperty("fee", fee);
            tx.addProperty("timestamp", timestamp);
            tx.addProperty("signature", signature);
            tx.addProperty("public_key", publicKeyHex);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/transaction"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(tx)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                JsonElement success = json.get("success");
                return success != null && success.getAsBoolean();
            } else {
                String body = response.body();
                JsonObject json = GSON.fromJson(body, JsonObject.class);
                String msg = json.has("message") ? json.get("message").getAsString() : body;
                System.err.println("[DilithiumEconomy] TX rejected (HTTP " + response.statusCode() + "): " + msg);
                return false;
            }
        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Failed to submit TX: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if the blockchain node is reachable.
     * Uses GET /status.
     */
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/status"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A transaction received from the blockchain explorer.
     */
    public record ChainTransaction(String hash, String from, String to, long amount, long timestamp) {}

    /**
     * Queries the blockchain explorer for incoming transactions to an address.
     * Parses the "transactions" array from GET /explorer/address response.
     * Returns only transactions where {@code to} equals the queried address.
     *
     * @return list of incoming transactions, or empty list if unavailable
     */
    public List<ChainTransaction> getIncomingTransactions(String address) {
        try {
            String url = baseUrl + "/explorer/address?addr=" + URLEncoder.encode(address, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            if (!json.has("data")) return List.of();

            JsonObject data = json.getAsJsonObject("data");
            if (!data.has("transactions")) return List.of();

            List<ChainTransaction> incoming = new ArrayList<>();
            for (JsonElement elem : data.getAsJsonArray("transactions")) {
                JsonObject tx = elem.getAsJsonObject();
                String to = tx.has("to") ? tx.get("to").getAsString() : "";
                if (!address.equals(to)) continue; // only incoming

                String hash = tx.has("hash") ? tx.get("hash").getAsString() : "";
                String from = tx.has("from") ? tx.get("from").getAsString() : "";
                long amount = tx.has("amount") ? tx.get("amount").getAsLong() : 0;
                long timestamp = tx.has("timestamp") ? tx.get("timestamp").getAsLong() : 0;
                incoming.add(new ChainTransaction(hash, from, to, amount, timestamp));
            }
            return incoming;
        } catch (Exception e) {
            // Transaction history not available — graceful degradation
            return List.of();
        }
    }

    public String baseUrl() {
        return baseUrl;
    }
}
