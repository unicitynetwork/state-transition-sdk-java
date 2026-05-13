@transaction-data
Feature: Transaction Data Boundaries

  Background:
    Given a mock aggregator client is set up

  Scenario: Minting with empty transaction data succeeds
    Given a user with a signing key
    When the user mints a token with empty transaction data
    Then the certification response status is "SUCCESS"

  Scenario: Minting with large transaction data succeeds
    Given a user with a signing key
    When the user mints a token with 10KB of random transaction data
    Then the certification response status is "SUCCESS"

  Scenario: Transfer with large transaction data succeeds
    Given Alice has a signing key
    And Bob is a registered user
    And Alice has a minted token
    When Alice transfers the token to Bob with 10KB of random data
    Then the transferred token passes verification
