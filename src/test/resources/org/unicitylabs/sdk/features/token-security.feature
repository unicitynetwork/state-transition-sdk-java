@security
Feature: Token Security
  Only the current owner can originate a transfer.

  Background:
    Given a mock aggregator is running
    And Alice has a signing key
    And Bob has a signing key
    And Alice has a minted token

  Scenario: Non-owner cannot create a transfer
    When Bob tries to create a transfer of Alice's token
    Then the transfer creation fails with a predicate mismatch error

  Scenario: Previous owner cannot reclaim transferred token
    Given Alice transfers the current token to Bob
    When Alice tries to create a transfer of the token
    Then the transfer creation fails with a predicate mismatch error
