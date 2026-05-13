@split
Feature: Token Split and Transfer

  Background:
    Given a mock aggregator is running
    And Alice has a minted token with 2 payment assets worth 100 and 200

  Scenario: Original token cannot be used after split burn
    Given Bob has a signing key
    When Alice splits the token into 2 new tokens
    And Alice tries to submit a transfer of the stale token to Bob
    Then the certification response status is "STATE_ID_EXISTS"

  Scenario: Split and transfer parts to different users
    Given Bob has a signing key
    And Carol has a signing key
    When Alice splits the token into 2 parts
    And Alice transfers split token 1 to Bob
    And Alice transfers split token 2 to Carol
    Then Bob's token passes verification
    And Carol's token passes verification

  Scenario: Recipient can further transfer a received split token
    Given Bob has a signing key
    And Carol has a signing key
    When Alice splits the token into 2 parts
    And Alice transfers split token 1 to Bob
    Then Bob can transfer split token 1 to Carol
    And Carol's received token passes verification

  Scenario: Double-spend of a split token is prevented
    Given Bob has a signing key
    And Carol has a signing key
    When Alice splits the token into 2 parts
    And Alice transfers split token 1 to Bob
    Then Alice cannot transfer split token 1 to Carol because it was already sent

  Scenario: Multi-level split - split a token that was already split
    Given Bob has a signing key
    When Alice splits the token into 2 parts
    And Alice transfers split token 1 to Bob
    And Bob splits his token into 2 sub-parts
    Then 2 sub-split tokens are created
    And each sub-split token passes verification

  Scenario: Multi-level split with transfer across 4 levels
    Given Bob has a signing key
    And Carol has a signing key
    And Dave has a signing key
    When Alice splits the token into 2 parts
    And Alice transfers split token 1 to Bob
    And Bob splits his token into 2 sub-parts
    And Bob transfers sub-split token 1 to Carol
    And Carol transfers sub-split token 1 to Dave
    Then Dave's token passes verification
    And Dave's token has the correct asset values

  Scenario: Double-spend after multi-level split is prevented
    Given Bob has a signing key
    And Carol has a signing key
    And Dave has a signing key
    When Alice splits the token into 2 parts
    And Alice transfers split token 1 to Bob
    And Bob splits his token into 2 sub-parts
    And Bob transfers sub-split token 1 to Carol
    Then Bob cannot transfer sub-split token 1 to Dave because it was already sent

  Scenario: Cannot spend a token after it has been split
    Given Bob has a signing key
    When Alice splits the token into 2 parts
    And Alice transfers split token 1 to Bob
    And Bob splits his token into 2 sub-parts
    Then Bob cannot transfer the pre-split token because it was burned
