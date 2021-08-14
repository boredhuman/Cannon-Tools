package dev.boredhuman.cannontools;

import dev.boredhuman.api.BHCoreAPI;
import dev.boredhuman.api.config.ConfigMinMax;
import dev.boredhuman.api.config.ConfigProperty;
import dev.boredhuman.api.events.BatchedLineRenderingEvent;
import dev.boredhuman.api.events.PacketEvent;
import dev.boredhuman.api.events.RenderHook;
import dev.boredhuman.api.module.AbstractModule;
import dev.boredhuman.api.module.ModColor;
import dev.boredhuman.api.module.Module;
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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Module(name = "Cannon Tools", author = "BoredHuman", description = "Tools to help cannon development.")
@Mod(modid = "Cannon Tools", dependencies = "after:BHCore")
public class CannonToolsMod extends AbstractModule {

	private Map<Integer, Pair<LinkedList<Long>, LinkedList<Position>>> TNTLines = new ConcurrentHashMap<>();
	private Map<Integer, Pair<LinkedList<Long>, LinkedList<Position>>> fallingBlockLines = new ConcurrentHashMap<>();
	private Map<Position, Long> tntPositionTime = new ConcurrentHashMap<>();
	private Set<Pair<Position, Integer>> liveTNT = new HashSet<>();
	private Set<Position> liveSand = new HashSet<>();

	@ConfigProperty(name = "Clear Time /s")
	@ConfigMinMax(min = 1, max = 30)
	private MutableValue<Float> clearTime = new MutableValue<>(5F);

	@ConfigProperty(name = "Line Width")
	@ConfigMinMax(min = 1, max = 5)
	private MutableValue<Float> lineWidth = new MutableValue<>(2F);

	@ConfigProperty(name = "TNT Line Color")
	private ModColor tntLineColor = new ModColor(Colors.RED.getIntColor());

	@ConfigProperty(name = "Sand Line Color")
	private ModColor fallingBlockLineColor = new ModColor(Colors.YELLOW.getIntColor());

	@ConfigProperty(name = "Box Color")
	private ModColor explosionBoxColor = new ModColor(Colors.RED.getIntColor());

	@ConfigProperty(name = "Box Radius")
	@ConfigMinMax(min = 0.1F, max = 0.5F)
	private MutableValue<Float> explosionBoxRadius = new MutableValue<>(0.25F);

	@ConfigProperty(name = "Box Line Width")
	@ConfigMinMax(min = 1, max = 5)
	private MutableValue<Float> boxLineWidth = new MutableValue<>(2F);

	@ConfigProperty(name = "TNT Breadcrumbs")
	private MutableValue<Boolean> showTNTCrumbs = new MutableValue<>(true);

	@ConfigProperty(name = "Sand Breadcrumbs")
	private MutableValue<Boolean> showSandCrumbs = new MutableValue<>(true);

	@ConfigProperty(name = "TNT Flashing")
	public MutableValue<Boolean> doTNTFlashing = new MutableValue<>(true);

	@ConfigProperty(name = "TNT Expand")
	public MutableValue<Boolean> doTNTExpand = new MutableValue<>(true);

	@ConfigProperty(name = "Minimal TNT")
	private MutableValue<Boolean> minimalTNT = new MutableValue<>(false);

	@ConfigProperty(name = "Minimal Sand")
	private MutableValue<Boolean> minimalSand = new MutableValue<>(false);

	@ConfigProperty(name = "Do Depth")
	private MutableValue<Boolean> doDepth = new MutableValue<>(false);

