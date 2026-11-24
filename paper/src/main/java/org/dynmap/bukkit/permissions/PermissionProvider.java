// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit.permissions;


import org.bukkit.command.CommandSender;

public interface PermissionProvider {
    boolean has(CommandSender sender, String permission);


    boolean hasOfflinePermission(String player, String perm);

}
