@verification
Feature: Token Verification - Wrong Trust Base
  Verification fails against a trust base rooted in a different key.

  Background:
    Given a mock aggregator is running
    And Alice has a signing key
    And Alice has a minted token

  Scenario: Token verified against wrong trust base fails
    Then the token fails verification against a different trust base
