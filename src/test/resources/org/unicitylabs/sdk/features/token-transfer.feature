@token-transfer
Feature: Token Transfer Operations
  As a developer using the Unicity SDK
  I want to perform token operations including minting, transfers, and splits
  So that I can manage token lifecycle effectively

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

  Scenario Outline: Double spend attempt
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using <transferType>
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    When "Alice" attempts to double-spend the original token to "Carol" using <transferType>
    Then the double-spend attempt should be rejected
    And "Carol" should not own any tokens
    When "Bob" transfers the token to "Dave" using <transferType>
    And "Dave" finalizes all received tokens
    Then "Dave" should own the token successfully

    Examples:
      | transferType          |
      | a proxy address       |
      | an unmasked predicate |

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

  Scenario Outline: Complete token transfer flow from Alice to Bob to Carol with multiple coins
    Given user "Alice" with nonce of 32 bytes
    When the "Alice" mints a token of type "<tokenType>" with coins data desctribed below
      | name     | symbol | id                                                               | decimals | value |
      | unicity  | UCT    | 455ad8720656b08e8dbd5bac1f3c73eeea5431565f6c1c3af742b1aa12d41d89 | 18       | 100   |
      | solana   | SOL    | dee5f8ce778562eec90e9c38a91296a023210ccc76ff4c29d527ac3eb64ade93 | 9        | 100   |
      | bitcoin  | BTC    | 86bc190fcf7b2d07c6078de93db803578760148b16d4431aa2f42a3241ff0daa | 8        | 100   |
      | ethereum | ETH    | 3c2450f2fd867e7bb60c6a69d7ad0e53ce967078c201a3ecaa6074ed4c0deafb | 18       | 100   |
      | tether   | USDT   | 40d25444648418fe7efd433e147187a3a6adf049ac62bc46038bda5b960bf690 | 6        | 100   |
      | usd-coin | USDC   | 2265121770fa6f41131dd9a6cc571e28679263d09a53eb2642e145b5b9a5b0a2 | 6        | 100   |

#    Given "Alice" mints a token with random coin data
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

    Examples:
      | tokenType                                                        |
      | f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509 |

  Scenario Outline: Complete token transfer flow to all users with splitting
    When the "Alice" mints a token of type "<tokenType>" with coins data desctribed below
      | name     | symbol | id                                                               | decimals | value |
      | unicity  | UCT    | 455ad8720656b08e8dbd5bac1f3c73eeea5431565f6c1c3af742b1aa12d41d89 | 18       | 100   |
      | solana   | SOL    | dee5f8ce778562eec90e9c38a91296a023210ccc76ff4c29d527ac3eb64ade93 | 9        | 100   |
      | bitcoin  | BTC    | 86bc190fcf7b2d07c6078de93db803578760148b16d4431aa2f42a3241ff0daa | 8        | 100   |
      | ethereum | ETH    | 3c2450f2fd867e7bb60c6a69d7ad0e53ce967078c201a3ecaa6074ed4c0deafb | 18       | 100   |
      | tether   | USDT   | 40d25444648418fe7efd433e147187a3a6adf049ac62bc46038bda5b960bf690 | 6        | 100   |
      | usd-coin | USDC   | 2265121770fa6f41131dd9a6cc571e28679263d09a53eb2642e145b5b9a5b0a2 | 6        | 100   |

    And each user have nametags prepared
    When "Alice" splits her token into halves for "Carol" as "<predicateTypeSplit>"
    And "Alice" transfers one split token to "Bob"
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    And all "Bob" nametag tokens should remain valid
    And the token should maintain its original ID and type
    When "Bob" transfers the token to "Carol" using an unmasked predicate
    And "Carol" finalizes all received tokens
    Then "Carol" should own the token successfully
    And the token should have 2 transactions in its history

    Examples:
      | tokenType                                                        | predicateTypeSplit |
      | f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509 |  Unmasked          |
#      | f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509 |  Masked          |

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

  Scenario Outline: Token transfer flow from Alice to subscription service with specific coin
    Given user "Alice" with nonce of 32 bytes
    When the "Alice" mints a token of type "<tokenType>" with coins data desctribed below
      | name    | symbol | id                                                               | decimals | value |
      | unicity | UCT    | 455ad8720656b08e8dbd5bac1f3c73eeea5431565f6c1c3af742b1aa12d41d89 | 18       | 1000  |

#    Given "Alice" mints a token with random coin data
    When "Alice" transfers the token to direct address "<directAddress>"
    Examples:
      | directAddress                                                                         |
      | DIRECT://00008f920927781ff8e20d09519dc84fe152d71f742beb6d393bf70459c2d6b014601917ce80 |