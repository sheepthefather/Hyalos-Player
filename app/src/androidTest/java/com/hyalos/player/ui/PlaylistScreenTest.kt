package com.hyalos.player.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hyalos.player.MainActivity
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The playlist screens, which read a local file and so need no server — which is
 * what makes them testable here at all. The browser cannot be: it needs a share
 * to list.
 *
 * Method names are camelCase because this is dexed; see [AppNavigationTest].
 *
 * The mutating test uses the scratch server, so that whatever order the tests
 * run in it cannot empty the list the read-only ones are looking at.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun entriesShowInTheOrderTheyWereAdded() {
        openPlaylist(TestFixture.LIBRARY_NAME)
        showAsList()

        val first = topOf(TestFixture.FIRST_FILM)
        val second = topOf(TestFixture.SECOND_FILM)
        val third = topOf(TestFixture.THIRD_FILM)
        assertTrue("the playlist is not in the order it was added", first < second && second < third)

        // Two entries live in /movies and one in /series. The folder line — which
        // only the list draws — is the whole reason a playlist is not a folder.
        rule.onAllNodesWithText("/movies").assertCountEquals(2)
    }

    private fun topOf(text: String): Float =
        rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /**
     * The layout is one stored preference shared by the browser and the playlist,
     * and it survives between runs — so this puts it where the test needs it
     * rather than assuming a fresh install.
     */
    private fun showAsList() {
        val toList = rule.onAllNodesWithContentDescription("切换到列表视图")
        if (toList.fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithContentDescription("切换到列表视图").performClick()
        }
    }

    /**
     * The bug this file exists for. Back used to climb out of the screen and take
     * the selection with it, which reads as having lost what was selected.
     */
    @Test
    fun backLeavesSelectionModeRatherThanTheScreen() {
        openPlaylist(TestFixture.LIBRARY_NAME)

        rule.onNodeWithText(TestFixture.FIRST_FILM).performTouchInput { longClick() }
        rule.onNodeWithText(TestFixture.SELECTION_COUNT).assertIsDisplayed()

        pressBack()

        rule.onNodeWithText(TestFixture.SELECTION_COUNT).assertDoesNotExist()
        // The back was spent leaving selection mode, so the list is still here.
        rule.onNodeWithText(TestFixture.FIRST_FILM).assertIsDisplayed()
    }

    @Test
    fun selectAllThenRemoveEmptiesTheList() {
        openPlaylist(TestFixture.SCRATCH_NAME)

        rule.onNodeWithText(TestFixture.SCRATCH_FILM).performTouchInput { longClick() }
        rule.onNodeWithContentDescription("全选").performClick()
        // One press, and the count is every entry rather than the one pressed.
        rule.onNodeWithText("已选 2 项").assertIsDisplayed()

        rule.onNodeWithContentDescription("移出播放列表").performClick()
        // The dialog repeats that label; its confirm button is the pressable one.
        rule.onNode(hasText("移出播放列表") and hasClickAction()).performClick()

        rule.onNodeWithText(TestFixture.EMPTY_PLAYLIST).assertIsDisplayed()
    }

    private fun openPlaylist(server: String) {
        rule.onNode(hasText("播放列表") and hasClickAction()).performClick()
        rule.onNodeWithText(server).performClick()
    }

    private fun pressBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun seed() = TestFixture.seed()
    }
}
