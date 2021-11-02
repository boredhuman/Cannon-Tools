package dev.boredhuman.cannontools;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDispenser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.Chunk;

import java.util.Objects;

public class MiniChunk {

	int x, z;
	long lastCheck;
	boolean containsDispenser;

	public MiniChunk(int x, int z) {
		this.x = x;
		this.z = z;
	}

	public boolean containsDispensers(long currentTime) {
		WorldClient worldClient = Minecraft.getMinecraft().theWorld;

		if (worldClient == null) {
			return false;
		}

		if (currentTime - this.lastCheck < 60000) {
			return this.containsDispenser;
		}

		Chunk chunk = worldClient.getChunkFromChunkCoords(this.x >> 2, this.z >> 2);

		BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

		for (int x = 0; x < 4; x++) {
			for (int y = 0; y < 256; y++) {
				for (int z = 0; z < 4; z++) {
					mutableBlockPos.set((this.x << 2) + x, y, (this.z << 2) + z);
					Block block = chunk.getBlock(mutableBlockPos);
					if (block instanceof BlockDispenser) {
						this.containsDispenser = true;
						break;
					}
				}
			}
		}

		this.lastCheck = currentTime;
		return this.containsDispenser;
	}

	public boolean hasExpired() {
		return System.currentTimeMillis() - this.lastCheck > 1000;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || this.getClass() != o.getClass()) {
			return false;
		}
		MiniChunk miniChunk = (MiniChunk) o;
		return this.x == miniChunk.x && this.z == miniChunk.z;
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.x, this.z);
	}
}
