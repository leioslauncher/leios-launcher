package tv.leios.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Every Leios setting in one place, for search: its name as the menu shows it, a short description,
 * where it lives, and other words people use for it. Picking one opens its menu in Customization with
 * the row highlighted.
 *
 * <p>All of it is text in res/values/strings_settings.xml: a setting's name there is the same string
 * its menu row uses, so search finds the row by that string, in any language. {@link #localize}
 * reads the texts for the language Leios shows.
 *
 * <p>Adding a setting: add its name, _desc and _words to strings_settings.xml, use the name for its
 * row in MainActivity, then add one {@link #e} line here. A new menu gets a {@link #menu} line too,
 * and a case in MainActivity.openSettingMenu. SettingsIndexTest fails when a row or a menu is missing.
 */
final class SettingsIndex {

    private SettingsIndex() {
    }

    /** A menu that holds settings. {@code method} is the MainActivity method that opens it. */
    static final class Menu {
        final String id, parent, method;
        /** String resource: the menu's title. */
        final int title;
        String titleText = "";

        Menu(String id, int title, String parent, String method) {
            this.id = id;
            this.title = title;
            this.parent = parent;
            this.method = method;
        }
    }

    /** One setting: a row in a menu. The ints are string resources ({@code words}: 0 for none). */
    static final class Entry {
        final String menu;
        final int label, description, words;
        String labelText = "", descriptionText = "";
        String[] wordTexts = new String[0];

        Entry(String menu, int label, int description, int words) {
            this.menu = menu;
            this.label = label;
            this.description = description;
            this.words = words;
        }

        /** Every name of this setting, made ready for matching once (search runs on each key press). */
        private SearchRank.Prepared pLabel, pMenu, pDescription;
        private SearchRank.Prepared[] pSynonyms;

        private void prepare() {
            if (pLabel != null) return;
            pLabel = SearchRank.prepare(labelText);
            pMenu = SearchRank.prepare(MENUS.get(menu).titleText);
            pDescription = SearchRank.prepare(descriptionText);
            pSynonyms = new SearchRank.Prepared[wordTexts.length];
            for (int i = 0; i < wordTexts.length; i++) pSynonyms[i] = SearchRank.prepare(wordTexts[i]);
        }

        /** Where it is, e.g. "Look & sound  ›  Sounds". */
        String crumb() {
            return SettingsIndex.crumb(menu);
        }
    }

    /** The seven sections of Customization, in order (MainActivity's SEC_* numbers). */
    static final String[] SECTIONS = {"background", "home", "apps", "look", "projector", "lock", "system"};

    static final Map<String, Menu> MENUS = new LinkedHashMap<>();
    static final List<Entry> ENTRIES = new ArrayList<>();
    /** What the texts were read for (a locale), and the separator between a crumb's parts. */
    private static Object localizedFor;
    private static String crumbSeparator = "  ›  ";

    private static void menu(String id, int title, String parent, String method) {
        MENUS.put(id, new Menu(id, title, parent, method));
    }

    private static void e(String menu, int label, int description, int words) {
        if (!MENUS.containsKey(menu)) throw new IllegalStateException("No menu " + menu);
        ENTRIES.add(new Entry(menu, label, description, words));
    }

    /**
     * Reads every name, description and search word with {@code text} (Context::getString), once per
     * {@code locale}: search then matches what people type in the language Leios shows.
     */
    static synchronized void localize(Object locale, IntFunction<String> text) {
        if (locale != null && locale.equals(localizedFor)) return;
        crumbSeparator = text.apply(R.string.crumb_separator);
        for (Menu m : MENUS.values()) m.titleText = text.apply(m.title);
        for (Entry e : ENTRIES) {
            e.labelText = text.apply(e.label);
            e.descriptionText = text.apply(e.description);
            List<String> words = new ArrayList<>();
            if (e.words != 0) {
                // Comma-separated; Chinese and Japanese often write a wide comma, Arabic its own.
                for (String w : text.apply(e.words).split("[,，、،]")) if (!w.trim().isEmpty()) words.add(w.trim());
            }
            e.wordTexts = words.toArray(new String[0]);
            e.pLabel = null;
        }
        localizedFor = locale;
    }

    static {
        menu("background", R.string.menu_background, null, "openBackgroundMenu");
        menu("background.slideshow", R.string.menu_background_slideshow, "background", "openSlideshowMenu");
        menu("background.adjust", R.string.menu_background_adjust, "background", "openAdjustMenu");
        menu("home", R.string.menu_home, null, "openHomeMenu");
        menu("home.layout", R.string.menu_home_layout, "home", "openLayoutMenu");
        menu("home.categories", R.string.menu_home_categories, "home", "openCategoriesMenu");
        menu("home.dock", R.string.menu_home_dock, "home", "openDockMenu");
        menu("home.weather", R.string.menu_home_weather, "home", "openWeatherMenu");
        menu("apps", R.string.menu_apps, null, "openAppsMenu");
        menu("look", R.string.menu_look, null, "openLookMenu");
        menu("look.appearance", R.string.menu_look_appearance, "look", "openAppearanceMenu");
        menu("look.font", R.string.menu_look_font, "look", "openFontMenu");
        menu("look.clock", R.string.menu_look_clock, "look", "openClockMenu");
        menu("look.bar", R.string.menu_look_bar, "look", "openBarMenu");
        menu("look.bar.notif", R.string.menu_look_bar_notif, "look.bar", "openNotifSetting");
        menu("look.tiles", R.string.menu_look_tiles, "look", "openTilesMenu");
        menu("look.tiles.advanced", R.string.menu_look_tiles_advanced, "look.tiles", "openTilesAdvanced");
        menu("look.themes", R.string.menu_look_themes, "look", "openThemesMenu");
        menu("look.sounds", R.string.menu_look_sounds, "look", "openSoundsMenu");
        menu("look.sounds.output", R.string.menu_look_sounds_output, "look.sounds", "openSoundsOutputMenu");
        menu("look.sounds.quiet", R.string.menu_look_sounds_quiet, "look.sounds", "openSoundsQuietMenu");
        menu("projector", R.string.menu_projector, null, "openProjectorMenu");
        menu("projector.startup", R.string.menu_projector_startup, "projector", "openStartupMenu");
        menu("projector.idle", R.string.menu_projector_idle, "projector", "openIdleMenu");
        menu("lock", R.string.menu_lock, null, "openLockMenu");
        menu("lock.shortcuts", R.string.menu_lock_shortcuts, "lock", "openShortcutsMenu");
        menu("system", R.string.menu_system, null, "openBackupMenu");
        menu("system.search", R.string.menu_system_search, "system", "openSearchSettings");
        menu("system.about", R.string.menu_system_about, "system", "openAboutMenu");

        // Background
        e("background", R.string.set_background_colours, R.string.set_background_colours_desc, R.string.set_background_colours_words);
        e("background", R.string.set_background_shapes, R.string.set_background_shapes_desc, R.string.set_background_shapes_words);
        e("background", R.string.set_background_photo, R.string.set_background_photo_desc, R.string.set_background_photo_words);
        e("background", R.string.set_background_slideshow, R.string.set_background_slideshow_desc, R.string.set_background_slideshow_words);
        e("background", R.string.set_background_video, R.string.set_background_video_desc, R.string.set_background_video_words);
        e("background", R.string.set_background_photo_of_the_day, R.string.set_background_photo_of_the_day_desc, R.string.set_background_photo_of_the_day_words);
        e("background", R.string.set_background_adjust, R.string.set_background_adjust_desc, R.string.set_background_adjust_words);
        e("background.slideshow", R.string.set_background_slideshow_photos_from, R.string.set_background_slideshow_photos_from_desc, R.string.set_background_slideshow_photos_from_words);
        e("background.slideshow", R.string.set_background_slideshow_change_every, R.string.set_background_slideshow_change_every_desc, R.string.set_background_slideshow_change_every_words);
        e("background.adjust", R.string.set_background_adjust_brightness, R.string.set_background_adjust_brightness_desc, R.string.set_background_adjust_brightness_words);
        e("background.adjust", R.string.set_background_adjust_contrast, R.string.set_background_adjust_contrast_desc, 0);
        e("background.adjust", R.string.set_background_adjust_saturation, R.string.set_background_adjust_saturation_desc, R.string.set_background_adjust_saturation_words);
        e("background.adjust", R.string.set_background_adjust_blur, R.string.set_background_adjust_blur_desc, R.string.set_background_adjust_blur_words);
        e("background.adjust", R.string.set_background_adjust_reset_adjustments, R.string.set_background_adjust_reset_adjustments_desc, R.string.set_background_adjust_reset_adjustments_words);

        // Home layout
        e("home", R.string.set_home_categories, R.string.set_home_categories_desc, R.string.set_home_categories_words);
        e("home", R.string.set_home_dock, R.string.set_home_dock_desc, R.string.set_home_dock_words);
        e("home", R.string.set_home_layout, R.string.set_home_layout_desc, R.string.set_home_layout_words);
        e("home", R.string.set_home_home_button_goes_to, R.string.set_home_home_button_goes_to_desc, R.string.set_home_home_button_goes_to_words);
        e("home", R.string.set_home_weather, R.string.set_home_weather_desc, R.string.set_home_weather_words);
        e("home.layout", R.string.set_home_layout_tiles_per_row, R.string.set_home_layout_tiles_per_row_desc, R.string.set_home_layout_tiles_per_row_words);
        e("home.layout", R.string.set_home_layout_tile_spacing, R.string.set_home_layout_tile_spacing_desc, R.string.set_home_layout_tile_spacing_words);
        e("home.layout", R.string.set_home_layout_row_spacing, R.string.set_home_layout_row_spacing_desc, R.string.set_home_layout_row_spacing_words);
        e("home.layout", R.string.set_home_layout_side_margins, R.string.set_home_layout_side_margins_desc, R.string.set_home_layout_side_margins_words);
        e("home.layout", R.string.set_home_layout_reset_layout, R.string.set_home_layout_reset_layout_desc, R.string.set_home_layout_reset_layout_words);
        e("home.categories", R.string.set_home_categories_new_category, R.string.set_home_categories_new_category_desc, R.string.set_home_categories_new_category_words);
        e("home.categories", R.string.set_home_categories_all_apps_row, R.string.set_home_categories_all_apps_row_desc, R.string.set_home_categories_all_apps_row_words);
        e("home.categories", R.string.set_home_categories_inputs_row_hdmi, R.string.set_home_categories_inputs_row_hdmi_desc, R.string.set_home_categories_inputs_row_hdmi_words);
        e("home.categories", R.string.set_home_categories_empty_categories, R.string.set_home_categories_empty_categories_desc, R.string.set_home_categories_empty_categories_words);
        e("home.categories", R.string.set_home_categories_reset_categories, R.string.set_home_categories_reset_categories_desc, R.string.set_home_categories_reset_categories_words);
        e("home.dock", R.string.set_home_dock_rearrange, R.string.set_home_dock_rearrange_desc, R.string.set_home_dock_rearrange_words);
        e("home.dock", R.string.set_home_dock_reset_dock_to_default, R.string.set_home_dock_reset_dock_to_default_desc, R.string.set_home_dock_reset_dock_to_default_words);
        e("home.weather", R.string.set_home_weather_show_weather, R.string.set_home_weather_show_weather_desc, R.string.set_home_weather_show_weather_words);
        e("home.weather", R.string.set_home_weather_city, R.string.set_home_weather_city_desc, R.string.set_home_weather_city_words);
        e("home.weather", R.string.set_home_weather_units, R.string.set_home_weather_units_desc, R.string.set_home_weather_units_words);
        e("home.weather", R.string.set_home_weather_reset_weather, R.string.set_home_weather_reset_weather_desc, R.string.set_home_weather_reset_weather_words);

        // Apps
        e("apps", R.string.set_apps_show_or_hide_apps, R.string.set_apps_show_or_hide_apps_desc, R.string.set_apps_show_or_hide_apps_words);
        e("apps", R.string.set_apps_arrange_apps, R.string.set_apps_arrange_apps_desc, R.string.set_apps_arrange_apps_words);
        e("apps", R.string.set_apps_sort_and_categories, R.string.set_apps_sort_and_categories_desc, R.string.set_apps_sort_and_categories_words);
        e("apps", R.string.set_apps_icon_pack, R.string.set_apps_icon_pack_desc, R.string.set_apps_icon_pack_words);
        e("apps", R.string.set_apps_reset_names_tiles_and_icons, R.string.set_apps_reset_names_tiles_and_icons_desc, R.string.set_apps_reset_names_tiles_and_icons_words);

        // Look & sound
        e("look", R.string.set_look_appearance, R.string.set_look_appearance_desc, R.string.set_look_appearance_words);
        e("look", R.string.set_look_font_and_text_size, R.string.set_look_font_and_text_size_desc, R.string.set_look_font_and_text_size_words);
        e("look", R.string.set_look_clock, R.string.set_look_clock_desc, R.string.set_look_clock_words);
        e("look", R.string.set_look_top_bar, R.string.set_look_top_bar_desc, R.string.set_look_top_bar_words);
        e("look", R.string.set_look_tiles, R.string.set_look_tiles_desc, R.string.set_look_tiles_words);
        e("look", R.string.set_look_themes, R.string.set_look_themes_desc, R.string.set_look_themes_words);
        e("look", R.string.set_look_sounds, R.string.set_look_sounds_desc, R.string.set_look_sounds_words);
        e("look.appearance", R.string.set_look_appearance_mode, R.string.set_look_appearance_mode_desc, R.string.set_look_appearance_mode_words);
        e("look.appearance", R.string.set_look_appearance_accent_colour, R.string.set_look_appearance_accent_colour_desc, R.string.set_look_appearance_accent_colour_words);
        e("look.font", R.string.set_look_font_font, R.string.set_look_font_font_desc, R.string.set_look_font_font_words);
        e("look.font", R.string.set_look_font_text_size, R.string.set_look_font_text_size_desc, R.string.set_look_font_text_size_words);
        e("look.font", R.string.set_look_font_reset_font_and_size, R.string.set_look_font_reset_font_and_size_desc, R.string.set_look_font_reset_font_and_size_words);
        e("look.clock", R.string.set_look_clock_format, R.string.set_look_clock_format_desc, R.string.set_look_clock_format_words);
        e("look.clock", R.string.set_look_clock_size, R.string.set_look_clock_size_desc, R.string.set_look_clock_size_words);
        e("look.clock", R.string.set_look_clock_position, R.string.set_look_clock_position_desc, R.string.set_look_clock_position_words);
        e("look.clock", R.string.set_look_clock_date, R.string.set_look_clock_date_desc, R.string.set_look_clock_date_words);
        e("look.clock", R.string.set_look_clock_greeting, R.string.set_look_clock_greeting_desc, R.string.set_look_clock_greeting_words);
        e("look.clock", R.string.set_look_clock_shadow, R.string.set_look_clock_shadow_desc, R.string.set_look_clock_shadow_words);
        e("look.clock", R.string.set_look_clock_reset_clock, R.string.set_look_clock_reset_clock_desc, R.string.set_look_clock_reset_clock_words);
        e("look.bar", R.string.set_look_bar_inputs_button, R.string.set_look_bar_inputs_button_desc, R.string.set_look_bar_inputs_button_words);
        e("look.bar", R.string.set_look_bar_search_button, R.string.set_look_bar_search_button_desc, R.string.set_look_bar_search_button_words);
        e("look.bar", R.string.set_look_bar_wi_fi, R.string.set_look_bar_wi_fi_desc, R.string.set_look_bar_wi_fi_words);
        e("look.bar", R.string.set_look_bar_notifications, R.string.set_look_bar_notifications_desc, R.string.set_look_bar_notifications_words);
        e("look.bar", R.string.set_look_bar_settings_button, R.string.set_look_bar_settings_button_desc, R.string.set_look_bar_settings_button_words);
        e("look.bar", R.string.set_look_bar_size, R.string.set_look_bar_size_desc, R.string.set_look_bar_size_words);
        e("look.bar", R.string.set_look_bar_reset_top_bar, R.string.set_look_bar_reset_top_bar_desc, R.string.set_look_bar_reset_top_bar_words);
        e("look.bar.notif", R.string.set_look_bar_notif_show_the_bell, R.string.set_look_bar_notif_show_the_bell_desc, R.string.set_look_bar_notif_show_the_bell_words);
        e("look.bar.notif", R.string.set_look_bar_notif_notification_access, R.string.set_look_bar_notif_notification_access_desc, R.string.set_look_bar_notif_notification_access_words);
        e("look.tiles", R.string.set_look_tiles_style, R.string.set_look_tiles_style_desc, R.string.set_look_tiles_style_words);
        e("look.tiles", R.string.set_look_tiles_show_apps_as, R.string.set_look_tiles_show_apps_as_desc, R.string.set_look_tiles_show_apps_as_words);
        e("look.tiles", R.string.set_look_tiles_app_names, R.string.set_look_tiles_app_names_desc, R.string.set_look_tiles_app_names_words);
        e("look.tiles", R.string.set_look_tiles_advanced, R.string.set_look_tiles_advanced_desc, R.string.set_look_tiles_advanced_words);
        e("look.tiles", R.string.set_look_tiles_reset_tiles, R.string.set_look_tiles_reset_tiles_desc, R.string.set_look_tiles_reset_tiles_words);
        e("look.tiles.advanced", R.string.set_look_tiles_advanced_corners, R.string.set_look_tiles_advanced_corners_desc, R.string.set_look_tiles_advanced_corners_words);
        e("look.tiles.advanced", R.string.set_look_tiles_advanced_focus_colour, R.string.set_look_tiles_advanced_focus_colour_desc, R.string.set_look_tiles_advanced_focus_colour_words);
        e("look.tiles.advanced", R.string.set_look_tiles_advanced_icon_tile_background, R.string.set_look_tiles_advanced_icon_tile_background_desc, R.string.set_look_tiles_advanced_icon_tile_background_words);
        e("look.tiles.advanced", R.string.set_look_tiles_advanced_glow_behind_the_dock, R.string.set_look_tiles_advanced_glow_behind_the_dock_desc, R.string.set_look_tiles_advanced_glow_behind_the_dock_words);
        e("look.themes", R.string.set_look_themes_save_this_look, R.string.set_look_themes_save_this_look_desc, R.string.set_look_themes_save_this_look_words);
        e("look.themes", R.string.set_look_themes_import_from_a_picture, R.string.set_look_themes_import_from_a_picture_desc, R.string.set_look_themes_import_from_a_picture_words);
        e("look.themes", R.string.set_look_themes_undo_last_theme, R.string.set_look_themes_undo_last_theme_desc, R.string.set_look_themes_undo_last_theme_words);
        e("look.sounds", R.string.set_look_sounds_volume, R.string.set_look_sounds_volume_desc, R.string.set_look_sounds_volume_words);
        e("look.sounds", R.string.set_look_sounds_output, R.string.set_look_sounds_output_desc, R.string.set_look_sounds_output_words);
        e("look.sounds", R.string.set_look_sounds_quiet_times, R.string.set_look_sounds_quiet_times_desc, R.string.set_look_sounds_quiet_times_words);
        e("look.sounds", R.string.set_look_sounds_start_up_sound, R.string.set_look_sounds_start_up_sound_desc, R.string.set_look_sounds_start_up_sound_words);
        e("look.sounds", R.string.set_look_sounds_panel_open_and_close, R.string.set_look_sounds_panel_open_and_close_desc, R.string.set_look_sounds_panel_open_and_close_words);
        e("look.sounds", R.string.set_look_sounds_reset_sounds, R.string.set_look_sounds_reset_sounds_desc, R.string.set_look_sounds_reset_sounds_words);
        e("look.sounds.output", R.string.set_look_sounds_output_sound_output, R.string.set_look_sounds_output_sound_output_desc, R.string.set_look_sounds_output_sound_output_words);
        e("look.sounds.output", R.string.set_look_sounds_output_silent_on_bluetooth_or_arc, R.string.set_look_sounds_output_silent_on_bluetooth_or_arc_desc, R.string.set_look_sounds_output_silent_on_bluetooth_or_arc_words);
        e("look.sounds.quiet", R.string.set_look_sounds_quiet_silent_at_bedtime, R.string.set_look_sounds_quiet_silent_at_bedtime_desc, R.string.set_look_sounds_quiet_silent_at_bedtime_words);
        e("look.sounds.quiet", R.string.set_look_sounds_quiet_silent_in_guest_mode, R.string.set_look_sounds_quiet_silent_in_guest_mode_desc, R.string.set_look_sounds_quiet_silent_in_guest_mode_words);

        // Projector & tools
        e("projector", R.string.set_projector_picture, R.string.set_projector_picture_desc, R.string.set_projector_picture_words);
        e("projector", R.string.set_projector_brightness, R.string.set_projector_brightness_desc, R.string.set_projector_brightness_words);
        e("projector", R.string.set_projector_keystone, R.string.set_projector_keystone_desc, R.string.set_projector_keystone_words);
        e("projector", R.string.set_projector_sleep_timer, R.string.set_projector_sleep_timer_desc, R.string.set_projector_sleep_timer_words);
        e("projector", R.string.set_projector_test_patterns, R.string.set_projector_test_patterns_desc, R.string.set_projector_test_patterns_words);
        e("projector", R.string.set_projector_start_up_and_wake, R.string.set_projector_start_up_and_wake_desc, R.string.set_projector_start_up_and_wake_words);
        e("projector", R.string.set_projector_when_home_is_idle, R.string.set_projector_when_home_is_idle_desc, R.string.set_projector_when_home_is_idle_words);
        e("projector.startup", R.string.set_projector_startup_when_it_starts, R.string.set_projector_startup_when_it_starts_desc, R.string.set_projector_startup_when_it_starts_words);
        e("projector.startup", R.string.set_projector_startup_when_it_wakes, R.string.set_projector_startup_when_it_wakes_desc, R.string.set_projector_startup_when_it_wakes_words);
        e("projector.idle", R.string.set_projector_idle_do_this, R.string.set_projector_idle_do_this_desc, R.string.set_projector_idle_do_this_words);
        e("projector.idle", R.string.set_projector_idle_after, R.string.set_projector_idle_after_desc, R.string.set_projector_idle_after_words);

        // Lock & remote
        e("lock", R.string.set_lock_pin_lock, R.string.set_lock_pin_lock_desc, R.string.set_lock_pin_lock_words);
        e("lock", R.string.set_lock_what_it_locks, R.string.set_lock_what_it_locks_desc, R.string.set_lock_what_it_locks_words);
        e("lock", R.string.set_lock_when, R.string.set_lock_when_desc, R.string.set_lock_when_words);
        e("lock", R.string.set_lock_guest_mode, R.string.set_lock_guest_mode_desc, R.string.set_lock_guest_mode_words);
        e("lock", R.string.set_lock_remote_shortcuts, R.string.set_lock_remote_shortcuts_desc, R.string.set_lock_remote_shortcuts_words);
        e("lock.shortcuts", R.string.set_lock_shortcuts_hold, R.string.set_lock_shortcuts_hold_desc, R.string.set_lock_shortcuts_hold_words);
        e("lock.shortcuts", R.string.set_lock_shortcuts_hold_the_settings_button, R.string.set_lock_shortcuts_hold_the_settings_button_desc, R.string.set_lock_shortcuts_hold_the_settings_button_words);
        e("lock.shortcuts", R.string.set_lock_shortcuts_hold_home_for_search, R.string.set_lock_shortcuts_hold_home_for_search_desc, R.string.set_lock_shortcuts_hold_home_for_search_words);

        // System
        e("system", R.string.set_system_search_and_privacy, R.string.set_system_search_and_privacy_desc, R.string.set_system_search_and_privacy_words);
        e("system", R.string.set_system_save_a_backup, R.string.set_system_save_a_backup_desc, R.string.set_system_save_a_backup_words);
        e("system", R.string.set_system_restore_a_backup, R.string.set_system_restore_a_backup_desc, R.string.set_system_restore_a_backup_words);
        e("system", R.string.set_system_automatic_backups, R.string.set_system_automatic_backups_desc, R.string.set_system_automatic_backups_words);
        e("system", R.string.set_system_reset_everything, R.string.set_system_reset_everything_desc, R.string.set_system_reset_everything_words);
        e("system", R.string.set_system_about, R.string.set_system_about_desc, R.string.set_system_about_words);
        e("system.search", R.string.set_system_search_online_title_search, R.string.set_system_search_online_title_search_desc, R.string.set_system_search_online_title_search_words);
        e("system.search", R.string.set_system_search_region, R.string.set_system_search_region_desc, R.string.set_system_search_region_words);
        e("system.search", R.string.set_system_search_titles_not_on_your_apps, R.string.set_system_search_titles_not_on_your_apps_desc, R.string.set_system_search_titles_not_on_your_apps_words);
        e("system.search", R.string.set_system_search_recent_searches, R.string.set_system_search_recent_searches_desc, R.string.set_system_search_recent_searches_words);
        e("system.search", R.string.set_system_search_clear_recent_searches, R.string.set_system_search_clear_recent_searches_desc, R.string.set_system_search_clear_recent_searches_words);
    }

    /** "Look & sound › Sounds" for "look.sounds". */
    static String crumb(String menuId) {
        List<String> parts = new ArrayList<>();
        for (Menu m = MENUS.get(menuId); m != null; m = m.parent == null ? null : MENUS.get(m.parent)) {
            parts.add(m.titleText);
        }
        Collections.reverse(parts);
        return String.join(crumbSeparator, parts);
    }

    /** The section (0–6) a menu belongs to. */
    static int section(String menuId) {
        String top = menuId.contains(".") ? menuId.substring(0, menuId.indexOf('.')) : menuId;
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i].equals(top)) return i;
        return 0;
    }

    /** Settings matching {@code query}, best first. */
    static List<Entry> search(String query, int limit) {
        SearchRank.Prepared q = SearchRank.prepare(query);
        List<Entry> found = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        if (q.text.isEmpty()) return found;
        for (Entry e : ENTRIES) {
            int s = score(q, e);
            if (s <= 0) continue;
            int at = 0;
            while (at < scores.size() && scores.get(at) >= s) at++;
            found.add(at, e);
            scores.add(at, s);
        }
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    static int score(SearchRank.Prepared q, Entry e) {
        e.prepare();
        int best = SearchRank.score(q, e.pLabel);
        for (SearchRank.Prepared s : e.pSynonyms) best = Math.max(best, SearchRank.score(q, s) * 9 / 10);
        best = Math.max(best, SearchRank.score(q, e.pMenu) * 6 / 10);
        // A word in the description counts a little, only as a whole-word start (no "in" in "brightness").
        int d = SearchRank.score(q, e.pDescription);
        if (d >= SearchRank.WORD_START) best = Math.max(best, d * 4 / 10);
        return best;
    }
}
