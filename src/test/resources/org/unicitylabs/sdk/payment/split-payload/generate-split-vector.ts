import { writeFileSync } from 'node:fs';

import { TestPaymentData } from './TestPaymentData.js';
import { NetworkId } from '../../../src/api/NetworkId.js';
import { SigningService } from '../../../src/crypto/secp256k1/SigningService.js';
import { Asset } from '../../../src/payment/asset/Asset.js';
import { AssetId } from '../../../src/payment/asset/AssetId.js';
import { PaymentAssetCollection } from '../../../src/payment/asset/PaymentAssetCollection.js';
import { IPaymentData } from '../../../src/payment/IPaymentData.js';
import { SplitMintJustification } from '../../../src/payment/SplitMintJustification.js';
import { SplitTokenRequest } from '../../../src/payment/SplitTokenRequest.js';
import { TokenSplit } from '../../../src/payment/TokenSplit.js';
import { SignaturePredicate } from '../../../src/predicate/builtin/SignaturePredicate.js';
import { EncodedPredicate } from '../../../src/predicate/EncodedPredicate.js';
import { PredicateVerifierService } from '../../../src/predicate/verification/PredicateVerifierService.js';
import { CborDeserializer } from '../../../src/serialization/cbor/CborDeserializer.js';
import { CborSerializer } from '../../../src/serialization/cbor/CborSerializer.js';
import { StateTransitionClient } from '../../../src/StateTransitionClient.js';
import { StateMask } from '../../../src/transaction/StateMask.js';
import { TokenId } from '../../../src/transaction/TokenId.js';
import { TokenSalt } from '../../../src/transaction/TokenSalt.js';
import { TokenType } from '../../../src/transaction/TokenType.js';
import { MintJustificationVerifierService } from '../../../src/transaction/verification/MintJustificationVerifierService.js';
import { TokenIssuanceVerifierService } from '../../../src/transaction/verification/TokenIssuanceVerifierService.js';
import { VerificationContext } from '../../../src/transaction/verification/VerificationContext.js';
import { HexConverter } from '../../../src/util/HexConverter.js';
import { mintToken } from '../../utils/TokenUtils.js';
import { createUnicityCertificateVerifier } from '../../utils/UnicityCertificateVerifierFixture.js';
import { TestAggregatorClient } from '../TestAggregatorClient.js';

const OUT = process.env.RUST_SPLIT_VECTOR_OUT;
const WRAP_TAG = 39050n;

/** Application envelope tag(39050, [1, assets, null]); defined here, not in the SDK. */
class WrappedPaymentData implements IPaymentData {
  public constructor(public readonly assets: PaymentAssetCollection) {}

  public static decode(bytes: Uint8Array): Promise<WrappedPaymentData> {
    const { tag, data } = CborDeserializer.decodeTag(bytes);
    if (tag !== WRAP_TAG) {
      throw new Error('bad tag');
    }
    const fields = CborDeserializer.decodeArray(data, 3);
    if (CborDeserializer.decodeUnsignedInteger(fields[0]) !== 1n) {
      throw new Error('bad version');
    }
    return Promise.resolve(new WrappedPaymentData(PaymentAssetCollection.fromCBOR(fields[1])));
  }

  public encode(): Promise<Uint8Array> {
    return Promise.resolve(
      CborSerializer.encodeTag(
        WRAP_TAG,
        CborSerializer.encodeArray(
          CborSerializer.encodeUnsignedInteger(1),
          this.assets.toCBOR(),
          CborSerializer.encodeNull(),
        ),
      ),
    );
  }
}

function key(n: number): Uint8Array {
  const bytes = new Uint8Array(32);
  bytes[31] = n;
  return bytes;
}
function filled(n: number, len = 32): Uint8Array {
  return new Uint8Array(len).fill(n);
}
const hex = HexConverter.encode;

