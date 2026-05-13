@split
Feature: Token Split - Combinatorial Asset Configurations

  Background:
    Given a mock aggregator is running

  Scenario Outline: Split token with <assetCount> assets into <splitCount> parts
    Given Alice has a minted token containing <assetCount> payment assets
    When Alice splits the token into <splitCount> equal parts
    Then the split validation succeeds

    Examples:
      | assetCount | splitCount |
      | 1          | 2          |
      | 2          | 3          |
      | 3          | 2          |
      | 3          | 3          |
