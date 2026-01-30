package com.suiramdev.worldmap.models;

import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.protocol.Opacity;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the complete rendering configuration for a single block type in
 * the asset map.
 * 
 * <p>
 * This class stores all attributes needed for chunk rendering, including
 * per-face textures, transparency, light emission, collision flags, and
 * metadata.
 * </p>
 * 
 * @author suiramdev
 * @version 1.0.0
 */
public class AssetMapPayload {

    /** Block ID (unique identifier) */
    private final int blockId;

    /** Per-face texture identifiers (preserved exactly as provided by API) */
    private final String textureNorth;
    private final String textureSouth;
    private final String textureEast;
    private final String textureWest;
    private final String textureTop;
    private final String textureBottom;

    /**
     * Draw type enum - determines rendering shape (Cube, Model, CubeWithModel,
     * etc.)
     */
    private final DrawType drawType;

    /** Custom model path (for foliage/model blocks) - null if not a custom model */
    private final String customModel;

    /** Opacity enum - determines transparency level */
    private final Opacity opacity;

    /** Light emission value (0-15, where 0 = no emission, 15 = full brightness) */
    private final int lightEmission;

    /** Block material enum - determines collision/solidity */
    private final BlockMaterial material;

    /** Additional metadata for rendering pipeline */
    private final Map<String, Object> metadata;

    /**
     * Private constructor - use Builder pattern.
     */
    private AssetMapPayload(Builder builder) {
        this.blockId = builder.blockId;
        this.textureNorth = builder.textureNorth;
        this.textureSouth = builder.textureSouth;
        this.textureEast = builder.textureEast;
        this.textureWest = builder.textureWest;
        this.textureTop = builder.textureTop;
        this.textureBottom = builder.textureBottom;
        this.drawType = builder.drawType;
        this.customModel = builder.customModel;
        this.opacity = builder.opacity;
        this.lightEmission = builder.lightEmission;
        this.material = builder.material;
        this.metadata = builder.metadata != null ? new HashMap<>(builder.metadata) : new HashMap<>();
    }

    /**
     * Gets the block ID.
     * 
     * @return The block ID
     */
    public int getBlockId() {
        return blockId;
    }

    /**
     * Gets the texture identifier for the north face.
     * 
     * @return The texture identifier
     */
    public String getTextureNorth() {
        return textureNorth;
    }

    /**
     * Gets the texture identifier for the south face.
     * 
     * @return The texture identifier
     */
    public String getTextureSouth() {
        return textureSouth;
    }

    /**
     * Gets the texture identifier for the east face.
     * 
     * @return The texture identifier
     */
    public String getTextureEast() {
        return textureEast;
    }

    /**
     * Gets the texture identifier for the west face.
     * 
     * @return The texture identifier
     */
    public String getTextureWest() {
        return textureWest;
    }

    /**
     * Gets the texture identifier for the top face.
     * 
     * @return The texture identifier
     */
    public String getTextureTop() {
        return textureTop;
    }

    /**
     * Gets the texture identifier for the bottom face.
     * 
     * @return The texture identifier
     */
    public String getTextureBottom() {
        return textureBottom;
    }

    /**
     * Gets the draw type enum value.
     * 
     * @return The draw type enum (Cube, Model, CubeWithModel, GizmoCube, or Empty)
     */
    public DrawType getDrawType() {
        return drawType;
    }

    /**
     * Gets the custom model path (for foliage/model blocks).
     * 
     * @return The custom model path, or null if not a custom model block
     */
    public String getCustomModel() {
        return customModel;
    }

    /**
     * Gets the opacity enum value.
     * 
     * @return The opacity enum (Solid, Semitransparent, Cutout, or Transparent)
     */
    public Opacity getOpacity() {
        return opacity;
    }

    /**
     * Gets the light emission value (0-15).
     * 
     * @return The light emission value
     */
    public int getLightEmission() {
        return lightEmission;
    }

    /**
     * Gets the block material enum value.
     * 
     * @return The block material enum (Empty or Solid)
     */
    public BlockMaterial getMaterial() {
        return material;
    }

