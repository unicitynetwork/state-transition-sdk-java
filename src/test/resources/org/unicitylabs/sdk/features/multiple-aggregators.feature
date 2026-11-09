Feature: Bulk Commitment Testing with Multiple Aggregators

  Scenario: Submit commitments to multiple aggregators concurrently
    Given the aggregator URLs are configured
    And the trust base is set
    And the aggregator clients are initialized
    And I configure 10 threads with 10 commitments each
    When I submit all mint commitments concurrently to all aggregators
    Then all mint commitments should receive inclusion proofs from all aggregators within 30 seconds
    And I should see performance metrics for each aggregator