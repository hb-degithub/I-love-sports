package com.vloc.motion.core;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * 支持 {x}/{y}/{z} 占位符的瓦片源。
 *
 * <p>osmdroid 自带的 {@code XYTileSource} 只会把 "baseUrl + z/x/y + 后缀" 拼起来，
 * 并不会替换占位符 —— 用它加载高德这类 "?x={x}&y={y}&z={z}" 的接口会拼出非法 URL（全部 404，
 * 地图只剩网格）。这里覆盖 {@link #getTileURLString(long)} 做真正的占位符替换。
 */
public class PlaceholderTileSource extends OnlineTileSourceBase {

    public PlaceholderTileSource(String name, int minZoom, int maxZoom, int tileSize,
                                 String imageFilenameEnding, String[] baseUrls) {
        super(name, minZoom, maxZoom, tileSize, imageFilenameEnding, baseUrls);
    }

    @Override
    public String getTileURLString(long tileIndex) {
        return getBaseUrl()
                .replace("{x}", Integer.toString(MapTileIndex.getX(tileIndex)))
                .replace("{y}", Integer.toString(MapTileIndex.getY(tileIndex)))
                .replace("{z}", Integer.toString(MapTileIndex.getZoom(tileIndex)));
    }
}
