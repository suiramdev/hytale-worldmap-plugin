package com.suiramdev.worldmap.services;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact, binary-friendly chunk payload optimized for size, stability, and
 * reusability.
 * 
 * This payload contains only raw, render-relevant block information using
 * numeric blockstate IDs.
 * No texture information (texture names, UVs, atlas indices, or material
 * references) is included.
 * Texture/material mapping is provided separately via API.
 * 
 * Binary Format (before compression):
 * - Version (1 byte): Format version for compatibility
 * - Chunk coordinates (8 bytes: 2x int): chunkX, chunkZ
 * - Timestamp (8 bytes: long): Processing timestamp
 * - Vertical bounds (4 bytes: 2x short): minY, maxY (actual Y range with
 * blocks)
 * - Feature flags (1 byte): Bit flags (light, biome, environment)
 * - Block palette size (varint): Number of unique blockstate IDs
 * - Block palette (N * 4 bytes): Array of int blockstate IDs (palette index ->
 * blockstate ID)
 * - Packed indices length (varint): Size of bit-packed data in bytes
 * - Bits per index (varint): Number of bits used per palette index
 * - Bit-packed indices (variable):
 * - Main chunk indices: 32 * (maxY-minY+1) * 32 indices (palette references)
 * - Halo indices: 34 * (maxY-minY+1) * 34 indices (includes main + 1-block
 * padding on X/Z)
 * 
 * The halo padding contains actual neighboring blockstate IDs (not inferred
 * visibility flags)
 * and is clearly separable so the worker can use it for cross-chunk face
 * culling while
 * excluding it from final mesh output. The worker can fully reconstruct the
 * padded block grid
 * without fetching additional chunks.
 * 
 * The payload is compressed with LZ4 fast compression after encoding.
 * Format is versioned and stable for future reprocessing.
 */
public class ChunkPayload {
    // Format version
    public static final byte FORMAT_VERSION = 1;

    // Feature flags
    public static final byte FLAG_LIGHT = 0x01;
    public static final byte FLAG_BIOME = 0x02;
    public static final byte FLAG_ENVIRONMENT = 0x04;

    // Chunk dimensions (Hytale: 32x32 blocks, 320 blocks tall)
    public static final int CHUNK_SIZE_X = 32;
    public static final int CHUNK_SIZE_Z = 32;
    public static final int CHUNK_SIZE_Y = 320;

    // Halo padding (1 block on each X/Z side)
    public static final int HALO_SIZE = 1;

    // Main chunk data
    public int chunkX;
    public int chunkZ;
    public long timestamp;
    public short minY;
    public short maxY;
    public byte featureFlags;

    // Block palette (blockstate ID -> palette index)
    public int[] blockPalette;
    private Map<Integer, Integer> blockPaletteMap; // blockstate ID -> palette index

    // Bit-packed block indices
    // Layout: [main chunk: 32xYx32] [halo: 34xYx34 - 32xYx32]
    // Main chunk indices: 0 to (32*Y*32 - 1)
    // Halo indices: (32*Y*32) to (34*Y*34 - 1)
    private byte[] packedIndices;
    private int bitsPerIndex;

