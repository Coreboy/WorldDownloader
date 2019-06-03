/*
 * This file is part of World Downloader: A mod to make backups of your
 * multiplayer worlds.
 * http://www.minecraftforum.net/forums/mapping-and-modding/minecraft-mods/2520465
 *
 * Copyright (c) 2014 nairol, cubic72
 * Copyright (c) 2018 Pokechu22, julialy
 *
 * This project is licensed under the MMPLv2.  The full text of the MMPL can be
 * found in LICENSE.md, or online at https://github.com/iopleke/MMPLv2/blob/master/LICENSE.md
 * For information about this the MMPLv2, see http://stopmodreposts.org/
 *
 * Do not redistribute (in modified or unmodified form) without prior permission.
 */
package wdl;

import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEventData;
import net.minecraft.block.BlockState;
import net.minecraft.client.multiplayer.ClientChunkProvider;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUseContext;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.profiler.Profiler;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.ServerWorld;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biomes;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.ServerChunkProvider;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

abstract class ExtWorldClient extends ClientWorld {
	public ExtWorldClient(ClientPlayNetHandler netHandler, WorldSettings settings, DimensionType dim,
			Difficulty difficulty, Profiler profilerIn) {
		super(netHandler, settings, dim, difficulty, profilerIn);
	}

	@Override
	protected ClientChunkProvider createChunkProvider() {
		ClientChunkProvider provider = new SimpleChunkProvider();
		// Update WorldClient.clientChunkProvider which is a somewhat redundant field
		ReflectionUtils.findAndSetPrivateField(this, ClientWorld.class, ClientChunkProvider.class, provider);
		return provider;
	}

	@Override
	public Entity getEntityByID(int id) {
		// Ignore the WorldClient implementation which depends on mc.player
		return this.entitiesById.lookup(id);
	}

	@Override
	public boolean spawnEntity(Entity entityIn) {
		// WorldClient implementation depends on an MC instance
		int chunkX = MathHelper.floor(entityIn.posX / 16.0D);
		int chunkZ = MathHelper.floor(entityIn.posZ / 16.0D);
		this.getChunk(chunkX, chunkZ).addEntity(entityIn);
		this.loadedEntityList.add(entityIn);
		this.onEntityAdded(entityIn);
		return true;
	}

	private final RecipeManager recipeManager = mock(RecipeManager.class);
	private final Scoreboard scoreboard = mock(Scoreboard.class, withSettings().useConstructor());

	@Override
	public RecipeManager getRecipeManager() {
		return this.recipeManager;
	}

	@Override
	public Scoreboard getScoreboard() {
		return this.scoreboard;
	}

	/** Places a block, creating the proper state. */
	public void placeBlockAt(BlockPos pos, Block block, PlayerEntity player, Direction direction) {
		player.rotationYaw = direction.getHorizontalAngle();
		BlockItemUseContext context = new BlockItemUseContext(
				new ItemUseContext(player, new ItemStack(block), pos, direction, 0, 0, 0));
		BlockState state = block.getStateForPlacement(context);
		setBlockState(pos, state);
	}

	/** Defers to the 1.13.1-specific tick method */
	public void tick() {
		this.tick(() -> true);
	}

	private static final Biome[] NO_BIOMES = new Biome[16*16];
	static {
		Arrays.fill(NO_BIOMES, Biomes.THE_VOID);
	}

	/**
	 * A simple chunk provider implementation that just caches chunks in a map.
	 */
	private final class SimpleChunkProvider extends ClientChunkProvider implements IChunkProvider {
		private final Long2ObjectMap<Chunk> map = new Long2ObjectOpenHashMap<>();

		public SimpleChunkProvider() {
			super(ExtWorldClient.this);
		}

		@Override
		public Chunk getChunk(int x, int z, boolean p_186025_3_, boolean p_186025_4_) {
			return map.computeIfAbsent(ChunkPos.asLong(x, z), k -> new Chunk(ExtWorldClient.this, x, z, NO_BIOMES));
		}

