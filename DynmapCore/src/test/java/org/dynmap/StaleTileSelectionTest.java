package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.dynmap.hdmap.HDMapTile;
import org.dynmap.hdmap.HDPerspective;
import org.junit.jupiter.api.Test;

class StaleTileSelectionTest {
    @Test void retainsOnlyTheOldestBoundedBatchInAgeOrder() {
        var selection=new StaleTileRefresh.Selection(3);
        var world=mock(DynmapWorld.class);var perspective=mock(HDPerspective.class);
        for(int i=100;i>=1;i--)selection.offer(new StaleTileRefresh.Candidate(null,new HDMapTile(world,perspective,i,0,0,0),i));
        assertEquals(List.of(1L,2L,3L),selection.results().stream().map(StaleTileRefresh.Candidate::timestamp).toList());
    }
    @Test void sharedMapVariantsCoalesceButDistinctPerspectivesRemainIndependent() {
        var selection=new StaleTileRefresh.Selection(3);
        var world=mock(DynmapWorld.class);var perspective=mock(HDPerspective.class);
        var tile=new HDMapTile(world,perspective,0,0,0,0);
        selection.offer(new StaleTileRefresh.Candidate(null,tile,100));
        selection.offer(new StaleTileRefresh.Candidate(null,new HDMapTile(world,perspective,0,0,0,0),50));
        selection.offer(new StaleTileRefresh.Candidate(null,tile,200));
        selection.offer(new StaleTileRefresh.Candidate(null,new HDMapTile(world,mock(HDPerspective.class),0,0,0,0),75));
        assertEquals(List.of(50L,75L),selection.results().stream().map(StaleTileRefresh.Candidate::timestamp).toList());
    }
}
