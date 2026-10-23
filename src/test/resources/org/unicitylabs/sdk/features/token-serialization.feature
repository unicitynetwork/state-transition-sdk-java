@cbor
Feature: Token Serialization

  Background:
    Given a mock aggregator client is set up
    And Alice has a minted token

  Scenario: Token can be exported and imported via CBOR
    When the token is exported to CBOR
    And the CBOR data is imported back to a token
    Then the imported token has the same ID as the original
    And the imported token has the same type as the original

  Scenario: Imported token passes verification
    When the token is exported to CBOR
    And the CBOR data is imported back to a token
    Then the imported token passes verification
