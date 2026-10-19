# Unicity State Transition SDK for Java: API Reference

This reference describes the SDK API for application developers: how to connect, mint, transfer, verify and store tokens, and how to wait for proofs and handle failures. The README gives a first introduction. The Javadoc comments in the source are authoritative for every signature and detail not covered here.

All code snippets are compiled with the tests (`src/test/java/org/unicitylabs/sdk/examples`), and the token flow is run against an in-memory aggregator.

## Contents

1. [Setup](#1-setup)
2. [Keys, Signing and Predicates](#2-keys-signing-and-predicates)
3. [Minting](#3-minting)
4. [Transfers](#4-transfers)
5. [Verifying Tokens](#5-verifying-tokens)
6. [Serialization](#6-serialization)
7. [Waiting for Inclusion Proofs](#7-waiting-for-inclusion-proofs)
8. [Errors](#8-errors)
9. [Advanced](#9-advanced)
   - [9.1 Aggregation Partition](#91-aggregation-partition)
   - [9.2 Partition Views and Seal Reuse](#92-partition-views-and-seal-reuse)
   - [9.3 Proof Consolidation](#93-proof-consolidation)
   - [9.4 Non-inclusion Proofs](#94-non-inclusion-proofs)

## 1. Setup

Requires Java 11 or later (Android 12+, API level 31, with core library desugaring). The dependency is `org.unicitylabs:java-state-transition-sdk`; see the README for installation. The application supplies Guava.

Everything below needs three objects: an aggregator client, the trust base of the network, and a `VerificationContext`.

**Aggregator client.** `JsonRpcAggregatorClient` talks to the aggregator gateway over JSON-RPC.

| Constructor | Use |
| --- | --- |
| `JsonRpcAggregatorClient(url)` | No API key. |
| `JsonRpcAggregatorClient(url, apiKey)` | Sends the key as a bearer token. The URL must be `https`; a key is never sent over plaintext HTTP. |
| `JsonRpcAggregatorClient(url, apiKey, allowInsecureTransport)` | `true` permits a key over `http`. For local development only. |
| `JsonRpcAggregatorClient(url, apiKey, allowInsecureTransport, httpClient)` | Uses your `OkHttpClient`; see [HTTP client](#http-client-and-timeouts). |

`StateTransitionClient` wraps an aggregator client and offers submission, proof lookup and the waiting operations. All its network operations return `CompletableFuture`.

```java
var aggregator = new JsonRpcAggregatorClient(
    "https://gateway.testnet2.unicity.network", apiKey);
return new StateTransitionClient(aggregator);
```

**Trust base.** The trust base identifies the network, its validators and quorum. Download the file for your network (links in the README), keep a trusted copy with the application, and load it:

```java
return RootTrustBase.fromJson(Files.readString(Path.of("bft-trustbase.testnet2.json")));
```

`trustBase.getNetworkId()` is the network ID to use when minting.

**Verification context.** `VerificationContext` bundles what token verification needs: the trust base, a `PredicateVerifierService` (`PredicateVerifierService.create()` handles the built-in predicates), a `MintJustificationVerifierService` (empty unless you use token splits, see [Payments and splits](#payments-and-splits)) and a `TokenIssuanceVerifierService`.

```java
var predicateVerifier = PredicateVerifierService.create();
return new VerificationContext(trustBase, predicateVerifier,
    new MintJustificationVerifierService(), new TokenIssuanceVerifierService(false));
```

The default `new TokenIssuanceVerifierService()` rejects every token whose type has no registered `TokenIssuanceVerifier`. The snippet passes `false` to accept all types, which is suitable for examples and tests. A cryptographically valid token is not necessarily one your application should accept; in production, register a `TokenIssuanceVerifier` for each token type you accept.

### HTTP client and timeouts

The default client uses OkHttp defaults (10 s connect, read and write timeouts, no call timeout) and does not follow redirects. To change them, pass your own `OkHttpClient`; the SDK shares its connection pool and dispatcher, never shuts the dispatcher down, and switches redirect following off on the requests it makes.

```java
var http = new OkHttpClient.Builder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();
return new JsonRpcAggregatorClient(url, apiKey, false, http);
```

A single call can have its own limit. `RequestOptions.withCallTimeout` bounds the whole HTTP call and replaces the client's read timeout for that call:

```java
return client.getInclusionProof(stateId,
    RequestOptions.none().withCallTimeout(Duration.ofSeconds(5)));
```

Waiting for a proof has its own total budget, see [section 7](#7-waiting-for-inclusion-proofs). The response body is limited to 8 MiB.

## 2. Keys, Signing and Predicates

A token is owned by a *predicate*: a lock that decides who may spend it. The SDK's built-in predicate is `SignaturePredicate`, which locks a token to a secp256k1 public key. The owner spends the token with a signature over the transaction. The SDK never stores keys.

| Task | API |
| --- | --- |
| Generate a private key | `SigningService.generatePrivateKey()` (`byte[]`). |
| Create a signer | `new SigningService(privateKey)`, or `SigningService.generate()` for a random one. |
| Public key | `signingService.getPublicKey()`. |
| Ownership predicate of a signer | `SignaturePredicate.fromSigningService(signingService)`. |
| Ownership predicate of someone else | `SignaturePredicate.create(publicKey)`; this is how you address a recipient. |
| Authorize a spend | `SignaturePredicateUnlockScript.create(transaction, signingService)`. |

```java
var signingService = new SigningService(storedPrivateKey);
return SignaturePredicate.fromSigningService(signingService);
```

```java
return SignaturePredicate.create(recipientPublicKey);
```

Store the private key securely; whoever holds it controls the tokens locked to it. `Predicate` is the common interface; transactions hold the encoded form (`EncodedPredicate`), which has `equals`. `BurnPredicate` locks a token forever and is used by splits.

## 3. Minting

A mint creates a token with a new ID. Build a `MintTransaction`, submit its certification data, wait for the inclusion proof, and build the verified `Token`.

```java
MintTransaction mint = MintTransaction.builder(context.getTrustBase().getNetworkId(), owner)
    .tokenType(new TokenType("my-token-type".getBytes(StandardCharsets.UTF_8)))
    .data(payload)
    .build();
// persist the exact transaction before it is submitted
journal.put("mint", mint.toCbor());

CertificationData data = CertificationData.fromMintTransaction(mint);
CertificationResponse response = client.submitCertificationRequest(data).get();
if (response.getStatus() != CertificationStatus.SUCCESS) {
  throw new IllegalStateException("Mint rejected: " + response.getStatus());
}

InclusionProof proof = InclusionProofUtils.waitInclusionProof(
    client, context.getTrustBase(), context.getPredicateVerifier(), mint).get();
CertifiedMintTransaction genesis = mint.toCertifiedTransaction(
    context.getTrustBase(), context.getPredicateVerifier(), proof);
return Token.mint(genesis, context);
```

`MintTransaction.builder(networkId, recipient)` takes the network and the owner predicate of the new token. Optional settings:

| Builder method | Default | Meaning |
| --- | --- | --- |
| `tokenType(TokenType)` | random | Application-defined type, 1 to 64 bytes (`new TokenType(bytes)`; `TokenType.generate()`). Issuance policies are registered per type. |
| `salt(TokenSalt)` | random, 32 bytes | 16 to 64 bytes. The token ID is derived from the network and the salt, so the same salt gives the same ID. Part of what the mint commits to; see [Crash recovery](#crash-recovery). |
| `data(byte[])` | none | Application payload, committed by the mint. |
| `justification(byte[])` | none | Evidence for the mint, verified by a registered `MintJustificationVerifier` (used by splits). |
| `expiresAt(Long)` | service assigned | Exclusive deadline of the request in Unix seconds. With none, the aggregator sets it from its consensus time and no local clock is needed. |

The token ID is `mint.getTokenId()`. The `journal` in the snippet stands for the application's durable storage; the line before the submission is explained next.

### Crash recovery

Persist the exact transaction before submitting it, and after a crash recover by deserializing it, never by rebuilding it. A certification request is deterministic only for exactly the same transaction. The token ID depends only on network and salt, but the transaction also commits to the token type, recipient, data, justification and deadline. Rebuilding with, say, a fresh random token type is a different request for the same token ID: it conflicts with the first one, and the token is lost. Persisting only the salt is not enough.

```java
// deserialize the persisted transaction; never rebuild it
MintTransaction mint = MintTransaction.fromCbor(journal.get("mint"));
return completeMint(client, context, mint);
```

```java
CertificationData data = CertificationData.fromMintTransaction(mint);
InclusionProof proof = client.submitAndWait(mint, data, 2, context.getTrustBase(),
    context.getPredicateVerifier(), WaitOptions.defaults()).get();
return Token.mint(mint.toCertifiedTransaction(
    context.getTrustBase(), context.getPredicateVerifier(), proof), context);
```

Submitting the persisted transaction again is safe at any point: before the first submission reached the aggregator, while it is pending, or after it was certified (see [Resubmission](#resubmission)). The same rule applies to transfers ([section 4](#4-transfers)) and splits ([Payments and splits](#payments-and-splits)).

`submitCertificationRequest` completes with a `CertificationResponse`; check its status. `SUCCESS` means the request is accepted and queued, not certified. Other statuses are listed in [section 8](#8-errors). Waiting for the proof is described in [section 7](#7-waiting-for-inclusion-proofs). `toCertifiedTransaction` and `Token.mint` verify what they receive and throw `VerificationException` if it is not valid.

For a single call with resubmission, use `StateTransitionClient.submitAndWait`, shown in [Transfers](#4-transfers).

### Payments and splits

For fungible value, the payload of a token is a `PaymentData`: your class implementing `getAssets()` (a `PaymentAssetCollection`) and `encode()`.

```java
var coins = new Asset(new AssetId("COIN".getBytes(StandardCharsets.UTF_8)),
    BigInteger.valueOf(500));
var points = new Asset(new AssetId("POINT".getBytes(StandardCharsets.UTF_8)),
    BigInteger.valueOf(20));
return PaymentAssetCollection.create(coins, points);
```

An `AssetId` is 1 to 128 bytes, a value is a positive integer below 2^256, and a collection holds 1 to 256 distinct assets. Mint the encoded payment data as the payload:

```java
byte[] payload = new Payment(assets).encode();
return mint(client, context, owner, payload, journal);
```

`TokenSplit.split(token, paymentDataDeserializer, requests, burnStateMask, burnExpiresAt)` divides a payment token into new tokens. For every asset, the outputs together must carry exactly the amount of the source. The result holds a burn transaction for the source token and, for each output, the data to mint it. `split` throws the checked `org.unicitylabs.sdk.smt.LeafExistsException` when two outputs would get the same token ID, for example a reused salt. It is thrown by the call itself, not delivered through a future.

1. Persist every input of the split (below), then submit the burn transaction as an ordinary transfer and keep the burned token.
2. For every `SplitToken`, mint it with the type, salt, network, recipient and payment data it names, and with the justification `SplitMintJustification.create(burnedToken, splitToken.getProofs()).toCbor()`. Persist each mint transaction before submitting it.

The burn commits to the output token IDs, which come from the output salts. A split rebuilt with other salts, another state mask or another deadline is a different, conflicting burn of the source token, and its value is lost. So fix the salts when creating the requests, and persist all inputs before the burn is submitted:

```java
var coins = new Asset(new AssetId("COIN".getBytes(StandardCharsets.UTF_8)),
    BigInteger.valueOf(500));
var points = new Asset(new AssetId("POINT".getBytes(StandardCharsets.UTF_8)),
    BigInteger.valueOf(20));
// fix the salts: they are part of what the burn commits to
List<SplitTokenRequest> requests = List.of(
    SplitTokenRequest.create(first, new Payment(PaymentAssetCollection.create(coins)),
        TokenSalt.generate()),
    SplitTokenRequest.create(second, new Payment(PaymentAssetCollection.create(points)),
        TokenSalt.generate()));
return requests;
```

```java
// persist every input of the split before anything is submitted
for (int i = 0; i < requests.size(); i++) {
  SplitTokenRequest request = requests.get(i);
  journal.put("split.recipient." + i,
      EncodedPredicate.fromPredicate(request.getRecipient()).toCbor());
  journal.put("split.payment." + i, request.getPaymentData().encode());
  journal.put("split.salt." + i, request.getSalt().getBytes());
}
journal.put("split.mask", burnMask.getBytes());
journal.put("split.expiresAt", ByteBuffer.allocate(Long.BYTES).putLong(burnExpiresAt).array());
return TokenSplit.split(source, Payment::decode, requests, burnMask, burnExpiresAt);
```

```java
TransferTransaction burn = split.getBurnTransaction();
CertificationData burnData = CertificationData.fromTransaction(
    burn, SignaturePredicateUnlockScript.create(burn, owner));
journal.put("split.burn", burn.toCbor());
journal.put("split.burn.data", burnData.toCbor());
Token burned = completeTransfer(client, context, source, burn, burnData);

List<Token> outputs = new ArrayList<>();
for (int i = 0; i < split.getTokens().size(); i++) {
  SplitToken output = split.getTokens().get(i);
  byte[] justification = SplitMintJustification.create(burned, output.getProofs()).toCbor();
  MintTransaction mint = MintTransaction.builder(output.getNetworkId(), output.getRecipient())
      .tokenType(output.getTokenType())
      .salt(output.getSalt())
      .data(output.getPaymentData().encode())
      .justification(justification)
      .build();
  journal.put("split.mint." + i, mint.toCbor());
  outputs.add(completeMint(client, context, mint));
}
return outputs;
```

Recovery. If the burn transaction was persisted, resubmit the persisted burn like any transfer ([section 4](#recovering-a-transfer)). The split outputs need the proofs of the `SplitResult`, which cannot be serialized: rebuild the result from the persisted inputs, and check that its burn is the persisted one before using it. Mints that were already persisted are recovered as in [Crash recovery](#crash-recovery).

```java
List<SplitTokenRequest> requests = new ArrayList<>();
for (int i = 0; i < outputs; i++) {
  requests.add(SplitTokenRequest.create(
      EncodedPredicate.fromCbor(journal.get("split.recipient." + i)),
      Payment.decode(journal.get("split.payment." + i)),
      TokenSalt.fromBytes(journal.get("split.salt." + i))));
}
SplitResult split = TokenSplit.split(source, Payment::decode, requests,
    StateMask.fromBytes(journal.get("split.mask")),
    ByteBuffer.wrap(journal.get("split.expiresAt")).getLong());
// the rebuilt burn must be the persisted one, byte for byte
if (!Arrays.equals(split.getBurnTransaction().toCbor(), journal.get("split.burn"))) {
  throw new IllegalStateException("the split inputs do not reproduce the persisted burn");
}
return split;
```

Verifying a split output needs the context to know how to check the justification:

```java
var justifications = new MintJustificationVerifierService()
    .register(new SplitMintJustificationVerifier(Payment::decode));
return new VerificationContext(trustBase, PredicateVerifierService.create(), justifications,
    new TokenIssuanceVerifierService(false));
```

The complete helper code is in `ApiReferenceExamples`. The burn is final once certified: keep the burned token and the persisted inputs until all outputs are minted.

## 4. Transfers

A transfer moves a token to a new owner in three steps: the current owner creates a transfer to the recipient predicate, signs it, and has it certified; the result is the same token with one more transaction.

```java
TransferTransaction transfer = TransferTransaction.create(
    token, recipient, StateMask.generate(), null);
CertificationData data = CertificationData.fromTransaction(
    transfer, SignaturePredicateUnlockScript.create(transfer, sender));
// persist the exact transaction and its certification data before submitting
journal.put("transfer", transfer.toCbor());
journal.put("transfer.data", data.toCbor());
return completeTransfer(client, context, token, transfer, data);
```

```java
InclusionProof proof = client.submitAndWait(transfer, data, 2, context.getTrustBase(),
    context.getPredicateVerifier(), WaitOptions.defaults()).get();
return token.transfer(transfer.toCertifiedTransaction(
    context.getTrustBase(), context.getPredicateVerifier(), proof), context);
```

`TransferTransaction.create(token, recipient, stateMask, data)` spends the token's latest state. The `StateMask` (16 to 64 random bytes, `StateMask.generate()`) makes the recipient's new state unguessable; `data` is an optional transfer payload (`null` for none), and a fifth argument sets an explicit `expiresAt` like for mints.

`submitAndWait(transaction, data, maxResubmits, trustBase, predicateVerifier, options)` submits, waits for the proof, resubmits after `Expired` or `Unknown` at most `maxResubmits` times, and checks that the proof certifies exactly this transaction. Use it for transfers; see [Resubmission](#resubmission). `token.transfer(certified, context)` verifies the transfer and returns a new `Token`; the original is unchanged and must not be spent again.

**Delivering the token.** The sender serializes the updated token (`token.toCbor()`, [section 6](#6-serialization)) and sends it to the recipient over the application's own channel. Nothing is sent to the aggregator for the recipient.

**Receiving.** The recipient loads the token, verifies it, and checks that it is locked to them. Verification alone does not establish ownership:

```java
return received.getLatestTransaction().getRecipient().equals(EncodedPredicate.fromPredicate(me));
```

The recipient later spends the token the same way, signing with their own `SigningService`. Each state can be spent once; a second spend of the same state is refused by the aggregator or conflicts with the first.

**Certified transactions.** A `CertifiedMintTransaction` or `CertifiedTransferTransaction` is a transaction together with the inclusion proof that certifies it. They are what `toCertifiedTransaction` returns and what a `Token` holds: `token.getGenesis()`, `token.getTransactions()` and `token.getLatestTransaction()`. Their accessors expose recipient, lock script, payload, state mask and the proof (`getInclusionProof()`).

### Recovering a transfer

A transfer has a random state mask and a signature, so a rebuilt transfer is a different request that conflicts with the first one if that reached the aggregator. As for mints, persist the exact transaction and its certification data before submitting (the snippet above), and after a crash deserialize both. The transaction's source state and lock script are chain context: take them from the token as it was before the transfer.

```java
// `token` is the token as it was before the transfer
Transaction latest = token.getLatestTransaction();
TransferTransaction transfer = TransferTransaction.fromCbor(journal.get("transfer"),
    latest.calculateStateHash(), latest.getRecipient());
CertificationData data = CertificationData.fromCbor(journal.get("transfer.data"));
return completeTransfer(client, context, token, transfer, data);
```

Keep the token as it was before the transfer until the transfer is certified and the new token is stored.

## 5. Verifying Tokens

`token.verify(context)` checks the genesis and every transfer: the unicity proofs against the trust base, the unlock signatures against the lock predicates, the chain of states, the issuance policy of the token type, mint justifications, and the tokens nested in justifications.

```java
VerificationResult<VerificationStatus> result = token.verify(context);
if (result.getStatus() != VerificationStatus.OK) {
  throw new IllegalArgumentException("Token rejected: " + result);
}
```

The result has a status, `OK` or `FAIL`, a message, and `getResults()`, a tree with one entry per rule and per nested token that explains a failure. `toString()` prints it.

`Token.mint`, `token.transfer`, `toCertifiedTransaction` and the waiting operations verify what they receive and throw `VerificationException` (an unchecked exception with `getVerificationResult()`) instead of returning a failed result.

What verification does not decide: whether the token is locked to *you* ([Receiving](#4-transfers)), whether you accept its issuer and type (the `TokenIssuanceVerifierService`), and whether the latest state has since been spent. A previous owner's copy of a token still verifies, but cannot be spent: the aggregator certifies one spend per state.

## 6. Serialization

Tokens and transactions are serialized as CBOR. They have no JSON form; where you need text, encode the bytes with `HexConverter.encode` and decode with `HexConverter.decode`.

```java
byte[] bytes = token.toCbor();
String hex = HexConverter.encode(bytes);
return Token.fromCbor(HexConverter.decode(hex));
```

| Type | Serialize | Deserialize |
| --- | --- | --- |
| `Token` | `toCbor()` | `Token.fromCbor(bytes)`; checks structure and links the transactions, not their validity. Call `verify`. |
| `MintTransaction`, `CertifiedMintTransaction` | `toCbor()` | `fromCbor(bytes)` |
| `TransferTransaction`, `CertifiedTransferTransaction` | `toCbor()` | `fromCbor(bytes, sourceStateHash, lockScript)`; the state spent and its lock are chain context, supplied from the preceding transaction. |
| `InclusionProof`, `CertificationData`, `StateId`, `TokenId`, `TokenType`, `TokenSalt`, `StateMask` | `toCbor()` | `fromCbor(bytes)` |

`Token.fromCbor` never trusts its input. Deserialize received bytes, then verify them with your context.

**Pending operations.** Serialization is also how an application survives a crash. Before submitting a mint, a transfer or a split, persist the exact transaction (`toCbor()`), the `CertificationData` of a transfer, and for a split its inputs (recipients, payment data, output salts, state mask and deadline). After a crash, deserialize them and submit again; never rebuild. Examples: [Crash recovery](#crash-recovery), [Recovering a transfer](#recovering-a-transfer) and [Payments and splits](#payments-and-splits).

JSON is used for the trust base: `RootTrustBase.fromJson(json)` and `trustBase.toJson()`.

```java
String json = trustBase.toJson();
return RootTrustBase.fromJson(json).getNetworkId().toString();
```

## 7. Waiting for Inclusion Proofs

### The standard wait

`InclusionProofUtils.waitInclusionProof(client, trustBase, predicateVerifier, transaction)` returns a `CompletableFuture<InclusionProof>` for the verified proof of a submitted transaction. A proof is returned only after it verified; nothing else ends the wait successfully. With `WaitOptions`:

| Option | Default | Meaning |
| --- | --- | --- |
| `timeout` | 15 s | Total time of the wait. It covers every request, pause and the verification, and is measured on a monotonic clock. |
| `longPoll` | on | Ask the aggregator to hold each request while the state is pending. `longPoll(false)` polls at the interval and sends no wait parameter. |
| `interval` | 1 s | Pace of the polling fallback. |
| `window` | 20 s | Preferred wait of one long-poll request. |
| `margin` | 2 s | Response margin kept between the end of a server wait and the end of the time left. |

```java
var options = WaitOptions.defaults().timeout(Duration.ofSeconds(60));
return InclusionProofUtils
    .waitInclusionProof(client, trustBase, predicateVerifier, transaction, options)
    .get();
```

The overload `waitInclusionProof(..., Duration timeout, Duration interval)` keeps long polling on and uses the interval for the fallback.

### How the wait proceeds

- The first request goes out immediately, with no initial pause. A held request is answered as soon as the proof exists, and the next request follows at once.
- Each long-poll request asks for `min(window, time left - margin)`. Near the end of the budget an ordinary immediate lookup is made.
- A server that ignores the wait (no `waitedMs` echo) is polled at the interval for the rest of that call. Nothing is remembered across calls.
- The echo and every status value are unauthenticated. They never extend the deadline and never make a proof valid or invalid. A certified proof is returned whatever the status says.
- A client that does not implement `StatusAwareAggregatorClient` (a custom transport, a test double) is polled at the interval.

### Choosing options

The aggregator certifies in rounds of about 1.5 s on the networks the SDK was measured against. In round units, a wait costs about:

| Mode | Median latency | Requests per proof |
| --- | --- | --- |
| Long poll (default) | 1.5 to 1.7 rounds | 1 |
| Polling at 1 s | 1.9 to 2.0 rounds | about 4 |
| Polling at 250 ms | 1.6 to 1.7 rounds | about 11 |

Latency does not depend on the load of the aggregator until it saturates; the tail grows under saturation, and single waits of 10 s and more were observed there. The default budget is 15 s in every Unicity SDK: the wait polls at a fixed interval, with no exponential backoff, and fails relatively fast. Raise `timeout` where the network is saturated or a late proof is acceptable. Use `longPoll(false)` only against aggregators that need it. Polling at a short interval buys little latency for many times the requests.

### Status hints

`JsonRpcAggregatorClient` asks the aggregator for a status hint while waiting: `pending`, `expired`, `unknown` or `included` (`ProofStatusHint`).

- `pending`: the wait continues.
- `expired`: the wait stops at once with `InclusionProofWaitException.Expired`. The same signed request may be submitted again.
- `unknown`: the wait stops with `InclusionProofWaitException.Unknown` after a grace period of two polling intervals. Right after a submission, another aggregator instance may not have seen the request yet.

`getInclusionProof(stateId, RequestOptions.none().withStatusHint(true))` returns the hint with the answer, without waiting. A server without the feature answers with a plain string and the client behaves as before; the same applies to status values this version does not know.

The hint is unauthenticated and only for control flow and user feedback. `unknown` does not show that a state was never certified, and `expired` does not show that it is unspent. An `included` hint has no value without a proof that verifies. With several aggregator instances behind a load balancer, an instance that did not admit the request may answer `unknown`; after a restart, queued requests answer `unknown` until a round picks them up.

### Failures

A wait completes exceptionally with the exceptions of [section 8](#8-errors). One deadline covers a whole operation, a wait or a `submitAndWait` with all its submissions and waits. When it passes, the future completes with `TimeoutException`, the active HTTP call and pause are cancelled, and a response or verification that finishes late is discarded.

`JsonRpcAggregatorClient` checks every response before interpreting it: a bounded body, one complete JSON object, version `2.0`, exactly one of `result` and `error`, and the id of the request, also on errors. An error with a null id is kept for diagnostics (`isIdMatched() == false`) and is never taken as an acknowledgement or a status.

### Cancellation

Cancelling a returned future cancels the HTTP call in flight, the pending pause and any outstanding work of that operation, also when the cancellation races with the start of the call. A deadline cancels in the same way. A call with its own timeout uses a derived client, so a held request is not cut short by your client's read timeout. Verification of a received proof runs on a small SDK-owned thread pool, not on the HTTP callback thread.

```java
CompletableFuture<InclusionProof> wait =
    InclusionProofUtils.waitInclusionProof(client, trustBase, predicateVerifier, transaction);
// cancelling the future cancels the HTTP call in flight and the pending pause
wait.cancel(true);
return wait;
```

### Resubmission

A certification request is signed and deterministic, so sending it again is the same request; the aggregator never creates a second leaf for it.

`StateTransitionClient.resubmitCertificationRequest(data, options)` is the idempotent form of submission. It returns `SubmitOutcome.Kind.ALREADY_SUBMITTED` instead of an error when the aggregator already holds a record for the state ID. That report is by state ID only: the aggregator may hold a different request for the same state, such as a conflicting spend. The proof tells which one was certified. Every other rejection, such as `REQUEST_EXPIRED`, completes with `CertificationRejectedException`. The plain `submitCertificationRequest` returns the aggregator's status as it is, including `STATE_ID_EXISTS`.

Resubmit the persisted transaction, never a rebuilt one ([Crash recovery](#crash-recovery)).

```java
return client.resubmitCertificationRequest(data, RequestOptions.none());
```

`submitAndWait(transaction, data, maxResubmits, trustBase, predicateVerifier, options)` combines submission, waiting, and at most `maxResubmits` resubmissions after `Expired` or `Unknown`. The timeout of `options` is the total of the call. The proof must certify exactly this transaction: if the state ID was certified with another request, the call completes with `InclusionProofWaitException.Conflict` (authenticated, not resubmittable), and nothing is resubmitted. A proof that does not verify fails the call.

```java
// the first submission and at most two resubmissions; the proof must certify this transaction
return client.submitAndWait(transaction, data, 2, trustBase, predicateVerifier,
    WaitOptions.defaults());
```

### Batches

`StateTransitionClient.getInclusionProofs(stateIds, BatchRequestOptions)` (optional capability `BatchAggregatorClient`, `get_inclusion_proofs.v1`) returns the proofs of 1 to 256 distinct states in request order from one finalized view, or fails as a whole. Absence is a `BatchProofException` of kind `UNAVAILABLE` with the offending indexes (`getIndexes()`), never a successful empty proof. A client without the capability completes with kind `UNSUPPORTED`; a batch is never emulated with single calls.

`withWaitMillis` holds a latest-view request while every missing state is merely pending, and is never sent for an exact state root. A server whose strict parser does not know the wait parameter answers `-32602` (`invalid_request`, `malformed_params`) without a `waitedMs` echo; the client then repeats the request once without the wait, within the same budget (both requests are counted in `InclusionProofBatch.getRequests()`). No other error is retried.

```java
// hold the request while every missing state is pending; retried once without the wait by a
// server that does not know the parameter
return client.getInclusionProofs(stateIds, BatchRequestOptions.latest().withWaitMillis(5_000));
```

## 8. Errors

Every asynchronous operation completes its future exceptionally. `future.get()` throws `ExecutionException`; the failure is `getCause()`. `join()` throws `CompletionException`; the failure is again `getCause()`. A cancelled future throws `CancellationException`.

```java
try {
  wait.get();
  return "proof";
} catch (ExecutionException e) {
  Throwable failure = e.getCause();
  if (failure instanceof InclusionProofWaitException
      && ((InclusionProofWaitException) failure).isResubmittable()) {
    return "submit the same request again";
  }
  if (failure instanceof TimeoutException) {
    return "no proof in time: the state may still be certified later";
  }
  if (failure instanceof VerificationException) {
    return "proof did not verify: do not accept";
  }
  if (failure instanceof CertificationRejectedException) {
    return "rejected: " + ((CertificationRejectedException) failure).getStatus();
  }
  return "service or transport failure: " + failure;
} catch (CancellationException e) {
  return "cancelled";
}
```

| Exception | When | What to do |
| --- | --- | --- |
| `CertificationResponse` status other than `SUCCESS` | The aggregator refused a submission (not an exception). | `STATE_ID_EXISTS`, `ALREADY_SUBMITTED`, `STATE_ID_QUEUED`: the state ID is already known, not necessarily with your request; use `resubmitCertificationRequest` and the proof to find out. `REQUEST_EXPIRED`: the request deadline passed; create a new transaction. `SERVICE_NOT_READY`: retry later. Other statuses (`SIGNATURE_VERIFICATION_FAILED`, `INVALID_*`, `STATE_ID_MISMATCH`, `UNSUPPORTED_ALGORITHM`, `INVALID_SHARD`) are defects in the request; do not retry. |
| `CertificationRejectedException` | `resubmitCertificationRequest` or `submitAndWait` got a refusal other than a duplicate. `getStatus()`. | As for the status above. |
| `TimeoutException` | The total time of an operation is used up. | The transaction may still be certified later. Resume from the persisted transaction: deserialize it and call `submitAndWait` again ([Crash recovery](#crash-recovery)). Do not rebuild it. |
| `InclusionProofWaitException.Expired` / `.Unknown` | An unauthenticated hint ended the wait. `isResubmittable()` is true. | Submit the same request again (`submitAndWait` does). |
| `InclusionProofWaitException.Conflict` | Another request was certified for this state. Authenticated. | The state is spent by something else; do not resubmit. |
| `VerificationException` | A proof, token or certified transaction did not verify. `getVerificationResult()`. | Do not accept the data. Never resubmitted for. |
| `JsonRpcNetworkException`, `JsonRpcDataException` | The service answered with an HTTP status or JSON-RPC error (`getStatus()`, `getRpcError()`), or the transport failed. Checked exceptions; they only appear as the cause of a failed future. | Retry transient failures with your own policy. |
| `JsonRpcResponseException` | The response is not a valid answer. | Treat as a faulty or malicious service. |
| `BatchProofException` | A batch lookup failed. `getKind()`: `UNAVAILABLE`, `UNSUPPORTED`, `REJECTED`, `LIMIT_EXCEEDED`, `SERVER`, `TRANSPORT`, `INVALID_RESPONSE`. | `isRetryable()` tells whether a new attempt may succeed. |
| `NonInclusionProofException` | A non-inclusion lookup failed. `getKind()`: `UNAVAILABLE`, `BUSY`, `REJECTED`, `SERVER`, `TRANSPORT`, `TIMEOUT`, `CANCELLED`, `INVALID_RESPONSE`. | `isRetryable()`. |
| `ConsolidationException` | Consolidation failed. `getKind()`: `CANCELLED`, `INPUT`, `INTEGRITY`, `INVALID_RESPONSE`, `REJECTED`, `SERVER`, `TIMEOUT`, `TRANSPORT`. | `isRetryable()`; `INTEGRITY` means the material was refused. |
| `LeafExistsException` | `TokenSplit.split` found two outputs with the same token ID (a reused salt). Checked; thrown by the call, not through a future. | Use a distinct salt for every output. |
| `IllegalArgumentException`, `NullPointerException` | A builder or factory got invalid input (sizes, ranges, null). | Fix the call. |
| `CancellationException` | The caller cancelled the future. | None. |

Checked versus unchecked: `JsonRpcNetworkException` (and its subclass `JsonRpcDataException`) are checked, but only ever appear as the cause of a failed future, so they need no `throws` clause. `LeafExistsException` is the one checked exception that a call throws directly: `TokenSplit.split` declares it. Every other domain error is unchecked and typed.

## 9. Advanced

### 9.1 Aggregation Partition

All Unicity networks use one aggregation partition, exported as `AggregationPartition.AGGREGATION_PARTITION_ID` (value `7`). It is a protocol constant: there is no environment, constructor or per-network override, and a proof never selects its own expected partition. Network authentication remains mandatory; the trust base fixes the network, authority and epoch.

Every application verification path requires the authenticated unicity certificate to be of this partition: inclusion verification, token verification (genesis, transfers and nested source tokens), non-inclusion verification, and consolidation.

### 9.2 Partition Views and Seal Reuse

A `PartitionView` is the complete shard-leaf vector of a partition under one unicity seal, decoded from untrusted bytes. `PartitionViewAuthenticator.authenticate` validates it, rebuilds the shard tree, follows the enclosing certificate to the seal hash and authenticates the seal under the caller's `RootTrustBase`. Only then does it return an immutable `AuthenticatedPartitionView`, from which ordinary unicity certificates are derived for every shard, all carrying the same seal. The seal commits to every leaf's configuration hash; independently pinned shard configurations (`PartitionViewPolicy`) are an optional stricter policy. The authority is never taken from a response.

Within one verification operation (for example one `token.verify(context)`), each distinct seal is authenticated once and then reused; the network, epoch and partition checks, paths, reference times, predicates and application rules still run for every proof. Seal reuse does not change token size or format. The details are in the Javadoc of `SealVerificationSession` and `OperationUnicityCertificateVerifier`.

### 9.3 Proof Consolidation

`new TokenProofClient(shardRouting, verificationContext).consolidate(token)` (or `consolidate(token, options)`) returns a `ConsolidationResult`: a new, fully verified token whose replaceable outer-history proofs share one unicity seal, or an unavailable result.

```java
var routing = ShardRouting.single(client.getAggregatorClient());
return new TokenProofClient(routing, context).consolidate(token);
```

`ShardRouting` holds the caller's endpoint configuration per shard (`ShardRouting.single(client)` for an unsharded partition); routing is never taken from a proof or a peer. Consolidation reduces verification cost where a verifier reuses a successful quorum check, for example in a smart contract (EVM) or a zkVM guest.

- It runs only when called. Mint, transfer, receive, import and serialization do not call it.
- It needs no signing key and never submits or resubmits a transaction.
- Token format and size do not change. Proofs nested in signed mint justifications or split and burn dependencies stay byte-identical and may still need their own seals; the summary reports outer-history and whole-verification checks separately.

An attempt asks the master shard (the one with the newest authenticated evidence) for its latest batch and partition view, authenticates it under the caller's trust base and configured topology, then fetches the exact roots named by the authenticated view from the other participating shards in bounded parallel. All requests run under one operation budget (`ConsolidationOptions.withTimeout`, default 10 s) and one cancellation. The batch call is the optional `BatchAggregatorClient` capability, never emulated. Waiting is opt-in (`withMasterLongPoll`) and applies only to the master's latest request.

Results: `getStatus()` is `CONSOLIDATED` (`getToken()`, `getSummary()`) or `UNAVAILABLE` (`getUnavailability()` with a reason and whether another attempt may help: a missing capability, a view that is not retained or not yet published, a busy service). Integrity failures, transport and server failures, timeouts and cancellation complete the future exceptionally with `ConsolidationException`. The original token is unchanged.

### 9.4 Non-inclusion Proofs

> Non-inclusion proves absence only at the authenticated snapshot returned by verification. It does not prove absence now, continuous absence, or that a token can be spent. Compare the snapshot's network and root round (or its certified seal timestamp under your application's time policy) with an independently trusted requirement. The seal timestamp is a consensus-certified time, not proof of real-time freshness. Do not use unverified response metadata for this check.

`StateTransitionClient.getNonInclusionProof(stateId, options)` (optional capability `NonInclusionAggregatorClient`, `get_non_inclusion_proof.v1`) is one call, with no polling and no retry. It returns a `NonInclusionProofResponse`: either an unverified absence proof, or the service's claim that the state is included. The claim is unauthenticated; request an inclusion proof and verify it. Failures are `NonInclusionProofException`.

`NonInclusionVerifier.verify(trustBase, proof, context)` authenticates the unicity certificate before it returns anything: shape, trusted network, authority and epoch interval, quorum, the aggregation partition, the shard and partition commitments together with the optional pins of the `NonInclusionContext`, the query binding and the full state-tree relation. The result is a `NonInclusionOutcome`:

- `ABSENT_AT_SNAPSHOT`: the path proves the key absent.
- `INCLUDED_AT_SNAPSHOT`: the path proves that exact key present.
- A `NonInclusionVerificationException` means rejected; there is no snapshot metadata.

`NonInclusionSnapshot` exposes the network ID, partition ID, shard ID, root round, seal timestamp (Unix seconds), root epoch, shard round, shard epoch and the state root (null for the certified empty tree). Rounds, epochs and timestamps are unsigned 64-bit values held in `long` bits; use the `...Unsigned()` (`BigInteger`) or `...Decimal()` accessors for values that can reach 2^63. All values are read from the authenticated certificate, not from the RPC block number or the time of receipt.

There is no freshness option. Freshness is the application's decision on the returned snapshot, against a checkpoint obtained independently of the proof and the server response:

```java
// `checkpoint` is obtained independently of the proof and of the server response.
NonInclusionOutcome outcome =
    NonInclusionVerifier.verify(trustBase, proof, NonInclusionContext.unsharded());
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

`NonInclusionContext.unsharded()` expects the unsharded partition, where the certificate's shard is the empty shard. For a sharded partition, pin the served shard with `withShard`. `new NonInclusionProof(stateId, bytes)` only records which state ID a proof was requested for. It is request bookkeeping, not evidence.
