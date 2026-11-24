package org.dynmap.utils;
import static org.junit.Assert.*;
import org.dynmap.renderer.RenderPatchFactory.SideVisible;
import org.junit.Test;
public class ExactPatchRotationTest {
 @Test public void cardinalFacesStayExactlyOnBlockBoundaries() {
  var factory=new PatchDefinitionFactory();var original=factory.getPatch(0,0,0,1,0,0,0,1,0,0,1,0,1,SideVisible.BOTH,0);
  for(int angle:new int[]{90,180,270,360}){
   var patch=(PatchDefinition)factory.getRotatedPatch(original,0,angle,0,0);
   for(double v:new double[]{patch.x0,patch.y0,patch.z0,patch.xu,patch.yu,patch.zu,patch.xv,patch.yv,patch.zv})assertTrue(v==0||v==1);
  }
 }
 @Test public void narrowMirroredSkinRegionsUseTheirVisibleGeometryForValidation() {
  var factory=new PatchDefinitionFactory();
  var patch=factory.getModelFace(new double[]{2,10,8},new double[]{14,16,8},org.dynmap.modsupport.BlockSide.NORTH,new double[]{16,0,10,3},null,true,0);
  assertNotNull(patch);assertTrue(patch.validate());
 }
}
