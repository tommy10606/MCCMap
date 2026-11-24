// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit.permissions;


import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.dynmap.Log;

public class BukkitPermissions implements PermissionProvider {
    private final String name;
    public BukkitPermissions(String name) {
        this.name = name;
        Log.info("Using Bukkit permissions for Minecraft administration");
    }

    @Override
    public boolean has(CommandSender sender, String permission) {
        Player player = sender instanceof Player ? (Player) sender : null;
        return player != null
                ? player.hasPermission(name + "." + permission) || player.hasPermission(name + ".*")
                : true;
    }


    @Override
    public boolean hasOfflinePermission(String player, String perm) {
        Player p = Bukkit.getPlayerExact(player);
        if (p != null) {
            return p.hasPermission(name + "." + perm);
        }
        else {
            OfflinePlayer op = Bukkit.getOfflinePlayer(player);
            if((op != null) && op.isOp()) {
                return true;
            }
            return false;
        }
    }
}
