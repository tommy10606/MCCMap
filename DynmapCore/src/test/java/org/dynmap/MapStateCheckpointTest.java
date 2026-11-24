package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
class MapStateCheckpointTest {
 @Test void takingARecoverySnapshotDoesNotMakeDelayedUpdatesImmediatelyEligible(){
  var state=new MapTypeState(mock(DynmapWorld.class),mock(MapType.class));state.invalidateTile(3,-4);
  assertFalse(state.isInvalidTile(3,-4));var saved=state.save();assertFalse(saved.isEmpty());assertFalse(state.isInvalidTile(3,-4));
  var restored=new MapTypeState(mock(DynmapWorld.class),mock(MapType.class));restored.restore(saved);assertTrue(restored.isInvalidTile(3,-4));
 }
}
