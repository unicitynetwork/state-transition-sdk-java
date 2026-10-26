# Provenance

`vectors.json` is vendored byte-identical (sha256 `14b5f068d744ed2ccfdcb96662f8a169b384e8cf0d7040ce5d14bfc60f0516cd`)
from `unicitynetwork/state-transition-sdk-js` `main`, path `tests/fixtures/signature-recovery/v1/vectors.json`
(JS commit 8758113). It is the same file as `tests/vectors/o3-signature-recovery/v1/vectors.json` in
`unicitynetwork/state-transition-sdk-rust` `main`, which originates from `unicitynetwork/bft-go-base`
`uc-consolidation` commit `eeedaa7` (`testdata/signature-recovery/v1/vectors.json`).

Do not edit. Re-vendor (and update this file) when the corpus is extended. Rule: 65-byte `r||s||v`, `1 <= r < n`,
`1 <= s <= n/2`, `v` in 0..3, and the key recovered with `v` must equal the trusted key.
