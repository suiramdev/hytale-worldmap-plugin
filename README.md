# Worldmap Plugin (Hytale)

This plugin streams chunk data to a web worker and now follows the manifest‑driven, content‑addressable asset flow.

**Asset Map Sync**
1. `POST /api/asset-map`  
   Request body:
   ```json
   {
     "assetMapHash": "<sha256>",
     "assetMap": [ ... ]
   }
   ```
   The `assetMapHash` returned from this sync is the authoritative hash used for chunk submission.

**Chunk Submission**
1. `POST /api/chunks/process`  
   Required headers:
   - `Authorization: Bearer <apiKey>`
   - `X-Chunk-Format-Version: <int>`
   - `X-Asset-Map-Hash: <assetMapHash>`

   Responses:
   - `202 Accepted` → `{ "jobId": "..." }` (queued)
   - `409 Conflict` → Missing assets manifest (see below)

**Missing Assets Upload**
1. `POST /api/assets/slices` (API‑key auth)  
   Multipart fields:
   - `assetMapHash`: `<assetMapHash>`
   - `manifest`: JSON array (order matters)
   - `files`: repeated file parts; **file order must match manifest order**

   Example `manifest` field:
   ```json
   [
     { "path": "stone.png", "contentHash": "<sha256>" },
     { "path": "Grass.blockymodel", "contentHash": "<sha256>" }
   ]
   ```

**End‑to‑End Flow**
1. Submit chunk with `X-Asset-Map-Hash`.
2. If `409`, resolve missing assets → compute SHA‑256 → upload slices.
3. Re‑submit the same chunk and proceed on `202`.
4. If the server reports a different `assetMapHash`, refresh via `POST /api/asset-map` and retry.

## Asset Retrieval Fallback Order
When resolving missing assets, the plugin uses the following order (with examples):
1. `AssetRegistry.getOptional(assetId, Asset.class)`  
   If present, use `Asset.getPack().getRoot()` to resolve the file path and read bytes.
2. `Assets.zip` (configured by `AssetsZipPath`)
3. Embedded plugin assets (classpath) when `IncludesAssetPack=true`

If an asset cannot be resolved, the plugin logs a warning and skips that asset for upload.

## Missing Asset Manifest Shape
The `409` response includes a manifest array with entries such as:
```json
{
  "assetId": "base:stone",
  "path": "stone.png",
  "assetMapHash": "<sha256>",
  "hashAlgorithm": "SHA-256",
  "contentTypeHint": "image/png"
}
```
The plugin uses `contentTypeHint` when present and always computes `SHA-256` for `contentHash`.
