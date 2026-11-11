@token-transfer
Feature: Token Transfer Operations
  As a developer using the Unicity SDK
  I want to perform token operations including minting, transfers, and splits
  So that I can manage token lifecycle effectively

  Background:
    Given the aggregator URL is configured
    And the trust base is set
    And the state transition client is initialized
    And the following users are set up with their signing services
      | name  |
      | Alice |
      | Bob   |
      | Carol |

  Scenario: Complete token transfer flow from Alice to Bob to Carol
    Given "Alice" mints a token with random coin data
    And user "Bob" create a nametag token with custom data "Bob Custom data"
    When "Alice" transfers the token to "Bob" using a proxy address
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    And all "Bob" nametag tokens should remain valid
    And the token should maintain its original ID and type
    When "Bob" transfers the token to "Carol" using an unmasked predicate
    And "Carol" finalizes all received tokens
    Then "Carol" should own the token successfully
    And the token should have 2 transactions in its history

  Scenario Outline: Token minting with different configurations
    Given user "<user>" with nonce of <nonceLength> bytes
    When the user mints a token of type "<tokenType>" with coin data containing <coinCount> coins
    Then the token should be minted successfully
    And the token should be verified successfully
    And the token should belong to the user

    Examples:
      | user  | nonceLength | tokenType | coinCount |
      | Alice | 32         | Standard  | 2         |
      | Bob   | 24         | Premium   | 3         |
      | Carol | 16         | Basic     | 1         |

  Scenario Outline: Name tag token creation and usage
    Given "Carol" mints a token with random coin data
    And user "<user>" create a nametag token with custom data "<nametagData>"
    Then the name tag token should be created successfully
    And the name tag should be usable for proxy addressing

    Examples:
      | user  | nametagData     |
      | Bob   | Bob's Address   |
      | Alice | Alice's Tag     |