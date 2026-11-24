Copper statue geometry

copper-golem-statues.json contains baked Minecraft 26.3 mesh data for the
standing, sitting, running and star poses, before horizontal facing rotation.
Each face contains origin XYZ, full-texture U endpoint XYZ, full-texture V
endpoint XYZ, maximum U, maximum V and skin-region index. Coordinates are in
blocks; UV coordinates refer to a 16-pixel tile. Entity-skin regions are copied
to the bottom left of those tiles by the CUSTOM texture definitions in
texture_1.txt. Keep the region ordering in the mesh and texture definitions
consistent. Original 64x64 entity texture paths allow resource-pack overrides.

The data and vanilla textures originate from Minecraft 26.3 assets. Minecraft
assets are copyright Mojang Studios. No Minecraft client implementation is
included. CopperGolemStatueRenderer supplies the renderer and facing selection.

Minecraft 26.3 sign geometry in models_1.txt follows the vanilla sign templates,
blockstate rotations, per-face UVs and element rotation origins. Sign atlases use
vanilla block texture paths so resource packs can override them. These Minecraft
assets are copyright Mojang Studios.
