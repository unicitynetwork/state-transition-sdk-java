package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;

/** Builds signed synthetic trust-base chains with known test keys. Test keys only. */
final class TrustBaseTestChain {

  /** A committee with known secrets. */
  static final class Committee {

    final Map<String, byte[]> secrets = new LinkedHashMap<>();
    final List<TrustBaseMember> members = new ArrayList<>();

    /** Members "m0000".."m(n-1)" with the given weight each, keys from {@code salt}. */
    Committee(int count, long weight, int salt) {
      for (int i = 0; i < count; i++) {
        String id = String.format("m%04d", i);
        byte[] secret = secret(salt, i);
        secrets.put(id, secret);
        members.add(new TrustBaseMember(id, new SigningService(secret).getPublicKey(), weight));
      }
    }

    long totalWeight() {
      long total = 0;
      for (TrustBaseMember member : members) {
        total += member.getWeight();
      }
      return total;
    }

    long threshold() {
      return totalWeight() * 2 / 3 + 1;
    }
  }

  private TrustBaseTestChain() {
  }

  static byte[] secret(int salt, int i) {
    byte[] secret = new byte[32];
    secret[28] = (byte) salt;
    secret[30] = (byte) ((i + 1) >> 8);
    secret[31] = (byte) (i + 1);
    return secret;
  }

  static TrustBaseEntry genesis(int network, long epoch, long startRound, Committee committee) {
    return new TrustBaseEntry(network, epoch, startRound, committee.members, committee.threshold(),
        null, null, new byte[32], null);
  }

  static byte[] sign(byte[] secret, byte[] data) {
    DataHash hash = new DataHasher(HashAlgorithm.SHA256).update(data).digest();
    return new SigningService(secret).sign(hash).encode();
  }

  /** A successor of {@code previous} signed by the named members of {@code signing}. */
  static TrustBaseEntry successor(TrustBaseEntry previous, Committee signing, Committee next,
      long startRound, Collection<String> signers) {
    return successor(previous, signing, next.members, next.threshold(), startRound, signers);
  }

  static TrustBaseEntry successor(TrustBaseEntry previous, Committee signing,
      List<TrustBaseMember> members, long threshold, long startRound, Collection<String> signers) {
    Map<String, byte[]> placeholder = Map.of("x", new byte[65]);
    TrustBaseEntry unsigned = new TrustBaseEntry(previous.getNetworkId().getId(),
        previous.getEpoch() + 1, startRound, members, threshold, null, null, previous.entryId(),
        placeholder);
    byte[] data = unsigned.sigBytes();
    Map<String, byte[]> signatures = new LinkedHashMap<>();
    for (String signer : signers) {
      signatures.put(signer, sign(signing.secrets.get(signer), data));
    }
    return new TrustBaseEntry(previous.getNetworkId().getId(), previous.getEpoch() + 1, startRound,
        members, threshold, null, null, previous.entryId(), signatures);
  }

  static List<String> firstN(Committee committee, int n) {
    return new ArrayList<>(committee.secrets.keySet()).subList(0, n);
  }
}
