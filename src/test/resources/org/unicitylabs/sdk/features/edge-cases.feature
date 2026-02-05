@edge-cases
Feature: Edge Cases and Serialization
  As a developer using the Unicity SDK
  I want to verify edge cases in token operations
  So that tokens remain valid under unusual conditions

  Background:
    Given the aggregator URL is configured
    And trust-base.json is set
    And the state transition client is initialized
    And the following users are set up with their signing services
      | name  |
      | Alice |
      | Bob   |
      | Carol |
      | Dave  |

  Scenario: CBOR serialization round-trip preserves minted token
    Given "Alice" mints a token with random coin data
    When the token for "Alice" is exported to CBOR and imported back
    Then the imported token should have the same ID as the original
    And the imported token should have the same type as the original
    And the imported token should pass verification

  Scenario Outline: CBOR round-trip after transfer via <transferType>
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using <transferType>
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    When the token for "Bob" is exported to CBOR and imported back
    Then the imported token should have the same ID as the original
    And the imported token should pass verification
    And the imported token should have 1 transactions in its history

    Examples:
      | transferType          |
      | a proxy address       |
      | an unmasked predicate |

  Scenario: CBOR round-trip after multi-hop transfer
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using a proxy address
    And "Bob" finalizes all received tokens
    When "Bob" transfers the token to "Carol" using an unmasked predicate
    And "Carol" finalizes all received tokens
    When the token for "Carol" is exported to CBOR and imported back
    Then the imported token should have the same ID as the original
    And the imported token should pass verification
    And the imported token should have 2 transactions in its history

  Scenario Outline: Long transfer chain preserves token integrity via <transferType>
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using <transferType>
    And "Bob" finalizes all received tokens
    When "Bob" transfers the token to "Carol" using <transferType>
    And "Carol" finalizes all received tokens
    When "Carol" transfers the token to "Dave" using <transferType>
    And "Dave" finalizes all received tokens
    When "Dave" transfers the token to "Alice" using <transferType>
    And "Alice" finalizes all received tokens
    Then "Alice" should own the token successfully
    And the token for "Alice" should have 4 transactions in its history
    And the token for "Alice" should maintain its original ID and type from "Alice"

    Examples:
      | transferType          |
      | a proxy address       |
      | an unmasked predicate |
