package org.dynmap.bukkit;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
class VentureChatRelayTest {
    @Test void onlyPublicLocalOnlinePlayerChannelsAreAccepted(){boolean[] online={true};var p=(Player)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()){case "isOnline"->online[0];case "getName"->"Tommy";default->null;});assertTrue(VentureChatRelay.accept(p,"Global","Tommy",false,false,List.of("Global")));assertFalse(VentureChatRelay.accept(p,"Staff","Tommy",false,false,List.of("Global")));assertFalse(VentureChatRelay.accept(p,"Global","Tommy",true,false,List.of("Global")));assertFalse(VentureChatRelay.accept(p,"Global","Tommy",false,true,List.of("Global")));assertFalse(VentureChatRelay.accept(null,"Global","Tommy",false,false,List.of("Global")));assertFalse(VentureChatRelay.accept(p,"Global","Console",false,false,List.of("Global")));online[0]=false;assertFalse(VentureChatRelay.accept(p,"Global","Tommy",false,false,List.of("Global")));}
    @Test void nativeOriginIsRequiredAndConsumedOnce(){var o=new VentureChatRelay.Origins();var id=UUID.randomUUID();assertFalse(o.claim(id,"hello",1));o.record(id,"hello",2);assertFalse(o.claim(id,"plugin broadcast",3));assertTrue(o.claim(id," \u00a7ahello",4));assertFalse(o.claim(id,"hello",5));}
    @Test void expiredMessagesAndOtherPlayersCannotAuthenticateBroadcasts(){var o=new VentureChatRelay.Origins();var id=UUID.randomUUID();o.record(id,"hello",1);assertFalse(o.claim(UUID.randomUUID(),"hello",2));assertFalse(o.claim(id,"hello",11_000_000_001L));}
    @Test void rapidMessagesAreMatchedIndependentlyAndBounded(){var o=new VentureChatRelay.Origins();var id=UUID.randomUUID();for(int i=0;i<20;i++)o.record(id,"message "+i,i);assertFalse(o.claim(id,"message 0",30));assertTrue(o.claim(id,"message 19",30));assertTrue(o.claim(id,"message 12",30));}
}
