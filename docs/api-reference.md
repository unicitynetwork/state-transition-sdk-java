# Unicity State Transition SDK for Java: API Reference

This document describes SDK behaviour that the README only summarizes. The Javadoc comments in the source are authoritative.

## 1. Waiting for Inclusion Proofs

### The standard wait

`InclusionProofUtils.waitInclusionProof(client, trustBase, predicateVerifier, transaction)` returns a `CompletableFuture<InclusionProof>` for the verified proof of a submitted transaction. A proof is returned only after it verified; nothing else ends the wait successfully. With `WaitOptions`:

| Option | Default | Meaning |
| --- | --- | --- |
| `timeout` | 30 s | Total time of the wait. It covers every request, pause and the verification, and is measured on a monotonic clock. |
| `longPoll` | on | Ask the aggregator to hold each request while the state is pending. `longPoll(false)` polls at the interval and sends no wait parameter. |
| `interval` | 1 s | Pace of the polling fallback. |
| `window` | 20 s | Preferred wait of one long-poll request. |
| `margin` | 2 s | Response margin kept between the end of a server wait and the end of the time left. |

```java
var options = WaitOptions.defaults().timeout(Duration.ofSeconds(60));
InclusionProof proof = InclusionProofUtils
        .waitInclusionProof(client, trustBase, predicateVerifier, transaction, options)
        .get();
```

The overload `waitInclusionProof(..., Duration timeout, Duration interval)` keeps long polling on and uses the interval for the fallback.

### How the wait proceeds

- The first request goes out immediately after the call, with no initial pause.
- Each long-poll request asks for `min(window, time left - margin)` milliseconds. Near the end of the budget no wait fits and an ordinary immediate lookup is made. The HTTP call timeout of such a request is its wait plus the margin, limited by the time left. A request that outlives its own timeout while budget remains is an inconclusive answer, and the rest of that wait polls at the interval.
- A held request is answered as soon as the proof exists, not on a timer. After a held answer the next request is sent at once.
- An answer without the `waitedMs` echo means the aggregator ignores the wait: the rest of that call polls at the interval. A zero echo and an answer that came back quickly are paced at the interval, never less than 10 ms, so a hostile or broken server cannot make the client spin. Nothing is remembered across calls.
- The echo and every status value are unauthenticated. They never extend the deadline and never make a proof valid or invalid. A certified proof is returned whatever the status says.
- A client that does not implement `StatusAwareAggregatorClient` (a custom transport or a test double) is polled at the interval, without wait and status.

### Choosing options

The aggregator certifies in rounds of about 1.5 s on the networks the SDK was measured against. In round units, a wait costs about:

| Mode | Median latency | Requests per proof |
| --- | --- | --- |
| Long poll (default) | 1.5 to 1.7 rounds | 1 |
| Polling at 1 s | 1.9 to 2.0 rounds | about 4 |
| Polling at 250 ms | 1.6 to 1.7 rounds | about 11 |

Latency does not depend on the load of the aggregator until it saturates; the tail grows under saturation, and single waits of 10 s and more were observed there. Keep the default budget of 30 s or more for production, and use `longPoll(false)` only against aggregators that need it. Polling at a short interval buys little latency for many times the requests.

### Status hints

`JsonRpcAggregatorClient` asks the aggregator for a status hint while waiting. The values are `pending`, `expired`, `unknown` and `included`.

- `pending`: the wait continues.
- `expired`: the wait stops at once with `InclusionProofWaitException.Expired`. The same signed request may be submitted again.
- `unknown`: the wait stops with `InclusionProofWaitException.Unknown` after a grace period of two polling intervals from the start of the wait. Right after a submission, another aggregator instance may not have seen the request yet.

`getInclusionProof(stateId, RequestOptions.none().withStatusHint(true))` returns the hint with the answer, without waiting. A server without the feature answers with a plain string and the client behaves as before; the same applies to status values this version does not know.

The hint is unauthenticated and is used only for control flow and user feedback. `unknown` does not show that a state was never certified or is unspent, and `expired` does not show that it is unspent. An `included` hint has no value without a proof that verifies.

With several aggregator instances behind a load balancer, an instance that did not admit the request may answer `unknown`. After a restart, the in-memory status is empty, so queued requests answer `unknown` until a round picks them up.

### Failures

A wait completes exceptionally with:

| Exception | Meaning |
| --- | --- |
| `TimeoutException` | The total time is used up. |
| `CancellationException` | The wait was cancelled. |
| `InclusionProofWaitException.Expired` / `.Unknown` | An unauthenticated hint ended the wait. `isResubmittable()` is true. |
| `InclusionProofWaitException.Conflict` | The aggregator certified another request for this state ID. The proof was authenticated first, so this is not a forged-proof report. Not resubmittable. |
| `VerificationException` | A proof arrived and did not verify. Never resubmitted for. |
| `JsonRpcDataException`, `JsonRpcNetworkException`, `JsonRpcResponseException`, `IOException` | The service's own error (`JsonRpcDataException` is a `JsonRpcNetworkException` whose status is the JSON-RPC code, with its data), an HTTP status, a response that is not a valid answer, or a transport failure. |

