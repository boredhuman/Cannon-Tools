package dev.boredhuman.cannontools;

import dev.boredhuman.api.BHCoreAPI;
import dev.boredhuman.api.config.CategoryStart;
import dev.boredhuman.api.config.ConfigMinMax;
import dev.boredhuman.api.config.ConfigProperty;
import dev.boredhuman.api.events.BatchedLineRenderingEvent;
import dev.boredhuman.api.events.PacketEvent;
import dev.boredhuman.api.events.RenderHook;
import dev.boredhuman.api.module.AbstractModule;
import dev.boredhuman.api.module.ModColor;
import dev.boredhuman.api.module.Module;
import dev.boredhuman.api.rendering.shapes.Box;
import dev.boredhuman.api.util.MutableValue;
import dev.boredhuman.gui.util.Colors;
import dev.boredhuman.util.Pair;
import dev.boredhuman.util.Position;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Module(name = "Cannon Tools", author = "BoredHuman", description = "Tools to help cannon development.")
@Mod(modid = "cannontools")
public class CannonToolsMod extends AbstractModule {

	private final Map<Integer, Pair<LinkedList<Long>, LinkedList<Position>>> TNTLines = new ConcurrentHashMap<>();
	private final Map<Integer, Pair<LinkedList<Long>, LinkedList<Position>>> fallingBlockLines = new ConcurrentHashMap<>();
	private final Map<Position, Long> tntPositionTime = new ConcurrentHashMap<>();
	private final Set<Pair<Position, Integer>> liveTNT = new HashSet<>();
	private final Set<Position> liveSand = new HashSet<>();

	@ConfigProperty(name = "Clear Time /s")
	@ConfigMinMax(min = 1, max = 30)
	private MutableValue<Float> clearTime = new MutableValue<>(5F);

	@ConfigProperty(name = "Line Width")
	@ConfigMinMax(min = 1, max = 5)
	private MutableValue<Float> lineWidth = new MutableValue<>(2F);

	@CategoryStart(name = "Explosion Box Settings")
	@ConfigProperty(name = "Explosion Box")
	private MutableValue<Boolean> explosionBox = new MutableValue<>(false);

	@ConfigProperty(name = "Box Color")
	private ModColor explosionBoxColor = new ModColor(Colors.RED.getIntColor());

	@ConfigProperty(name = "Box Radius")
	@ConfigMinMax(min = 0.1F, max = 0.5F)
	private MutableValue<Float> explosionBoxRadius = new MutableValue<>(0.25F);

	@ConfigProperty(name = "Box Line Width")
	@ConfigMinMax(min = 1, max = 5)
	private MutableValue<Float> boxLineWidth = new MutableValue<>(2F);

	@CategoryStart(name = "TNT Settings")
	@ConfigProperty(name = "TNT Breadcrumbs")
	private MutableValue<Boolean> showTNTCrumbs = new MutableValue<>(true);

	@ConfigProperty(name = "TNT Line Color")
	private ModColor tntLineColor = new ModColor(Colors.RED.getIntColor());

	@ConfigProperty(name = "Minimal TNT")
	private MutableValue<Boolean> minimalTNT = new MutableValue<>(false);

	@ConfigProperty(name = "TNT Triangle Lines")
	private MutableValue<Boolean> tntTriangles = new MutableValue<>(false);

	@CategoryStart(name = "Sand Settings")
	@ConfigProperty(name = "Sand Breadcrumbs")
	private MutableValue<Boolean> showSandCrumbs = new MutableValue<>(true);

	@ConfigProperty(name = "Sand Line Color")
	private ModColor fallingBlockLineColor = new ModColor(Colors.YELLOW.getIntColor());

	@ConfigProperty(name = "Minimal Sand")
	private MutableValue<Boolean> minimalSand = new MutableValue<>(false);

	@ConfigProperty(name = "Sand Triangle Lines")
	private MutableValue<Boolean> sandTriangles = new MutableValue<>(false);

	@CategoryStart(name = "Rendering Settings")
	@ConfigProperty(name = "TNT Flashing")
	public MutableValue<Boolean> doTNTFlashing = new MutableValue<>(true);

	@ConfigProperty(name = "TNT Expand")
	public MutableValue<Boolean> doTNTExpand = new MutableValue<>(true);

	@ConfigProperty(name = "Do Depth")
	private MutableValue<Boolean> doDepth = new MutableValue<>(false);

	@CategoryStart(name = "Patch Crumbs Settings")
	@ConfigProperty(name = "Patch Crumbs")
	private MutableValue<Boolean> patchCrumbs = new MutableValue<>(false);

