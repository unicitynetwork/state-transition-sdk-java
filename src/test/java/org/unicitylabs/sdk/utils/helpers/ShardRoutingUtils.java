package org.unicitylabs.sdk.utils.helpers;

import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.RequestId;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

public class ShardRoutingUtils {

    public static int getShardForRequest(RequestId requestId, int shardIdLength) {
        // Interpret requestId as hex -> BigInteger
        BigInteger idNum = new BigInteger(requestId.toString(), 16);
        int shardBits = idNum.intValue() & ((1 << shardIdLength) - 1);
        // valid shard IDs start at (1 << shardIdLength)
        return (1 << shardIdLength) | shardBits;
    }

    public static AggregatorClient selectAggregatorForRequest(
            RequestId requestId,
            int shardIdLength,
            List<AggregatorClient> aggregators,
            Map<Integer, AggregatorClient> shardMap) {

        int shardId = getShardForRequest(requestId, shardIdLength);
        return shardMap.getOrDefault(shardId, aggregators.get(0)); // fallback
    }
}