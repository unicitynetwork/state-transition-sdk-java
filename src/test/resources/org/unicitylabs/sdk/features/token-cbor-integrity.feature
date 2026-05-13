@cbor
Feature: Token CBOR Import Integrity
  Corrupted CBOR input is rejected during Token.fromCbor.

  Background:
    Given a mock aggregator is running
    And Alice has a signing key
    And Alice has a minted token

  Scenario: Importing truncated CBOR data fails
    When the token is exported to CBOR
    And the CBOR data is truncated to half its length
    Then importing the corrupted CBOR data fails with an error

  Scenario: Importing random bytes as a token fails
    When random bytes are used as token CBOR data
    Then importing the corrupted CBOR data fails with an error