    /**
     * Create a new chunk payload
     */
    public ChunkPayload(int chunkX, int chunkZ, long timestamp) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.timestamp = timestamp;
        this.minY = 0;
        this.maxY = CHUNK_SIZE_Y - 1;
        this.featureFlags = 0;
        this.blockPaletteMap = new HashMap<>();
    }

    /**
     * Build the payload from raw block data
     * 
     * @param mainBlocks Main chunk blocks [32][Y][32] (blockstate IDs)
     * @param haloBlocks Halo padding blocks [34][Y][34] (blockstate IDs, includes
     *                   main + padding)
     * @param minY       Minimum Y coordinate with blocks
     * @param maxY       Maximum Y coordinate with blocks
     */
    public void buildFromBlocks(int[][][] mainBlocks, int[][][] haloBlocks, int minY, int maxY) {
        this.minY = (short) minY;
        this.maxY = (short) maxY;

        // Build palette from all unique blockstate IDs
        buildPalette(mainBlocks, haloBlocks, minY, maxY);

        // Calculate bits per index (ceil(log2(paletteSize)))
        int paletteSize = blockPalette.length;
        if (paletteSize <= 1) {
            bitsPerIndex = 1;
        } else {
            bitsPerIndex = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        }

        // Pack indices
        packIndices(mainBlocks, haloBlocks, minY, maxY);
    }

    /**
     * Build the blockstate palette from unique block IDs
     */
    private void buildPalette(int[][][] mainBlocks, int[][][] haloBlocks, int minY, int maxY) {
        blockPaletteMap.clear();
        List<Integer> paletteList = new ArrayList<>();

        // Scan main chunk
        for (int x = 0; x < CHUNK_SIZE_X; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = 0; z < CHUNK_SIZE_Z; z++) {
                    int blockId = mainBlocks[x][y][z];
                    if (!blockPaletteMap.containsKey(blockId)) {
                        blockPaletteMap.put(blockId, paletteList.size());
                        paletteList.add(blockId);
                    }
                }
            }
        }

        // Scan halo (34x34 including main chunk)
        for (int x = 0; x < CHUNK_SIZE_X + 2 * HALO_SIZE; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = 0; z < CHUNK_SIZE_Z + 2 * HALO_SIZE; z++) {
                    int blockId = haloBlocks[x][y][z];
                    if (!blockPaletteMap.containsKey(blockId)) {
                        blockPaletteMap.put(blockId, paletteList.size());
                        paletteList.add(blockId);
                    }
                }
            }
        }

        // Convert to array
        blockPalette = new int[paletteList.size()];
        for (int i = 0; i < paletteList.size(); i++) {
            blockPalette[i] = paletteList.get(i);
        }
    }

    /**
     * Pack block indices using bit-packing
     * 
     * Layout:
     * 1. Main chunk indices: 32 * Y * 32 indices (for mesh generation)
     * 2. Halo indices: 34 * Y * 34 indices (for cross-chunk face culling, includes
     * main + padding)
     * 
     * The halo is sent separately so the worker can use it for culling while
     * excluding
     * the padding from final mesh output.
     */
    private void packIndices(int[][][] mainBlocks, int[][][] haloBlocks, int minY, int maxY) {
        int mainChunkSize = CHUNK_SIZE_X * (maxY - minY + 1) * CHUNK_SIZE_Z;
        int haloSizeX = CHUNK_SIZE_X + 2 * HALO_SIZE;
        int haloSizeZ = CHUNK_SIZE_Z + 2 * HALO_SIZE;
        int haloChunkSize = haloSizeX * (maxY - minY + 1) * haloSizeZ;
        int totalSize = mainChunkSize + haloChunkSize;

        // Calculate packed size in bytes
        long totalBits = (long) totalSize * bitsPerIndex;
        int packedBytes = (int) ((totalBits + 7) / 8); // Round up

        packedIndices = new byte[packedBytes];
        BitPacker packer = new BitPacker(packedIndices);

        // Pack main chunk indices (32xYx32) - used for mesh generation
        for (int x = 0; x < CHUNK_SIZE_X; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = 0; z < CHUNK_SIZE_Z; z++) {
                    int blockId = mainBlocks[x][y][z];
                    Integer paletteIndex = blockPaletteMap.get(blockId);
                    if (paletteIndex == null) {
                        // Should not happen if palette was built correctly, but handle gracefully
                        paletteIndex = 0;
                    }
                    packer.writeBits(paletteIndex, bitsPerIndex);
                }
            }
        }

        // Pack halo indices (34xYx34) - used for cross-chunk face culling
        // Includes main chunk at [1..32][y][1..32] plus 1-block padding on all X/Z
        // sides
        for (int x = 0; x < haloSizeX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = 0; z < haloSizeZ; z++) {
                    int blockId = haloBlocks[x][y][z];
                    Integer paletteIndex = blockPaletteMap.get(blockId);
                    if (paletteIndex == null) {
                        // Should not happen if palette was built correctly, but handle gracefully
                        paletteIndex = 0;
                    }
                    packer.writeBits(paletteIndex, bitsPerIndex);
                }
            }
        }

        packer.flush();
    }

    /**
     * Serialize to binary format
     */
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Version
        dos.writeByte(FORMAT_VERSION);

        // Chunk coordinates
        dos.writeInt(chunkX);
        dos.writeInt(chunkZ);

        // Timestamp
        dos.writeLong(timestamp);

        // Vertical bounds
        dos.writeShort(minY);
        dos.writeShort(maxY);

        // Feature flags
        dos.writeByte(featureFlags);

        // Block palette
        writeVarInt(dos, blockPalette.length);
        for (int blockId : blockPalette) {
            dos.writeInt(blockId);
        }

        // Bit-packed indices
        writeVarInt(dos, packedIndices.length);
        writeVarInt(dos, bitsPerIndex);
        dos.write(packedIndices);

        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Write a variable-length integer (varint)
     */
    private void writeVarInt(DataOutputStream dos, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            dos.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        dos.writeByte(value & 0x7F);
    }

    /**
     * Bit packer for efficient storage of indices
     */
    private static class BitPacker {
        private final byte[] buffer;
        private int bitOffset = 0;

        public BitPacker(byte[] buffer) {
            this.buffer = buffer;
        }

        public void writeBits(int value, int bits) {
            for (int i = 0; i < bits; i++) {
                int bit = (value >> i) & 1;
                int byteIndex = bitOffset / 8;
                int bitIndex = bitOffset % 8;

                if (bit == 1) {
                    buffer[byteIndex] |= (1 << bitIndex);
                } else {
                    buffer[byteIndex] &= ~(1 << bitIndex);
                }

                bitOffset++;
            }
        }

        public void flush() {
            // Nothing to do, bits are written directly
        }
    }
}