		@Override
		public boolean tick(BooleanSupplier p_73156_1_) {
			return false;
		}

		@Override
		public String makeString() {
			return this.toString();
		}

		@Override
		public IChunkGenerator<?> getChunkGenerator() {
			return null; // This is allowed, albeit awkward; see ChunkProviderClient
		}
	}
}

abstract class ExtWorldServer extends ServerWorld {
	public ExtWorldServer(MinecraftServer server, ISaveHandler saveHandlerIn, WorldInfo info, DimensionType dim,
			Profiler profilerIn) {
		super(server, saveHandlerIn, null, info, dim, profilerIn);
	}

	@Override
	protected ServerChunkProvider createChunkProvider() {
		return new SimpleChunkProvider();
	}

	private final RecipeManager recipeManager = mock(RecipeManager.class);
	private final ServerScoreboard scoreboard = mock(ServerScoreboard.class, withSettings().useConstructor(new Object[] {null}));

	@Override
	public RecipeManager getRecipeManager() {
		return this.recipeManager;
	}

	@Override
	public ServerScoreboard getScoreboard() {
		return this.scoreboard;
	}

	/** Places a block, creating the proper state. */
	public void placeBlockAt(BlockPos pos, Block block, PlayerEntity player, Direction direction) {
		player.rotationYaw = direction.getHorizontalAngle();
		BlockItemUseContext context = new BlockItemUseContext(
				new ItemUseContext(player, new ItemStack(block), pos, direction, 0, 0, 0));
		BlockState state = block.getStateForPlacement(context);
		setBlockState(pos, state);
	}

	/** Left-clicks a block. */
	public void clickBlock(BlockPos pos, PlayerEntity player) {
		BlockState state = this.getBlockState(pos);
		state.onBlockClicked(this, pos, player);
	}

	/** Right-clicks a block. */
	public void interactBlock(BlockPos pos, PlayerEntity player) {
		BlockState state = this.getBlockState(pos);
		state.onBlockActivated(this, pos, player, Hand.MAIN_HAND, Direction.DOWN, 0, 0, 0);
	}

	/** Right-clicks an entity. */
	public void interactEntity(Entity entity, PlayerEntity player) {
		entity.processInitialInteract(player, Hand.MAIN_HAND);
	}

	public Consumer<BlockEventData> onBlockEvent = (e -> super.addBlockEvent(e.getPosition(), e.getBlock(), e.getEventID(), e.getEventParameter()));

	@Override
	public void addBlockEvent(BlockPos pos, Block blockIn, int eventID, int eventParam) {
		onBlockEvent.accept(new BlockEventData(pos, blockIn, eventID, eventParam));
	}

	/** Defers to the 1.13.1-specific tick method */
	public void tick() {
		this.tick(() -> true);
	}

	private static final Biome[] NO_BIOMES = new Biome[16*16];
	static {
		Arrays.fill(NO_BIOMES, Biomes.THE_VOID);
	}

	/**
	 * A simple chunk provider implementation that just caches chunks in a map.
	 */
	private final class SimpleChunkProvider extends ServerChunkProvider implements IChunkProvider {
		private final Long2ObjectMap<Chunk> map = new Long2ObjectOpenHashMap<>();

		public SimpleChunkProvider() {
			super(ExtWorldServer.this, null, null, null);
		}

		@Override
		public Chunk getChunk(int x, int z, boolean p_186025_3_, boolean p_186025_4_) {
			return map.computeIfAbsent(ChunkPos.asLong(x, z), k -> new Chunk(ExtWorldServer.this, x, z, NO_BIOMES));
		}

		@Override
		public boolean tick(BooleanSupplier p_73156_1_) {
			return false;
		}

		@Override
		public String makeString() {
			return this.toString();
		}

		@Override
		public int getLoadedChunkCount() {
			return map.size();
		}

		@Override
		public boolean chunkExists(int x, int z) {
			return true;
		}

		@Override
		public IChunkGenerator<?> getChunkGenerator() {
			return null; // This is allowed, albeit awkward; see ChunkProviderClient
		}
	}
}