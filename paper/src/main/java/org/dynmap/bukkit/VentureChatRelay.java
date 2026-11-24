package org.dynmap.bukkit;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Accepted VentureChat channel messages, limited to local online players in public channels. */
final class VentureChatRelay implements AutoCloseable, Listener {
    private final Plugin provider;
    private final JavaPlugin owner;
    private boolean warned;
    private final Origins origins = new Origins();
    static final class Origins {
        private record Pending(String text,long time) {}
        private final Map<UUID,ArrayDeque<Pending>> messages = new HashMap<>();
        private static String plain(String text) {return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',text)).trim();}
        synchronized void record(UUID id,String text,long now) {
            messages.values().forEach(queue->queue.removeIf(p->now-p.time>10_000_000_000L));
            messages.values().removeIf(ArrayDeque::isEmpty);
            var queue=messages.computeIfAbsent(id,key->new ArrayDeque<>());
            if(queue.size()>=8)queue.removeFirst();queue.addLast(new Pending(plain(text),now));
        }
        synchronized boolean claim(UUID id,String text,long now) {
            var queue=messages.get(id);if(queue==null)return false;
            queue.removeIf(p->now-p.time>10_000_000_000L);
            for(var it=queue.iterator();it.hasNext();)if(it.next().text.equals(plain(text))){it.remove();if(queue.isEmpty())messages.remove(id);return true;}
            if(queue.isEmpty())messages.remove(id);return false;
        }
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void onNativeChat(AsyncPlayerChatEvent event) {
        if(active()&&event.isAsynchronous()&&event.getPlayer().isOnline())origins.record(event.getPlayer().getUniqueId(),event.getMessage(),System.nanoTime());
    }
    interface Receiver {void receive(Player player,String text);}
    @SuppressWarnings("unchecked")
    VentureChatRelay(JavaPlugin owner,Plugin provider,Collection<String> channels,Receiver receiver) throws ReflectiveOperationException {
        this.owner=owner;this.provider=provider;
        Class<? extends Event> type=(Class<? extends Event>)Class.forName("mineverse.Aust1n46.chat.api.events.VentureChatEvent",true,provider.getClass().getClassLoader()).asSubclass(Event.class);
        Bukkit.getPluginManager().registerEvent(type,this,EventPriority.MONITOR,(listener,event)->{
            try {
                Object channel=event.getClass().getMethod("getChannel").invoke(event);
                String name=(String)channel.getClass().getMethod("getName").invoke(channel);
                boolean permission=(Boolean)channel.getClass().getMethod("hasPermission").invoke(channel);
                boolean distance=(Boolean)channel.getClass().getMethod("hasDistance").invoke(channel);
                Object chatPlayer=event.getClass().getMethod("getMineverseChatPlayer").invoke(event);
                Player player=chatPlayer==null?null:(Player)chatPlayer.getClass().getMethod("getPlayer").invoke(chatPlayer);
                String username=(String)event.getClass().getMethod("getUsername").invoke(event);
                if(!accept(player,name,username,permission,distance,channels))return;
                String text=ChatColor.stripColor((String)event.getClass().getMethod("getChat").invoke(event));
                if(text==null||text.isBlank())return;
                if(!origins.claim(player.getUniqueId(),text,System.nanoTime()))return;
                Bukkit.getScheduler().runTask(owner,()->{if(active()&&player.isOnline())receiver.receive(player,text);});
                warned=false;
            }catch(ReflectiveOperationException|RuntimeException failure){
                if(!warned){owner.getLogger().warning("VentureChat relay could not read the accepted player message; skipped");warned=true;}
            }
        },owner,false);
        Bukkit.getPluginManager().registerEvents(this,owner);
    }
    static boolean accept(Player player,String channel,String username,boolean permission,boolean distance,Collection<String> allowed) {
        return player!=null&&player.isOnline()&&player.getName().equals(username)&&!permission&&!distance&&allowed.stream().anyMatch(c->c.equalsIgnoreCase(channel));
    }
    boolean active(){return provider.isEnabled()&&owner.isEnabled();}
    @Override public void close(){HandlerList.unregisterAll(this);}
}
