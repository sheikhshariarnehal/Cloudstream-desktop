package com.lagradost.cloudstream.desktop.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.jar.JarFile

@Serializable
data class RepoManifest(
    val name: String,
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList()
)

@Serializable
data class PluginManifest(
    val name: String,
    val internalName: String? = null,
    val description: String? = null,
    val url: String? = null,
    val jarUrl: String? = null,
    val version: Int = 1
)

object DesktopPluginLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _registeredApis = MutableStateFlow<List<MainAPI>>(emptyList())
    val registeredApis: StateFlow<List<MainAPI>> = _registeredApis.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val pluginsDir: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        val dir = File(userHome, ".cloudstream_desktop/plugins")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val logFile: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        File(userHome, ".cloudstream_desktop/plugin_debug.log")
    }

    private fun log(msg: String) {
        println("[PluginLoader] $msg")
        try {
            logFile.appendText("$msg\n")
        } catch (_: Exception) {}
    }

    fun registerBuiltInApis(apis: List<MainAPI>) {
        apis.forEach { api ->
            APIHolder.allProviders.add(api)
            APIHolder.addPluginMapping(api)
        }
        syncApisFromHolder()
    }

    fun registerNehalProviders() {
        try {
            disableBundledProviderTelemetry()
            val providers = listOf<MainAPI>(
                com.redowan.CircleFtpProvider(),
                com.redowan.BdixICCFtpProvider(),
                com.nehal.ctgmovies.CTGMoviesProvider(),
                com.nehal.cineplexbd.CineplexBDProvider(),
                com.nehal.dhakaflix.DhakaFlixProvider(),
                com.nehal.dhakaflixbdix.DhakaFlixBDIXProvider(),
                com.nehal.discoveryftp.DiscoveryFTPProvider(),
                com.nehal.ftpbd.FTPBDProvider(),
                com.nehal.fmftp.FmFtpProvider(),
                com.nehal.jellyfinbd.JellyfinBDProvider(),
                com.nehal.showtimebd.ShowTimeBDProvider(),
                com.nehal.vegamovies.VegaMoviesProvider(),
                com.allwish.AllWish(),
                com.aniwatch.Aniwatch(),
                com.cncverse.MovieBoxProviderIN()
            )
            registerBuiltInApis(providers)

            // Register native custom extractors for Anime and movies
            try {
                com.lagradost.cloudstream3.utils.extractorApis.add(com.aniwatch.MegaPlay())
                com.lagradost.cloudstream3.utils.extractorApis.add(com.allwish.MegaPlay())
                com.lagradost.cloudstream3.utils.extractorApis.add(com.allwish.Zen())
                com.lagradost.cloudstream3.utils.extractorApis.add(com.allwish.Vidwish())
                com.lagradost.cloudstream3.utils.extractorApis.add(com.nehal.vegamovies.VCloud())
            } catch (_: Throwable) {}

            log("Registered ${providers.size} native BDIX providers & extractors successfully!")
        } catch (e: Throwable) {
            log("Error registering native BDIX providers: ${e.stackTraceToString()}")
        }
    }

    fun syncApisFromHolder() {
        val all = mutableListOf<MainAPI>()
        all.addAll(APIHolder.apis)
        all.addAll(APIHolder.allProviders)
        _registeredApis.value = all.distinctBy { it.name }
        log("Synced APIs. Total registered count: ${_registeredApis.value.size}")
    }

    fun getApiByName(name: String): MainAPI? {
        return _registeredApis.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    suspend fun loadRepository(repoUrl: String): List<PluginManifest> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        log("Starting repo download: $repoUrl")
        val downloadedPlugins = mutableListOf<PluginManifest>()
        try {
            val repoText = app.get(repoUrl).text
            val repo = json.decodeFromString<RepoManifest>(repoText)

            for (pluginListUrl in repo.pluginLists) {
                try {
                    val pluginsJsonText = app.get(pluginListUrl).text
                    val manifests = json.decodeFromString<List<PluginManifest>>(pluginsJsonText)

                    for (manifest in manifests) {
                        val jarTargetUrl = manifest.jarUrl ?: manifest.url
                        if (jarTargetUrl != null) {
                            val fileName = "${manifest.internalName ?: manifest.name}.jar"
                            val targetFile = File(pluginsDir, fileName)

                            val bytes = app.get(jarTargetUrl).body.bytes()
                            if (bytes.size > 200) {
                                targetFile.writeBytes(bytes)
                                loadJarFile(targetFile)
                                downloadedPlugins.add(manifest)
                            }
                        }
                    }
                } catch (e: Exception) {
                    log("Error fetching plugin list $pluginListUrl: ${e.stackTraceToString()}")
                }
            }
        } catch (e: Exception) {
            log("Error fetching repo $repoUrl: ${e.stackTraceToString()}")
        } finally {
            syncApisFromHolder()
            _isSyncing.value = false
        }
        downloadedPlugins
    }

    fun loadAllLocalPlugins() {
        val files = pluginsDir.listFiles { file -> file.extension == "jar" && file.length() > 200 } ?: return
        log("Loading ${files.size} local plugin JAR files from ${pluginsDir.absolutePath}")
        for (file in files) {
            try {
                loadJarFile(file)
            } catch (e: Exception) {
                log("Error loading file ${file.name}: ${e.stackTraceToString()}")
            }
        }
        syncApisFromHolder()
    }

    private fun loadJarFile(jarFile: File) {
        if (!jarFile.exists() || jarFile.length() < 200) return
        log("Scanning JAR: ${jarFile.name} (${jarFile.length()} bytes)")
        try {
            val classLoader = URLClassLoader(
                arrayOf(jarFile.toURI().toURL()),
                DesktopPluginLoader::class.java.classLoader
            )
            val jar = JarFile(jarFile)

            // 1. Try reading manifest.json if present inside JAR
            val manifestEntry = jar.getJarEntry("manifest.json")
            if (manifestEntry != null) {
                try {
                    val manifestText = jar.getInputStream(manifestEntry).bufferedReader().readText()
                    log("Found manifest.json in ${jarFile.name}: $manifestText")
                    val manifest = json.decodeFromString<BasePlugin.Manifest>(manifestText)
                    val pluginClassName = manifest.pluginClassName
                    if (!pluginClassName.isNullOrEmpty()) {
                        instantiateAndLoadPluginClass(classLoader, pluginClassName!!, jarFile)
                        return
                    }
                } catch (t: Throwable) {
                    log("Error reading manifest in ${jarFile.name}: ${t.stackTraceToString()}")
                }
            }

            // 2. Fallback scan all entries
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(".class") && !entry.name.contains("$")) {
                    val className = entry.name.removeSuffix(".class").replace('/', '.')
                    try {
                        val clazz = classLoader.loadClass(className)
                        if (Modifier.isAbstract(clazz.modifiers)) continue

                        val isMainApi = MainAPI::class.java.isAssignableFrom(clazz) ||
                                clazz.superclass?.name == "com.lagradost.cloudstream3.MainAPI"

                        val isBasePlugin = BasePlugin::class.java.isAssignableFrom(clazz) ||
                                clazz.superclass?.name == "com.lagradost.cloudstream3.plugins.BasePlugin" ||
                                clazz.superclass?.name == "com.lagradost.cloudstream3.plugins.Plugin"

                        if (isMainApi) {
                            log("Found MainAPI class in ${jarFile.name}: $className")
                            val apiInstance = clazz.getDeclaredConstructor().newInstance() as MainAPI
                            APIHolder.allProviders.add(apiInstance)
                            APIHolder.addPluginMapping(apiInstance)
                            log("Successfully registered MainAPI: ${apiInstance.name}")
                        } else if (isBasePlugin) {
                            instantiateAndLoadPluginClass(classLoader, className, jarFile)
                        }
                    } catch (t: Throwable) {
                        // Ignore non-plugin classes
                    }
                }
            }
            syncApisFromHolder()
        } catch (e: Exception) {
            log("Failed to process JAR ${jarFile.name}: ${e.stackTraceToString()}")
        }
    }

    private fun instantiateAndLoadPluginClass(classLoader: ClassLoader, className: String, jarFile: File) {
        log("Attempting to load plugin class: $className from ${jarFile.name}")
        try {
            val clazz = classLoader.loadClass(className)
            if (Modifier.isAbstract(clazz.modifiers)) return

            val pluginInstance = clazz.getDeclaredConstructor().newInstance() as BasePlugin
            pluginInstance.filename = jarFile.absolutePath
            log("Instantiated Plugin class: $className")

            val loadMethods = clazz.methods.filter { it.name == "load" }
            for (method in loadMethods) {
                try {
                    if (method.parameterTypes.isEmpty()) {
                        method.invoke(pluginInstance)
                        log("Called load() on $className")
                    } else if (method.parameterTypes.size == 1) {
                        method.invoke(pluginInstance, arrayOf<Any?>(null))
                        log("Called load(null) on $className")
                    }
                } catch (t: Throwable) {
                    log("Error calling load() on $className: ${t.stackTraceToString()}")
                }
            }
        } catch (t: Throwable) {
            log("Error instantiating $className: ${t.stackTraceToString()}")
        }
    }
}
