package com.dopachiru.linux.platform

/**
 * ブラウザとみなすアプリ([AppIds] の形)。
 *
 * URL の規則は**これらが前面にあるときにしか効かせない**。
 * 拡張が最後に報せてきた URL は、ブラウザから離れても手元に残り続けるので、
 * ここで絞らないと、エディタを開いている最中に
 * 「YouTube のショートを見ている」と判定されうる。
 *
 * 同じブラウザでも、deb・Flatpak・Debian の ESR で名前が違う。
 */
val Browsers: Set<String> = setOf(
    "firefox",
    "firefox-esr",
    "org.mozilla.firefox",
    "google-chrome",
    "google-chrome-stable",
    "com.google.chrome",
    "chromium",
    "chromium-browser",
    "org.chromium.chromium",
    "brave-browser",
    "com.brave.browser",
    "microsoft-edge",
    "com.microsoft.edge",
    "vivaldi-stable",
    "com.vivaldi.vivaldi",
    "opera",
    "com.opera.opera",
    "librewolf",
    "io.gitlab.librewolf-community",
    "floorp",
    "one.ablaze.floorp",
    "zen",
    "app.zen_browser.zen",
)
