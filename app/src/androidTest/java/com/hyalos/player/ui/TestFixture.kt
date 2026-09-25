package com.hyalos.player.ui

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Servers and playlists written where the app will find them.
 *
 * The app has no seam for injecting a container — `AppContainer` is built in
 * `HyalosApp.onCreate` — so a test that wants a list to look at has to put one
 * on disk. That works because neither store is read until a screen asks for it,
 * and the screens are launched by the test rule, well after these bytes land.
 *
 * Two servers, not one. DataStore caches what it reads for the life of the
 * process, so whatever is on disk at the first read is what every test in the
 * run sees — a test that mutates a playlist would otherwise decide, by running
 * order, whether the others find anything. The scratch server is the one tests
 * are allowed to empty.
 *
 * Both test classes seed the same bytes, so which of them runs first does not
 * matter.
 */
object TestFixture {
    const val LIBRARY_ID = "00000000-0000-0000-0000-0000000000a1"
    const val LIBRARY_NAME = "TestNAS"
    const val SCRATCH_ID = "00000000-0000-0000-0000-0000000000b2"
    const val SCRATCH_NAME = "Scratch"

    const val FIRST_FILM = "Alpha.mkv"
    const val SECOND_FILM = "Beta.mkv"
    const val THIRD_FILM = "Gamma.mkv"
    const val SCRATCH_FILM = "Only.mkv"
    const val SCRATCH_OTHER = "Extra.mkv"

    /** The settings gear's description, which only the servers tab has. */
    const val SETTINGS = "设置"
    const val SELECTION_COUNT = "已选 1 项"
    const val EMPTY_PLAYLIST = "播放列表是空的"
    const val THERE_ARE_ENTRIES = "已加入视频"
    const val THERE_ARE_NONE = "还没有加入视频"

    private val SERVERS = """
        {"servers":[
          {"id":"$LIBRARY_ID","name":"$LIBRARY_NAME","host":"127.0.0.1","share":"media","startPath":"/"},
          {"id":"$SCRATCH_ID","name":"$SCRATCH_NAME","host":"127.0.0.1","share":"media","startPath":"/"}
        ]}
    """.trimIndent()

    private val PLAYLISTS = """
        {"byServer":{
          "$LIBRARY_ID":["/movies/$FIRST_FILM","/movies/$SECOND_FILM","/series/$THIRD_FILM"],
          "$SCRATCH_ID":["/movies/$SCRATCH_FILM","/series/$SCRATCH_OTHER"]
        }}
    """.trimIndent()

    fun seed() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "datastore")
        dir.mkdirs()
        File(dir, "servers.json").writeText(SERVERS)
        File(dir, "playlists.json").writeText(PLAYLISTS)
    }
}
