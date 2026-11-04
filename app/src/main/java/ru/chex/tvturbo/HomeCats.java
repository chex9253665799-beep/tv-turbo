package ru.chex.tvturbo;

import java.util.HashMap;
import java.util.Map;

/** Раскладка приложений по разделам главного экрана: известные пакеты + подсказки по названию. */
public final class HomeCats {

    public static final String MOVIES = "Кино и сериалы", TV = "ТВ-каналы", KIDS = "Детям",
            MUSIC = "Музыка", VIDEO = "Видео", TOOLS = "Полезное", GAMES = "Игры", OTHER = "Другие";

    public static final String[] ORDER = {MOVIES, TV, VIDEO, KIDS, MUSIC, GAMES, TOOLS, OTHER};

    private static final Map<String, String> KNOWN = new HashMap<>();
    static {
        String[][] k = {
            // кино и сериалы
            {"ru.ivi.client", MOVIES}, {"ru.kinopoisk.tv", MOVIES}, {"ru.kinopoisk", MOVIES}, {"ru.okko.tv", MOVIES},
            {"ru.okko.androidtv", MOVIES}, {"ru.rt.video.app.tv", MOVIES}, {"ru.mts.mtstv", MOVIES}, {"ru.kion.tv", MOVIES},
            {"ru.start.androidmobile", MOVIES}, {"ru.start.tv", MOVIES}, {"com.more.tv", MOVIES}, {"ru.more.play", MOVIES},
            {"ru.premier.tv", MOVIES}, {"gpm.tnt_premier", MOVIES}, {"com.amediateka.androidtv", MOVIES},
            {"com.netflix.ninja", MOVIES}, {"com.amazon.amazonvideo.livingroom", MOVIES}, {"tv.zona", MOVIES},
            {"com.mitv.videoplayer", MOVIES},
            // ТВ
            {"ru.limehd.androidtv", TV}, {"limehd.ru.ctv", TV}, {"ru.tv1.android.tv", TV}, {"ru.smotrim.tv", TV},
            {"com.vk.tvvideo", VIDEO}, {"com.vk.vktv", VIDEO}, {"ru.rutube.app.tv", VIDEO}, {"ru.rutube.rutubetv", VIDEO},
            {"com.google.android.youtube.tv", VIDEO}, {"com.google.android.youtube", VIDEO}, {"ru.yandex.androidkeyboard", TOOLS},
            {"com.xiaomi.mitv.tvplayer", TV}, {"com.android.tv", TV}, {"com.droidlogic.tvsource", TV},
            // детям
            {"com.google.android.youtube.tvkids", KIDS}, {"ru.mult.tv", KIDS}, {"ru.smeshariki.app", KIDS},
            // музыка
            {"ru.yandex.music", MUSIC}, {"com.google.android.youtube.tvmusic", MUSIC}, {"com.vk.music", MUSIC},
            {"com.zvuk.tv", MUSIC}, {"com.spotify.tv.android", MUSIC},
            // полезное
            {"ru.vk.store.tv", TOOLS}, {"ru.vk.store", TOOLS}, {"com.android.vending", TOOLS}, {"ru.chex.tvturbo", TOOLS},
            {"com.phlox.tvwebbrowser", TOOLS}, {"org.localsend.localsend_app", TOOLS}, {"com.yablio.sendfilestotv", TOOLS},
            {"com.android.tv.settings", TOOLS}, {"com.google.android.apps.mediashell", TOOLS},
        };
        for (String[] e : k) KNOWN.put(e[0], e[1]);
    }

    public static String of(String pkg, String label, boolean isGame) {
        String c = KNOWN.get(pkg);
        if (c != null) return c;
        if (isGame) return GAMES;
        String s = (pkg + " " + label).toLowerCase();
        if (s.matches(".*(kino|movie|film|serial|кино|фильм|сериал|vod|cinema).*")) return MOVIES;
        if (s.matches(".*(kids|детск|мульт|cartoon|baby).*")) return KIDS;
        if (s.matches(".*(music|radio|музык|радио|audio|podcast).*")) return MUSIC;
        if (s.matches(".*(iptv|\\btv\\b|channel|каналы|эфир|телевид).*")) return TV;
        if (s.matches(".*(video|видео|tube|stream).*")) return VIDEO;
        if (s.matches(".*(game|игр).*")) return GAMES;
        if (s.matches(".*(browser|браузер|file|файл|store|магазин|settings|настрой|weather|погод).*")) return TOOLS;
        return OTHER;
    }

    private HomeCats() { }
}