	@Mod.Instance
	private static CannonToolsMod INSTANCE;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		BHCoreAPI.getAPI().registerModules(this);
		MinecraftForge.EVENT_BUS.register(this);
	}

	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		RenderingRegistry.registerEntityRenderingHandler(EntityTNTPrimed.class, new TNTRenderer(Minecraft.getMinecraft().getRenderManager()));
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
		long now = System.currentTimeMillis();
		for (Entity entity : mc.theWorld.loadedEntityList) {
			Position position = new Position(entity.posX, entity.posY, entity.posZ);
			if (entity instanceof EntityTNTPrimed && this.minimalTNT.getValue()) {
				EntityTNTPrimed tnt = (EntityTNTPrimed) entity;
				Pair<Position, Integer> positionFusePair = new Pair<>(position, tnt.fuse);
				if (this.liveTNT.contains(positionFusePair)) {
					entity.setDead();
					continue;
				}
				this.liveTNT.add(positionFusePair);
			}
			if (entity instanceof EntityFallingBlock && this.minimalSand.getValue()) {
				if (this.liveSand.contains(position)) {
					entity.setDead();
					continue;
				}
				this.liveSand.add(position);
			}
			if ((!(entity instanceof EntityTNTPrimed) || !this.showTNTCrumbs.getValue()) && (!(entity instanceof EntityFallingBlock)) || !this.showSandCrumbs.getValue()) {
				continue;
			}
			int id = entity.getEntityId();
			Pair<LinkedList<Long>, LinkedList<Position>> listLinkedListPair;
			if (entity instanceof EntityTNTPrimed) {
				listLinkedListPair = this.TNTLines.computeIfAbsent(id, k -> new Pair<>(new LinkedList<>(), new LinkedList<>()));
			} else {
				listLinkedListPair = this.fallingBlockLines.computeIfAbsent(id, k -> new Pair<>(new LinkedList<>(), new LinkedList<>()));
			}
			Position last = listLinkedListPair.second.peekLast();
			if (last == null || !last.equals(position)) {
				listLinkedListPair.second.add(position);
				listLinkedListPair.first.add(now);
			}
		}
		this.liveTNT.clear();
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

	@SubscribeEvent
	public void batchedLineStripEvent(BatchedLineRenderingEvent event) {
		RenderHook renderHook = phase -> {
			if (!this.doDepth.getValue()) {
				if (phase == RenderHook.Phase.SETUP) {
					GlStateManager.disableDepth();
				} else {
					GlStateManager.enableDepth();
				}
			}
		};
		for (Pair<LinkedList<Long>, LinkedList<Position>> toDraw : this.TNTLines.values()) {
			event.addLineStripLineWidth(Pair.of(this.lineWidth.getValue(), renderHook), new BatchedLineRenderingEvent.LineStrip(toDraw.second, this.tntLineColor.getGBRA()));
		}
		for (Pair<LinkedList<Long>, LinkedList<Position>> toDraw : this.fallingBlockLines.values()) {
			event.addLineStripLineWidth(Pair.of(this.lineWidth.getValue(), renderHook), new BatchedLineRenderingEvent.LineStrip(toDraw.second, this.fallingBlockLineColor.getGBRA()));
		}

		List<Pair<Position, Position>> lines = new ArrayList<>();

		for (Position position : this.tntPositionTime.keySet()) {
			lines.addAll(this.makeOutline(position, this.explosionBoxRadius.getValue()));
		}

		event.addLineLineWidth(Pair.of(this.boxLineWidth.getValue(), renderHook), new BatchedLineRenderingEvent.Line(lines, this.explosionBoxColor.getGBRA()));
	}

	@SubscribeEvent
	public void onPacket(PacketEvent event) {
		if (event.getPacket() instanceof S27PacketExplosion) {
			S27PacketExplosion explosion = (S27PacketExplosion) event.getPacket();
			Position pos = new Position(explosion.getX(), explosion.getY(), explosion.getZ());
			this.tntPositionTime.put(pos, System.currentTimeMillis());
		}
	}

	public List<Pair<Position, Position>> makeOutline(Position position, double radius) {
		List<Pair<Position, Position>> linesList = new ArrayList<>();
		double minX = position.x - radius;
		double minY = position.y - radius;
		double minZ = position.z - radius;
		double maxX = position.x + radius;
		double maxY = position.y + radius;
		double maxZ = position.z + radius;
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
