// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit.helper;

import java.util.HashMap;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.dynmap.DynmapChunk;
import org.dynmap.common.chunk.GenericChunkCache;
import org.dynmap.renderer.DynmapBlockState;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.Polygon;

/** Paper 26.3 adapter. Modified from Dynmap's version-specific helpers. */
public final class BukkitVersionHelper {
    public static BukkitVersionHelper helper;
    public static GenericChunkCache gencache;

    private Registry<Biome> biomes() {
        return MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
    }
    public Object[] getBiomeBaseList() { return biomes().stream().toArray(); }
    public String[] getBiomeNames() { return biomes().keySet().stream().map(Object::toString).toArray(String[]::new); }
    public String getBiomeBaseResourceLocsation(Object biome) { return biomes().getKey((Biome) biome).toString(); }
    public String getBiomeBaseIDString(Object biome) { return biomes().getKey((Biome) biome).getPath(); }
    public float getBiomeBaseTemperature(Object biome) { return ((Biome) biome).getBaseTemperature(); }
    public float getBiomeBaseHumidity(Object biome) { return ((Biome) biome).climateSettings.downfall(); }
    public int getBiomeBaseWaterMult(Object biome) { return ((Biome) biome).getWaterColor(); }
    public String[] getBlockNames() { return BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString).toArray(String[]::new); }

    public void initializeBlockStates() {
        var baseStates = new HashMap<String, DynmapBlockState>();
        var builder = new DynmapBlockState.Builder();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            DynmapBlockState base = baseStates.get(name);
            String text = state.toString();
            int bracket = text.indexOf('[');
            String properties = bracket < 0 ? "" : text.substring(bracket + 1, text.lastIndexOf(']'));
            builder.setBaseState(base).setStateIndex(base == null ? 0 : base.getStateCount())
                .setBlockName(name).setStateName(properties).setAttenuatesLight(state.getLightDampening());
            if (state.isSolid()) builder.setSolid();
            if (state.isAir()) builder.setAir();
            if (state.is(BlockTags.LOGS)) builder.setLog();
            if (state.is(BlockTags.LEAVES)) builder.setLeaves();
            if (!state.getFluidState().isEmpty() && !(state.getBlock() instanceof LiquidBlock)) builder.setWaterlogged();
            DynmapBlockState mapped = builder.build();
            if (state == state.getBlock().defaultBlockState()) mapped.setAsDefaultState();
            baseStates.putIfAbsent(name, mapped);
        }
    }
    public MapChunkCache getChunkCache(BukkitWorld world, List<DynmapChunk> chunks) {
        var cache = new PaperMapChunkCache(gencache);
        cache.setChunks(world, chunks);
        return cache;
    }
    public Player[] getOnlinePlayers() { return Bukkit.getOnlinePlayers().toArray(Player[]::new); }
    public double getHealth(Player player) { return player.getHealth(); }
    public int getWorldMinY(World world) { return world.getMinHeight(); }
    public String getSkinURL(Player player) {
        var skin = player.getPlayerProfile().getTextures().getSkin();
        return skin == null ? null : skin.toString();
    }
    public void sendTitleText(Player player, String title, String subtitle, int in, int stay, int out) {
        if (player != null) player.sendTitle(title, subtitle, in, stay, out);
    }
    public Polygon getWorldBorder(World world) {
        if (world == null) return null;
        try { return WorldBorderGeometry.from(world.getWorldBorder()); }
        catch (LinkageError | RuntimeException unavailable) { return null; }
    }
}
