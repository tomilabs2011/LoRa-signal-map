package com.tomilabs.lorasignalmap.map;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;

/** Built-in map styles. CARTO tiles are requested in @2x for sharp text on high-DPI screens. */
public final class MapStyles {
    public static final String AUTO = "auto";
    public static final String OFFLINE = "offline";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String OSM = "osm";

    private static final String CARTO_COPYRIGHT = "© OpenStreetMap contributors © CARTO";

    private static final ITileSource CARTO_LIGHT = new XYTileSource("CartoPositron2x", 0, 20, 256, "@2x.png",
            new String[]{
                    "https://a.basemaps.cartocdn.com/light_all/",
                    "https://b.basemaps.cartocdn.com/light_all/",
                    "https://c.basemaps.cartocdn.com/light_all/",
                    "https://d.basemaps.cartocdn.com/light_all/"},
            CARTO_COPYRIGHT);

    private static final ITileSource CARTO_DARK = new XYTileSource("CartoDarkMatter2x", 0, 20, 256, "@2x.png",
            new String[]{
                    "https://a.basemaps.cartocdn.com/dark_all/",
                    "https://b.basemaps.cartocdn.com/dark_all/",
                    "https://c.basemaps.cartocdn.com/dark_all/",
                    "https://d.basemaps.cartocdn.com/dark_all/"},
            CARTO_COPYRIGHT);

    private MapStyles() {
    }

    public static ITileSource source(String style) {
        if (DARK.equals(style)) {
            return CARTO_DARK;
        }
        if (OSM.equals(style)) {
            return TileSourceFactory.MAPNIK;
        }
        return CARTO_LIGHT;
    }

    public static boolean isDark(String style) {
        return DARK.equals(style);
    }
}
