@multi-aggregator
Feature: Multiple Aggregator Operations

  @shard-routing
  Scenario: Submit commitments to correct shard aggregators
    Given the aggregator URLs are configured with shard_id_length 1
    And trust-base.json is set
    And the aggregator clients are initialized
    And I configure 10 threads with 10 commitments each
    When I submit all mint commitments to correct shards concurrently
    Then all shard mint commitments should receive inclusion proofs from all aggregators within 300 seconds
    And I should see performance metrics for each aggregator

  @performance @ignore
  Scenario: Submit commitments to multiple aggregators concurrently
    Given the aggregator URLs are configured
      | http://localhost:3000 |
      | http://localhost:3001 |
      | http://localhost:3002 |
      | http://localhost:3003 |
      | http://localhost:3004 |
      | http://localhost:3005 |
    And trust-base.json is set
    And the aggregator clients are initialized
    And I configure 1 threads with 10 commitments each
    When I submit all mint commitments concurrently to all aggregators
    Then all mint commitments should receive inclusion proofs from all aggregators within 1380 seconds
    And I should see performance metrics for each aggregator

  @performance @ignore
  Scenario: Submit conflicting commitments to multiple aggregators concurrently
    Given the aggregator URLs are configured
      | http://localhost:3000 |
      | http://localhost:3001 |
      | http://localhost:3002 |
      | http://localhost:3003 |
      | http://localhost:3004 |
      | http://localhost:3005 |
    And trust-base.json is set
    And the aggregator clients are initialized
    And I configure 100 threads with 100 commitments each
    When I submit conflicting mint commitments concurrently to all aggregators
    Then all mint commitments should receive inclusion proofs from all aggregators within 30 seconds
    And I should see performance metrics for each aggregator

  @performance @ignore
  Scenario: Submit mixed valid and conflicting commitments concurrently
    Given the aggregator URLs are configured
      | http://localhost:3000 |
      | http://localhost:3001 |
      | http://localhost:3002 |
      | http://localhost:3003 |
      | http://localhost:3004 |
      | http://localhost:3005 |
    And trust-base.json is set
    And the aggregator clients are initialized
    And I configure 2 threads with 1 commitments each
    When I submit mixed valid and conflicting commitments concurrently to all aggregators
    Then all mint commitments should receive inclusion proofs from all aggregators within 30 seconds
    And I should see performance metrics for each aggregator

  @performance @ignore
  Scenario Outline: Concurrent token minting stress test
    Given the aggregator URL is configured
    And trust-base.json is set
    And the state transition client is initialized
    And <threadCount> concurrent workers with <iterations> iterations each
    When all workers execute mint-and-verify flow concurrently
    Then performance statistics should be printed

    Examples:
      | threadCount | iterations |
      | 100         | 10         |
