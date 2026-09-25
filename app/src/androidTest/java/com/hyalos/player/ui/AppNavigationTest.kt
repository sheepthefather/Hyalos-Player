package com.hyalos.player.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hyalos.player.MainActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shape of the app: two tabs, and what back does at the bottom of each.
 *
 * Written against what the screens say rather than against coordinates — the
 * taps this replaces were `input tap 540 396`, and a layout change would have
 * made them quietly hit something else.
 *
 * Method names are camelCase rather than backticked sentences, as the JVM tests
 * use. This one is dexed, and DEX below version 040 — which is what `minSdk 29`
 * produces — refuses spaces in a method name.
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theBarIsOnBothTabRoots() {
        // The server list's own title is also "服务器", so the tab is told apart
        // by being the one that can be pressed.
        rule.onNode(hasText("服务器") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("播放列表") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun switchingTabsKeepsBothOfThem() {
        goToPlaylistTab()
        rule.onNodeWithText(TestFixture.SCRATCH_NAME).assertIsDisplayed()

        goToServersTab()
        rule.onNodeWithContentDescription(TestFixture.SETTINGS).assertIsDisplayed()
    }

    @Test
    fun backAtTheSecondTabsRootLandsOnTheFirst() {
        goToPlaylistTab()
        rule.onNodeWithText(TestFixture.SCRATCH_NAME).assertIsDisplayed()

        pressBack()

        // Still an app: had back closed it, or popped the last entry, there
        // would be no server list here and no gear.
        rule.onNodeWithContentDescription(TestFixture.SETTINGS).assertIsDisplayed()
    }

    @Test
    fun settingsCanBeWalkedIntoAndBackOutOf() {
        rule.onNodeWithContentDescription(TestFixture.SETTINGS).performClick()
        rule.onNodeWithText("存储").assertIsDisplayed()

        rule.onNodeWithText("播放").performClick()
        rule.onNodeWithText("自动播放下一个").assertIsDisplayed()

        pressBack()
        rule.onNodeWithText("存储").assertIsDisplayed()

        pressBack()
        rule.onNodeWithContentDescription(TestFixture.SETTINGS).assertIsDisplayed()
    }

    private fun goToPlaylistTab() =
        rule.onNode(hasText("播放列表") and hasClickAction()).performClick()

    private fun goToServersTab() =
        rule.onNode(hasText("服务器") and hasClickAction()).performClick()

    /** The system back button, which is what the toolbar arrow is a second route to. */
    private fun pressBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun seed() = TestFixture.seed()
    }
}
