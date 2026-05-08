package org.unicitylabs.sdk.api.bft;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RootTrustBaseTest {

  @Test
  public void testRootTrustBaseDeserializationFromJson() {
    RootTrustBase trustBase = RootTrustBase.fromJson(
            "{\"version\":1,\"networkId\":3,\"epoch\":1,\"epochStartRound\":1,\"rootNodes\":[{\"nodeId\":\"16Uiu2HAm3PaA9z8jonZzfvuT1WJgTxCpbFkV4Wq4PSSBk7VctkmG\",\"sigKey\":\"0x03982564bf661da9c048397114fab9dcfbfedb0ad7c1b1c83e13c0f9fa633f7aa6\",\"stake\":1},{\"nodeId\":\"16Uiu2HAm8918Ds2nPiVLXg55kypyoXoiweokpQxtnguZjgxz3pNE\",\"sigKey\":\"0x039a2f7f41c5583d339f31490757152b947ccb19944634a40a16a762a32a4855d4\",\"stake\":1},{\"nodeId\":\"16Uiu2HAmEEEGyvYZno7hm2Gs8FwfPejWdpvWC3HLivQD5hXFbNUh\",\"sigKey\":\"0x038cabc84fa86076277879554c277f9a0a19955fa4c3b37871fca81d5f709777f1\",\"stake\":1},{\"nodeId\":\"16Uiu2HAmNwgru7QSsVRacGqXdtfeaf1oqtznvXA6rSzKU1822kuW\",\"sigKey\":\"0x03044c0309fd0a713440da958f8c510a40a4347aa82622481655d162c227d771e3\",\"stake\":1}],\"quorumThreshold\":3,\"stateHash\":\"\",\"changeRecordHash\":\"\",\"previousEntryHash\":\"\",\"signatures\":{\"16Uiu2HAm3PaA9z8jonZzfvuT1WJgTxCpbFkV4Wq4PSSBk7VctkmG\":\"0xfe672d56ddd60e4b028b52999b4e43bcbdac9413d9e8da6f969d46c249da8f492cd719017510af8b199b94c7605b79707da56950a4888320f8cf7e07329e92da01\",\"16Uiu2HAm8918Ds2nPiVLXg55kypyoXoiweokpQxtnguZjgxz3pNE\":\"0x8d1b178f6617a6aff9e9d4a71febac6837bd2a5088f3e3b81c766065e6c7a7ad718d1e0a1c7f7e12954514e663b888337cbaa6e7c8bd5e721f4ae5520ca6f09e00\",\"16Uiu2HAmEEEGyvYZno7hm2Gs8FwfPejWdpvWC3HLivQD5hXFbNUh\":\"0x28ef1e0279fb2962149011177c173aabb7e1fad102f07c898b9de4fe71b424390dd0cbc59f75453a7573c4853218eab800431e42fd0d4a6000ef73d50170d03101\",\"16Uiu2HAmNwgru7QSsVRacGqXdtfeaf1oqtznvXA6rSzKU1822kuW\":\"0xf563d04beb3eb5bd5967cb53e6cc1d1b331cd37c03d7a34ba7f11bc0d2c4994818168e1f5caf88956b34384dd3d3685c432d7a487b5c0bee2da012fd70891bab01\"}}"
    );

    Assertions.assertEquals(1, trustBase.getVersion());
    Assertions.assertEquals(3, trustBase.getNetworkId());
    Assertions.assertEquals(1, trustBase.getEpoch());
    Assertions.assertEquals(1, trustBase.getEpochStartRound());
    Assertions.assertEquals(4, trustBase.getRootNodes().size());
    Assertions.assertEquals(3, trustBase.getQuorumThreshold());
    Assertions.assertEquals(0, trustBase.getStateHash().length);
    Assertions.assertEquals(0, trustBase.getChangeRecordHash().length);
    Assertions.assertEquals(0, trustBase.getPreviousEntryHash().length);
    Assertions.assertEquals(4, trustBase.getSignatures().size());
  }

}
