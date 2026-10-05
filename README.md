# Unicity State Transition SDK — Java

Build applications that mint, transfer, and verify digital assets on the Unicity Network. This Java SDK gives you control over tokens, ownership rules, and payments, with cryptographic verification built in.

Unicity combines private, off-chain token transactions with network-backed protection against double-spending. Token data stays with the parties you share it with, while unicity proofs let recipients verify that assets are spent only once. The network is designed to scale horizontally as demand grows.

- **Create digital assets:** mint tokens with your own types and application data payload.
- **Move value:** transfer tokens and split fungible tokens for payments.
- **Verify locally:** validate token history and unicity proofs against the compact trust base.
- **Integrate with your application:** choose ownership predicates, issuance policies, storage, and delivery channels.
- **Build for JVM and Android:** use typed SDK objects and asynchronous network operations returning `CompletableFuture`.

## Installation

Requires Java 11 or later. Android applications require Android 12+ (API level 31+) and core library desugaring.

To use this checkout, publish the SDK to your local Maven repository with the included Gradle wrapper:

```bash
git clone https://github.com/unicitynetwork/state-transition-sdk-java.git
cd state-transition-sdk-java
./gradlew publishToMavenLocal
```

Add the dependency to your application's `build.gradle`:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'org.unicitylabs:java-state-transition-sdk:3.0-SNAPSHOT'
    implementation 'com.google.guava:guava:33.0.0-jre'
}
```

Guava must be supplied by the application. For Android, use `33.0.0-android` instead of `33.0.0-jre` and enable desugaring:

```groovy
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
        coreLibraryDesugaringEnabled true
    }
}

dependencies {
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'
}
```

## Unicity Network

Mainnet is live. Start with testnet2; for production, use the mainnet gateway and matching trust base.

| Network | Gateway | Network ID | Trust base |
| --- | --- | --- | --- |
| Mainnet | `https://gateway.mainnet.unicity.network` | `1` | [JSON](https://raw.githubusercontent.com/unicitynetwork/unicity-ids/main/bft-trustbase.mainnet.json) |
| Testnet2 | `https://gateway.testnet2.unicity.network` | `4` | [JSON](https://raw.githubusercontent.com/unicitynetwork/unicity-ids/main/bft-trustbase.testnet2.json) |

Pass your gateway API key as the second argument to `JsonRpcAggregatorClient`. The public testnet2 key is `sk_ddc3cfcc001e4a28ac3fad7407f99590`. Use your own mainnet key obtained from [Sphere](https://sphere.unicity.network/) and keep it secure.

### First connection

Download the testnet2 trust base above as `bft-trustbase.testnet2.json` in your working directory. Add this class to your application and run it:

```java
import java.nio.file.Files;
import java.nio.file.Path;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;

public class Connect {
    public static void main(String[] args) throws Exception {
        var aggregator = new JsonRpcAggregatorClient(
                "https://gateway.testnet2.unicity.network",
                "sk_ddc3cfcc001e4a28ac3fad7407f99590"); // Public testnet2 key
        var client = new StateTransitionClient(aggregator);
        var trustBase = RootTrustBase.fromJson(
                Files.readString(Path.of("bft-trustbase.testnet2.json")));

        System.out.println("Network ID: " + trustBase.getNetworkId().getId());
        System.out.println("Latest round: " + aggregator.getLatestBlockNumber().get());
    }
}
```

The trust base identifies the Consensus Layer (BFT Core) instance. Pin a trusted copy in your application for production. Use `trustBase.getNetworkId()` when creating tokens so they match your gateway; testnet2 is network ID `4` (`NetworkId.TESTNET` is `2`).

The example waits for the result with `.get()`. In asynchronous applications, compose the returned futures; on Android, keep blocking calls off the UI thread.

## Work with tokens

The SDK handles cryptography and token encoding. Your application manages keys, stores tokens, and delivers them to recipients over your chosen transport.

| Task | SDK entry points | Example |
| --- | --- | --- |
| Mint a token | `MintTransaction.builder()`, `Token.mint()` | [Mint helper](./src/test/java/org/unicitylabs/sdk/utils/TokenUtils.java) |
| Transfer ownership | `TransferTransaction.create()`, `token.transfer()` | [Transfer flow](./src/test/java/org/unicitylabs/sdk/common/CommonTestFlow.java) |
| Split a fungible token | `TokenSplit.split()` | [Split flow](./src/test/java/org/unicitylabs/sdk/functional/payment/SplitBuilderTest.java) |
| Verify a received token | `token.verify(context)` | [Receive and verify](./src/test/java/org/unicitylabs/sdk/utils/TokenUtils.java) |

For minting and transfers, create the transaction, submit its `CertificationData` with `client.submitCertificationRequest()`, and check the response status. Use `InclusionProofUtils.waitInclusionProof()` to obtain the verified unicity proof, then `transaction.toCertifiedTransaction()` and `Token.mint()` or `token.transfer()` to produce the updated token. The proof type is named `InclusionProof` in the API.

These examples are test code with local network configuration. When adapting them to a public network, use the matching gateway, key, and trust base, and pass `trustBase.getNetworkId()` when minting.

### Store, send, and receive

Use `token.toCbor()` to save or send a token, and `Token.fromCbor(bytes)` to load it. Verify received tokens with your application's `VerificationContext` before accepting them:

```java
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

// Inside your receive handler; see the examples for context setup.
var token = Token.fromCbor(bytes);
var result = token.verify(context);
if (result.getStatus() != VerificationStatus.OK) {
    throw new IllegalArgumentException("Token verification failed: " + result.getStatus());
}
```

## Security

Unicity proofs provide independently verifiable evidence of certified state transitions being unique. This protects against double-spending without publishing token data to a public ledger.

Use the SDK's verification APIs with the authentic network trust base and your application's token issuance policies. A token's cryptographic validity and whether your application accepts its issuer are separate checks; register accepted token types with `TokenIssuanceVerifierService`. Keep private keys and token backups secure, and check that received tokens belong to the intended recipient.

## Development

From a repository checkout:

```bash
./gradlew build
./gradlew test
./gradlew checkstyleMain
```

Run the integration tests, including Docker-based tests:

```bash
./gradlew integrationTest
```

The [deployed-network test](./src/test/java/org/unicitylabs/sdk/e2e/TokenE2ETest.java) is enabled by `AGGREGATOR_URL`. Its current setup loads `src/test/resources/trust-base.json` and creates a client without an API key; configure both for your target network before running it.

## Resources

- [Unicity Network](https://github.com/unicitynetwork/)
- [Token examples](./src/test/java/org/unicitylabs/sdk/utils/TokenUtils.java)
- [Report an issue](https://github.com/unicitynetwork/state-transition-sdk-java/issues)

## License

[MIT](./LICENSE)
