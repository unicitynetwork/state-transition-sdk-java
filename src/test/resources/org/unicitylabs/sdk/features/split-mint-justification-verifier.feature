Feature: SplitMintJustificationVerifier rejects malformed split mints

  # Java SDK: SplitMintJustificationVerifier guards the split-mint flow with
  # 12 distinct FAIL branches. Five are reachable via field-level mutation from
  # outside the SDK (the ones below). The remaining seven require surgical
  # CBOR/path bit-flipping and are covered in unit tests.

  Background:
    Given a mock aggregator is running
    And Alice has split-minted 2 tokens with 2 payment assets

  Scenario Outline: Verifier rejects a split mint when <mutation>
    Given a CertifiedMintTransaction is mutated by <mutation>
    When SplitMintJustificationVerifier.verify is invoked
    Then the SplitMintJustificationVerifier verification result is FAIL
    And the SplitMintJustificationVerifier failure message contains "<expectedSubstring>"

    Examples:
      | mutation                                              | expectedSubstring                                  |
      | stripping the justification field                     | Transaction has no justification                   |
      | stripping the data field                              | Assets data is missing                             |
      | adding an extra asset to data not present in proofs   | Total amount of assets differ in token and proofs  |
      | renaming one proof's assetId to one not in data       | not found in asset data                            |
      | mismatching one asset's value between data and tree   | does not match asset tree leaf                     |