	@ConfigProperty(name = "Patch Color")
	private ModColor patchColor = new ModColor(0xFF00FF00);

	@ConfigProperty(name = "Cube Thickness")
	@ConfigMinMax(min = 0.1F, max = 0.5F)
	private MutableValue<Float> cubeThickness = new MutableValue<>(0.25F);

	// position x, z and insert time, explosions
	private Map<Pair<Integer, Integer>, Pair<Long, Pair<List<Position>, Set<Integer>>>> wallColumn = Collections.synchronizedMap(new LinkedHashMap<>());

	private Map<Position, MiniChunk> dispenserCache = new HashMap<>();

	RenderHook renderHook = phase -> {
		if (!this.doDepth.getValue()) {
			if (phase == RenderHook.Phase.SETUP) {
				GlStateManager.disableDepth();
			} else {
				GlStateManager.enableDepth();
			}
		}
	};

	private static CannonToolsMod INSTANCE;

	@Mod.EventHandler
	public void preInit(FMLPostInitializationEvent event) {
		this.init();
	}

	@Override
	public void init() {
		CannonToolsMod.INSTANCE = this;
		BHCoreAPI.getAPI().registerModules(this);
		RenderingRegistry.registerEntityRenderingHandler(EntityTNTPrimed.class, new TNTRenderer(Minecraft.getMinecraft().getRenderManager()));
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (!this.enabled.getValue()) {
			return;
		}
		if (event.phase == TickEvent.Phase.END) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.theWorld == null) {
			return;
		}
		this.cleanUp(this.TNTLines);
		this.cleanUp(this.fallingBlockLines);
		this.cleanUpBoxes();
		this.cleanupColumns();
		long now = System.currentTimeMillis();
		for (Entity entity : mc.theWorld.loadedEntityList) {
			boolean isTNT = entity instanceof EntityTNTPrimed;
			boolean isSand = entity instanceof EntityFallingBlock;
			if (!isSand && !isTNT) {
				continue;
			}
			Position position = new Position(entity.posX, entity.posY + 0.49, entity.posZ);
			if (isTNT && this.minimalTNT.getValue()) {
				EntityTNTPrimed tnt = (EntityTNTPrimed) entity;
				Pair<Position, Integer> positionFusePair = new Pair<>(position, tnt.fuse);
				if (this.liveTNT.contains(positionFusePair)) {
					entity.setDead();
					continue;
				}
				this.liveTNT.add(positionFusePair);
			}
			if (isSand && this.minimalSand.getValue()) {
				if (this.liveSand.contains(position)) {
					entity.setDead();
					continue;
				}
				this.liveSand.add(position);
			}
			boolean gatherTNTLines = isTNT && this.showTNTCrumbs.getValue();
			boolean gatherSandLines = isSand && this.showSandCrumbs.getValue();
			if ((!gatherTNTLines && !gatherSandLines && !this.patchCrumbs.getValue())) {
				continue;
			}

			int cX = MathHelper.floor_double(entity.posX / 4.0D);
			int cZ = MathHelper.floor_double(entity.posZ / 4.0D);
			Position miniChunkPosition = new Position(cX, 0, cZ);
			MiniChunk miniChunk = this.dispenserCache.computeIfAbsent(miniChunkPosition, pos -> new MiniChunk(cX, cZ));
			Position cXP = new Position(cX + 1, 0, cZ);
			MiniChunk mc2 = this.dispenserCache.computeIfAbsent(cXP, pos -> new MiniChunk(cX + 1, cZ));
			Position cXN = new Position(cX - 1, 0, cZ);
			MiniChunk mc3 = this.dispenserCache.computeIfAbsent(cXN, pos -> new MiniChunk(cX - 1, cZ));
			Position cZP = new Position(cX, 0, cZ + 1);
			MiniChunk mc4 = this.dispenserCache.computeIfAbsent(cZP, pos -> new MiniChunk(cX, cZ + 1));
			Position cZN = new Position(cX, 0, cZ - 1);
			MiniChunk mc5 = this.dispenserCache.computeIfAbsent(cZN, pos -> new MiniChunk(cX, cZ - 1));

			if (miniChunk.containsDispensers(now) || mc2.containsDispensers(now) || mc3.containsDispensers(now) || mc4.containsDispensers(now) || mc5.containsDispensers(now)) {
				continue;
			}

			Pair<Long, Pair<List<Position>, Set<Integer>>> wallColumn = this.wallColumn.computeIfAbsent(Pair.of((int) entity.posX, (int) entity.posZ), pair -> {
				return Pair.of(now, Pair.of(new ArrayList<>(), new HashSet<>()));
			});

			int id = entity.getEntityId();
			if (wallColumn.second.second.add(id)) {
				wallColumn.second.first.add(position);
			}
			Pair<LinkedList<Long>, LinkedList<Position>> listLinkedListPair;
			if (entity instanceof EntityTNTPrimed) {
				listLinkedListPair = this.TNTLines.computeIfAbsent(id, k -> new Pair<>(new LinkedList<>(), new LinkedList<>()));
			} else {
				listLinkedListPair = this.fallingBlockLines.computeIfAbsent(id, k -> new Pair<>(new LinkedList<>(), new LinkedList<>()));
			}
			Position last = listLinkedListPair.second.peekLast();
			if (last == null || !last.equals(position)) {
				boolean triangle = ((isTNT && this.tntTriangles.getValue()) || (isSand && this.sandTriangles.getValue())) && last != null;
				if (triangle) {
					listLinkedListPair.second.add(new Position(last.x, position.y, last.z));
					listLinkedListPair.first.add(now);
					listLinkedListPair.second.add(new Position(position.x, position.y, last.z));
					listLinkedListPair.first.add(now);
				}
				listLinkedListPair.second.add(position);
				listLinkedListPair.first.add(now);
			}
		}
		this.liveTNT.clear();
		this.liveSand.clear();
	}

	public void cleanUp(Map<Integer, Pair<LinkedList<Long>, LinkedList<Position>>> map) {
		long clearTime = (long) (this.clearTime.getValue() * 1000f);
		long now = System.currentTimeMillis();
		for (Map.Entry<Integer, Pair<LinkedList<Long>, LinkedList<Position>>> lineData : map.entrySet()) {
			Pair<LinkedList<Long>, LinkedList<Position>> timePositionPair = lineData.getValue();
			if (timePositionPair.second.isEmpty()) {
				map.remove(lineData.getKey());
			}
			while (timePositionPair.first.peekFirst() != null && timePositionPair.first.getFirst() + clearTime < now) {
				timePositionPair.first.removeFirst();
				timePositionPair.second.removeFirst();
			}
		}
	}

	public void cleanUpBoxes() {
		long clearTime = (long) (this.clearTime.getValue() * 1000f);
		long now = System.currentTimeMillis();
		this.tntPositionTime.entrySet().removeIf(positionLongEntry -> positionLongEntry.getValue() + clearTime < now);
	}

	public void cleanupColumns() {
		long now = System.currentTimeMillis();
		List<Pair<Integer, Integer>> toRemove = new ArrayList<>();
		for (Map.Entry<Pair<Integer, Integer>, Pair<Long, Pair<List<Position>, Set<Integer>>>> mapEntry : this.wallColumn.entrySet()) {
			if (mapEntry.getValue().first + 10000 < now) {
				toRemove.add(mapEntry.getKey());
			}
		}
		for (Pair<Integer, Integer> key : toRemove) {
			this.wallColumn.remove(key);
		}
	}

	@SubscribeEvent
	public void batchedLineStripEvent(BatchedLineRenderingEvent event) {
		if (this.showTNTCrumbs.getValue()) {
			for (Pair<LinkedList<Long>, LinkedList<Position>> toDraw : this.TNTLines.values()) {
				event.addLineStripLineWidth(Pair.of(this.lineWidth.getValue(), this.renderHook), new BatchedLineRenderingEvent.LineStrip(toDraw.second, this.tntLineColor.getBGRA()));
			}
		}
		if (this.showSandCrumbs.getValue()) {
			for (Pair<LinkedList<Long>, LinkedList<Position>> toDraw : this.fallingBlockLines.values()) {
				event.addLineStripLineWidth(Pair.of(this.lineWidth.getValue(), this.renderHook), new BatchedLineRenderingEvent.LineStrip(toDraw.second, this.fallingBlockLineColor.getBGRA()));
			}
		}

		if (this.explosionBox.getValue()) {
			List<Pair<Position, Position>> lines = new ArrayList<>();

			for (Position position : this.tntPositionTime.keySet()) {
				lines.addAll(this.makeOutline(position, this.explosionBoxRadius.getValue()));
			}

			event.addLineLineWidth(Pair.of(this.boxLineWidth.getValue(), this.renderHook), new BatchedLineRenderingEvent.Line(lines, this.explosionBoxColor.getBGRA()));
		}

		if (this.patchCrumbs.getValue()) {
			this.doPatchCrumbs(event);
		}
	}

	@SubscribeEvent
	public void onPacket(PacketEvent event) {
		if (!this.explosionBox.getValue()) {
			return;
		}
		if (event.getPacket() instanceof S27PacketExplosion) {
			S27PacketExplosion explosion = (S27PacketExplosion) event.getPacket();
			Position pos = new Position(explosion.getX(), explosion.getY(), explosion.getZ());
			this.tntPositionTime.put(pos, System.currentTimeMillis());
		}
	}

	int biggestColumnSize = 0;
	long biggestColumnSizeTime = 0;

	public void doPatchCrumbs(BatchedLineRenderingEvent event) {
		if (this.wallColumn.isEmpty()) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now - this.biggestColumnSizeTime > 30000) {
			this.biggestColumnSize = 0;
		}

		Pair<List<Position>, Set<Integer>> column = null;
		for (Pair<Long, Pair<List<Position>, Set<Integer>>> value : this.wallColumn.values()) {
			if (this.biggestColumnSize < value.second.second.size()) {
				this.biggestColumnSize = value.second.second.size();
				this.biggestColumnSizeTime = now;
			}
			if (column == null) {
				column = value.second;
				continue;
			}
			if (column.second.size() < value.second.second.size()) {
				column = value.second;
			}
		}

		if (column == null || column.second.size() < this.biggestColumnSize * 0.25) {
			return;
		}

		Position patchPosition = column.first.get(0);

		if (patchPosition.y == -1) {
			return;
		}

		float rad = this.cubeThickness.getValue();

		Box patchXBox = new Box(patchPosition.x - 100, patchPosition.y - rad, patchPosition.z - rad, patchPosition.x + 100, patchPosition.y + rad, patchPosition.z + rad);
		Box patchZBox = new Box(patchPosition.x - rad, patchPosition.y - rad, patchPosition.z - 100, patchPosition.x + rad, patchPosition.y + rad, patchPosition.z + 100);

		List<Pair<Position, Position>> boxOutlines = new ArrayList<>();
		boxOutlines.addAll(this.makeOutline(patchXBox.minX, patchXBox.minY, patchXBox.minZ, patchXBox.maxX, patchXBox.maxY, patchXBox.maxZ));
		boxOutlines.addAll(this.makeOutline(patchZBox.minX, patchZBox.minY, patchZBox.minZ, patchZBox.maxX, patchZBox.maxY, patchZBox.maxZ));

		event.addLineLineWidth(Pair.of(this.boxLineWidth.getValue(), this.renderHook), new BatchedLineRenderingEvent.Line(boxOutlines, this.patchColor.getBGRA()));

		int color = this.patchColor.getBGRA();
		event.addBoxesColor(color, Arrays.asList(new Box[]{patchXBox, patchZBox}), null);
	}

	public List<Pair<Position, Position>> makeOutline(Position position, double radius) {
		List<Pair<Position, Position>> linesList = new ArrayList<>();
		double minX = position.x - radius;
		double minY = position.y - radius;
		double minZ = position.z - radius;
		double maxX = position.x + radius;
		double maxY = position.y + radius;
		double maxZ = position.z + radius;

		return this.makeOutline(minX, minY, minZ, maxX, maxY, maxZ);
	}

	public List<Pair<Position, Position>> makeOutline(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		List<Pair<Position, Position>> linesList = new ArrayList<>();
		Position origin = new Position(minX, minY, minZ);
		Position max = new Position(maxX, maxY, maxZ);
		Position originX = new Position(maxX, minY, minZ);
		Position originZ = new Position(minX, minY, maxZ);
		Position originXZ = new Position(maxX, minY, maxZ);
		Position maxx = new Position(minX, maxY, maxZ);
		Position maxz = new Position(maxX, maxY, minZ);
		Position maxxz = new Position(minX, maxY, minZ);
		linesList.add(new Pair<>(origin, originX));
		linesList.add(new Pair<>(origin, originZ));
		linesList.add(new Pair<>(originX, originXZ));
		linesList.add(new Pair<>(originZ, originXZ));

		linesList.add(new Pair<>(max, maxx));
		linesList.add(new Pair<>(max, maxz));
		linesList.add(new Pair<>(maxx, maxxz));
		linesList.add(new Pair<>(maxz, maxxz));

		linesList.add(new Pair<>(origin, maxxz));
		linesList.add(new Pair<>(originX, maxz));
		linesList.add(new Pair<>(originZ, maxx));
		linesList.add(new Pair<>(originXZ, max));

		return linesList;
	}

	public static CannonToolsMod getInstance() {
		return CannonToolsMod.INSTANCE;
	}
}
