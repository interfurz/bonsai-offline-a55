package de.bonsai.offline;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class PerformanceOptionsTest {
    @Test public void defaultProfilesKeepF16AndFlashOffAndIncludeVulkanMicrobatchOne() {
        List<KernelOptimizer.Profile> profiles=KernelOptimizer.profiles(new KernelOptimizer.Profile(true,4,4,1),true);
        assertEquals(1,profiles.get(0).microBatch);
        for(KernelOptimizer.Profile p:profiles) {assertFalse(p.flashAttention);assertFalse(p.kvQ8);}
        assertTrue(profiles.stream().anyMatch(p->p.microBatch==128));
    }
    @Test public void optionalProfilesAreExplicitAndKeepBaselineCandidates() {
        List<KernelOptimizer.Profile> profiles=KernelOptimizer.profiles(new KernelOptimizer.Profile(true,4,4,1,true,true),true);
        assertTrue(profiles.stream().anyMatch(p->p.flashAttention&&p.kvQ8));
        assertTrue(profiles.stream().anyMatch(p->!p.flashAttention&&!p.kvQ8));
    }
    @Test(expected=IllegalArgumentException.class) public void q8RequiresFlash() {new KernelOptimizer.Profile(false,4,4,32,false,true);}
}
