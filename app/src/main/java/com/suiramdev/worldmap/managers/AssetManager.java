package com.suiramdev.worldmap.managers;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockTypeTextures;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.CustomModelTexture;
import com.suiramdev.worldmap.models.AssetMapPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manager for gathering asset map data from the BlockType registry.
 * 
 * <p>
 * Extracts block rendering attributes (textures, transparency, light emission,
 * collision flags, etc.) from registered block types.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class AssetManager {

    private final boolean debugMode;

    /**
     * Creates a new AssetManager instance.
     * 
     * @param debugMode Whether debug logging is enabled
     */
    public AssetManager(boolean debugMode) {
        this.debugMode = debugMode;
    }

    /**
     * Gathers asset map from the BlockType registry.
     * 
     * @return List of block configurations
     */
    public List<AssetMapPayload> gatherAssetMap() {
        List<AssetMapPayload> assetMap = new ArrayList<>();

        try {
            // Get BlockType asset map (BlockType.getAssetMap() returns BlockTypeAssetMap<String, BlockType>)
            BlockTypeAssetMap<String, BlockType> blockTypeAssetMap = BlockType.getAssetMap();

            if (blockTypeAssetMap == null) {
                if (debugMode) {
                    System.err.println("[Worldmap] BlockType.getAssetMap() returned null - registry may not be initialized yet");
                }
                return assetMap;
            }

            // Get the underlying map to iterate all block types
            Map<String, BlockType> blockTypeMap = blockTypeAssetMap.getAssetMap();

            if (blockTypeMap == null || blockTypeMap.isEmpty()) {
                if (debugMode) {
                    System.err.println("[Worldmap] BlockType asset map is empty - registry may not be initialized yet");
                }
                return assetMap;
            }

            System.out.println("[Worldmap] Found " + blockTypeMap.size() + " block types in registry");

            // Extract asset map entry for each block type
            for (BlockType blockType : blockTypeMap.values()) {
                if (blockType == null) {
                    continue;
                }

                try {
                    // Get the integer block ID using the asset map's getIndex method
                    String blockTypeId = blockType.getId();
                    if (blockTypeId == null || blockTypeId.isEmpty()) {
                        if (debugMode) {
                            System.err.println("[Worldmap] BlockType has null or empty ID, skipping");
                        }
                        continue;
                    }

                    int blockId = blockTypeAssetMap.getIndex(blockTypeId);
                    if (blockId == Integer.MIN_VALUE) {
                        // Integer.MIN_VALUE is the NOT_FOUND constant in BlockTypeAssetMap
                        if (debugMode) {
                            System.err.println("[Worldmap] Could not get index for block type: " + blockTypeId);
                        }
                        continue;
                    }

                    AssetMapPayload config = extractBlockConfig(blockId, blockType);
                    if (config != null) {
                        assetMap.add(config);
                    }
                } catch (Exception e) {
                    if (debugMode) {
                        System.err.println("[Worldmap] Error extracting asset map entry for block type '" 
                                + (blockType.getId() != null ? blockType.getId() : "unknown") + "': " + e.getMessage());
                    }
                }
            }

            if (debugMode) {
                System.out.println("[Worldmap] Successfully extracted " + assetMap.size() + " asset map entries");
            }
        } catch (Exception e) {
            System.err.println("[Worldmap] Error gathering asset map: " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
        }

        return assetMap;
    }


    /**
     * Extracts block configuration from a BlockType.
     * 
     * @param blockId   The block ID
     * @param blockType The BlockType instance
     * @return The extracted AssetMapPayload, or null on error
     */
    private AssetMapPayload extractBlockConfig(int blockId, BlockType blockType) {
        AssetMapPayload.Builder builder = new AssetMapPayload.Builder(blockId);

        try {
            // Extract textures
            extractTextures(blockType, builder);

            // Extract draw type and custom model (for foliage/model blocks)
            extractDrawType(blockType, builder);

            // Extract transparency
            extractTransparency(blockType, builder);

            // Extract light emission
            extractLightEmission(blockType, builder);

            // Extract solidity/collision
            extractSolidity(blockType, builder);

            // Extract additional metadata
            extractMetadata(blockType, builder);

            return builder.build();
        } catch (Exception e) {
            if (debugMode) {
                System.err.println(
                        "[Worldmap] Error extracting asset map entry for blockId " + blockId + ": " + e.getMessage());
            }
            // Return entry with defaults if extraction fails
            return builder.build();
        }
    }

    /**
     * Extracts texture information from BlockType.
     */
    private void extractTextures(BlockType blockType, AssetMapPayload.Builder builder) {
        try {
            // Try standard cube textures first
            BlockTypeTextures[] textures = blockType.getTextures();

            if (textures != null && textures.length > 0) {
                // Standard cube block with per-face textures
                BlockTypeTextures tex = textures[0]; // Use first texture set

                // Extract per-face textures using actual getter methods
                String north = tex.getNorth();
                String south = tex.getSouth();
                String east = tex.getEast();
                String west = tex.getWest();
                String top = tex.getUp();
                String bottom = tex.getDown();

                builder.textureNorth(north != null ? north : "default");
                builder.textureSouth(south != null ? south : "default");
                builder.textureEast(east != null ? east : "default");
                builder.textureWest(west != null ? west : "default");
                builder.textureTop(top != null ? top : "default");
                builder.textureBottom(bottom != null ? bottom : "default");
            } else {
                // Try custom model texture (for foliage/model blocks)
                CustomModelTexture[] customTextures = blockType.getCustomModelTexture();

                if (customTextures != null && customTextures.length > 0) {
                    CustomModelTexture customTex = customTextures[0];
                    String texturePath = customTex.getTexture();

                    if (texturePath != null && !texturePath.isEmpty()) {
                        // Use same texture for all faces for model-based blocks
                        builder.textureNorth(texturePath);
                        builder.textureSouth(texturePath);
                        builder.textureEast(texturePath);
                        builder.textureWest(texturePath);
                        builder.textureTop(texturePath);
                        builder.textureBottom(texturePath);
                    }
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                System.err.println("[Worldmap] Error extracting textures: " + e.getMessage());
            }
            // Use defaults if extraction fails
        }
    }

    /**
     * Extracts draw type and custom model information from BlockType.
     */
    private void extractDrawType(BlockType blockType, AssetMapPayload.Builder builder) {
        try {
            // Extract draw type
            DrawType drawType = blockType.getDrawType();
            if (drawType != null) {
                builder.drawType(drawType);
            }

            // Extract custom model path (for foliage/model blocks)
            String customModel = blockType.getCustomModel();
            if (customModel != null && !customModel.isEmpty()) {
                builder.customModel(customModel);
            }
        } catch (Exception e) {
            if (debugMode) {
                System.err.println("[Worldmap] Error extracting draw type: " + e.getMessage());
            }
            // Use defaults if extraction fails
        }
    }

    /**
     * Extracts opacity information from BlockType.
     */
    private void extractTransparency(BlockType blockType, AssetMapPayload.Builder builder) {
        try {
            // Use getOpacity() directly - store the enum value
            Opacity opacity = blockType.getOpacity();
            if (opacity != null) {
                builder.opacity(opacity);
            }
        } catch (Exception e) {
            if (debugMode) {
                System.err.println("[Worldmap] Error extracting opacity: " + e.getMessage());
            }
            // Use default (Opacity.Solid) if extraction fails
        }
    }

    /**
     * Extracts light emission information from BlockType.
     */
    private void extractLightEmission(BlockType blockType, AssetMapPayload.Builder builder) {
        try {
            // Use getLight().radius - ColorLight.radius is the light emission value (0-15)
            ColorLight light = blockType.getLight();
            if (light != null) {
                // radius is a byte, convert to int (0-15 range)
                int lightEmission = light.radius & 0xFF; // Convert unsigned byte to int
                builder.lightEmission(lightEmission);
            }
        } catch (Exception e) {
            if (debugMode) {
                System.err.println("[Worldmap] Error extracting light emission: " + e.getMessage());
            }
            // Use default (0) if extraction fails
        }
    }

    /**
     * Extracts material/collision information from BlockType.
     */
    private void extractSolidity(BlockType blockType, AssetMapPayload.Builder builder) {
        try {
            // Use getMaterial() directly - store the enum value
            BlockMaterial material = blockType.getMaterial();
            if (material != null) {
                builder.material(material);
            }
        } catch (Exception e) {
            if (debugMode) {
                System.err.println("[Worldmap] Error extracting material: " + e.getMessage());
            }
            // Use default (BlockMaterial.Solid) if extraction fails
        }
    }

    /**
     * Extracts additional metadata from BlockType.
     */
    private void extractMetadata(BlockType blockType, AssetMapPayload.Builder builder) {
        Map<String, Object> metadata = new HashMap<>();

        try {
            // Extract custom model scale if available (and not default)
            try {
                float customModelScale = blockType.getCustomModelScale();
                if (customModelScale != 1.0f) {
                    metadata.put("customModelScale", customModelScale);
                }
            } catch (Exception e) {
                // Ignore if not available
            }

            // Extract custom model animation if available
            try {
                String customModelAnimation = blockType.getCustomModelAnimation();
                if (customModelAnimation != null && !customModelAnimation.isEmpty()) {
                    metadata.put("customModelAnimation", customModelAnimation);
                }
            } catch (Exception e) {
                // Ignore if not available
            }

            if (!metadata.isEmpty()) {
                builder.metadata(metadata);
            }
        } catch (Exception e) {
            if (debugMode) {
                System.err.println("[Worldmap] Error extracting metadata: " + e.getMessage());
            }
        }
    }
}