describe('Generate Rust split cross-SDK vector', () => {
  it('writes wrapped and bare split vectors', async () => {
    const aggregator = TestAggregatorClient.create(key(9));
    const client = new StateTransitionClient(aggregator);
    const ctx = new VerificationContext(
      aggregator.rootTrustBase,
      PredicateVerifierService.create(),
      createUnicityCertificateVerifier(),
      new MintJustificationVerifierService(),
      new TokenIssuanceVerifierService(false),
    );
    const owner = new SigningService(key(1));
    const tokenType = new TokenType(filled(0xc0));
    const assetA = new AssetId(filled(0xaa));
    const assetB = new AssetId(filled(0xbb));
    const burnExpiresAt = 4102444800n;
    const burnMask = filled(0x07);

    const cases = [
      {
        name: 'wrapped_10_into_3_7',
        wrapped: true,
        source: [new Asset(assetA, 10n)],
        outputs: [
          { recipientKey: 2, salt: 0x10, assets: [new Asset(assetA, 3n)] },
          { recipientKey: 3, salt: 0x20, assets: [new Asset(assetA, 7n)] },
        ],
      },
      {
        name: 'bare_two_assets',
        wrapped: false,
        source: [new Asset(assetA, 100n), new Asset(assetB, 50n)],
        outputs: [
          { recipientKey: 2, salt: 0x10, assets: [new Asset(assetA, 60n), new Asset(assetB, 50n)] },
          { recipientKey: 3, salt: 0x20, assets: [new Asset(assetA, 40n)] },
        ],
      },
    ];

    const result: unknown[] = [];
    for (const [i, c] of cases.entries()) {
      const mk = (assets: Asset[]): IPaymentData & { decode?: never } =>
        c.wrapped
          ? new WrappedPaymentData(PaymentAssetCollection.create(...assets))
          : new TestPaymentData(PaymentAssetCollection.create(...assets));
      const decode = c.wrapped ? WrappedPaymentData.decode : TestPaymentData.decode;

      const token = await mintToken(
        client,
        ctx,
        SignaturePredicate.create(owner.publicKey),
        await mk(c.source).encode(),
        NetworkId.LOCAL,
        tokenType,
        TokenSalt.fromBytes(filled(0x01 + i)),
        null,
        null,
      );

      const requests = c.outputs.map((o) =>
        SplitTokenRequest.create(
          SignaturePredicate.create(new SigningService(key(o.recipientKey)).publicKey),
          mk(o.assets),
          TokenSalt.fromBytes(filled(o.salt)),
        ),
      );
      const split = await TokenSplit.split(token, decode, requests, {
        burnStateMask: StateMask.fromBytes(burnMask),
        expiresAt: burnExpiresAt,
      });

      const tokens = [];
      for (const [j, out] of split.tokens.entries()) {
        const payload = await out.paymentData.encode();
        const tokenId = await TokenId.fromSalt(token.networkId, out.salt);
        const commitment = await SplitMintJustification.calculateLeafData(
          token,
          EncodedPredicate.fromPredicate(out.recipient),
          out.salt,
          tokenId,
          payload,
        );
        tokens.push({
          recipientKey: c.outputs[j].recipientKey,
          salt: hex(filled(c.outputs[j].salt)),
          tokenId: hex(tokenId.bytes),
          assets: out.paymentData.assets.toArray().map((a) => ({ id: hex(a.id.bytes), value: a.value.toString() })),
          payload: hex(payload),
          commitment: hex(commitment),
          proofs: out.proofs.map((p) => hex(p.toCBOR())),
        });
      }
      result.push({
        name: c.name,
        wrapped: c.wrapped,
        sourceToken: hex(token.toCBOR()),
        sourceAssets: c.source.map((a) => ({ id: hex(a.id.bytes), value: a.value.toString() })),
        burnStateMask: hex(burnMask),
        burnExpiresAt: Number(burnExpiresAt),
        burnTransaction: hex(split.burn.transaction.toCBOR()),
        manifest: hex(split.burn.transaction.data!),
        tokens,
      });
    }

    if (OUT) {
      writeFileSync(
        OUT,
        `${JSON.stringify({ __comment: 'generated by state-transition-sdk-js split vector test', cases: result }, null, 2)}\n`,
      );
    }
  }, 120000);
});
