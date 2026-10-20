package de.bonsai.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModelIntegrityTest {
    @Test public void testedOfficialFilesPass() {
        ModelIntegrity.verify("Ternary-Bonsai-2-4B-Q2_0.gguf",1212103520L,
            "64a5183b44921bdb5ac4a6f5f92007821d6ebe5c66997d3c147fd214ab9433d4");
        ModelIntegrity.verify("Ternary-Bonsai-2-4B-Q2_0 (1).gguf",1212103520L,ModelIntegrity.Q2_SHA);
        ModelIntegrity.verify("Ternary-Bonsai-2-4B-PQ2_0.gguf",1146465120L,
            "d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409");
    }
    @Test public void reportedBrokenCopyIsRejectedDespiteMatchingSize() {
        IllegalStateException error=assertThrows(IllegalStateException.class,()->
            ModelIntegrity.verify("Ternary-Bonsai-2-4B-Q2_0.gguf",1212103520L,
                "4e157769ecbdbdc9f555c58a0834d00bce09430a99efd53a8f3beca642042eff"));
        assertTrue(error.getMessage().contains("64a5183b"));
        assertTrue(error.getMessage().contains("4e157769"));
    }
    @Test public void incompleteOfficialFileIsRejected() {
        assertThrows(IllegalStateException.class,()->
            ModelIntegrity.verify(ModelIntegrity.Q2_NAME,1212103519L,ModelIntegrity.Q2_SHA));
    }
    @Test public void otherModelsAreNotMisidentifiedAsOfficialBonsai() {
        ModelIntegrity.verify("my-custom-model.gguf",42,"custom");
    }
}
