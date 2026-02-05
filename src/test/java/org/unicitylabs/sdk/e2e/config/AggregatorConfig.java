package org.unicitylabs.sdk.e2e.config;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralized aggregator URL and shard topology configuration.
 *
 * Standalone: sdk.aggregator.url or AGGREGATOR_URL (default http://localhost:3000).
 * Sharded: sdk.shard.id.length + sdk.shard.<id>.url for each shard.
 * Explicit shard-to-URL mapping — no ordering concerns.
 */
public final class AggregatorConfig {

    private AggregatorConfig() {
    }

    /**
     * Returns the single aggregator URL.
     * Resolution order: system property sdk.aggregator.url, env AGGREGATOR_URL,
     * default http://localhost:3000.
     */
    public static String getSingleUrl() {
        String url = System.getProperty("sdk.aggregator.url");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        url = System.getenv("AGGREGATOR_URL");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        return "http://192.168.43.106:3000";
    }

    /**
     * Returns the shard ID length.
     * Resolution order: system property sdk.shard.id.length, env SHARD_ID_LENGTH,
     * default 1.
     */
    public static int getShardIdLength() {
        String val = System.getProperty("sdk.shard.id.length");
        if (val != null && !val.isEmpty()) {
            return Integer.parseInt(val);
        }
        val = System.getenv("SHARD_ID_LENGTH");
        if (val != null && !val.isEmpty()) {
            return Integer.parseInt(val);
        }
        return 1;
    }

    /**
     * Returns an explicit {shardId -> url} mapping for all expected shards.
     *
     * Shard IDs use a leading 1-bit prefix:
     *   shardIdLength=1 -> shardIds: 2, 3      (binary: 10, 11)
     *   shardIdLength=2 -> shardIds: 4, 5, 6, 7 (binary: 100, 101, 110, 111)
     *
     * Per-shard URL resolution: system property sdk.shard.<id>.url, then env SHARD_<id>_URL.
     *
     * @throws IllegalStateException if any expected shard is missing a URL
     */
    public static Map<Integer, String> getShardUrlMap() {
        int shardIdLength = getShardIdLength();
        int baseId = 1 << shardIdLength;
        int shardCount = 1 << shardIdLength;

        Map<Integer, String> map = new HashMap<>();
        java.util.List<Integer> missing = new java.util.ArrayList<>();

        for (int i = 0; i < shardCount; i++) {
            int shardId = baseId + i;
            String url = resolveShardUrl(shardId);
            if (url == null || url.isEmpty()) {
                missing.add(shardId);
            } else {
                map.put(shardId, url);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing shard URL configuration for shard IDs: " + missing
                            + ". Configure via -Dsdk.shard.<id>.url=<url> or env SHARD_<id>_URL"
            );
        }

        return map;
    }

    private static String resolveShardUrl(int shardId) {
        String propKey = "sdk.shard." + shardId + ".url";
        String url = System.getProperty(propKey);
        if (url != null && !url.isEmpty()) {
            return url;
        }
        String envKey = "SHARD_" + shardId + "_URL";
        url = System.getenv(envKey);
        if (url != null && !url.isEmpty()) {
            return url;
        }
        return null;
    }
}
