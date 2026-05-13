@minting
Feature: Token Minting

  Background:
    Given a mock aggregator is running

  Scenario: Successfully mint a new token
    Given a user with a signing key
    When the user mints a new token
    Then the certification response status is "SUCCESS"

  Scenario: Minted token has correct properties
    Given a user with a signing key
    When the user mints a new token with specific token ID and type
    Then the token ID matches the mint parameters
    And the token type matches the mint parameters

  Scenario: Minted token passes verification
    Given a user with a signing key
    When the user mints a new token
    Then the token passes verification

  @nametag
  Scenario Outline: Mint a token addressed to Bob via <method>
    # Blocked on UnicityId in Java v2 SDK — see BDD_MIGRATION_PLAN.md Phase 8.
    Given Alice has a signing key
    And Bob has a signing key
    And Bob has registered the nametag "@bob" in domain "bdd/test"
    When Alice mints a new token addressed to Bob via <method>
    Then the certification response status is "SUCCESS"
    And the current token verifies
    And the current token can be spent by Bob

    Examples:
      | method  |
      | pubkey  |
      | nametag |
