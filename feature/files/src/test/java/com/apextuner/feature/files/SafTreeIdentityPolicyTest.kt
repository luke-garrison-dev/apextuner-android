package com.apextuner.feature.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafTreeIdentityPolicyTest {
    @Test
    fun exactTreeIdentityMatches() {
        assertTrue(
            SafTreeIdentityPolicy.same(
                SafTreeIdentity("com.android.externalstorage.documents", "primary:Documents"),
                SafTreeIdentity("com.android.externalstorage.documents", "primary:Documents"),
            ),
        )
    }

    @Test
    fun foldersFromSameProviderDoNotMatch() {
        assertFalse(
            SafTreeIdentityPolicy.same(
                SafTreeIdentity("com.android.externalstorage.documents", "primary:Documents"),
                SafTreeIdentity("com.android.externalstorage.documents", "primary:Download"),
            ),
        )
    }

    @Test
    fun sameDocumentIdFromDifferentProvidersDoesNotMatch() {
        assertFalse(
            SafTreeIdentityPolicy.same(
                SafTreeIdentity("provider.one", "root:Documents"),
                SafTreeIdentity("provider.two", "root:Documents"),
            ),
        )
    }
}