`JsonRpcAggregatorClient` checks every response before it interprets it: a bounded body (8 MiB by default), version `2.0`, exactly one of result and error, and the id of the request, also on errors. An error whose id is null is kept and marked with `isIdMatched() == false`.

### Cancellation and the HTTP client

Cancelling a returned future cancels the HTTP call in flight, the pending pause and any outstanding work of that operation. `CompletableFuture.cancel` alone does not reach an in-flight call; the SDK links the two explicitly, also when the cancellation races with the start of the call. A deadline cancels in the same way, and no callback restarts the work afterwards.

Pass your own `OkHttpClient` to `new JsonRpcAggregatorClient(url, apiKey, allowInsecureTransport, httpClient)` to share its connection pool and dispatcher. The SDK disables redirect following on its derived client and never shuts your dispatcher down. A call with its own timeout uses a derived client, so a held request is not cut short by your client's read timeout. Verification of a received proof runs on a small SDK-owned thread pool, not on the HTTP callback thread.

### Resubmission

A certification request is signed and deterministic, so sending it again is the same request; the aggregator never creates a second leaf for it.

`StateTransitionClient.resubmitCertificationRequest(data, options)` is the idempotent form of submission. It returns `SubmitOutcome.Kind.ALREADY_SUBMITTED` instead of an error when the aggregator already holds a record for the state ID. The server reports this in two ways:

- A MongoDB-backed queue rejects a copy of a still-pending request with an internal error (code `-32603`) whose data names a duplicate key on the state ID index. The client recognizes it by that text, since the server has no dedicated code. A Redis-backed queue accepts and drops the copy.
- `STATE_ID_EXISTS` when the state is already certified.

Both reports are by state ID only, so a different request for the same state, such as a conflicting spend, is reported the same way. Every other rejection, such as `REQUEST_EXPIRED`, completes with `CertificationRejectedException`. The plain `submitCertificationRequest` reports both duplicate responses as they are.

`submitAndWait(transaction, data, maxResubmits, trustBase, predicateVerifier, options)` combines submission, waiting, and at most `maxResubmits` resubmissions after `Expired` or `Unknown`. The timeout of `options` is the total of the call. The proof must certify exactly this transaction: if the state ID was certified with another request, the call completes with `InclusionProofWaitException.Conflict` after the proof was authenticated, and nothing is resubmitted. A proof that does not verify fails the call.

### Batches

`StateTransitionClient.getInclusionProofs(stateIds, BatchRequestOptions)` (optional capability `BatchAggregatorClient`, `get_inclusion_proofs.v1`) returns the proofs of 1 to 256 distinct states in request order from one finalized view, or fails as a whole. Absence is a `BatchProofException` of kind `UNAVAILABLE` with the offending indexes, never a successful empty proof. A client without the capability completes with kind `UNSUPPORTED`; a batch is never emulated with single calls.

`withWaitMillis` holds a latest-view request while every missing state is merely pending, and is never sent for an exact state root. A server whose strict parser does not know the wait parameter answers the generic `-32602`, reason `invalid_request`, detail `malformed_params`, without a `waitedMs` echo. The client then repeats the request once without the wait, within the same budget; both requests are counted in `InclusionProofBatch.getRequests()`. No other error is retried, an answer with an echo is never retried, nothing is cached across calls, and there is no separate capability probe.

## 2. Aggregation Partition

All Unicity networks use one aggregation partition, exported as `AggregationPartition.AGGREGATION_PARTITION_ID` (value `7`). It is a protocol constant: there is no environment, constructor or per-network override, and a proof never selects its own expected partition. Network authentication remains mandatory; the trust base fixes the network, authority and epoch.

Every application verification path requires the authenticated unicity certificate to be of this partition. This covers inclusion verification, token verification (genesis, transfers and nested source tokens), non-inclusion verification, and consolidation (input proofs, partition views, enclosing certificates and the unsharded direct target). Generic codecs are partition-agnostic and do not by themselves establish validity.

## 3. Partition Views and Seal Reuse

A `PartitionView` is the complete shard-leaf vector of a partition under one unicity seal, decoded from untrusted bytes as a candidate. `PartitionViewAuthenticator.authenticate` validates its structure, rebuilds the shard tree, follows the enclosing certificate's partition path to the seal hash, matches the enclosing leaf exactly and authenticates the seal under the caller's `RootTrustBase` (network, epoch, quorum). Only then does it return an immutable `AuthenticatedPartitionView`, from which ordinary unicity certificates are derived for every shard, all carrying the same seal. The seal commits to every leaf's configuration hash, so no peer configuration list is needed; independently pinned shard configurations (`PartitionViewPolicy`) are an optional stricter policy. The authority is never taken from a response.

