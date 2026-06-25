package net.kdt.pojavlaunch.cobblemon;

import android.content.Context;
import android.widget.Toast;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public final class CobblemonLegacyInstaller {
    public static final String PROFILE_NAME = "Cobblemon Legacy";
    public static final String MINECRAFT_VERSION = "1.21.1";
    public static final String FABRIC_LOADER_VERSION = "0.19.3";
    public static final String PACK_VERSION = "0.2.1";
    public static final String PACK_URL = "https://github.com/gotardelo/cobblemonlegacy-downloads/releases/download/mobile-full-v0.2.1/CobblemonLegacy-MobileFull-v0.2.1.mrpack";
    public static final String PACK_SHA1 = "43bdf444869ffe34da0897a7785f8e628c96b082";
    private static final String CONTROL_LAYOUT_FILE = "cobblemon-legacy.json";

    private CobblemonLegacyInstaller() {}

    public interface Callback {
        void onSuccess(String profileKey);
    }

    public static void installOrUpdate(Context context, Callback callback) {
        if (ProgressKeeper.hasOngoingTasks()) {
            Toast.makeText(context, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }

        Context appContext = context.getApplicationContext();
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, "Preparando Cobblemon Legacy...");
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String profileKey = installOrUpdateBlocking(appContext);
                Tools.runOnUiThread(() -> {
                    if (callback != null) callback.onSuccess(profileKey);
                    Toast.makeText(appContext, "Cobblemon Legacy pronto para jogar.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Tools.showErrorRemote("Nao foi possivel instalar o Cobblemon Legacy.", e);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            }
        });
    }

    public static String findInstalledProfileKey() {
        LauncherProfiles.load();
        String versionId = getFabricVersionId();
        for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile profile = entry.getValue();
            if (profile == null) continue;
            if (!PROFILE_NAME.equals(profile.name)) continue;
            if (!versionId.equals(profile.lastVersionId)) continue;
            if (profile.gameDir != null && profile.gameDir.contains(PACK_SHA1)) return entry.getKey();
        }
        for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile profile = entry.getValue();
            if (profile != null && PROFILE_NAME.equals(profile.name) && versionId.equals(profile.lastVersionId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String installOrUpdateBlocking(Context context) throws IOException {
        LauncherProfiles.load();
        ModLoader modLoader = new ModLoader(ModLoader.MOD_LOADER_FABRIC, FABRIC_LOADER_VERSION, MINECRAFT_VERSION);
        String profileKey = findInstalledProfileKey();
        MinecraftProfile profile = profileKey == null ? null : LauncherProfiles.mainProfileJson.profiles.get(profileKey);
        if (profileKey == null || !isCurrentPack(profile) || !isPackComplete(profile)) {
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, "Baixando pack Cobblemon Legacy...");
            ModItem item = new ModItem(
                    Constants.SOURCE_MODRINTH,
                    true,
                    "cobblemon-legacy-mobile-full",
                    PROFILE_NAME,
                    "Pack completo Android do Cobblemon Legacy.",
                    null
            );
            ModDetail detail = new ModDetail(
                    item,
                    new String[]{"Mobile Full v" + PACK_VERSION},
                    new String[]{MINECRAFT_VERSION},
                    new String[]{PACK_URL},
                    new String[]{PACK_SHA1}
            );

            ModLoader installedLoader = new ModrinthApi().installMod(detail, 0);
            if (installedLoader != null) modLoader = installedLoader;
            LauncherProfiles.load();
            profileKey = findInstalledProfileKey();
            if (profileKey == null) {
                throw new IOException("O perfil Cobblemon Legacy foi instalado, mas nao foi encontrado.");
            }
        }

        ensureMinecraftVersionJson();
        ensureFabricLoader(modLoader);
        LauncherProfiles.load();
        profileKey = findInstalledProfileKey();
        if (profileKey == null) {
            throw new IOException("O perfil Cobblemon Legacy nao foi encontrado apos instalar o Fabric.");
        }

        ensureControlLayout(context, profileKey);

        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                .apply();
        return profileKey;
    }

    private static boolean isCurrentPack(MinecraftProfile profile) {
        return profile != null && profile.gameDir != null && profile.gameDir.contains(PACK_SHA1);
    }

    private static boolean isPackComplete(MinecraftProfile profile) {
        if (profile == null) return false;
        File gameDir = Tools.getGameDirPath(profile);
        return countFiles(new File(gameDir, "mods"), ".jar") >= 100
                && countFiles(new File(gameDir, "resourcepacks"), ".zip") >= 26
                && new File(gameDir, "options.txt").isFile()
                && new File(gameDir, "servers.dat").isFile();
    }

    private static int countFiles(File dir, String suffix) {
        File[] files = dir.listFiles((ignored, name) -> name.toLowerCase().endsWith(suffix));
        return files == null ? 0 : files.length;
    }

    private static void ensureControlLayout(Context context, String profileKey) throws IOException {
        Tools.copyAssetFile(context, CONTROL_LAYOUT_FILE, Tools.CTRLMAP_PATH, CONTROL_LAYOUT_FILE, true);
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(profileKey);
        if (profile == null) {
            throw new IOException("O perfil Cobblemon Legacy nao foi encontrado para aplicar controles.");
        }
        profile.controlFile = CONTROL_LAYOUT_FILE;
        LauncherProfiles.write();
    }

    private static void ensureMinecraftVersionJson() throws IOException {
        File versionJsonDir = new File(Tools.DIR_HOME_VERSION, MINECRAFT_VERSION);
        File versionJson = new File(versionJsonDir, MINECRAFT_VERSION + ".json");
        if (versionJson.isFile() && versionJson.length() > 0) return;

        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, "Preparando Minecraft " + MINECRAFT_VERSION + "...");
        String versionJsonUrl = findMinecraftVersionJsonUrl();
        String versionJsonContents = DownloadUtils.downloadString(versionJsonUrl);
        FileUtils.ensureDirectory(versionJsonDir);
        Tools.write(versionJson.getAbsolutePath(), versionJsonContents);
    }

    private static String findMinecraftVersionJsonUrl() throws IOException {
        try {
            JSONObject manifest = new JSONObject(DownloadUtils.downloadString(LauncherPreferences.PREF_VERSION_REPOS));
            JSONArray versions = manifest.getJSONArray("versions");
            for (int i = 0; i < versions.length(); i++) {
                JSONObject version = versions.getJSONObject(i);
                if (MINECRAFT_VERSION.equals(version.getString("id"))) {
                    return version.getString("url");
                }
            }
        } catch (JSONException e) {
            throw new IOException("Nao foi possivel ler o manifest de versoes do Minecraft.", e);
        }
        throw new IOException("Minecraft " + MINECRAFT_VERSION + " nao foi encontrado no manifest oficial.");
    }

    private static void ensureFabricLoader(ModLoader modLoader) throws IOException {
        ensureMinecraftVersionJson();
        File versionJson = new File(Tools.DIR_HOME_VERSION + "/" + modLoader.getVersionId() + "/" + modLoader.getVersionId() + ".json");
        if (versionJson.isFile()) return;

        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, "Instalando Fabric " + FABRIC_LOADER_VERSION + "...");
        final IOException[] installError = new IOException[1];
        final boolean[] noData = new boolean[1];
        modLoader.getDownloadTask(new ModloaderDownloadListener() {
            @Override
            public void onDownloadFinished(File downloadedFile) {
            }

            @Override
            public void onDataNotAvailable() {
                noData[0] = true;
            }

            @Override
            public void onDownloadError(Exception e) {
                installError[0] = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }).run();

        if (installError[0] != null) throw installError[0];
        if (noData[0]) throw new IOException("O metadata do Fabric nao esta disponivel.");
        if (!versionJson.isFile()) {
            throw new IOException("O Fabric foi baixado, mas o JSON da versao nao foi criado.");
        }
    }

    private static String getFabricVersionId() {
        return "fabric-loader-" + FABRIC_LOADER_VERSION + "-" + MINECRAFT_VERSION;
    }
}
