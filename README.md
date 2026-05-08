# Unicity Java State Transition SDK

A Java SDK for interacting with the Unicity network, enabling state transitions and token operations
with cross-platform support for JVM and Android 12+.

## Features

- **Token Operations**: Mint, transfer, and manage fungible tokens
- **State Transitions**: Submit and verify state transitions on the Unicity network
- **Cross-Platform**: Works on standard JVM and Android 12+ (API level 31+)
- **Type-Safe**: Strongly typed API with comprehensive error handling
- **CBOR Support**: Built-in CBOR encoding/decoding using Jackson
- **Async Operations**: All network operations return `CompletableFuture` for non-blocking execution

## Requirements

- Java 11 or higher
- Android 12+ (API level 31+) for Android platform
- Gradle 8.8 or higher

## Installation

### Using JitPack

Add JitPack repository:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
```

#### For Android Projects:

```groovy
dependencies {
    implementation 'com.github.unicitynetwork:java-state-transition-sdk:1.1:android'
}
```

#### For JVM Projects:

```groovy
dependencies {
    implementation 'com.github.unicitynetwork:java-state-transition-sdk:1.1:jvm'
}
```

### Using Local Maven

```groovy
dependencies {
    implementation 'org.unicitylabs:java-state-transition-sdk:1.1-SNAPSHOT'
}
```

## Quick Start

## Building from Source

### Clone the Repository

```bash
git clone https://github.com/unicitynetwork/java-state-transition-sdk.git
cd java-state-transition-sdk
```

### Build the Project

```bash
./gradlew build
```

### Run Tests

```bash
# Run unit tests
./gradlew test

# Run integration tests (requires Docker)
./gradlew integrationTest

# Run E2E tests against deployed aggregator
AGGREGATOR_URL=https://gateway-test.unicity.network ./gradlew integrationTest
```

## Platform-Specific Considerations

### Android Compatibility

The SDK is compatible with Android 12+ (API level 31+). It uses:

- OkHttp for HTTP operations instead of Java 11's HttpClient
- Android-compatible Guava version
- Animal Sniffer plugin to ensure API compatibility

### JVM Compatibility

The standard JVM version uses:

- Java 11 APIs
- Full Guava JRE version
- All Java 11 features

## Architecture

The SDK follows a modular architecture under `org.unicitylabs.sdk`:

- **api**: Core API interfaces and aggregator client
- **address**: Address schemes and implementations (DirectAddress, ProxyAddress)
- **bft**: BFT layer data, trustbase, certificates, seals
- **hash**: Cryptographic hashing (SHA256, SHA224, SHA384, SHA512, RIPEMD160)
- **jsonrpc**: JSON-RPC transport layer
- **`mtree`**: Merkle tree implementations
    - `plain`: Sparse Merkle Tree (SMT)
    - `sum`: Sparse Merkle Sum Tree (SMST)
- **predicate**: Ownership predicates
- **serializer**: CBOR/JSON serializer utilities
- **signing**: Digital signature support (ECDSA secp256k1)
- **token**: Token types
    - `fungible`: Fungible token support with CoinId and TokenCoinData
      **`transaction`**: Transaction types and builders
    - `split`: Token splitting functionality with TokenSplitBuilder
- **util**: Utilities
- **verification**: Verification rules

All certificate and transaction verification is handled internally by the SDK, requiring only the
trustbase as input from the user.

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Guidelines

- Follow Java naming conventions
- Add unit tests for new features
- Update documentation as needed
- Ensure compatibility with both JVM and Android platforms
- Run `./gradlew build` before submitting PR

## Testing

The SDK includes comprehensive test suites:

### Unit Tests

Located in `src/test/java`, these test individual components in isolation.

### Integration Tests

Located in `src/test/java/org/unicitylabs/sdk/`:

- `integration/TokenIntegrationTest`: Tests against Docker-based local aggregator
- `e2e/TokenE2ETest`: E2E tests using CommonTestFlow (requires `AGGREGATOR_URL` env var)
- `e2e/BasicE2ETest`: Basic connectivity and performance tests

### Running Tests

```bash
# All tests
./gradlew test

# Integration tests only
./gradlew integrationTest

# Specific test class
./gradlew test --tests "org.unicitylabs.sdk.api.StateIdTest"
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

For issues and feature requests, please use
the [GitHub issue tracker](https://github.com/unicitynetwork/java-state-transition-sdk/issues).

For questions about the Unicity Labs, visit [unicity-labs.com](https://unicity-labs.com).

## Acknowledgments

- Built on the Unicity network protocol
- Uses Jackson for CBOR encoding
- Uses Bouncy Castle for cryptographic operations
- Uses OkHttp for Android-compatible HTTP operations