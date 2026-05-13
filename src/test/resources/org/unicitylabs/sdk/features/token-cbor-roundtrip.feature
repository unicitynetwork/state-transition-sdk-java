@cbor
Feature: Token CBOR Roundtrip — All States
  CBOR export/import preserves token identity and passes verification.

  Background:
    Given a mock aggregator is running

  Scenario: Freshly minted token survives CBOR roundtrip
    Given Alice has a signing key
    And Alice has a minted token
    When the token is exported to CBOR
    And the CBOR data is imported back to a token
    Then the imported token has the same ID as the original
    And the imported token passes verification

  Scenario: Token after transfer survives CBOR roundtrip
    Given Alice has a signing key
    And Bob has a signing key
    And Alice has a minted token
    When Alice transfers the current token to Bob
    And the token is exported to CBOR
    And the CBOR data is imported back to a token
    Then the imported token passes verification
    And the imported token has 1 transaction in its history

  Scenario: Split child token survives CBOR roundtrip
    Given Alice has a signing key
    And Alice has a minted token with 2 payment assets worth 100 and 200
    When Alice splits the token into 2 new tokens
    And split token 1 is exported to CBOR
    And the CBOR data is imported back to a token
    Then the imported token passes verification
