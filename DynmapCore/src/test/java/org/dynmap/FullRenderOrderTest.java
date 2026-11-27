package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.dynmap.utils.TileFlags;
import org.junit.jupiter.api.Test;

class FullRenderOrderTest {
    private RenderInventory inventory(int... xs) throws Exception {
        var flags=new TileFlags();for(int x:xs)flags.setFlag(x,-7,true);
        var saved=new ConfigurationNode();saved.put("phase","done");saved.put("tiles",flags.save());saved.put("total",xs.length);saved.put("retain-seeds",true);
        return new RenderInventory(saved);
    }
    @Test void allSelectedMapsFillMissingTilesBeforeAnyPreexistingTileIsRefreshed() throws Exception {
        var order=new FullRenderOrder();var events=new ArrayList<String>();
        for(String map:List.of("flat","surface")) {
            var inventory=inventory(-3,-2,-1,0,1,2,3);order.start(map);
            while(order.pending(map,inventory)) {
                var tile=order.next(map,inventory,(x,y)->x%2==0,2);
                if(tile!=null) {assertFalse(order.refresh());events.add(map+":missing:"+tile.x);}
            }
            var filled=new TileFlags();filled.setFlag(-3,-7,true);order.finishMissing(map,filled);
        }
        assertEquals(8,events.size());order.beginRefresh();order=new FullRenderOrder(order.save());
        for(String map:List.of("flat","surface")) {
            assertEquals(3,order.total(map));assertTrue(order.start(map));assertFalse(order.start(map));
            while(order.pending(map,inventory())) {
                var tile=order.next(map,inventory(),(x,y)->{throw new AssertionError("refresh must consume its saved bitmap");},1);
                assertEquals(0,tile.x%2);events.add(map+":old:"+tile.x);
            }
            var flags=new TileFlags();flags.load(order.filled(map));assertTrue(flags.getFlag(-3,-7));
        }
        assertEquals(14,events.size());assertTrue(events.subList(0,8).stream().allMatch(s->s.contains(":missing:")));assertTrue(events.subList(8,14).stream().allMatch(s->s.contains(":old:")));
    }
    @Test void restartKeepsTheMissingScanAndDoesNotRefreshTilesCreatedInThatPass() throws Exception {
        var inventory=inventory(-100,-1,0,1,100);var order=new FullRenderOrder();order.start("flat");
        var actual=new HashSet<Integer>(Set.of(-1,0,1));var missing=new HashSet<Integer>();
        while(order.pending("flat",inventory)) {
            var tile=order.next("flat",inventory,(x,y)->actual.contains(x),1);
            if(tile!=null){missing.add(tile.x);actual.add(tile.x);}
            inventory=new RenderInventory(inventory.save());order=new FullRenderOrder(order.save());
        }
        assertEquals(Set.of(-100,100),missing);assertEquals(3,order.deferredCount("flat"));
        order.beginRefresh();var refreshed=new HashSet<Integer>();
        while(order.pending("flat",inventory)) {var tile=order.next("flat",inventory,(x,y)->true,1);refreshed.add(tile.x);order=new FullRenderOrder(order.save());}
        assertEquals(Set.of(-1,0,1),refreshed);
    }
    @Test void classificationYieldsAfterItsBudgetInsteadOfLoadingAnEntireWorldQueue() throws Exception {
        var order=new FullRenderOrder();order.start("flat");var inventory=inventory(0,1,2,3,4,5);var checked=new java.util.concurrent.atomic.AtomicInteger();
        assertNull(order.next("flat",inventory,(x,y)->{checked.incrementAndGet();return true;},2));assertEquals(2,checked.get());assertTrue(order.pending("flat",inventory));assertEquals(2,order.deferredCount("flat"));
    }
    @Test void invalidPassCannotBeResumed() {var n=new ConfigurationNode();n.put("phase","bad");assertThrows(java.io.IOException.class,()->new FullRenderOrder(n));}
}
