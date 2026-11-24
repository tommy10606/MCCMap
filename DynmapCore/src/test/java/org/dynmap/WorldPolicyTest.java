package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.dynmap.common.*;
import org.junit.jupiter.api.Test;
class WorldPolicyTest {
 @Test void disablingUnconfiguredWorldPersistsTheEntry() throws Exception {
  DynmapCore core=new DynmapCore();var node=new ConfigurationNode();node.put("worlds",new ArrayList<Map<String,Object>>());
  var field=DynmapCore.class.getDeclaredField("world_config");field.setAccessible(true);field.set(core,node);
  assertTrue(core.setWorldEnable("private",false));assertEquals(1,node.getMapList("worlds").size());assertEquals(false,node.getMapList("worlds").getFirst().get("enabled"));
 }
 @Test void newWorldOptInPolicyKeepsUnlistedWorldsOfflineAndAllowsExplicitChoices() throws Exception {
  var core=new DynmapCore(){@Override ConfigurationNode getDefaultTemplateConfigurationNode(DynmapWorld world){var t=new ConfigurationNode();t.put("enabled",true);return t;}};
  core.configuration=new ConfigurationNode();core.configuration.put("auto-enable-new-worlds",false);
  var config=new ConfigurationNode();config.put("worlds",new ArrayList<Map<String,Object>>());
  var field=DynmapCore.class.getDeclaredField("world_config");field.setAccessible(true);field.set(core,config);
  var world=mock(DynmapWorld.class);when(world.getName()).thenReturn("dungeon");when(world.getTitle()).thenReturn("Dungeon");
  assertFalse(core.getWorldConfiguration(world).getBoolean("enabled",true));
  assertTrue(core.setWorldEnable("allowed",true));when(world.getName()).thenReturn("allowed");
  assertTrue(core.getWorldConfiguration(world).getBoolean("enabled",false));
 }
 @Test void manualResumeCannotOverridePopulationLimitAndManualPauseSurvivesPlayerChanges() throws Exception {
  var core=new DynmapCore();core.mapManager=mock(MapManager.class);var server=mock(DynmapServerInterface.class);core.setServer(server);
  when(server.getOnlinePlayers()).thenReturn(new DynmapPlayer[4]);
  var field=DynmapCore.class.getDeclaredField("fullrenderplayerlimit");field.setAccessible(true);field.set(core,3);
  core.setPauseFullRadiusRenders(false);verify(core.mapManager).setPauseFullRadiusRenders(true);
  core.applyRenderingPauses(2);verify(core.mapManager).setPauseFullRadiusRenders(false);
  core.setPauseFullRadiusRenders(true);core.applyRenderingPauses(0);verify(core.mapManager,times(3)).setPauseFullRadiusRenders(true);
 }
}
