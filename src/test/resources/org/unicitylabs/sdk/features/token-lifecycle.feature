@token-lifecycle
Feature: Token Lifecycle State Transitions
  As a developer using the Unicity SDK
  I want to verify that tokens follow correct state transitions
  So that burned and spent tokens cannot be reused

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

  Scenario: Burned token cannot be transferred after split
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" splits her token into halves for "Bob" as "Unmasked"
    Then "Alice" should not be able to transfer the burned token to "Carol"

  Scenario: Pre-transfer token reference cannot be reused
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    And "Alice" saves a reference to the current token
    When "Alice" transfers the token to "Bob" using a proxy address
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    When "Alice" attempts to reuse the saved token reference to transfer to "Carol"
    Then the reuse attempt should be rejected

  Scenario Outline: Token state is preserved through transfer chain
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using <transferType>
    And "Bob" finalizes all received tokens
    When "Bob" transfers the token to "Carol" using <transferType>
    And "Carol" finalizes all received tokens
    When "Carol" transfers the token to "Dave" using <transferType>
    And "Dave" finalizes all received tokens
    Then "Dave" should own the token successfully
    And the token for "Dave" should have 3 transactions in its history
    And the token for "Dave" should maintain its original ID and type from "Alice"

    Examples:
      | transferType          |
      | a proxy address       |
      | an unmasked predicate |