Verification authenticates each distinct seal once per operation (`SealVerificationSession`, a bounded memo of successful quorum checks bound to one trust base and policy). Only the quorum signature check is reused; paths, reference times, predicates and application rules are checked for every proof. `OperationUnicityCertificateVerifier.getStats()` reports the work performed. Seal reuse does not change token size or format.

## 4. Proof Consolidation

Consolidation returns a new, fully verified token whose replaceable outer-history proofs share one unicity seal. This reduces verification cost where a verifier reuses a successful quorum check, for example in a smart contract (EVM) or a zkVM guest.

Properties:

- It runs only when called. Mint, transfer, receive, import and serialization do not call it.
- It needs no signing key and never submits or resubmits a transaction.
- Token format and size do not change. Proofs nested in signed mint justifications or split and burn dependencies stay byte-identical and may still need their own seals; the summary reports outer-history and whole-verification checks separately.

An attempt asks the master shard (the one with the newest authenticated evidence) for its latest batch and partition view, authenticates it under the caller's trust base and configured topology, then fetches the exact roots named by the authenticated view from the other participating shards in bounded parallel. All requests run under one operation budget and one cancellation. The batch call is the optional `BatchAggregatorClient` capability, never emulated with single-proof calls. Waiting is opt-in and applies only to the master's latest request; exact-root requests never carry a wait.

Results: a consolidated token, or an unavailable result with a reason and whether another attempt may help (missing capability, a view that is not retained or not yet published, a busy service). Integrity failures, transport and server failures, timeouts and cancellation complete the future exceptionally. The original token is unchanged.

## 5. Non-inclusion Proofs

> Non-inclusion proves absence only at the authenticated snapshot returned by verification. It does not prove absence now, continuous absence, or that a token can be spent. Compare the snapshot's network and root round (or its certified seal timestamp under your application's time policy) with an independently trusted requirement. The seal timestamp is a consensus-certified time, not proof of real-time freshness. Do not use unverified response metadata for this check.

`StateTransitionClient.getNonInclusionProof(stateId, options)` (optional capability `NonInclusionAggregatorClient`, `get_non_inclusion_proof.v1`) is one call, with no polling and no retry. It returns a `NonInclusionProofResponse`: either an unverified absence proof, or the service's claim that the state is included. The claim is unauthenticated; request an inclusion proof and verify it. Failures are `NonInclusionProofException` with a kind (`UNAVAILABLE`, `BUSY`, `REJECTED`, `SERVER`, `TRANSPORT`, `TIMEOUT`, `CANCELLED`, `INVALID_RESPONSE`).

`NonInclusionVerifier.verify(trustBase, proof, context)` authenticates the unicity certificate before it returns anything: shape, trusted network, authority and epoch interval, quorum, the aggregation partition, the shard and partition commitments together with the optional pins of the `NonInclusionContext`, the query binding and the full state-tree relation. The result is a `NonInclusionOutcome`:

- `ABSENT_AT_SNAPSHOT`: the path proves the key absent.
- `INCLUDED_AT_SNAPSHOT`: the path proves that exact key present.
- A `NonInclusionVerificationException` means rejected; there is no snapshot metadata.

`NonInclusionSnapshot` exposes the network ID, partition ID, shard ID, root round, seal timestamp (Unix seconds), root epoch, shard round, shard epoch and the state root (null for the certified empty tree). Rounds, epochs and timestamps are unsigned 64-bit values held in `long` bits; use the `...Unsigned()` (`BigInteger`) or `...Decimal()` accessors for values that can reach 2^63. All values are read from the authenticated certificate, not from the RPC block number or the time of receipt, and the snapshot owns copies of its data.

There is no freshness option. Freshness is the application's decision on the returned snapshot, against a checkpoint obtained independently of the proof and the server response:

```java
// `checkpoint` is obtained independently of the proof and of the server response.
NonInclusionOutcome outcome = NonInclusionVerifier.verify(trustBase, proof, NonInclusionContext.unsharded());
if (!outcome.isAbsent()) {
    throw new IllegalStateException("no verified absence");
}
NonInclusionSnapshot snapshot = outcome.getSnapshot();
if (snapshot.getNetworkId().getId() != checkpoint.networkId()
        || Long.compareUnsigned(snapshot.getRootRound(), checkpoint.requiredRootRound()) < 0) {
    throw new IllegalStateException("snapshot does not meet application policy");
}
// Absence at `snapshot` only; any spend or finality decision needs its own policy.
```

`NonInclusionContext.unsharded()` expects the unsharded partition, where the certificate's shard is the empty shard. For a sharded partition, pin the served shard with `withShard`. The certificate's shard ID and complete shard path must then be that shard's, and the query and terminal must belong to it. Because the certificate is authenticated first, a rejection reflects the certificate before the path is examined.

`new NonInclusionProof(stateId, bytes)` only records which state ID a proof was requested for. It is request bookkeeping, not evidence.
