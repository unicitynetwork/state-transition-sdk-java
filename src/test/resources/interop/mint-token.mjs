// Mint a token and transfer it once, using the PUBLISHED TypeScript SDK (4.0.0 API: verification is
// anchored in a trust base history) against the aggregator the Java integration suite started,
// then print the token as hex.
//
// This runs inside a node container, against the npm artifact rather than the TypeScript repo's
// source tree — the same bytes a consumer installs. TRUST_BASE_PATH is the bundle (tag 39015,
// genesis to tip) the aggregator serves; as for any local network, its genesis entry is the pin. The token comes back over stdout so nothing
// has to be bind-mounted or copied out.
import { readFileSync } from 'node:fs';

import { AggregatorClient } from '@unicitylabs/state-transition-sdk/lib/api/AggregatorClient.js';
import { UnicitySealQuorumSignaturesVerificationRule } from '@unicitylabs/state-transition-sdk/lib/api/bft/verification/rule/UnicitySealQuorumSignaturesVerificationRule.js';
import { UnicityCertificateVerifier } from '@unicitylabs/state-transition-sdk/lib/api/bft/verification/UnicityCertificateVerifier.js';
import { VerifiedSealCache } from '@unicitylabs/state-transition-sdk/lib/api/bft/verification/VerifiedSealCache.js';
import { TrustBaseBundle } from '@unicitylabs/state-transition-sdk/lib/api/bft/trustbase/TrustBaseBundle.js';
import { TrustBaseHistory } from '@unicitylabs/state-transition-sdk/lib/api/bft/trustbase/TrustBaseHistory.js';
import { CertificationData } from '@unicitylabs/state-transition-sdk/lib/api/CertificationData.js';
import { NetworkId } from '@unicitylabs/state-transition-sdk/lib/api/NetworkId.js';
import { Secp256k1SignatureVerifier } from '@unicitylabs/state-transition-sdk/lib/crypto/secp256k1/Secp256k1SignatureVerifier.js';
import { SigningService } from '@unicitylabs/state-transition-sdk/lib/crypto/secp256k1/SigningService.js';
import { SignaturePredicate } from '@unicitylabs/state-transition-sdk/lib/predicate/builtin/SignaturePredicate.js';
import { SignaturePredicateUnlockScript } from '@unicitylabs/state-transition-sdk/lib/predicate/builtin/SignaturePredicateUnlockScript.js';
import { PredicateVerifierService } from '@unicitylabs/state-transition-sdk/lib/predicate/verification/PredicateVerifierService.js';
import { StateTransitionClient } from '@unicitylabs/state-transition-sdk/lib/StateTransitionClient.js';
import { MintTransaction } from '@unicitylabs/state-transition-sdk/lib/transaction/MintTransaction.js';
import { StateMask } from '@unicitylabs/state-transition-sdk/lib/transaction/StateMask.js';
import { Token } from '@unicitylabs/state-transition-sdk/lib/transaction/Token.js';
import { TransferTransaction } from '@unicitylabs/state-transition-sdk/lib/transaction/TransferTransaction.js';
import { MintJustificationVerifierService } from '@unicitylabs/state-transition-sdk/lib/transaction/verification/MintJustificationVerifierService.js';
import { TokenIssuanceVerifierService } from '@unicitylabs/state-transition-sdk/lib/transaction/verification/TokenIssuanceVerifierService.js';
import { VerificationContext } from '@unicitylabs/state-transition-sdk/lib/transaction/verification/VerificationContext.js';
import { waitInclusionProof } from '@unicitylabs/state-transition-sdk/lib/util/InclusionProofUtils.js';

const aggregatorUrl = process.env.AGGREGATOR_URL;
const trustBasePath = process.env.TRUST_BASE_PATH;
if (!aggregatorUrl || !trustBasePath) {
  throw new Error('AGGREGATOR_URL and TRUST_BASE_PATH must be set');
}

const bundleBytes = new Uint8Array(readFileSync(trustBasePath));
const bundle = await TrustBaseBundle.decode(bundleBytes, { maxBytes: bundleBytes.length, maxEntries: 1024 });
const trustBase = TrustBaseHistory.create(await bundle.entries[0].entryId());
for (const entry of bundle.entries) {
  await trustBase.append(entry);
}
const aggregatorClient = new AggregatorClient(aggregatorUrl, null);
const client = new StateTransitionClient(aggregatorClient);
const predicateVerifier = PredicateVerifierService.create();
const unicityCertificateVerifier = new UnicityCertificateVerifier(
  new UnicitySealQuorumSignaturesVerificationRule(new Secp256k1SignatureVerifier(), new VerifiedSealCache(256)),
);
const context = new VerificationContext(
  trustBase,
  predicateVerifier,
  unicityCertificateVerifier,
  new MintJustificationVerifierService(),
  new TokenIssuanceVerifierService(false),
);

// An hour out, so the request cannot expire while it is in flight.
const expiresAt = BigInt(Math.floor(Date.now() / 1000)) + 3600n;
const alice = SigningService.generate();
const bob = SigningService.generate();

const submit = async (certificationData) => {
  const { status } = await client.submitCertificationRequest(certificationData);
  if (status !== 'SUCCESS') {
    throw new Error(`certification request failed: ${status}`);
  }
};

const mint = await MintTransaction.create(NetworkId.fromId(trustBase.networkId.id), SignaturePredicate.fromSigningService(alice), {
  expiresAt,
});
await submit(await CertificationData.fromMintTransaction(mint));
const minted = await Token.mint(
  await mint.toCertifiedTransaction(
    trustBase,
    predicateVerifier,
    unicityCertificateVerifier,
    await waitInclusionProof(client, trustBase, predicateVerifier, unicityCertificateVerifier, mint),
  ),
  context,
);

const transfer = await TransferTransaction.create(
  minted,
  SignaturePredicate.fromSigningService(bob),
  StateMask.generate(),
  { expiresAt },
);
await submit(await CertificationData.fromTransaction(transfer, await SignaturePredicateUnlockScript.create(transfer, alice)));
const token = await minted.transfer(
  await transfer.toCertifiedTransaction(
    trustBase,
    predicateVerifier,
    unicityCertificateVerifier,
    await waitInclusionProof(client, trustBase, predicateVerifier, unicityCertificateVerifier, transfer),
  ),
  context,
);

// Verified by the producing SDK before it leaves, so a failure on the Java side is a
// cross-implementation disagreement and not a token that was never valid.
const result = await token.verify(context);
if (result.status !== 'OK') {
  throw new Error(`the TypeScript SDK could not verify its own token: ${result.status}`);
}

const hex = Buffer.from(token.toCBOR()).toString('hex');
process.stdout.write(`TOKEN_HEX=${hex}\nEXPIRES_AT=${expiresAt}\n`);