    /**
     * Gets additional metadata.
     * 
     * @return A defensive copy of the metadata map
     */
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata); // Return defensive copy
    }

    /**
     * Gets a specific metadata value.
     * 
     * @param key The metadata key
     * @return The metadata value, or null if not found
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Builder for AssetMapPayload with sensible defaults.
     */
    public static class Builder {
        private final int blockId;
        private String textureNorth = "default";
        private String textureSouth = "default";
        private String textureEast = "default";
        private String textureWest = "default";
        private String textureTop = "default";
        private String textureBottom = "default";
        private DrawType drawType = DrawType.Cube;
        private String customModel = null;
        private Opacity opacity = Opacity.Solid;
        private int lightEmission = 0;
        private BlockMaterial material = BlockMaterial.Solid;
        private Map<String, Object> metadata = new HashMap<>();

        /**
         * Creates a new builder for the given block ID.
         * 
         * @param blockId The block ID
         */
        public Builder(int blockId) {
            this.blockId = blockId;
        }

        /**
         * Sets the north face texture.
         * 
         * @param texture The texture identifier
         * @return This builder instance
         */
        public Builder textureNorth(String texture) {
            this.textureNorth = texture != null ? texture : "default";
            return this;
        }

        /**
         * Sets the south face texture.
         * 
         * @param texture The texture identifier
         * @return This builder instance
         */
        public Builder textureSouth(String texture) {
            this.textureSouth = texture != null ? texture : "default";
            return this;
        }

        /**
         * Sets the east face texture.
         * 
         * @param texture The texture identifier
         * @return This builder instance
         */
        public Builder textureEast(String texture) {
            this.textureEast = texture != null ? texture : "default";
            return this;
        }

        /**
         * Sets the west face texture.
         * 
         * @param texture The texture identifier
         * @return This builder instance
         */
        public Builder textureWest(String texture) {
            this.textureWest = texture != null ? texture : "default";
            return this;
        }

        /**
         * Sets the top face texture.
         * 
         * @param texture The texture identifier
         * @return This builder instance
         */
        public Builder textureTop(String texture) {
            this.textureTop = texture != null ? texture : "default";
            return this;
        }

        /**
         * Sets the bottom face texture.
         * 
         * @param texture The texture identifier
         * @return This builder instance
         */
        public Builder textureBottom(String texture) {
            this.textureBottom = texture != null ? texture : "default";
            return this;
        }

        /**
         * Sets the draw type enum value.
         * 
         * @param drawType The draw type enum (Cube, Model, CubeWithModel, GizmoCube, or
         *                 Empty)
         * @return This builder instance
         */
        public Builder drawType(DrawType drawType) {
            this.drawType = drawType != null ? drawType : DrawType.Cube;
            return this;
        }

        /**
         * Sets the custom model path (for foliage/model blocks).
         * 
         * @param customModel The custom model path, or null if not a custom model block
         * @return This builder instance
         */
        public Builder customModel(String customModel) {
            this.customModel = customModel;
            return this;
        }

        /**
         * Sets the opacity enum value.
         * 
         * @param opacity The opacity enum (Solid, Semitransparent, Cutout, or
         *                Transparent)
         * @return This builder instance
         */
        public Builder opacity(Opacity opacity) {
            this.opacity = opacity != null ? opacity : Opacity.Solid;
            return this;
        }

        /**
         * Sets the light emission value (clamped to 0-15).
         * 
         * @param lightEmission The light emission value
         * @return This builder instance
         */
        public Builder lightEmission(int lightEmission) {
            // Clamp to valid range [0, 15]
            this.lightEmission = Math.max(0, Math.min(15, lightEmission));
            return this;
        }

        /**
         * Sets the block material enum value.
         * 
         * @param material The block material enum (Empty or Solid)
         * @return This builder instance
         */
        public Builder material(BlockMaterial material) {
            this.material = material != null ? material : BlockMaterial.Solid;
            return this;
        }

        /**
         * Sets the metadata map.
         * 
         * @param metadata The metadata map
         * @return This builder instance
         */
        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                this.metadata = new HashMap<>(metadata);
            }
            return this;
        }

        /**
         * Adds a metadata entry.
         * 
         * @param key   The metadata key
         * @param value The metadata value
         * @return This builder instance
         */
        public Builder addMetadata(String key, Object value) {
            if (key != null && value != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        /**
         * Builds the AssetMapPayload instance.
         * 
         * @return The configured AssetMapPayload
         */
        public AssetMapPayload build() {
            return new AssetMapPayload(this);
        }
    }
}
