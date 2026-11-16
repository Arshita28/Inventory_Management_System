package com.inventory.preview;

/** Small, consistently sized decorative icons; links keep their own accessible labels. */
final class UiIcons {
    static final String ARROW_UP_RIGHT = icon("M7 17 17 7M7 7h10v10");
    static final String DOWNLOAD = icon("M12 3v12M7 10l5 5 5-5M5 17v4h14v-4");
    static final String CHEVRON_DOWN = icon("m6 9 6 6 6-6");

    private UiIcons() { }

    private static String icon(String path) {
        return "<svg class='ui-icon' viewBox='0 0 24 24' aria-hidden='true' focusable='false'><path d='" + path + "'/></svg>";
    }
}
