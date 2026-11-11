# Shared trust-base V2 vectors

Both files are vendored byte for byte from `ristik/state-transition-sdk-js` (`tests/vectors/trust-base-v2/`), which vendors them from `ristik/bft-go-base`; never regenerate or edit them here.

## `vectors.json`

- source: `ristik/bft-go-base`, branch `utb/upstream-v2`, commit `c32454f`, `testdata/trust-base-v2/v1/vectors.json`
- sha256: `dfcf63a085de29be9141821c3089f0f08548314470df8e96e4be43d4da466c09`
- regenerate there with `UPDATE_VECTORS=1 go test ./types -run TestTrustBaseV2SharedVectors`.
- used by this PR: `entries` and `decode`. `bundles`, `history` and `weightedQuorum` belong to the history PR.

## `max-entry/v1/max-entry.json`

- source: `ristik/bft-go-base`, branch `utb/gettrustbase-codec`, commit `81ee667`, `testdata/trust-base-v2/max-entry/v1/max-entry.json`
- sha256: `c4c5970307c1780fa08108529e3ef9e37803726b6df1843d61c8421f97a795b6`
- epoch 1 is a genesis of 256 members with 128-byte node ids; epoch 2 carries the signatures of all 256 predecessor members, 95,365 bytes, the largest valid entry. The per-entry cap is 128 KiB. `bundle` is the two-entry release bundle of the pair (used by the history PR).
