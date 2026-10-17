package com.tomilabs.lorasignalmap.offline;

import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.osmdroid.tileprovider.MapTileProviderArray;
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.MapTileIndex;

/** osmdroid tile module that renders tiles on the phone from the offline map. */
public final class VectorTileModule extends MapTileModuleProviderBase {
    public static final ITileSource SOURCE = new XYTileSource("OfflineVector", 0, 22, 256, ".png",
            new String[]{"https://offline.invalid/"}, "© OpenStreetMap contributors");

    private final Resources res;
    private final int tilePx;
    private final float density;
    private volatile OfflineMap map;

    public VectorTileModule(Resources res, OfflineMap map) {
        super(2, 60);
        this.res = res;
        this.density = res.getDisplayMetrics().density;
        // tiles are shown at 256 dp; render them at device resolution so text is sharp
        this.tilePx = Math.max(256, Math.min(768, Math.round(256 * density)));
        this.map = map;
    }

    /** A provider for MapView.setTileProvider(). */
    public static MapTileProviderArray provider(Resources res, OfflineMap map) {
        return new MapTileProviderArray(SOURCE, null, new MapTileModuleProviderBase[]{new VectorTileModule(res, map)});
    }

    @Override
    protected String getName() {
        return "Offline vector map";
    }

    @Override
    protected String getThreadGroupName() {
        return "offlinevector";
    }

    @Override
    public boolean getUsesDataConnection() {
        return false;
    }

    @Override
    public int getMinimumZoomLevel() {
        return 0;
    }

    @Override
    public int getMaximumZoomLevel() {
        return 22;
    }

    @Override
    public void setTileSource(ITileSource tileSource) {
        // fixed source
    }

    @Override
    public TileLoader getTileLoader() {
        return new TileLoader() {
            @Override
            public Drawable loadTile(long pMapTileIndex) {
                int z = MapTileIndex.getZoom(pMapTileIndex);
                int x = MapTileIndex.getX(pMapTileIndex);
                int y = MapTileIndex.getY(pMapTileIndex);
                return new BitmapDrawable(res, VectorTileRenderer.render(map, z, x, y, tilePx, density));
            }
        };
    }
}
