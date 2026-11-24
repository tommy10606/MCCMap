// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit.helper;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.dynmap.DynmapChunk;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.chunk.GenericChunk;
import org.dynmap.common.chunk.GenericChunkCache;
import org.dynmap.common.chunk.GenericMapChunkCache;

/** Paper adapter: snapshot loaded chunks on the server thread; read saved chunks asynchronously. */
public final class PaperMapChunkCache extends GenericMapChunkCache {
    private ServerLevel world;
    private ThreadLocal<BiomeSample> biomeSamples;
    private static final class BiomeSample {
        final net.minecraft.core.BlockPos.MutableBlockPos position = new net.minecraft.core.BlockPos.MutableBlockPos();
        final net.minecraft.world.level.biome.BiomeManager manager;
        int x, y, z;
        BiomeSample(long seed, net.minecraft.core.Holder<Biome> placeholder) {
            manager = new net.minecraft.world.level.biome.BiomeManager((qx, qy, qz) -> {
                x = qx << 2; y = qy << 2; z = qz << 2; return placeholder;
            }, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed));
        }
    }
    @Override protected BiomeMap getBlockBiome(int x, int y, int z) {
        BiomeSample sample = biomeSamples.get();
        sample.manager.getBiome(sample.position.set(x, y, z));
        return getSnapshotBiome(sample.x, sample.y, sample.z);
    }
    public PaperMapChunkCache(GenericChunkCache cache) { super(cache); }
    public void setChunks(BukkitWorld world, List<DynmapChunk> chunks) {
        this.world = ((CraftWorld) world.getWorld()).getHandle();
        long seed = this.world.getSeed();
        var placeholder = this.world.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
            .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        biomeSamples = ThreadLocal.withInitial(() -> new BiomeSample(seed, placeholder));
        super.setChunks(world, chunks);
    }
    @Override
    protected Supplier<GenericChunk> getLoadedChunkAsync(DynmapChunk chunk) {
        var snapshot = CompletableFuture.supplyAsync(() -> {
            var loaded = world.getChunkIfLoaded(chunk.x, chunk.z);
            return loaded == null ? null : SerializableChunkData.copyOf(world, loaded);
        }, ((CraftServer) Bukkit.getServer()).getServer());
        return () -> {
            var data = snapshot.join();
            return data == null ? null : parseChunkFromNBT(new NBT.NBTCompound(data.write()));
        };
    }
    @Override
    protected GenericChunk getLoadedChunk(DynmapChunk chunk) {
        if (!Bukkit.isPrimaryThread()) return getLoadedChunkAsync(chunk).get();
        var loaded = world.getChunkIfLoaded(chunk.x, chunk.z);
        return loaded == null ? null : parseChunkFromNBT(new NBT.NBTCompound(SerializableChunkData.copyOf(world, loaded).write()));
    }
    @Override
    protected Supplier<GenericChunk> loadChunkAsync(DynmapChunk chunk) {
        var data = world.getChunkSource().chunkMap.read(new ChunkPos(chunk.x, chunk.z));
        return () -> data.join().map(NBT.NBTCompound::new).map(this::parseChunkFromNBT).orElse(null);
    }
    @Override
    protected GenericChunk loadChunk(DynmapChunk chunk) { return loadChunkAsync(chunk).get(); }
    @Override
    public int getFoliageColor(BiomeMap biome, int[] colors, int x, int z) {
        return biome.<Biome>getBiomeObject().map(Biome::getSpecialEffects)
            .flatMap(effects -> effects.foliageColorOverride()).orElse(colors[biome.biomeLookup()]);
    }
    @Override
    public int getGrassColor(BiomeMap biome, int[] colors, int x, int z) {
        return biome.<Biome>getBiomeObject().map(Biome::getSpecialEffects)
            .map(effects -> effects.grassColorModifier().modifyColor(x, z,
                effects.grassColorOverride().orElse(colors[biome.biomeLookup()])))
            .orElse(colors[biome.biomeLookup()]);
    }
}
