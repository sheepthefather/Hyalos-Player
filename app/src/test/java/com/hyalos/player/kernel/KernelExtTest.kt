package com.hyalos.player.kernel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.krystallos_ffi.KernelException

/**
 * The rule that decides whether an operation is worth repeating on a fresh
 * session.
 *
 * Getting it wrong in one direction hides real errors behind a pointless retry;
 * getting it wrong in the other leaves the user staring at a retry button that
 * would have worked — which is the bug this exists to fix.
 */
class KernelExtTest {

    @Test
    fun `answers about the path are not worth repeating`() {
        // Reconnecting does not make a missing file appear.
        for (answer in listOf(
            KernelException.NotFound("/a"),
            KernelException.PermissionDenied("/a"),
            KernelException.AlreadyExists("/a"),
            KernelException.NotADirectory("/a"),
            KernelException.IsADirectory("/a"),
            KernelException.DirectoryNotEmpty("/a"),
            KernelException.InvalidPath("/a", "why"),
            KernelException.Unsupported("op"),
        )) {
            assertTrue("${answer::class.simpleName} describes the path", answer.describesThePath)
        }
    }

    @Test
    fun `anything that might be the connection is worth repeating`() {
        for (symptom in listOf(
            KernelException.ConnectionLost("gone"),
            KernelException.Auth("bad password"),
            // The one that matters: a socket the server closed reaches the app
            // as this, because libsmb2's directory API reports failure with a
            // null pointer and no return code to classify.
            KernelException.Backend("smb2_service failed with : smb2_service: POLLHUP, socket error."),
        )) {
            assertFalse("${symptom::class.simpleName} may be the connection", symptom.describesThePath)
        }
    }
}
